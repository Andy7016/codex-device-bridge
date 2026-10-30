package io.github.vvcchh0.devicebridge

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Test-only proxy: either a fixed response or forwarding to one owned loopback port. */
internal class OwnedConnectProxy(
    private val targetPort: Int? = null,
    private val response: String? = null,
    private val delayMs: Long = 0,
    private val drip: Boolean = false,
    private val stall: Boolean = false,
    private val expectedAuthority: String? = null,
) : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val stopped = CountDownLatch(1)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val workers = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    val connected = CountDownLatch(1)
    val requests = AtomicInteger()
    val credentialLeak = AtomicBoolean()
    @Volatile var lastAuthority = ""
    val port get() = server.localPort
    private val acceptor = thread(isDaemon = true, name = "owned-connect-proxy") {
        while (stopped.count > 0 && requests.get() < 100) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            sockets.add(socket)
            workers.add(thread(isDaemon = true) {
                try { serve(socket) } catch (_: Exception) { }
                finally { sockets.remove(socket); try { socket.close() } catch (_: Exception) { } }
            })
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10000
        val head = ByteArrayOutputStream()
        while (head.size() < 8192) {
            val value = socket.inputStream.read()
            if (value < 0) return
            head.write(value)
            if (head.size() >= 4 && head.toByteArray().takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
        }
        val text = head.toString("US-ASCII")
        requests.incrementAndGet()
        credentialLeak.set(credentialLeak.get() || Regex("(?im)^(Authorization|Proxy-Authorization|Cookie|Sec-WebSocket-[^:]+):").containsMatchIn(text))
        lastAuthority = text.lineSequence().first().split(' ').getOrElse(1) { "" }
        connected.countDown()
        if (stopped.await(delayMs, TimeUnit.MILLISECONDS)) return
        if (drip) {
            for (byte in "HTTP/1.1 200 OK\r\n\r\n".toByteArray()) {
                socket.outputStream.write(byte.toInt()); socket.outputStream.flush()
                if (stopped.await(500, TimeUnit.MILLISECONDS)) return
            }
            return
        }
        if (response != null) {
            socket.outputStream.write(response.toByteArray(Charsets.ISO_8859_1)); socket.outputStream.flush()
            if (stall) stopped.await(12, TimeUnit.SECONDS)
            return
        }
        if (lastAuthority != (expectedAuthority ?: "127.0.0.1:$targetPort")) {
            socket.outputStream.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray()); return
        }
        val target = Socket()
        sockets.add(target)
        try {
            target.connect(InetSocketAddress("127.0.0.1", requireNotNull(targetPort)), 2000)
            socket.outputStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            socket.outputStream.flush()
            val back = thread(isDaemon = true) {
                try { target.inputStream.copyTo(socket.outputStream, 16384) } catch (_: Exception) { }
                finally { try { socket.close() } catch (_: Exception) { } }
            }
            try { socket.inputStream.copyTo(target.outputStream, 16384) } catch (_: Exception) { }
            finally { target.close(); back.join(1000) }
        } finally { sockets.remove(target); target.close() }
    }
    override fun close() {
        stopped.countDown(); server.close()
        sockets.toList().forEach { try { it.close() } catch (_: Exception) { } }
        acceptor.join(1000)
        workers.forEach { it.join(100) }
    }
}
