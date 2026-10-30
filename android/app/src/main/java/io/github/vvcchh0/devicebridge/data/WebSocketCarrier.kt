package io.github.vvcchh0.devicebridge.data

import org.java_websocket.client.WebSocketClient
import org.java_websocket.WebSocketImpl
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.protocols.Protocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.Proxy
import java.net.ProxySelector
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/** Message framing ends here. Inner TLS sees a bounded, ordered, abortable byte stream. */
internal class WebSocketCarrier(relay: RelayEndpoint, hostId: String, context: SSLContext, track: (Socket) -> Unit) : Socket() {
    private val lock = Object()
    private val sendGate = Object()
    private val closeScheduled = java.util.concurrent.atomic.AtomicBoolean()
    private val queue = ArrayDeque<ByteArray>()
    private var headOffset = 0
    private var queued = 0
    @Volatile private var ended = false
    @Volatile private var timeout = 10000
    private var ready = false
    private val opened = CountDownLatch(1)
    private var failure: IOException? = null
    private val authority = if (relay.host.contains(':')) "[${relay.host}]" else relay.host
    private val targetUri = URI("wss://$authority:${relay.port}${relay.path}/$hostId/client")
    private val client = object : WebSocketClient(
        targetUri,
        Draft_6455(emptyList(), listOf(Protocol("device-bridge.v1")), 16384),
        mapOf("Authorization" to "Bearer ${relay.ticket() ?: throw IOException("relay_ticket_missing")}"), 8000,
    ) {
        override fun onSetSSLParameters(parameters: SSLParameters) {
            super.onSetSSLParameters(parameters)
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.protocols = arrayOf("TLSv1.2", "TLSv1.3").filter { socket?.let { s ->
                s !is javax.net.ssl.SSLSocket || it in s.supportedProtocols
            } ?: true }.toTypedArray()
        }
        override fun onOpen(handshake: ServerHandshake) {
            if (handshake.getFieldValue("Sec-WebSocket-Protocol") != "device-bridge.v1" ||
                handshake.getFieldValue("Sec-WebSocket-Extensions").isNotEmpty()) {
                requestAbort(IOException("relay_protocol_mismatch")); return
            }
            opened.countDown()
        }
        override fun onMessage(text: String) {
            val invalid = synchronized(lock) {
                if (ready || text != "{\"ready\":true}\n") true
                else { ready = true; lock.notifyAll(); false }
            }
            if (invalid) requestAbort(IOException("invalid_relay_ready"))
        }
        override fun onMessage(data: ByteBuffer) {
            val invalid = synchronized(lock) {
                if (!ready || ended || data.remaining() !in 1..16384 || queued + data.remaining() > 65536) {
                    true
                } else {
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    queue.addLast(bytes); queued += bytes.size
                    lock.notifyAll(); false
                }
            }
            if (invalid) requestAbort(IOException("carrier_buffer_limit"))
        }
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            synchronized(lock) { ended = true; opened.countDown(); lock.notifyAll() }
        }
        override fun onError(error: Exception) { requestAbort(IOException("websocket_failed", error)) }
    }

    init {
        client.setSocketFactory(context.socketFactory)
        client.isDaemon = true
        client.connectionLostTimeout = 20
        try {
            track(this)
            val selected = ProxySelector.getDefault()?.select(URI(targetUri.toString().replaceFirst("wss:", "https:")))?.firstOrNull()
            if (selected != null && selected.type() == Proxy.Type.HTTP) throw IOException("http_proxy_not_supported")
            if (selected != null && selected.type() == Proxy.Type.SOCKS) client.setProxy(selected)
            // Use the application's default network. Never bind beneath a user VPN.
            if (ended) throw IOException("carrier_closed")
            client.connect()
            if (!opened.await(8, TimeUnit.SECONDS) || ended || !client.isOpen) throw SocketTimeoutException("websocket_connect_timeout")
            synchronized(sendGate) {
                if (ended) throw IOException("carrier_closed")
                client.send(org.json.JSONObject().put("version", 2).put("hostId", hostId).put("role", "client").toString())
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 45000
            synchronized(lock) {
                while (!ready && !ended) {
                    val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw SocketTimeoutException("relay_ready_timeout")
                    lock.wait(remaining)
                }
                if (!ready || ended) throw failure ?: IOException("relay_unavailable")
            }
        } catch (error: Exception) { abort(IOException("relay_unavailable", error)); throw error }
    }

    private fun requestAbort(error: IOException) {
        if (closeScheduled.compareAndSet(false, true)) CLOSER.execute { abort(error) }
    }

    private fun abort(error: IOException? = null) {
        synchronized(sendGate) {
        synchronized(lock) {
            if (error != null) failure = error
            ended = true
            queue.forEach { it.fill(0) }; queue.clear(); queued = 0; headOffset = 0
            opened.countDown(); lock.notifyAll()
        }
        }
        // Library close can synchronously invoke callbacks. No application locks held here.
        try { client.socket?.close() } catch (_: Exception) { }
        try { client.closeConnection(1006, "carrier_closed") } catch (_: Exception) { }
    }

    override fun isConnected(): Boolean = ready && !ended
    override fun isClosed(): Boolean = ended
    override fun getSoTimeout(): Int = timeout
    override fun setSoTimeout(value: Int) { timeout = value.coerceAtLeast(1) }
    override fun close() = abort()
    override fun shutdownInput() = abort()
    override fun shutdownOutput() = abort()

    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val deadline = android.os.SystemClock.elapsedRealtime() + timeout
            synchronized(lock) {
                while (queue.isEmpty() && !ended) {
                    val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0) throw SocketTimeoutException("carrier_read_timeout")
                    lock.wait(remaining)
                }
                if (queue.isEmpty()) { failure?.let { throw it }; return -1 }
                val first = queue.first
                val count = minOf(length, first.size - headOffset)
                first.copyInto(target, offset, headOffset, headOffset + count)
                headOffset += count; queued -= count
                if (headOffset == first.size) { queue.removeFirst().fill(0); headOffset = 0 }
                return count
            }
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(data: ByteArray, offset: Int, length: Int) {
            if (ended) throw IOException("carrier_closed")
            var position = offset
            while (position < offset + length) {
                val chunk = minOf(16384, offset + length - position)
                val accepted = synchronized(sendGate) {
                    if (ended) throw IOException("carrier_closed")
                    val connection = client.connection as? WebSocketImpl ?: throw IOException("carrier_unavailable")
                    if (connection.outQueue.sumOf { it.remaining() } + chunk > 65536) false
                    else { client.send(data.copyOfRange(position, position + chunk)); true }
                }
                if (!accepted) { abort(); throw IOException("carrier_send_limit") }
                position += chunk
            }
        }
    }

    private companion object {
        val CLOSER = java.util.concurrent.Executors.newSingleThreadExecutor { action -> Thread(action, "bridge-wss-close").apply { isDaemon = true } }
    }
}
