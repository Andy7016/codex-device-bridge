package io.github.vvcchh0.devicebridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import io.github.vvcchh0.devicebridge.ui.BridgeApp
import io.github.vvcchh0.devicebridge.ui.DeviceBridgeTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: BridgeViewModel
    private val profilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importProfile(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[BridgeViewModel::class.java]
        setContent {
            val state by model.ui.collectAsStateWithLifecycle()
            DeviceBridgeTheme {
                BridgeApp(state, UiActions(
                    importProfile = { profilePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                    connect = { model.connect() }, disconnect = { model.disconnect() }, selectSession = { model.selectSession(it) },
                    refresh = { model.refresh() }, loadOlder = { model.loadOlder() }, send = { model.send(it) },
                    reconcile = { model.reconcile(it) }, showDemo = { model.showDemo() }, forgetProfile = { model.forgetProfile() },
                ))
            }
        }
    }
    override fun onStart() { super.onStart(); if (::model.isInitialized) model.setForeground(true) }
    override fun onStop() { if (::model.isInitialized) model.setForeground(false); super.onStop() }
}
