package io.github.vvcchh0.devicebridge.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.vvcchh0.devicebridge.ChatMessage
import io.github.vvcchh0.devicebridge.ConnectionStatus
import io.github.vvcchh0.devicebridge.PendingOperation
import io.github.vvcchh0.devicebridge.R
import io.github.vvcchh0.devicebridge.SessionInfo
import io.github.vvcchh0.devicebridge.UiActions
import io.github.vvcchh0.devicebridge.UiState
import io.github.vvcchh0.devicebridge.data.PairingPhase

private enum class BridgePage(val title: String, @param:DrawableRes val icon: Int) {
    CHAT("会话", R.drawable.ic_nav_chat),
    DEVICE("设备", R.drawable.ic_nav_device),
    ACTIVITY("动态", R.drawable.ic_nav_activity),
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
        topBar = {
            BridgeHeader(
                state = state,
                page = page,
                sessions = supportedSessions,
                selectedSession = selectedSession,
                onSelectSession = actions.selectSession,
                onRefresh = actions.refresh,
            )
        },
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
                    selectedSession = selectedSession,
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
private fun BridgeHeader(
    state: UiState,
    page: BridgePage,
    sessions: List<SessionInfo>,
    selectedSession: SessionInfo?,
    onSelectSession: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val connected = isConnected(state.connection)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (page == BridgePage.CHAT) {
                    SessionPicker(
                        sessions = sessions,
                        selectedSession = selectedSession,
                        enabled = sessions.isNotEmpty() && !state.busy,
                        onSelectSession = onSelectSession,
                    )
                } else {
                    Text(
                        text = if (page == BridgePage.DEVICE) "设备连接" else "操作动态",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(2.dp))
                ConnectionLine(state)
            }
            if (page == BridgePage.CHAT) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !state.busy && connected,
                    modifier = Modifier
                        .testTag("refresh")
                        .semantics { contentDescription = if (state.busy) "同步中" else "刷新会话" },
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ConnectionLine(state: UiState) {
    val profile = when {
        state.demo || state.connection == ConnectionStatus.DEMO -> "合成演示环境"
        state.profileName.isNullOrBlank() -> "尚未配置设备档案"
        else -> state.profileName.orEmpty()
    }
    Row(
        modifier = Modifier.semantics {
            contentDescription = "设备连接状态：${connectionLabel(state.connection)}"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(connectionDotColor(state.connection)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = buildString {
                append(profile)
                append(" · ")
                append(connectionLabel(state.connection))
                if (state.lastSync != null) append(" · 同步于 ${state.lastSync}")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionPicker(
    sessions: List<SessionInfo>,
    selectedSession: SessionInfo?,
    enabled: Boolean,
    onSelectSession: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { expanded = true }
                .testTag("session-picker")
                .semantics {
                    contentDescription = selectedSession?.let { "切换会话，当前为${it.title}" } ?: "选择 Codex 会话"
                }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selectedSession?.title ?: if (sessions.isEmpty()) "暂无 Codex 会话" else "选择会话",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sessions.isNotEmpty()) {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sessions.forEach { session ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                session.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (session == selectedSession) FontWeight.SemiBold else null,
                            )
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
    StatusPill(
        text = connectionLabel(connection),
        container = container,
        content = content,
        modifier = Modifier.semantics {
            contentDescription = "设备状态：${connectionLabel(connection)}"
        },
    )
}

@Composable
private fun StatusPill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, shape = CircleShape, modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BottomNavigation(current: BridgePage, onSelect: (BridgePage) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    // The enclosing column already applies navigation bar insets.
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        BridgePage.entries.forEach { item ->
            NavigationBarItem(
                selected = current == item,
                onClick = { onSelect(item) },
                icon = { Icon(painterResource(item.icon), contentDescription = null) },
                label = { Text(item.title) },
                modifier = Modifier.testTag("nav-${item.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun ChatPage(
    state: UiState,
    actions: UiActions,
    selectedSession: SessionInfo?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val connected = isConnected(state.connection)
    val showLoadOlder = state.hasMoreHistory && connected
    val leadingItems = if (showLoadOlder) 1 else 0
    var displayedLast by remember(state.selectedSession) { mutableStateOf<String?>(null) }
    val newest = state.messages.lastOrNull()?.id
    LaunchedEffect(state.selectedSession, newest) {
        if (newest != null && newest != displayedLast) {
            val nearEnd = (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >=
                state.messages.size + leadingItems - 3
            if (displayedLast == null || nearEnd) listState.animateScrollToItem(state.messages.lastIndex + leadingItems)
            displayedLast = newest
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag("conversation-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showLoadOlder) {
            item(key = "load-older") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    OutlinedButton(
                        onClick = actions.loadOlder,
                        enabled = !state.busy && connected,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("load-older-toolbar"),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) { Text("加载更早消息", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        if (state.messages.isEmpty()) {
            item(key = "conversation-empty") {
                EmptyConversation(hasSession = selectedSession != null, connected = connected)
            }
        } else {
            items(state.messages, key = { "message-${it.id}" }) { message ->
                ChatMessageBubble(message, canExpand = !state.busy && connected, onExpand = { actions.expandMessage(message.id) })
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
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            items(visibleOperations, key = { "operation-${it.id}" }) { operation ->
                OperationCard(
                    operation = operation,
                    canReconcile = needsReconcile(operation),
                    onReconcile = { actions.reconcile(operation.id) },
                    busy = state.busy,
                    horizontalPadding = 0.dp,
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(hasSession: Boolean, connected: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(
            icon = if (connected) R.drawable.ic_nav_chat else R.drawable.ic_nav_device,
            size = 56.dp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (!connected) "设备尚未连接" else if (!hasSession) "选择一个会话" else "这里还没有消息",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                !connected -> "导入可信的设备档案并连接后，才能读取受支持的 Codex 会话。"
                !hasSession -> "连接成功后，点击顶部标题选择一个 Codex 会话。"
                else -> "此页面只显示桌面端返回的内容，不会生成模拟的对话消息。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IconBadge(@DrawableRes icon: Int, size: Dp, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.5f),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ChatMessageBubble(message: ChatMessage, canExpand: Boolean = false, onExpand: () -> Unit = {}) {
    val role = message.role.lowercase()
    if (role == "tool" || role == "tool_result" || role == "tool-result") {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 38.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    text = "工具记录 · 只读",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
    } else if (role == "user") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(modifier = Modifier.fillMaxWidth(0.86f), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = "你发送的消息" },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        SelectionContainer {
                            Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        MessageFooter(message, canExpand, onExpand, accent = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(icon = R.drawable.ic_codex, size = 28.dp, contentDescription = "Codex")
            Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                SelectionContainer {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                MessageFooter(message, canExpand, onExpand, accent = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MessageFooter(message: ChatMessage, canExpand: Boolean, onExpand: () -> Unit, accent: Color) {
    if (message.nextPart != null) {
        TextButton(
            onClick = onExpand,
            enabled = canExpand,
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            modifier = Modifier.testTag("expand-message-${message.id}"),
        ) {
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("展开全文 · 约${(message.totalBytes + 1023) / 1024} KB", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (message.hasQuestions) {
        Text(
            "提问记录；当前仅显示内容，作答入口尚未开放。",
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    // A completed turn is the normal case; only surface states that need attention.
    if (message.status.isNotBlank() && !isCompletedTurn(message.status)) {
        Text(
            text = turnLabel(message.status),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = turnColor(message.status),
        )
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
    val submit = {
        if (sendEnabled) {
            onSend(outgoingText)
            keyboard?.hide()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)
            .testTag("composer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (session != null && connected && granted && fixedMessages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                fixedMessages.forEachIndexed { index, message ->
                    AssistChip(
                        onClick = { onDraftChanged(message) },
                        enabled = composerEnabled,
                        shape = CircleShape,
                        label = {
                            Text(
                                message,
                                modifier = Modifier.widthIn(max = 240.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.testTag("allowed-message-$index"),
                    )
                }
            }
        }

        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChanged,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("message-input")
                .semantics {
                    contentDescription = if (freeText) "输入消息" else "已授权消息；请使用上方消息选项"
                },
            enabled = composerEnabled,
            readOnly = !freeText,
            shape = RoundedCornerShape(26.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                Text(
                    text = when {
                        !connected -> "设备未连接，发送功能已停用"
                        session == null -> "请选择 Codex 会话"
                        !granted -> "此会话没有发送授权"
                        freeText -> "写一条消息…"
                        fixedMessages.isEmpty() -> "当前没有可发送的授权消息"
                        else -> "仅可发送上方桌面端授权的消息"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = {
                FilledIconButton(
                    onClick = submit,
                    enabled = sendEnabled,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(40.dp)
                        .testTag("send-message")
                        .semantics { contentDescription = "发送授权消息" },
                ) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = null, modifier = Modifier.size(20.dp))
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        if (duplicateUnknown) {
            Text(
                text = "这条消息的投递结果待核对。先到“动态”核对状态，避免重复发送。",
                modifier = Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PageIntro(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        PageIntro("管理本机与桌面端之间的受信连接。")
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon = R.drawable.ic_nav_device, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("当前档案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = when {
                            state.demo || state.connection == ConnectionStatus.DEMO -> "合成演示档案"
                            state.profileName.isNullOrBlank() -> "尚未导入"
                            else -> state.profileName.orEmpty()
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ConnectionBadge(state.connection)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text("设备端点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
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
                TextButton(
                    onClick = onForget,
                    enabled = !state.busy && !state.profileName.isNullOrBlank(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("forget-profile"),
                ) { Text("忘记档案") }
            }
        }
        PairingCard(state, actions)
        SectionCard {
            Text("使用须知", style = MaterialTheme.typography.titleSmall)
            GuidanceItem(
                title = "导入前请确认来源",
                body = "设备档案包含设备凭据。只导入由你在本机生成并且信任的档案文件。",
                emphasized = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GuidanceItem(
                title = "当前能力范围",
                body = "此桥接面向受支持的 Codex 桌面会话。ChatGPT 会话、模型切换、设置管理、审批操作、文件与附件目前不支持。",
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            GuidanceItem(
                title = "桌面端需要在线",
                body = "连接状态和会话内容来自桌面端桥接服务；离线时不会显示为已连接。",
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextButton(
                onClick = actions.showDemo,
                enabled = !state.busy && !state.demo && state.connection != ConnectionStatus.DEMO,
                modifier = Modifier.testTag("show-demo"),
            ) {
                Text("查看合成演示数据")
            }
            Text(
                text = "演示内容为合成示例，不代表真实设备、账户或会话。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GuidanceItem(title: String, body: String, emphasized: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasized) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PairingCard(state: UiState, actions: UiActions) {
    SectionCard {
        Text("与桌面端配对", style = MaterialTheme.typography.titleMedium)
        Text(
            "扫描桌面端生成的一次性二维码，或粘贴二维码文本。批准必须在电脑上完成。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        OutlinedTextField(
            value = state.pairingInput,
            onValueChange = actions.updatePairingInput,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("pairing-qr-input"),
            label = { Text("配对二维码文本") },
            placeholder = { Text("dbpair:…") },
            shape = RoundedCornerShape(14.dp),
            minLines = 2,
            maxLines = 4,
            supportingText = { Text("二维码包含短期配对凭据；仅从你信任的电脑获取。") },
        )
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

@Composable
private fun PairingStatusPanel(title: String, body: String, code: String?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (code != null) {
                Text(
                    code,
                    modifier = Modifier.fillMaxWidth().testTag("pairing-code"),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    letterSpacing = 6.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
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
        PageIntro("投递状态与模型回复进度分别显示。")
        if (operations.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconBadge(icon = R.drawable.ic_nav_activity, size = 56.dp)
                Spacer(Modifier.height(4.dp))
                Text("暂无发送记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "获得发送授权并发送消息后，这里会分别显示消息是否送达，以及模型是否完成回复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
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
    horizontalPadding: Dp = 16.dp,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                val (container, content) = statePillColors(operation.state)
                StatusPill(text = operation.state.toOperationStateLabel(), container = container, content = content)
            }
            SelectionContainer {
                Text(operation.text, style = MaterialTheme.typography.bodyMedium)
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusLine(label = "消息投递", value = deliveryLabel(operation.state))
                    StatusLine(label = "模型回复", value = turnLabel(operation.turnStatus))
                }
            }
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
private fun InfoCard(title: String, body: String, emphasized: Boolean = false) {
    SectionCard(
        container = if (emphasized) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
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


private fun isCompletedTurn(rawStatus: String): Boolean =
    normalizedStatus(rawStatus) in setOf("completed", "complete", "finished", "succeeded", "已完成", "完成")

@Composable
private fun turnColor(rawStatus: String): Color = when (normalizedStatus(rawStatus)) {
    "failed", "error", "失败", "interrupted", "cancelled", "canceled", "已取消" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

@Composable
private fun statePillColors(state: String): Pair<Color, Color> {
    val status = normalizedStatus(state)
    val uncertain = status in setOf("unknown", "uncertain", "indeterminate", "结果未知", "未知", "待核对") ||
        status.contains("unknown") || status.contains("uncertain")
    return when {
        status in setOf("failed", "rejected", "error", "失败") ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        uncertain -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        status in setOf("sent", "delivered", "accepted", "completed", "complete", "已送达", "完成") ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
