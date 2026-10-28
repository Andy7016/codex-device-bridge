package io.github.vvcchh0.devicebridge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.vvcchh0.devicebridge.data.BridgeFailure
import io.github.vvcchh0.devicebridge.data.ConnectionProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BridgeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BridgeRepository(application)
    private val mutable = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = mutable
    private var profile: ConnectionProfile? = null
    private var nextBefore: String? = null
    private var runtimeStatus = "unknown"
    private var historyExpanded = false
    private var polling: Job? = null
    private var actionJob: Job? = null
    private var foreground = false

    init {
        action {
            profile = withContext(Dispatchers.IO) { repository.profiles.load() }
            profile?.let { p ->
                mutable.update { it.copy(profileName = p.displayName, endpoint = "${p.host}:${p.port}") }
                val operations = withContext(Dispatchers.IO) { repository.outbox.load(p.id) }
                mutable.update { it.copy(profileName = p.displayName, endpoint = "${p.host}:${p.port}", operations = operations) }
            }
        }
    }

    fun importProfile(uri: Uri) = action {
        val imported = withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("missing_profile")
            val bytes = stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 131072) error("profile_too_large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            try { repository.profiles.importJson(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
        }
        polling?.cancel(); profile = imported; nextBefore = null; historyExpanded = false
        mutable.value = UiState(profileName = imported.displayName, endpoint = "${imported.host}:${imported.port}", busy = true)
        val operations = withContext(Dispatchers.IO) { repository.outbox.load(imported.id) }
        mutable.value = UiState(profileName = imported.displayName, endpoint = "${imported.host}:${imported.port}", operations = operations,
            busy = true, notice = "连接资料已安全保存。点击连接验证电脑身份。")
    }

    fun connect() = action {
        val p = profile ?: return@action
        nextBefore = null; historyExpanded = false
        mutable.value = UiState(profileName = p.displayName, endpoint = "${p.host}:${p.port}",
            connection = ConnectionStatus.CONNECTING, busy = true)
        val savedOperations = withContext(Dispatchers.IO) { repository.outbox.load(p.id) }
        mutable.update { it.copy(operations = savedOperations) }
        val sessions = withContext(Dispatchers.IO) { repository.catalog(p) }
        mutable.update { it.copy(sessions = sessions, selectedSession = sessions.firstOrNull()?.label,
            connection = ConnectionStatus.CONNECTED, messages = emptyList(), notice = null) }
        refreshData(p)
    }

    fun disconnect() {
        actionJob?.cancel(); polling?.cancel()
        mutable.update { it.copy(connection = ConnectionStatus.DISCONNECTED, notice = "已断开，原操作记录保留在本机。", demo = false) }
    }

    fun selectSession(label: String) = action {
        if (mutable.value.demo || mutable.value.sessions.none { it.label == label }) return@action
        nextBefore = null; historyExpanded = false
        mutable.update { it.copy(selectedSession = label, messages = emptyList()) }
        profile?.let { refreshData(it) }
    }

    fun refresh() = action {
        historyExpanded = false
        if (!mutable.value.demo) profile?.let { refreshData(it) }
    }

    fun loadOlder() = action {
        val p = profile ?: return@action
        val label = mutable.value.selectedSession ?: return@action
        val cursor = nextBefore ?: return@action
        val page = withContext(Dispatchers.IO) { repository.history(p, label, cursor) }
        historyExpanded = true
        nextBefore = page.nextBefore
        mutable.update { it.copy(messages = (page.messages + it.messages).distinctBy { item -> item.id }, hasMoreHistory = nextBefore != null) }
    }

    fun send(text: String) = action {
        val p = profile ?: return@action
        if (mutable.value.connection != ConnectionStatus.CONNECTED || mutable.value.demo) return@action
        val session = mutable.value.sessions.firstOrNull { it.label == mutable.value.selectedSession } ?: return@action
        if (runtimeStatus != "idle") {
            mutable.update { it.copy(notice = "电脑任务仍在进行或状态待确认，请先刷新。") }; return@action
        }
        val operation = withContext(Dispatchers.IO) { repository.send(p, session, text) }
        mutable.update { it.copy(notice = if (operation.state == "unknown") "发送结果待核对。已保留原操作，不会自动重发。" else "电脑已接收，生成进度会继续更新。") }
        refreshData(p)
    }

    fun reconcile(id: String) = action {
        val p = profile ?: return@action
        withContext(Dispatchers.IO) { repository.reconcile(p, id) }; refreshData(p)
    }

    fun forgetProfile() = action {
        polling?.cancel()
        withContext(Dispatchers.IO) { repository.profiles.forget() }
        profile = null // Keep encrypted outbox, including unknown operations.
        mutable.value = UiState(busy = true, notice = "连接已移除，重新导入同一身份可继续核对原操作。")
    }

    fun setForeground(value: Boolean) { foreground = value; if (value) startPolling() else polling?.cancel() }

    private fun startPolling() {
        polling?.cancel()
        if (!foreground || mutable.value.demo || mutable.value.connection != ConnectionStatus.CONNECTED) return
        polling = viewModelScope.launch {
            var failures = 0
            while (foreground && !mutable.value.demo && mutable.value.connection != ConnectionStatus.DISCONNECTED) {
                delay((3000L shl failures.coerceAtMost(3)).coerceAtMost(30000L))
                if (mutable.value.busy) continue
                val p = profile ?: break
                try {
                    refreshData(p); failures = 0
                    mutable.update { it.copy(connection = ConnectionStatus.CONNECTED, error = null) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failures++
                    mutable.update { it.copy(connection = ConnectionStatus.ERROR, error = explain(error)) }
                    if (error is BridgeFailure && error.code == "denied") break
                }
            }
        }
    }

    private suspend fun refreshData(p: ConnectionProfile) {
        val label = mutable.value.selectedSession ?: return
        val outstanding = withContext(Dispatchers.IO) { repository.outbox.load(p.id) }.firstOrNull {
            it.state in setOf("unknown", "accepted", "dispatching") || it.state == "completed" && it.turnStatus !in setOf("completed", "failed", "interrupted")
        }
        var unresolved = false
        if (outstanding != null) {
            try { withContext(Dispatchers.IO) { repository.reconcile(p, outstanding.id) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unresolved = true }
        }
        val page = withContext(Dispatchers.IO) { repository.history(p, label) }
        val operations = withContext(Dispatchers.IO) { repository.outbox.load(p.id) }
        runtimeStatus = page.runtimeStatus
        if (!historyExpanded) nextBefore = page.nextBefore
        mutable.update { old ->
            val messages = if (historyExpanded) {
                val merged = LinkedHashMap<String, ChatMessage>()
                (old.messages + page.messages).forEach { merged[it.id] = it }
                merged.values.toList()
            } else page.messages
            old.copy(messages = messages, operations = operations, hasMoreHistory = nextBefore != null,
            notice = if (unresolved) "有原操作仍待核对，记录已保留；当前消息浏览不受影响。" else old.notice,
            lastSync = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())) }
    }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        polling?.cancel()
        actionJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(error = explain(error), connection = if (profile != null && !it.demo) ConnectionStatus.ERROR else it.connection) }
            } finally {
                mutable.update { it.copy(busy = false) }
                if (mutable.value.connection == ConnectionStatus.CONNECTED) startPolling()
            }
        }
    }

    private fun explain(error: Exception): String = when ((error as? BridgeFailure)?.code) {
        "denied" -> "电脑拒绝访问：设备授权可能已撤销，或会话不在授权范围内。"
        "tls_verification_failed" -> "电脑身份验证失败，请核对地址、证书有效期和连接资料。"
        "transport_timeout", "transport_failed" -> "连接暂时不可用，请检查电脑在线状态。待核对操作不会重发。"
        "unresolved_operation_exists" -> "相同消息仍有待核对操作，请先核对原操作。"
        "message_not_authorized" -> "电脑当前只允许指定的测试消息。"
        else -> "操作未完成，请检查连接资料或刷新。原操作记录已保留。"
    }

    fun showDemo() {
        if (mutable.value.busy) return
        polling?.cancel()
        mutable.value = UiState(profileName = "桌面工作站 · 演示", endpoint = "本地演示 · 未连接任何设备", connection = ConnectionStatus.DEMO,
            sessions = listOf(SessionInfo("demo", "连接与恢复原型", canSend = false)), selectedSession = "demo", demo = true,
            messages = listOf(ChatMessage("d1", "user", "手机断线后，刚才的消息会重新发送吗？"),
                ChatMessage("d2", "assistant", "不会。每次发送先保存操作身份；重新连接后核对原会话，再继续显示结果。")),
            operations = listOf(PendingOperation("demo-op", "demo", "查看恢复状态", "completed", "completed")), notice = "这是合成演示，未操作真实会话。")
    }
}
