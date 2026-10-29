package io.github.vvcchh0.devicebridge.data

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/** Public profile metadata. v1 PKCS#12 material remains private and v2 stores only a Keystore alias. */
@ConsistentCopyVisibility
data class ConnectionProfile internal constructor(
    val id: String,
    val displayName: String,
    val schemaVersion: Int,
    val host: String,
    val port: Int,
    val serverCertificatePem: String,
    val serverCertificateSha256: String,
    val hostId: String? = null,
    val serverName: String = host,
    val relay: RelayEndpoint? = null,
    val directEndpoints: List<DirectEndpoint> = emptyList(),
    val clientKeyAlias: String? = null,
    val clientCertificateSha256: String? = null,
    internal val legacyCredentials: ClientCredentials? = null,
) {
    override fun toString(): String =
        "ConnectionProfile(id=$id, displayName=$displayName, schemaVersion=$schemaVersion, host=$host, port=$port, hostId=$hostId, clientKeyAlias=$clientKeyAlias)"

    internal fun serverCertificate(): X509Certificate = parseX509Pem(serverCertificatePem)

    internal fun keyManagers(): Array<KeyManager> = if (schemaVersion == 1) {
        (legacyCredentials ?: throw ProfileStoreException("client_identity_unavailable")).keyManagers()
    } else {
        try {
            AndroidDeviceIdentity().keyManagers(
                clientKeyAlias ?: throw PairingException("device_key_missing"),
                clientCertificateSha256 ?: throw PairingException("device_key_missing"),
            )
        } catch (e: PairingException) {
            throw ProfileStoreException(e.code)
        }
    }
}

internal class ClientCredentials(
    private val pkcs12Base64: String,
    private val password: String,
) {
    fun keyManagers(): Array<KeyManager> {
        val encoded = try {
            Base64.getDecoder().decode(pkcs12Base64)
        } catch (_: IllegalArgumentException) {
            throw ProfileStoreException("invalid_profile")
        }
        val passwordChars = password.toCharArray()
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(encoded).use { keyStore.load(it, passwordChars) }
            val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            factory.init(keyStore, passwordChars)
            return factory.keyManagers
        } catch (_: Exception) {
            throw ProfileStoreException("client_identity_unavailable")
        } finally {
            passwordChars.fill('\u0000')
            encoded.fill(0)
        }
    }

    override fun toString(): String = "ClientCredentials(<redacted>)"
}

class ProfileStore(context: Context) {
    private val vault = EncryptedVaultFile(context, PROFILE_FILE_NAME)

    /** Supports legacy v1 imports and the exact v2 shape. Validation completes before atomic replacement. */
    fun importJson(json: String): ConnectionProfile = synchronized(STORE_LOCK) {
        if (json.length > MAX_PROFILE_JSON_BYTES) throw ProfileStoreException("invalid_profile")
        val bytes = json.toByteArray(Charsets.UTF_8)
        try {
            if (bytes.size > MAX_PROFILE_JSON_BYTES) throw ProfileStoreException("invalid_profile")
            val root = parseJson(decodeUtf8(bytes))
            val version = exactInt(root.opt("schemaVersion")) ?: throw ProfileStoreException("invalid_profile")
            val profile = when (version) {
                1 -> parseV1(root)
                2 -> parseV2(root)
                else -> throw ProfileStoreException("unsupported_profile_version")
            }
            try {
                vault.write(bytes, MAX_PROFILE_JSON_BYTES)
            } catch (e: VaultException) {
                throw ProfileStoreException(e.code)
            }
            profile
        } catch (e: ProfileStoreException) {
            throw e
        } catch (_: Exception) {
            throw ProfileStoreException("invalid_profile")
        } finally {
            bytes.fill(0)
        }
    }

    /** Writes a v2 profile after proof-backed approval. Invite secrets are intentionally omitted. */
    fun completePairing(invite: PairingInvite, alias: String, clientCertificateSha256: String): ConnectionProfile {
        val direct = org.json.JSONArray()
        invite.directEndpoints.forEach { direct.put(JSONObject().put("host", it.host).put("port", it.port)) }
        val relay = JSONObject()
            .put("host", invite.relay.host)
            .put("port", invite.relay.port)
            .put("certificatePem", invite.relay.certificatePem ?: JSONObject.NULL)
        val json = JSONObject()
            .put("schemaVersion", 2)
            .put("displayName", invite.displayName)
            .put("hostId", invite.hostId)
            .put("serverName", invite.serverName)
            .put("serverCertificatePem", invite.serverCertificatePem)
            .put("serverCertificateSha256", invite.serverCertificateSha256)
            .put("clientKeyAlias", alias)
            .put("clientCertificateSha256", clientCertificateSha256)
            .put("relay", relay)
            .put("directEndpoints", direct)
        return importJson(json.toString())
    }

    /** Returns null only for an absent record; decryption or schema failures remain visible. */
    fun load(): ConnectionProfile? = synchronized(STORE_LOCK) {
        if (!vault.exists()) return@synchronized null
        val bytes = try {
            vault.read(MAX_PROFILE_JSON_BYTES)
        } catch (e: VaultException) {
            throw ProfileStoreException(e.code)
        }
        try {
            val root = parseJson(decodeUtf8(bytes))
            when (exactInt(root.opt("schemaVersion"))) {
                1 -> parseV1(root)
                2 -> parseV2(root)
                else -> throw ProfileStoreException("unsupported_profile_version")
            }
        } catch (e: ProfileStoreException) {
            throw e
        } catch (_: Exception) {
            throw ProfileStoreException("profile_corrupt")
        } finally {
            bytes.fill(0)
        }
    }

    /** Removes only the profile record; per-profile outboxes and Keystore aliases remain intact. */
    fun forget() = synchronized(STORE_LOCK) {
        try {
            vault.delete()
        } catch (e: VaultException) {
            throw ProfileStoreException(e.code)
        }
    }

    private fun parseV1(root: JSONObject): ConnectionProfile {
        requireKeys(root, V1_KEYS)
        if (exactInt(root.opt("schemaVersion")) != 1) throw ProfileStoreException("invalid_profile")
        val displayName = validateDisplayName(requiredString(root, "displayName", 1, 96))
        val host = validateLegacyHost(requiredString(root, "host", 1, 253))
        val port = exactInt(root.opt("port"))?.takeIf { it in 1..65535 } ?: throw ProfileStoreException("invalid_profile")
        val serverPem = requiredString(root, "serverCertificatePem", 1, MAX_CERTIFICATE_PEM_CHARS)
        val serverCertificate = parseCertificate(serverPem)
        validateCertificateValidity(serverCertificate)
        val serverPin = verifyPin(serverCertificate, requiredString(root, "serverCertificateSha256", 64, 64))
        val pkcs12Base64 = requiredString(root, "clientPkcs12Base64", 1, MAX_PKCS12_BASE64_CHARS)
        val password = requiredString(root, "clientPkcs12Password", 0, MAX_PASSWORD_CHARS)
        val clientPin = inspectLegacyClientIdentity(pkcs12Base64, password)
        val idBytes = "$host\n$port\n$serverPin\n$clientPin".toByteArray(Charsets.UTF_8)
        val id = try { sha256Hex(idBytes) } finally { idBytes.fill(0) }
        return ConnectionProfile(
            id = id,
            displayName = displayName,
            schemaVersion = 1,
            host = host,
            port = port,
            serverCertificatePem = serverPem,
            serverCertificateSha256 = serverPin,
            serverName = host,
            directEndpoints = listOf(DirectEndpoint(host, port)),
            legacyCredentials = ClientCredentials(pkcs12Base64, password),
        )
    }

    private fun parseV2(root: JSONObject): ConnectionProfile {
        requireKeys(root, V2_KEYS)
        if (exactInt(root.opt("schemaVersion")) != 2) throw ProfileStoreException("invalid_profile")
        val displayName = validateDisplayName(requiredString(root, "displayName", 1, 80))
        val hostId = requiredString(root, "hostId", 64, 64)
        if (!SHA256_PATTERN.matches(hostId)) throw ProfileStoreException("invalid_profile")
        val serverName = requiredString(root, "serverName", 1, 253)
        if (serverName != PairingQr.expectedServerName(hostId)) throw ProfileStoreException("server_name_mismatch")
        val serverPem = requiredString(root, "serverCertificatePem", 1, MAX_CERTIFICATE_PEM_CHARS)
        val serverCertificate = parseCertificate(serverPem)
        validateCertificateValidity(serverCertificate)
        val serverPin = verifyPin(serverCertificate, requiredString(root, "serverCertificateSha256", 64, 64))
        val hostKey = serverCertificate.publicKey as? java.security.interfaces.ECPublicKey
            ?: throw ProfileStoreException("invalid_host_certificate")
        if (!isP256(hostKey.params) || sha256Hex(hostKey.encoded) != hostId) throw ProfileStoreException("host_identity_mismatch")

        val relay = parseRelay(root.opt("relay"))
        val endpoints = parseDirectEndpoints(root.opt("directEndpoints"))

        val alias = requiredString(root, "clientKeyAlias", 1, MAX_ALIAS_CHARS)
        val clientPin = requiredString(root, "clientCertificateSha256", 64, 64)
        if (!SHA256_PATTERN.matches(clientPin)) throw ProfileStoreException("invalid_profile")
        try {
            val identity = AndroidDeviceIdentity().load(alias)
            if (identity.certificateSha256 != clientPin) throw ProfileStoreException("client_identity_mismatch")
        } catch (e: ProfileStoreException) {
            throw e
        } catch (e: PairingException) {
            throw ProfileStoreException(e.code)
        }

        val idBytes = "$hostId\n$clientPin".toByteArray(Charsets.UTF_8)
        val id = try { sha256Hex(idBytes) } finally { idBytes.fill(0) }
        val firstEndpoint = endpoints.firstOrNull()
        val displayedRoute = firstEndpoint?.host ?: relay.host
        val displayedPort = firstEndpoint?.port ?: relay.port
        return ConnectionProfile(
            id = id,
            displayName = displayName,
            schemaVersion = 2,
            host = displayedRoute,
            port = displayedPort,
            serverCertificatePem = serverPem,
            serverCertificateSha256 = serverPin,
            hostId = hostId,
            serverName = serverName,
            relay = relay,
            directEndpoints = endpoints,
            clientKeyAlias = alias,
            clientCertificateSha256 = clientPin,
        )
    }

    private fun parseRelay(value: Any?): RelayEndpoint {
        val json = value as? JSONObject ?: throw ProfileStoreException("invalid_profile")
        if (json.keys().asSequence().toSet() != RELAY_KEYS) throw ProfileStoreException("invalid_profile")
        val host = try { PairingQr.normalizeEndpointHost(requiredString(json, "host", 1, 253)) }
        catch (_: PairingException) { throw ProfileStoreException("invalid_profile") }
        val port = exactInt(json.opt("port"))?.takeIf { it in 1..65535 } ?: throw ProfileStoreException("invalid_profile")
        val pem = when (val certificate = json.opt("certificatePem")) {
            JSONObject.NULL -> null
            is String -> {
                val parsed = parseCertificate(certificate)
                validateCertificateValidity(parsed)
                certificate
            }
            else -> throw ProfileStoreException("invalid_profile")
        }
        return RelayEndpoint(host, port, pem)
    }

    private fun parseDirectEndpoints(value: Any?): List<DirectEndpoint> {
        val array = value as? org.json.JSONArray ?: throw ProfileStoreException("invalid_profile")
        if (array.length() !in 0..4) throw ProfileStoreException("invalid_profile")
        return (0 until array.length()).map { index ->
            val item = array.opt(index) as? JSONObject ?: throw ProfileStoreException("invalid_profile")
            if (item.keys().asSequence().toSet() != DIRECT_KEYS) throw ProfileStoreException("invalid_profile")
            val originalHost = requiredString(item, "host", 1, 64)
            val host = try { PairingQr.validateDirectHost(originalHost) }
            catch (_: PairingException) { throw ProfileStoreException("invalid_direct_endpoint") }
            val port = exactInt(item.opt("port"))?.takeIf { it in 1..65535 } ?: throw ProfileStoreException("invalid_profile")
            DirectEndpoint(host, port)
        }
    }

    private fun inspectLegacyClientIdentity(pkcs12Base64: String, password: String): String {
        val encoded = try { Base64.getDecoder().decode(pkcs12Base64) }
        catch (_: IllegalArgumentException) { throw ProfileStoreException("invalid_profile") }
        if (encoded.isEmpty() || encoded.size > MAX_PKCS12_BYTES) {
            encoded.fill(0)
            throw ProfileStoreException("invalid_profile")
        }
        val passwordChars = password.toCharArray()
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(encoded).use { keyStore.load(it, passwordChars) }
            val aliases = keyStore.aliases()
            var privateAlias: String? = null
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (keyStore.isKeyEntry(alias)) {
                    if (privateAlias != null) throw ProfileStoreException("invalid_profile")
                    privateAlias = alias
                }
            }
            val alias = privateAlias ?: throw ProfileStoreException("client_identity_missing")
            if (keyStore.getKey(alias, passwordChars) !is PrivateKey) throw ProfileStoreException("client_identity_missing")
            val leaf = keyStore.getCertificate(alias) as? X509Certificate
                ?: throw ProfileStoreException("client_identity_missing")
            val chain = keyStore.getCertificateChain(alias)
            if (chain.isNullOrEmpty() || chain.any { it !is X509Certificate }) {
                throw ProfileStoreException("client_identity_missing")
            }
            chain.filterIsInstance<X509Certificate>().forEach(::validateCertificateValidity)
            return sha256Hex(leaf.encoded)
        } catch (e: ProfileStoreException) {
            throw e
        } catch (_: Exception) {
            throw ProfileStoreException("invalid_client_identity")
        } finally {
            passwordChars.fill('\u0000')
            encoded.fill(0)
        }
    }

    private fun validateLegacyHost(value: String): String {
        try {
            return PairingQr.normalizeEndpointHost(value)
        } catch (_: PairingException) {
            throw ProfileStoreException("invalid_profile")
        }
    }

    private fun validateDisplayName(value: String): String {
        if (value != value.trim() || value.any { Character.isISOControl(it) }) throw ProfileStoreException("invalid_profile")
        return value
    }

    private fun verifyPin(certificate: X509Certificate, value: String): String {
        val pin = value.lowercase(Locale.ROOT)
        if (!SHA256_PATTERN.matches(pin) ||
            !MessageDigest.isEqual(pin.toByteArray(Charsets.US_ASCII), sha256Hex(certificate.encoded).toByteArray(Charsets.US_ASCII))
        ) throw ProfileStoreException("certificate_pin_mismatch")
        return pin
    }

    private fun parseCertificate(pem: String): X509Certificate = try {
        parseX509Pem(pem, MAX_CERTIFICATE_PEM_CHARS)
    } catch (e: PairingException) {
        throw ProfileStoreException("invalid_server_certificate")
    }

    private fun validateCertificateValidity(certificate: X509Certificate) {
        try { certificate.checkValidity() }
        catch (_: Exception) { throw ProfileStoreException("certificate_not_valid") }
    }

    private fun parseJson(json: String): JSONObject = try {
        JSONObject(json)
    } catch (_: JSONException) {
        throw ProfileStoreException("invalid_profile")
    }

    private fun requireKeys(root: JSONObject, expected: Set<String>) {
        if (root.length() != expected.size || root.keys().asSequence().toSet() != expected) {
            throw ProfileStoreException("invalid_profile")
        }
    }

    private fun requiredString(root: JSONObject, name: String, min: Int, max: Int): String {
        val value = root.opt(name) as? String ?: throw ProfileStoreException("invalid_profile")
        if (value.length !in min..max) throw ProfileStoreException("invalid_profile")
        return value
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private companion object {
        const val PROFILE_FILE_NAME = "profile.v1.enc"
        const val MAX_PROFILE_JSON_BYTES = 128 * 1024
        const val MAX_CERTIFICATE_PEM_CHARS = PairingQr.MAX_DECODED_BYTES
        const val MAX_PKCS12_BYTES = 96 * 1024
        const val MAX_PKCS12_BASE64_CHARS = 128 * 1024
        const val MAX_PASSWORD_CHARS = 512
        const val MAX_ALIAS_CHARS = 96
        val STORE_LOCK = Any()
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val V1_KEYS = setOf(
            "schemaVersion", "displayName", "host", "port", "serverCertificatePem", "serverCertificateSha256",
            "clientPkcs12Base64", "clientPkcs12Password",
        )
        val V2_KEYS = setOf(
            "schemaVersion", "displayName", "hostId", "serverName", "serverCertificatePem", "serverCertificateSha256",
            "clientKeyAlias", "clientCertificateSha256", "relay", "directEndpoints",
        )
        val RELAY_KEYS = setOf("host", "port", "certificatePem")
        val DIRECT_KEYS = setOf("host", "port")

        fun exactInt(value: Any?): Int? {
            if (value !is Number) return null
            val number = value.toDouble()
            if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
            return number.toInt()
        }
    }
}

class ProfileStoreException(val code: String) : Exception(code)
