package io.github.vvcchh0.devicebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vvcchh0.devicebridge.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class HttpConnectTest {
    private fun factory(proxy: OwnedConnectProxy, host: String = "relay.synthetic.invalid") =
        HttpConnectSocketFactory(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)), host, 443, SSLContext.getDefault()) { }

    @Test fun rejectionAuthenticationAndRedirectNeverRetryOrSendRelayCredentials() {
        for ((status, code) in mapOf(407 to "relay_proxy_auth_required", 403 to "relay_proxy_rejected", 302 to "relay_proxy_rejected")) {
            OwnedConnectProxy(response = "HTTP/1.1 $status Synthetic\r\nLocation: https://unowned.invalid/\r\n\r\n").use { proxy ->
                try { factory(proxy).createSocket(); fail("proxy rejection accepted") }
                catch (failure: RelayConnectionFailure) { assertEquals(code, failure.code) }
                assertEquals(1, proxy.requests.get())
                assertFalse(proxy.credentialLeak.get())
            }
        }
    }
    @Test fun malformedOversizedAndFoldedHeadersFailClosed() {
        for (response in listOf("HTTP/1.1 200 OK\nBad: header\r\n\r\n",
            "HTTP/1.1 200 OK\r\n folded: value\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX-Large: " + "a".repeat(9000) + "\r\n\r\n")) {
            OwnedConnectProxy(response = response).use { proxy ->
                try { factory(proxy).createSocket(); fail("bad response accepted") }
                catch (failure: RelayConnectionFailure) { assertEquals("relay_proxy_protocol", failure.code) }
            }
        }
    }
    @Test fun targetIsSentToProxyWithoutLocalTargetDnsAndPlaintextCannotReplaceTls() {
        OwnedConnectProxy(response = "HTTP/1.1 200 OK\r\n\r\nnot-a-tls-server").use { proxy ->
            try { factory(proxy).createSocket(); fail("plaintext accepted as TLS") }
            catch (_: SSLException) { }
            assertEquals("relay.synthetic.invalid:443", proxy.lastAuthority)
            assertFalse(proxy.credentialLeak.get())
        }
    }
    @Test fun targetCannotInjectConnectHeaders() {
        OwnedConnectProxy(response = "HTTP/1.1 200 OK\r\n\r\n").use { proxy ->
            try { factory(proxy, "relay\r\nAuthorization: synthetic").createSocket(); fail("injection accepted") }
            catch (failure: RelayConnectionFailure) { assertEquals("relay_proxy_protocol", failure.code) }
            assertEquals(0, proxy.requests.get())
        }
    }
    @Test fun dripResponseHasAnAbsoluteDeadline() {
        OwnedConnectProxy(drip = true).use { proxy ->
            val start = android.os.SystemClock.elapsedRealtime()
            try { factory(proxy).createSocket(); fail("slow proxy accepted") }
            catch (failure: RelayConnectionFailure) { assertEquals("relay_proxy_timeout", failure.code) }
            assertTrue(android.os.SystemClock.elapsedRealtime() - start in 7000..11500)
        }
    }
    @Test fun connectAndTlsShareOneDeadline() {
        OwnedConnectProxy(response = "HTTP/1.1 200 OK\r\n\r\n", delayMs = 6000, stall = true).use { proxy ->
            val start = android.os.SystemClock.elapsedRealtime()
            try { factory(proxy).createSocket(); fail("stalled TLS accepted") }
            catch (failure: RelayConnectionFailure) { assertEquals("relay_proxy_timeout", failure.code) }
            assertTrue(android.os.SystemClock.elapsedRealtime() - start in 7000..11500)
        }
    }
    @Test fun cancellingCarrierClosesProxySocketBeforeFactoryReturns() {
        OwnedConnectProxy(response = "", stall = true).use { proxy ->
            val original = ProxySelector.getDefault()
            val carrier = AtomicReference<Socket>()
            val failure = AtomicReference<Throwable>()
            try {
                ProxySelector.setDefault(object : ProxySelector() {
                    override fun select(uri: URI) = mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
                    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
                })
                val worker = thread(isDaemon = true) {
                    try { WebSocketCarrier(RelayEndpoint("relay.synthetic.invalid", 443, null, "wss", "/v1/relay", "synthetic-bearer"),
                        "0".repeat(64), SSLContext.getDefault()) { carrier.set(it) } }
                    catch (error: Exception) { failure.set(error) }
                }
                assertTrue(proxy.connected.await(3, TimeUnit.SECONDS))
                requireNotNull(carrier.get()).close()
                worker.join(2000)
                assertFalse(worker.isAlive)
                assertNotNull(failure.get())
                assertFalse(proxy.credentialLeak.get())
            } finally { carrier.get()?.close(); ProxySelector.setDefault(original) }
        }
    }
}
