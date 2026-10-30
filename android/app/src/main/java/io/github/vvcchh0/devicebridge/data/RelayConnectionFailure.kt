package io.github.vvcchh0.devicebridge.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only fixed local codes reach the UI; never display server text, URLs or headers. */
internal class RelayConnectionFailure(val code: String, cause: Throwable? = null) : IOException(code, cause)

internal fun relayFailureCode(error: Throwable): String {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.size < 8 && seen.add(current)) {
        if (current is RelayConnectionFailure) return current.code
        if (current is SSLException) return "relay_tls_verification_failed"
        if (current is UnknownHostException) return "relay_dns_failed"
        if (current is ConnectException) return "relay_connect_failed"
        val text = current.message.orEmpty().take(512)
        if (text == "http_proxy_not_supported") return "relay_http_proxy_unsupported"
        if (text == "relay_ready_timeout") return "relay_wait_timeout"
        val status = Regex("(?i)status code(?: received)?\\s*:\\s*(401|403|429|503)\\b").find(text)?.groupValues?.get(1)
        if (status != null) return when (status) {
            "401", "403" -> "relay_admission_rejected"
            "429" -> "relay_capacity"
            else -> "relay_service_unavailable"
        }
        if (current is SocketTimeoutException && current.cause == null) return "relay_connect_timeout"
        current = current.cause
    }
    return "relay_connection_failed"
}
