package io.github.vvcchh0.devicebridge

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vvcchh0.devicebridge.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadOnlyCatalogTest {
    @Test fun oneHundredSessionsRemainReadOnlyThroughTheProxyAndLargeResponse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = JSONObject(instrumentation.context.assets.open("wss-lab.json").bufferedReader().use { it.readText() })
        assertEquals(100, fixture.getInt("bulkReadCount"))
        val root = File(instrumentation.targetContext.noBackupFilesDir, "readonly-catalog-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir(): File = root }
        val repository = BridgeRepository(context)
        val original = ProxySelector.getDefault()
        var alias: String? = null
        OwnedConnectProxy(targetPort = fixture.getInt("port")).use { proxy ->
            try {
                ProxySelector.setDefault(object : ProxySelector() {
                    override fun select(uri: URI) = mutableListOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
                    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
                })
                var progress = repository.pairing.startOrResume(fixture.getString("qrText"))
                val deadline = System.currentTimeMillis() + 55000
                while (progress.phase == PairingPhase.WAITING_FOR_APPROVAL && System.currentTimeMillis() < deadline) {
                    Thread.sleep(250); progress = repository.pairing.checkStatus()
                }
                assertEquals(PairingPhase.APPROVED, progress.phase)
                val profile = requireNotNull(progress.profile)
                alias = profile.clientKeyAlias
                val sessions = repository.catalog(profile)
                assertEquals(100, sessions.size)
                assertTrue(sessions.sumOf { it.title.toByteArray().size } > 8192)
                assertTrue(sessions.none { it.canSend || it.freeText })
                try { repository.send(profile, sessions.first(), "synthetic text"); fail("read-only send accepted") }
                catch (failure: BridgeFailure) { assertEquals("message_not_authorized", failure.code) }
                assertTrue(repository.outbox.load(profile.id).isEmpty())
                assertTrue(proxy.requests.get() > 0)
                assertFalse(proxy.credentialLeak.get())
            } finally {
                repository.cancelTransport(); ProxySelector.setDefault(original)
                alias?.let { AndroidDeviceIdentity().delete(it) }
                root.walkBottomUp().forEach { assertTrue(it.delete()) }
            }
        }
    }
}
