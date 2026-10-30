package io.github.vvcchh0.devicebridge.data

import org.json.JSONObject
import java.security.Signature
import java.util.Base64

/** Fixed ES256 profile; verified with the already pinned host public key. */
internal object RelayTicket {
    fun validate(value: String, hostId: String, subject: String, serverPem: String,
                 now: Long = System.currentTimeMillis() / 1000, allowExpired: Boolean = false): Long {
        try {
            if (value.length !in 100..2048) throw IllegalArgumentException()
            val parts = value.split('.')
            if (parts.size != 3 || parts[0] != "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9" ||
                !parts[1].matches(Regex("[A-Za-z0-9_-]+")) || !parts[2].matches(Regex("[A-Za-z0-9_-]{86}"))) throw IllegalArgumentException()
            val claims = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            if (claims.keys().asSequence().toSet() != setOf("iss", "sub", "aud", "iat", "exp", "jti") ||
                claims.opt("iss") != hostId || claims.opt("sub") != subject || claims.opt("aud") != "device-bridge-relay-v1" ||
                !(claims.opt("jti") as? String ?: "").matches(Regex("[0-9a-f]{32}"))) throw IllegalArgumentException()
            val issued = (claims.opt("iat") as? Number)?.toString()?.toLongOrNull() ?: throw IllegalArgumentException()
            val expires = (claims.opt("exp") as? Number)?.toString()?.toLongOrNull() ?: throw IllegalArgumentException()
            if (issued > now + 30 || expires <= issued || expires - issued > 30 * 86400L || (!allowExpired && expires <= now)) throw IllegalArgumentException()
            val raw = Base64.getUrlDecoder().decode(parts[2])
            if (raw.size != 64) throw IllegalArgumentException()
            fun integer(bytes: ByteArray): ByteArray {
                var start = 0
                while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
                val body = bytes.copyOfRange(start, bytes.size)
                val positive = if (body[0].toInt() and 128 != 0) byteArrayOf(0) + body else body
                return byteArrayOf(2, positive.size.toByte()) + positive
            }
            val pair = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
            val der = byteArrayOf(48, pair.size.toByte()) + pair
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(parseX509Pem(serverPem).publicKey)
            signature.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
            if (!signature.verify(der)) throw IllegalArgumentException()
            return expires
        } catch (_: Exception) { throw PairingException("invalid_relay_ticket") }
    }

    fun connectionUpdate(value: String, serverPem: String): JSONObject {
        try {
            if (!value.startsWith("dbroute:") || value.length > 11000) throw IllegalArgumentException()
            val parts = value.removePrefix("dbroute:").split('.')
            if (parts.size != 3 || parts[0] != "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9" ||
                !parts[1].matches(Regex("[A-Za-z0-9_-]+")) || !parts[2].matches(Regex("[A-Za-z0-9_-]{86}"))) throw IllegalArgumentException()
            val raw = Base64.getUrlDecoder().decode(parts[2])
            fun integer(offset: Int): ByteArray {
                val number = java.math.BigInteger(1, raw.copyOfRange(offset, offset + 32)).toByteArray()
                return byteArrayOf(2, number.size.toByte()) + number
            }
            val pair = integer(0) + integer(32)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(parseX509Pem(serverPem).publicKey)
            verifier.update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
            if (!verifier.verify(byteArrayOf(48, pair.size.toByte()) + pair)) throw IllegalArgumentException()
            return JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        } catch (_: Exception) { throw PairingException("invalid_connection_update") }
    }
}
