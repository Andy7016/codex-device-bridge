package io.github.vvcchh0.devicebridge

data class SessionInfo(
    val label: String,
    val title: String,
    val kind: String = "codex",
    val canSend: Boolean = false,
    val allowedMessages: List<String> = emptyList(),
    val freeText: Boolean = false,
)

data class ChatMessage(val id: String, val role: String, val text: String, val status: String = "")

data class PendingOperation(
    val id: String,
    val session: String,
    val text: String,
    val state: String,
    val turnStatus: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR, DEMO }

data class UiState(
    val profileName: String? = null,
    val endpoint: String = "尚未连接设备",
    val connection: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val sessions: List<SessionInfo> = emptyList(),
    val selectedSession: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val operations: List<PendingOperation> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lastSync: String? = null,
    val hasMoreHistory: Boolean = false,
    val demo: Boolean = false,
)

data class UiActions(
    val importProfile: () -> Unit,
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val selectSession: (String) -> Unit,
    val refresh: () -> Unit,
    val loadOlder: () -> Unit,
    val send: (String) -> Unit,
    val reconcile: (String) -> Unit,
    val showDemo: () -> Unit,
    val forgetProfile: () -> Unit,
)
