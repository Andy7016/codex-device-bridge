package io.github.vvcchh0.devicebridge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vvcchh0.devicebridge.data.OperationStore
import io.github.vvcchh0.devicebridge.data.ProfileStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileIdentityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private fun fixture(name: String): JSONObject {
        assumeTrue(instrumentation.context.assets.list("")?.contains(name) == true)
        return JSONObject(instrumentation.context.assets.open(name).bufferedReader().use { it.readText() })
    }
    private fun awaitIdle(model: BridgeViewModel) {
        val deadline = System.currentTimeMillis() + 30000
        while (model.ui.value.busy && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertFalse("view model remained busy", model.ui.value.busy)
    }
    private fun create(store: ViewModelStore): BridgeViewModel {
        lateinit var model: BridgeViewModel
        instrumentation.runOnMainSync {
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[BridgeViewModel::class.java]
        }
        awaitIdle(model)
        return model
    }

    @Test fun reconnectAfterDemoDisplaysTheActualNetworkProfile() {
        val original = fixture("bridge-test.json").getJSONObject("profile")
        val profile = ProfileStore(application).importJson(original.toString())
        val store = ViewModelStore()
        try {
            val model = create(store)
            instrumentation.runOnMainSync { model.showDemo(); model.disconnect(); model.connect() }
            awaitIdle(model)
            assertEquals(ConnectionStatus.CONNECTED, model.ui.value.connection)
            assertEquals(profile.displayName, model.ui.value.profileName)
            assertEquals("${profile.host}:${profile.port}", model.ui.value.endpoint)
            assertFalse(model.ui.value.demo)
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }

    @Test fun unreadableNewProfileOutboxCannotRetainAnotherProfilesDisplayData() {
        val original = fixture("bridge-test.json").getJSONObject("profile")
        val another = fixture("bridge-tls-test.json").getJSONObject("validDerivedLeaf").put("displayName", "合成设备 B")
        val profiles = ProfileStore(application)
        val profileB = profiles.importJson(another.toString())
        val operations = OperationStore(application)
        operations.clear(profileB.id)
        operations.save(profileB.id, listOf(PendingOperation("synthetic-b", "synthetic", "合成缓存", "unknown")))
        val encrypted = File(application.noBackupFilesDir, "devicebridge-v1/operations-${profileB.id}.v1.enc")
        val bytes = encrypted.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); encrypted.writeBytes(bytes)
        profiles.importJson(original.toString())
        val input = File(application.cacheDir, "test-profile-b.json").apply { writeText(another.toString()) }
        val store = ViewModelStore()
        try {
            val model = create(store)
            instrumentation.runOnMainSync { model.showDemo(); model.importProfile(Uri.fromFile(input)) }
            awaitIdle(model)
            assertEquals("合成设备 B", model.ui.value.profileName)
            assertEquals("${profileB.host}:${profileB.port}", model.ui.value.endpoint)
            assertTrue(model.ui.value.messages.isEmpty())
            assertTrue(model.ui.value.operations.isEmpty())
            assertNotNull(model.ui.value.error)
            assertTrue(encrypted.exists())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            operations.clear(profileB.id); input.delete(); profiles.importJson(original.toString())
        }
    }
}
