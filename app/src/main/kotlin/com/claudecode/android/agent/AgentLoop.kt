package com.claudecode.android.agent

import android.util.Log
import com.claudecode.android.api.*
import com.claudecode.android.context.ContextManager
import com.claudecode.android.hooks.HookEvent
import com.claudecode.android.hooks.HookResult
import com.claudecode.android.hooks.HookSystem
import com.claudecode.android.memory.MemoryManager
import com.claudecode.android.session.PermissionManager
import com.claudecode.android.session.PermissionMode
import com.claudecode.android.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "AgentLoop"

/** Agent Loop 最大迭代次数（防止无限循环） */
private const val MAX_ITERATIONS = 100

/**
 * Agent Loop — Claude Code 的核心引擎
 *
 * 这是整个项目最核心的类，像素级复现了 Claude Code 的 Agent Loop 机制。
 *
 * ## 工作原理
 *
 * Agent Loop 是一个"思考-行动"循环：
 * 1. 将用户指令 + 完整历史 + 工具定义发送给 Claude
 * 2. Claude 决定是否需要调用工具：
 *    - 需要工具 -> 执行工具 -> 将结果传回 Claude -> 继续循环
 *    - 不需要工具 -> 输出最终回答 -> 循环结束
 *
 * ## 完整流程图
 *
 * ```
 * 用户输入
 *   |
 * [构建请求：系统提示词 + 工具定义 + 消息历史]
 *   |
 * POST /v1/messages
 *   |
 * +---------------------------------------------+
 * | stopReason == "tool_use"?                    |
 * |  +- YES:                                     |
 * |  |   触发 PreToolUse Hook                    |
 * |  |   执行工具（Read/Write/...）              |
 * |  |   触发 PostToolUse Hook                   |
 * |  |   将工具结果追加到历史                     |
 * |  |   ^ 继续循环                              |
 * |  +- NO（end_turn）:                          |
 * |      触发 Stop Hook                          |
 * |      返回最终结果                             |
 * +---------------------------------------------+
 * ```
 *
 * ## 与原版 Claude Code 的对应关系
 * - `sendApiRequest()` <-> Claude Code 的 `query()` 函数
 * - `executeToolCall()` <-> Claude Code 的 `processToolCall()` 函数
 * - `MAX_ITERATIONS` <-> Claude Code 的 `maxIterations` 配置
 */
class AgentLoop(
    private val apiClient: AnthropicClient,
    private val toolRegistry: ToolRegistry,
    private val memoryManager: MemoryManager,
    private val contextManager: ContextManager,
    private val hookSystem: HookSystem,
    private val permissionManager: PermissionManager
) {
    // UI 事件流：Agent Loop 通过这个 Flow 向 UI 层发送实时事件
    private val _events = MutableSharedFlow<AgentEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * 运行 Agent Loop
     *
     * 这是 AgentLoop 的主入口，通常在 ViewModel 中调用：
     * ```kotlin
     * viewModelScope.launch {
     *     agentLoop.run(session = session, model = ClaudeModel.CLAUDE_OPUS_4_6)
     * }
     * ```
     *
     * @param session 当前会话（包含用户输入和历史消息）
     * @param model 使用的 Claude 模型
     */
    suspend fun run(session: AgentSession, model: ClaudeModel = ClaudeModel.DEFAULT) {
        Log.i(TAG, "Agent Loop 开始，会话 ID: ${session.sessionId}")

        // 更新会话状态为"思考中"
        session.setStatus(SessionStatus.Thinking)

        // 加载记忆（CLAUDE.md 文件）
        val memory = memoryManager.loadMemory(session.workingDirectory)

        // 构建系统提示词（基础提示词 + 记忆内容注入）
        val systemPrompt = buildSystemPrompt(session, memory)

        // 获取所有工具定义（传给 API 告诉 Claude 可以用哪些工具）
        val toolDefinitions = toolRegistry.getAllDefinitions()

        // 发送事件通知 UI：Agent Loop 已启动
        _events.emit(AgentEvent.Started(session.sessionId))

        var iterationCount = 0

        try {
            // ============================================================
            // 核心循环：不断与 Claude 交互，直到任务完成
            // ============================================================
            while (iterationCount < MAX_ITERATIONS) {
                iterationCount++
                Log.d(TAG, "Loop 迭代 #$iterationCount")

                // 检查是否需要上下文压缩
                val currentTokenUsage = session.tokenUsage.value
                if (currentTokenUsage.requiresCompression()) {
                    Log.i(TAG, "Token 使用率超过 90%，触发上下文压缩")
                    _events.emit(AgentEvent.ContextCompressing)
                    contextManager.compress(session)
                }

                // 构建 API 请求
                val request = MessagesRequest(
                    model = model.modelId,
                    system = systemPrompt,
                    tools = toolDefinitions,
                    messages = session.messages.value  // 传完整历史！
                )

                // 发送 API 请求（流式）并收集响应
                val response = sendApiRequest(request, session)

                // 更新 Token 使用量
                session.updateTokenUsage(response.usage)
                _events.emit(AgentEvent.TokenUsageUpdated(response.usage))

                // 将 assistant 回复追加到消息历史
                session.addAssistantMessage(response.content)

                when {
                    // Case 1: Claude 需要调用工具
                    response.requiresToolExecution -> {
                        Log.d(TAG, "Claude 请求调用 ${response.toolUseBlocks.size} 个工具")

                        // 处理所有工具调用请求（可能同时请求多个）
                        for (toolUse in response.toolUseBlocks) {
                            executeToolCall(toolUse, session)
                        }

                        // 继续循环（将工具结果传回 Claude）
                        session.setStatus(SessionStatus.Thinking)
                        continue
                    }

                    // Case 2: Claude 认为任务已完成
                    response.isFinished -> {
                        Log.i(TAG, "Claude 完成任务，迭代次数: $iterationCount")

                        // 触发 Stop Hook
                        hookSystem.fire(HookEvent.Stop(
                            stopReason = "end_turn",
                            sessionId = session.sessionId
                        ))

                        // 更新会话状态为完成
                        session.setStatus(SessionStatus.Completed)
                        _events.emit(AgentEvent.Completed(
                            finalText = response.textContent,
                            iterations = iterationCount
                        ))
                        break
                    }

                    // Case 3: 达到最大 Token 数
                    else -> {
                        Log.w(TAG, "达到最大 Token 数，尝试压缩后继续")
                        contextManager.compress(session)
                        continue
                    }
                }
            }

            // 超过最大迭代次数（通常不应该发生）
            if (iterationCount >= MAX_ITERATIONS) {
                Log.e(TAG, "超过最大迭代次数 ($MAX_ITERATIONS)，强制停止")
                session.setStatus(SessionStatus.Error("超过最大执行次数"))
                _events.emit(AgentEvent.Error("任务执行步骤过多，已自动停止（最大 $MAX_ITERATIONS 步）"))
            }

        } catch (e: CancellationException) {
            // 协程被取消（用户主动停止任务）
            Log.i(TAG, "Agent Loop 被用户取消")
            session.setStatus(SessionStatus.Idle)
            _events.emit(AgentEvent.Cancelled)
            throw e  // 重新抛出，让协程框架正确处理取消

        } catch (e: Exception) {
            Log.e(TAG, "Agent Loop 发生错误", e)
            session.setStatus(SessionStatus.Error(e.message ?: "未知错误"))
            _events.emit(AgentEvent.Error(e.message ?: "未知错误"))
        }
    }

    /**
     * 发送 API 请求并处理流式响应
     *
     * 通过 SSE 流实时更新 UI（用户能看到 Claude 逐字输出），
     * 同时收集完整响应用于后续处理。
     */
    private suspend fun sendApiRequest(
        request: MessagesRequest,
        session: AgentSession
    ): MessagesResponse {
        // 收集流式输出，重建完整响应
        val contentBlocks = mutableMapOf<Int, ContentBlock>()
        val partialJsonBuffers = mutableMapOf<Int, StringBuilder>()
        var stopReason: String? = null
        var tokenUsage = TokenUsage(0, 0)

        apiClient.sendMessageStream(request).collect { event ->
            when (event) {
                is StreamEvent.ContentBlockStart -> {
                    contentBlocks[event.index] = event.contentBlock
                    // 如果是工具调用块，初始化 JSON 缓冲区
                    if (event.contentBlock is ContentBlock.ToolUse) {
                        partialJsonBuffers[event.index] = StringBuilder()
                    }
                    // 通知 UI 有新内容块开始
                    _events.emit(AgentEvent.ContentBlockStarted(event.index, event.contentBlock))
                }

                is StreamEvent.ContentBlockDelta -> {
                    when (val delta = event.delta) {
                        is DeltaContent.TextDelta -> {
                            // 文本增量：更新当前文本块并通知 UI（实现打字机效果）
                            val current = contentBlocks[event.index] as? ContentBlock.Text
                            if (current != null) {
                                val updated = current.copy(text = current.text + delta.text)
                                contentBlocks[event.index] = updated
                                _events.emit(AgentEvent.TextDelta(delta.text))
                            }
                        }
                        is DeltaContent.InputJsonDelta -> {
                            // 工具调用参数增量：追加到 JSON 缓冲区
                            partialJsonBuffers[event.index]?.append(delta.partialJson)
                        }
                        is DeltaContent.ThinkingDelta -> {
                            // 思考过程增量（extended thinking 模式）
                            _events.emit(AgentEvent.ThinkingDelta(delta.thinking))
                        }
                    }
                }

                is StreamEvent.ContentBlockStop -> {
                    // 内容块完成：如果是工具调用，解析完整的 JSON 参数
                    val block = contentBlocks[event.index]
                    if (block is ContentBlock.ToolUse) {
                        val jsonStr = partialJsonBuffers[event.index]?.toString() ?: "{}"
                        try {
                            val parsedInput = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
                            contentBlocks[event.index] = block.copy(input = parsedInput)
                        } catch (e: Exception) {
                            Log.w(TAG, "解析工具调用 JSON 参数失败: $jsonStr", e)
                        }
                    }
                }

                is StreamEvent.MessageDelta -> {
                    stopReason = event.stopReason
                    tokenUsage = event.usage
                }

                is StreamEvent.Error -> {
                    throw AnthropicException(0, event.message)
                }

                else -> { /* 忽略其他事件 */ }
            }
        }

        // 重建完整的 MessagesResponse
        return MessagesResponse(
            id = "",
            type = "message",
            role = "assistant",
            content = contentBlocks.entries.sortedBy { it.key }.map { it.value },
            stopReason = stopReason,
            usage = tokenUsage
        )
    }

    /**
     * 执行单个工具调用
     *
     * 执行流程：
     * 1. 触发 PreToolUse Hook（允许拦截/修改工具调用）
     * 2. 检查权限（是否需要用户确认）
     * 3. 执行工具
     * 4. 触发 PostToolUse Hook（允许修改工具结果）
     * 5. 将结果追加到消息历史
     */
    private suspend fun executeToolCall(toolUse: ContentBlock.ToolUse, session: AgentSession) {
        Log.d(TAG, "执行工具: ${toolUse.name}，参数: ${toolUse.input}")

        // 更新 UI：显示当前正在执行的工具
        session.setStatus(SessionStatus.ExecutingTool(toolUse.name))
        session.setCurrentTool(toolUse.name)
        _events.emit(AgentEvent.ToolCallStarted(toolUse.name, toolUse.input.toString()))

        // Step 1: 触发 PreToolUse Hook
        val preHookResult = hookSystem.fire(HookEvent.PreToolUse(
            toolName = toolUse.name,
            toolInput = toolUse.input,
            sessionId = session.sessionId
        ))

        // 如果 Hook 阻断了工具调用，直接返回被阻断的结果
        if (preHookResult is HookResult.Block) {
            Log.i(TAG, "工具 ${toolUse.name} 被 PreToolUse Hook 阻断: ${preHookResult.reason}")
            session.addToolResult(
                toolUseId = toolUse.id,
                result = "工具调用被 Hook 阻断: ${preHookResult.reason}",
                isError = true
            )
            _events.emit(AgentEvent.ToolCallBlocked(toolUse.name, preHookResult.reason))
            return
        }

        // 如果 Hook 修改了工具输入，使用修改后的输入
        val actualInput = if (preHookResult is HookResult.ModifyInput) {
            Log.d(TAG, "工具输入被 Hook 修改")
            preHookResult.newInput
        } else {
            toolUse.input
        }

        // Step 2: 检查权限
        val permissionGranted = permissionManager.checkPermission(
            toolName = toolUse.name,
            toolInput = actualInput,
            mode = session.permissionMode
        )

        if (!permissionGranted) {
            Log.i(TAG, "工具 ${toolUse.name} 权限被拒绝")
            session.addToolResult(
                toolUseId = toolUse.id,
                result = "用户拒绝了此操作",
                isError = true
            )
            _events.emit(AgentEvent.ToolCallDenied(toolUse.name))
            return
        }

        // Step 3: 执行工具
        val toolResult = try {
            val result = toolRegistry.execute(toolUse.name, actualInput)
            Log.d(TAG, "工具 ${toolUse.name} 执行成功")
            result
        } catch (e: Exception) {
            Log.e(TAG, "工具 ${toolUse.name} 执行失败", e)
            com.claudecode.android.tools.ToolResult.error(e.message ?: "工具执行失败")
        }

        // Step 4: 触发 PostToolUse Hook
        val postHookResult = hookSystem.fire(HookEvent.PostToolUse(
            toolName = toolUse.name,
            toolInput = actualInput,
            toolOutput = toolResult.output,
            sessionId = session.sessionId
        ))

        // 如果 Hook 修改了工具输出，使用修改后的输出
        val finalOutput = if (postHookResult is HookResult.ModifyOutput) {
            Log.d(TAG, "工具输出被 PostToolUse Hook 修改")
            postHookResult.newOutput
        } else {
            toolResult.output
        }

        // Step 5: 将工具结果追加到消息历史
        session.addToolResult(
            toolUseId = toolUse.id,
            result = finalOutput,
            isError = toolResult.isError
        )

        // 通知 UI 工具调用完成
        session.setCurrentTool(null)
        _events.emit(AgentEvent.ToolCallCompleted(
            toolName = toolUse.name,
            output = finalOutput,
            isError = toolResult.isError
        ))

        // 同时触发 SubagentStop Hook（如果这是一个子 Agent 的工具调用）
        if (toolUse.name == "Agent") {
            hookSystem.fire(HookEvent.SubagentStop(
                subagentId = toolUse.id,
                stopReason = "end_turn",
                parentSessionId = session.sessionId
            ))
        }
    }

    /**
     * 构建系统提示词
     *
     * 系统提示词定义了 Claude 的：
     * - 角色身份（"你是 Claude Code..."）
     * - 运行环境（Android、工作目录、可用工具等）
     * - 行为规则（何时使用哪个工具、安全限制等）
     * - 记忆内容（CLAUDE.md 文件内容）
     */
    private fun buildSystemPrompt(session: AgentSession, memory: com.claudecode.android.memory.MemoryContext): String {
        return buildString {
            // 基础身份定义
            append("""
                你是 Claude Code，运行在 Android 设备上的 AI 编程助手。
                你可以帮助用户读写文件、执行代码、搜索信息、完成各种编程任务。
                
                ## 当前环境
                - 平台：Android
                - 工作目录：${session.workingDirectory}
                - 时间：${java.util.Date()}
                
                ## 工具使用原则
                - 优先使用 Glob 和 Grep 搜索文件，而不是盲目读取所有文件
                - 修改已有文件时，优先使用 Edit 而不是 Write（避免覆盖未读取的内容）
                - 所有文件路径使用绝对路径（防止相对路径错误）
                - 不可逆操作（删除文件等）执行前要再次确认
                
                ## 输出格式
                - 使用 Markdown 格式
                - 代码用代码块包裹
                - 保持简洁，避免不必要的解释
            """.trimIndent())

            // 注入记忆内容（CLAUDE.md）
            if (memory.hasContent()) {
                append("\n\n## 项目记忆（CLAUDE.md）\n")
                append(memory.combinedContent)
            }
        }
    }

    /** 会话的权限模式扩展属性 */
    private val AgentSession.permissionMode: PermissionMode
        get() = PermissionMode.DEFAULT  // 默认权限模式，后续从设置中读取
}

/**
 * Agent 事件（AgentLoop 向 UI 发送的实时更新）
 *
 * UI 层通过收集这些事件来更新界面，实现实时显示 Claude 的思考和操作过程。
 */
sealed class AgentEvent {
    /** Agent 开始运行 */
    data class Started(val sessionId: String) : AgentEvent()

    /** 文本增量（打字机效果） */
    data class TextDelta(val text: String) : AgentEvent()

    /** 思考过程增量 */
    data class ThinkingDelta(val thinking: String) : AgentEvent()

    /** 新内容块开始 */
    data class ContentBlockStarted(val index: Int, val block: ContentBlock) : AgentEvent()

    /** 工具调用开始 */
    data class ToolCallStarted(val toolName: String, val input: String) : AgentEvent()

    /** 工具调用被 Hook 阻断 */
    data class ToolCallBlocked(val toolName: String, val reason: String) : AgentEvent()

    /** 工具调用被用户拒绝 */
    data class ToolCallDenied(val toolName: String) : AgentEvent()

    /** 工具调用完成 */
    data class ToolCallCompleted(val toolName: String, val output: String, val isError: Boolean) : AgentEvent()

    /** Token 使用量更新 */
    data class TokenUsageUpdated(val usage: TokenUsage) : AgentEvent()

    /** 上下文压缩中 */
    object ContextCompressing : AgentEvent()

    /** 任务完成 */
    data class Completed(val finalText: String, val iterations: Int) : AgentEvent()

    /** 任务被用户取消 */
    object Cancelled : AgentEvent()

    /** 发生错误 */
    data class Error(val message: String) : AgentEvent()
}
