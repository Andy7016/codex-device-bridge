package io.github.vvcchh0.devicebridge.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Encrypted durable handshake journal. Never stores private-key bytes. */
@ConsistentCopyVisibility
data class PendingPairing internal constructor(
    val qrText: String,
    val keyAlias: String,
    val requestId: String,
    val phoneName: String,
    val clientCertificatePem: String,
    val clientCertificateSha256: String,
    val challenge: String? = null,
    val confirmationCode: String? = null,
    val expiresAt: Long? = null,
    val signatureBase64: String? = null,
    val proofSubmitted: Boolean = false,
) {
    override fun toString(): String =
        "PendingPairing(requestId=$requestId, keyAlias=$keyAlias, challengePresent=${challenge != null}, proofSubmitted=$proofSubmitted, qrText=<redacted>, signature=<redacted>)"
}

class PairingStore(context: Context) {
    private val vault = EncryptedVaultFile(context, PAIRING_FILE)

    fun load(): PendingPairing? = synchronized(LOCK) {
        if (!vault.exists()) return@synchronized null
        val bytes = try {
            vault.read(MAX_BYTES)
        } catch (e: VaultException) {
            throw PairingException(e.code)
        }
        try {
            parse(bytes.toString(Charsets.UTF_8))
        } catch (e: PairingException) {
            throw e
        } catch (_: Exception) {
            throw PairingException("pending_pairing_corrupt")
        } finally {
            bytes.fill(0)
        }
    }

    fun save(pending: PendingPairing) = synchronized(LOCK) {
        validate(pending)
        val bytes = encode(pending).toByteArray(Charsets.UTF_8)
        try {
            vault.write(bytes, MAX_BYTES)
        } catch (e: VaultException) {
            throw PairingException(e.code)
        } finally {
            bytes.fill(0)
        }
    }

    fun clear() = synchronized(LOCK) {
        try {
            vault.delete()
        } catch (e: VaultException) {
            throw PairingException(e.code)
        }
    }

    private fun parse(json: String): PendingPairing {
        val root = try { JSONObject(json) } catch (_: JSONException) { throw PairingException("pending_pairing_corrupt") }
        if (root.keys().asSequence().toSet() != FIELDS || root.length() != FIELDS.size || root.optInt("schemaVersion", -1) != 1) {
            throw PairingException("pending_pairing_corrupt")
        }
        val pending = PendingPairing(
            qrText = requiredString(root, "qrText", PairingQr.PREFIX.length + 1, MAX_QR_CHARS),
            keyAlias = requiredString(root, "keyAlias", 1, MAX_ALIAS_CHARS),
            requestId = requiredString(root, "requestId", 36, 36),
            phoneName = requiredString(root, "phoneName", 1, 80),
            clientCertificatePem = requiredString(root, "clientCertificatePem", 1, PairingQr.MAX_DECODED_BYTES),
            clientCertificateSha256 = requiredString(root, "clientCertificateSha256", 64, 64),
            challenge = nullableString(root.opt("challenge"), 64),
            confirmationCode = nullableString(root.opt("confirmationCode"), 6),
            expiresAt = nullableLong(root.opt("expiresAt")),
            signatureBase64 = nullableString(root.opt("signatureBase64"), MAX_SIGNATURE_CHARS),
            proofSubmitted = root.opt("proofSubmitted") as? Boolean ?: throw PairingException("pending_pairing_corrupt"),
        )
        try {
            validate(pending)
        } catch (_: PairingException) {
            throw PairingException("pending_pairing_corrupt")
        }
        return pending
    }

    private fun encode(pending: PendingPairing): String = JSONObject()
        .put("schemaVersion", 1)
        .put("qrText", pending.qrText)
        .put("keyAlias", pending.keyAlias)
        .put("requestId", pending.requestId)
        .put("phoneName", pending.phoneName)
        .put("clientCertificatePem", pending.clientCertificatePem)
        .put("clientCertificateSha256", pending.clientCertificateSha256)
        .put("challenge", pending.challenge ?: JSONObject.NULL)
        .put("confirmationCode", pending.confirmationCode ?: JSONObject.NULL)
        .put("expiresAt", pending.expiresAt ?: JSONObject.NULL)
        .put("signatureBase64", pending.signatureBase64 ?: JSONObject.NULL)
        .put("proofSubmitted", pending.proofSubmitted)
        .toString()

    private fun validate(pending: PendingPairing) {
        val invite = PairingQr.parseForResume(pending.qrText)
        try {
            UUID.fromString(pending.requestId)
        } catch (_: Exception) {
            throw PairingException("pending_pairing_invalid")
        }
        if (pending.keyAlias.length !in 1..MAX_ALIAS_CHARS || !pending.keyAlias.startsWith(KEY_ALIAS_PREFIX) ||
            pending.phoneName.length !in 1..80 || pending.phoneName.any { Character.isISOControl(it) } ||
            pending.clientCertificatePem.length !in 1..PairingQr.MAX_DECODED_BYTES ||
            !SHA256_PATTERN.matches(pending.clientCertificateSha256)
        ) throw PairingException("pending_pairing_invalid")
        val certificate = parseX509Pem(pending.clientCertificatePem)
        if (sha256Hex(certificate.encoded) != pending.clientCertificateSha256 || invite.inviteId.isEmpty()) {
            throw PairingException("pending_pairing_invalid")
        }
        if (pending.challenge != null && !SHA256_PATTERN.matches(pending.challenge)) throw PairingException("pending_pairing_invalid")
        if (pending.confirmationCode != null && !CODE_PATTERN.matches(pending.confirmationCode)) throw PairingException("pending_pairing_invalid")
        if (pending.expiresAt != null && pending.expiresAt <= 0) throw PairingException("pending_pairing_invalid")
        if (pending.signatureBase64 != null) {
            if (pending.signatureBase64.length !in 1..MAX_SIGNATURE_CHARS) throw PairingException("pending_pairing_invalid")
            try {
                if (java.util.Base64.getDecoder().decode(pending.signatureBase64).size !in 8..128) throw PairingException("pending_pairing_invalid")
            } catch (_: IllegalArgumentException) {
                throw PairingException("pending_pairing_invalid")
            }
        }
        if ((pending.challenge == null) != (pending.confirmationCode == null) ||
            (pending.challenge == null) != (pending.expiresAt == null) ||
            pending.proofSubmitted && pending.signatureBase64 == null
        ) throw PairingException("pending_pairing_invalid")
    }

    private fun requiredString(root: JSONObject, name: String, min: Int, max: Int): String {
        val value = root.opt(name) as? String ?: throw PairingException("pending_pairing_corrupt")
        if (value.length !in min..max) throw PairingException("pending_pairing_corrupt")
        return value
    }

    private fun nullableString(value: Any?, max: Int): String? = when (value) {
        JSONObject.NULL -> null
        is String -> value.takeIf { it.length in 1..max } ?: throw PairingException("pending_pairing_corrupt")
        else -> throw PairingException("pending_pairing_corrupt")
    }

    private fun nullableLong(value: Any?): Long? = when (value) {
        JSONObject.NULL -> null
        is Number -> value.toString().toLongOrNull() ?: throw PairingException("pending_pairing_corrupt")
        else -> throw PairingException("pending_pairing_corrupt")
    }

    private companion object {
        const val PAIRING_FILE = "pending-pairing.v1.enc"
        const val MAX_BYTES = 48 * 1024
        const val MAX_QR_CHARS = 11000
        const val MAX_ALIAS_CHARS = 96
        const val MAX_SIGNATURE_CHARS = 256
        const val KEY_ALIAS_PREFIX = "devicebridge.phone.v2."
        val LOCK = Any()
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val CODE_PATTERN = Regex("[0-9]{6}")
        val FIELDS = setOf(
            "schemaVersion", "qrText", "keyAlias", "requestId", "phoneName", "clientCertificatePem",
            "clientCertificateSha256", "challenge", "confirmationCode", "expiresAt", "signatureBase64", "proofSubmitted",
        )
    }
}
