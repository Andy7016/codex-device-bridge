package io.github.vvcchh0.devicebridge

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vvcchh0.devicebridge.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebSocketIntegrationTest {
    private var proxy: OwnedConnectProxy? = null
    private var oldProxy: java.net.ProxySelector? = null
    @Before fun configureOwnedProxy() {
        if (InstrumentationRegistry.getArguments().getString("httpProxy") != "true") return
        oldProxy = java.net.ProxySelector.getDefault()
        val forwarding = OwnedConnectProxy(targetPort = fixture().getInt("port"))
        proxy = forwarding
        java.net.ProxySelector.setDefault(object : java.net.ProxySelector() {
            override fun select(uri: java.net.URI) = mutableListOf(java.net.Proxy(java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress("127.0.0.1", forwarding.port)))
            override fun connectFailed(uri: java.net.URI, address: java.net.SocketAddress, error: java.io.IOException) = Unit
        })
    }
    @After fun closeOwnedProxy() {
        val forwarding = proxy ?: return
        java.net.ProxySelector.setDefault(oldProxy)
        forwarding.close()
        assertFalse("credentials leaked into CONNECT", forwarding.credentialLeak.get())
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(): JSONObject {
        val assets = instrumentation.context.assets
        assumeTrue("owned WSS lab was not prepared", assets.list("")?.contains("wss-lab.json") == true)
        return JSONObject(assets.open("wss-lab.json").bufferedReader().use { it.readText() })
    }

    @Test fun realKeystoreTlsUnknownRecoveryAndSignedRouteMigration() {
        val fixture = fixture()
        val root = File(instrumentation.targetContext.noBackupFilesDir, "wss-fixture-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        val context = object : ContextWrapper(instrumentation.targetContext) { override fun getNoBackupFilesDir(): File = root }
        val repository = BridgeRepository(context)
        var alias: String? = null
        try {
            var progress = repository.pairing.startOrResume(fixture.getString("qrText"))
            val deadline = System.currentTimeMillis() + 55000
            while (progress.phase == PairingPhase.WAITING_FOR_APPROVAL && System.currentTimeMillis() < deadline) {
                Thread.sleep(250); progress = repository.pairing.checkStatus()
            }
            assertEquals(PairingPhase.APPROVED, progress.phase)
            proxy?.let { assertTrue("HTTP CONNECT was not used", it.requests.get() > 0) }
            val profile = requireNotNull(progress.profile)
            alias = profile.clientKeyAlias
            assertEquals(3, profile.schemaVersion)
            val session = repository.catalog(profile).single { it.canSend }
            val history = repository.history(profile, session.label)
            assertTrue(history.messages.isNotEmpty())
            val updateQr = history.messages.single { it.id == "connection-update" }.text
            val id = fixture.getString("operationId")
            val submitted = repository.send(profile, session, fixture.getString("message"), id)
            assertEquals("unknown", submitted.state)
            val restored = BridgeRepository(context)
            assertEquals(id, restored.outbox.load(profile.id).single().id)

            // Reconstruct the old v2 shape with a dead route, keeping the original Keystore identity.
            val old = JSONObject().put("schemaVersion", 2).put("displayName", profile.displayName)
                .put("hostId", profile.hostId).put("serverName", profile.serverName)
                .put("serverCertificatePem", profile.serverCertificatePem).put("serverCertificateSha256", profile.serverCertificateSha256)
                .put("clientKeyAlias", profile.clientKeyAlias).put("clientCertificateSha256", profile.clientCertificateSha256)
                .put("relay", JSONObject().put("host", "old-relay.invalid").put("port", 443).put("certificatePem", JSONObject.NULL))
                .put("directEndpoints", org.json.JSONArray())
            assertEquals(profile.id, restored.profiles.importJson(old.toString()).id)
            val migrated = restored.profiles.applyConnectionUpdate(updateQr)
            assertEquals(profile.id, migrated.id)
            assertEquals(alias, migrated.clientKeyAlias)
            assertEquals(id, restored.outbox.load(migrated.id).single().id)
            val reconciled = restored.reconcile(migrated, id)
            assertEquals("completed", reconciled.state)
            assertEquals("completed", restored.send(migrated, session, fixture.getString("message"), id).state)

            // Both hostname and exact leaf pin fail before any application write.
            for (invalid in listOf(migrated.copy(serverName = "wrong.bridge.invalid"),
                migrated.copy(serverCertificateSha256 = "0".repeat(64)))) {
                try { BridgeTransport().request(invalid, "catalog", JSONObject()); fail("wrong host accepted") }
                catch (failure: BridgeFailure) { assertFalse(failure.requestWritten) }
            }
            val encoded = updateQr.dropLast(4) + "AAAA"
            try { restored.profiles.applyConnectionUpdate(encoded); fail("forged update accepted") }
            catch (_: Exception) { }
            assertEquals(migrated.id, restored.profiles.load()?.id)
            assertEquals(alias, restored.profiles.load()?.clientKeyAlias)
        } finally {
            repository.cancelTransport()
            alias?.let { AndroidDeviceIdentity().delete(it) }
            // Only this method's freshly created isolated directory is removed.
            root.walkBottomUp().forEach { assertTrue(it.delete()) }
        }
    }

    @Test fun rejectedRouteTicketReportsAdmissionFailureBeforeApplicationWrite() {
        val original = PairingQr.parse(fixture().getString("qrText"))
        val invite = original.copy(relay = original.relay.withTicket("invalid-synthetic-ticket"), directEndpoints = emptyList())
        val identity = AndroidDeviceIdentity().create()
        try {
            val params = JSONObject().put("inviteId", invite.inviteId).put("inviteSecret", invite.secret())
                .put("requestId", UUID.randomUUID().toString()).put("phoneName", "Synthetic rejection")
                .put("clientCertificatePem", identity.certificatePem)
            try { BridgeTransport().requestPairing(invite, "pair.begin", params); fail("invalid ticket accepted") }
            catch (failure: BridgeFailure) {
                assertEquals("relay_admission_rejected", failure.code)
                assertFalse(failure.requestWritten)
                assertFalse(failure.deliveryUnknown)
                proxy?.let { assertTrue("HTTP CONNECT was not used", it.requests.get() > 0) }
            }
        } finally { AndroidDeviceIdentity().delete(identity.alias) }
    }

    @Test fun cancelledBeforeWriteCannotStartAnotherRoute() {
        val fixture = fixture()
        val invite = PairingQr.parse(fixture.getString("qrText"))
            .copy(directEndpoints = listOf(DirectEndpoint("127.0.0.1", 1)))
        val identity = AndroidDeviceIdentity().create()
        var attempts = 0
        lateinit var transport: BridgeTransport
        transport = BridgeTransport(object : RouteExchange {
            override fun exchange(route: TransportRoute, hostId: String, tls: InnerTlsConfig, method: String, frame: ByteArray): JSONObject {
                attempts++; transport.cancelActive()
                throw BridgeFailure("cancelled", false)
            }
        })
        try {
            val params = JSONObject().put("inviteId", invite.inviteId).put("inviteSecret", invite.secret())
                .put("requestId", UUID.randomUUID().toString()).put("phoneName", "Synthetic cancellation")
                .put("clientCertificatePem", identity.certificatePem)
            try { transport.requestPairing(invite, "pair.begin", params); fail("cancelled request accepted") }
            catch (_: BridgeFailure) { }
            assertEquals(1, attempts)
        } finally { AndroidDeviceIdentity().delete(identity.alias) }
    }
}
