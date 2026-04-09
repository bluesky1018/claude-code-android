package com.claudecode.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudecode.android.agent.AgentEvent
import com.claudecode.android.agent.AgentLoop
import com.claudecode.android.agent.AgentSession
import com.claudecode.android.mcp.McpClient
import com.claudecode.android.session.PermissionManager
import com.claudecode.android.storage.SessionRepository
import com.claudecode.android.storage.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 对话界面 ViewModel
 * 连接 AgentLoop 与 UI，处理所有 AgentEvent
 */
class ChatViewModel(
    private val agentLoop: AgentLoop,
    private val permissionManager: PermissionManager,
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val mcpClient: McpClient
) : ViewModel() {

    // ==================== UI 状态 ====================

    data class ChatUiState(
        val messages: List<UiMessage> = emptyList(),
        val isRunning: Boolean = false,
        val isWaitingForPermission: Boolean = false,
        val currentTool: String? = null,
        val tokenCount: Int = 0,
        val tokenLimit: Int = 200_000,
        val costUsd: Double = 0.0,
        val iterationCount: Int = 0,
        val errorMessage: String? = null,
        val sessionId: String? = null,
        val isPaused: Boolean = false,           // pause_turn 状态
        val budgetExceeded: Boolean = false,
        val pendingPermissions: List<PermissionManager.PermissionRequest> = emptyList()
    )

    sealed class UiMessage {
        abstract val id: String
        abstract val timestamp: Long

        data class UserMessage(
            override val id: String = UUID.randomUUID().toString(),
            override val timestamp: Long = System.currentTimeMillis(),
            val text: String
        ) : UiMessage()

        data class AssistantText(
            override val id: String = UUID.randomUUID().toString(),
            override val timestamp: Long = System.currentTimeMillis(),
            val text: String,
            val isStreaming: Boolean = false
        ) : UiMessage()

        data class ThinkingBlock(
            override val id: String = UUID.randomUUID().toString(),
            override val timestamp: Long = System.currentTimeMillis(),
            val thinking: String,
            val isCollapsed: Boolean = true  // 默认折叠
        ) : UiMessage()

        data class ToolCallCard(
            override val id: String = UUID.randomUUID().toString(),
            override val timestamp: Long = System.currentTimeMillis(),
            val toolName: String,
            val toolInput: String,
            val toolOutput: String? = null,
            val isRunning: Boolean = true,
            val isError: Boolean = false,
            val durationMs: Long? = null
        ) : UiMessage()

        data class SubAgentCard(
            override val id: String = UUID.randomUUID().toString(),
            override val timestamp: Long = System.currentTimeMillis(),
            val subagentId: String,
            val description: String,
            val isRunning: Boolean = true,
            val result: String? = null
        ) : UiMessage()

        data class ErrorMessage(
            override val id: String = UUID.randomUUID().toString(),
            override val timestamp: Long = System.currentTimeMillis(),
            val error: String
        ) : UiMessage()

        data class SystemNotification(
            override val id: String = UUID.randomUUID().toString(),
            override val timestamp: Long = System.currentTimeMillis(),
            val message: String,
            val type: NotificationType = NotificationType.INFO
        ) : UiMessage()

        enum class NotificationType { INFO, WARNING, ERROR, SUCCESS }
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var agentJob: Job? = null
    private var currentSession: AgentSession? = null
    private var streamingAssistantMessageId: String? = null
    private var streamingThinkingMessageId: String? = null

    // ==================== 初始化 ====================

    init {
        // 监听权限请求
        viewModelScope.launch {
            permissionManager.pendingRequests.collect { requests ->
                _uiState.update { it.copy(
                    pendingPermissions = requests,
                    isWaitingForPermission = requests.isNotEmpty()
                )}
            }
        }
    }

    // ==================== 发送消息 ====================

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isRunning) return

        // 添加用户消息到 UI
        appendMessage(UiMessage.UserMessage(text = text))
        _uiState.update { it.copy(isRunning = true, errorMessage = null) }

        val session = currentSession ?: createNewSession()
        currentSession = session

        agentJob = viewModelScope.launch {
            try {
                agentLoop.events.collect { event ->
                    handleAgentEvent(event)
                }
                agentLoop.run(
                    userMessage = text,
                    session = session
                )
            } catch (e: Exception) {
                appendMessage(UiMessage.ErrorMessage(error = e.message ?: "Unknown error"))
                _uiState.update { it.copy(isRunning = false, errorMessage = e.message) }
            }
        }
    }

    /** 恢复暂停的会话（pause_turn 后） */
    fun resumeSession() {
        currentSession?.resume()
        _uiState.update { it.copy(isPaused = false) }
    }

    // ==================== 事件处理 ====================

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                val msgId = streamingAssistantMessageId ?: run {
                    val id = UUID.randomUUID().toString()
                    streamingAssistantMessageId = id
                    appendMessage(UiMessage.AssistantText(id = id, text = "", isStreaming = true))
                    id
                }
                updateMessage(msgId) { msg ->
                    (msg as? UiMessage.AssistantText)?.copy(
                        text = msg.text + event.text,
                        isStreaming = true
                    ) ?: msg
                }
            }

            is AgentEvent.ThinkingDelta -> {
                val thinkId = streamingThinkingMessageId ?: run {
                    val id = UUID.randomUUID().toString()
                    streamingThinkingMessageId = id
                    appendMessage(UiMessage.ThinkingBlock(id = id, thinking = "", isCollapsed = true))
                    id
                }
                updateMessage(thinkId) { msg ->
                    (msg as? UiMessage.ThinkingBlock)?.copy(thinking = msg.thinking + event.thinking) ?: msg
                }
            }

            is AgentEvent.MessageComplete -> {
                streamingAssistantMessageId?.let { msgId ->
                    updateMessage(msgId) { msg ->
                        (msg as? UiMessage.AssistantText)?.copy(isStreaming = false) ?: msg
                    }
                }
                streamingAssistantMessageId = null
                streamingThinkingMessageId = null
            }

            is AgentEvent.ToolCallStarted -> {
                appendMessage(UiMessage.ToolCallCard(
                    id = event.toolUseId,
                    toolName = event.toolName,
                    toolInput = event.toolInput.toString(),
                    isRunning = true
                ))
                _uiState.update { it.copy(currentTool = event.toolName) }
            }

            is AgentEvent.ToolCallCompleted -> {
                updateMessage(event.toolUseId) { msg ->
                    (msg as? UiMessage.ToolCallCard)?.copy(
                        toolOutput = event.result,
                        isRunning = false,
                        isError = event.isError,
                        durationMs = event.durationMs
                    ) ?: msg
                }
                _uiState.update { it.copy(currentTool = null) }
            }

            is AgentEvent.TokenUsageUpdated -> {
                _uiState.update { it.copy(
                    tokenCount = event.inputTokens + event.outputTokens
                )}
            }

            is AgentEvent.CostUpdate -> {
                _uiState.update { it.copy(costUsd = event.costUsd) }
            }

            is AgentEvent.IterationCount -> {
                _uiState.update { it.copy(iterationCount = event.count) }
            }

            is AgentEvent.SessionPaused -> {
                _uiState.update { it.copy(isPaused = true, isRunning = false) }
                appendMessage(UiMessage.SystemNotification(
                    message = "会话已暂停，等待用户确认后继续...",
                    type = UiMessage.NotificationType.WARNING
                ))
            }

            is AgentEvent.BudgetExceeded -> {
                _uiState.update { it.copy(budgetExceeded = true, isRunning = false) }
                appendMessage(UiMessage.SystemNotification(
                    message = "已达到成本上限 \$${String.format("%.4f", event.costUsd)}，任务停止。",
                    type = UiMessage.NotificationType.ERROR
                ))
            }

            is AgentEvent.StopRefusal -> {
                appendMessage(UiMessage.SystemNotification(
                    message = "Claude 拒绝了该请求：${event.reason}",
                    type = UiMessage.NotificationType.WARNING
                ))
                _uiState.update { it.copy(isRunning = false) }
            }

            is AgentEvent.AgentCompleted -> {
                _uiState.update { it.copy(isRunning = false, currentTool = null) }
            }

            is AgentEvent.AgentError -> {
                _uiState.update { it.copy(isRunning = false, errorMessage = event.error) }
                appendMessage(UiMessage.ErrorMessage(error = event.error))
            }

            else -> { /* 其他事件忽略 */ }
        }
    }

    // ==================== 权限管理 ====================

    fun approvePermission(requestId: String) = permissionManager.approvePermission(requestId)
    fun denyPermission(requestId: String) = permissionManager.denyPermission(requestId)

    // ==================== 会话管理 ====================

    fun stopTask() {
        agentJob?.cancel()
        _uiState.update { it.copy(isRunning = false, currentTool = null) }
    }

    fun clearMessages() {
        _uiState.update { ChatUiState() }
        currentSession = null
        streamingAssistantMessageId = null
    }

    fun forkSession() {
        currentSession?.let { session ->
            viewModelScope.launch {
                val forked = sessionRepository.forkSession(session.sessionId)
                if (forked != null) {
                    appendMessage(UiMessage.SystemNotification(
                        message = "会话已 Fork（ID: ${forked.sessionId.take(8)}...）",
                        type = UiMessage.NotificationType.SUCCESS
                    ))
                }
            }
        }
    }

    private fun createNewSession(): AgentSession {
        val workingDir = settingsRepository.getWorkingDirectory()
        val mode = settingsRepository.getPermissionMode()
        val permMode = try {
            PermissionManager.PermissionMode.valueOf(mode.uppercase().replace("-", "_"))
        } catch (e: Exception) {
            PermissionManager.PermissionMode.DEFAULT
        }

        return AgentSession(
            workingDirectory = workingDir,
            permissionMode = permMode,
            maxTurns = settingsRepository.getMaxTurns(),
            budgetUsd = settingsRepository.getMaxBudgetUsd()
        ).also { session ->
            _uiState.update { it.copy(sessionId = session.sessionId) }
            viewModelScope.launch {
                sessionRepository.createSession(
                    sessionId = session.sessionId,
                    workingDirectory = workingDir,
                    model = settingsRepository.getModel()
                )
            }
        }
    }

    // ==================== 消息操作辅助 ====================

    private fun appendMessage(message: UiMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages + message)
        }
    }

    private fun updateMessage(id: String, transform: (UiMessage) -> UiMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }
}
