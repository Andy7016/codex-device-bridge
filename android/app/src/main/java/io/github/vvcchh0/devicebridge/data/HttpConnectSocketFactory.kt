package io.github.vvcchh0.devicebridge.data

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** Returns only a verified TLS socket. No relay credentials enter CONNECT. */
internal class HttpConnectSocketFactory(
    private val proxy: Proxy, private val host: String, private val port: Int,
    private val context: SSLContext, private val track: (Socket) -> Unit,
) : SocketFactory() {
    override fun createSocket(): Socket {
        val authority = authority(host, port)
        val address = proxy.address() as? InetSocketAddress
            ?: throw RelayConnectionFailure("relay_proxy_unavailable")
        if (proxy.type() != Proxy.Type.HTTP || address.port !in 1..65535) throw RelayConnectionFailure("relay_proxy_unavailable")
        val raw = Socket(Proxy.NO_PROXY)
        var secure: SSLSocket? = null
        val started = SystemClock.elapsedRealtime()
        try {
            track(raw)
            SocketDeadline(raw, 8000).use { deadline ->
                val destination = if (address.isUnresolved) InetSocketAddress(address.hostString, address.port) else address
                deadline.armRead()
                raw.connect(destination, raw.soTimeout)
                val request = "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n".toByteArray(Charsets.US_ASCII)
                raw.outputStream.write(request)
                raw.outputStream.flush()
                val status = responseStatus(raw, deadline)
                if (status == 407) throw RelayConnectionFailure("relay_proxy_auth_required")
                if (status != 200) throw RelayConnectionFailure("relay_proxy_rejected")
                deadline.armRead()
                val tls = context.socketFactory.createSocket(raw, host, port, true) as? SSLSocket
                    ?: throw IOException("proxy_tls_unavailable")
                secure = tls
                track(tls)
                tls.useClientMode = true
                tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.soTimeout = raw.soTimeout
                tls.startHandshake()
                deadline.armRead() // A completed handshake cannot escape the absolute deadline.
                tls.soTimeout = 0
                return tls
            }
        } catch (error: Exception) {
            try { raw.close() } catch (_: Exception) { }
            try { secure?.close() } catch (_: Exception) { }
            if (error is RelayConnectionFailure) throw error
            val timedOut = error is SocketTimeoutException || SystemClock.elapsedRealtime() - started >= 8000
            if (timedOut) throw RelayConnectionFailure("relay_proxy_timeout", error)
            if (error is SSLException) throw error
            throw RelayConnectionFailure("relay_proxy_unavailable", error)
        }
    }

    private fun responseStatus(socket: Socket, deadline: SocketDeadline): Int {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val ending = byteArrayOf(13, 10, 13, 10)
        while (bytes.size() < 8192 && matched < 4) {
            deadline.armRead()
            val value = socket.inputStream.read()
            if (value < 0) throw RelayConnectionFailure("relay_proxy_protocol")
            bytes.write(value)
            matched = if (value == ending[matched].toInt()) matched + 1 else if (value == 13) 1 else 0
        }
        if (matched != 4) throw RelayConnectionFailure("relay_proxy_protocol")
        val lines = bytes.toString("ISO-8859-1").split("\r\n")
        if (lines.size > 67 || lines.any { '\r' in it || '\n' in it }) throw RelayConnectionFailure("relay_proxy_protocol")
        val first = Regex("HTTP/1\\.[01] ([0-9]{3})(?: [\\x20-\\x7e\\x80-\\xff]*)?").matchEntire(lines.first())
            ?: throw RelayConnectionFailure("relay_proxy_protocol")
        for (line in lines.drop(1).dropLast(2)) {
            val colon = line.indexOf(':')
            if (colon < 1 || !Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(line.substring(0, colon)) ||
                line.substring(colon + 1).any { it.code < 32 && it != '\t' || it.code == 127 }) {
                throw RelayConnectionFailure("relay_proxy_protocol")
            }
        }
        return first.groupValues[1].toInt()
    }

    private fun authority(host: String, port: Int): String {
        if (host.isEmpty() || host.length > 253 || host.any { it.code <= 32 || it.code >= 127 || it in "/\\@?#[]" } || port !in 1..65535) {
            throw RelayConnectionFailure("relay_proxy_protocol")
        }
        try {
            val target = URI("https", null, host, port, null, null, null)
            if (target.host?.removeSurrounding("[", "]") != host) throw IOException()
        } catch (_: Exception) { throw RelayConnectionFailure("relay_proxy_protocol") }
        return if (':' in host) "[$host]:$port" else "$host:$port"
    }

    // The WebSocket library uses only the no-argument overload. Never retarget this factory.
    override fun createSocket(host: String, port: Int): Socket = throw IOException("fixed_proxy_target")
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = throw IOException("fixed_proxy_target")
    override fun createSocket(host: InetAddress, port: Int): Socket = throw IOException("fixed_proxy_target")
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = throw IOException("fixed_proxy_target")
}
