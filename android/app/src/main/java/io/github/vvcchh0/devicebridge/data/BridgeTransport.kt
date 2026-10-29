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
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** Direct-first, relay-second typed JSONL transport. Route fallback stops before the first RPC byte. */
class BridgeTransport internal constructor(private val exchange: RouteExchange) : PairingRpc {
    constructor() : this(SecureRouteExchange())

    fun request(profile: ConnectionProfile, method: String, params: JSONObject): JSONObject {
        validateApplicationParams(method, params)
        val routes = if (profile.schemaVersion == 1) {
            profile.directEndpoints.map { TransportRoute.Direct(it) }
        } else {
            profile.directEndpoints.map { TransportRoute.Direct(it) } + listOfNotNull(profile.relay?.let { TransportRoute.Relay(it) })
        }
        val tls = InnerTlsConfig(
            serverName = profile.serverName,
            certificatePem = profile.serverCertificatePem,
            certificateSha256 = profile.serverCertificateSha256,
            clientKeyManagers = profile.keyManagers(),
        )
        return execute(routes, profile.hostId ?: "", tls, method, params)
    }

    override fun requestPairing(invite: PairingInvite, method: String, params: JSONObject): JSONObject {
        validatePairingParams(invite, method, params)
        val routes = invite.directEndpoints.map { TransportRoute.Direct(it) } + listOf(TransportRoute.Relay(invite.relay))
        val tls = InnerTlsConfig(
            serverName = invite.serverName,
            certificatePem = invite.serverCertificatePem,
            certificateSha256 = invite.serverCertificateSha256,
            clientKeyManagers = emptyArray(), // Pairing is server-authenticated; no unapproved phone cert is sent.
        )
        return execute(routes, invite.hostId, tls, method, params)
    }

    private fun execute(
        routes: List<TransportRoute>,
        hostId: String,
        tls: InnerTlsConfig,
        method: String,
        params: JSONObject,
    ): JSONObject {
        if (routes.isEmpty()) throw BridgeFailure("no_route", deliveryUnknown = false)
        val frame = try {
            JSONObject().put("method", method).put("params", JSONObject(params.toString()))
                .toString().toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            throw BridgeFailure("invalid_params", deliveryUnknown = false)
        }
        if (frame.size + 1 > MAX_FRAME_BYTES) {
            frame.fill(0)
            throw BridgeFailure("frame_too_large", deliveryUnknown = false)
        }
        try {
            var lastFailure: BridgeFailure? = null
            for (route in routes) {
                try {
                    return exchange.exchange(route, hostId, tls, method, frame)
                } catch (failure: BridgeFailure) {
                    if (failure.requestWritten) throw failure
                    lastFailure = failure
                }
            }
            throw lastFailure ?: BridgeFailure("transport_failed", deliveryUnknown = false)
        } finally {
            frame.fill(0)
        }
    }

    private fun validateApplicationParams(method: String, params: JSONObject) {
        val fields = when (method) {
            "catalog" -> emptySet()
            "history" -> setOf("thread", "before", "limit")
            "submit" -> setOf("thread", "id", "payload")
            "status", "reconcile" -> setOf("id")
            "sync" -> setOf("thread", "cursor")
            else -> throw BridgeFailure("method_not_allowed", deliveryUnknown = false)
        }
        if (params.keys().asSequence().toSet() != fields) throw BridgeFailure("invalid_params", deliveryUnknown = false)
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
                if (payload.keys().asSequence().toSet() != setOf("text")) throw BridgeFailure("invalid_params", deliveryUnknown = false)
                requireText(payload, "text", 0, MAX_TEXT_CHARS)
            }
            "status", "reconcile" -> requireString(params, "id", 1, MAX_ID_CHARS)
            "sync" -> {
                requireString(params, "thread", 1, MAX_ALIAS_CHARS)
                val cursor = exactLong(params.opt("cursor"))
                if (cursor == null || cursor < 0) throw BridgeFailure("invalid_params", deliveryUnknown = false)
            }
        }
    }

    private fun validatePairingParams(invite: PairingInvite, method: String, params: JSONObject) {
        val expected = when (method) {
            "pair.begin" -> setOf("inviteId", "inviteSecret", "requestId", "phoneName", "clientCertificatePem")
            "pair.prove", "pair.status" -> setOf("inviteId", "inviteSecret", "requestId", "signature")
            else -> throw BridgeFailure("method_not_allowed", deliveryUnknown = false)
        }
        if (params.keys().asSequence().toSet() != expected) throw BridgeFailure("invalid_params", deliveryUnknown = false)
        if (requireString(params, "inviteId", 32, 32) != invite.inviteId ||
            requireString(params, "inviteSecret", 64, 64) != invite.secret()
        ) throw BridgeFailure("invalid_params", deliveryUnknown = false)
        val requestId = requireString(params, "requestId", 36, 36)
        try { java.util.UUID.fromString(requestId) }
        catch (_: Exception) { throw BridgeFailure("invalid_params", deliveryUnknown = false) }
        if (method == "pair.begin") {
            val phoneName = requireString(params, "phoneName", 1, 80)
            if (phoneName != phoneName.trim()) throw BridgeFailure("invalid_params", deliveryUnknown = false)
            // PEM armor contains line breaks; bound it without the general control-character rejection used for RPC strings.
            val certificatePem = params.opt("clientCertificatePem") as? String
                ?: throw BridgeFailure("invalid_params", deliveryUnknown = false)
            if (certificatePem.length !in 1..PairingQr.MAX_DECODED_BYTES) {
                throw BridgeFailure("invalid_params", deliveryUnknown = false)
            }
            val certificate = try { parseX509Pem(certificatePem) }
            catch (_: PairingException) { throw BridgeFailure("invalid_params", deliveryUnknown = false) }
            val key = certificate.publicKey as? java.security.interfaces.ECPublicKey
                ?: throw BridgeFailure("invalid_params", deliveryUnknown = false)
            if (!isP256(key.params) || certificate.subjectX500Principal != certificate.issuerX500Principal) {
                throw BridgeFailure("invalid_params", deliveryUnknown = false)
            }
            try {
                certificate.checkValidity()
                if (!certificate.sigAlgName.equals("SHA256withECDSA", ignoreCase = true)) {
                    throw BridgeFailure("invalid_params", deliveryUnknown = false)
                }
                certificate.verify(certificate.publicKey)
            }
            catch (_: Exception) { throw BridgeFailure("invalid_params", deliveryUnknown = false) }
        } else {
            val signature = requireString(params, "signature", 8, MAX_SIGNATURE_CHARS)
            try {
                if (java.util.Base64.getDecoder().decode(signature).size !in 8..128) {
                    throw BridgeFailure("invalid_params", deliveryUnknown = false)
                }
            } catch (_: IllegalArgumentException) {
                throw BridgeFailure("invalid_params", deliveryUnknown = false)
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

    private fun requireText(params: JSONObject, name: String, min: Int, max: Int): String {
        val value = params.opt(name) as? String ?: throw BridgeFailure("invalid_params", deliveryUnknown = false)
        if (value.length !in min..max || value.contains('\u0000')) throw BridgeFailure("invalid_params", deliveryUnknown = false)
        return value
    }

    private fun exactInt(value: Any?): Int? {
        if (value !is Number) return null
        val number = value.toDouble()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
        return number.toInt()
    }

    private fun exactLong(value: Any?): Long? = if (value is Number) value.toString().toLongOrNull() else null

    private companion object {
        const val MAX_ALIAS_CHARS = 128
        const val MAX_ID_CHARS = 128
        const val MAX_CURSOR_CHARS = 256
        const val MAX_TEXT_CHARS = 8192
        const val MAX_SIGNATURE_CHARS = 256
        const val MAX_FRAME_BYTES = 8192
    }
}

internal sealed class TransportRoute {
    data class Direct(val endpoint: DirectEndpoint) : TransportRoute()
    data class Relay(val endpoint: RelayEndpoint) : TransportRoute()
}

internal data class InnerTlsConfig(
    val serverName: String,
    val certificatePem: String,
    val certificateSha256: String,
    val clientKeyManagers: Array<javax.net.ssl.KeyManager>,
)

internal interface RouteExchange {
    fun exchange(route: TransportRoute, hostId: String, tls: InnerTlsConfig, method: String, frame: ByteArray): JSONObject
}

private class SecureRouteExchange : RouteExchange {
    override fun exchange(route: TransportRoute, hostId: String, tls: InnerTlsConfig, method: String, frame: ByteArray): JSONObject {
        var carrier: Socket? = null
        var inner: SSLSocket? = null
        var requestWritten = false
        try {
            carrier = openCarrier(route, hostId)
            val context = innerContext(tls)
            val peerPort = when (route) {
                is TransportRoute.Direct -> route.endpoint.port
                is TransportRoute.Relay -> route.endpoint.port
            }
            inner = context.socketFactory.createSocket(carrier, tls.serverName, peerPort, true) as? SSLSocket
                ?: throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
            inner.soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
            inner.useClientMode = true
            val protocols = inner.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            if (protocols.isEmpty()) throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
            inner.enabledProtocols = protocols
            val parameters = inner.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            inner.sslParameters = parameters
            inner.startHandshake()
            inner.soTimeout = TLS_READ_TIMEOUT_MS

            requestWritten = true
            val output = inner.outputStream
            output.write(frame)
            output.write(NEWLINE)
            output.flush()
            val responseLine = readLine(inner, MAX_FRAME_BYTES, method = method)
            return try {
                parseResponse(responseLine, method)
            } finally {
                responseLine.fill(0)
            }
        } catch (failure: BridgeFailure) {
            throw BridgeFailure(
                failure.code,
                deliveryUnknown = failure.deliveryUnknown,
                requestWritten = failure.requestWritten || requestWritten,
            )
        } catch (_: SocketTimeoutException) {
            throw BridgeFailure("transport_timeout", deliveryUnknown = method == "submit" && requestWritten, requestWritten = requestWritten)
        } catch (error: SSLException) {
            throw BridgeFailure("tls_verification_failed", deliveryUnknown = method == "submit" && requestWritten, requestWritten = requestWritten).apply { initCause(error) }
        } catch (_: CertificateException) {
            throw BridgeFailure("tls_verification_failed", deliveryUnknown = method == "submit" && requestWritten, requestWritten = requestWritten)
        } catch (_: IOException) {
            throw BridgeFailure("transport_failed", deliveryUnknown = method == "submit" && requestWritten, requestWritten = requestWritten)
        } catch (_: Exception) {
            throw BridgeFailure("transport_failed", deliveryUnknown = method == "submit" && requestWritten, requestWritten = requestWritten)
        } finally {
            try { inner?.close() ?: carrier?.close() } catch (_: Exception) { }
        }
    }

    private fun openCarrier(route: TransportRoute, hostId: String): Socket = when (route) {
        is TransportRoute.Direct -> {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(route.endpoint.host, route.endpoint.port), DIRECT_CONNECT_TIMEOUT_MS)
                socket.soTimeout = TLS_READ_TIMEOUT_MS
                socket
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) { }
                throw BridgeFailure("direct_route_unavailable", deliveryUnknown = false)
            }
        }
        is TransportRoute.Relay -> openRelayCarrier(route.endpoint, hostId)
    }

    private fun openRelayCarrier(relay: RelayEndpoint, hostId: String): SSLSocket {
        var raw: Socket? = null
        var outer: SSLSocket? = null
        try {
            val socket = Socket()
            raw = socket
            socket.connect(InetSocketAddress(relay.host, relay.port), RELAY_CONNECT_TIMEOUT_MS)
            socket.soTimeout = TLS_READ_TIMEOUT_MS
            val context = relayContext(relay)
            outer = context.socketFactory.createSocket(socket, relay.host, relay.port, true) as? SSLSocket
                ?: throw BridgeFailure("relay_tls_unavailable", deliveryUnknown = false)
            outer.soTimeout = TLS_HANDSHAKE_TIMEOUT_MS
            outer.useClientMode = true
            val protocols = outer.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            if (protocols.isEmpty()) throw BridgeFailure("relay_tls_unavailable", deliveryUnknown = false)
            outer.enabledProtocols = protocols
            val sslParameters = outer.sslParameters
            sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            outer.sslParameters = sslParameters
            outer.startHandshake()

            val preface = JSONObject().put("version", 1).put("role", "client").put("hostId", hostId)
                .toString().toByteArray(Charsets.UTF_8)
            if (preface.size + 1 > MAX_RELAY_PREFACE_BYTES) throw BridgeFailure("relay_preface_invalid", false)
            try {
                val output = outer.outputStream
                output.write(preface)
                output.write(NEWLINE)
                output.flush()
            } finally {
                preface.fill(0)
            }
            outer.soTimeout = RELAY_READY_TIMEOUT_MS
            val readyLine = readLine(outer, MAX_RELAY_PREFACE_BYTES, method = null)
            try {
                val ready = JSONObject(decodeUtf8(readyLine))
                if (ready.length() != 1 || ready.opt("ready") != true) {
                    throw BridgeFailure("relay_not_ready", deliveryUnknown = false)
                }
            } catch (e: BridgeFailure) {
                throw e
            } catch (_: Exception) {
                throw BridgeFailure("relay_response_invalid", deliveryUnknown = false)
            } finally {
                readyLine.fill(0)
            }
            return outer
        } catch (failure: BridgeFailure) {
            try { outer?.close() ?: raw?.close() } catch (_: Exception) { }
            throw failure
        } catch (_: SocketTimeoutException) {
            try { outer?.close() ?: raw?.close() } catch (_: Exception) { }
            throw BridgeFailure("relay_timeout", deliveryUnknown = false)
        } catch (_: SSLException) {
            try { outer?.close() ?: raw?.close() } catch (_: Exception) { }
            throw BridgeFailure("relay_tls_verification_failed", deliveryUnknown = false)
        } catch (_: Exception) {
            try { outer?.close() ?: raw?.close() } catch (_: Exception) { }
            throw BridgeFailure("relay_unavailable", deliveryUnknown = false)
        }
    }

    private fun innerContext(config: InnerTlsConfig): SSLContext {
        val certificate = try { parseX509Pem(config.certificatePem) }
        catch (_: PairingException) { throw BridgeFailure("invalid_profile", false) }
        val trustManager = trustManager(certificate, config.certificateSha256)
        return try {
            SSLContext.getInstance("TLS").apply {
                init(config.clientKeyManagers, arrayOf(trustManager), SecureRandom())
            }
        } catch (_: Exception) {
            throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
        }
    }

    private fun relayContext(relay: RelayEndpoint): SSLContext {
        val managers: Array<javax.net.ssl.TrustManager>? = relay.certificatePem?.let { pem ->
            val certificate = try { parseX509Pem(pem) }
            catch (_: PairingException) { throw BridgeFailure("invalid_relay_profile", false) }
            arrayOf(trustManager(certificate, sha256Hex(certificate.encoded)))
        }
        return try {
            SSLContext.getInstance("TLS").apply { init(emptyArray(), managers, SecureRandom()) }
        } catch (_: BridgeFailure) {
            throw BridgeFailure("relay_tls_unavailable", false)
        } catch (_: Exception) {
            throw BridgeFailure("relay_tls_unavailable", false)
        }
    }

    private fun trustManager(certificate: X509Certificate, expectedPinHex: String): X509ExtendedTrustManager {
        val expectedPin = try { decodeHex(expectedPinHex) }
        catch (_: Exception) { throw BridgeFailure("invalid_profile", deliveryUnknown = false) }
        val keyStore = try {
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("profile-trust-anchor", certificate)
            }
        } catch (_: Exception) {
            expectedPin.fill(0)
            throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
        }
        val delegate = try {
            javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
                .trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
                ?: throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
        } catch (e: BridgeFailure) {
            expectedPin.fill(0)
            throw e
        } catch (_: Exception) {
            expectedPin.fill(0)
            throw BridgeFailure("tls_unavailable", deliveryUnknown = false)
        }
        return PinnedTrustManager(delegate, expectedPin).also { expectedPin.fill(0) }
    }

    private fun parseResponse(line: ByteArray, method: String): JSONObject {
        val envelope = try { JSONObject(decodeUtf8(line)) }
        catch (_: Exception) { throw BridgeFailure("invalid_response", method == "submit", requestWritten = true) }
        if (envelope.has("ok") && envelope.opt("ok") != true) {
            val rawError = envelope.opt("error")
            val code = when (rawError) {
                is String -> rawError
                is JSONObject -> rawError.opt("code") as? String
                else -> null
            }?.takeIf { ERROR_CODE_PATTERN.matches(it) } ?: "bridge_error"
            throw BridgeFailure(code, deliveryUnknown = false, requestWritten = true)
        }
        if (envelope.has("error")) {
            val rawError = envelope.opt("error")
            val code = when (rawError) {
                is String -> rawError
                is JSONObject -> rawError.opt("code") as? String
                else -> null
            }?.takeIf { ERROR_CODE_PATTERN.matches(it) } ?: "bridge_error"
            throw BridgeFailure(code, deliveryUnknown = false, requestWritten = true)
        }
        val result = envelope.opt("result") as? JSONObject
            ?: throw BridgeFailure("invalid_response", deliveryUnknown = method == "submit", requestWritten = true)
        return result
    }

    private fun readLine(socket: Socket, maxFrameBytes: Int, method: String?): ByteArray {
        val application = method != null
        val deliveryUnknown = method == "submit"
        val output = ByteArrayOutputStream()
        SocketDeadline(socket, socket.soTimeout.coerceAtLeast(1)).use { deadline ->
        while (true) {
            deadline.armRead()
            val value = try { socket.getInputStream().read() }
            catch (e: SocketTimeoutException) { throw e }
            if (value < 0) {
                throw BridgeFailure(
                    if (application) "response_incomplete" else "relay_response_incomplete",
                    deliveryUnknown = deliveryUnknown,
                    requestWritten = application,
                )
            }
            if (value == NEWLINE) break
            if (output.size() + 2 > maxFrameBytes) {
                throw BridgeFailure(
                    "frame_too_large",
                    deliveryUnknown = deliveryUnknown,
                    requestWritten = application,
                )
            }
            output.write(value)
        }
        if (output.size() + 1 > maxFrameBytes) {
            throw BridgeFailure("frame_too_large", deliveryUnknown = deliveryUnknown, requestWritten = application)
        }
        var line = output.toByteArray()
        if (line.lastOrNull()?.toInt() == CARRIAGE_RETURN) line = line.copyOf(line.size - 1)
        if (line.isEmpty()) throw BridgeFailure("invalid_response", deliveryUnknown = deliveryUnknown, requestWritten = application)
        return line
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun decodeHex(value: String): ByteArray {
        if (!SHA256_PATTERN.matches(value)) throw BridgeFailure("invalid_profile", false)
        return ByteArray(value.length / 2) { index ->
            val offset = index * 2
            value.substring(offset, offset + 2).toInt(16).toByte()
        }
    }

    private class PinnedTrustManager(
        private val delegate: X509TrustManager,
        expectedPin: ByteArray,
    ) : X509ExtendedTrustManager() {
        private val expectedPin = expectedPin.clone()

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            delegate.checkServerTrusted(chain, authType)
            verifyPin(chain)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) {
            if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(chain, authType, socket)
            else delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) {
            if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(chain, authType, socket)
            else delegate.checkServerTrusted(chain, authType)
            verifyPin(chain)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine) {
            if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(chain, authType, engine)
            else delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine) {
            if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(chain, authType, engine)
            else delegate.checkServerTrusted(chain, authType)
            verifyPin(chain)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun verifyPin(chain: Array<out X509Certificate>) {
            if (chain.isEmpty()) throw CertificateException("server_certificate_untrusted")
            val actual = try { MessageDigest.getInstance("SHA-256").digest(chain[0].encoded) }
            catch (_: Exception) { throw CertificateException("server_certificate_untrusted") }
            val matches = try { MessageDigest.isEqual(expectedPin, actual) } finally { actual.fill(0) }
            if (!matches) throw CertificateException("server_certificate_pin_mismatch")
        }
    }

    private companion object {
        const val NEWLINE = 10
        const val CARRIAGE_RETURN = 13
        const val MAX_FRAME_BYTES = 8192
        const val MAX_RELAY_PREFACE_BYTES = 2048
        const val DIRECT_CONNECT_TIMEOUT_MS = 2500
        const val RELAY_CONNECT_TIMEOUT_MS = 8000
        const val TLS_HANDSHAKE_TIMEOUT_MS = 8000
        const val TLS_READ_TIMEOUT_MS = 25000
        const val RELAY_READY_TIMEOUT_MS = 45000
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val ERROR_CODE_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
    }
}

class BridgeFailure(
    val code: String,
    val deliveryUnknown: Boolean,
    internal val requestWritten: Boolean = deliveryUnknown,
) : Exception(code)
