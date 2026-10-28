package io.github.vvcchh0.devicebridge

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vvcchh0.devicebridge.ui.BridgeApp
import io.github.vvcchh0.devicebridge.ui.DeviceBridgeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BridgeUiTest {
    @get:Rule val compose = createComposeRule()
    private fun actions(reconcile: (String) -> Unit = {}) = UiActions({}, {}, {}, {}, {}, {}, {}, reconcile, {}, {})

    @Test fun unpairedDeviceCannotConnectOrSend() {
        compose.setContent { DeviceBridgeTheme { BridgeApp(UiState(), actions()) } }
        compose.onNodeWithTag("nav-device").performClick()
        compose.onNodeWithTag("import-profile").assertExists()
        compose.onNodeWithTag("connect").assertIsNotEnabled()
    }

    @Test fun unknownOperationOffersReadOnlyReconciliation() {
        var reconciled = ""
        val state = UiState(profileName = "合成设备", connection = ConnectionStatus.CONNECTED,
            sessions = listOf(SessionInfo("test", "合成会话", canSend = true)), selectedSession = "test",
            operations = listOf(PendingOperation("original-id", "test", "合成消息", "unknown")))
        compose.setContent { DeviceBridgeTheme { BridgeApp(state, actions { reconciled = it }) } }
        compose.onNodeWithTag("nav-activity").performClick()
        compose.onNodeWithTag("reconcile").performClick()
        assertEquals("original-id", reconciled)
    }
}
