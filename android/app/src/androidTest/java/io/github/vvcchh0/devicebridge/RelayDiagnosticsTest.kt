package io.github.vvcchh0.devicebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vvcchh0.devicebridge.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.InetSocketAddress
import java.net.SocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException

@RunWith(AndroidJUnit4::class)
class RelayDiagnosticsTest {
    @Test fun explicitHttpProxyFailsBeforeNetworkWithAnActionableCode() {
        val original = ProxySelector.getDefault()
        try {
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI) = mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9)))
                override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
            })
            val endpoint = RelayEndpoint("synthetic-relay.invalid", 443, null, "wss", "/v1/relay", "synthetic")
            try {
                WebSocketCarrier(endpoint, "0".repeat(64), SSLContext.getDefault()) { }
                fail("explicit proxy unexpectedly accepted")
            } catch (failure: RelayConnectionFailure) {
                assertEquals("relay_http_proxy_unsupported", failure.code)
            }
        } finally { ProxySelector.setDefault(original) }
    }
    @Test fun networkAndProxyFailuresRemainDistinct() {
        assertEquals("relay_http_proxy_unsupported", relayFailureCode(IOException("http_proxy_not_supported")))
        assertEquals("relay_dns_failed", relayFailureCode(IOException("wrapped", UnknownHostException("private host"))))
        assertEquals("relay_tls_verification_failed", relayFailureCode(IOException("wrapped", SSLHandshakeException("private certificate"))))
        assertEquals("relay_wait_timeout", relayFailureCode(SocketTimeoutException("relay_ready_timeout")))
    }
    @Test fun onlyFixedCodesEscapeUntrustedHandshakeMessages() {
        for ((status, code) in mapOf(403 to "relay_admission_rejected", 429 to "relay_capacity", 503 to "relay_service_unavailable")) {
            val result = relayFailureCode(IOException("wrapped", IOException("Invalid status code received: $status Status line: synthetic-private")))
            assertEquals(code, result)
            assertFalse(result.contains("synthetic-private"))
        }
        assertEquals("relay_connection_failed", relayFailureCode(IOException("Bearer synthetic-secret")))
    }
    @Test fun socketTimeoutDoesNotHideANestedTlsFailure() {
        val wrapped = SocketTimeoutException("open failed").apply { initCause(SSLHandshakeException("synthetic")) }
        assertEquals("relay_tls_verification_failed", relayFailureCode(wrapped))
    }
}
