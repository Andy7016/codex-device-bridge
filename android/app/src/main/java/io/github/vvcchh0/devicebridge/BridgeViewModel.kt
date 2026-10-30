package io.github.vvcchh0.devicebridge

import android.app.Application
import android.net.Uri
import android.net.Network
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.vvcchh0.devicebridge.data.BridgeFailure
import io.github.vvcchh0.devicebridge.data.ConnectionProfile
import io.github.vvcchh0.devicebridge.data.PairingException
import io.github.vvcchh0.devicebridge.data.PairingPhase
import io.github.vvcchh0.devicebridge.data.PairingProgress
import io.github.vvcchh0.devicebridge.data.PairingQrImageDecoder
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
    private var pairingPolling: Job? = null
    private var actionJob: Job? = null
    private var foreground = false
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkGeneration = 0

    init {
        action {
            profile = withContext(Dispatchers.IO) { repository.profiles.load() }
            val pendingPairing = withContext(Dispatchers.IO) { repository.pairing.pendingProgress() }
            profile?.let { p ->
                mutable.update { it.copy(profileName = p.displayName, endpoint = "${p.host}:${p.port}") }
                val operations = withContext(Dispatchers.IO) { repository.outbox.load(p.id) }
                mutable.update { it.copy(profileName = p.displayName, endpoint = "${p.host}:${p.port}", operations = operations) }
            }
            if (pendingPairing != null) applyPairingProgress(pendingPairing)
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

    /** QR text stays in the UI only until pairing begins; it is encrypted before the first RPC. */
    fun updatePairingInput(value: String) {
        mutable.update { it.copy(pairingInput = value.take(11_000), error = null) }
    }

    fun acceptScannedPairingQr(value: String) = updatePairingInput(value)

    fun importPairingImage(uri: Uri) = pairingAction {
        val qr = withContext(Dispatchers.IO) {
            PairingQrImageDecoder.decode(getApplication<Application>().contentResolver, uri)
        }
        updatePairingInput(qr)
        mutable.update { it.copy(notice = "二维码已读取。检查来源后，点击开始配对。") }
    }

    fun beginPairing() = pairingAction {
        val qr = mutable.value.pairingInput.trim()
        if (qr.isEmpty()) throw PairingException("pairing_qr_required")
        if (qr.startsWith("dbroute:")) {
            val updated = withContext(Dispatchers.IO) { repository.profiles.applyConnectionUpdate(qr) }
            polling?.cancel(); profile = updated
            mutable.value = UiState(profileName = updated.displayName, endpoint = "${updated.host}:${updated.port}",
                operations = withContext(Dispatchers.IO) { repository.outbox.load(updated.id) }, busy = true,
                notice = "连接已更新，原手机身份、授权和操作记录已保留。点击连接核对状态。")
            return@pairingAction
        }
        val progress = withContext(Dispatchers.IO) { repository.pairing.startOrResume(qr) }
        applyPairingProgress(progress)
    }

    fun checkPairing() = pairingAction {
        val progress = withContext(Dispatchers.IO) { repository.pairing.checkStatus() }
        applyPairingProgress(progress)
    }

    fun discardPairing() = pairingAction {
        withContext(Dispatchers.IO) { repository.pairing.discardPending() }
        pairingPolling?.cancel()
        mutable.update {
            it.copy(pairingPhase = null, pairingHostName = null, pairingConfirmationCode = null,
                pairingExpiresAt = null, pairingRequestId = null, notice = "已移除本机未完成的配对请求。")
        }
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
        actionJob?.cancel(); polling?.cancel(); repository.cancelTransport()
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

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            observeNetwork()
            startPolling()
            if (mutable.value.pairingPhase == PairingPhase.WAITING_FOR_APPROVAL) startPairingPolling()
        } else {
            networkGeneration++
            networkCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) { } }
            networkCallback = null
            polling?.cancel()
            pairingPolling?.cancel()
        }
    }

    private fun observeNetwork() {
        if (networkCallback != null) return
        val generation = ++networkGeneration
        var last: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val hasChanged = last != null && last != network
                last = network
                if (hasChanged) changed()
            }
            override fun onLost(network: Network) {
                if (last == network) { last = null; changed() }
            }
            private fun changed() {
                viewModelScope.launch {
                    if (!foreground || generation != networkGeneration || mutable.value.demo ||
                        mutable.value.connection == ConnectionStatus.DISCONNECTED) return@launch
                    repository.cancelTransport()
                    startPolling()
                }
            }
        }
        try { connectivity.registerDefaultNetworkCallback(callback); networkCallback = callback }
        catch (_: Exception) { /* Timed requests and foreground polling remain available. */ }
    }

    override fun onCleared() {
        networkGeneration++
        networkCallback?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        repository.cancelTransport()
        super.onCleared()
    }

    private fun pairingAction(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        pairingPolling?.cancel()
        actionJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val saved = try { withContext(Dispatchers.IO) { repository.pairing.pendingProgress() } } catch (_: Exception) { null }
                if (saved != null) applyPairingProgress(saved, launchPolling = false)
                mutable.update { it.copy(error = explain(error)) }
            } finally {
                mutable.update { it.copy(busy = false) }
                if (mutable.value.pairingPhase == PairingPhase.WAITING_FOR_APPROVAL && foreground) startPairingPolling()
            }
        }
    }

    internal suspend fun applyPairingProgress(progress: PairingProgress, launchPolling: Boolean = true) {
        if (progress.phase == PairingPhase.APPROVED && progress.profile != null) {
            val approved = progress.profile
            polling?.cancel()
            profile = approved
            nextBefore = null
            historyExpanded = false
            // Switch identity and remove old chat data before reading this
            // profile's outbox. A corrupt new record must never retain another
            // computer's messages or an active polling loop.
            mutable.value = UiState(profileName = approved.displayName, endpoint = "${approved.host}:${approved.port}",
                busy = mutable.value.busy, pairingPhase = progress.phase, pairingHostName = progress.hostName,
                pairingConfirmationCode = progress.confirmationCode, pairingExpiresAt = progress.expiresAt,
                pairingRequestId = progress.requestId)
            val operations = withContext(Dispatchers.IO) { repository.outbox.load(approved.id) }
            mutable.update { it.copy(operations = operations,
                notice = "电脑已批准此设备。连接前可先核对资料；连接不会自动发送消息。", error = null) }
        } else {
            mutable.update {
                it.copy(pairingPhase = progress.phase, pairingHostName = progress.hostName,
                    pairingConfirmationCode = progress.confirmationCode, pairingExpiresAt = progress.expiresAt,
                    pairingRequestId = progress.requestId,
                    notice = when (progress.phase) {
                        PairingPhase.STARTING -> "已保存配对请求，正在联系电脑。"
                        PairingPhase.WAITING_FOR_APPROVAL -> "请在电脑上核对设备与确认码，并批准所需权限。"
                        PairingPhase.DENIED -> "电脑拒绝了此配对请求。"
                        PairingPhase.APPROVED -> it.notice
                    }, error = null)
            }
        }
        if (launchPolling && progress.phase == PairingPhase.WAITING_FOR_APPROVAL && foreground) startPairingPolling()
    }

    /** Short foreground polling window; manual status checks resume the same durable request later. */
    private fun startPairingPolling() {
        pairingPolling?.cancel()
        if (!foreground || mutable.value.pairingPhase != PairingPhase.WAITING_FOR_APPROVAL) return
        pairingPolling = viewModelScope.launch {
            repeat(MAX_PAIRING_POLLS) {
                delay(PAIRING_POLL_INTERVAL_MS)
                if (!foreground || mutable.value.pairingPhase != PairingPhase.WAITING_FOR_APPROVAL) return@launch
                if (mutable.value.busy) return@repeat
                try {
                    val progress = withContext(Dispatchers.IO) { repository.pairing.checkStatus() }
                    applyPairingProgress(progress, launchPolling = false)
                    if (progress.phase != PairingPhase.WAITING_FOR_APPROVAL) return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the code and request visible; the explicit status button resumes recovery.
                }
            }
        }
    }

    private fun startPolling() {
        polling?.cancel()
        if (!foreground || mutable.value.demo || profile == null || mutable.value.connection !in setOf(ConnectionStatus.CONNECTED, ConnectionStatus.ERROR)) return
        polling = viewModelScope.launch {
            var failures = 0
            while (foreground && !mutable.value.demo && mutable.value.connection != ConnectionStatus.DISCONNECTED) {
                delay((3000L shl failures.coerceAtMost(3)).coerceAtMost(30000L))
                if (mutable.value.busy) continue
                val p = profile ?: break
                try {
                    if (mutable.value.sessions.isEmpty()) {
                        val sessions = withContext(Dispatchers.IO) { repository.catalog(p) }
                        mutable.update { it.copy(sessions = sessions, selectedSession = sessions.firstOrNull()?.label) }
                    }
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
                if (mutable.value.connection in setOf(ConnectionStatus.CONNECTED, ConnectionStatus.ERROR)) startPolling()
            }
        }
    }

    private fun explain(error: Exception): String = when ((error as? BridgeFailure)?.code) {
        "denied" -> "电脑拒绝访问：设备授权可能已撤销，或会话不在授权范围内。"
        "tls_verification_failed" -> "电脑身份验证失败，请核对地址、证书有效期和连接资料。"
        "transport_timeout", "transport_failed" -> "连接暂时不可用，请检查电脑在线状态。待核对操作不会重发。"
        "unresolved_operation_exists" -> "相同消息仍有待核对操作，请先核对原操作。"
        "message_not_authorized" -> "电脑当前只允许指定的测试消息。"
        else -> when ((error as? PairingException)?.code) {
            "invalid_pairing_qr", "unsupported_pairing_qr", "pairing_invite_expired" -> "配对二维码无效或已过期，请在电脑端重新生成。"
            "pending_pairing_exists" -> "本机已有未完成的配对请求，请先继续检查或移除该请求。"
            "no_pending_pairing" -> "本机没有可恢复的配对请求，请扫描新的电脑二维码。"
            "confirmation_code_mismatch" -> "本机确认码校验失败，请重新开始配对。"
            else -> "操作未完成，请检查连接资料或刷新。原操作记录已保留。"
        }
    }

    private companion object {
        const val PAIRING_POLL_INTERVAL_MS = 3_000L
        const val MAX_PAIRING_POLLS = 15
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
