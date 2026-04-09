package com.claudecode.android.ui.sessions

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DismissDirection
import androidx.compose.material3.DismissState
import androidx.compose.material3.DismissValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.claudecode.android.ui.AppRoutes
import com.claudecode.android.ui.theme.ClaudeCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ===== 会话数据模型 =====

/**
 * SessionSummary - 会话摘要数据
 *
 * 用于在列表中展示会话的概要信息，不包含完整消息内容（避免加载过多数据）。
 * 完整消息内容只在打开会话时从数据库按需加载。
 *
 * @param id 会话唯一标识符
 * @param title 会话标题（通常取第一条用户消息的前 30 个字）
 * @param createdAt 会话创建时间，格式化后展示
 * @param lastMessagePreview 最后一条消息的预览文字（最多 60 字）
 * @param messageCount 本次会话的总消息数（包括用户和AI的消息）
 * @param workingDirectory 会话时使用的工作目录
 */
data class SessionSummary(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: String,
    val lastMessagePreview: String,
    val messageCount: Int,
    val workingDirectory: String = "/workspace"
)

// ===== ViewModel =====

/**
 * SessionListViewModel - 会话列表 ViewModel
 *
 * 负责加载和管理历史会话列表。
 * 实际实现应从 Room 数据库查询会话记录。
 */
class SessionListViewModel : ViewModel() {

    /** 内部可变的会话列表状态 */
    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** 对外暴露的只读会话列表 */
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    init {
        // 加载模拟数据（实际应从数据库查询）
        loadSampleSessions()
    }

    /**
     * deleteSession - 删除指定会话
     *
     * @param sessionId 要删除的会话 ID
     */
    fun deleteSession(sessionId: String) {
        _sessions.update { list ->
            list.filter { it.id != sessionId }
        }
        // 实际实现：从数据库删除 sessionId 对应的记录
    }

    /**
     * loadSampleSessions - 加载模拟会话数据
     *
     * 仅用于开发阶段展示效果，生产代码中替换为数据库查询。
     */
    private fun loadSampleSessions() {
        val dateFormat = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINESE)
        _sessions.value = listOf(
            SessionSummary(
                title = "分析 Android 项目架构",
                createdAt = "04月09日 14:30",
                lastMessagePreview = "已完成项目分析，发现 3 个潜在的内存泄漏问题，建议使用 WeakReference...",
                messageCount = 12,
                workingDirectory = "/workspace/android-project"
            ),
            SessionSummary(
                title = "重构 API 客户端代码",
                createdAt = "04月08日 09:15",
                lastMessagePreview = "Retrofit 接口已更新，添加了 Flow 支持和错误处理拦截器",
                messageCount = 8,
                workingDirectory = "/workspace/api-client"
            ),
            SessionSummary(
                title = "修复 Compose 界面渲染问题",
                createdAt = "04月07日 16:45",
                lastMessagePreview = "LazyColumn 的 key 参数已修正，列表更新动画恢复正常",
                messageCount = 6,
                workingDirectory = "/workspace/ui-fixes"
            ),
            SessionSummary(
                title = "编写单元测试套件",
                createdAt = "04月06日 11:20",
                lastMessagePreview = "已为 ViewModel 层创建 15 个测试用例，覆盖率达到 87%",
                messageCount = 20,
                workingDirectory = "/workspace/tests"
            ),
            SessionSummary(
                title = "数据库迁移方案设计",
                createdAt = "04月05日 15:00",
                lastMessagePreview = "Room 数据库 v2 迁移脚本已生成，需要处理外键约束问题",
                messageCount = 9,
                workingDirectory = "/workspace/database"
            )
        )
    }
}

/**
 * SessionListScreen - 历史会话列表界面
 *
 * 展示所有历史对话，用户可以：
 * - 点击继续某个历史会话
 * - 左滑删除会话记录
 * - 点击 FAB 新建会话
 *
 * 当列表为空时显示友好的空状态引导页面。
 *
 * @param navController 导航控制器，用于跳转和返回
 * @param viewModel 会话列表 ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    navController: NavController,
    viewModel: SessionListViewModel = viewModel()
) {
    val sessions by viewModel.sessions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "历史会话",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    // 返回聊天界面
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        // 右下角新建会话的 FAB 按钮
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // 跳转到聊天界面（已有的 chat 路由会通过 ViewModel 创建新会话）
                    navController.navigate(AppRoutes.CHAT) {
                        // 弹出到 chat 路由，避免堆叠
                        popUpTo(AppRoutes.CHAT) { inclusive = true }
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "新建会话"
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (sessions.isEmpty()) {
            // 空状态展示
            EmptySessionsState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            // 会话列表
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(
                    items = sessions,
                    key = { it.id }  // 使用 id 作为 key 优化列表动画
                ) { session ->
                    SwipeToDeleteSessionItem(
                        session = session,
                        onDelete = { viewModel.deleteSession(session.id) },
                        onClick = {
                            // 点击会话，跳转到聊天界面并恢复该会话
                            // 实际实现需要通过参数传递 sessionId
                            navController.navigate("${AppRoutes.CHAT}?sessionId=${session.id}") {
                                popUpTo(AppRoutes.CHAT) { inclusive = true }
                            }
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }  // FAB 下方留出空间
            }
        }
    }
}

/**
 * SwipeToDeleteSessionItem - 支持左滑删除的会话列表项
 *
 * 使用 SwipeToDismissBox 包裹会话卡片，当用户左滑超过阈值时触发删除。
 * 滑动时显示红色背景和删除图标作为视觉反馈。
 *
 * @param session 会话摘要数据
 * @param onDelete 删除确认回调
 * @param onClick 点击会话的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteSessionItem(
    session: SessionSummary,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    // 记录是否已删除，用于播放消失动画
    var isDeleted by remember { mutableStateOf(false) }

    // SwipeToDismissBox 的状态管理
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            // 只有当滑动到 EndToStart（从右向左）时才确认删除
            if (value == SwipeToDismissBoxValue.EndToStart) {
                isDeleted = true
                true  // 确认删除
            } else {
                false  // 其他方向不触发删除
            }
        }
    )

    // 删除确认后触发动画和回调
    LaunchedEffect(isDeleted) {
        if (isDeleted) {
            onDelete()
        }
    }

    // 带消失动画的容器
    AnimatedVisibility(
        visible = !isDeleted,
        exit = shrinkVertically() + fadeOut()
    ) {
        SwipeToDismissBox(
            state = dismissState,
            // 只允许从右向左滑动（EndToStart）
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            // 滑动时显示的背景层（红色删除提示）
            backgroundContent = {
                SwipeDeleteBackground()
            },
            content = {
                // 实际会话卡片内容
                SessionItemCard(
                    session = session,
                    onClick = onClick
                )
            }
        )
    }
}

/**
 * SwipeDeleteBackground - 左滑删除时的背景层
 *
 * 当用户向左滑动会话卡片时，显示此红色背景和删除图标，
 * 给用户清晰的视觉反馈：继续滑动将删除此会话。
 */
@Composable
fun SwipeDeleteBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onError
            )
        }
    }
}

/**
 * SessionItemCard - 单条会话展示卡片
 *
 * 展示会话摘要信息：
 * - 左侧：会话图标（彩色圆形头像）
 * - 右侧顶部：标题 + 时间
 * - 右侧底部：最后一条消息预览 + 消息计数
 *
 * @param session 会话数据
 * @param onClick 点击回调
 */
@Composable
fun SessionItemCard(
    session: SessionSummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：会话图标（用标题首字母生成彩色头像）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    // 取标题第一个字作为头像文字
                    text = session.title.firstOrNull()?.toString() ?: "C",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 右侧：会话信息
            Column(modifier = Modifier.weight(1f)) {
                // 顶部行：标题 + 时间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 会话标题
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 会话时间
                    Text(
                        text = session.createdAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 底部行：消息预览 + 消息数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 最后一条消息预览
                    Text(
                        text = session.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 消息计数标签
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${session.messageCount}条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }

                // 工作目录标签（小字等宽字体）
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = session.workingDirectory,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * EmptySessionsState - 空会话状态展示
 *
 * 当用户还没有任何历史会话时显示此引导页面，包含：
 * - 大图标（聊天气泡）
 * - 标题文字
 * - 副标题引导文字
 *
 * 设计目标：让新用户快速理解如何开始使用，减少困惑。
 *
 * @param modifier 布局修饰符
 */
@Composable
fun EmptySessionsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 大图标
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 主标题
        Text(
            text = "还没有会话记录",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 副标题引导语
        Text(
            text = "开始你的第一个 Claude Code 会话",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "点击右下角的 + 按钮，向 Claude 描述你的编程任务",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// ===== Preview 预览 =====

/**
 * 预览：会话列表（深色主题）
 */
@Preview(name = "会话列表-深色", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun SessionListDarkPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        SessionListScreen(navController = rememberNavController())
    }
}

/**
 * 预览：会话列表（浅色主题）
 */
@Preview(name = "会话列表-浅色", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun SessionListLightPreview() {
    ClaudeCodeTheme(darkTheme = false) {
        SessionListScreen(navController = rememberNavController())
    }
}

/**
 * 预览：单条会话卡片
 */
@Preview(name = "会话卡片", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun SessionItemCardPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            SessionItemCard(
                session = SessionSummary(
                    title = "分析 Android 项目架构",
                    createdAt = "04月09日 14:30",
                    lastMessagePreview = "已完成项目分析，发现 3 个潜在的内存泄漏问题...",
                    messageCount = 12,
                    workingDirectory = "/workspace/android-project"
                ),
                onClick = {}
            )
        }
    }
}

/**
 * 预览：空状态
 */
@Preview(name = "空状态", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun EmptySessionsPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        EmptySessionsState(modifier = Modifier.fillMaxSize())
    }
}
