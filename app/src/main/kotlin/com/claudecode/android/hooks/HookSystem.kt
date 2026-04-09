package com.claudecode.android.hooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Hook 系统 — 拦截和修改 Agent 行为
 *
 * ## 什么是 Hook？
 *
 * Hook 是在特定时机（工具调用前/后、任务停止时等）触发的自定义脚本。
 * 用户可以通过配置 Hook 来：
 * - 记录所有工具调用日志（用于审计和调试）
 * - 阻止危险的文件操作（如阻止删除重要文件）
 * - 自动格式化保存的代码文件（如运行 ktlint）
 * - 在任务完成时发送通知（如推送消息到 Slack）
 * - 修改工具输入/输出（如自动替换敏感信息）
 *
 * ## Hook 系统的设计哲学
 *
 * Hook 系统的核心理念是"可扩展性而不牺牲安全性"。
 * 用户可以自定义 Claude 的行为，但每个 Hook 都是独立的进程，
 * 无法直接访问 Claude 的内部状态，只能通过标准输入输出通信。
 * 这与 Claude Code 桌面版的设计完全一致。
 *
 * ## Hook 类型（完全对标 Claude Code）
 *
 * - PreToolUse：工具调用前触发，可以阻断或修改工具输入
 * - PostToolUse：工具调用后触发，可以修改工具输出
 * - Stop：Agent 任务完成时触发
 * - SubagentStop：子 Agent 完成时触发
 * - Notification：发送通知时触发
 *
 * ## Hook 配置示例（存储在 App 设置中）
 *
 * ```json
 * {
 *   "hooks": [
 *     {
 *       "event": "PreToolUse",
 *       "matcher": { "toolName": "Bash" },
 *       "command": "echo '即将执行 Bash 命令'"
 *     },
 *     {
 *       "event": "PostToolUse",
 *       "matcher": { "toolNamePattern": "Write|Edit" },
 *       "command": "ktlint --format $TOOL_INPUT_FILE_PATH"
 *     }
 *   ]
 * }
 * ```
 *
 * ## 退出码协议（与 Claude Code 一致）
 *
 * - 0：Continue（正常继续，不做任何干预）
 * - 1：Block（仅对 PreToolUse 有效，阻断工具调用）
 * - 2：ModifyOutput（对 PostToolUse 有效，stdout 内容替换工具输出）
 * - 其他：忽略，视为 Continue
 */

// ==================== Hook 事件定义 ====================

/**
 * Hook 事件密封类 — 代表不同时机触发的事件
 *
 * 使用密封类（sealed class）而不是枚举，是因为每种事件携带不同的数据。
 * 密封类在 when 表达式中强制处理所有子类型，避免遗漏。
 */
sealed class HookEvent {

    /**
     * 工具调用前事件
     *
     * 触发时机：Claude 决定调用某个工具，但还没有实际执行时。
     *
     * 典型用途：
     * - 安全审计：记录所有即将执行的 Bash 命令
     * - 权限控制：阻止对特定文件/目录的写操作
     * - 参数验证：检查工具参数是否符合项目规范
     *
     * @param toolName 即将被调用的工具名称（如 "Bash", "Write", "Edit"）
     * @param toolInput 工具的输入参数（JSON 格式）
     * @param sessionId 当前 Agent 会话 ID，用于关联日志
     */
    data class PreToolUse(
        val toolName: String,
        val toolInput: JsonObject,
        val sessionId: String
    ) : HookEvent()

    /**
     * 工具调用后事件
     *
     * 触发时机：工具已经执行完成，但结果还没有被 Claude 处理时。
     *
     * 典型用途：
     * - 结果过滤：移除输出中的敏感信息（如密码、Token）
     * - 格式化：对写入的文件执行代码格式化
     * - 日志记录：记录工具执行结果以便后续分析
     *
     * @param toolName 刚刚被调用的工具名称
     * @param toolInput 工具的输入参数（与 PreToolUse 相同）
     * @param toolOutput 工具的执行结果（字符串格式）
     * @param sessionId 当前 Agent 会话 ID
     */
    data class PostToolUse(
        val toolName: String,
        val toolInput: JsonObject,
        val toolOutput: String,
        val sessionId: String
    ) : HookEvent()

    /**
     * 任务停止事件
     *
     * 触发时机：Agent 完成任务或用户主动中止时。
     *
     * 典型用途：
     * - 发送完成通知（如 Android 通知推送）
     * - 清理临时文件
     * - 记录任务完成时间和统计信息
     *
     * @param stopReason 停止原因（"end_turn"=正常完成, "user_interrupt"=用户中断）
     * @param sessionId 当前 Agent 会话 ID
     */
    data class Stop(
        val stopReason: String,
        val sessionId: String
    ) : HookEvent()

    /**
     * 子 Agent 停止事件
     *
     * 触发时机：在多 Agent 任务中，某个子 Agent 完成时触发。
     *
     * 典型用途：
     * - 监控多 Agent 工作流的进度
     * - 在子任务完成时触发下一步工作
     * - 汇总子 Agent 的执行报告
     *
     * @param subagentId 已完成的子 Agent 的标识符
     * @param stopReason 子 Agent 的停止原因
     * @param parentSessionId 父 Agent 会话 ID
     */
    data class SubagentStop(
        val subagentId: String,
        val stopReason: String,
        val parentSessionId: String
    ) : HookEvent()

    /**
     * 通知事件
     *
     * 触发时机：系统需要向用户发送通知时（如任务完成、需要用户输入时）。
     *
     * 典型用途：
     * - 自定义通知渠道（如替换默认通知为企业消息系统）
     * - 添加通知过滤（如只通知特定类型的消息）
     * - 通知内容格式化（如添加项目名前缀）
     *
     * @param message 要发送的通知内容
     * @param sessionId 当前 Agent 会话 ID
     */
    data class Notification(
        val message: String,
        val sessionId: String
    ) : HookEvent()
}

// ==================== Hook 执行结果 ====================

/**
 * Hook 执行结果密封类
 *
 * 通过退出码和 stdout 确定具体结果：
 * - 退出码 0 → Continue
 * - 退出码 1 → Block（仅 PreToolUse 可用）
 * - 退出码 2 → ModifyOutput（stdout 为新输出，仅 PostToolUse 可用）
 * - 退出码 3 → ModifyInput（stdout 为 JSON 格式的新输入，仅 PreToolUse 可用）
 */
sealed class HookResult {
    /** 正常继续，不干预工具调用或输出 */
    object Continue : HookResult()

    /**
     * 阻断工具调用（仅对 PreToolUse 有效）
     *
     * 当 Hook 返回此结果时，工具不会被执行，
     * Claude 会收到一条错误消息解释为什么工具被阻断。
     *
     * @param reason 阻断原因，会展示给用户和 Claude
     */
    data class Block(val reason: String) : HookResult()

    /**
     * 修改工具输入（仅对 PreToolUse 有效）
     *
     * 允许 Hook 在工具执行前修改参数，例如：
     * - 自动补全缺失的参数
     * - 将相对路径转换为绝对路径
     * - 替换敏感的配置值
     *
     * @param newInput 修改后的工具输入 JSON
     */
    data class ModifyInput(val newInput: JsonObject) : HookResult()

    /**
     * 修改工具输出（仅对 PostToolUse 有效）
     *
     * 允许 Hook 在 Claude 看到工具结果之前修改它，例如：
     * - 脱敏处理（移除 API 密钥、密码）
     * - 格式化输出（将 JSON 转换为可读的表格）
     * - 追加额外信息（如代码格式化的结果）
     *
     * @param newOutput 修改后的工具输出字符串
     */
    data class ModifyOutput(val newOutput: String) : HookResult()
}

// ==================== Hook 配置数据类 ====================

/**
 * Hook 配置 — 定义一个 Hook 的触发条件和执行命令
 *
 * 配置存储在 App 的 SharedPreferences 或本地配置文件中，
 * 用户可以通过设置界面管理（增删改查）Hook 列表。
 *
 * @param id 唯一标识符，用于管理和调试
 * @param event 事件类型名称（"PreToolUse", "PostToolUse", "Stop" 等）
 * @param matcher 匹配条件，null 表示匹配所有工具
 * @param command 要执行的 shell 命令，事件数据通过 stdin 传入
 * @param enabled 是否启用，false 时该 Hook 被跳过
 */
data class HookConfig(
    val id: String,
    val event: String,
    val matcher: HookMatcher?,
    val command: String,
    val enabled: Boolean = true
)

/**
 * Hook 匹配器 — 决定 Hook 是否应该被触发
 *
 * 支持两种匹配模式：
 * 1. 精确匹配（toolName）：只匹配特定工具名，效率高
 * 2. 正则匹配（toolNamePattern）：支持复杂的匹配规则
 *
 * 两个字段同时存在时，任一匹配即触发（OR 逻辑）。
 * 两个字段都为 null 时，匹配所有工具（用于全局 Hook）。
 */
data class HookMatcher(
    /** 精确匹配工具名，区分大小写（如 "Bash", "Write", "Edit"）*/
    val toolName: String? = null,

    /**
     * 正则表达式匹配工具名（如 "Write|Edit|Create" 匹配所有写操作工具）
     *
     * 使用 Java Regex 语法，常见模式：
     * - "Bash" 等同于 toolName = "Bash"
     * - "Write|Edit" 匹配写文件和编辑文件
     * - ".*File.*" 匹配所有名称包含 "File" 的工具
     */
    val toolNamePattern: String? = null
) {
    /**
     * 检查给定的工具名是否符合匹配条件
     *
     * 匹配逻辑：
     * 1. 如果 toolName 精确匹配，返回 true
     * 2. 如果 toolNamePattern 正则匹配，返回 true
     * 3. 如果两者都为 null（无条件匹配），返回 true
     * 4. 否则返回 false
     *
     * @param toolName 要检查的工具名
     * @return 是否匹配
     */
    fun matches(toolName: String): Boolean {
        // 如果没有任何匹配条件，则匹配所有工具（全局 Hook）
        if (this.toolName == null && this.toolNamePattern == null) {
            return true
        }

        // 精确匹配优先（效率更高）
        if (this.toolName != null && this.toolName == toolName) {
            return true
        }

        // 正则匹配
        if (this.toolNamePattern != null) {
            return try {
                Regex(this.toolNamePattern).containsMatchIn(toolName)
            } catch (e: Exception) {
                // 正则表达式语法错误时，记录日志但不崩溃
                android.util.Log.w(TAG, "Hook 匹配器正则语法错误: '${this.toolNamePattern}'", e)
                false
            }
        }

        return false
    }

    companion object {
        private const val TAG = "HookMatcher"
    }
}

// ==================== Hook 执行结果数据类 ====================

/**
 * Hook 命令执行结果
 *
 * 封装 shell 命令的执行结果，供 HookSystem 解析成 HookResult。
 *
 * @param exitCode shell 命令的退出码（0=成功，非0=失败或特殊行为）
 * @param stdout 标准输出内容（用于 ModifyOutput 时的新内容）
 * @param stderr 标准错误内容（用于错误日志）
 */
data class HookExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

// ==================== Hook 系统主类 ====================

/**
 * Hook 系统主类
 *
 * 管理 Hook 配置的加载和触发。采用单例模式（通过依赖注入管理），
 * 确保整个 App 中只有一个 Hook 配置状态。
 */
class HookSystem {

    companion object {
        private const val TAG = "HookSystem"

        /** Hook 命令执行超时时间（毫秒）*/
        private const val HOOK_TIMEOUT_MS = 10_000L

        /** Hook 退出码：正常继续 */
        private const val EXIT_CONTINUE = 0

        /** Hook 退出码：阻断工具调用（仅 PreToolUse）*/
        private const val EXIT_BLOCK = 1

        /** Hook 退出码：修改输出（仅 PostToolUse），或修改输入（PreToolUse）*/
        private const val EXIT_MODIFY = 2
    }

    /** 已加载的 Hook 配置列表，按配置顺序排列 */
    private val hooks = mutableListOf<HookConfig>()

    /**
     * 加载 Hook 配置列表
     *
     * 清除旧配置并加载新配置。通常在 App 启动或用户修改设置时调用。
     * 只加载 enabled=true 的 Hook，disabled 的 Hook 直接过滤掉。
     *
     * @param configs 新的 Hook 配置列表
     */
    fun loadHooks(configs: List<HookConfig>) {
        hooks.clear()
        // 只保留启用的 Hook，提高运行时效率
        val enabledHooks = configs.filter { it.enabled }
        hooks.addAll(enabledHooks)
        android.util.Log.d(TAG, "已加载 ${hooks.size} 个启用的 Hook（共 ${configs.size} 个）")
    }

    /**
     * 触发 Hook 并返回最终结果
     *
     * 执行流程：
     * 1. 根据事件类型找到所有匹配的 Hook 配置
     * 2. 将事件数据序列化为 JSON，通过 stdin 传给 Hook 命令
     * 3. 依次执行每个匹配的 Hook 命令
     * 4. 根据退出码解析结果：
     *    - 0 → Continue（继续执行下一个 Hook）
     *    - 1 → Block（立即停止，阻断工具调用）
     *    - 2 → Modify（立即停止，修改输入或输出）
     * 5. 如果所有 Hook 都返回 Continue，最终结果为 Continue
     *
     * 为什么一旦返回非 Continue 就立即停止？
     * 阻断或修改是明确的意图，后续 Hook 无需再执行。
     * 这也避免了多个 Hook 互相干扰的问题。
     *
     * @param event 要触发的 Hook 事件
     * @return Hook 链的最终执行结果
     */
    suspend fun fire(event: HookEvent): HookResult = withContext(Dispatchers.IO) {
        // 获取事件类型名称，用于筛选匹配的 Hook
        val eventTypeName = getEventTypeName(event)

        // 筛选出匹配当前事件的所有 Hook
        val matchingHooks = hooks.filter { hook ->
            if (hook.event != eventTypeName) return@filter false

            // 对于有工具名的事件，检查 matcher 是否匹配
            val toolName = when (event) {
                is HookEvent.PreToolUse -> event.toolName
                is HookEvent.PostToolUse -> event.toolName
                else -> null  // Stop/Notification 等事件不需要匹配工具名
            }

            if (toolName != null && hook.matcher != null) {
                hook.matcher.matches(toolName)
            } else {
                // 没有 matcher 或事件不涉及工具名，则匹配所有
                true
            }
        }

        if (matchingHooks.isEmpty()) {
            android.util.Log.d(TAG, "事件 $eventTypeName 没有匹配的 Hook，跳过")
            return@withContext HookResult.Continue
        }

        android.util.Log.d(TAG, "事件 $eventTypeName 找到 ${matchingHooks.size} 个匹配 Hook")

        // 将事件序列化为 JSON，用于传递给 Hook 命令
        val eventJson = eventToJson(event)

        // 依次执行每个匹配的 Hook
        for (hookConfig in matchingHooks) {
            android.util.Log.d(TAG, "执行 Hook: ${hookConfig.id} (${hookConfig.command})")

            val execResult = try {
                executeHookCommand(hookConfig.command, eventJson)
            } catch (e: Exception) {
                // Hook 执行异常时，记录错误但不中断主流程
                // 这是防御性策略：Hook 不应该因为自身错误而破坏正常功能
                android.util.Log.e(TAG, "Hook ${hookConfig.id} 执行异常: ${e.message}", e)
                continue
            }

            // 记录 stderr 内容（通常是错误信息或调试日志）
            if (execResult.stderr.isNotBlank()) {
                android.util.Log.w(TAG, "Hook ${hookConfig.id} stderr: ${execResult.stderr}")
            }

            // 根据退出码解析结果
            val result = parseHookResult(event, execResult)

            // 如果不是 Continue，立即返回（不再执行后续 Hook）
            if (result !is HookResult.Continue) {
                android.util.Log.i(TAG, "Hook ${hookConfig.id} 返回非 Continue 结果: $result")
                return@withContext result
            }
        }

        // 所有 Hook 都返回了 Continue
        HookResult.Continue
    }

    /**
     * 执行单个 Hook 命令
     *
     * 执行方式：
     * - 使用 Android 的 Runtime.exec() 运行 shell 命令
     * - 通过 stdin 传入 JSON 格式的事件数据（与 Claude Code 一致）
     * - 设置超时防止 Hook 命令挂起阻塞整个 Agent
     *
     * 为什么通过 stdin 而不是环境变量传递数据？
     * 1. stdin 可以传递任意大小的数据（环境变量有长度限制）
     * 2. 复杂的 JSON 数据通过环境变量传递容易出现转义问题
     * 3. 这与 Claude Code 桌面版的协议一致，允许共用 Hook 脚本
     *
     * @param command 要执行的 shell 命令
     * @param eventJson 事件数据的 JSON 字符串，通过 stdin 传入
     * @return 命令执行结果（退出码、stdout、stderr）
     */
    private suspend fun executeHookCommand(
        command: String,
        eventJson: String
    ): HookExecutionResult = withContext(Dispatchers.IO) {
        val process = try {
            // 使用 sh -c 执行命令，支持管道、重定向等 shell 特性
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "无法启动 Hook 进程: ${e.message}", e)
            throw e
        }

        // 通过 stdin 传入事件 JSON 数据
        try {
            process.outputStream.use { stdin ->
                stdin.write(eventJson.toByteArray(Charsets.UTF_8))
                stdin.flush()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "向 Hook stdin 写入失败: ${e.message}")
            // stdin 写入失败不一定是致命错误，继续等待进程结果
        }

        // 读取 stdout 和 stderr
        val stdout = try {
            process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "读取 Hook stdout 失败: ${e.message}")
            ""
        }

        val stderr = try {
            process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "读取 Hook stderr 失败: ${e.message}")
            ""
        }

        // 等待进程完成，设置超时防止挂起
        val finished = try {
            // 使用 Java 的 waitFor 并手动实现超时
            var waited = 0L
            val interval = 100L
            while (waited < HOOK_TIMEOUT_MS) {
                try {
                    process.exitValue()  // 如果进程还在运行，会抛出 IllegalThreadStateException
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(interval)
                    waited += interval
                }
            }
            waited < HOOK_TIMEOUT_MS
        } catch (e: Exception) {
            false
        }

        if (!finished) {
            android.util.Log.w(TAG, "Hook 命令超时（${HOOK_TIMEOUT_MS}ms），强制终止")
            process.destroy()
            return@withContext HookExecutionResult(
                exitCode = -1,
                stdout = stdout,
                stderr = "Hook 执行超时（${HOOK_TIMEOUT_MS}ms）"
            )
        }

        val exitCode = process.exitValue()
        android.util.Log.d(TAG, "Hook 命令退出码: $exitCode, stdout 长度: ${stdout.length}")

        HookExecutionResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    /**
     * 将 HookEvent 转换为 JSON 字符串，通过 stdin 传给 Hook 命令
     *
     * JSON 结构设计原则：
     * 1. 包含事件类型（type 字段），让 Hook 脚本知道处理哪种事件
     * 2. 所有相关数据都扁平化到顶层，方便 jq 等工具解析
     * 3. 字段名使用 camelCase，与 Claude Code 的协议保持一致
     *
     * 示例输出（PreToolUse）：
     * ```json
     * {
     *   "type": "PreToolUse",
     *   "sessionId": "sess_abc123",
     *   "toolName": "Bash",
     *   "toolInput": { "command": "ls -la" }
     * }
     * ```
     *
     * @param event 要序列化的 Hook 事件
     * @return JSON 字符串
     */
    private fun eventToJson(event: HookEvent): String {
        return when (event) {
            is HookEvent.PreToolUse -> buildString {
                append("{")
                append("\"type\":\"PreToolUse\",")
                append("\"sessionId\":\"${event.sessionId}\",")
                append("\"toolName\":\"${event.toolName}\",")
                append("\"toolInput\":${event.toolInput}")
                append("}")
            }

            is HookEvent.PostToolUse -> buildString {
                // toolOutput 中可能包含双引号，需要转义
                val escapedOutput = event.toolOutput
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")

                append("{")
                append("\"type\":\"PostToolUse\",")
                append("\"sessionId\":\"${event.sessionId}\",")
                append("\"toolName\":\"${event.toolName}\",")
                append("\"toolInput\":${event.toolInput},")
                append("\"toolOutput\":\"$escapedOutput\"")
                append("}")
            }

            is HookEvent.Stop -> buildString {
                append("{")
                append("\"type\":\"Stop\",")
                append("\"sessionId\":\"${event.sessionId}\",")
                append("\"stopReason\":\"${event.stopReason}\"")
                append("}")
            }

            is HookEvent.SubagentStop -> buildString {
                append("{")
                append("\"type\":\"SubagentStop\",")
                append("\"parentSessionId\":\"${event.parentSessionId}\",")
                append("\"subagentId\":\"${event.subagentId}\",")
                append("\"stopReason\":\"${event.stopReason}\"")
                append("}")
            }

            is HookEvent.Notification -> buildString {
                val escapedMessage = event.message
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")

                append("{")
                append("\"type\":\"Notification\",")
                append("\"sessionId\":\"${event.sessionId}\",")
                append("\"message\":\"$escapedMessage\"")
                append("}")
            }
        }
    }

    /**
     * 根据退出码和事件类型解析 HookResult
     *
     * 退出码协议（与 Claude Code 桌面版一致）：
     * - 0：Continue（无论事件类型，都继续）
     * - 1：Block（PreToolUse 阻断工具调用；其他事件忽略，视为 Continue）
     * - 2：
     *   - PreToolUse：ModifyInput（stdout 为 JSON 格式的新输入参数）
     *   - PostToolUse：ModifyOutput（stdout 为新的工具输出内容）
     *   - 其他事件：忽略，视为 Continue
     *
     * @param event 触发的事件（用于区分语义）
     * @param execResult Hook 命令的执行结果
     * @return 解析后的 HookResult
     */
    private fun parseHookResult(event: HookEvent, execResult: HookExecutionResult): HookResult {
        return when (execResult.exitCode) {
            EXIT_CONTINUE -> HookResult.Continue

            EXIT_BLOCK -> {
                // 退出码 1：阻断（只对 PreToolUse 有意义）
                if (event is HookEvent.PreToolUse) {
                    // 从 stdout 中读取阻断原因（如果有的话）
                    val reason = execResult.stdout.trim().ifBlank {
                        "Hook '${event.toolName}' 调用被用户定义的 Hook 阻断"
                    }
                    HookResult.Block(reason)
                } else {
                    // 非 PreToolUse 事件收到退出码 1，视为 Continue（避免误阻断）
                    android.util.Log.w(TAG, "非 PreToolUse 事件收到退出码 1，忽略阻断请求")
                    HookResult.Continue
                }
            }

            EXIT_MODIFY -> {
                when (event) {
                    is HookEvent.PreToolUse -> {
                        // PreToolUse 退出码 2：修改工具输入
                        // stdout 应该是新的 JSON 格式工具参数
                        val newInputJson = execResult.stdout.trim()
                        if (newInputJson.isEmpty()) {
                            android.util.Log.w(TAG, "Hook 返回退出码 2 但 stdout 为空，视为 Continue")
                            return HookResult.Continue
                        }
                        try {
                            // 解析 stdout 中的 JSON 作为新的工具输入
                            // 这里使用简单的字符串包装，实际项目中应使用 kotlinx.serialization
                            val parsedInput = parseJsonObject(newInputJson)
                            HookResult.ModifyInput(parsedInput)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Hook 返回的修改输入 JSON 解析失败: ${e.message}")
                            HookResult.Continue
                        }
                    }

                    is HookEvent.PostToolUse -> {
                        // PostToolUse 退出码 2：修改工具输出
                        // stdout 就是新的工具输出内容（纯文本，非 JSON）
                        val newOutput = execResult.stdout
                        if (newOutput.isEmpty()) {
                            android.util.Log.w(TAG, "Hook 返回退出码 2 但 stdout 为空，视为 Continue")
                            HookResult.Continue
                        } else {
                            HookResult.ModifyOutput(newOutput)
                        }
                    }

                    else -> {
                        // 其他事件类型不支持修改操作
                        android.util.Log.w(TAG, "不支持修改的事件类型收到退出码 2，忽略")
                        HookResult.Continue
                    }
                }
            }

            else -> {
                // 其他退出码（如 127=命令未找到，-1=超时）：视为 Continue，但记录警告
                android.util.Log.w(TAG, "Hook 返回未知退出码 ${execResult.exitCode}，视为 Continue")
                HookResult.Continue
            }
        }
    }

    /**
     * 获取事件类型的字符串名称，用于与 HookConfig.event 匹配
     *
     * 返回的字符串与 Claude Code 桌面版的事件类型名称完全一致，
     * 确保用户可以复用为桌面版编写的 Hook 配置。
     *
     * @param event 要获取类型名的事件
     * @return 事件类型名称字符串
     */
    private fun getEventTypeName(event: HookEvent): String {
        return when (event) {
            is HookEvent.PreToolUse -> "PreToolUse"
            is HookEvent.PostToolUse -> "PostToolUse"
            is HookEvent.Stop -> "Stop"
            is HookEvent.SubagentStop -> "SubagentStop"
            is HookEvent.Notification -> "Notification"
        }
    }

    /**
     * 简单的 JSON 对象解析辅助方法
     *
     * 这是一个轻量级实现，将 JSON 字符串包装为 JsonObject。
     * 在实际项目中，建议使用 kotlinx.serialization 或 Gson 进行完整的 JSON 解析。
     *
     * 为什么不直接用 kotlinx.serialization？
     * 因为 JsonObject 的构建 API 在不同版本的 kotlinx.serialization 中有差异，
     * 这里使用简单包装确保编译兼容性。
     *
     * @param jsonString JSON 格式的字符串
     * @return 解析后的 JsonObject
     * @throws IllegalArgumentException 如果字符串不是有效的 JSON 对象
     */
    private fun parseJsonObject(jsonString: String): JsonObject {
        // 使用 kotlinx.serialization 的 Json 解析器
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(jsonString).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("无效的 JSON 对象: $jsonString", e)
        }
    }
}
