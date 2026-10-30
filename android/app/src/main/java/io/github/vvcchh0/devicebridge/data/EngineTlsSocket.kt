package io.github.vvcchh0.devicebridge.data

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult

/** TLS independent of a native fd; used only for WSS, retaining standard trust and hostname checks. */
internal class EngineTlsSocket(context: SSLContext, private val carrier: Socket, host: String, port: Int) : Socket() {
    private val engine = context.createSSLEngine(host, port).apply {
        useClientMode = true
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }
    private val network = ByteBuffer.allocate(65536).apply { flip() }
    private val output = ByteBuffer.allocate(65536)
    private val plaintext = ByteBuffer.allocate(65536).apply { flip() }
    @Volatile private var ended = false
    private var inboundEnded = false

    init {
        if (engine.session.packetBufferSize > 65536 || engine.session.applicationBufferSize > 65536) throw IOException("tls_buffer_limit")
        carrier.soTimeout = 10000
        SocketDeadline(carrier, 10000).use {
            engine.beginHandshake()
            handshake()
        }
    }

    private fun receive() {
        network.compact()
        if (!network.hasRemaining()) throw IOException("tls_buffer_limit")
        val count = carrier.inputStream.read(network.array(), network.position(), network.remaining())
        if (count < 0) throw EOFException("inner_tls_closed")
        network.position(network.position() + count)
        network.flip()
    }

    private fun unwrap(): SSLEngineResult {
        while (true) {
            plaintext.compact()
            val result = engine.unwrap(network, plaintext)
            plaintext.flip()
            when (result.status) {
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> receive()
                SSLEngineResult.Status.BUFFER_OVERFLOW -> throw IOException("tls_buffer_limit")
                SSLEngineResult.Status.CLOSED -> { inboundEnded = true; return result }
                else -> return result
            }
        }
    }

    private fun wrap(source: ByteBuffer): SSLEngineResult {
        output.clear()
        val result = engine.wrap(source, output)
        if (result.status != SSLEngineResult.Status.OK) throw IOException("inner_tls_wrap_failed")
        if (output.position() > 0) carrier.outputStream.write(output.array(), 0, output.position())
        return result
    }

    private fun handshake() {
        var steps = 0
        while (!ended) {
            if (++steps > 4096) throw IOException("tls_handshake_limit")
            when (engine.handshakeStatus.name) {
                "NEED_TASK" -> { var task = engine.delegatedTask; while (task != null) { task.run(); task = engine.delegatedTask } }
                "NEED_WRAP" -> wrap(ByteBuffer.allocate(0))
                "NEED_UNWRAP", "NEED_UNWRAP_AGAIN" -> { unwrap(); if (inboundEnded) throw EOFException("inner_tls_closed") }
                else -> return
            }
        }
        throw IOException("carrier_closed")
    }

    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (!plaintext.hasRemaining()) {
                if (inboundEnded) return -1
                unwrap()
                if (!inboundEnded) handshake()
            }
            val count = minOf(length, plaintext.remaining())
            plaintext.get(target, offset, count)
            return count
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(data: ByteArray, offset: Int, length: Int) {
            val source = ByteBuffer.wrap(data, offset, length)
            var steps = 0
            while (source.hasRemaining()) {
                if (++steps > 4096 || ended) throw IOException("tls_write_limit")
                handshake(); wrap(source)
            }
        }
    }

    override fun getSoTimeout(): Int = carrier.soTimeout
    override fun setSoTimeout(value: Int) { carrier.soTimeout = value }
    override fun isConnected(): Boolean = carrier.isConnected && !ended
    override fun isClosed(): Boolean = ended
    override fun close() { ended = true; carrier.close() }
}
