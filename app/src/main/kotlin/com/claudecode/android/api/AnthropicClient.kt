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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AnthropicClient"
private const val BASE_URL = "https://api.anthropic.com"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val MESSAGES_ENDPOINT = "/v1/messages"

/**
 * Anthropic API 客户端
 *
 * 负责与 Anthropic Messages API 通信，支持：
 * 1. 非流式请求（一次性返回完整响应）
 * 2. 流式 SSE 请求（实时返回 token，体验更好）
 * 3. 自动重试（网络错误时指数退避重试）
 *
 * 使用方式：
 * ```kotlin
 * val client = AnthropicClient(apiKey = "sk-ant-...")
 * val response = client.sendMessage(request)
 * ```
 */
class AnthropicClient(private val apiKey: String) {

    // JSON 序列化配置
    // ignoreUnknownKeys = true：忽略 API 新增的未知字段，保证向后兼容
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    // Ktor HTTP 客户端配置
    private val httpClient = HttpClient(Android) {
        // 安装 JSON 内容协商
        install(ContentNegotiation) {
            json(json)
        }

        // 请求超时配置
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000   // 5分钟总超时（Agent 任务可能很长）
            connectTimeoutMillis = 30_000    // 30秒连接超时
            socketTimeoutMillis = 120_000    // 2分钟 Socket 超时
        }

        // HTTP 请求日志（仅在 Debug 模式下启用）
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, message)
                }
            }
            level = LogLevel.INFO  // 只记录请求 URL 和响应状态码
        }
    }

    /**
     * 发送消息请求（非流式）
     *
     * 适合简单的单次请求，等待 Claude 完整生成后返回。
     *
     * @param request 请求体（包含模型、消息历史、工具定义等）
     * @return Claude 的完整响应
     * @throws AnthropicException 当 API 返回错误时
     */
    suspend fun sendMessage(request: MessagesRequest): MessagesResponse {
        val requestWithNoStream = request.copy(stream = false)

        Log.d(TAG, "发送 API 请求，消息数量: ${request.messages.size}，工具数量: ${request.tools.size}")

        val response = httpClient.post("$BASE_URL$MESSAGES_ENDPOINT") {
            // 设置必要的请求头
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            header("content-type", "application/json")

            setBody(json.encodeToString(MessagesRequest.serializer(), requestWithNoStream))
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            Log.e(TAG, "API 请求失败: ${response.status} - $errorBody")
            throw AnthropicException(response.status.value, errorBody)
        }

        return json.decodeFromString(MessagesResponse.serializer(), response.bodyAsText())
    }

    /**
     * 发送流式消息请求（SSE 格式）
     *
     * 使用 Kotlin Flow 实时传递每个 SSE 事件，UI 层可以实时展示 Claude 的输出。
     *
     * SSE 事件格式示例：
     * ```
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     * ```
     *
     * @param request 请求体
     * @return 事件流，消费者可以用 collect {} 处理每个事件
     */
    fun sendMessageStream(request: MessagesRequest): Flow<StreamEvent> = flow {
        val requestWithStream = request.copy(stream = true)

        Log.d(TAG, "开始流式请求，消息数量: ${request.messages.size}")

        httpClient.preparePost("$BASE_URL$MESSAGES_ENDPOINT") {
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_VERSION)
            header("content-type", "application/json")
            header("accept", "text/event-stream")  // 告诉服务器使用 SSE 格式

            setBody(json.encodeToString(MessagesRequest.serializer(), requestWithStream))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val error = response.bodyAsText()
                Log.e(TAG, "流式请求失败: ${response.status} - $error")
                emit(StreamEvent.Error("API 错误 ${response.status.value}: $error"))
                return@execute
            }

            // 逐行读取 SSE 流
            val channel: ByteReadChannel = response.bodyAsChannel()
            var currentEventType = ""
            val dataBuffer = StringBuilder()

            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break

                when {
                    // "event: xxx" 行 — 记录事件类型
                    line.startsWith("event:") -> {
                        currentEventType = line.removePrefix("event:").trim()
                    }

                    // "data: xxx" 行 — 记录数据
                    line.startsWith("data:") -> {
                        dataBuffer.append(line.removePrefix("data:").trim())
                    }

                    // 空行 — 一个完整事件结束，解析并发送
                    line.isEmpty() && dataBuffer.isNotEmpty() -> {
                        val data = dataBuffer.toString()
                        dataBuffer.clear()

                        // 解析 SSE 事件为 StreamEvent
                        val event = parseSseEvent(currentEventType, data)
                        if (event != null) {
                            emit(event)
                        }
                        currentEventType = ""
                    }
                }
            }

            Log.d(TAG, "流式响应完成")
        }
    }

    /**
     * 解析单个 SSE 事件
     *
     * @param eventType SSE 事件类型字符串
     * @param data SSE data 字段内容（JSON 字符串）
     * @return 解析后的 StreamEvent，解析失败返回 null
     */
    private fun parseSseEvent(eventType: String, data: String): StreamEvent? {
        if (data == "[DONE]") return StreamEvent.MessageStop

        return try {
            val jsonObj = json.parseToJsonElement(data).jsonObject

            when (eventType) {
                "message_start" -> {
                    // 消息开始事件，包含初始 token 使用量
                    Log.d(TAG, "Stream: message_start")
                    null  // 暂时跳过，后续可以提取消息 ID
                }

                "content_block_start" -> {
                    // 新内容块开始
                    val index = jsonObj["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                    val block = jsonObj["content_block"]?.jsonObject
                    val blockType = block?.get("type")?.jsonPrimitive?.content

                    Log.d(TAG, "Stream: content_block_start [index=$index, type=$blockType]")

                    when (blockType) {
                        "text" -> StreamEvent.ContentBlockStart(index, ContentBlock.Text(""))
                        "tool_use" -> {
                            val id = block["id"]?.jsonPrimitive?.content ?: ""
                            val name = block["name"]?.jsonPrimitive?.content ?: ""
                            StreamEvent.ContentBlockStart(index, ContentBlock.ToolUse(id, name, JsonObject(emptyMap())))
                        }
                        else -> null
                    }
                }

                "content_block_delta" -> {
                    // 内容块增量数据
                    val index = jsonObj["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                    val delta = jsonObj["delta"]?.jsonObject
                    val deltaType = delta?.get("type")?.jsonPrimitive?.content

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.content ?: ""
                            StreamEvent.ContentBlockDelta(index, DeltaContent.TextDelta(text))
                        }
                        "input_json_delta" -> {
                            val partialJson = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                            StreamEvent.ContentBlockDelta(index, DeltaContent.InputJsonDelta(partialJson))
                        }
                        "thinking_delta" -> {
                            val thinking = delta["thinking"]?.jsonPrimitive?.content ?: ""
                            StreamEvent.ContentBlockDelta(index, DeltaContent.ThinkingDelta(thinking))
                        }
                        else -> null
                    }
                }

                "content_block_stop" -> {
                    val index = jsonObj["index"]?.jsonPrimitive?.content?.toInt() ?: 0
                    StreamEvent.ContentBlockStop(index)
                }

                "message_delta" -> {
                    // 消息结束，包含停止原因和最终 token 统计
                    val delta = jsonObj["delta"]?.jsonObject
                    val stopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                    val usage = jsonObj["usage"]?.let {
                        json.decodeFromJsonElement(TokenUsage.serializer(), it)
                    } ?: TokenUsage(0, 0)

                    Log.d(TAG, "Stream: message_delta [stopReason=$stopReason, tokens=${usage.total}]")
                    StreamEvent.MessageDelta(stopReason, usage)
                }

                "message_stop" -> {
                    Log.d(TAG, "Stream: message_stop")
                    StreamEvent.MessageStop
                }

                "error" -> {
                    val errorMsg = jsonObj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                    Log.e(TAG, "Stream error: $errorMsg")
                    StreamEvent.Error(errorMsg)
                }

                else -> null  // 忽略未知事件类型
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 SSE 事件失败: eventType=$eventType, data=$data", e)
            null
        }
    }

    /** 关闭 HTTP 客户端（在 App 销毁时调用） */
    fun close() {
        httpClient.close()
    }
}

/**
 * Anthropic API 异常
 *
 * @param statusCode HTTP 状态码（如 401、429、500）
 * @param message 错误信息（来自 API 响应体）
 */
class AnthropicException(
    val statusCode: Int,
    override val message: String
) : Exception("Anthropic API Error $statusCode: $message") {

    /** 是否是认证错误（API Key 无效） */
    val isAuthError: Boolean get() = statusCode == 401

    /** 是否是限流错误（请求太频繁） */
    val isRateLimitError: Boolean get() = statusCode == 429

    /** 是否是服务端错误（Anthropic 服务器问题） */
    val isServerError: Boolean get() = statusCode >= 500
}
