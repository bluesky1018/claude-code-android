package com.claudecode.android.agent

import android.util.Log
import com.claudecode.android.api.ContentBlock
import com.claudecode.android.api.Message
import com.claudecode.android.api.TokenUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

private const val TAG = "AgentSession"

/**
 * Agent 会话（AgentSession）
 *
 * 表示一个完整的 Claude Code 工作会话，与真实 Claude Code 的 Session 对象像素级对应。
 * 包含：
 * - 完整的对话消息历史（每次 API 调用都需要传全部历史）
 * - 当前工作目录（工具调用的默认根目录）
 * - 会话状态（空闲、思考中、调用工具中、已完成、已暂停、错误）
 * - Token 使用统计 + USD 费用追踪
 * - JSONL 持久化（每条消息实时写入磁盘，与真实 Claude Code 一致）
 * - 会话 fork（在当前时间点创建分支会话）
 * - 最大轮次限制（null = 无限，与真实 Claude Code 默认行为一致）
 * - 预算控制（可选 USD 上限）
 * - 暂停/恢复机制（处理 pause_turn stop reason，2025 新功能）
 */
data class AgentSession(
    /** 会话唯一 ID（与 JSONL 文件名对应，如 abc123.jsonl） */
    val sessionId: String = UUID.randomUUID().toString(),

    /** 会话标题（用于展示在历史列表中） */
    val title: String = "新会话",

    /** 工作目录路径（工具调用的默认根目录） */
    val workingDirectory: String = "/",

    /**
     * 会话开始时间戳（Unix 毫秒）
     * 与真实 Claude Code 的 session.startTime 对应
     */
    val startTime: Long = System.currentTimeMillis(),

    /**
     * 会话创建时间（Unix 毫秒，保持向后兼容）
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * JSONL 持久化文件路径
     * 格式：~/.claude/projects/<projectHash>/<sessionId>.jsonl
     * 与真实 Claude Code 的 JSONL 存储路径完全一致
     * null = 不持久化（内存模式）
     */
    val sessionFilePath: String? = null,

    /**
     * 当前使用的 Claude 模型 ID
     * 用于计算每次 API 调用的 USD 费用
     */
    val currentModel: String = "claude-opus-4-6",

    /**
     * 最大对话轮次限制
     * null = 无限轮次（与真实 Claude Code 默认行为一致，不应硬编码 100）
     * 非 null = 达到此轮次后自动停止循环
     */
    var maxTurns: Int? = null,

    /**
     * 可选 USD 预算上限
     * null = 无预算限制
     * 非 null = 累计费用达到此值后，AgentLoop 发出 BudgetExceeded 事件并停止
     */
    var budgetUsd: Double? = null
) {
    // ============================================================
    // 费用追踪（由 AgentLoop 在每次 API 调用后更新）
    // ============================================================

    /**
     * 当前累计 USD 费用
     * 每次 API 调用后由 AgentLoop 根据 usage 和模型单价计算并累加
     */
    var currentCostUsd: Double = 0.0

    // ============================================================
    // 消息历史管理（可变状态，使用 StateFlow 驱动 UI 实时更新）
    // ============================================================

    /** 完整的对话消息历史（user/assistant 轮流） */
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /**
     * 会话当前状态
     * 详见 SessionStatus 枚举的状态转换说明
     */
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Idle)
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    /** 当前 Token 使用量（输入 + 输出 token 数） */
    private val _tokenUsage = MutableStateFlow(TokenUsage(0, 0))
    val tokenUsage: StateFlow<TokenUsage> = _tokenUsage.asStateFlow()

    /** 当前正在执行的工具名称（用于 UI 展示"正在调用 Read..."），null 表示无工具执行 */
    private val _currentTool = MutableStateFlow<String?>(null)
    val currentTool: StateFlow<String?> = _currentTool.asStateFlow()

    /**
     * 暂停恢复信号（CompletableDeferred）
     * 当 AgentLoop 收到 stop_reason == "pause_turn" 时，调用 resumeSignal.await() 阻塞等待
     * UI 层调用 session.resume() 后触发 resumeSignal.complete(Unit)，AgentLoop 继续执行
     * 每次暂停结束后重置为新的 CompletableDeferred，供下次暂停使用
     */
    @Volatile
    var resumeSignal: CompletableDeferred<Unit> = CompletableDeferred()

    // ============================================================
    // JSONL 持久化
    // ============================================================

    /**
     * 将消息追加写入 JSONL 文件（实时持久化）
     *
     * 真实 Claude Code 每条消息都实时写入磁盘，确保崩溃后可恢复会话历史。
     * 每行是一个独立的 JSON 对象（JSONL = JSON Lines 格式）。
     * 如果 sessionFilePath 为 null，则跳过持久化（内存模式）。
     *
     * 文件路径：~/.claude/projects/<projectHash>/<sessionId>.jsonl
     *
     * @param message 要持久化的消息对象（会被序列化为一行 JSON）
     */
    fun appendToJSONL(message: Message) {
        val path = sessionFilePath ?: return
        try {
            val file = File(path)
            // 确保父目录存在（~/.claude/projects/<hash>/）
            file.parentFile?.mkdirs()
            // 序列化为单行 JSON 并追加换行符（JSONL 格式每行一个对象）
            val jsonLine = Json { ignoreUnknownKeys = true }.encodeToString(message) + "\n"
            file.appendText(jsonLine, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "JSONL 持久化失败，路径: $path，消息角色: ${message.role}", e)
        }
    }

    /**
     * 创建当前会话的 fork（分支会话）
     *
     * 与真实 Claude Code 的 --fork 功能对应，用于实验性分支探索：
     * - 复制当前所有消息历史到新会话
     * - 生成新的 sessionId（UUID）
     * - 继承父会话的所有配置（工作目录、模型、最大轮次、预算上限）
     * - 新会话的 JSONL 路径基于相同 projectHash + 新 sessionId
     * - 费用重置为 0（fork 是独立的新分支，费用独立计算）
     * - 标题添加 "(fork)" 后缀
     *
     * @return 新的分支 AgentSession，已包含当前时间点的完整消息历史
     */
    fun fork(): AgentSession {
        val newSessionId = UUID.randomUUID().toString()
        Log.i(TAG, "Fork 会话: $sessionId -> $newSessionId，消息数: ${_messages.value.size}")

        // 计算新 JSONL 文件路径（与父会话在同一 projectHash 目录下）
        val newFilePath = if (sessionFilePath != null) {
            val parentDir = File(sessionFilePath).parent
            if (parentDir != null) "$parentDir/$newSessionId.jsonl" else null
        } else {
            null
        }

        // 创建分支会话，继承父会话配置
        val forked = AgentSession(
            sessionId = newSessionId,
            title = "$title (fork)",
            workingDirectory = workingDirectory,
            startTime = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            sessionFilePath = newFilePath,
            currentModel = currentModel,
            maxTurns = maxTurns,
            budgetUsd = budgetUsd
        )

        // 复制当前所有消息历史（创建不可变列表副本）
        forked.replaceMessages(_messages.value.toList())

        // 将父会话的历史快照写入新 JSONL 文件
        _messages.value.forEach { forked.appendToJSONL(it) }

        return forked
    }

    // ============================================================
    // 消息操作方法
    // ============================================================

    /**
     * 添加用户消息
     * 在用户发送指令时调用，同步持久化到 JSONL
     *
     * @param text 用户输入的纯文本内容
     */
    fun addUserMessage(text: String) {
        val message = Message.user(text)
        _messages.value = _messages.value + message
        appendToJSONL(message)
    }

    /**
     * 添加 assistant 消息（Claude 的回复）
     * 从 API 响应的 content 字段提取后调用，同步持久化到 JSONL
     *
     * @param content Claude 返回的内容块列表（文本、工具调用、思考等）
     */
    fun addAssistantMessage(content: List<ContentBlock>) {
        val message = Message("assistant", content)
        _messages.value = _messages.value + message
        appendToJSONL(message)
    }

    /**
     * 添加工具调用结果
     * 工具执行完成后，将结果包装成 user 消息传回给 Claude
     *
     * 重要说明：
     * - 工具结果使用 "user" 角色，但内容类型是 "tool_result"（Anthropic API 规范）
     * - 当多个工具并发执行时，所有结果合并到同一条 user 消息中
     * - 每次更新都持久化到 JSONL
     *
     * @param toolUseId 对应的 tool_use 块 ID
     * @param result 工具执行结果文本
     * @param isError 是否执行失败
     */
    fun addToolResult(toolUseId: String, result: String, isError: Boolean = false) {
        val toolResult = ContentBlock.ToolResult(
            toolUseId = toolUseId,
            content = result,
            isError = isError
        )
        val currentMessages = _messages.value.toMutableList()
        val lastMessage = currentMessages.lastOrNull()

        if (lastMessage?.role == "user") {
            // 追加到现有 user 消息（并发工具执行时，多个结果合并）
            val updatedContent = lastMessage.content + toolResult
            val updatedMessage = lastMessage.copy(content = updatedContent)
            currentMessages[currentMessages.lastIndex] = updatedMessage
            _messages.value = currentMessages
            // 持久化更新后的完整 user 消息
            appendToJSONL(updatedMessage)
        } else {
            // 创建新的 user 消息承载工具结果
            val newMessage = Message("user", listOf(toolResult))
            currentMessages.add(newMessage)
            _messages.value = currentMessages
            appendToJSONL(newMessage)
        }
    }

    /**
     * 批量替换消息历史
     * 用于上下文压缩后更新消息列表（压缩是内存操作，不重写 JSONL）
     *
     * @param newMessages 新的消息历史列表
     */
    fun replaceMessages(newMessages: List<Message>) {
        _messages.value = newMessages
    }

    // ============================================================
    // 暂停/恢复控制
    // ============================================================

    /**
     * 恢复已暂停的会话
     * UI 层在用户点击"继续"按钮时调用
     * 触发 AgentLoop 中 resumeSignal.await() 的解除阻塞
     *
     * 如果会话不在 Paused 状态，调用无效
     */
    fun resume() {
        if (_status.value is SessionStatus.Paused) {
            Log.i(TAG, "恢复暂停的会话: $sessionId")
            resumeSignal.complete(Unit)
            // 重置信号，供下次 pause_turn 使用
            resumeSignal = CompletableDeferred()
        } else {
            Log.w(TAG, "尝试恢复非暂停状态的会话: $sessionId，当前状态: ${_status.value}")
        }
    }

    // ============================================================
    // 状态更新方法（供 AgentLoop 内部调用）
    // ============================================================

    /** 设置会话状态，线程安全（StateFlow 保证） */
    fun setStatus(status: SessionStatus) { _status.value = status }

    /** 更新 Token 使用统计（输入 + 输出 token 总数） */
    fun updateTokenUsage(usage: TokenUsage) { _tokenUsage.value = usage }

    /** 设置当前正在执行的工具名称（null = 无工具在执行） */
    fun setCurrentTool(toolName: String?) { _currentTool.value = toolName }

    /**
     * 会话是否处于活跃运行状态
     * 活跃 = 不是空闲、不是已完成、不是已暂停
     */
    val isActive: Boolean
        get() = _status.value !is SessionStatus.Idle
            && _status.value !is SessionStatus.Completed
            && _status.value !is SessionStatus.Paused
}

/**
 * 会话状态枚举（SessionStatus）
 *
 * 与真实 Claude Code 的 session status 完全对应，新增 Paused 状态（2025年 pause_turn 功能）：
 *
 * 正常流程：
 *   Idle -> Thinking（用户发送消息，开始 API 请求）
 *   Thinking -> ExecutingTool（Claude 返回 tool_use，包含工具名和开始时间）
 *   ExecutingTool -> Thinking（工具执行完毕，继续请求 Claude）
 *   Thinking -> Completed（Claude 返回 end_turn，任务结束）
 *
 * 暂停流程（新功能）：
 *   Thinking -> Paused（Claude 返回 pause_turn，等待用户输入）
 *   Paused -> Thinking（用户调用 session.resume()，继续执行）
 *
 * 异常流程：
 *   任意状态 -> Error（发生不可恢复的错误）
 *   任意状态 -> Idle（用户主动取消）
 */
sealed class SessionStatus {
    /** 空闲，等待用户输入（初始状态） */
    object Idle : SessionStatus()

    /** Claude 正在思考（已发出 API 请求，等待响应中） */
    object Thinking : SessionStatus()

    /**
     * 正在执行工具
     * @param toolName 当前执行的工具名（如 "Read", "Write", "Bash", "Glob", "Grep"）
     * @param startTime 工具开始执行的时间戳（毫秒），用于 UI 显示耗时
     */
    data class ExecutingTool(
        val toolName: String,
        val startTime: Long = System.currentTimeMillis()
    ) : SessionStatus()

    /** 任务已成功完成（Claude 返回 end_turn） */
    object Completed : SessionStatus()

    /**
     * 发生错误，任务终止
     * @param message 人类可读的错误描述
     */
    data class Error(val message: String) : SessionStatus()

    /**
     * 会话已暂停（Claude 返回 pause_turn stop reason）
     * 真实 Claude Code 2025 年新增功能，用于需要人工介入的场景
     * 等待用户调用 session.resume() 才能继续
     */
    object Paused : SessionStatus()
}

/**
 * 计算项目哈希值（用于构建 JSONL 文件路径）
 *
 * 与真实 Claude Code 的 getProjectHash() 函数对应：
 * 对工作目录路径进行 SHA-256 哈希，取前 8 个字符作为项目唯一标识符。
 *
 * @param workingDirectory 工作目录绝对路径（如 "/data/user/0/com.example/files/myproject"）
 * @return 8 字符的十六进制哈希字符串（如 "a3f7c901"）
 */
fun computeProjectHash(workingDirectory: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(workingDirectory.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }.take(8)
}

/**
 * 构建 JSONL 文件路径
 *
 * 路径格式：<homeDir>/.claude/projects/<projectHash>/<sessionId>.jsonl
 * 与真实 Claude Code 的 getSessionFilePath() 完全一致
 *
 * @param homeDir 用户主目录路径（Android 上通常是 context.filesDir.absolutePath）
 * @param workingDirectory 工作目录路径（用于计算 projectHash）
 * @param sessionId 会话唯一 ID（用于文件名）
 * @return JSONL 文件的完整绝对路径
 */
fun buildSessionFilePath(homeDir: String, workingDirectory: String, sessionId: String): String {
    val projectHash = computeProjectHash(workingDirectory)
    return "$homeDir/.claude/projects/$projectHash/$sessionId.jsonl"
}
