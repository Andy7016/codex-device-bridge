package io.github.vvcchh0.devicebridge.data

import android.content.Context
import org.json.JSONObject

internal interface PairingRpc {
    fun requestPairing(invite: PairingInvite, method: String, params: JSONObject): JSONObject
}

enum class PairingPhase { STARTING, WAITING_FOR_APPROVAL, APPROVED, DENIED }

data class PairingProgress(
    val phase: PairingPhase,
    val hostName: String,
    val confirmationCode: String? = null,
    val expiresAt: Long? = null,
    val requestId: String? = null,
    val profile: ConnectionProfile? = null,
) {
    override fun toString(): String =
        "PairingProgress(phase=$phase, hostName=$hostName, confirmationCode=$confirmationCode, expiresAt=$expiresAt, requestId=$requestId)"
}

/** Resumable, one-invitation pairing state machine. Pairing RPCs never present the new client identity. */
class PairingRepository internal constructor(
    context: Context,
    private val rpc: PairingRpc = BridgeTransport(),
    private val identities: AndroidDeviceIdentity = AndroidDeviceIdentity(),
) {
    private val pairingStore = PairingStore(context)
    private val profiles = ProfileStore(context)

    constructor(context: Context) : this(context, BridgeTransport(), AndroidDeviceIdentity())

    fun pendingProgress(): PairingProgress? {
        val pending = pairingStore.load() ?: return null
        val invite = PairingQr.parseForResume(pending.qrText)
        return PairingProgress(
            phase = if (pending.challenge == null) PairingPhase.STARTING else PairingPhase.WAITING_FOR_APPROVAL,
            hostName = invite.displayName,
            confirmationCode = pending.confirmationCode,
            expiresAt = pending.expiresAt,
            requestId = pending.requestId,
        )
    }

    /** Persists QR, alias and requestId before the first pairing RPC, so a lost begin ACK reuses them. */
    fun startOrResume(qrText: String, phoneName: String = DEFAULT_PHONE_NAME): PairingProgress {
        val invite = PairingQr.parse(qrText)
        val normalizedPhoneName = validatePhoneName(phoneName)
        var pending = pairingStore.load()
        if (pending == null) {
            val identity = identities.create()
            pending = PendingPairing(
                qrText = qrText,
                keyAlias = identity.alias,
                requestId = PairingCrypto.requestId(),
                phoneName = normalizedPhoneName,
                clientCertificatePem = identity.certificatePem,
                clientCertificateSha256 = identity.certificateSha256,
            )
            pairingStore.save(pending)
        } else if (pending.qrText != qrText) {
            throw PairingException("pending_pairing_exists")
        }
        return advance(pending, invite)
    }

    /** Replays the exact same proof safely; it never creates a new key or request ID. */
    fun checkStatus(): PairingProgress {
        var pending = pairingStore.load() ?: throw PairingException("no_pending_pairing")
        val invite = PairingQr.parseForResume(pending.qrText)
        if (pending.challenge == null || pending.signatureBase64 == null) {
            return advance(pending, invite)
        }
        verifyStoredIdentity(pending)
        pending = pending.copy(proofSubmitted = true)
        pairingStore.save(pending)
        // A previous prove may have failed before the host received it. Repeating the exact proof
        // lets the host verify and reveal the request without relying on a status lookup that could
        // otherwise observe only the still-private challenge.
        val result = rpc.requestPairing(invite, "pair.prove", proofParams(invite, pending))
        return consumeStatus(invite, pending, result)
    }

    /** Clears pending state; retained keys may belong to a previously committed profile/outbox. */
    fun discardPending() {
        pairingStore.clear()
    }

    private fun advance(pendingValue: PendingPairing, invite: PairingInvite): PairingProgress {
        var pending = pendingValue
        verifyStoredIdentity(pending)
        if (pending.challenge == null) {
            val response = rpc.requestPairing(
                invite,
                "pair.begin",
                JSONObject()
                    .put("inviteId", invite.inviteId)
                    .put("inviteSecret", invite.secret())
                    .put("requestId", pending.requestId)
                    .put("phoneName", pending.phoneName)
                    .put("clientCertificatePem", pending.clientCertificatePem),
            )
            if (response.keys().asSequence().toSet() != BEGIN_RESPONSE_FIELDS || response.optString("state") != "challenge") {
                throw PairingException("pairing_response_invalid")
            }
            val challenge = response.optString("challenge")
            val code = response.optString("confirmationCode")
            val expiresAt = (response.opt("expiresAt") as? Number)?.toString()?.toLongOrNull()
                ?: throw PairingException("pairing_response_invalid")
            if (!SHA256_PATTERN.matches(challenge) || !CODE_PATTERN.matches(code) || expiresAt <= 0) {
                throw PairingException("pairing_response_invalid")
            }
            pending = pending.copy(challenge = challenge, confirmationCode = code, expiresAt = expiresAt)
            pairingStore.save(pending) // Challenge is durable before the signature/prove request.
        }

        if (pending.signatureBase64 == null) {
            val proof = PairingCrypto.proofBytes(
                invite.inviteId,
                pending.requestId,
                invite.hostId,
                pending.challenge!!,
                pending.clientCertificateSha256,
            )
            try {
                if (PairingCrypto.confirmationCode(proof) != pending.confirmationCode) {
                    throw PairingException("confirmation_code_mismatch")
                }
                val signature = identities.sign(pending.keyAlias, proof)
                pending = pending.copy(signatureBase64 = signature)
                pairingStore.save(pending) // Signature is durable before prove; a lost ACK reuses this exact proof.
            } finally {
                proof.fill(0)
            }
        }

        pending = pending.copy(proofSubmitted = true)
        pairingStore.save(pending)
        val result = rpc.requestPairing(invite, "pair.prove", proofParams(invite, pending))
        return consumeStatus(invite, pending, result)
    }

    private fun consumeStatus(invite: PairingInvite, pending: PendingPairing, result: JSONObject): PairingProgress {
        if (result.keys().asSequence().toSet() != STATUS_RESPONSE_FIELDS) throw PairingException("pairing_response_invalid")
        val state = result.optString("state")
        val code = result.optString("confirmationCode")
        val expiresAt = (result.opt("expiresAt") as? Number)?.toString()?.toLongOrNull()
            ?: throw PairingException("pairing_response_invalid")
        if (state !in PAIRING_STATES || !CODE_PATTERN.matches(code) || code != pending.confirmationCode || expiresAt <= 0) {
            throw PairingException("pairing_response_invalid")
        }
        return when (state) {
            "pending" -> PairingProgress(PairingPhase.WAITING_FOR_APPROVAL, invite.displayName, code, expiresAt, pending.requestId)
            "denied" -> {
                pairingStore.clear()
                PairingProgress(PairingPhase.DENIED, invite.displayName, code, expiresAt, pending.requestId)
            }
            "approved" -> {
                val profile = profiles.completePairing(invite, pending.keyAlias, pending.clientCertificateSha256)
                pairingStore.clear()
                PairingProgress(PairingPhase.APPROVED, invite.displayName, code, expiresAt, pending.requestId, profile)
            }
            else -> throw PairingException("pairing_response_invalid")
        }
    }

    private fun verifyStoredIdentity(pending: PendingPairing): DeviceIdentity {
        val identity = identities.load(pending.keyAlias)
        if (identity.certificateSha256 != pending.clientCertificateSha256 ||
            identity.certificatePem != pending.clientCertificatePem
        ) throw PairingException("device_identity_mismatch")
        return identity
    }

    private fun proofParams(invite: PairingInvite, pending: PendingPairing): JSONObject {
        val signature = pending.signatureBase64 ?: throw PairingException("pairing_proof_missing")
        return JSONObject()
            .put("inviteId", invite.inviteId)
            .put("inviteSecret", invite.secret())
            .put("requestId", pending.requestId)
            .put("signature", signature)
    }

    private fun validatePhoneName(value: String): String {
        if (value.length !in 1..80 || value.any { Character.isISOControl(it) } || value != value.trim()) {
            throw PairingException("invalid_phone_name")
        }
        return value
    }

    private companion object {
        const val DEFAULT_PHONE_NAME = "Android device"
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val CODE_PATTERN = Regex("[0-9]{6}")
        val PAIRING_STATES = setOf("pending", "approved", "denied")
        val BEGIN_RESPONSE_FIELDS = setOf("state", "challenge", "confirmationCode", "expiresAt")
        val STATUS_RESPONSE_FIELDS = setOf("state", "confirmationCode", "expiresAt")
    }
}
