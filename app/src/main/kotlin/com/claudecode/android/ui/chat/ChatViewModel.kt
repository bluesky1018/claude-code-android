package com.claudecode.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// ===== UI 消息类型定义 =====
// 使用 sealed class 确保类型安全，穷举所有消息展示类型

/**
 * UiMessage - 聊天界面消息的抽象基类
 *
 * 采用 sealed class 而非接口，确保在 when 表达式中能穷举所有子类型，
 * 避免遗漏处理某种消息类型导致 UI 展示错误。
 *
 * 每个子类都有唯一的 id，用于 LazyColumn 的 key 优化列表性能。
 */
sealed class UiMessage {
    abstract val id: String
}

/**
 * UserMessage - 用户发送的消息
 *
 * 在界面上显示为右对齐的蓝色气泡，类似 iMessage 风格。
 *
 * @param id 唯一标识符，默认使用 UUID
 * @param text 消息文本内容
 * @param timestamp 发送时间，格式化为 "HH:mm" 显示在气泡下方
 */
data class UserMessage(
    override val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
) : UiMessage()

/**
 * AssistantText - AI 助手回复的文本消息
 *
 * 显示在左侧，支持 Markdown 渲染（代码块、加粗、列表等）。
 * isStreaming 为 true 时显示光标动画，表示正在流式输出。
 *
 * @param id 唯一标识符
 * @param text 消息文本（可能包含 Markdown 语法）
 * @param isStreaming 是否正在流式输出中，用于显示打字光标
 */
data class AssistantText(
    override val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isStreaming: Boolean = false
) : UiMessage()

/**
 * ToolCallCard - 工具调用展示卡片
 *
 * 当 Agent 调用工具时（如执行 Bash 命令、读写文件等），以卡片形式
 * 展示在消息列表中。卡片支持展开/折叠查看详细输入输出内容。
 *
 * @param id 唯一标识符
 * @param toolName 工具名称（Bash、Read、Write、Edit、Glob、Grep 等）
 * @param input 工具调用的输入参数（通常是 JSON 字符串或命令）
 * @param output 工具执行的输出结果（可能为 null，表示尚未完成）
 * @param isError 是否执行出错，影响状态图标和颜色
 * @param durationMs 执行耗时（毫秒），null 表示还在执行中
 * @param isExpanded 卡片是否展开显示详细信息，默认折叠节省空间
 */
data class ToolCallCard(
    override val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val input: String,
    val output: String? = null,
    val isError: Boolean = false,
    val durationMs: Long? = null,
    val isExpanded: Boolean = false
) : UiMessage()

/**
 * ErrorMessage - 错误消息展示
 *
 * 当 AgentLoop 运行出错（网络错误、API 错误等）时，以醒目的
 * 红色错误卡片展示在消息列表底部。
 *
 * @param id 唯一标识符
 * @param message 错误描述文字，尽量对用户友好
 */
data class ErrorMessage(
    override val id: String = UUID.randomUUID().toString(),
    val message: String
) : UiMessage()

// ===== UI 状态定义 =====

/**
 * ChatUiState - 聊天界面的完整 UI 状态
 *
 * 遵循单一数据源原则（Single Source of Truth），所有界面状态
 * 集中在这一个 data class 中，方便状态快照、测试和调试。
 *
 * @param messages 当前会话的所有消息列表，按时间顺序排列
 * @param isAgentRunning Agent 是否正在运行，控制发送按钮和停止按钮的显示
 * @param currentToolName 当前正在执行的工具名称，用于状态栏显示"正在执行 Bash..."
 * @param errorMessage 全局错误信息（非消息列表中的错误），用于 Snackbar
 * @param tokenUsage Token 使用量 Pair<已用Token数, 总Token限制>，用于进度条显示
 * @param workingDirectory 当前工作目录，显示在顶部状态栏
 * @param pendingPermissionRequest 等待用户审批的权限请求，非 null 时弹出确认对话框
 */
data class ChatUiState(
    /** 当前会话所有消息，包括用户消息、AI回复、工具调用卡片 */
    val messages: List<UiMessage> = emptyList(),

    /** Agent 是否正在运行：true 时显示停止按钮，false 时显示发送按钮 */
    val isAgentRunning: Boolean = false,

    /** 当前执行的工具名称，如 "Bash"、"Read"，null 表示没有工具在执行 */
    val currentToolName: String? = null,

    /** Snackbar 错误消息，显示后应清空 */
    val errorMessage: String? = null,

    /** Token 使用量 (已用, 总量)，用于显示配额进度条 */
    val tokenUsage: Pair<Int, Int>? = null,

    /** 当前工作目录路径，显示在 AppBar 副标题 */
    val workingDirectory: String = "/workspace",

    /** 待审批的权限请求，包含工具名和操作摘要 */
    val pendingPermissionRequest: PermissionRequest? = null
)

/**
 * PermissionRequest - 权限审批请求
 *
 * 当 Agent 需要执行敏感操作（如写文件、执行命令）时，
 * 需要用户明确批准，此数据类保存请求的详细信息。
 *
 * @param requestId 请求唯一 ID，用于匹配审批回调
 * @param toolName 请求权限的工具名称
 * @param inputSummary 操作摘要，向用户说明将要执行什么
 */
data class PermissionRequest(
    val requestId: String,
    val toolName: String,
    val inputSummary: String
)

/**
 * ChatViewModel - 聊天界面的核心业务逻辑层
 *
 * 职责：
 * 1. 维护聊天界面的完整 UI 状态
 * 2. 处理用户输入和消息发送
 * 3. 管理 AgentLoop 的生命周期（启动、停止）
 * 4. 处理权限审批流程
 * 5. 管理会话创建和切换
 *
 * 使用 StateFlow 而非 LiveData，原因：
 * - 与 Kotlin 协程天然集成
 * - 支持 Compose 的状态收集（collectAsStateWithLifecycle）
 * - 有初始值，不会出现 LiveData 的 null 问题
 */
class ChatViewModel : ViewModel() {

    // ===== 私有可变状态 =====

    /**
     * 内部可变的 UI 状态，使用 MutableStateFlow 以支持原子更新
     * 外部只暴露只读的 StateFlow，防止外部意外修改状态
     */
    private val _uiState = MutableStateFlow(ChatUiState())

    /**
     * 对外暴露的只读 UI 状态流
     * UI 层通过 collectAsStateWithLifecycle() 观察此流
     */
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * 输入框文字状态
     * 单独抽出为独立的 StateFlow，避免每次输入都触发整个 UI 状态重组
     */
    val inputText = MutableStateFlow("")

    /**
     * 当前 AgentLoop 协程任务的引用
     * 保存 Job 引用以便在用户点击"停止"时能取消协程
     */
    private var agentJob: Job? = null

    /**
     * 当前会话 ID
     * 用于将消息持久化到数据库中对应的会话记录
     */
    private var currentSessionId: String = UUID.randomUUID().toString()

    // ===== 公开操作方法 =====

    /**
     * sendMessage - 发送用户消息并启动 AgentLoop
     *
     * 执行流程：
     * 1. 检查输入非空
     * 2. 将用户消息添加到消息列表
     * 3. 清空输入框
     * 4. 在 viewModelScope 中启动协程执行 AgentLoop
     * 5. AgentLoop 运行期间持续更新 UI 状态
     *
     * @param text 用户输入的消息文本
     */
    fun sendMessage(text: String) {
        // 检查输入非空且 Agent 未在运行
        if (text.isBlank() || _uiState.value.isAgentRunning) return

        // 创建用户消息并添加到列表
        val userMessage = UserMessage(text = text.trim())
        addMessage(userMessage)

        // 清空输入框，让用户知道消息已发送
        inputText.value = ""

        // 在 viewModelScope 中启动 AgentLoop
        // viewModelScope 会在 ViewModel 销毁时自动取消，防止内存泄漏
        agentJob = viewModelScope.launch {
            runAgentLoop(text.trim())
        }
    }

    /**
     * stopTask - 停止当前运行的 AgentLoop
     *
     * 取消协程会触发 CancellationException，AgentLoop 应在
     * 各个挂起点（delay、IO操作等）处检查并响应取消信号。
     * 取消后将 isAgentRunning 置为 false，恢复发送按钮。
     */
    fun stopTask() {
        agentJob?.cancel()
        agentJob = null
        // 更新 UI 状态：停止运行标志，清除工具执行状态
        _uiState.update { state ->
            state.copy(
                isAgentRunning = false,
                currentToolName = null
            )
        }
    }

    /**
     * newSession - 创建新的对话会话
     *
     * 清空当前消息列表，重置所有运行状态，生成新的会话 ID。
     * 调用前应先保存当前会话（由 Repository 层负责）。
     */
    fun newSession() {
        // 如果有正在运行的任务，先停止
        stopTask()

        // 重置所有状态，开始新会话
        currentSessionId = UUID.randomUUID().toString()
        _uiState.value = ChatUiState()
        inputText.value = ""
    }

    /**
     * approvePermission - 用户批准权限请求
     *
     * 当用户点击权限对话框的"允许"按钮时调用。
     * 将审批结果通知 AgentLoop，允许其继续执行操作。
     *
     * @param requestId 要审批的权限请求 ID，必须与待审批请求匹配
     */
    fun approvePermission(requestId: String) {
        val current = _uiState.value.pendingPermissionRequest
        if (current?.requestId != requestId) return

        // 清除待审批请求，恢复正常状态
        _uiState.update { it.copy(pendingPermissionRequest = null) }

        // 通知 AgentLoop 权限已批准（实际实现中通过 Channel 或 CompletableDeferred 传递）
        permissionResultMap[requestId] = true
    }

    /**
     * denyPermission - 用户拒绝权限请求
     *
     * 当用户点击权限对话框的"拒绝"按钮时调用。
     * AgentLoop 收到拒绝信号后应停止当前操作并向用户说明原因。
     *
     * @param requestId 要拒绝的权限请求 ID
     */
    fun denyPermission(requestId: String) {
        val current = _uiState.value.pendingPermissionRequest
        if (current?.requestId != requestId) return

        // 清除待审批请求
        _uiState.update { it.copy(pendingPermissionRequest = null) }

        // 通知 AgentLoop 权限已拒绝
        permissionResultMap[requestId] = false
    }

    /**
     * clearError - 清除 Snackbar 错误消息
     * 在 Snackbar 展示完成后调用，避免重组时重复显示
     */
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * toggleToolCardExpand - 切换工具调用卡片的展开/折叠状态
     *
     * @param messageId 要切换展开状态的工具调用卡片 ID
     */
    fun toggleToolCardExpand(messageId: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message is ToolCallCard && message.id == messageId) {
                        message.copy(isExpanded = !message.isExpanded)
                    } else {
                        message
                    }
                }
            )
        }
    }

    // ===== 私有辅助方法 =====

    /**
     * 权限结果暂存 Map
     * 用于 AgentLoop 和权限对话框之间的通信
     * 实际生产代码应使用 CompletableDeferred 或 Channel
     */
    private val permissionResultMap = mutableMapOf<String, Boolean>()

    /**
     * addMessage - 向消息列表添加一条新消息
     *
     * @param message 要添加的消息
     */
    private fun addMessage(message: UiMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages + message)
        }
    }

    /**
     * updateLastAssistantMessage - 更新最后一条 AssistantText 消息的内容
     *
     * 用于流式输出场景：每次收到新的 token 时追加到最后一条消息，
     * 而不是创建新消息，实现打字机效果。
     *
     * @param appendText 要追加的文本片段
     * @param isStreaming 是否还在流式输出中
     */
    private fun updateLastAssistantMessage(appendText: String, isStreaming: Boolean) {
        _uiState.update { state ->
            val messages = state.messages.toMutableList()
            val lastIndex = messages.indexOfLast { it is AssistantText }
            if (lastIndex >= 0) {
                val last = messages[lastIndex] as AssistantText
                messages[lastIndex] = last.copy(
                    text = last.text + appendText,
                    isStreaming = isStreaming
                )
            } else {
                // 如果没有 AssistantText，创建一个新的
                messages.add(AssistantText(text = appendText, isStreaming = isStreaming))
            }
            state.copy(messages = messages)
        }
    }

    /**
     * runAgentLoop - AgentLoop 主执行逻辑（模拟实现）
     *
     * 这是 AgentLoop 的核心方法，实际实现应：
     * 1. 调用 Claude API 获取回复（流式）
     * 2. 解析工具调用指令
     * 3. 执行对应工具（Bash、Read、Write 等）
     * 4. 将工具结果反馈给 Claude
     * 5. 循环直到 Claude 不再调用工具（stop_reason == "end_turn"）
     *
     * 当前为模拟实现，展示完整的状态流转过程。
     *
     * @param userInput 用户输入的消息
     */
    private suspend fun runAgentLoop(userInput: String) {
        try {
            // 进入运行状态，显示停止按钮
            _uiState.update { it.copy(isAgentRunning = true, currentToolName = null) }

            // 模拟 AI 思考延迟（实际为 API 请求等待时间）
            delay(500)

            // 模拟流式文本输出：创建初始空消息
            val assistantMsgId = UUID.randomUUID().toString()
            addMessage(AssistantText(id = assistantMsgId, text = "", isStreaming = true))

            // 模拟流式 token 输出
            val responseText = "我来帮你分析这个任务："$userInput"

首先让我查看一下当前目录的文件结构。"
            for (char in responseText) {
                updateLastAssistantMessage(char.toString(), isStreaming = true)
                delay(20) // 每个字符 20ms，模拟打字效果
            }

            // 标记流式输出完成
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg is AssistantText && msg.id == assistantMsgId) {
                            msg.copy(isStreaming = false)
                        } else msg
                    }
                )
            }

            // 模拟工具调用：显示正在执行 Bash 命令
            _uiState.update { it.copy(currentToolName = "Bash") }
            val toolCardId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()

            // 添加工具调用卡片（执行中状态）
            addMessage(
                ToolCallCard(
                    id = toolCardId,
                    toolName = "Bash",
                    input = "ls -la",
                    output = null,  // null 表示执行中
                    isError = false,
                    durationMs = null
                )
            )

            // 模拟命令执行时间
            delay(1200)

            // 更新工具调用卡片（执行完成）
            val durationMs = System.currentTimeMillis() - startTime
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg is ToolCallCard && msg.id == toolCardId) {
                            msg.copy(
                                output = "total 48
drwxr-xr-x  8 user  staff   256 Apr  9 10:30 .
drwxr-xr-x  3 user  staff    96 Apr  9 10:30 ..
-rw-r--r--  1 user  staff  1234 Apr  9 10:30 README.md
drwxr-xr-x  5 user  staff   160 Apr  9 10:30 src",
                                durationMs = durationMs
                            )
                        } else msg
                    },
                    currentToolName = null
                )
            }

            // 模拟最终总结回复
            delay(300)
            val finalMsgId = UUID.randomUUID().toString()
            addMessage(AssistantText(id = finalMsgId, text = "", isStreaming = true))

            val summaryText = "
目录中有 README.md 和 src 文件夹。任务执行完成！"
            for (char in summaryText) {
                updateLastAssistantMessage(char.toString(), isStreaming = true)
                delay(25)
            }

            // 完成流式输出
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { msg ->
                        if (msg is AssistantText && msg.id == finalMsgId) {
                            msg.copy(isStreaming = false)
                        } else msg
                    },
                    // 更新 Token 使用量（模拟值）
                    tokenUsage = Pair(1250, 200000)
                )
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（用户点击停止），这是正常流程，重新抛出让协程框架处理
            throw e
        } catch (e: Exception) {
            // 其他错误：网络错误、API 错误等
            addMessage(ErrorMessage(message = "执行出错：${e.message ?: "未知错误"}"))
            _uiState.update { it.copy(errorMessage = "AgentLoop 执行失败：${e.message}") }
        } finally {
            // 无论成功失败，都要恢复 UI 为非运行状态
            _uiState.update { state ->
                state.copy(
                    isAgentRunning = false,
                    currentToolName = null
                )
            }
        }
    }
}
