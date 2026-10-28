package io.github.vvcchh0.devicebridge.data

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** One-request-per-connection JSONL mTLS transport with no retry behavior. */
class BridgeTransport {
    fun request(profile: ConnectionProfile, method: String, params: JSONObject): JSONObject {
        validateParams(method, params)
        val requestFrame = try {
            JSONObject().put("method", method).put("params", JSONObject(params.toString()))
                .toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            throw BridgeFailure("invalid_params", deliveryUnknown = false)
        }
        if (requestFrame.size + 1 > MAX_FRAME_BYTES) {
            requestFrame.fill(0)
            throw BridgeFailure("frame_too_large", deliveryUnknown = false)
        }

        var requestStarted = false
        var rawSocket: Socket? = null
        try {
            val context = createTlsContext(profile)
            val socket = Socket()
            rawSocket = socket
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)

            val tlsSocket = context.socketFactory.createSocket(socket, profile.host, profile.port, true) as SSLSocket
            tlsSocket.soTimeout = READ_TIMEOUT_MS
            tlsSocket.useClientMode = true
            val supported = tlsSocket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            if (supported.isEmpty()) throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
            tlsSocket.enabledProtocols = supported
            val sslParameters = tlsSocket.sslParameters
            sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            tlsSocket.sslParameters = sslParameters
            tlsSocket.startHandshake()
            val peer = tlsSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("server_certificate_untrusted")
            peer.checkValidity()
            val actualPin = MessageDigest.getInstance("SHA-256").digest(peer.encoded)
            val expectedPin = decodeHex(profile.serverCertificateSha256)
            try {
                if (!MessageDigest.isEqual(expectedPin, actualPin)) throw CertificateException("server_certificate_pin_mismatch")
            } finally { actualPin.fill(0); expectedPin.fill(0) }

            requestStarted = true
            val output = tlsSocket.outputStream
            output.write(requestFrame)
            output.write(NEWLINE)
            output.flush()
            val responseLine = readResponseFrame(tlsSocket, method)
            return try {
                parseResponse(responseLine, method, requestStarted)
            } finally {
                responseLine.fill(0)
            }
        } catch (e: BridgeFailure) {
            throw BridgeFailure(e.code, deliveryUnknown = e.deliveryUnknown && method == "submit")
        } catch (_: SocketTimeoutException) {
            throw BridgeFailure("transport_timeout", deliveryUnknown = method == "submit" && requestStarted)
        } catch (_: SSLException) {
            throw BridgeFailure("tls_verification_failed", deliveryUnknown = method == "submit" && requestStarted)
        } catch (_: CertificateException) {
            throw BridgeFailure("tls_verification_failed", deliveryUnknown = method == "submit" && requestStarted)
        } catch (_: IOException) {
            throw BridgeFailure("transport_failed", deliveryUnknown = method == "submit" && requestStarted)
        } catch (_: Exception) {
            throw BridgeFailure("transport_failed", deliveryUnknown = method == "submit" && requestStarted)
        } finally {
            requestFrame.fill(0)
            try {
                rawSocket?.close()
            } catch (_: Exception) {
                // Socket cleanup must not replace the sanitized request outcome.
            }
        }
    }

    private fun createTlsContext(profile: ConnectionProfile): SSLContext {
        try {
            val pinnedServer = profile.serverCertificate()
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("profile-server", pinnedServer)
            }
            val trustFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
            ).apply { init(trustStore) }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(profile.clientCredentials.keyManagers(), trustFactory.trustManagers, SecureRandom())
            return sslContext
        } catch (e: BridgeFailure) {
            throw e
        } catch (e: ProfileStoreException) {
            throw BridgeFailure("client_identity_unavailable", deliveryUnknown = false)
        } catch (_: Exception) {
            throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
        }
    }

    private fun readResponseFrame(socket: SSLSocket, method: String): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val value = try {
                socket.inputStream.read()
            } catch (e: SocketTimeoutException) {
                throw e
            }
            if (value < 0) throw BridgeFailure("response_incomplete", deliveryUnknown = method == "submit")
            if (value == NEWLINE) break
            if (output.size() + 2 > MAX_FRAME_BYTES) {
                throw BridgeFailure("frame_too_large", deliveryUnknown = method == "submit")
            }
            output.write(value)
        }
        if (output.size() + 1 > MAX_FRAME_BYTES) {
            throw BridgeFailure("frame_too_large", deliveryUnknown = method == "submit")
        }
        var result = output.toByteArray()
        if (result.isEmpty()) throw BridgeFailure("invalid_response", deliveryUnknown = method == "submit")
        if (result.last().toInt() == CARRIAGE_RETURN) result = result.copyOf(result.size - 1)
        return result
    }

    private fun parseResponse(line: ByteArray, method: String, requestStarted: Boolean): JSONObject {
        val jsonText = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(line)).toString()
        } catch (_: Exception) {
            throw BridgeFailure("invalid_response", deliveryUnknown = method == "submit" && requestStarted)
        }
        val envelope = try {
            JSONObject(jsonText)
        } catch (_: JSONException) {
            throw BridgeFailure("invalid_response", deliveryUnknown = method == "submit" && requestStarted)
        }
        if (envelope.opt("ok") !is Boolean) throw BridgeFailure("invalid_response", method == "submit" && requestStarted)
        if (!envelope.getBoolean("ok")) {
            val code = envelope.opt("error") as? String
            val safeCode = code?.takeIf { ERROR_CODE_PATTERN.matches(it) } ?: "bridge_error"
            throw BridgeFailure(safeCode, deliveryUnknown = method == "submit" && requestStarted)
        }
        val result = envelope.opt("result") as? JSONObject
            ?: throw BridgeFailure("invalid_response", deliveryUnknown = method == "submit" && requestStarted)
        return result
    }

    private fun validateParams(method: String, params: JSONObject) {
        val expected = when (method) {
            "catalog" -> emptySet()
            "history" -> setOf("thread", "before", "limit")
            "submit" -> setOf("thread", "id", "payload")
            "status", "reconcile" -> setOf("id")
            "sync" -> setOf("thread", "cursor")
            else -> throw BridgeFailure("method_not_allowed", deliveryUnknown = false)
        }
        if (params.keys().asSequence().toSet() != expected) {
            throw BridgeFailure("invalid_params", deliveryUnknown = false)
        }

        when (method) {
            "catalog" -> Unit
            "history" -> {
                requireString(params, "thread", 1, MAX_ALIAS_CHARS)
                val before = params.opt("before")
                if (before !== JSONObject.NULL && (before !is String || before.length !in 1..MAX_CURSOR_CHARS)) {
                    throw BridgeFailure("invalid_params", deliveryUnknown = false)
                }
                val limit = exactInt(params.opt("limit"))
                if (limit == null || limit !in 1..8) throw BridgeFailure("invalid_params", deliveryUnknown = false)
            }
            "submit" -> {
                requireString(params, "thread", 1, MAX_ALIAS_CHARS)
                requireString(params, "id", 1, MAX_ID_CHARS)
                val payload = params.opt("payload") as? JSONObject
                    ?: throw BridgeFailure("invalid_params", deliveryUnknown = false)
                if (payload.keys().asSequence().toSet() != setOf("text")) {
                    throw BridgeFailure("invalid_params", deliveryUnknown = false)
                }
                requireString(payload, "text", 0, MAX_TEXT_CHARS)
            }
            "status", "reconcile" -> requireString(params, "id", 1, MAX_ID_CHARS)
            "sync" -> {
                requireString(params, "thread", 1, MAX_ALIAS_CHARS)
                val cursor = exactLong(params.opt("cursor"))
                if (cursor == null || cursor < 0) throw BridgeFailure("invalid_params", deliveryUnknown = false)
            }
        }
    }

    private fun requireString(params: JSONObject, name: String, min: Int, max: Int): String {
        val value = params.opt(name) as? String ?: throw BridgeFailure("invalid_params", deliveryUnknown = false)
        if (value.length !in min..max || value.any { Character.isISOControl(it) }) {
            throw BridgeFailure("invalid_params", deliveryUnknown = false)
        }
        return value
    }

    private fun exactInt(value: Any?): Int? {
        if (value !is Number) return null
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
        return number.toInt()
    }

    private fun exactLong(value: Any?): Long? {
        if (value !is Number) return null
        return value.toString().toLongOrNull()
    }

    private fun decodeHex(value: String): ByteArray {
        if (!SHA256_HEX_PATTERN.matches(value)) throw BridgeFailure("invalid_profile", deliveryUnknown = false)
        return ByteArray(value.length / 2) { index ->
            val offset = index * 2
            value.substring(offset, offset + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 8192
        const val CONNECT_TIMEOUT_MS = 8000
        const val READ_TIMEOUT_MS = 25000
        const val NEWLINE = 10
        const val CARRIAGE_RETURN = 13
        const val MAX_ALIAS_CHARS = 128
        const val MAX_ID_CHARS = 128
        const val MAX_CURSOR_CHARS = 256
        const val MAX_TEXT_CHARS = 8192
        val SHA256_HEX_PATTERN = Regex("[0-9a-f]{64}")
        val ERROR_CODE_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

class BridgeFailure(val code: String, val deliveryUnknown: Boolean) : Exception(code)
