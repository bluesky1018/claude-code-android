package com.claudecode.android.agent

import com.claudecode.android.api.ContentBlock
import com.claudecode.android.api.Message
import com.claudecode.android.api.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Agent 会话（AgentSession）
 *
 * 表示一个完整的 Claude Code 工作会话，包含：
 * - 完整的对话消息历史（每次 API 调用都需要传全部历史）
 * - 当前工作目录（工具调用的默认根目录）
 * - 会话状态（空闲、思考中、调用工具中、已完成）
 * - Token 使用统计
 *
 * 一个会话对应用户从开始任务到任务完成的完整过程。
 * 会话可以被保存到数据库，下次打开 App 时恢复。
 */
data class AgentSession(
    /** 会话唯一 ID */
    val sessionId: String = UUID.randomUUID().toString(),

    /** 会话标题（用于展示在历史列表中） */
    val title: String = "新会话",

    /** 工作目录路径（工具调用的默认根目录） */
    val workingDirectory: String = "/",

    /** 会话创建时间（Unix 时间戳，毫秒） */
    val createdAt: Long = System.currentTimeMillis()
) {
    // ============================================================
    // 消息历史管理（可变状态，使用 StateFlow 驱动 UI 更新）
    // ============================================================

    /** 完整的对话消息历史（user/assistant 轮流） */
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /** 会话当前状态 */
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    /** 当前 Token 使用量 */
    private val _tokenUsage = MutableStateFlow(TokenUsage(0, 0))
    val tokenUsage: StateFlow<TokenUsage> = _tokenUsage.asStateFlow()

    /** 当前正在执行的工具名称（用于 UI 展示"正在调用 Read..."） */
    private val _currentTool = MutableStateFlow<String?>(null)
    val currentTool: StateFlow<String?> = _currentTool.asStateFlow()

    // ============================================================
    // 消息操作方法
    // ============================================================

    /**
     * 添加用户消息
     * 在用户发送指令时调用
     */
    fun addUserMessage(text: String) {
        val message = Message.user(text)
        _messages.value = _messages.value + message
    }

    /**
     * 添加 assistant 消息（Claude 的回复）
     * 从 API 响应中提取内容后调用
     */
    fun addAssistantMessage(content: List<ContentBlock>) {
        val message = Message("assistant", content)
        _messages.value = _messages.value + message
    }

    /**
     * 添加工具调用结果
     * 工具执行完成后，将结果包装成 user 消息传回给 Claude
     *
     * 注意：工具结果使用 "user" 角色，但内容类型是 "tool_result"
     * 这是 Anthropic API 的规范，不要弄错！
     */
    fun addToolResult(toolUseId: String, result: String, isError: Boolean = false) {
        val toolResult = ContentBlock.ToolResult(
            toolUseId = toolUseId,
            content = result,
            isError = isError
        )
        // 将工具结果追加到最后一条 user 消息，或创建新的 user 消息
        val currentMessages = _messages.value.toMutableList()
        val lastMessage = currentMessages.lastOrNull()

        if (lastMessage?.role == "user") {
            // 追加到现有 user 消息（当有多个工具同时调用时）
            val updatedContent = lastMessage.content + toolResult
            currentMessages[currentMessages.lastIndex] = lastMessage.copy(content = updatedContent)
        } else {
            // 创建新的 user 消息
            currentMessages.add(Message("user", listOf(toolResult)))
        }

        _messages.value = currentMessages
    }

    /**
     * 批量替换消息历史（用于上下文压缩后更新消息列表）
     */
    fun replaceMessages(newMessages: List<Message>) {
        _messages.value = newMessages
    }

    // ============================================================
    // 状态更新方法
    // ============================================================

    fun setStatus(status: SessionStatus) { _status.value = status }
    fun updateTokenUsage(usage: TokenUsage) { _tokenUsage.value = usage }
    fun setCurrentTool(toolName: String?) { _currentTool.value = toolName }

    /** 会话是否处于活跃状态（正在运行） */
    val isActive: Boolean get() = _status.value !is SessionStatus.Idle && _status.value !is SessionStatus.Completed
}

/**
 * 会话状态枚举
 *
 * 状态转换流程：
 * Idle -> Thinking（发送请求后）
 * Thinking -> ExecutingTool（需要调用工具时）
 * ExecutingTool -> Thinking（工具执行完毕，继续请求 Claude）
 * Thinking -> Completed（Claude 返回 end_turn）
 * 任意状态 -> Error（发生错误）
 * 任意状态 -> Idle（用户中断或会话结束）
 */
sealed class SessionStatus {
    /** 空闲，等待用户输入 */
    object Idle : SessionStatus()

    /** Claude 正在思考（等待 API 响应） */
    object Thinking : SessionStatus()

    /**
     * 正在执行工具
     * @param toolName 当前执行的工具名（如 "Read", "Write"）
     */
    data class ExecutingTool(val toolName: String) : SessionStatus()

    /** 任务已完成 */
    object Completed : SessionStatus()

    /**
     * 发生错误
     * @param message 错误描述
     */
    data class Error(val message: String) : SessionStatus()
}
