package io.github.vvcchh0.devicebridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vvcchh0.devicebridge.ChatMessage
import io.github.vvcchh0.devicebridge.ConnectionStatus
import io.github.vvcchh0.devicebridge.PendingOperation
import io.github.vvcchh0.devicebridge.SessionInfo
import io.github.vvcchh0.devicebridge.UiActions
import io.github.vvcchh0.devicebridge.UiState
import io.github.vvcchh0.devicebridge.data.PairingPhase

private enum class BridgePage(val title: String, val symbol: String) {
    CHAT("会话", "聊"),
    DEVICE("设备", "连"),
    ACTIVITY("动态", "记"),
}

@Composable
fun BridgeApp(state: UiState, actions: UiActions) {
    var page by remember { mutableStateOf(BridgePage.CHAT) }
    var forgetConfirmation by remember { mutableStateOf(false) }
    val supportedSessions = remember(state.sessions) {
        state.sessions.filter { it.kind.equals("codex", ignoreCase = true) }
    }
    val selectedSession = supportedSessions.firstOrNull { it.label == state.selectedSession }
    var draft by remember(state.selectedSession) { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BridgeHeader(state = state) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (page == BridgePage.CHAT) {
                    Composer(
                        state = state,
                        session = selectedSession,
                        draft = draft,
                        onDraftChanged = { draft = it },
                        onSend = { text ->
                            actions.send(text)
                            draft = ""
                        },
                    )
                }
                BottomNavigation(current = page, onSelect = { page = it })
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.error != null) {
                NoticeBanner(text = state.error, isError = true)
            } else if (state.notice != null) {
                NoticeBanner(text = state.notice, isError = false)
            }
            if (state.demo || state.connection == ConnectionStatus.DEMO) {
                DemoBanner()
            }
            if (state.historyCached) {
                NoticeBanner(text = "显示上次读取的内容，尚未与电脑核对。", isError = false)
            }

            when (page) {
                BridgePage.CHAT -> ChatPage(
                    state = state,
                    actions = actions,
                    sessions = supportedSessions,
                    selectedSession = selectedSession,
                    onSelectSession = actions.selectSession,
                    modifier = Modifier.weight(1f),
                )
                BridgePage.DEVICE -> DevicePage(
                    state = state,
                    actions = actions,
                    onForget = { forgetConfirmation = true },
                    modifier = Modifier.weight(1f),
                )
                BridgePage.ACTIVITY -> ActivityPage(
                    state = state,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (forgetConfirmation) {
        AlertDialog(
            onDismissRequest = { forgetConfirmation = false },
            title = { Text("忘记此设备档案？") },
            text = {
                Text("这会从本机移除已导入的设备凭据。之后如需连接，请重新导入可信的本地档案。")
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("forget-confirm"),
                    onClick = {
                        forgetConfirmation = false
                        actions.forgetProfile()
                    },
                ) { Text("忘记档案") }
            },
            dismissButton = {
                TextButton(onClick = { forgetConfirmation = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BridgeHeader(state: UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "桥",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "桌面桥接",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "访问已授权的 Codex 会话",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ConnectionBadge(state.connection)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 11.dp)
                .semantics {
                    contentDescription = "设备连接状态：${connectionLabel(state.connection)}"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(connectionDotColor(state.connection)),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = when {
                    state.demo || state.connection == ConnectionStatus.DEMO -> "合成演示环境"
                    state.profileName.isNullOrBlank() -> "尚未配置设备档案"
                    else -> state.profileName.orEmpty()
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = connectionLabel(state.connection),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ConnectionBadge(connection: ConnectionStatus) {
    val container = when (connection) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        ConnectionStatus.DEMO -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (connection) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimaryContainer
        ConnectionStatus.CONNECTING, ConnectionStatus.DEMO -> MaterialTheme.colorScheme.onTertiaryContainer
        ConnectionStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(50),
        modifier = Modifier.semantics {
            contentDescription = "设备状态：${connectionLabel(connection)}"
        },
    ) {
        Text(
            text = connectionLabel(connection),
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BottomNavigation(current: BridgePage, onSelect: (BridgePage) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BridgePage.entries.forEach { item ->
            val selected = current == item
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(item) }
                    .testTag("nav-${item.name.lowercase()}"),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp)
                        .semantics {
                            contentDescription = if (selected) "${item.title}，当前页面" else "打开${item.title}页面"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.symbol,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = item.title,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatPage(
    state: UiState,
    actions: UiActions,
    sessions: List<SessionInfo>,
    selectedSession: SessionInfo?,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var displayedLast by remember(state.selectedSession) { mutableStateOf<String?>(null) }
    val newest = state.messages.lastOrNull()?.id
    LaunchedEffect(state.selectedSession, newest) {
        if (newest != null && newest != displayedLast) {
            val nearEnd = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.messages.size - 3
            if (displayedLast == null || nearEnd) listState.animateScrollToItem(state.messages.lastIndex)
            displayedLast = newest
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        SessionToolbar(
            sessions = sessions,
            selectedSession = selectedSession,
            connected = isConnected(state.connection),
            busy = state.busy,
            hasMoreHistory = state.hasMoreHistory,
            onSelectSession = onSelectSession,
            onRefresh = actions.refresh,
            onLoadOlder = actions.loadOlder,
        )
        if (state.lastSync != null) {
            Text(
                text = "最近同步：${state.lastSync}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .testTag("conversation-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item(key = "conversation-empty") {
                    EmptyConversation(hasSession = selectedSession != null, connected = isConnected(state.connection))
                }
            } else {
                items(state.messages, key = { "message-${it.id}" }) { message ->
                    ChatMessageBubble(message, canExpand = !state.busy && isConnected(state.connection), onExpand = { actions.expandMessage(message.id) })
                }
            }
            val visibleOperations = state.operations.filter { op ->
                needsReconcile(op) && (selectedSession == null || op.session == selectedSession.label)
            }
            if (visibleOperations.isNotEmpty()) {
                item(key = "recent-operations-title") {
                    Text(
                        text = "发送与回复状态",
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                items(visibleOperations, key = { "operation-${it.id}" }) { operation ->
                    OperationCard(
                        operation = operation,
                        canReconcile = needsReconcile(operation),
                        onReconcile = { actions.reconcile(operation.id) },
                        busy = state.busy,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionToolbar(
    sessions: List<SessionInfo>,
    selectedSession: SessionInfo?,
    connected: Boolean,
    busy: Boolean,
    hasMoreHistory: Boolean,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = sessions.isNotEmpty() && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("session-picker")
                    .semantics {
                        contentDescription = selectedSession?.let { "切换会话，当前为${it.title}" } ?: "选择 Codex 会话"
                    },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = selectedSession?.title ?: if (sessions.isEmpty()) "暂无 Codex 会话" else "选择会话",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = selectedSession?.label ?: "Codex",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    session.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelectSession(session.label)
                        },
                        modifier = Modifier.testTag("session-option-${sessions.indexOf(session)}"),
                    )
                }
            }
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !busy && connected,
            modifier = Modifier.testTag("refresh"),
        ) { Text(if (busy) "同步中" else "刷新") }
    }
    if (hasMoreHistory && connected) {
        TextButton(
            onClick = onLoadOlder,
            enabled = !busy && connected,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("load-older-toolbar"),
        ) { Text("加载更早消息") }
    }
}

@Composable
private fun EmptyConversation(hasSession: Boolean, connected: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (!connected) "设备尚未连接" else if (!hasSession) "选择一个会话" else "这里还没有消息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = when {
                    !connected -> "导入可信的设备档案并连接后，才能读取受支持的 Codex 会话。"
                    !hasSession -> "连接成功后，从会话列表选择一个 Codex 会话。"
                    else -> "此页面只显示桌面端返回的内容，不会生成模拟的对话消息。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatMessageBubble(message: ChatMessage, canExpand: Boolean = false, onExpand: () -> Unit = {}) {
    val role = message.role.lowercase()
    if (role == "tool" || role == "tool_result" || role == "tool-result") {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(
                    text = "工具记录 · 只读",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(5.dp))
                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyMedium) }
                if (message.status.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "记录状态：${turnLabel(message.status)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        val isUser = role == "user"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 0.96f),
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomEnd = if (isUser) 6.dp else 18.dp,
                    bottomStart = if (isUser) 18.dp else 6.dp,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                ),
                border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                    Text(
                        text = if (isUser) "你" else "Codex",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f) else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(5.dp))
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (message.nextPart != null) {
                        TextButton(onClick = onExpand, enabled = canExpand,
                            colors = ButtonDefaults.textButtonColors(contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("expand-message-${message.id}")) {
                            Text("展开全文 · 约${(message.totalBytes+1023)/1024} KB")
                        }
                    }
                    if (message.hasQuestions) {
                        Text("提问记录；当前仅显示内容，作答入口尚未开放。", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (message.status.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = turnLabel(message.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    state: UiState,
    session: SessionInfo?,
    draft: String,
    onDraftChanged: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val connected = isConnected(state.connection)
    val granted = session?.canSend == true
    val composerEnabled = connected && granted && !state.busy
    val fixedMessages = session?.allowedMessages.orEmpty()
    val freeText = session?.freeText == true
    val outgoingText = draft
    val duplicateUnknown = session != null && state.operations.any { operation ->
        operation.session == session.label &&
            operation.text == outgoingText &&
            needsReconcile(operation)
    }
    val draftAllowed = outgoingText.isNotBlank() && (freeText || fixedMessages.contains(outgoingText))
    val sendEnabled = composerEnabled && draftAllowed && !duplicateUnknown

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag("composer"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (session == null || !connected || !granted) {
            Text(
                text = when {
                    !connected -> "设备未连接，发送功能已停用"
                    session == null -> "请选择 Codex 会话"
                    !granted -> "此会话没有发送授权"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (fixedMessages.isNotEmpty() || !freeText) {
            Text(
                text = if (freeText) "授权消息示例" else "仅可发送桌面端授权的固定消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (fixedMessages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    fixedMessages.forEachIndexed { index, message ->
                        AssistChip(
                            onClick = { onDraftChanged(message) },
                            enabled = composerEnabled,
                            label = {
                                Text(
                                    message,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier.testTag("allowed-message-$index"),
                        )
                    }
                }
            } else if (!freeText) {
                Text(
                    text = "当前没有可发送的授权消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier
                    .weight(1f)
                    .testTag("message-input")
                    .semantics {
                        contentDescription = if (freeText) "输入消息" else "已授权消息；请使用上方消息选项"
                    },
                enabled = composerEnabled,
                readOnly = !freeText,
                placeholder = {
                    Text(if (freeText) "写一条消息…" else "选择一条授权消息")
                },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (sendEnabled) {
                            onSend(outgoingText)
                            keyboard?.hide()
                        }
                    },
                ),
            )
            Button(
                onClick = {
                    if (sendEnabled) {
                        onSend(outgoingText)
                        keyboard?.hide()
                    }
                },
                enabled = sendEnabled,
                modifier = Modifier
                    .height(56.dp)
                    .testTag("send-message")
                    .semantics { contentDescription = "发送授权消息" },
            ) {
                Text("发送")
            }
        }
        if (duplicateUnknown) {
            Text(
                text = "这条消息的投递结果待核对。先到“动态”核对状态，避免重复发送。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun DevicePage(
    state: UiState,
    actions: UiActions,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle(
            title = "设备连接",
            subtitle = "管理本机与桌面端之间的受信连接。",
        )
        PairingCard(state, actions)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("当前档案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = when {
                                state.demo || state.connection == ConnectionStatus.DEMO -> "合成演示档案"
                                state.profileName.isNullOrBlank() -> "尚未导入"
                                else -> state.profileName.orEmpty()
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    ConnectionBadge(state.connection)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("设备端点", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(
                        text = state.endpoint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("device-endpoint"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.lastSync != null) {
                    Text(
                        text = "最近同步：${state.lastSync}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = actions.importProfile,
                        enabled = !state.busy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("import-profile"),
                    ) { Text("导入档案", maxLines = 1) }
                    Button(
                        onClick = actions.connect,
                        enabled = !state.busy && !isConnected(state.connection) && !state.profileName.isNullOrBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("connect"),
                    ) { Text(if (state.connection == ConnectionStatus.CONNECTING) "连接中" else "连接设备", maxLines = 1) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = actions.disconnect,
                        enabled = !state.busy && (state.connection == ConnectionStatus.CONNECTED || state.connection == ConnectionStatus.CONNECTING),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("disconnect"),
                    ) { Text("断开连接") }
                    OutlinedButton(
                        onClick = onForget,
                        enabled = !state.busy && !state.profileName.isNullOrBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("forget-profile"),
                    ) { Text("忘记档案") }
                }
            }
        }

        InfoCard(
            title = "导入前请确认来源",
            body = "设备档案包含设备凭据。只导入由你在本机生成并且信任的档案文件。",
            emphasized = true,
        )
        InfoCard(
            title = "当前能力范围",
            body = "此桥接面向受支持的 Codex 桌面会话。ChatGPT 会话、模型切换、设置管理、审批操作、文件与附件目前不支持。",
        )
        InfoCard(
            title = "桌面端需要在线",
            body = "连接状态和会话内容来自桌面端桥接服务；离线时不会显示为已连接。",
        )
        Button(
            onClick = actions.showDemo,
            enabled = !state.busy && !state.demo && state.connection != ConnectionStatus.DEMO,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("show-demo"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Text("查看合成演示数据")
        }
        Text(
            text = "演示内容为合成示例，不代表真实设备、账户或会话。",
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PairingCard(state: UiState, actions: UiActions) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text("与桌面端配对", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "扫描桌面端生成的一次性二维码，或粘贴二维码文本。批准必须在电脑上完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.pairingInput,
                onValueChange = actions.updatePairingInput,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("pairing-qr-input"),
                label = { Text("配对二维码文本") },
                placeholder = { Text("dbpair:…") },
                minLines = 2,
                maxLines = 4,
                supportingText = { Text("二维码包含短期配对凭据；仅从你信任的电脑获取。") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = actions.scanPairingQr,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).testTag("scan-pairing-qr"),
                ) { Text("扫描二维码", maxLines = 1) }
                OutlinedButton(
                    onClick = actions.importPairingImage,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).testTag("import-pairing-image"),
                ) { Text("读取二维码图片", maxLines = 1) }
            }
            Button(
                onClick = actions.beginPairing,
                enabled = !state.busy && state.pairingInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("begin-pairing"),
            ) { Text(if (state.busy) "正在处理…" else "开始配对") }

            when (state.pairingPhase) {
                PairingPhase.STARTING -> PairingStatusPanel(
                    title = "正在联系 ${state.pairingHostName.orEmpty()}",
                    body = "请求身份和恢复记录已保存在本机。再次开始会继续使用同一请求。",
                    code = null,
                )
                PairingPhase.WAITING_FOR_APPROVAL -> PairingStatusPanel(
                    title = "等待电脑批准：${state.pairingHostName.orEmpty()}",
                    body = "请核对这组确认码是否与电脑上显示的一致，再在电脑上批准设备及其权限。",
                    code = state.pairingConfirmationCode,
                )
                PairingPhase.APPROVED -> PairingStatusPanel(
                    title = "已由电脑批准",
                    body = "连接资料已保存。确认码仅用于本次配对核对；连接后仍需你手动发送消息。",
                    code = state.pairingConfirmationCode,
                )
                PairingPhase.DENIED -> PairingStatusPanel(
                    title = "配对已被拒绝",
                    body = "电脑没有授权此设备。需要时请在电脑端重新生成新的配对二维码。",
                    code = state.pairingConfirmationCode,
                )
                null -> Unit
            }

            if (state.pairingPhase == PairingPhase.STARTING || state.pairingPhase == PairingPhase.WAITING_FOR_APPROVAL) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = actions.checkPairing,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).testTag("check-pairing"),
                    ) { Text("检查批准状态") }
                    TextButton(
                        onClick = actions.discardPairing,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f).testTag("discard-pairing"),
                    ) { Text("移除此请求") }
                }
            }
        }
    }
}

@Composable
private fun PairingStatusPanel(title: String, body: String, code: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (code != null) {
                Text(
                    code,
                    modifier = Modifier.fillMaxWidth().testTag("pairing-code"),
                    style = MaterialTheme.typography.headlineMedium,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun ActivityPage(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    val operations = remember(state.operations) { state.operations.sortedByDescending { it.createdAt } }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(
            title = "操作动态",
            subtitle = "投递状态与模型回复进度分别显示。",
        )
        if (operations.isEmpty()) {
            InfoCard(
                title = "暂无发送记录",
                body = "获得发送授权并发送消息后，这里会分别显示消息是否送达，以及模型是否完成回复。",
            )
        } else {
            operations.forEachIndexed { index, operation ->
                OperationCard(
                    operation = operation,
                    canReconcile = needsReconcile(operation),
                    onReconcile = { actions.reconcile(operation.id) },
                    busy = state.busy,
                    modifier = Modifier.testTag("activity-operation-$index"),
                )
            }
        }
        InfoCard(
            title = "结果待核对时",
            body = "核对只读取同一操作的最新状态，不会重新发送，也不会创建新的发送操作。",
            emphasized = true,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun OperationCard(
    operation: PendingOperation,
    canReconcile: Boolean,
    onReconcile: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = operation.session,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = operation.state.toOperationStateLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = stateTextColor(operation.state),
                )
            }
            SelectionContainer {
                Text(operation.text, style = MaterialTheme.typography.bodyMedium)
            }
            StatusLine(label = "消息投递", value = deliveryLabel(operation.state))
            StatusLine(label = "模型回复", value = turnLabel(operation.turnStatus))
            if (canReconcile) {
                Text(
                    text = "投递结果可能尚未确认。此操作只会读取当前状态。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedButton(
                    onClick = onReconcile,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reconcile"),
                ) { Text("核对此操作状态") }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(74.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(title: String, body: String, emphasized: Boolean = false) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (emphasized) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (emphasized) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeBanner(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun DemoBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(13.dp),
    ) {
        Text(
            text = "演示模式 · 当前展示合成数据，不代表真实设备或会话",
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun isConnected(connection: ConnectionStatus): Boolean =
    connection == ConnectionStatus.CONNECTED || connection == ConnectionStatus.DEMO

private fun connectionLabel(connection: ConnectionStatus): String = when (connection) {
    ConnectionStatus.CONNECTED -> "已连接"
    ConnectionStatus.CONNECTING -> "连接中"
    ConnectionStatus.DISCONNECTED -> "未连接"
    ConnectionStatus.ERROR -> "连接异常"
    ConnectionStatus.DEMO -> "演示"
}

@Composable
private fun connectionDotColor(connection: ConnectionStatus): Color = when (connection) {
    ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
    ConnectionStatus.CONNECTING, ConnectionStatus.DEMO -> MaterialTheme.colorScheme.tertiary
    ConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
    ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
}

private fun normalizedStatus(value: String): String = value.trim().lowercase().replace('_', '-').replace(' ', '-')

private fun needsReconcile(operation: PendingOperation): Boolean {
    val state = normalizedStatus(operation.state)
    val turn = normalizedStatus(operation.turnStatus)
    val uncertain = state in setOf("unknown", "uncertain", "indeterminate", "结果未知", "结果不确定", "未知", "待核对") ||
        state.contains("unknown") || state.contains("uncertain") || state.contains("indeterminate")
    val accepted = state in setOf("completed", "complete", "delivered", "sent", "accepted", "已送达", "完成") ||
        state.startsWith("completed-")
    val running = turn in setOf("in-progress", "running", "active", "working", "processing", "进行中", "处理中") ||
        turn.contains("progress") || turn.contains("running")
    return uncertain || state in setOf("accepted", "dispatching", "sending") || (accepted && running)
}

private fun String.toOperationStateLabel(): String = when (normalizedStatus(this)) {
    "queued", "pending", "waiting", "排队中", "等待中" -> "等待发送"
    "sending", "in-progress", "进行中", "发送中" -> "正在发送"
    "accepted" -> "电脑已受理"
    "dispatching" -> "正在交接"
    "sent", "delivered", "已送达" -> "已送达"
    "unknown", "uncertain", "indeterminate", "结果未知", "未知", "待核对" -> "结果待核对"
    "failed", "rejected", "error", "失败" -> "发送失败"
    "completed", "complete", "完成" -> "投递已确认"
    "cancelled", "canceled", "已取消" -> "已取消"
    else -> when {
        normalizedStatus(this).contains("unknown") || normalizedStatus(this).contains("uncertain") || normalizedStatus(this).contains("indeterminate") -> "结果待核对"
        isBlank() -> "状态未报告"
        else -> this
    }
}

private fun deliveryLabel(rawState: String): String {
    val status = normalizedStatus(rawState)
    if (status.contains("unknown") || status.contains("uncertain") || status.contains("indeterminate")) return "结果待核对"
    return when (status) {
    "queued", "pending", "waiting", "排队中", "等待中" -> "等待发送"
    "sending", "in-progress", "进行中", "发送中" -> "正在发送"
    "accepted" -> "电脑已受理"
    "dispatching" -> "正在交接"
    "sent", "delivered", "completed", "complete", "已送达", "完成" -> "已送达"
    "unknown", "uncertain", "indeterminate", "结果未知", "未知", "待核对" -> "结果待核对"
    "failed", "rejected", "error", "失败" -> "发送失败"
        else -> rawState.ifBlank { "未报告" }
    }
}

private fun turnLabel(rawStatus: String): String = when (normalizedStatus(rawStatus)) {
    "in-progress", "running", "active", "working", "processing", "进行中", "处理中" -> "生成中"
    "completed", "complete", "finished", "succeeded", "已完成", "完成" -> "已完成"
    "failed", "error", "失败" -> "已失败"
    "interrupted" -> "已中断"
    "cancelled", "canceled", "已取消" -> "已取消"
    "unknown", "uncertain", "indeterminate", "未知", "待核对" -> "状态待核对"
    else -> if (rawStatus.isBlank()) "暂无状态" else rawStatus
}

@Composable
private fun stateTextColor(state: String): Color = when (normalizedStatus(state)) {
    "failed", "rejected", "error", "失败" -> MaterialTheme.colorScheme.error
    "unknown", "uncertain", "indeterminate", "结果未知", "未知", "待核对" -> MaterialTheme.colorScheme.tertiary
    "sent", "delivered", "accepted", "completed", "complete", "已送达", "完成" -> MaterialTheme.colorScheme.primary
    else -> if (normalizedStatus(state).contains("unknown") || normalizedStatus(state).contains("uncertain")) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}
