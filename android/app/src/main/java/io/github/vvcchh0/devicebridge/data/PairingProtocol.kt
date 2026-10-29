package io.github.vvcchh0.devicebridge.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.security.auth.x500.X500Principal
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import java.net.Socket
import javax.net.ssl.SSLEngine

data class RelayEndpoint(val host: String, val port: Int, val certificatePem: String?)
data class DirectEndpoint(val host: String, val port: Int)

/** Invitation secrets are kept out of generated string output. */
data class PairingInvite(
    val displayName: String,
    val hostId: String,
    val serverName: String,
    val serverCertificatePem: String,
    val serverCertificateSha256: String,
    val relay: RelayEndpoint,
    val directEndpoints: List<DirectEndpoint>,
    val inviteId: String,
    private val inviteSecret: String,
    val expiresAt: Long,
) {
    internal fun secret(): String = inviteSecret

    override fun toString(): String =
        "PairingInvite(displayName=$displayName, hostId=$hostId, serverName=$serverName, relay=${relay.host}:${relay.port}, directCount=${directEndpoints.size}, inviteId=$inviteId, expiresAt=$expiresAt, inviteSecret=<redacted>)"
}

object PairingQr {
    const val PREFIX = "dbpair:"
    const val MAX_DECODED_BYTES = 8192
    private const val MAX_ENCODED_CHARS = 10940
    private val HOST_ID = Regex("[0-9a-f]{64}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val INVITE_ID = Regex("[0-9a-f]{32}")
    private val INVITE_SECRET = Regex("[0-9a-f]{64}")

    fun parse(value: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): PairingInvite =
        parseInternal(value, nowEpochSeconds, allowExpired = false)

    internal fun parseForResume(value: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): PairingInvite =
        parseInternal(value, nowEpochSeconds, allowExpired = true)

    internal fun parseInternal(value: String, nowEpochSeconds: Long, allowExpired: Boolean): PairingInvite {
        if (!value.startsWith(PREFIX) || value.length > MAX_ENCODED_CHARS) throw PairingException("invalid_pairing_qr")
        val encoded = value.substring(PREFIX.length)
        if (encoded.isEmpty() || encoded.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_') }) {
            throw PairingException("invalid_pairing_qr")
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw PairingException("invalid_pairing_qr")
        }
        if (bytes.size > MAX_DECODED_BYTES) {
            bytes.fill(0)
            throw PairingException("invalid_pairing_qr")
        }
        val json = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            bytes.fill(0)
            throw PairingException("invalid_pairing_qr")
        } finally {
            bytes.fill(0)
        }
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw PairingException("invalid_pairing_qr")
        }
        if (root.keys().asSequence().toSet() != INVITE_KEYS) throw PairingException("invalid_pairing_qr")
        if (exactInt(root.opt("schemaVersion")) != 1) throw PairingException("unsupported_pairing_qr")

        val displayName = string(root, "displayName", 1, 80)
        if (displayName != displayName.trim() || displayName.any { Character.isISOControl(it) }) {
            throw PairingException("invalid_pairing_qr")
        }
        val hostId = string(root, "hostId", 64, 64)
        if (!HOST_ID.matches(hostId)) throw PairingException("invalid_pairing_qr")
        val serverName = string(root, "serverName", 1, 253)
        val expectedServerName = expectedServerName(hostId)
        if (serverName != expectedServerName) throw PairingException("invalid_pairing_qr")

        val serverPem = string(root, "serverCertificatePem", 1, MAX_DECODED_BYTES)
        val serverCertificate = parseX509Pem(serverPem, MAX_DECODED_BYTES)
        try {
            serverCertificate.checkValidity(Date(nowEpochSeconds * 1000))
        } catch (_: Exception) {
            throw PairingException("server_certificate_not_valid")
        }
        val key = serverCertificate.publicKey as? ECPublicKey ?: throw PairingException("invalid_host_certificate")
        if (!isP256(key.params)) throw PairingException("invalid_host_certificate")
        if (!MessageDigest.isEqual(sha256Hex(key.encoded).toByteArray(Charsets.US_ASCII), hostId.toByteArray(Charsets.US_ASCII))) {
            throw PairingException("host_identity_mismatch")
        }
        val certificatePin = string(root, "serverCertificateSha256", 64, 64)
        if (!SHA256.matches(certificatePin) ||
            !MessageDigest.isEqual(sha256Hex(serverCertificate.encoded).toByteArray(Charsets.US_ASCII), certificatePin.toByteArray(Charsets.US_ASCII))
        ) {
            throw PairingException("certificate_pin_mismatch")
        }

        val relayJson = root.opt("relay") as? JSONObject ?: throw PairingException("invalid_pairing_qr")
        if (relayJson.keys().asSequence().toSet() != RELAY_KEYS) throw PairingException("invalid_pairing_qr")
        val relayHost = normalizeHost(string(relayJson, "host", 1, 253))
        val relayPort = port(relayJson.opt("port"))
        val relayPemValue = relayJson.opt("certificatePem")
        val relayPem = when (relayPemValue) {
            JSONObject.NULL -> null
            is String -> relayPemValue.also {
                if (it.length !in 1..MAX_DECODED_BYTES) throw PairingException("invalid_pairing_qr")
                val certificate = parseX509Pem(it, MAX_DECODED_BYTES)
                try {
                    certificate.checkValidity(Date(nowEpochSeconds * 1000))
                } catch (_: Exception) {
                    throw PairingException("relay_certificate_not_valid")
                }
            }
            else -> throw PairingException("invalid_pairing_qr")
        }

        val directArray = root.opt("directEndpoints") as? org.json.JSONArray ?: throw PairingException("invalid_pairing_qr")
        if (directArray.length() !in 0..4) throw PairingException("invalid_pairing_qr")
        val direct = (0 until directArray.length()).map { index ->
            val item = directArray.opt(index) as? JSONObject ?: throw PairingException("invalid_pairing_qr")
            if (item.keys().asSequence().toSet() != DIRECT_KEYS) throw PairingException("invalid_pairing_qr")
            val host = directIp(string(item, "host", 1, 64))
            DirectEndpoint(host, port(item.opt("port")))
        }

        val inviteId = string(root, "inviteId", 32, 32)
        val inviteSecret = string(root, "inviteSecret", 64, 64)
        if (!INVITE_ID.matches(inviteId) || !INVITE_SECRET.matches(inviteSecret)) throw PairingException("invalid_pairing_qr")
        val expiresAt = exactLong(root.opt("expiresAt")) ?: throw PairingException("invalid_pairing_qr")
        if (expiresAt <= 0 || (!allowExpired && expiresAt <= nowEpochSeconds)) throw PairingException("pairing_invite_expired")

        return PairingInvite(
            displayName = displayName,
            hostId = hostId,
            serverName = serverName,
            serverCertificatePem = serverPem,
            serverCertificateSha256 = certificatePin,
            relay = RelayEndpoint(relayHost, relayPort, relayPem),
            directEndpoints = direct,
            inviteId = inviteId,
            inviteSecret = inviteSecret,
            expiresAt = expiresAt,
        )
    }

    fun encode(json: JSONObject): String {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_DECODED_BYTES) {
            bytes.fill(0)
            throw PairingException("invalid_pairing_qr")
        }
        return try {
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun expectedServerName(hostId: String): String =
        "device.${hostId.take(32)}.${hostId.drop(32)}.bridge.invalid"

    internal fun normalizeEndpointHost(value: String): String = normalizeHost(value)

    internal fun validateDirectHost(value: String): String = directIp(value)

    private fun directIp(value: String): String {
        if (value.contains('%') || value != value.trim()) throw PairingException("invalid_direct_endpoint")
        if (value.contains(':')) {
            val address = try {
                InetAddress.getByName(value)
            } catch (_: Exception) {
                throw PairingException("invalid_direct_endpoint")
            }
            if (address !is Inet6Address || !isPrivateIpv6(address.address)) throw PairingException("invalid_direct_endpoint")
            return value.lowercase(Locale.ROOT)
        }
        return parseIpv4(value, requirePrivate = true)
    }

    private fun parseIpv4(value: String, requirePrivate: Boolean): String {
        val parts = value.split('.')
        if (parts.size != 4) throw PairingException("invalid_direct_endpoint")
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.length > 1 && part.startsWith('0') || !part.all { it in '0'..'9' }) {
                throw PairingException("invalid_direct_endpoint")
            }
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: throw PairingException("invalid_direct_endpoint")
        }
        val first = octets[0]
        val second = octets[1]
        val privateOrLoopback = first == 10 || first == 127 || first == 192 && second == 168 ||
            first == 172 && second in 16..31
        if (requirePrivate && !privateOrLoopback) throw PairingException("invalid_direct_endpoint")
        return octets.joinToString(".")
    }

    private fun isPrivateIpv6(bytes: ByteArray): Boolean {
        val loopback = bytes.take(15).all { it == 0.toByte() } && bytes.last() == 1.toByte()
        val uniqueLocal = (bytes[0].toInt() and 0xfe) == 0xfc
        return loopback || uniqueLocal
    }

    private fun normalizeHost(value: String): String {
        if (value != value.trim() || value.length > 253 || value.any { it <= ' ' || it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' || it == '%' }) {
            throw PairingException("invalid_endpoint")
        }
        if (value.startsWith('[') || value.endsWith('.') || value.contains("://")) throw PairingException("invalid_endpoint")
        if (value.contains(':')) {
            val address = try { InetAddress.getByName(value) } catch (_: Exception) { throw PairingException("invalid_endpoint") }
            if (address !is Inet6Address) throw PairingException("invalid_endpoint")
            return value.lowercase(Locale.ROOT)
        }
        if (value.all { it in '0'..'9' || it == '.' }) return parseIpv4(value, requirePrivate = false)
        val ascii = try { java.net.IDN.toASCII(value, java.net.IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT) }
        catch (_: Exception) { throw PairingException("invalid_endpoint") }
        if (ascii.length > 253 || ascii.split('.').any { it.isEmpty() || it.length > 63 }) throw PairingException("invalid_endpoint")
        return ascii
    }

    private fun port(value: Any?): Int = exactInt(value)?.takeIf { it in 1..65535 } ?: throw PairingException("invalid_endpoint")

    private fun string(root: JSONObject, name: String, min: Int, max: Int): String {
        val value = root.opt(name) as? String ?: throw PairingException("invalid_pairing_qr")
        if (value.length !in min..max) throw PairingException("invalid_pairing_qr")
        return value
    }

    private val INVITE_KEYS = setOf(
        "schemaVersion", "displayName", "hostId", "serverName", "serverCertificatePem", "serverCertificateSha256",
        "relay", "directEndpoints", "inviteId", "inviteSecret", "expiresAt",
    )
    private val RELAY_KEYS = setOf("host", "port", "certificatePem")
    private val DIRECT_KEYS = setOf("host", "port")
}

data class DeviceIdentity(val alias: String, val certificatePem: String, val certificateSha256: String) {
    override fun toString(): String = "DeviceIdentity(alias=$alias, certificateSha256=$certificateSha256, certificatePem=<redacted>)"
}

/** Device identity keys are non-exportable Android Keystore P-256 keys; only the public cert is returned. */
class AndroidDeviceIdentity {
    fun create(): DeviceIdentity {
        val keyStore = loadAndroidKeyStore()
        var alias: String
        do {
            alias = "$ALIAS_PREFIX${UUID.randomUUID()}"
        } while (keyStore.containsAlias(alias))

        val now = System.currentTimeMillis()
        val randomSerial = BigInteger(159, SecureRandom()).add(BigInteger.ONE)
        try {
            val generator = java.security.KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(P256_CURVE))
                    // Conscrypt hashes the TLS transcript itself and requests
                    // a raw ECDSA signature. Pairing proofs use SHA-256 here.
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(X500Principal("CN=Device Bridge Phone"))
                    .setCertificateSerialNumber(randomSerial)
                    .setCertificateNotBefore(Date(now - CERTIFICATE_SKEW_MS))
                    .setCertificateNotAfter(Date(now + CERTIFICATE_LIFETIME_MS))
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKeyPair()
        } catch (_: Exception) {
            throw PairingException("device_key_generation_failed")
        }
        return load(alias)
    }

    fun load(alias: String): DeviceIdentity {
        if (!alias.startsWith(ALIAS_PREFIX) || alias.length > MAX_ALIAS_CHARS) throw PairingException("device_key_missing")
        val keyStore = loadAndroidKeyStore()
        if (!keyStore.containsAlias(alias)) throw PairingException("device_key_missing")
        val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: throw PairingException("device_key_missing")
        validatePhoneCertificate(certificate)
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: throw PairingException("device_key_missing")
        if (privateKey.algorithm != "EC") throw PairingException("device_key_invalid")
        return DeviceIdentity(alias, encodePem(certificate.encoded), sha256Hex(certificate.encoded))
    }

    fun sign(alias: String, bytes: ByteArray): String {
        val identity = load(alias)
        if (identity.certificatePem.isEmpty()) throw PairingException("device_key_missing")
        val keyStore = loadAndroidKeyStore()
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: throw PairingException("device_key_missing")
        try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(privateKey)
            signature.update(bytes)
            return Base64.getEncoder().encodeToString(signature.sign())
        } catch (_: Exception) {
            throw PairingException("device_proof_failed")
        }
    }

    internal fun keyManagers(alias: String, expectedFingerprint: String): Array<javax.net.ssl.KeyManager> {
        val identity = load(alias)
        if (identity.certificateSha256 != expectedFingerprint) throw PairingException("device_identity_mismatch")
        val keyStore = loadAndroidKeyStore()
        return try {
            val factory = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore, null)
            val delegate = factory.keyManagers.filterIsInstance<X509KeyManager>().singleOrNull()
                ?: throw PairingException("device_key_unavailable")
            arrayOf(ScopedClientKeyManager(delegate, alias, parseX509Pem(identity.certificatePem)))
        } catch (_: Exception) {
            throw PairingException("device_key_unavailable")
        }
    }

    fun delete(alias: String) {
        if (!alias.startsWith(ALIAS_PREFIX) || alias.length > MAX_ALIAS_CHARS) throw PairingException("device_key_missing")
        try {
            loadAndroidKeyStore().deleteEntry(alias)
        } catch (_: Exception) {
            throw PairingException("device_key_delete_failed")
        }
    }

    private fun loadAndroidKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    } catch (_: Exception) {
        throw PairingException("device_key_store_unavailable")
    }

    private fun validatePhoneCertificate(certificate: X509Certificate) {
        try {
            certificate.checkValidity()
            if (certificate.subjectX500Principal != certificate.issuerX500Principal) throw PairingException("device_certificate_invalid")
            certificate.verify(certificate.publicKey)
            val publicKey = certificate.publicKey as? ECPublicKey ?: throw PairingException("device_certificate_invalid")
            if (!isP256(publicKey.params)) throw PairingException("device_certificate_invalid")
            if (!certificate.sigAlgName.contains("ECDSA", ignoreCase = true)) throw PairingException("device_certificate_invalid")
        } catch (e: PairingException) {
            throw e
        } catch (_: Exception) {
            throw PairingException("device_certificate_invalid")
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val ALIAS_PREFIX = "devicebridge.phone.v2."
        const val MAX_ALIAS_CHARS = 96
        const val P256_CURVE = "secp256r1"
        const val CERTIFICATE_SKEW_MS = 60_000L
        const val CERTIFICATE_LIFETIME_MS = 3650L * 24 * 60 * 60 * 1000
    }
}

/** Restricts TLS client authentication to the certificate chosen by this profile. */
private class ScopedClientKeyManager(
    private val delegate: X509KeyManager,
    private val allowedAlias: String,
    private val allowedCertificate: X509Certificate,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
        if (isAllowed(keyType, issuers)) arrayOf(allowedAlias) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? =
        keyType?.firstNotNullOfOrNull { type -> if (isAllowed(type, issuers)) allowedAlias else null }

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?): String? =
        keyType?.firstNotNullOfOrNull { type -> if (isAllowed(type, issuers)) allowedAlias else null }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == allowedAlias) arrayOf(allowedCertificate) else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == allowedAlias) delegate.getPrivateKey(allowedAlias) else null

    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
        delegate.getServerAliases(keyType, issuers)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? =
        delegate.chooseServerAlias(keyType, issuers, socket)

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: SSLEngine?): String? =
        delegate.chooseServerAlias(keyType, issuers, null)

    private fun isAllowed(keyType: String?, issuers: Array<out java.security.Principal>?): Boolean {
        if (keyType == null || !keyType.startsWith("EC", ignoreCase = true)) return false
        if (issuers.isNullOrEmpty()) return true
        val issuer = allowedCertificate.issuerX500Principal
        return issuers.any { it == issuer }
    }
}

internal object PairingCrypto {
    fun requestId(): String = UUID.randomUUID().toString()

    fun proofBytes(inviteId: String, requestId: String, hostId: String, challenge: String, clientCertificateSha256: String): ByteArray =
        "device-bridge-pair-v1\n$inviteId\n$requestId\n$hostId\n$challenge\n$clientCertificateSha256".toByteArray(Charsets.UTF_8)

    fun confirmationCode(proofBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(proofBytes)
        return try {
            BigInteger(1, digest).mod(BigInteger.valueOf(1_000_000L)).toString().padStart(6, '0')
        } finally {
            digest.fill(0)
        }
    }
}

internal fun parseX509Pem(pem: String, maxChars: Int = PairingQr.MAX_DECODED_BYTES): X509Certificate {
    val begin = "-----BEGIN CERTIFICATE-----"
    val end = "-----END CERTIFICATE-----"
    if (pem.length !in 1..maxChars) throw PairingException("invalid_certificate")
    val trimmed = pem.trim()
    if (!trimmed.startsWith(begin) || !trimmed.endsWith(end) || trimmed.indexOf(begin, begin.length) >= 0) {
        throw PairingException("invalid_certificate")
    }
    val der = try {
        Base64.getDecoder().decode(trimmed.substring(begin.length, trimmed.length - end.length).filterNot(Char::isWhitespace))
    } catch (_: IllegalArgumentException) {
        throw PairingException("invalid_certificate")
    }
    return try {
        val stream = ByteArrayInputStream(der)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(stream) as? X509Certificate
            ?: throw PairingException("invalid_certificate")
        if (stream.available() != 0 || !MessageDigest.isEqual(certificate.encoded, der)) throw PairingException("invalid_certificate")
        certificate
    } catch (e: PairingException) {
        throw e
    } catch (_: Exception) {
        throw PairingException("invalid_certificate")
    } finally {
        der.fill(0)
    }
}

internal fun encodePem(der: ByteArray): String {
    val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
    return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return try {
        buildString(digest.size * 2) {
            for (byte in digest) append("0123456789abcdef"[(byte.toInt() ushr 4) and 0x0f]).append("0123456789abcdef"[byte.toInt() and 0x0f])
        }
    } finally {
        digest.fill(0)
    }
}

internal fun isP256(parameters: ECParameterSpec): Boolean = try {
    val algorithmParameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
    val expected = algorithmParameters.getParameterSpec(ECParameterSpec::class.java)
    parameters.curve == expected.curve && parameters.generator == expected.generator &&
        parameters.order == expected.order && parameters.cofactor == expected.cofactor
} catch (_: Exception) {
    false
}

private fun exactInt(value: Any?): Int? {
    if (value !is Number) return null
    val number = value.toDouble()
    if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
    return number.toInt()
}

private fun exactLong(value: Any?): Long? = if (value is Number) value.toString().toLongOrNull() else null

class PairingException(val code: String) : Exception(code)
