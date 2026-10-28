package io.github.vvcchh0.devicebridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vvcchh0.devicebridge.data.BridgeFailure
import io.github.vvcchh0.devicebridge.data.BridgeTransport
import io.github.vvcchh0.devicebridge.data.ProfileStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TlsRejectionTest {
    @Test fun actualTlsRejectsWrongHostnameAndDifferentLeafBeforeApplicationData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue(assets.list("")?.contains("bridge-tls-test.json") == true)
        val fixture = JSONObject(assets.open("bridge-tls-test.json").bufferedReader().use { it.readText() })
        val original = JSONObject(assets.open("bridge-test.json").bufferedReader().use { it.readText() }).getJSONObject("profile")
        val store = ProfileStore(instrumentation.targetContext)
        val transport = BridgeTransport()
        try {
            val control = store.importJson(fixture.getJSONObject("validDerivedLeaf").toString())
            assertTrue(transport.request(control, "catalog", JSONObject()).getJSONArray("sessions").length() > 0)
            for (name in listOf("wrongHostname", "differentLeaf")) {
                val profile = store.importJson(fixture.getJSONObject(name).toString())
                try { transport.request(profile, "catalog", JSONObject()); fail("invalid TLS peer accepted: $name") }
                catch (failure: BridgeFailure) { assertEquals("tls_verification_failed", failure.code) }
            }
            val valid = store.importJson(original.toString())
            assertTrue(transport.request(valid, "catalog", JSONObject()).getJSONArray("sessions").length() > 0)
        } finally { store.importJson(original.toString()) }
    }
}

@RunWith(AndroidJUnit4::class)
class RevocationTest {
    @Test fun revokedCertificateIdentityCannotReadCatalog() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyRevoked") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = ProfileStore(context).load()!!
        try { BridgeTransport().request(profile, "catalog", JSONObject()); fail("revoked identity accepted") }
        catch (failure: BridgeFailure) { assertEquals("denied", failure.code) }
    }
}
