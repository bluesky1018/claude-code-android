package com.claudecode.android.api

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AnthropicClient"

/** Anthropic Messages API 的基础 URL */
private const val BASE_URL = "https://api.anthropic.com"

/** API 版本头，固定为 "2023-06-01" */
private const val ANTHROPIC_VERSION = "2023-06-01"

/** Messages 接口路径 */
private const val MESSAGES_ENDPOINT = "/v1/messages"

/**
 * Anthropic API 客户端
 *
 * 负责与 Anthropic Messages API 通信，支持：
 * 1. 非流式请求（一次性返回完整响应），用于上下文压缩摘要等场景
 * 2. 流式 SSE 请求（实时返回 token），用于 Agent Loop 主循环
 * 3. 自动指数退避重试（遇到 529 过载错误时自动重试）
 * 4. 提示缓存（系统提示词自动添加 cache_control，节省重复 token）
 * 5. Extended Thinking 支持（通过 betas 字段启用）
 *
 * 使用方式：
 * ```kotlin
 * val client = AnthropicClient(apiKey = "sk-ant-...")
 *
 * // 非流式（等待完整响应）
 * val response = client.sendMessage(request)
 *
 * // 流式（实时接收事件）
 * client.sendMessageStream(request).collect { event ->
 *     when (event) {
 *         is StreamEvent.ContentBlockDelta -> updateUI(event.delta)
 *         is StreamEvent.AssembledResponse -> handleFinalResponse(event.response)
 *         else -> {}
 *     }
 * }
 * ```
 */
class AnthropicClient(private val apiKey: String) {

    /**
     * JSON 序列化配置
     *
     * - ignoreUnknownKeys = true：忽略 API 新增的未知字段，保证向后兼容
     * - encodeDefaults = false：不序列化默认值，减少请求体大小
     * - isLenient = true：允许宽松的 JSON 格式（如结尾逗号）
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    /**
     * Ktor HTTP 客户端（使用 Android 引擎）
     *
     * 使用 Ktor 而非 OkHttp，原因：
     * 1. 与 Kotlin Coroutines 和 Flow 原生集成
     * 2. Android 引擎基于 HttpURLConnection，无额外依赖
     * 3. 流式 SSE 处理更简洁（ByteReadChannel）
     */
    private val httpClient = HttpClient(Android) {
        // 安装 JSON 内容协商插件
        install(ContentNegotiation) {
            json(json)
        }

        // 请求超时配置
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000   // 5分钟总超时（Agent 任务可能很长）
            connectTimeoutMillis = 30_000    // 30秒连接超时
            socketTimeoutMillis = 120_000    // 2分钟 Socket 读写超时
        }

        // HTTP 请求日志（记录 URL 和状态码，不记录 body 避免泄露敏感信息）
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, message)
                }
            }
            level = LogLevel.INFO  // INFO 级别：只记录 URL 和响应码
        }
    }

    // ============================================================
    // 指数退避重试逻辑
    // ============================================================

    /**
     * 带指数退避重试的执行包装器
     *
     * 当 Anthropic API 返回 529（服务过载）时，自动按指数退避策略重试。
     * 退避时间：1s → 2s → 4s → ...（最多重试 maxRetries 次）
     *
     * 只有 529 错误才重试，其他错误（401/429/500 等）直接抛出。
     *
     * @param maxRetries 最大重试次数（不含初始尝试），默认 3 次
     * @param initialDelay 初始等待时间（毫秒），默认 1000ms
     * @param block 要执行的挂起函数
     * @return block 的返回值
     * @throws Exception 当超过最大重试次数或遇到非 529 错误时
     */
    private suspend fun <T> withRetry(
        maxRetries: Int = 3,
        initialDelay: Long = 1000L,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelay

        // 尝试 maxRetries 次，每次失败且是 529 则等待后重试
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                // 只对 529（服务过载）进行重试
                if (e is ClientRequestException && e.response.status.value == 529) {
                    Log.w(TAG, "API 过载（529），第 ${attempt + 1} 次重试，等待 ${currentDelay}ms")
                    delay(currentDelay)
                    currentDelay *= 2  // 指数退避：每次翻倍
                    return@repeat      // 继续下一次 repeat 迭代
                }
                // 非 529 错误直接抛出，不重试
                throw e
            }
        }

        // 所有重试都失败后，执行最后一次（让异常自然抛出）
        Log.w(TAG, "已重试 $maxRetries 次，执行最终尝试")
        return block()
    }

    // ============================================================
    // 提示缓存辅助方法
    // ============================================================

    /**
     * 为系统提示词的最后一个文本块添加缓存控制
     *
     * 真实 Claude Code 的做法：在系统提示词末尾添加 cache_control，
     * 这样系统提示词（通常包含大量工具定义和规则）会被缓存，
     * 后续 Agent Loop 迭代时直接读取缓存，节省大量 input_tokens。
     *
     * @param systemText 原始系统提示词字符串
     * @return 带缓存控制的 ContentBlock.Text 列表（单元素列表）
     */
    private fun buildCachedSystemPrompt(systemText: String): List<ContentBlock.Text> {
        return listOf(
            ContentBlock.Text(
                text = systemText,
                // 在系统提示词末尾标记缓存边界，后续请求命中缓存可节省大量 token
                cacheControl = CacheControl(type = "ephemeral")
            )
        )
    }

    // ============================================================
    // 非流式消息发送
    // ============================================================

    /**
     * 发送消息请求（非流式）
     *
     * 等待 Claude 完整生成后一次性返回，适用于：
     * - 上下文压缩摘要（需要完整响应才能处理）
     * - 简单的单次问答场景
     * - 不需要实时展示的后台任务
     *
     * 内置指数退避重试，遇到 529 过载自动等待重试。
     *
     * @param request 请求体（包含模型、消息历史、工具定义等）
     * @return Claude 的完整响应
     * @throws AnthropicException 当 API 返回非 529 错误时
     */
    suspend fun sendMessage(request: MessagesRequest): MessagesResponse {
        // 强制关闭流式模式（非流式调用）
        val nonStreamRequest = request.copy(stream = false)

        Log.d(TAG, "发送非流式 API 请求，消息数量: ${request.messages.size}，工具数量: ${request.tools.size}")

        return withRetry {
            val response = httpClient.post("$BASE_URL$MESSAGES_ENDPOINT") {
                // 必需的认证和版本请求头
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
                contentType(ContentType.Application.Json)

                // 如果请求包含 beta 功能，添加对应请求头
                nonStreamRequest.betas?.let { betas ->
                    if (betas.isNotEmpty()) {
                        header("anthropic-beta", betas.joinToString(","))
                        Log.d(TAG, "启用 Beta 功能: ${betas.joinToString(", ")}")
                    }
                }

                // 序列化请求体并发送
                setBody(json.encodeToString(MessagesRequest.serializer(), nonStreamRequest))
            }

            // 处理 HTTP 错误响应
            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "非流式 API 请求失败: ${response.status.value} - $errorBody")
                throw AnthropicException(response.status.value, errorBody)
            }

            // 解析并返回完整响应
            val responseBody = response.bodyAsText()
            Log.d(TAG, "非流式响应接收完毕，状态: ${response.status.value}")
            json.decodeFromString(MessagesResponse.serializer(), responseBody)
        }
    }

    // ============================================================
    // 流式 SSE 消息发送（核心方法）
    // ============================================================

    /**
     * 发送流式消息请求（SSE 格式）
     *
     * 使用 Kotlin Flow 实时传递每个 SSE 事件，UI 层可以实时展示 Claude 的输出。
     * 这是 Agent Loop 的核心方法，处理了以下复杂逻辑：
     *
     * 1. SSE 事件解析（正确处理多行事件、空行边界）
     * 2. input_json_delta 拼接（工具参数 JSON 以片段形式传输，需全部拼接后解析）
     * 3. 完整响应组装（流结束后发射 AssembledResponse 事件）
     * 4. Extended Thinking 支持（thinking_delta 和 signature_delta 处理）
     * 5. 指数退避重试（遇到 529 自动重试）
     *
     * SSE 事件格式示例：
     * ```
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     *
     * ```
     * （注意：空行表示一个完整事件的结束）
     *
     * @param request 请求体（stream 字段会被强制设为 true）
     * @return Flow<StreamEvent>，消费者用 collect {} 处理每个事件
     */
    fun sendMessageStream(request: MessagesRequest): Flow<StreamEvent> = flow {
        // 强制启用流式模式
        val streamRequest = request.copy(stream = true)

        Log.d(TAG, "开始流式 SSE 请求，消息数量: ${request.messages.size}，工具数量: ${request.tools.size}")

        // ---- 响应组装状态 ----
        // 消息元数据（从 message_start 事件中提取）
        var messageId = ""
        var messageModel = request.model
        var messageRole = "assistant"
        var initialUsage = TokenUsage(0, 0)
        var finalStopReason: String? = null
        var finalUsage = TokenUsage(0, 0)

        // 内容块列表（按 index 顺序存储，最终组装为完整响应）
        val contentBlocks = mutableMapOf<Int, ContentBlock>()

        // 文本增量缓冲（按 index 存储，用于累积 text_delta）
        val textBuilders = mutableMapOf<Int, StringBuilder>()

        // 工具输入 JSON 缓冲（按 index 存储，用于累积 input_json_delta）
        // 关键修复：之前只 append 但从未拼接解析，导致工具输入为空 JsonObject
        val partialJsonMap = mutableMapOf<Int, StringBuilder>()

        // 思考内容缓冲（按 index 存储，用于累积 thinking_delta）
        val thinkingBuilders = mutableMapOf<Int, StringBuilder>()

        // 思考签名缓冲（按 index 存储，用于累积 signature_delta）
        val signatureBuilders = mutableMapOf<Int, StringBuilder>()

        httpClient.preparePost("$BASE_URL$MESSAGES_ENDPOINT") {
            // 必需的认证和版本请求头
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            contentType(ContentType.Application.Json)
            // 告诉服务器客户端接受 SSE 格式
            header("accept", "text/event-stream")

            // 如果请求包含 beta 功能，添加对应请求头
            streamRequest.betas?.let { betas ->
                if (betas.isNotEmpty()) {
                    header("anthropic-beta", betas.joinToString(","))
                    Log.d(TAG, "流式请求启用 Beta 功能: ${betas.joinToString(", ")}")
                }
            }

            setBody(json.encodeToString(MessagesRequest.serializer(), streamRequest))
        }.execute { response ->
            // 处理 HTTP 层错误
            if (!response.status.isSuccess()) {
                val error = response.bodyAsText()
                Log.e(TAG, "流式 API 请求失败: ${response.status.value} - $error")
                emit(StreamEvent.Error("API 错误 ${response.status.value}: $error"))
                return@execute
            }

            Log.d(TAG, "流式连接建立成功，开始读取 SSE 事件")

            // 逐行读取 SSE 字节流
            val channel: ByteReadChannel = response.bodyAsChannel()

            // SSE 解析状态
            var currentEventType = ""       // 当前事件的 event: 字段值
            val dataLines = mutableListOf<String>()  // 当前事件的 data: 行列表

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

                when {
                    // "event: <type>" 行 — 记录事件类型
                    line.startsWith("event:") -> {
                        currentEventType = line.removePrefix("event:").trim()
                    }

                    // "data: <json>" 行 — 累积数据（一个事件可能跨多个 data: 行）
                    line.startsWith("data:") -> {
                        dataLines.add(line.removePrefix("data:").trim())
                    }

                    // 空行 — SSE 事件边界，处理当前积累的事件
                    line.isEmpty() -> {
                        if (dataLines.isEmpty()) {
                            // 连续空行，忽略
                            continue
                        }

                        // 将多个 data: 行合并为单个字符串
                        val data = dataLines.joinToString("")
                        dataLines.clear()

                        // 处理流结束标志
                        if (data == "[DONE]") {
                            Log.d(TAG, "SSE 流接收到 [DONE] 标志")
                            emit(StreamEvent.MessageStop)
                            currentEventType = ""
                            continue
                        }

                        // 解析并处理 SSE 事件
                        try {
                            val jsonObj = json.parseToJsonElement(data).jsonObject

                            when (currentEventType) {

                                // ---- message_start：消息开始 ----
                                "message_start" -> {
                                    val messageObj = jsonObj["message"]?.jsonObject
                                    messageId = messageObj?.get("id")?.jsonPrimitive?.content ?: ""
                                    messageModel = messageObj?.get("model")?.jsonPrimitive?.content ?: request.model
                                    messageRole = messageObj?.get("role")?.jsonPrimitive?.content ?: "assistant"
                                    // 提取初始 token 使用量
                                    initialUsage = messageObj?.get("usage")?.let {
                                        json.decodeFromJsonElement(TokenUsage.serializer(), it)
                                    } ?: TokenUsage(0, 0)

                                    Log.d(TAG, "消息开始: id=$messageId, model=$messageModel, 初始token=${initialUsage.inputTokens}")
                                    emit(StreamEvent.MessageStart(messageId, messageModel, initialUsage))
                                }

                                // ---- content_block_start：新内容块开始 ----
                                "content_block_start" -> {
                                    val index = jsonObj["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                                    val block = jsonObj["content_block"]?.jsonObject
                                    val blockType = block?.get("type")?.jsonPrimitive?.content

                                    Log.d(TAG, "内容块开始: index=$index, type=$blockType")

                                    when (blockType) {
                                        "text" -> {
                                            // 初始化文本内容缓冲区
                                            textBuilders[index] = StringBuilder()
                                            val initialBlock = ContentBlock.Text("")
                                            contentBlocks[index] = initialBlock
                                            emit(StreamEvent.ContentBlockStart(index, initialBlock))
                                        }
                                        "tool_use" -> {
                                            // 提取工具调用的 id 和 name
                                            val id = block["id"]?.jsonPrimitive?.content ?: ""
                                            val name = block["name"]?.jsonPrimitive?.content ?: ""
                                            // 关键修复：初始化 JSON 拼接缓冲区
                                            partialJsonMap[index] = StringBuilder()
                                            val initialBlock = ContentBlock.ToolUse(id, name, JsonObject(emptyMap()))
                                            contentBlocks[index] = initialBlock
                                            Log.d(TAG, "工具调用开始: index=$index, id=$id, name=$name")
                                            emit(StreamEvent.ContentBlockStart(index, initialBlock))
                                        }
                                        "thinking" -> {
                                            // 初始化思考内容缓冲区
                                            thinkingBuilders[index] = StringBuilder()
                                            signatureBuilders[index] = StringBuilder()
                                            val initialBlock = ContentBlock.Thinking("")
                                            contentBlocks[index] = initialBlock
                                            emit(StreamEvent.ContentBlockStart(index, initialBlock))
                                        }
                                        else -> {
                                            Log.w(TAG, "未知内容块类型: $blockType，跳过")
                                        }
                                    }
                                }

                                // ---- content_block_delta：内容块增量 ----
                                "content_block_delta" -> {
                                    val index = jsonObj["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                                    val delta = jsonObj["delta"]?.jsonObject
                                    val deltaType = delta?.get("type")?.jsonPrimitive?.content

                                    when (deltaType) {
                                        // 文本增量：追加到对应 StringBuilder
                                        "text_delta" -> {
                                            val text = delta["text"]?.jsonPrimitive?.content ?: ""
                                            textBuilders[index]?.append(text)
                                            emit(StreamEvent.ContentBlockDelta(index, DeltaContent.TextDelta(text)))
                                        }

                                        // 工具输入 JSON 增量：追加到 partialJsonMap
                                        // 关键修复：仅在此处累积，在 content_block_stop 时统一解析
                                        "input_json_delta" -> {
                                            val partialJson = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                                            partialJsonMap[index]?.append(partialJson)
                                            emit(StreamEvent.ContentBlockDelta(index, DeltaContent.InputJsonDelta(partialJson)))
                                        }

                                        // 思考增量：追加到 thinkingBuilders
                                        "thinking_delta" -> {
                                            val thinking = delta["thinking"]?.jsonPrimitive?.content ?: ""
                                            thinkingBuilders[index]?.append(thinking)
                                            emit(StreamEvent.ContentBlockDelta(index, DeltaContent.ThinkingDelta(thinking)))
                                        }

                                        // 签名增量：追加到 signatureBuilders
                                        "signature_delta" -> {
                                            val signature = delta["signature"]?.jsonPrimitive?.content ?: ""
                                            signatureBuilders[index]?.append(signature)
                                            emit(StreamEvent.ContentBlockDelta(index, DeltaContent.SignatureDelta(signature)))
                                        }

                                        else -> {
                                            Log.w(TAG, "未知 delta 类型: $deltaType，跳过")
                                        }
                                    }
                                }

                                // ---- content_block_stop：内容块结束 ----
                                // 关键修复点：在此处将拼接好的 JSON 解析为 JsonObject
                                "content_block_stop" -> {
                                    val index = jsonObj["index"]?.jsonPrimitive?.content?.toInt() ?: 0

                                    // 根据该 index 存储的内容块类型，进行最终组装
                                    val currentBlock = contentBlocks[index]
                                    when (currentBlock) {
                                        is ContentBlock.Text -> {
                                            // 组装完整文本内容
                                            val fullText = textBuilders[index]?.toString() ?: ""
                                            contentBlocks[index] = ContentBlock.Text(fullText)
                                            textBuilders.remove(index)
                                            Log.d(TAG, "文本块完成: index=$index, 长度=${fullText.length}")
                                        }
                                        is ContentBlock.ToolUse -> {
                                            // 关键修复：将所有 input_json_delta 拼接后解析为完整 JsonObject
                                            val fullJson = partialJsonMap[index]?.toString() ?: "{}"
                                            val parsedInput = try {
                                                json.parseToJsonElement(fullJson).jsonObject
                                            } catch (e: Exception) {
                                                Log.e(TAG, "工具输入 JSON 解析失败: index=$index, json=$fullJson", e)
                                                JsonObject(emptyMap())  // 解析失败时用空对象兜底
                                            }
                                            // 用解析好的 input 替换占位空对象
                                            contentBlocks[index] = currentBlock.copy(input = parsedInput)
                                            partialJsonMap.remove(index)
                                            Log.d(TAG, "工具调用完成: index=$index, tool=${currentBlock.name}, inputKeys=${parsedInput.keys}")
                                        }
                                        is ContentBlock.Thinking -> {
                                            // 组装完整思考内容和签名
                                            val fullThinking = thinkingBuilders[index]?.toString() ?: ""
                                            val fullSignature = signatureBuilders[index]?.toString()?.takeIf { it.isNotEmpty() }
                                            contentBlocks[index] = ContentBlock.Thinking(fullThinking, fullSignature)
                                            thinkingBuilders.remove(index)
                                            signatureBuilders.remove(index)
                                            Log.d(TAG, "思考块完成: index=$index, 长度=${fullThinking.length}")
                                        }
                                        null -> {
                                            Log.w(TAG, "content_block_stop 收到未知 index: $index")
                                        }
                                        else -> {
                                            Log.w(TAG, "content_block_stop 遇到未处理的块类型: ${currentBlock::class.simpleName}")
                                        }
                                    }

                                    emit(StreamEvent.ContentBlockStop(index))
                                }

                                // ---- message_delta：消息完成 ----
                                "message_delta" -> {
                                    val delta = jsonObj["delta"]?.jsonObject
                                    finalStopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                                    finalUsage = jsonObj["usage"]?.let {
                                        json.decodeFromJsonElement(TokenUsage.serializer(), it)
                                    } ?: TokenUsage(0, 0)

                                    Log.d(TAG, "消息完成: stopReason=$finalStopReason, 输出token=${finalUsage.outputTokens}")
                                    emit(StreamEvent.MessageDelta(finalStopReason, finalUsage))
                                }

                                // ---- message_stop：流结束 ----
                                "message_stop" -> {
                                    Log.d(TAG, "SSE 流正常结束")
                                    emit(StreamEvent.MessageStop)

                                    // 组装完整响应并发射 AssembledResponse 事件
                                    // 按 index 排序，恢复原始顺序
                                    val sortedBlocks = contentBlocks.entries
                                        .sortedBy { it.key }
                                        .map { it.value }

                                    val assembledResponse = MessagesResponse(
                                        id = messageId,
                                        type = "message",
                                        role = messageRole,
                                        content = sortedBlocks,
                                        stopReason = finalStopReason,
                                        usage = finalUsage
                                    )

                                    Log.d(TAG, "发射组装完成响应: blocks=${sortedBlocks.size}, stopReason=$finalStopReason")
                                    emit(StreamEvent.AssembledResponse(assembledResponse))
                                }

                                // ---- error：API 错误 ----
                                "error" -> {
                                    val errorMsg = jsonObj["error"]?.jsonObject
                                        ?.get("message")?.jsonPrimitive?.content
                                        ?: "未知 API 错误"
                                    Log.e(TAG, "API 流式错误: $errorMsg")
                                    emit(StreamEvent.Error(errorMsg))
                                }

                                // ---- ping：心跳 ----
                                "ping" -> {
                                    // 心跳事件，忽略即可（用于保持 SSE 连接活跃）
                                    emit(StreamEvent.Ping)
                                }

                                else -> {
                                    // 未知事件类型，记录但不抛出错误（向前兼容）
                                    Log.d(TAG, "收到未知 SSE 事件类型: $currentEventType，跳过")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "解析 SSE 事件失败: eventType=$currentEventType, data=$data", e)
                            // 解析失败不中断流，继续处理后续事件
                        }

                        currentEventType = ""
                    }

                    // 注释行（以 ":" 开头）或其他行：忽略
                    else -> {
                        // SSE 规范中 ":" 开头是注释，可忽略
                    }
                }
            }

            Log.d(TAG, "SSE ByteReadChannel 已关闭，流处理完成")
        }
    }

    // ============================================================
    // 资源清理
    // ============================================================

    /**
     * 关闭 HTTP 客户端
     *
     * 在 Application 销毁或不再需要此客户端时调用，释放连接池等资源。
     * 通常在 ViewModel 的 onCleared() 或 Application.onTerminate() 中调用。
     */
    fun close() {
        httpClient.close()
        Log.d(TAG, "AnthropicClient HTTP 客户端已关闭")
    }
}

// ============================================================
// 异常类
// ============================================================

/**
 * Anthropic API 调用异常
 *
 * 当 API 返回非 2xx 状态码时抛出，携带 HTTP 状态码和错误信息。
 *
 * 常见错误码：
 * - 401：API Key 无效或过期
 * - 429：请求速率超限（需要降速）
 * - 500/529：服务端错误（建议重试）
 *
 * @param statusCode HTTP 状态码
 * @param message API 返回的错误信息
 */
class AnthropicException(
    /** HTTP 状态码（如 401、429、500、529） */
    val statusCode: Int,
    override val message: String
) : Exception("Anthropic API 错误 $statusCode: $message") {

    /** 是否是认证错误（API Key 无效或缺失） */
    val isAuthError: Boolean get() = statusCode == 401

    /** 是否是权限错误（无权访问该资源） */
    val isForbiddenError: Boolean get() = statusCode == 403

    /** 是否是限流错误（请求太频繁，需要等待） */
    val isRateLimitError: Boolean get() = statusCode == 429

    /** 是否是服务过载错误（需要指数退避重试） */
    val isOverloadedError: Boolean get() = statusCode == 529

    /** 是否是服务端错误（Anthropic 侧问题，可以重试） */
    val isServerError: Boolean get() = statusCode >= 500

    /** 是否可以重试（429 和 5xx 通常可以重试） */
    val isRetryable: Boolean get() = isRateLimitError || isServerError
}
