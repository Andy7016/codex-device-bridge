package io.github.vvcchh0.devicebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vvcchh0.devicebridge.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

@RunWith(AndroidJUnit4::class)
class HttpConnectTlsTest {
    private fun relay(): RelayEndpoint {
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("wss-lab.json").bufferedReader().use { it.readText() })
        return PairingQr.parse(fixture.getString("qrText")).relay
    }
    private fun trust(relay: RelayEndpoint): SSLContext {
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(requireNotNull(relay.certificatePem).byteInputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-fixture", certificate) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
    }
    @Test fun verifiedTunnelRetainsTheRelayTlsIdentity() {
        val relay = relay()
        OwnedConnectProxy(targetPort = relay.port).use { proxy ->
            val factory = HttpConnectSocketFactory(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)),
                relay.host, relay.port, trust(relay)) { }
            (factory.createSocket() as SSLSocket).use { socket ->
                assertEquals(relay.host, socket.session.peerHost)
                assertTrue(socket.session.protocol in setOf("TLSv1.2", "TLSv1.3"))
            }
            assertFalse(proxy.credentialLeak.get())
        }
    }
    @Test fun trustedCertificateCannotAuthorizeAWrongRelayHostname() {
        val relay = relay()
        val host = "wrong-relay.synthetic.invalid"
        OwnedConnectProxy(targetPort = relay.port, expectedAuthority = "$host:${relay.port}").use { proxy ->
            val factory = HttpConnectSocketFactory(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)),
                host, relay.port, trust(relay)) { }
            try { factory.createSocket(); fail("wrong hostname accepted") }
            catch (_: SSLException) { }
            assertEquals("$host:${relay.port}", proxy.lastAuthority)
            assertFalse(proxy.credentialLeak.get())
        }
    }
    @Test fun proxyCannotIntroduceAnUntrustedRelayCertificate() {
        val relay = relay()
        OwnedConnectProxy(targetPort = relay.port).use { proxy ->
            val factory = HttpConnectSocketFactory(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)),
                relay.host, relay.port, SSLContext.getDefault()) { }
            try { factory.createSocket(); fail("untrusted certificate accepted") }
            catch (_: SSLException) { }
            assertFalse(proxy.credentialLeak.get())
        }
    }
}
