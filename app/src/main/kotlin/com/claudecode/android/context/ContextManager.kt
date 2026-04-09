package com.claudecode.android.context

import com.claudecode.android.api.AnthropicClient
import com.claudecode.android.api.Message
import com.claudecode.android.agent.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 上下文管理器 — 防止超出 Claude 的 200K Token 上下文窗口
 *
 * ## 为什么需要上下文压缩？
 *
 * Claude 的上下文窗口是有限的（目前最大 200K tokens）。
 * 在长时间运行的 Agent 任务中，消息历史会不断增长，
 * 当接近上限时，需要对历史消息进行压缩，保留最重要的信息。
 *
 * 如果不压缩，有两种糟糕的结果：
 * 1. API 调用因超出上下文窗口而报错，任务中断
 * 2. Claude 无法看到所有历史，导致决策质量下降
 *
 * 压缩的目标是：在 token 数量减少的同时，尽可能保留最重要的信息。
 *
 * ## 压缩策略（按优先级）
 *
 * 1. 工具结果截断：长文件读取结果只保留前 N 个字符
 *    - 适用场景：读取了大文件，但大部分内容对后续决策无用
 *    - 副作用最小，不需要额外 API 调用，延迟极低
 *
 * 2. 中间消息摘要：用 Claude 对较早的对话做摘要，替换原始内容
 *    - 适用场景：对话历史很长，但早期内容已不再需要细节
 *    - 需要额外一次 API 调用，但压缩率高
 *
 * 3. 保留最近 N 条：始终保留最近的 N 条完整消息
 *    - 确保 Claude 始终能看到最新的上下文，保证当前任务的连贯性
 *
 * ## Token 阈值
 *
 * - 85%（170K）：发出警告，提示用户考虑开启新会话
 * - 90%（180K）：触发轻量压缩（截断工具结果），影响最小
 * - 95%（190K）：触发重度压缩（生成摘要），可能丢失部分细节
 *
 * ## 为什么选这些阈值？
 *
 * 留有 5%~10% 的余量是为了给 Claude 的回复留空间。
 * 如果等到 99% 才压缩，压缩过程本身（需要 API 调用）可能导致超出限制。
 * 对标 Claude Code 的实际行为：在 85% 时开始发出提示，90% 时自动干预。
 */
class ContextManager(
    private val apiClient: AnthropicClient
) {

    companion object {
        /** Claude 当前最大上下文窗口大小（token 数）*/
        const val CONTEXT_WINDOW_LIMIT = 200_000

        /** 警告阈值：使用量超过 85% 时向用户发出提示 */
        const val WARNING_THRESHOLD = 0.85f

        /** 轻量压缩阈值：使用量超过 90% 时触发工具结果截断 */
        const val COMPRESS_THRESHOLD = 0.90f

        /** 重度压缩阈值：使用量超过 95% 时触发摘要生成 */
        const val HEAVY_COMPRESS_THRESHOLD = 0.95f

        /** 始终保留最近 N 条消息的完整内容，不参与压缩 */
        const val KEEP_LAST_N_MESSAGES = 10

        /**
         * 工具结果最大保留字符数（约 2500 tokens）
         *
         * 为什么是 10000 字符？
         * 经验值：大多数有用的工具输出在 10KB 以内。
         * 超出部分通常是重复的、大量的文件内容，压缩后信息损失最小。
         */
        const val MAX_TOOL_RESULT_CHARS = 10_000

        /** 日志标签 */
        private const val TAG = "ContextManager"

        /**
         * Token 估算参数
         *
         * 这是一个粗略估算，实际 token 数取决于 Tokenizer（BPE）。
         * 英文：平均 4 字符 ≈ 1 token（来自 OpenAI 的经验数据，Claude 类似）
         * 中文：每个汉字通常是 1-2 tokens，取保守值 2 字符 ≈ 1 token
         *
         * 使用粗略估算而非精确计算是因为：
         * 1. 精确计算需要本地 Tokenizer，增加依赖和包体积
         * 2. 粗略估算足以触发阈值判断，误差在可接受范围内（±10%）
         */
        private const val CHARS_PER_TOKEN_ENGLISH = 4
        private const val CHARS_PER_TOKEN_CHINESE = 2
    }

    /**
     * 估算消息列表的 token 数量
     *
     * 使用混合策略估算：
     * - 检测内容中汉字的比例
     * - 根据汉字比例加权计算 token 数
     *
     * 这比简单地"字符数 / 4"更准确，因为中文 token 密度更高。
     *
     * @param messages 要估算的消息列表
     * @return 估算的 token 总数
     */
    fun estimateTokenCount(messages: List<Message>): Int {
        var totalTokens = 0

        for (message in messages) {
            // 角色标识符本身也占用少量 token（约 4 个）
            totalTokens += 4

            val content = message.content
            if (content.isNullOrEmpty()) continue

            // 统计汉字数量，用于判断是中英文混合还是纯英文
            val chineseCharCount = content.count { char ->
                // Unicode 基本汉字区间：\u4E00-\u9FFF
                // 扩展汉字 A 区：\u3400-\u4DBF
                char in '\u4E00'..'\u9FFF' || char in '\u3400'..'\u4DBF'
            }

            val chineseRatio = chineseCharCount.toFloat() / content.length.coerceAtLeast(1)

            // 加权计算：汉字用 2字符/token，非汉字用 4字符/token
            val effectiveCharsPerToken = if (chineseRatio > 0.3f) {
                // 中文为主的内容
                CHARS_PER_TOKEN_CHINESE.toFloat()
            } else {
                // 英文或代码为主的内容
                CHARS_PER_TOKEN_ENGLISH.toFloat()
            }

            totalTokens += (content.length / effectiveCharsPerToken).toInt()
        }

        android.util.Log.d(TAG, "估算 token 数: $totalTokens (消息数: ${messages.size})")
        return totalTokens
    }

    /**
     * 截断过长的工具结果（轻量压缩）
     *
     * 适用场景：
     * - 读取了大型文件（如日志文件、生成的代码文件）
     * - 执行命令输出了大量内容（如 grep 结果、构建日志）
     *
     * 截断策略：
     * - 保留前 MAX_TOOL_RESULT_CHARS 个字符
     * - 在截断处添加说明，告知 Claude 内容已被截断
     * - 不截断最近 KEEP_LAST_N_MESSAGES 条消息（保证当前上下文完整）
     *
     * 为什么保留开头而不是结尾？
     * 工具结果的重要信息通常在开头（如文件的头部声明、命令的主要输出）。
     * 结尾往往是大量重复或次要的内容（如文件末尾的空行、日志的详细堆栈）。
     *
     * @param messages 原始消息列表
     * @return 截断工具结果后的新消息列表（不修改原列表）
     */
    fun truncateLongToolResults(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return messages

        // 计算保护范围：最近 N 条消息不参与截断
        val protectFromIndex = (messages.size - KEEP_LAST_N_MESSAGES).coerceAtLeast(0)

        return messages.mapIndexed { index, message ->
            // 最近的消息不截断，保证当前工作上下文完整
            if (index >= protectFromIndex) {
                return@mapIndexed message
            }

            // 只截断工具角色（tool_result）的消息
            // 用户消息和 Claude 的回复通常不会特别长
            if (message.role != "tool" && message.role != "tool_result") {
                return@mapIndexed message
            }

            val content = message.content ?: return@mapIndexed message

            // 内容不超过限制，无需截断
            if (content.length <= MAX_TOOL_RESULT_CHARS) {
                return@mapIndexed message
            }

            // 执行截断，并附加说明
            val truncatedContent = buildString {
                append(content.substring(0, MAX_TOOL_RESULT_CHARS))
                append("\n\n[... 内容已截断，原始长度 ${content.length} 字符，")
                append("仅保留前 $MAX_TOOL_RESULT_CHARS 字符以节省上下文空间 ...]")
            }

            android.util.Log.d(TAG, "截断工具结果: ${content.length} -> $MAX_TOOL_RESULT_CHARS 字符")

            // 创建新的 Message 对象（保持不可变原则）
            message.copy(content = truncatedContent)
        }
    }

    /**
     * 生成摘要并替换旧消息（重度压缩）
     *
     * 这是最后手段的压缩策略，适用于轻量压缩已无法满足需求的情况。
     *
     * 压缩流程：
     * 1. 保留最近 KEEP_LAST_N_MESSAGES 条消息不参与摘要
     * 2. 对其余的较早消息调用 Claude API 生成摘要
     * 3. 将摘要作为一条系统消息插入到保留消息之前
     * 4. 删除被摘要替代的旧消息
     *
     * 权衡（Tradeoffs）：
     * - 优点：大幅减少 token 使用量（通常减少 60-80%）
     * - 缺点：需要额外 API 调用（增加延迟和成本）；细节信息不可逆丢失
     *
     * 为什么在 AgentSession 而非 List<Message> 上操作？
     * AgentSession 持有对消息列表的可变引用，压缩结果需要直接更新 session，
     * 避免调用方需要重新赋值的样板代码。
     *
     * @param session 当前 Agent 会话，压缩后直接更新其消息历史
     */
    suspend fun compress(session: AgentSession) = withContext(Dispatchers.IO) {
        val messages = session.messageHistory
        if (messages.size <= KEEP_LAST_N_MESSAGES) {
            android.util.Log.d(TAG, "消息数量不足 ${KEEP_LAST_N_MESSAGES}，无需压缩")
            return@withContext
        }

        // 分割消息：保留最近 N 条，对其余消息生成摘要
        val keepStartIndex = messages.size - KEEP_LAST_N_MESSAGES
        val messagesToSummarize = messages.subList(0, keepStartIndex)
        val recentMessages = messages.subList(keepStartIndex, messages.size)

        android.util.Log.d(TAG, "开始重度压缩：摘要 ${messagesToSummarize.size} 条消息，" +
                "保留最近 ${recentMessages.size} 条")

        // 调用 Claude 生成摘要
        val summary = try {
            summarizeMessages(messagesToSummarize)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "生成摘要失败，放弃压缩: ${e.message}", e)
            return@withContext
        }

        // 构建摘要消息，作为对话历史的起点
        // 使用 system 角色标记为摘要，让 Claude 知道这是压缩后的历史
        val summaryMessage = Message(
            role = "system",
            content = buildString {
                append("## 历史对话摘要\n\n")
                append("以下是之前对话的摘要（共 ${messagesToSummarize.size} 条消息被压缩）：\n\n")
                append(summary)
                append("\n\n---\n以下是最近的完整对话记录：")
            }
        )

        // 更新 session 的消息历史：摘要消息 + 保留的最近消息
        session.replaceMessageHistory(listOf(summaryMessage) + recentMessages)

        val newTokenCount = estimateTokenCount(session.messageHistory)
        android.util.Log.d(TAG, "压缩完成，新 token 估算: $newTokenCount")
    }

    /**
     * 调用 Claude 对消息列表生成中文摘要
     *
     * 摘要提示词设计原则（对标 Claude Code 的压缩行为）：
     * - 保留所有重要决策和结论
     * - 保留已完成的任务状态
     * - 保留发现的关键错误和解决方案
     * - 丢弃中间的探索过程（除非结论与过程密切相关）
     * - 用中文生成摘要，与 App 整体语言一致
     *
     * 为什么要让 Claude 做摘要而不是用本地算法？
     * 本地算法（如 TF-IDF、TextRank）对代码和技术讨论的理解有限，
     * 会丢失语义信息。让 Claude 做摘要能更好地保留任务相关的关键信息。
     *
     * @param messages 需要摘要的消息列表
     * @return Claude 生成的摘要文本
     */
    private suspend fun summarizeMessages(messages: List<Message>): String = withContext(Dispatchers.IO) {
        // 将消息列表转换为可读的文本格式，用于摘要请求
        val conversationText = messages.joinToString("\n\n") { msg ->
            val roleLabel = when (msg.role) {
                "user" -> "用户"
                "assistant" -> "Claude"
                "tool" -> "工具结果"
                "tool_result" -> "工具结果"
                else -> msg.role
            }
            "[$roleLabel]: ${msg.content ?: "(空内容)"}"
        }

        // 构建摘要请求的提示词
        val summaryPrompt = """
请对以下对话历史进行简洁的中文摘要。

摘要要求：
1. **保留关键信息**：所有重要决策、已完成的操作、发现的错误及其解决方案
2. **保留任务状态**：当前任务的进度和尚未完成的事项
3. **保留重要代码**：如果有关键的代码片段或文件路径，请保留
4. **丢弃冗余内容**：中间探索过程、重复的确认消息、已不相关的信息
5. **使用结构化格式**：用 Markdown 列表和标题组织内容，方便快速理解

对话历史如下：

$conversationText

请生成摘要：
""".trimIndent()

        // 调用 API 获取摘要
        // 使用较低的 temperature 确保摘要的确定性和准确性
        val summaryMessage = Message(role = "user", content = summaryPrompt)
        val response = apiClient.sendMessage(
            messages = listOf(summaryMessage),
            maxTokens = 2000  // 摘要不需要太长
        )

        response.content ?: "（摘要生成失败，历史对话已压缩）"
    }

    /**
     * 检查当前上下文使用率并决定是否需要压缩
     *
     * 这是一个便捷方法，供 AgentSession 在每次 API 调用前调用。
     * 根据当前 token 使用率返回建议的行动。
     *
     * @param messages 当前消息列表
     * @return 压缩建议（NONE/WARN/LIGHT/HEAVY）
     */
    fun checkCompressionNeeded(messages: List<Message>): CompressionAdvice {
        val tokenCount = estimateTokenCount(messages)
        val usageRatio = tokenCount.toFloat() / CONTEXT_WINDOW_LIMIT

        android.util.Log.d(TAG, "上下文使用率: ${(usageRatio * 100).toInt()}% ($tokenCount / $CONTEXT_WINDOW_LIMIT tokens)")

        return when {
            usageRatio >= HEAVY_COMPRESS_THRESHOLD -> {
                android.util.Log.w(TAG, "上下文使用率达到 ${(usageRatio * 100).toInt()}%，需要重度压缩！")
                CompressionAdvice.HEAVY_COMPRESS
            }
            usageRatio >= COMPRESS_THRESHOLD -> {
                android.util.Log.w(TAG, "上下文使用率达到 ${(usageRatio * 100).toInt()}%，触发轻量压缩")
                CompressionAdvice.LIGHT_COMPRESS
            }
            usageRatio >= WARNING_THRESHOLD -> {
                android.util.Log.i(TAG, "上下文使用率达到 ${(usageRatio * 100).toInt()}%，发出警告")
                CompressionAdvice.WARN
            }
            else -> CompressionAdvice.NONE
        }
    }
}

/**
 * 压缩建议枚举
 *
 * 将压缩决策与执行分离，允许调用方根据建议执行不同的压缩策略，
 * 也允许在测试中验证决策逻辑而不实际触发压缩。
 */
enum class CompressionAdvice {
    /** 无需任何操作，上下文使用率在安全范围内 */
    NONE,

    /** 发出警告，建议用户考虑开启新会话，但不强制压缩 */
    WARN,

    /**
     * 轻量压缩：截断过长的工具结果
     * - 执行速度快，无需额外 API 调用
     * - 信息损失最小，仅截断冗余的工具输出尾部
     */
    LIGHT_COMPRESS,

    /**
     * 重度压缩：生成摘要替换历史消息
     * - 执行较慢，需要一次额外 API 调用（通常 2-5 秒）
     * - 信息损失较大，历史细节不可恢复
     * - 是最后手段，避免 API 调用因超出上下文而失败
     */
    HEAVY_COMPRESS
}
