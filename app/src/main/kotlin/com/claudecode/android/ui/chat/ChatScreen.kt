package com.claudecode.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.claudecode.android.ui.AppRoutes
import com.claudecode.android.ui.theme.ClaudeCodeTheme
import kotlinx.coroutines.launch

/**
 * ChatScreen - 聊天主界面
 *
 * 应用的核心功能界面，用户在此与 Claude Code Agent 进行交互。
 * 采用 Scaffold 布局，分为三个主要区域：
 * - TopAppBar：显示标题、工作目录和操作按钮
 * - 消息列表：展示所有对话消息和工具调用卡片
 * - 底部输入栏：文字输入框和发送/停止按钮
 *
 * @param navController 导航控制器，用于跳转到其他页面
 * @param viewModel 聊天 ViewModel，默认由 Compose 自动创建
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = viewModel()
) {
    // 收集 UI 状态流
    val uiState by viewModel.uiState.collectAsState()
    val inputText by viewModel.inputText.collectAsState()

    // 消息列表的滚动状态
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Snackbar 状态，用于显示错误消息
    val snackbarHostState = remember { SnackbarHostState() }

    // 当消息列表更新时，自动滚动到底部
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    // 显示错误 Snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        // 顶部 AppBar
        topBar = {
            ChatTopBar(
                workingDirectory = uiState.workingDirectory,
                isAgentRunning = uiState.isAgentRunning,
                currentToolName = uiState.currentToolName,
                tokenUsage = uiState.tokenUsage,
                onNewSession = { viewModel.newSession() },
                onNavigateToSessions = { navController.navigate(AppRoutes.SESSIONS) },
                onNavigateToSettings = { navController.navigate(AppRoutes.SETTINGS) }
            )
        },
        // Snackbar 错误提示
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 底部输入栏
        bottomBar = {
            InputBar(
                text = inputText,
                onTextChange = { viewModel.inputText.value = it },
                onSend = { viewModel.sendMessage(inputText) },
                onStop = { viewModel.stopTask() },
                isRunning = uiState.isAgentRunning
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // 消息列表区域
        MessageList(
            messages = uiState.messages,
            listState = listState,
            isAgentRunning = uiState.isAgentRunning,
            onToggleToolCard = { messageId -> viewModel.toggleToolCardExpand(messageId) },
            modifier = Modifier.padding(paddingValues)
        )

        // 权限审批对话框
        uiState.pendingPermissionRequest?.let { request ->
            PermissionDialog(
                toolName = request.toolName,
                inputSummary = request.inputSummary,
                onApprove = { viewModel.approvePermission(request.requestId) },
                onDeny = { viewModel.denyPermission(request.requestId) }
            )
        }
    }
}

/**
 * ChatTopBar - 聊天界面顶部工具栏
 *
 * 显示：
 * - 应用名称和当前工作目录
 * - Agent 运行状态指示（进度条）
 * - 操作按钮（新建会话、历史记录、设置）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    workingDirectory: String,
    isAgentRunning: Boolean,
    currentToolName: String?,
    tokenUsage: Pair<Int, Int>?,
    onNewSession: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Column {
        TopAppBar(
            title = {
                Column {
                    // 主标题
                    Text(
                        text = "Claude Code",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    // 工作目录副标题
                    Text(
                        text = workingDirectory,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            actions = {
                // 当前工具执行状态文字
                if (isAgentRunning && currentToolName != null) {
                    Text(
                        text = "运行 $currentToolName...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                // 新建会话按钮
                IconButton(onClick = onNewSession) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "新建会话",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                // 历史会话按钮
                IconButton(onClick = onNavigateToSessions) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "历史会话",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                // 设置按钮
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Agent 运行时显示进度条
        if (isAgentRunning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        // Token 使用量进度条（如果有数据）
        tokenUsage?.let { (used, total) ->
            if (!isAgentRunning) {
                val progress = if (total > 0) used.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        progress > 0.9f -> MaterialTheme.colorScheme.error
                        progress > 0.7f -> Color(0xFFF59E0B) // 警告黄色
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

/**
 * MessageList - 消息列表
 *
 * 使用 LazyColumn 高效渲染大量消息，每种消息类型对应不同的展示组件。
 * 新消息出现时自动滚动到底部，提升用户体验。
 *
 * @param messages 消息列表
 * @param listState LazyColumn 的滚动状态，用于外部控制滚动
 * @param isAgentRunning Agent 是否在运行，控制打字指示器的显示
 * @param onToggleToolCard 切换工具调用卡片展开/折叠的回调
 */
@Composable
fun MessageList(
    messages: List<UiMessage>,
    listState: LazyListState,
    isAgentRunning: Boolean,
    onToggleToolCard: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 顶部间距
        item { Spacer(modifier = Modifier.height(8.dp)) }

        // 渲染每条消息
        items(
            items = messages,
            // 使用 id 作为 key，优化列表性能，避免不必要的重组
            key = { it.id }
        ) { message ->
            when (message) {
                is UserMessage -> UserMessageBubble(message = message)
                is AssistantText -> AssistantMessageItem(message = message)
                is ToolCallCard -> ToolCallCardItem(
                    card = message,
                    onToggleExpand = { onToggleToolCard(message.id) }
                )
                is ErrorMessage -> ErrorMessageItem(message = message)
            }
        }

        // Agent 运行中且最后一条不是 AssistantText 时显示打字指示器
        if (isAgentRunning && (messages.isEmpty() || messages.last() !is AssistantText)) {
            item {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TypingIndicator()
                }
            }
        }

        // 底部间距，防止消息被输入栏遮挡
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

/**
 * UserMessageBubble - 用户消息气泡
 *
 * 右对齐展示，使用蓝色背景，模仿 iMessage 风格。
 * 气泡下方显示发送时间。
 *
 * @param message 用户消息数据
 */
@Composable
fun UserMessageBubble(message: UserMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End  // 右对齐
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 4.dp  // 右下角小圆角，表示"说话者"方向
                    )
                )
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        // 发送时间
        Text(
            text = message.timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, end = 4.dp)
        )
    }
}

/**
 * AssistantMessageItem - AI 助手消息展示
 *
 * 左对齐展示，支持 Markdown 格式渲染。
 * isStreaming 为 true 时显示光标动画，表示正在输出。
 *
 * @param message AI 助手消息数据
 */
@Composable
fun AssistantMessageItem(message: AssistantText) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        // AI 图标 + 名称标识
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "C",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Claude",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 消息内容（使用普通 Text，实际项目可接入 Markdown 渲染库）
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 4.dp,  // 左上角小圆角，表示"说话者"方向
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 18.dp
                    )
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // 消息文字 + 流式输出光标
            Text(
                text = if (message.isStreaming) "${message.text}▋" else message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * ToolCallCardItem - 工具调用展示卡片
 *
 * 当 Agent 调用工具时，以卡片形式展示在消息流中。
 * 支持展开/折叠查看详细的输入输出内容。
 *
 * 工具图标映射：
 * - Bash → ⚡ 闪电（命令执行）
 * - Read → 📄 文档（文件读取）
 * - Write → ✏️ 铅笔（文件写入）
 * - Edit → 🔧 扳手（文件编辑）
 * - Glob/Grep → 🔍 放大镜（搜索）
 * - WebFetch → 🌐 地球（网络请求）
 *
 * @param card 工具调用卡片数据
 * @param onToggleExpand 切换展开/折叠状态的回调
 */
@Composable
fun ToolCallCardItem(
    card: ToolCallCard,
    onToggleExpand: () -> Unit
) {
    // 根据工具名映射图标 emoji
    val toolIcon = when (card.toolName.lowercase()) {
        "bash" -> "⚡"
        "read", "readfile" -> "📄"
        "write", "writefile" -> "✏️"
        "edit", "multiedit" -> "🔧"
        "glob", "grep" -> "🔍"
        "webfetch", "websearch" -> "🌐"
        "todoread", "todowrite" -> "📝"
        else -> "🛠️"
    }

    // 执行状态判断
    val isRunning = card.output == null && !card.isError
    val isSuccess = card.output != null && !card.isError
    val isError = card.isError

    // 格式化执行时间（将毫秒转换为易读格式）
    val durationText = card.durationMs?.let { ms ->
        when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> "${"%.1f".format(ms / 1000.0)}s"
            else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = when {
                isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                isSuccess -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.outline
            }
        )
    ) {
        Column {
            // 卡片头部：工具信息摘要行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 工具图标
                Text(
                    text = toolIcon,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )

                // 工具名称
                Text(
                    text = card.toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                // 执行状态指示
                when {
                    isRunning -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    isSuccess -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "成功",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    isError -> Icon(
                        Icons.Default.Error,
                        contentDescription = "失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // 执行耗时
                durationText?.let { duration ->
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = duration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 展开/折叠箭头
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = if (card.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (card.isExpanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 展开区域：显示输入参数和输出结果
            AnimatedVisibility(
                visible = card.isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, bottom = 10.dp)
                ) {
                    // 分隔线
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // 输入参数区域
                    Text(
                        text = "输入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = card.input,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 输出结果区域（如果有）
                    card.output?.let { output ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "输出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = output,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (card.isError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ErrorMessageItem - 错误消息展示组件
 *
 * 以醒目的红色背景展示错误信息，帮助用户快速识别问题。
 *
 * @param message 错误消息数据
 */
@Composable
fun ErrorMessageItem(message: ErrorMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = "错误",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/**
 * TypingIndicator - AI 打字指示器
 *
 * 当 Agent 正在处理但还未输出文字时，显示三个跳动的圆点动画，
 * 让用户知道系统正在工作，避免以为程序卡死。
 *
 * 使用 infiniteTransition 实现循环动画，三个圆点依次延迟启动，
 * 形成波浪式跳动效果。
 */
@Composable
fun TypingIndicator() {
    // 创建无限循环的动画过渡
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    // 三个圆点分别有不同的延迟时间，形成波浪效果
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 三个圆点，透明度由动画控制
        listOf(dot1Alpha, dot2Alpha, dot3Alpha).forEach { alpha ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                    )
            )
        }
    }
}

/**
 * InputBar - 底部输入区域
 *
 * 包含：
 * - 多行文字输入框（支持换行）
 * - 发送按钮（Agent 空闲时显示）
 * - 停止按钮（Agent 运行时显示，带动画切换）
 *
 * 适配键盘弹出：使用 imePadding() 确保输入框始终可见。
 *
 * @param text 当前输入框文字
 * @param onTextChange 文字变化回调
 * @param onSend 发送消息回调
 * @param onStop 停止 Agent 回调
 * @param isRunning Agent 是否正在运行
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()  // 适配底部导航栏
                .imePadding()             // 适配软键盘弹出
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // 文字输入框
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = if (isRunning) "Agent 正在运行中..." else "输入消息或 @ 命令",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                // 支持多行输入，最多显示 5 行
                maxLines = 5,
                minLines = 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default  // 默认允许换行
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (!isRunning) onSend() }
                ),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                enabled = !isRunning  // Agent 运行时禁用输入
            )

            Spacer(modifier = Modifier.width(8.dp))

            // 发送/停止按钮（根据运行状态切换）
            if (isRunning) {
                // 停止按钮：红色圆形按钮
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { onStop() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                // 发送按钮：主色圆形按钮，输入为空时半透明
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (text.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        .clickable(enabled = text.isNotBlank()) { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * PermissionDialog - 权限审批对话框
 *
 * 当 Agent 需要执行敏感操作时弹出，要求用户明确批准。
 * 显示工具名称和操作摘要，让用户能做出知情决策。
 *
 * @param toolName 请求权限的工具名称
 * @param inputSummary 操作内容摘要
 * @param onApprove 用户点击"允许"的回调
 * @param onDeny 用户点击"拒绝"的回调
 */
@Composable
fun PermissionDialog(
    toolName: String,
    inputSummary: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,  // 点击对话框外部视为拒绝
        icon = {
            Text(
                text = "🔐",
                fontSize = 28.sp
            )
        },
        title = {
            Text(
                text = "权限请求",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column {
                Text(
                    text = "Claude Code 请求执行以下操作：",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                // 工具名称标签
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "工具：",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 操作摘要
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                ) {
                    Text(
                        text = inputSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApprove,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("允许")
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(
                    text = "拒绝",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

// ===== Preview 预览 =====

/**
 * 预览：用户消息气泡
 */
@Preview(name = "用户消息气泡", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun UserMessageBubblePreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            UserMessageBubble(
                message = UserMessage(
                    text = "帮我分析这个项目的代码结构",
                    timestamp = "14:30"
                )
            )
        }
    }
}

/**
 * 预览：AI 助手消息
 */
@Preview(name = "AI助手消息", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun AssistantMessagePreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            AssistantMessageItem(
                message = AssistantText(
                    text = "好的，我来帮你分析项目结构。首先让我查看一下文件目录...",
                    isStreaming = false
                )
            )
        }
    }
}

/**
 * 预览：工具调用卡片（折叠）
 */
@Preview(name = "工具卡片-折叠", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun ToolCallCardCollapsedPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            ToolCallCardItem(
                card = ToolCallCard(
                    toolName = "Bash",
                    input = "ls -la /workspace",
                    output = "total 32\ndrwxr-xr-x ...",
                    durationMs = 1234,
                    isExpanded = false
                ),
                onToggleExpand = {}
            )
        }
    }
}

/**
 * 预览：工具调用卡片（展开）
 */
@Preview(name = "工具卡片-展开", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun ToolCallCardExpandedPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            ToolCallCardItem(
                card = ToolCallCard(
                    toolName = "Read",
                    input = "README.md",
                    output = "# Claude Code Android\n\n这是一个 Android 客户端...",
                    durationMs = 45,
                    isExpanded = true
                ),
                onToggleExpand = {}
            )
        }
    }
}

/**
 * 预览：打字指示器
 */
@Preview(name = "打字指示器", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun TypingIndicatorPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp)) {
            TypingIndicator()
        }
    }
}

/**
 * 预览：底部输入栏（空闲状态）
 */
@Preview(name = "输入栏-空闲", showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
fun InputBarIdlePreview() {
    ClaudeCodeTheme(darkTheme = true) {
        InputBar(
            text = "分析这段代码",
            onTextChange = {},
            onSend = {},
            onStop = {},
            isRunning = false
        )
    }
}

/**
 * 预览：底部输入栏（运行状态）
 */
@Preview(name = "输入栏-运行中", showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
fun InputBarRunningPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        InputBar(
            text = "",
            onTextChange = {},
            onSend = {},
            onStop = {},
            isRunning = true
        )
    }
}

/**
 * 预览：权限对话框
 */
@Preview(name = "权限对话框", showBackground = true)
@Composable
fun PermissionDialogPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        PermissionDialog(
            toolName = "Bash",
            inputSummary = "rm -rf /tmp/test_files\n# 删除测试文件",
            onApprove = {},
            onDeny = {}
        )
    }
}
