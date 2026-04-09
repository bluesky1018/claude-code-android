package com.claudecode.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Anthropic API 数据模型
 *
 * 这些数据类对应 Anthropic Messages API 的 JSON 请求/响应格式。
 * 参考文档：https://docs.anthropic.com/en/api/messages
 *
 * 核心概念：
 * - Message：一条对话消息（user 或 assistant）
 * - ContentBlock：消息内容的最小单元（文本、工具调用、工具结果等）
 * - ToolDefinition：告诉 Claude 可以使用哪些工具（JSON Schema 格式）
 */

// ============================================================
// 消息相关数据类
// ============================================================

/**
 * 对话消息
 *
 * @param role "user"（用户/工具结果）或 "assistant"（Claude 的回复）
 * @param content 消息内容列表（一条消息可以包含多个内容块）
 */
@Serializable
data class Message(
    val role: String,
    val content: List<ContentBlock>
) {
    companion object {
        /** 创建一条简单的用户文本消息 */
        fun user(text: String) = Message("user", listOf(ContentBlock.Text(text)))

        /** 创建一条简单的 assistant 文本消息 */
        fun assistant(text: String) = Message("assistant", listOf(ContentBlock.Text(text)))
    }
}

/**
 * 内容块（ContentBlock）— 消息内容的最小单元
 *
 * Anthropic API 使用多态 JSON，通过 "type" 字段区分类型：
 * - "text"：纯文本内容
 * - "tool_use"：Claude 请求调用某个工具（包含工具名和参数）
 * - "tool_result"：工具调用的执行结果（返回给 Claude）
 * - "thinking"：Claude 的内部思考过程（extended thinking 模式）
 */
@Serializable
sealed class ContentBlock {

    /** 纯文本内容 */
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String
    ) : ContentBlock()

    /**
     * 工具调用请求
     * 当 Claude 决定需要调用某个工具时，会在响应中包含这个内容块。
     *
     * @param id 工具调用的唯一 ID，后续需要用这个 ID 匹配工具结果
     * @param name 工具名称（如 "Read", "Write", "Bash"）
     * @param input 工具参数（JSON 对象）
     */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : ContentBlock()

    /**
     * 工具调用结果
     * 工具执行完成后，需要将结果通过 user 消息返回给 Claude。
     *
     * @param toolUseId 对应工具调用请求的 ID
     * @param content 工具执行结果（字符串）
     * @param isError 如果为 true，表示工具执行失败
     */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false
    ) : ContentBlock()

    /**
     * Claude 的思考过程（仅在 extended thinking 模式下出现）
     * 这些内容不会直接显示给用户，但有助于理解 Claude 的推理过程。
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        val thinking: String
    ) : ContentBlock()
}

// ============================================================
// API 请求数据类
// ============================================================

/**
 * 发送给 Anthropic API 的请求体
 *
 * @param model 使用的模型（如 "claude-opus-4-6-20260401"）
 * @param maxTokens 最大输出 token 数
 * @param stream 是否使用 SSE 流式输出（推荐开启，体验更好）
 * @param system 系统提示词（定义 Claude 的角色、工具、行为规则）
 * @param tools 可用工具定义列表（JSON Schema 格式）
 * @param messages 完整的对话历史（每次都要传全部历史！）
 */
@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = 8096,
    val stream: Boolean = true,
    val system: String,
    val tools: List<ToolDefinition> = emptyList(),
    val messages: List<Message>
)

// ============================================================
// 工具定义数据类
// ============================================================

/**
 * 工具定义（传给 API，告诉 Claude 可以使用哪些工具）
 *
 * 格式完全遵循 JSON Schema 规范。
 * Claude 会根据这些定义决定什么时候调用哪个工具、传入什么参数。
 *
 * @param name 工具名（如 "Read"）
 * @param description 工具功能描述（非常重要！Claude 根据这个决定是否使用）
 * @param inputSchema 输入参数的 JSON Schema
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

// ============================================================
// API 响应数据类
// ============================================================

/**
 * Anthropic API 的完整响应（非流式模式）
 *
 * @param id 响应唯一 ID
 * @param type 响应类型（通常是 "message"）
 * @param role 响应角色（始终是 "assistant"）
 * @param content 响应内容列表（可能包含 text 和 tool_use 块）
 * @param stopReason 停止原因：
 *   - "end_turn"：Claude 认为任务完成，停止输出
 *   - "tool_use"：Claude 需要调用工具，暂停等待工具结果
 *   - "max_tokens"：达到最大 token 限制
 * @param usage Token 使用统计
 */
@Serializable
data class MessagesResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ContentBlock>,
    @SerialName("stop_reason") val stopReason: String?,
    val usage: TokenUsage
) {
    /** 判断是否还需要继续 Agent Loop（还有工具调用未完成） */
    val requiresToolExecution: Boolean get() = stopReason == "tool_use"

    /** 判断任务是否已完成 */
    val isFinished: Boolean get() = stopReason == "end_turn"

    /** 提取所有工具调用请求 */
    val toolUseBlocks: List<ContentBlock.ToolUse>
        get() = content.filterIsInstance<ContentBlock.ToolUse>()

    /** 提取所有文本内容，拼接成单个字符串 */
    val textContent: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
}

/**
 * Token 使用统计
 * 用于监控上下文使用量，决定何时触发上下文压缩
 */
@Serializable
data class TokenUsage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("cache_read_input_tokens") val cacheReadTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationTokens: Int = 0
) {
    /** 总 Token 数 */
    val total: Int get() = inputTokens + outputTokens

    /** 判断是否接近上下文窗口限制（超过 85% 发出警告） */
    fun isApproachingLimit(limit: Int = 200_000): Boolean = inputTokens > limit * 0.85

    /** 判断是否需要立即压缩（超过 90%） */
    fun requiresCompression(limit: Int = 200_000): Boolean = inputTokens > limit * 0.90
}

// ============================================================
// SSE 流式事件数据类
// ============================================================

/**
 * SSE（Server-Sent Events）流式响应事件类型
 *
 * Anthropic API 的流式输出使用 SSE 格式，每个事件有固定的类型。
 * 处理流式输出时，需要按顺序处理这些事件来重建完整响应。
 */
sealed class StreamEvent {
    /** 消息开始（包含消息 ID 等元数据） */
    data class MessageStart(val message: MessagesResponse) : StreamEvent()

    /** 新内容块开始（文本块或工具调用块） */
    data class ContentBlockStart(val index: Int, val contentBlock: ContentBlock) : StreamEvent()

    /** 内容块数据片段（流式传输中的部分数据） */
    data class ContentBlockDelta(val index: Int, val delta: DeltaContent) : StreamEvent()

    /** 内容块结束 */
    data class ContentBlockStop(val index: Int) : StreamEvent()

    /** 消息完成（包含停止原因和 Token 统计） */
    data class MessageDelta(val stopReason: String?, val usage: TokenUsage) : StreamEvent()

    /** 整个消息结束 */
    object MessageStop : StreamEvent()

    /** 错误事件 */
    data class Error(val message: String) : StreamEvent()
}

/** SSE Delta 内容（部分文本或部分工具输入 JSON） */
@Serializable
sealed class DeltaContent {
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val text: String) : DeltaContent()

    @Serializable
    @SerialName("input_json_delta")
    data class InputJsonDelta(@SerialName("partial_json") val partialJson: String) : DeltaContent()

    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(val thinking: String) : DeltaContent()
}

// ============================================================
// 可用模型枚举
// ============================================================

/**
 * Anthropic 可用模型
 * 按能力由强到弱排列，越靠前的模型能力越强但成本也越高。
 */
enum class ClaudeModel(val modelId: String, val displayName: String) {
    CLAUDE_OPUS_4_6("claude-opus-4-6-20260401", "Claude Opus 4.6（最强）"),
    CLAUDE_SONNET_4_5("claude-sonnet-4-5-20260401", "Claude Sonnet 4.5（平衡）"),
    CLAUDE_HAIKU_4("claude-haiku-4-20260401", "Claude Haiku 4（快速/低成本）");

    companion object {
        val DEFAULT = CLAUDE_OPUS_4_6
    }
}
