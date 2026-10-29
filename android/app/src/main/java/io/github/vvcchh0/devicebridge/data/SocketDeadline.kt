package io.github.vvcchh0.devicebridge.data

import android.os.SystemClock
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Timer
import java.util.TimerTask

/** Absolute frame deadline: trickled bytes cannot continually reset SO_TIMEOUT. */
internal class SocketDeadline(private val socket: Socket, timeoutMs: Int) : AutoCloseable {
    private val expires = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1)
    private val timer = Timer("devicebridge-frame-deadline", true)
    init {
        timer.schedule(object : TimerTask() {
            override fun run() { try { socket.close() } catch (_: Exception) { } }
        }, timeoutMs.coerceAtLeast(1).toLong())
    }
    fun armRead() {
        val remaining = expires - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw SocketTimeoutException("frame_deadline")
        socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }
    override fun close() { timer.cancel() }
}
