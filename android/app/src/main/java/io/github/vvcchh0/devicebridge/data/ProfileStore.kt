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
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/** A validated connection profile. Private key material is deliberately excluded from its public data. */
@ConsistentCopyVisibility
data class ConnectionProfile internal constructor(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val serverCertificatePem: String,
    val serverCertificateSha256: String,
    internal val clientCredentials: ClientCredentials,
) {
    override fun toString(): String =
        "ConnectionProfile(id=$id, displayName=$displayName, host=$host, port=$port, serverCertificateSha256=$serverCertificateSha256)"

    internal fun serverCertificate(): X509Certificate = parseProfileServerCertificate(serverCertificatePem)
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

    /** Validates the complete JSON profile before atomically replacing the stored profile. */
    fun importJson(json: String): ConnectionProfile = synchronized(STORE_LOCK) {
        if (json.length > MAX_PROFILE_JSON_BYTES) throw ProfileStoreException("invalid_profile")
        val bytes = json.toByteArray(Charsets.UTF_8)
        try {
            if (bytes.size > MAX_PROFILE_JSON_BYTES) throw ProfileStoreException("invalid_profile")
            val profile = parseProfile(decodeUtf8(bytes))
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

    /** Returns null only when no profile record exists. Corrupt or undecryptable data stays visible as an error. */
    fun load(): ConnectionProfile? = synchronized(STORE_LOCK) {
        if (!vault.exists()) return@synchronized null
        val bytes = try {
            vault.read(MAX_PROFILE_JSON_BYTES)
        } catch (e: VaultException) {
            throw ProfileStoreException(e.code)
        }
        try {
            parseProfile(decodeUtf8(bytes))
        } catch (e: ProfileStoreException) {
            throw e
        } catch (_: Exception) {
            throw ProfileStoreException("profile_corrupt")
        } finally {
            bytes.fill(0)
        }
    }

    /** Removes only the one encrypted profile record; operation records and the shared key are untouched. */
    fun forget() = synchronized(STORE_LOCK) {
        try {
            vault.delete()
        } catch (e: VaultException) {
            throw ProfileStoreException(e.code)
        }
    }

    private fun parseProfile(json: String): ConnectionProfile {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            throw ProfileStoreException("invalid_profile")
        }
        if (root.length() != PROFILE_KEYS.size || root.keys().asSequence().toSet() != PROFILE_KEYS) {
            throw ProfileStoreException("invalid_profile")
        }

        val schemaVersion = exactInt(root.opt("schemaVersion")) ?: throw ProfileStoreException("invalid_profile")
        if (schemaVersion != 1) throw ProfileStoreException("unsupported_profile_version")
        val rawDisplayName = requiredString(root, "displayName", 1, 96)
        val displayName = rawDisplayName.trim()
        if (displayName.isEmpty() || displayName != rawDisplayName || rawDisplayName.any { Character.isISOControl(it) }) {
            throw ProfileStoreException("invalid_profile")
        }
        val host = normalizeHost(requiredString(root, "host", 1, 253))
        val port = exactInt(root.opt("port")) ?: throw ProfileStoreException("invalid_profile")
        if (port !in 1..65535) throw ProfileStoreException("invalid_profile")

        val pem = requiredString(root, "serverCertificatePem", 1, MAX_CERTIFICATE_PEM_CHARS)
        val certificate = parseProfileServerCertificate(pem)
        val suppliedPin = requiredString(root, "serverCertificateSha256", 64, 64).lowercase(Locale.ROOT)
        if (!HEX_SHA256.matches(suppliedPin)) throw ProfileStoreException("invalid_profile")
        val certificatePin = sha256Hex(certificate.encoded)
        if (!MessageDigest.isEqual(suppliedPin.toByteArray(Charsets.US_ASCII), certificatePin.toByteArray(Charsets.US_ASCII))) {
            throw ProfileStoreException("certificate_pin_mismatch")
        }
        try {
            certificate.checkValidity()
        } catch (_: Exception) {
            throw ProfileStoreException("server_certificate_not_valid")
        }

        val pkcs12Base64 = requiredString(root, "clientPkcs12Base64", 1, MAX_PKCS12_BASE64_CHARS)
        val password = requiredString(root, "clientPkcs12Password", 0, MAX_PASSWORD_CHARS)
        val clientIdentity = inspectClientIdentity(pkcs12Base64, password)
        val profileId = deriveProfileId(host, port, certificatePin, clientIdentity.fingerprint)
        return ConnectionProfile(
            id = profileId,
            displayName = displayName,
            host = host,
            port = port,
            serverCertificatePem = pem,
            serverCertificateSha256 = certificatePin,
            clientCredentials = ClientCredentials(pkcs12Base64, password),
        )
    }

    private fun inspectClientIdentity(pkcs12Base64: String, password: String): ClientIdentity {
        val encoded = try {
            Base64.getDecoder().decode(pkcs12Base64)
        } catch (_: IllegalArgumentException) {
            throw ProfileStoreException("invalid_profile")
        }
        if (encoded.isEmpty() || encoded.size > MAX_PKCS12_BYTES) {
            encoded.fill(0)
            throw ProfileStoreException("invalid_profile")
        }
        val passwordChars = password.toCharArray()
        try {
            val keyStore = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(encoded).use { keyStore.load(it, passwordChars) }
            val aliases = keyStore.aliases()
            var selectedAlias: String? = null
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (keyStore.isKeyEntry(alias)) {
                    if (selectedAlias != null) throw ProfileStoreException("invalid_profile")
                    selectedAlias = alias
                }
            }
            val alias = selectedAlias ?: throw ProfileStoreException("client_identity_missing")
            val key = keyStore.getKey(alias, passwordChars) as? PrivateKey
                ?: throw ProfileStoreException("client_identity_missing")
            val leaf = keyStore.getCertificate(alias) as? X509Certificate
                ?: throw ProfileStoreException("client_identity_missing")
            val chain = keyStore.getCertificateChain(alias)
            if (chain.isNullOrEmpty() || chain.any { it !is X509Certificate }) {
                throw ProfileStoreException("client_identity_missing")
            }
            for (certificate in chain.filterIsInstance<X509Certificate>()) {
                try {
                    certificate.checkValidity()
                } catch (_: Exception) {
                    throw ProfileStoreException("client_certificate_not_valid")
                }
            }
            return ClientIdentity(sha256Hex(leaf.encoded))
        } catch (e: ProfileStoreException) {
            throw e
        } catch (_: Exception) {
            throw ProfileStoreException("invalid_client_identity")
        } finally {
            passwordChars.fill('\u0000')
            encoded.fill(0)
        }
    }

    private fun normalizeHost(value: String): String {
        if (value != value.trim() || value.length > 253 || value.any { it <= ' ' || it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' || it == '%' }) {
            throw ProfileStoreException("invalid_profile")
        }
        if (value.contains("://") || value.startsWith("[") || value.endsWith(".")) {
            throw ProfileStoreException("invalid_profile")
        }
        if (value.contains(':')) {
            try {
                val address = java.net.InetAddress.getByName(value)
                if (address !is java.net.Inet6Address) throw ProfileStoreException("invalid_profile")
                return value.lowercase(Locale.ROOT)
            } catch (e: ProfileStoreException) {
                throw e
            } catch (_: Exception) {
                throw ProfileStoreException("invalid_profile")
            }
        }
        if (value.all { it.isDigit() || it == '.' }) {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any {
                    val octet = it.toIntOrNull()
                    it.isEmpty() || it.length > 3 || octet == null || octet !in 0..255
                }) {
                throw ProfileStoreException("invalid_profile")
            }
            return parts.joinToString(".") { it.toInt().toString() }
        }
        val ascii = try {
            java.net.IDN.toASCII(value, java.net.IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            throw ProfileStoreException("invalid_profile")
        }
        if (ascii.isEmpty() || ascii.length > 253 || ascii.split('.').any { it.isEmpty() || it.length > 63 }) {
            throw ProfileStoreException("invalid_profile")
        }
        return ascii
    }

    private fun requiredString(root: JSONObject, name: String, min: Int, max: Int): String {
        val value = root.opt(name)
        if (value !is String || value.length !in min..max) throw ProfileStoreException("invalid_profile")
        return value
    }

    private fun deriveProfileId(host: String, port: Int, serverPin: String, clientPin: String): String {
        val scope = "$host\n$port\n$serverPin\n$clientPin".toByteArray(Charsets.UTF_8)
        return try {
            sha256Hex(scope)
        } finally {
            scope.fill(0)
        }
    }

    private data class ClientIdentity(val fingerprint: String)

    private companion object {
        const val PROFILE_FILE_NAME = "profile.v1.enc"
        const val MAX_PROFILE_JSON_BYTES = 128 * 1024
        const val MAX_CERTIFICATE_PEM_CHARS = 32 * 1024
        const val MAX_PKCS12_BYTES = 96 * 1024
        const val MAX_PKCS12_BASE64_CHARS = 128 * 1024
        const val MAX_PASSWORD_CHARS = 512
        val STORE_LOCK = Any()
        val PROFILE_KEYS = setOf(
            "schemaVersion",
            "displayName",
            "host",
            "port",
            "serverCertificatePem",
            "serverCertificateSha256",
            "clientPkcs12Base64",
            "clientPkcs12Password",
        )
        val HEX_SHA256 = Regex("[0-9a-f]{64}")

        fun exactInt(value: Any?): Int? {
            if (value !is Number) return null
            val double = value.toDouble()
            if (!double.isFinite() || double % 1.0 != 0.0 || double < Int.MIN_VALUE || double > Int.MAX_VALUE) return null
            return double.toInt()
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return try {
                buildString(digest.size * 2) {
                    for (byte in digest) append("0123456789abcdef"[(byte.toInt() ushr 4) and 0x0f]).append("0123456789abcdef"[byte.toInt() and 0x0f])
                }
            } finally {
                digest.fill(0)
            }
        }

        fun decodeUtf8(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }
    }
}

class ProfileStoreException(val code: String) : Exception(code)

internal fun parseProfileServerCertificate(pem: String): X509Certificate {
    val begin = "-----BEGIN CERTIFICATE-----"
    val end = "-----END CERTIFICATE-----"
    val trimmed = pem.trim()
    if (!trimmed.startsWith(begin) || !trimmed.endsWith(end) || trimmed.indexOf(begin, begin.length) >= 0) {
        throw ProfileStoreException("invalid_server_certificate")
    }
    val body = trimmed.substring(begin.length, trimmed.length - end.length).filterNot(Char::isWhitespace)
    val der = try {
        Base64.getDecoder().decode(body)
    } catch (_: IllegalArgumentException) {
        throw ProfileStoreException("invalid_server_certificate")
    }
    return try {
        val input = ByteArrayInputStream(der)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(input) as? X509Certificate
            ?: throw ProfileStoreException("invalid_server_certificate")
        if (input.available() != 0 || !MessageDigest.isEqual(certificate.encoded, der)) {
            throw ProfileStoreException("invalid_server_certificate")
        }
        certificate
    } catch (e: ProfileStoreException) {
        throw e
    } catch (_: Exception) {
        throw ProfileStoreException("invalid_server_certificate")
    } finally {
        der.fill(0)
    }
}
