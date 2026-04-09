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
 * - CacheControl：提示缓存控制，标记哪些内容可以被缓存以节省 token
 * - StreamEvent：SSE 流式事件，用于实时展示 Claude 的输出
 */

// ============================================================
// 缓存控制数据类
// ============================================================

/**
 * 缓存控制（Prompt Caching）
 *
 * 通过在内容块上添加 cache_control 字段，告诉 API 缓存该块及其之前的内容。
 * 后续请求如果前缀匹配，将直接读取缓存，大幅减少重复内容的 token 消耗。
 *
 * 使用场景：
 * - 系统提示词（通常很长且固定，非常适合缓存）
 * - 长文档/代码上下文（每次 Agent 循环都传递的内容）
 *
 * @param type 缓存类型，目前固定为 "ephemeral"（临时缓存，约5分钟有效）
 */
@Serializable
data class CacheControl(
    val type: String = "ephemeral"  // 临时缓存类型，当前只支持 "ephemeral"
)

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
    /** 消息角色："user" 或 "assistant" */
    val role: String,
    /** 消息内容块列表（支持多种类型混合，如文本+工具调用） */
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

    /**
     * 纯文本内容
     *
     * @param text 文本字符串内容
     * @param cacheControl 可选的缓存控制（设置后该块及之前的内容会被缓存）
     */
    @Serializable
    @SerialName("text")
    data class Text(
        /** 实际文本内容 */
        val text: String,
        /** 可选缓存控制，用于提示缓存（Prompt Caching），为 null 表示不缓存 */
        @SerialName("cache_control") val cacheControl: CacheControl? = null
    ) : ContentBlock()

    /**
     * 工具调用请求
     * 当 Claude 决定需要调用某个工具时，会在响应中包含这个内容块。
     *
     * @param id 工具调用的唯一 ID，后续需要用这个 ID 匹配工具结果
     * @param name 工具名称（如 "Read", "Write", "Bash"）
     * @param input 工具参数（JSON 对象，结构由工具的 inputSchema 定义）
     */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        /** 工具调用唯一标识符，格式如 "toolu_01AbCdEf..." */
        val id: String,
        /** 工具名称，与 ToolDefinition.name 对应 */
        val name: String,
        /** 工具调用参数，满足对应工具的 inputSchema 约束 */
        val input: JsonObject
    ) : ContentBlock()

    /**
     * 工具调用结果
     * 工具执行完成后，需要将结果通过 user 消息返回给 Claude。
     *
     * @param toolUseId 对应工具调用请求的 ID（与 ToolUse.id 匹配）
     * @param content 工具执行结果（字符串，可以是任意格式）
     * @param isError 如果为 true，表示工具执行失败（Claude 会尝试处理错误）
     */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        /** 对应 ToolUse.id，用于匹配工具调用和结果 */
        @SerialName("tool_use_id") val toolUseId: String,
        /** 工具执行的输出内容 */
        val content: String,
        /** 是否发生错误，true 时 Claude 知道工具执行失败 */
        @SerialName("is_error") val isError: Boolean = false
    ) : ContentBlock()

    /**
     * Claude 的思考过程（仅在 extended thinking 模式下出现）
     * 这些内容不会直接显示给用户，但有助于理解 Claude 的推理过程。
     *
     * @param thinking 思考过程的文本内容
     * @param signature 思考块的签名，用于验证内容未被篡改（回传时必须原样传递）
     */
    @Serializable
    @SerialName("thinking")
    data class Thinking(
        /** Claude 的思考过程文本 */
        val thinking: String,
        /** 思考块签名，回传给 API 时必须原样携带，保证完整性 */
        val signature: String? = null
    ) : ContentBlock()
}

// ============================================================
// API 请求数据类
// ============================================================

/**
 * 发送给 Anthropic API 的请求体
 *
 * @param model 使用的模型（如 "claude-opus-4-6-20260401"）
 * @param maxTokens 最大输出 token 数（包含思考 token）
 * @param stream 是否使用 SSE 流式输出（推荐开启，体验更好）
 * @param system 系统提示词（定义 Claude 的角色、工具、行为规则）
 * @param tools 可用工具定义列表（JSON Schema 格式）
 * @param messages 完整的对话历史（每次都要传全部历史！）
 * @param betas 启用的 Beta 功能列表，如 "interleaved-thinking-2025-05-14"
 */
@Serializable
data class MessagesRequest(
    /** 要使用的 Claude 模型 ID */
    val model: String,
    /** 生成内容的最大 token 数，建议设置足够大以避免截断 */
    @SerialName("max_tokens") val maxTokens: Int = 8096,
    /** 是否启用流式输出，true 时使用 SSE，实时返回增量内容 */
    val stream: Boolean = true,
    /** 系统提示词，用于定义 Claude 的角色和行为规范 */
    val system: String,
    /** 可用工具列表，Claude 会根据任务选择合适的工具调用 */
    val tools: List<ToolDefinition> = emptyList(),
    /** 完整对话历史，按时间顺序排列，交替出现 user 和 assistant */
    val messages: List<Message>,
    /**
     * Beta 功能标识列表（可选）
     * 示例：listOf("interleaved-thinking-2025-05-14") 启用交错思考模式
     * 需要在请求头 "anthropic-beta" 中传递相同的值
     */
    val betas: List<String>? = null
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
 * @param inputSchema 输入参数的 JSON Schema（定义工具接受的参数结构）
 */
@Serializable
data class ToolDefinition(
    /** 工具名称，在工具调用中通过此名称引用 */
    val name: String,
    /** 工具功能描述，Claude 通过这段描述判断何时使用该工具 */
    val description: String,
    /** 参数的 JSON Schema，定义工具接受哪些参数及其类型约束 */
    @SerialName("input_schema") val inputSchema: JsonObject
)

// ============================================================
// API 响应数据类
// ============================================================

/**
 * Anthropic API 的完整响应（非流式模式）
 *
 * @param id 响应唯一 ID（格式如 "msg_01AbCdEf..."）
 * @param type 响应类型（通常是 "message"）
 * @param role 响应角色（始终是 "assistant"）
 * @param content 响应内容列表（可能包含 text 和 tool_use 块）
 * @param stopReason 停止原因，决定后续 Agent 行为
 * @param usage Token 使用统计（含缓存统计）
 */
@Serializable
data class MessagesResponse(
    /** 响应消息的唯一 ID */
    val id: String,
    /** 响应类型，通常为 "message" */
    val type: String,
    /** 响应角色，始终为 "assistant" */
    val role: String,
    /** 响应内容块列表，可能同时包含文本和工具调用 */
    val content: List<ContentBlock>,
    /** 停止原因（见 StopReason 枚举），决定 Agent 是否继续循环 */
    @SerialName("stop_reason") val stopReason: String?,
    /** Token 使用统计，用于监控上下文消耗和缓存效果 */
    val usage: TokenUsage
) {
    /** 判断是否还需要继续 Agent Loop（还有工具调用未完成） */
    val requiresToolExecution: Boolean get() = stopReason == StopReason.TOOL_USE.value

    /** 判断任务是否已完成 */
    val isFinished: Boolean get() = stopReason == StopReason.END_TURN.value

    /** 判断是否因 token 超限而停止 */
    val isMaxTokens: Boolean get() = stopReason == StopReason.MAX_TOKENS.value

    /** 判断是否因 pause_turn 暂停（用于权限决策等场景） */
    val isPaused: Boolean get() = stopReason == StopReason.PAUSE_TURN.value

    /** 提取所有工具调用请求 */
    val toolUseBlocks: List<ContentBlock.ToolUse>
        get() = content.filterIsInstance<ContentBlock.ToolUse>()

    /** 提取所有文本内容，拼接成单个字符串 */
    val textContent: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    /** 提取所有思考块内容 */
    val thinkingContent: String
        get() = content.filterIsInstance<ContentBlock.Thinking>().joinToString("\n") { it.thinking }
}

/**
 * Token 使用统计
 * 用于监控上下文使用量，决定何时触发上下文压缩，以及评估缓存效果。
 *
 * @param inputTokens 本次请求输入的 token 数（不含缓存读取部分）
 * @param outputTokens 本次请求生成的输出 token 数
 * @param cacheCreationInputTokens 本次写入缓存的 token 数（创建缓存条目的成本）
 * @param cacheReadInputTokens 本次从缓存读取的 token 数（命中缓存节省的量）
 */
@Serializable
data class TokenUsage(
    /** 输入 token 数（实际处理的，不含缓存命中的部分） */
    @SerialName("input_tokens") val inputTokens: Int,
    /** 输出 token 数（Claude 生成的内容） */
    @SerialName("output_tokens") val outputTokens: Int,
    /** 缓存创建消耗的 token 数（首次写入缓存时产生） */
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    /** 从缓存读取的 token 数（命中缓存时节省的量） */
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0
) {
    /** 总 Token 数（输入+输出，不含缓存统计） */
    val total: Int get() = inputTokens + outputTokens

    /** 实际等效输入 token（含缓存读取，用于估算实际上下文大小） */
    val effectiveInputTokens: Int get() = inputTokens + cacheReadInputTokens

    /** 判断是否接近上下文窗口限制（超过 85% 发出警告） */
    fun isApproachingLimit(limit: Int = 200_000): Boolean = effectiveInputTokens > limit * 0.85

    /** 判断是否需要立即压缩（超过 90%） */
    fun requiresCompression(limit: Int = 200_000): Boolean = effectiveInputTokens > limit * 0.90

    /** 缓存命中率（节省的 token 占总等效输入的比例） */
    val cacheHitRate: Float
        get() = if (effectiveInputTokens > 0) cacheReadInputTokens.toFloat() / effectiveInputTokens else 0f
}

// ============================================================
// 停止原因枚举
// ============================================================

/**
 * Claude 停止生成的原因
 *
 * Agent Loop 的核心逻辑依赖于 stop_reason 来决定下一步行为：
 * - end_turn / stop_sequence → 任务完成，结束循环
 * - tool_use → 执行工具，将结果加入历史，继续循环
 * - max_tokens → 触发上下文压缩或报错
 * - pause_turn → 等待用户权限决策
 * - refusal → 模型拒绝执行，需要告知用户
 */
enum class StopReason(val value: String) {
    /** Claude 认为任务完成，主动停止输出 */
    END_TURN("end_turn"),
    /** 达到 max_tokens 限制，输出被截断 */
    MAX_TOKENS("max_tokens"),
    /** Claude 需要调用工具，暂停等待工具结果 */
    TOOL_USE("tool_use"),
    /** 暂停当前轮次，等待外部输入（如权限决策） */
    PAUSE_TURN("pause_turn"),
    /** 模型拒绝执行请求（安全策略触发） */
    REFUSAL("refusal"),
    /** 触发了停止序列（stop_sequences 中定义的字符串） */
    STOP_SEQUENCE("stop_sequence");

    companion object {
        /** 从字符串值查找对应枚举 */
        fun fromValue(value: String): StopReason? = entries.find { it.value == value }
    }
}

// ============================================================
// 权限决策枚举
// ============================================================

/**
 * 权限决策结果
 *
 * 当 stop_reason 为 "pause_turn" 时，用户需要做出权限决策：
 * - allow：允许执行操作
 * - deny：拒绝执行操作
 * - ask：需要进一步询问用户
 *
 * 对应真实 Claude Code 的 permission system。
 */
enum class PermissionDecision(val value: String) {
    /** 允许执行该操作 */
    ALLOW("allow"),
    /** 拒绝执行该操作 */
    DENY("deny"),
    /** 需要询问用户意见（默认行为） */
    ASK("ask");

    companion object {
        /** 从字符串值查找对应枚举 */
        fun fromValue(value: String): PermissionDecision? = entries.find { it.value == value }
    }
}

// ============================================================
// Hook 输出数据类
// ============================================================

/**
 * Hook 特定输出（结构化 Hook 返回值）
 *
 * 当工具执行完成后，Hook 系统可以通过这个数据类返回结构化信息，
 * 用于影响后续的 Agent 行为（如是否继续、是否需要权限等）。
 *
 * @param decision 权限决策结果
 * @param reason 决策原因说明（会展示给用户）
 * @param output 额外的输出内容（传递给 Claude 或展示给用户）
 */
@Serializable
data class HookSpecificOutput(
    /** 权限决策：allow/deny/ask */
    val decision: String? = null,
    /** 决策的原因描述，用于向用户解释为何需要权限 */
    val reason: String? = null,
    /** Hook 的额外输出内容，可附加到工具结果中 */
    val output: String? = null
)

// ============================================================
// SSE 流式事件数据类
// ============================================================

/**
 * SSE（Server-Sent Events）流式响应事件类型
 *
 * Anthropic API 的流式输出使用 SSE 格式，每个事件有固定的类型。
 * 处理流式输出时，需要按顺序处理这些事件来重建完整响应。
 *
 * 完整的事件序列：
 * 1. message_start → 消息开始，携带元数据
 * 2. content_block_start → 内容块开始（文本或工具调用）
 * 3. content_block_delta (×N) → 增量数据（文本片段或 JSON 片段）
 * 4. content_block_stop → 内容块结束
 * 5. (重复 2-4 若有多个内容块)
 * 6. message_delta → 消息完成，携带停止原因和最终 token 统计
 * 7. message_stop → 整个流结束
 */
sealed class StreamEvent {
    /**
     * 消息开始事件（message_start）
     * 流中第一个事件，包含消息 ID、模型、角色和初始 token 统计
     *
     * @param messageId 消息的唯一 ID（如 "msg_01AbCd..."）
     * @param model 实际使用的模型 ID
     * @param usage 初始 token 统计（此时 output_tokens 通常为 0）
     */
    data class MessageStart(
        /** 响应消息的唯一标识符 */
        val messageId: String,
        /** 实际使用的模型名称 */
        val model: String,
        /** 初始 token 使用统计 */
        val usage: TokenUsage
    ) : StreamEvent()

    /**
     * 内容块开始事件（content_block_start）
     * 通知客户端一个新内容块（文本或工具调用）即将开始
     *
     * @param index 内容块在响应中的位置索引（从 0 开始）
     * @param contentBlock 内容块的初始状态（文本块内容为空，工具调用块含 id 和 name）
     */
    data class ContentBlockStart(
        /** 内容块的位置索引，用于关联后续 delta 事件 */
        val index: Int,
        /** 内容块初始值（Text 或 ToolUse，内容为空待 delta 填充） */
        val contentBlock: ContentBlock
    ) : StreamEvent()

    /**
     * 内容块增量事件（content_block_delta）
     * 携带内容块的增量数据（流式传输的核心事件）
     *
     * @param index 对应内容块的索引
     * @param delta 增量内容（文本片段、JSON 片段或思考片段）
     */
    data class ContentBlockDelta(
        /** 对应 ContentBlockStart 事件的 index */
        val index: Int,
        /** 增量内容，需要追加到对应内容块中 */
        val delta: DeltaContent
    ) : StreamEvent()

    /**
     * 内容块结束事件（content_block_stop）
     * 通知该内容块的所有增量已传输完毕，可以开始处理
     *
     * @param index 结束的内容块索引
     */
    data class ContentBlockStop(
        /** 已完成传输的内容块索引 */
        val index: Int
    ) : StreamEvent()

    /**
     * 消息增量事件（message_delta）
     * 整个消息生成完毕，携带停止原因和最终 token 统计
     *
     * @param stopReason 停止原因（"end_turn"/"tool_use"/"max_tokens" 等）
     * @param usage 最终 token 使用统计（含 output_tokens 的最终值）
     */
    data class MessageDelta(
        /** 生成停止的原因，决定 Agent Loop 的下一步行为 */
        val stopReason: String?,
        /** 最终 token 使用统计 */
        val usage: TokenUsage
    ) : StreamEvent()

    /**
     * 消息结束事件（message_stop）
     * SSE 流的最后一个事件，标志整个流传输完成
     */
    object MessageStop : StreamEvent()

    /**
     * 错误事件（error）
     * API 发生错误时发送，包含错误描述
     *
     * @param message 错误信息描述
     */
    data class Error(val message: String) : StreamEvent()

    /**
     * Ping 事件（ping）
     * API 定期发送的心跳事件，用于保持连接活跃，客户端可忽略
     */
    object Ping : StreamEvent()

    /**
     * 完整响应组装完成事件（内部使用）
     * 当所有 SSE 事件处理完毕后，发射此事件包含完整的响应内容
     *
     * @param response 完整组装好的响应（含所有内容块、停止原因、token 统计）
     */
    data class AssembledResponse(
        /** 完整组装好的消息响应，可直接用于 Agent Loop 逻辑判断 */
        val response: MessagesResponse
    ) : StreamEvent()
}

/**
 * SSE Delta 增量内容
 *
 * 每个 content_block_delta 事件包含一个 DeltaContent，描述增量的类型和内容。
 * 客户端需要根据类型将增量追加到对应的内容块中。
 */
@Serializable
sealed class DeltaContent {

    /**
     * 文本增量（text_delta）
     * 文本内容块的增量，直接追加到当前文本内容后
     *
     * @param text 本次增量的文本片段
     */
    @Serializable
    @SerialName("text_delta")
    data class TextDelta(
        /** 增量文本片段，需追加到对应 Text 块的 text 字段 */
        val text: String
    ) : DeltaContent()

    /**
     * 工具输入 JSON 增量（input_json_delta）
     * 工具调用块的参数 JSON 以流式方式传输，每次只传输一个片段
     * 需要将所有片段拼接后解析为完整的 JsonObject
     *
     * @param partialJson 部分 JSON 字符串片段（不是完整 JSON！）
     */
    @Serializable
    @SerialName("input_json_delta")
    data class InputJsonDelta(
        /** 工具参数 JSON 的部分片段，需拼接所有片段后才能解析 */
        @SerialName("partial_json") val partialJson: String
    ) : DeltaContent()

    /**
     * 思考增量（thinking_delta）
     * 仅在 extended thinking 模式下出现，传输 Claude 的思考过程
     *
     * @param thinking 思考内容的片段
     */
    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(
        /** 思考内容片段，需追加到 Thinking 块的 thinking 字段 */
        val thinking: String
    ) : DeltaContent()

    /**
     * 签名增量（signature_delta）
     * 仅在 extended thinking 模式下出现，用于验证思考块完整性
     * 必须原样保存并在后续请求中回传
     *
     * @param signature 思考块的签名字符串
     */
    @Serializable
    @SerialName("signature_delta")
    data class SignatureDelta(
        /** 思考块签名，回传时必须完整携带 */
        val signature: String
    ) : DeltaContent()
}

// ============================================================
// 可用模型枚举
// ============================================================

/**
 * Anthropic 可用模型
 * 按能力由强到弱排列，越靠前的模型能力越强但成本也越高。
 * 建议根据任务复杂度选择合适的模型以平衡性能和成本。
 */
enum class ClaudeModel(
    /** API 中使用的完整模型 ID */
    val modelId: String,
    /** 用户界面展示的名称 */
    val displayName: String
) {
    /** 最强模型，适合复杂的 Agent 任务和多步推理 */
    CLAUDE_OPUS_4_6("claude-opus-4-6-20260401", "Claude Opus 4.6（最强）"),
    /** 平衡模型，性能与成本的最佳平衡点 */
    CLAUDE_SONNET_4_5("claude-sonnet-4-5-20260401", "Claude Sonnet 4.5（平衡）"),
    /** 快速低成本模型，适合简单任务和高频请求 */
    CLAUDE_HAIKU_4("claude-haiku-4-20260401", "Claude Haiku 4（快速/低成本）");

    companion object {
        /** 默认使用最强模型，确保 Agent 任务的成功率 */
        val DEFAULT = CLAUDE_OPUS_4_6

        /** 从模型 ID 字符串查找枚举值 */
        fun fromModelId(modelId: String): ClaudeModel? = entries.find { it.modelId == modelId }
    }
}
