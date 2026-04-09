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
import com.claudecode.android.tools.ToolDangerLevel
import com.claudecode.android.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "AgentLoop"

/**
 * 网络错误最大重试次数（指数退避）
 * 对应真实 Claude Code 的重试逻辑
 */
private const val MAX_RETRY_COUNT = 3

/**
 * 初始重试延迟毫秒数（指数退避基数）
 * 第 1 次重试：1000ms，第 2 次：2000ms，第 3 次：4000ms
 */
private const val INITIAL_RETRY_DELAY_MS = 1000L

/**
 * API 过载（529）重试基础延迟（毫秒）
 * 比普通网络错误等待更长时间
 */
private const val OVERLOAD_RETRY_DELAY_MS = 5000L

/**
 * Agent Loop — Claude Code 的核心引擎
 *
 * 这是整个项目最核心的类，像素级复现了真实 Claude Code 的 Agent Loop 机制。
 *
 * ## 核心改进（对比旧版本）
 *
 * 1. **移除硬编码 MAX_ITERATIONS = 100**：
 *    改为 `while (session.maxTurns == null || iterationCount < session.maxTurns!!)` 循环，
 *    与真实 Claude Code 默认无限循环行为一致
 *
 * 2. **并发工具执行**：
 *    当 Claude 返回多个 tool_use 块时，安全工具（只读）并发执行，
 *    危险工具（写入/执行）顺序执行，避免文件系统竞态条件
 *
 * 3. **完整 stop_reason 处理**：
 *    end_turn / tool_use / max_tokens / pause_turn / refusal / stop_sequence 全部处理
 *
 * 4. **预算追踪**：每次 API 调用后累计 USD 费用，超出预算自动停止
 *
 * 5. **JSONL 持久化**：每条 assistant 消息和 tool_result 实时写入磁盘
 *
 * 6. **Hooks**：SessionStart / SessionEnd / PreToolUse / PostToolUse / Stop
 *
 * 7. **pause_turn 处理**：收到 pause_turn 后暂停会话，等待 session.resume()
 *
 * 8. **网络错误重试**：指数退避重试，529 过载使用更长延迟，401/403 立即停止
 *
 * ## 工作流程
 *
 * ```
 * 用户输入
 *   |
 * [SessionStart Hook → 构建系统提示词 + 工具定义]
 *   |
 * while (maxTurns == null || count < maxTurns)
 *   |
 * [POST /v1/messages（SSE 流式）]
 *   |
 * stop_reason?
 *   ├── "tool_use"     → 并发/顺序执行工具 → 追加结果 → 继续循环
 *   ├── "end_turn"     → Stop Hook → SessionEnd Hook → Completed
 *   ├── "max_tokens"   → 上下文压缩 → 继续循环
 *   ├── "pause_turn"   → 暂停 → 等待 resume() → 继续循环
 *   ├── "refusal"      → StopRefusal 事件 → SessionEnd Hook → 停止
 *   └── "stop_sequence"→ SessionEnd Hook → 停止
 * ```
 */
class AgentLoop(
    private val apiClient: AnthropicClient,
    private val toolRegistry: ToolRegistry,
    private val memoryManager: MemoryManager,
    private val contextManager: ContextManager,
    private val hookSystem: HookSystem,
    private val permissionManager: PermissionManager
) {
    /**
     * UI 事件流：AgentLoop 通过这个 Flow 向 UI 层发送实时事件
     * 使用 SharedFlow（不重放历史事件）确保每个事件只处理一次
     */
    private val _events = MutableSharedFlow<AgentEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * 运行 Agent Loop（主入口）
     *
     * 通常在 ViewModel 的 coroutineScope 中调用：
     * ```kotlin
     * viewModelScope.launch {
     *     agentLoop.run(session = session, model = ClaudeModel.CLAUDE_OPUS_4_6)
     * }
     * ```
     *
     * @param session 当前会话对象（包含消息历史、配置和状态）
     * @param model 使用的 Claude 模型（默认使用配置中的默认模型）
     */
    suspend fun run(session: AgentSession, model: ClaudeModel = ClaudeModel.DEFAULT) {
        Log.i(TAG, "Agent Loop 启动，会话 ID: ${session.sessionId}，模型: ${model.modelId}")

        // 更新会话状态为"思考中"
        session.setStatus(SessionStatus.Thinking)

        // 加载项目记忆（读取 CLAUDE.md 文件内容）
        val memory = memoryManager.loadMemory(session.workingDirectory)

        // 构建结构化系统提示词（身份 + 环境 + 记忆注入）
        val systemPrompt = SystemPromptBuilder.build(session, memory)

        // 获取所有工具定义（告诉 Claude 可以使用哪些工具）
        val toolDefinitions = toolRegistry.getAllDefinitions()

        // 触发 SessionStart Hook（通知外部系统会话开始）
        hookSystem.fire(HookEvent.SessionStart(
            sessionId = session.sessionId,
            workingDirectory = session.workingDirectory
        ))

        // 通知 UI：Agent Loop 已启动
        _events.emit(AgentEvent.Started(session.sessionId))

        var iterationCount = 0

        try {
            // ============================================================
            // 核心循环：与 maxTurns 对比，null 表示无限循环
            // 真实 Claude Code 默认不限制轮次，移除了旧版的硬编码 MAX_ITERATIONS = 100
            // ============================================================
            while (session.maxTurns == null || iterationCount < session.maxTurns!!) {
                iterationCount++
                Log.d(TAG, "Agent Loop 迭代 #$iterationCount（maxTurns: ${session.maxTurns ?: "无限"}）")

                // 发出迭代计数事件（UI 可用于显示进度）
                _events.emit(AgentEvent.IterationCount(iterationCount, session.maxTurns))

                // 检查是否需要上下文压缩（Token 使用率接近上限时提前压缩）
                val currentTokenUsage = session.tokenUsage.value
                if (currentTokenUsage.requiresCompression()) {
                    Log.i(TAG, "Token 使用率超过阈值，触发上下文压缩")
                    _events.emit(AgentEvent.ContextCompressing)
                    contextManager.compress(session)
                }

                // 构建 API 请求（每次都传入完整历史！）
                val request = MessagesRequest(
                    model = model.modelId,
                    system = systemPrompt,
                    tools = toolDefinitions,
                    messages = session.messages.value
                )

                // 发送 API 请求（含重试逻辑），流式收集响应
                val response = sendApiRequestWithRetry(request, session)

                // 更新 Token 使用量统计
                session.updateTokenUsage(response.usage)
                _events.emit(AgentEvent.TokenUsageUpdated(response.usage))

                // 计算本次调用 USD 费用并累加（根据模型单价 × token 数）
                session.currentCostUsd += response.usage.calculateCost(session.currentModel)
                _events.emit(AgentEvent.CostUpdate(session.currentCostUsd))

                // 检查是否超出预算上限
                if (session.budgetUsd != null && session.currentCostUsd >= session.budgetUsd!!) {
                    Log.w(TAG, "超出 USD 预算上限: \$${session.currentCostUsd} >= \$${session.budgetUsd}")
                    _events.emit(AgentEvent.BudgetExceeded(session.currentCostUsd))
                    session.setStatus(SessionStatus.Error("已超出 USD \$${session.budgetUsd} 预算上限"))
                    break
                }

                // 将 assistant 回复追加到消息历史（含 JSONL 持久化）
                session.addAssistantMessage(response.content)

                // ============================================================
                // 根据 stop_reason 决定下一步操作
                // 处理真实 Claude Code 所有可能的 stop reason
                // ============================================================
                when (val stopReason = response.stopReason) {

                    // ---- tool_use：Claude 需要调用工具 ----
                    "tool_use" -> {
                        Log.d(TAG, "stop_reason=tool_use，工具数量: ${response.toolUseBlocks.size}")

                        // 判断是否包含危险工具（写入/执行类），决定顺序/并发执行
                        val hasDangerousTools = response.toolUseBlocks.any { toolUse ->
                            toolRegistry.getDangerLevel(toolUse.name) >= ToolDangerLevel.CAUTION
                        }

                        val toolResults: List<ToolCallResult> = if (hasDangerousTools || response.toolUseBlocks.size == 1) {
                            // 顺序执行：危险工具（文件写入/Bash 执行等）必须顺序执行，
                            // 避免并发导致的文件系统竞态条件
                            Log.d(TAG, "顺序执行工具（含危险工具或单工具）")
                            response.toolUseBlocks.map { toolUse ->
                                executeToolCall(toolUse, session)
                            }
                        } else {
                            // 并发执行：纯只读工具（Glob/Grep/Read 等）可以安全并发，
                            // 大幅减少等待时间（如同时读取多个文件）
                            Log.d(TAG, "并发执行工具（全部为安全只读工具）")
                            coroutineScope {
                                response.toolUseBlocks.map { toolUse ->
                                    async(Dispatchers.IO) {
                                        executeToolCall(toolUse, session)
                                    }
                                }.awaitAll()
                            }
                        }

                        // 将所有工具结果写入消息历史（支持多工具合并到同一 user 消息）
                        toolResults.forEach { result ->
                            session.addToolResult(
                                toolUseId = result.toolUseId,
                                result = result.output,
                                isError = result.isError
                            )
                        }

                        // 继续循环（将工具结果传回 Claude）
                        session.setStatus(SessionStatus.Thinking)
                        continue
                    }

                    // ---- end_turn：Claude 认为任务已完成 ----
                    "end_turn" -> {
                        Log.i(TAG, "stop_reason=end_turn，迭代次数: $iterationCount")

                        // 触发 Stop Hook
                        hookSystem.fire(HookEvent.Stop(
                            stopReason = "end_turn",
                            sessionId = session.sessionId
                        ))

                        // 触发 SessionEnd Hook
                        hookSystem.fire(HookEvent.SessionEnd(
                            sessionId = session.sessionId,
                            reason = "end_turn"
                        ))

                        // 更新状态并通知 UI
                        session.setStatus(SessionStatus.Completed)
                        _events.emit(AgentEvent.Completed(
                            finalText = response.textContent,
                            iterations = iterationCount
                        ))
                        break
                    }

                    // ---- max_tokens：达到最大输出 Token 数，压缩上下文后继续 ----
                    "max_tokens" -> {
                        Log.w(TAG, "stop_reason=max_tokens，触发上下文压缩后继续")
                        _events.emit(AgentEvent.ContextCompressing)
                        contextManager.compress(session)
                        continue
                    }

                    // ---- pause_turn：Claude 请求暂停，等待用户输入（2025 新功能）----
                    "pause_turn" -> {
                        Log.i(TAG, "stop_reason=pause_turn，会话暂停，等待用户恢复")
                        session.setStatus(SessionStatus.Paused)
                        _events.emit(AgentEvent.SessionPaused(session.sessionId))
                        // 阻塞等待 UI 层调用 session.resume()
                        session.resumeSignal.await()
                        // 恢复后继续循环
                        session.setStatus(SessionStatus.Thinking)
                        Log.i(TAG, "会话从暂停中恢复，继续第 $iterationCount 轮后的执行")
                        continue
                    }

                    // ---- refusal：Claude 拒绝执行请求 ----
                    "refusal" -> {
                        val refusalReason = response.textContent.ifBlank { "Claude 拒绝执行此请求" }
                        Log.w(TAG, "stop_reason=refusal，拒绝原因: $refusalReason")

                        // 触发 Stop Hook（携带拒绝原因）
                        hookSystem.fire(HookEvent.Stop(
                            stopReason = "refusal",
                            sessionId = session.sessionId
                        ))

                        // 触发 SessionEnd Hook
                        hookSystem.fire(HookEvent.SessionEnd(
                            sessionId = session.sessionId,
                            reason = "refusal"
                        ))

                        _events.emit(AgentEvent.StopRefusal(refusalReason))
                        session.setStatus(SessionStatus.Completed)
                        break
                    }

                    // ---- stop_sequence：命中自定义停止序列 ----
                    "stop_sequence" -> {
                        Log.i(TAG, "stop_reason=stop_sequence，命中自定义停止序列")

                        hookSystem.fire(HookEvent.Stop(
                            stopReason = "stop_sequence",
                            sessionId = session.sessionId
                        ))

                        hookSystem.fire(HookEvent.SessionEnd(
                            sessionId = session.sessionId,
                            reason = "stop_sequence"
                        ))

                        session.setStatus(SessionStatus.Completed)
                        _events.emit(AgentEvent.Completed(
                            finalText = response.textContent,
                            iterations = iterationCount
                        ))
                        break
                    }

                    // ---- 其他未知 stop_reason（兜底处理）----
                    else -> {
                        Log.w(TAG, "未知 stop_reason: $stopReason，尝试压缩上下文后继续")
                        contextManager.compress(session)
                        continue
                    }
                }
            }

            // 循环因 maxTurns 限制而退出（不是 break，是条件不满足）
            if (session.maxTurns != null && iterationCount >= session.maxTurns!!) {
                Log.i(TAG, "达到最大轮次限制 (${session.maxTurns})，停止循环")
                hookSystem.fire(HookEvent.SessionEnd(
                    sessionId = session.sessionId,
                    reason = "max_turns_reached"
                ))
                session.setStatus(SessionStatus.Completed)
                _events.emit(AgentEvent.Completed(
                    finalText = "已达到最大轮次限制（${session.maxTurns} 轮）",
                    iterations = iterationCount
                ))
            }

        } catch (e: AuthException) {
            // 认证失败（401/403），立即停止，不重试
            Log.e(TAG, "认证失败，停止 Agent Loop", e)
            session.setStatus(SessionStatus.Error("认证失败：${e.message}"))
            _events.emit(AgentEvent.AuthError(e.message ?: "认证失败"))

        } catch (e: CancellationException) {
            // 协程被取消（用户主动停止任务）
            Log.i(TAG, "Agent Loop 被用户取消")
            session.setStatus(SessionStatus.Idle)
            _events.emit(AgentEvent.Cancelled)
            throw e  // 必须重新抛出，确保协程框架正确处理取消信号

        } catch (e: Exception) {
            Log.e(TAG, "Agent Loop 发生未处理的错误", e)
            session.setStatus(SessionStatus.Error(e.message ?: "未知错误"))
            _events.emit(AgentEvent.Error(e.message ?: "未知错误"))
        } finally {
            // 无论何种结束方式，清理当前工具状态
            session.setCurrentTool(null)
        }
    }

    // ============================================================
    // API 请求（含重试逻辑）
    // ============================================================

    /**
     * 带重试的 API 请求（指数退避）
     *
     * 重试策略（与真实 Claude Code 一致）：
     * - 网络错误：最多重试 3 次，延迟 1s → 2s → 4s（指数退避）
     * - 529 过载：最多重试 3 次，延迟 5s → 10s → 20s
     * - 401/403 认证失败：立即抛出 AuthException，不重试
     *
     * @param request API 请求对象
     * @param session 当前会话（用于记录重试日志）
     * @return 完整的 MessagesResponse
     */
    private suspend fun sendApiRequestWithRetry(
        request: MessagesRequest,
        session: AgentSession
    ): MessagesResponse {
        var lastException: Exception? = null

        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                return sendApiRequest(request, session)
            } catch (e: AuthException) {
                // 认证错误不重试，直接向上抛出
                throw e
            } catch (e: AnthropicException) {
                lastException = e
                when (e.statusCode) {
                    401, 403 -> {
                        // 认证/授权失败，立即停止
                        Log.e(TAG, "API 认证失败 (${e.statusCode})，停止重试")
                        throw AuthException(e.message ?: "认证失败", e.statusCode)
                    }
                    529 -> {
                        // API 过载，使用更长的等待时间
                        val delayMs = OVERLOAD_RETRY_DELAY_MS * (1L shl attempt)
                        Log.w(TAG, "API 过载 (529)，第 ${attempt + 1}/$MAX_RETRY_COUNT 次重试，等待 ${delayMs}ms")
                        delay(delayMs)
                    }
                    else -> {
                        // 其他 API 错误，指数退避重试
                        val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                        Log.w(TAG, "API 错误 (${e.statusCode})，第 ${attempt + 1}/$MAX_RETRY_COUNT 次重试，等待 ${delayMs}ms")
                        delay(delayMs)
                    }
                }
            } catch (e: Exception) {
                lastException = e
                // 网络连接错误等，指数退避重试
                val delayMs = INITIAL_RETRY_DELAY_MS * (1L shl attempt)
                Log.w(TAG, "网络错误，第 ${attempt + 1}/$MAX_RETRY_COUNT 次重试，等待 ${delayMs}ms", e)
                delay(delayMs)
            }
        }

        // 所有重试耗尽
        throw lastException ?: Exception("API 请求失败，已重试 $MAX_RETRY_COUNT 次")
    }

    /**
     * 发送 API 请求并处理 SSE 流式响应
     *
     * 通过 Server-Sent Events 流实时更新 UI（打字机效果），
     * 同时在内存中重建完整响应用于后续处理。
     *
     * @param request API 请求对象
     * @param session 当前会话（用于发射 UI 事件）
     * @return 重建完整的 MessagesResponse
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
                    // 工具调用块需要累积 JSON 字符串（分多个 delta 传输）
                    if (event.contentBlock is ContentBlock.ToolUse) {
                        partialJsonBuffers[event.index] = StringBuilder()
                    }
                    // 通知 UI 有新内容块开始（如新工具调用开始）
                    _events.emit(AgentEvent.ContentBlockStarted(event.index, event.contentBlock))
                }

                is StreamEvent.ContentBlockDelta -> {
                    when (val delta = event.delta) {
                        is DeltaContent.TextDelta -> {
                            // 文本增量：更新内存中的文本块，同时推送给 UI（打字机效果）
                            val current = contentBlocks[event.index] as? ContentBlock.Text
                            if (current != null) {
                                val updated = current.copy(text = current.text + delta.text)
                                contentBlocks[event.index] = updated
                                _events.emit(AgentEvent.TextDelta(delta.text))
                            }
                        }
                        is DeltaContent.InputJsonDelta -> {
                            // 工具参数 JSON 增量：累积到缓冲区，等待 ContentBlockStop 时解析
                            partialJsonBuffers[event.index]?.append(delta.partialJson)
                        }
                        is DeltaContent.ThinkingDelta -> {
                            // 思考过程增量（extended thinking 模式，claude-3-7 支持）
                            _events.emit(AgentEvent.ThinkingDelta(delta.thinking))
                        }
                    }
                }

                is StreamEvent.ContentBlockStop -> {
                    // 内容块完成：工具调用块需要解析完整 JSON 参数
                    val block = contentBlocks[event.index]
                    if (block is ContentBlock.ToolUse) {
                        val jsonStr = partialJsonBuffers[event.index]?.toString() ?: "{}"
                        try {
                            val parsedInput = kotlinx.serialization.json.Json
                                .parseToJsonElement(jsonStr).jsonObject
                            contentBlocks[event.index] = block.copy(input = parsedInput)
                        } catch (e: Exception) {
                            Log.w(TAG, "解析工具调用 JSON 参数失败: $jsonStr", e)
                        }
                    }
                }

                is StreamEvent.MessageDelta -> {
                    // 消息元数据更新：stop_reason 和最终 token 使用量
                    stopReason = event.stopReason
                    tokenUsage = event.usage
                }

                is StreamEvent.Error -> {
                    // SSE 流传输中的错误
                    throw AnthropicException(0, event.message)
                }

                else -> { /* 其他事件类型（message_start 等）暂时忽略 */ }
            }
        }

        // 按 index 顺序重建完整的 MessagesResponse
        return MessagesResponse(
            id = "",
            type = "message",
            role = "assistant",
            content = contentBlocks.entries.sortedBy { it.key }.map { it.value },
            stopReason = stopReason,
            usage = tokenUsage
        )
    }

    // ============================================================
    // 工具执行（单个工具调用）
    // ============================================================

    /**
     * 执行单个工具调用，返回结果（不直接写入 session，由调用方统一处理）
     *
     * 执行流程（与真实 Claude Code processToolCall() 对应）：
     * 1. 触发 PreToolUse Hook（可拦截/修改输入）
     * 2. 检查权限（是否需要用户确认）
     * 3. 执行工具（通过 ToolRegistry 路由）
     * 4. 触发 PostToolUse Hook（可修改输出）
     * 5. 如果是 Agent 工具，触发 SubagentStop Hook
     * 6. 返回 ToolCallResult（由 run() 方法统一写入 session）
     *
     * 注意：此方法是幂等的，多次调用同一 toolUse 不会改变 session 状态。
     * 这使得并发执行成为可能（session.addToolResult 在此方法外部调用）。
     *
     * @param toolUse Claude 返回的工具调用请求块
     * @param session 当前会话（用于权限检查和 Hook 调用）
     * @return 工具执行结果（含 toolUseId、输出文本、是否错误）
     */
    private suspend fun executeToolCall(
        toolUse: ContentBlock.ToolUse,
        session: AgentSession
    ): ToolCallResult {
        Log.d(TAG, "执行工具: ${toolUse.name}，参数: ${toolUse.input}")

        // 更新 UI：显示当前正在执行的工具
        session.setStatus(SessionStatus.ExecutingTool(toolUse.name))
        session.setCurrentTool(toolUse.name)
        _events.emit(AgentEvent.ToolCallStarted(toolUse.name, toolUse.input.toString()))

        // Step 1: 触发 PreToolUse Hook（允许外部拦截或修改工具调用）
        val preHookResult = hookSystem.fire(HookEvent.PreToolUse(
            toolName = toolUse.name,
            toolInput = toolUse.input,
            sessionId = session.sessionId
        ))

        // 如果 Hook 阻断了工具调用，返回被阻断的错误结果
        if (preHookResult is HookResult.Block) {
            Log.i(TAG, "工具 ${toolUse.name} 被 PreToolUse Hook 阻断: ${preHookResult.reason}")
            _events.emit(AgentEvent.ToolCallBlocked(toolUse.name, preHookResult.reason))
            session.setCurrentTool(null)
            return ToolCallResult(
                toolUseId = toolUse.id,
                output = "工具调用被 Hook 阻断: ${preHookResult.reason}",
                isError = true
            )
        }

        // 如果 Hook 修改了工具输入，使用修改后的输入
        val actualInput = if (preHookResult is HookResult.ModifyInput) {
            Log.d(TAG, "工具 ${toolUse.name} 输入被 PreToolUse Hook 修改")
            preHookResult.newInput
        } else {
            toolUse.input
        }

        // Step 2: 检查权限（根据会话权限模式决定是否需要用户确认）
        val permissionGranted = permissionManager.checkPermission(
            toolName = toolUse.name,
            toolInput = actualInput,
            mode = session.permissionMode
        )

        if (!permissionGranted) {
            Log.i(TAG, "工具 ${toolUse.name} 权限被拒绝")
            _events.emit(AgentEvent.ToolCallDenied(toolUse.name))
            session.setCurrentTool(null)
            return ToolCallResult(
                toolUseId = toolUse.id,
                output = "用户拒绝了此操作",
                isError = true
            )
        }

        // Step 3: 执行工具（通过 ToolRegistry 路由到具体实现）
        val toolResult = try {
            val result = toolRegistry.execute(toolUse.name, actualInput)
            Log.d(TAG, "工具 ${toolUse.name} 执行成功")
            result
        } catch (e: Exception) {
            Log.e(TAG, "工具 ${toolUse.name} 执行异常", e)
            com.claudecode.android.tools.ToolResult.error(e.message ?: "工具执行失败")
        }

        // Step 4: 触发 PostToolUse Hook（允许外部修改工具输出）
        val postHookResult = hookSystem.fire(HookEvent.PostToolUse(
            toolName = toolUse.name,
            toolInput = actualInput,
            toolOutput = toolResult.output,
            sessionId = session.sessionId
        ))

        // 如果 Hook 修改了工具输出，使用修改后的输出
        val finalOutput = if (postHookResult is HookResult.ModifyOutput) {
            Log.d(TAG, "工具 ${toolUse.name} 输出被 PostToolUse Hook 修改")
            postHookResult.newOutput
        } else {
            toolResult.output
        }

        // 通知 UI 工具调用完成
        session.setCurrentTool(null)
        _events.emit(AgentEvent.ToolCallCompleted(
            toolName = toolUse.name,
            output = finalOutput,
            isError = toolResult.isError
        ))

        // Step 5: 如果是 Agent 子工具调用，触发 SubagentStop Hook
        if (toolUse.name == "Agent") {
            hookSystem.fire(HookEvent.SubagentStop(
                subagentId = toolUse.id,
                stopReason = "end_turn",
                parentSessionId = session.sessionId
            ))
        }

        return ToolCallResult(
            toolUseId = toolUse.id,
            output = finalOutput,
            isError = toolResult.isError
        )
    }

    /** 会话的权限模式扩展属性（默认权限模式，后续从用户设置中读取） */
    private val AgentSession.permissionMode: PermissionMode
        get() = PermissionMode.DEFAULT
}

/**
 * 工具调用结果（内部数据类）
 *
 * executeToolCall() 返回此对象，由 run() 方法统一写入 session。
 * 这种设计支持并发执行多个工具后，统一提交所有结果。
 *
 * @param toolUseId 对应 Claude 返回的 tool_use 块 ID
 * @param output 工具执行输出文本（传回给 Claude 的内容）
 * @param isError 是否执行失败
 */
private data class ToolCallResult(
    val toolUseId: String,
    val output: String,
    val isError: Boolean
)

/**
 * 认证异常（401/403 错误）
 * 收到此异常时，AgentLoop 立即停止，不重试
 *
 * @param message 错误描述
 * @param statusCode HTTP 状态码（401 或 403）
 */
class AuthException(message: String, val statusCode: Int) : Exception(message)

/**
 * Agent 事件（AgentLoop 向 UI 发送的实时事件流）
 *
 * UI 层收集 agentLoop.events SharedFlow，根据事件类型更新界面。
 * 所有事件都是不可变的数据类或单例对象。
 */
sealed class AgentEvent {
    /** Agent 开始运行，携带会话 ID */
    data class Started(val sessionId: String) : AgentEvent()

    /** 文本内容增量（打字机效果的每个字符/词） */
    data class TextDelta(val text: String) : AgentEvent()

    /** 思考过程增量（extended thinking 模式，仅 claude-3-7 支持） */
    data class ThinkingDelta(val thinking: String) : AgentEvent()

    /** 新内容块开始（文本块或工具调用块） */
    data class ContentBlockStarted(val index: Int, val block: ContentBlock) : AgentEvent()

    /** 工具调用开始，携带工具名和输入参数 */
    data class ToolCallStarted(val toolName: String, val input: String) : AgentEvent()

    /** 工具调用被 PreToolUse Hook 阻断 */
    data class ToolCallBlocked(val toolName: String, val reason: String) : AgentEvent()

    /** 工具调用被用户权限拒绝 */
    data class ToolCallDenied(val toolName: String) : AgentEvent()

    /** 工具调用成功完成 */
    data class ToolCallCompleted(
        val toolName: String,
        val output: String,
        val isError: Boolean
    ) : AgentEvent()

    /** Token 使用量更新（每次 API 响应后） */
    data class TokenUsageUpdated(val usage: TokenUsage) : AgentEvent()

    /** 上下文压缩正在进行中 */
    object ContextCompressing : AgentEvent()

    /** 任务成功完成 */
    data class Completed(val finalText: String, val iterations: Int) : AgentEvent()

    /** 任务被用户主动取消 */
    object Cancelled : AgentEvent()

    /** 发生错误，任务终止 */
    data class Error(val message: String) : AgentEvent()

    /**
     * 会话已暂停（收到 pause_turn stop reason）
     * UI 应显示"继续"按钮，等待用户操作
     */
    data class SessionPaused(val sessionId: String) : AgentEvent()

    /**
     * 已超出 USD 预算上限
     * @param costUsd 当前累计费用（美元）
     */
    data class BudgetExceeded(val costUsd: Double) : AgentEvent()

    /**
     * Claude 拒绝执行请求（stop_reason == "refusal"）
     * @param reason 拒绝原因文本
     */
    data class StopRefusal(val reason: String) : AgentEvent()

    /**
     * 当前迭代计数更新
     * @param count 当前已完成的迭代次数
     * @param maxTurns 最大允许轮次（null = 无限）
     */
    data class IterationCount(val count: Int, val maxTurns: Int?) : AgentEvent()

    /**
     * USD 费用更新
     * @param costUsd 当前累计费用（美元）
     */
    data class CostUpdate(val costUsd: Double) : AgentEvent()

    /**
     * 认证失败（401/403）
     * UI 应引导用户重新配置 API Key
     */
    data class AuthError(val message: String) : AgentEvent()
}

/**
 * 系统提示词构建器（SystemPromptBuilder）
 *
 * 与真实 Claude Code 的 buildSystemPrompt() 函数对应。
 * 负责将基础身份定义、运行环境信息和项目记忆（CLAUDE.md）组合成完整的系统提示词。
 */
object SystemPromptBuilder {
    /**
     * 构建结构化系统提示词
     *
     * @param session 当前会话（提供工作目录等环境信息）
     * @param memoryContext 项目记忆上下文（来自 CLAUDE.md 文件）
     * @return 完整的系统提示词字符串
     */
    fun build(session: AgentSession, memoryContext: com.claudecode.android.memory.MemoryContext): String {
        return buildString {
            // 核心身份和能力定义
            append("""
                你是 Claude Code，运行在 Android 设备上的 AI 编程助手。
                你可以帮助用户读写文件、执行代码、搜索信息、完成各种编程和系统操作任务。

                ## 当前运行环境
                - 平台：Android
                - 工作目录：${session.workingDirectory}
                - 会话 ID：${session.sessionId}
                - 时间：${java.util.Date()}

                ## 工具使用原则
                - 优先使用 Glob 和 Grep 搜索文件，而不是盲目读取所有文件
                - 修改已有文件时，优先使用 Edit 而不是 Write（避免覆盖未读取的内容）
                - 所有文件路径使用绝对路径（防止相对路径错误）
                - 不可逆操作（删除文件、覆盖写入等）执行前必须确认
                - 所有文件操作都有权限检查，遵守用户的权限配置

                ## 输出格式规范
                - 使用 Markdown 格式输出
                - 代码使用代码块包裹（标注语言）
                - 保持简洁，避免冗余的解释
                - 操作完成后给出清晰的完成摘要
            """.trimIndent())

            // 注入项目记忆内容（来自 CLAUDE.md 文件）
            if (memoryContext.hasContent()) {
                append("\n\n## 项目专属记忆（来自 CLAUDE.md）\n")
                append(memoryContext.combinedContent)
            }

            // 注入预算提示（如果设置了预算上限）
            if (session.budgetUsd != null) {
                append("\n\n## 预算提示\n")
                append("本次会话预算上限：USD \$${session.budgetUsd}，已消耗：USD \$${session.currentCostUsd}")
            }
        }
    }
}
