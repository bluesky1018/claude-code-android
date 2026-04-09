package com.claudecode.android.hooks

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hook 系统 — 像素级复刻真实 Claude Code 的 Hook 机制
 *
 * 真实 Claude Code 共有 23 个 Hook 事件（注：TeammateIdle 单独归入 Team 类），分 10 类：
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  类别           │ 事件名称                                               │
 * ├─────────────────┼─────────────────────────────────────────────────────── │
 * │ 生命周期 (7)    │ SessionStart, SessionEnd, Stop, StopFailure,          │
 * │                 │ Notification, ConfigChange, InstructionsLoaded        │
 * │ 工具调用 (5)    │ PreToolUse, PostToolUse, PostToolUseFailure,          │
 * │                 │ PermissionRequest, PermissionDenied                   │
 * │ 输入 (1)        │ UserPromptSubmit                                      │
 * │ 子 Agent (2)    │ SubagentStart, SubagentStop                           │
 * │ 任务管理 (2)    │ TaskCreated, TaskCompleted                            │
 * │ 文件系统 (2)    │ CwdChanged, FileChanged                               │
 * │ 压缩 (2)        │ PreCompact, PostCompact                               │
 * │ Worktree (2)    │ WorktreeCreate, WorktreeRemove                        │
 * │ Team (1)        │ TeammateIdle                                          │
 * │ MCP (2)         │ Elicitation, ElicitationResult                        │
 * └─────────────────┴───────────────────────────────────────────────────────┘
 *
 * Hook 输出协议（stdout JSON 结构化格式，与真实 Claude Code 完全一致）：
 * {
 *   "hookSpecificOutput": {
 *     "hookEventName": "PreToolUse",
 *     "permissionDecision": "deny",           // allow | deny | ask
 *     "permissionDecisionReason": "描述原因",
 *     "hookForceOutput": "可选覆盖输出",
 *     "suppressOutput": false,
 *     "modifiedInput": { ... },               // 仅 PreToolUse 有效
 *     "modifiedPrompt": "修改后的提示词"       // 仅 UserPromptSubmit 有效
 *   }
 * }
 *
 * 支持的 Hook 类型：
 *   1. "command" — Shell 命令，通过 stdin 接收 JSON，stdout 输出 JSON
 *   2. "http"    — HTTP POST 端点，请求体 JSON，响应体 JSON
 *   3. "prompt"  — Claude 自身作为 Hook 处理器（当前简化为直通）
 *
 * settings.json 配置格式（与真实 Claude Code 完全对标）：
 * {
 *   "hooks": {
 *     "PreToolUse": [
 *       {
 *         "matcher": "Bash",
 *         "if": "Bash(rm *)",
 *         "hooks": [
 *           { "type": "command", "command": "/path/to/script.sh" }
 *         ]
 *       }
 *     ],
 *     "PostToolUse": [
 *       {
 *         "hooks": [
 *           { "type": "http", "url": "https://my-server.com/hook", "timeout_ms": 5000 }
 *         ]
 *       }
 *     ]
 *   }
 * }
 *
 * if 条件语法：ToolName(pattern)，例如 "Bash(rm *)"、"Write(/etc/*)"
 *
 * 命令 Hook 的 stdin JSON 数据结构：
 * {
 *   "session_id": "abc123",
 *   "hook_event_name": "PreToolUse",
 *   "tool_name": "Write",
 *   "tool_input": { "file_path": "/workspace/auth.kt", "content": "..." },
 *   "tool_output": "...",
 *   "stop_reason": "end_turn",
 *   "cwd": "/workspace"
 * }
 */
class HookSystem(private val context: Context) {

    // ==================== Hook 配置存储 ====================

    /** 已注册的所有 Hook 事件配置（按注册顺序执行） */
    private val configs = mutableListOf<HookEventConfig>()

    /**
     * HTTP 客户端（复用单例，避免重复创建 Connection Pool）
     * 连接超时 10s，读取超时 30s（单个 HTTP Hook 超时由 hookDef.timeoutMs 控制）
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ==================== 23 个 Hook 事件枚举 ====================

    /**
     * Hook 事件名称枚举 — 完全对标真实 Claude Code 的全部 23 个事件
     *
     * 枚举值的命名与真实 Claude Code settings.json 中的键名完全一致，
     * 确保 [loadFromSettings] 能正确通过 [HookEventName.valueOf] 解析。
     */
    enum class HookEventName {
        // ── 生命周期类（7 个） ──────────────────────────────────────────────
        /** 会话开始或从历史恢复 */
        SessionStart,
        /** 会话正常终止 */
        SessionEnd,
        /** Claude 完成一轮响应（end_turn / stop_sequence 触发） */
        Stop,
        /** API 错误导致 turn 异常结束 */
        StopFailure,
        /** Claude Code 向用户发送通知（如 --no-notifications 模式下的桌面通知） */
        Notification,
        /** 配置文件发生变化（.claude.json、settings.json、CLAUDE.md 等） */
        ConfigChange,
        /** CLAUDE.md 或 rules/*.md 加载/重新加载完成 */
        InstructionsLoaded,

        // ── 工具调用类（5 个） ──────────────────────────────────────────────
        /** 工具调用前触发（可阻断执行或修改 tool_input） */
        PreToolUse,
        /** 工具调用成功后触发（可强制替换输出或抑制 UI 显示） */
        PostToolUse,
        /** 工具调用失败后触发（捕获 exception/error） */
        PostToolUseFailure,
        /** 权限确认对话框弹出时触发（DEFAULT 模式下用户点击前） */
        PermissionRequest,
        /** 自动模式分类器拒绝了某个工具调用 */
        PermissionDenied,

        // ── 输入类（1 个） ──────────────────────────────────────────────────
        /** 用户提交提示词时（Claude 处理之前），可修改提示内容 */
        UserPromptSubmit,

        // ── 子 Agent 类（2 个） ─────────────────────────────────────────────
        /** 子 Agent（Task/Agent Tool 创建的子进程）启动 */
        SubagentStart,
        /** 子 Agent 完成并返回结果 */
        SubagentStop,

        // ── 任务管理类（2 个） ──────────────────────────────────────────────
        /** TodoWrite 工具创建了新任务条目 */
        TaskCreated,
        /** 任务被标记为 completed 状态 */
        TaskCompleted,

        // ── 文件系统类（2 个） ──────────────────────────────────────────────
        /** 工作目录发生变化（通常由 cd 命令触发） */
        CwdChanged,
        /** 被监控的文件内容发生变化（FileWatcher 触发） */
        FileChanged,

        // ── 压缩类（2 个） ──────────────────────────────────────────────────
        /** 上下文压缩（Compact）开始之前 */
        PreCompact,
        /** 上下文压缩完成之后 */
        PostCompact,

        // ── Worktree 类（2 个） ─────────────────────────────────────────────
        /** 创建 git worktree（/worktree 命令） */
        WorktreeCreate,
        /** 移除 git worktree */
        WorktreeRemove,

        // ── Team 类（1 个） ─────────────────────────────────────────────────
        /** Agent 团队中某个成员进入空闲状态（等待下一个任务） */
        TeammateIdle,

        // ── MCP 类（2 个） ──────────────────────────────────────────────────
        /** MCP Server 发起 elicitation 请求，等待用户输入 */
        Elicitation,
        /** 用户已响应 MCP elicitation，结果回传给 Server */
        ElicitationResult
    }

    // ==================== Hook 事件数据类 ====================

    /**
     * Hook 事件基类（sealed class）
     *
     * 每个子类对应一个具体事件，携带该事件特有的数据字段。
     * [sessionId] 是所有事件共有的字段，用于关联会话上下文。
     */
    sealed class HookEvent {
        /** 当前会话的唯一标识符 */
        abstract val sessionId: String
        /** 事件名称（用于查询配置、构造 JSON） */
        abstract val eventName: HookEventName

        // ── 生命周期事件 ──────────────────────────────────────────────────

        /**
         * 会话开始事件
         * @param workingDirectory 会话初始工作目录（绝对路径）
         */
        data class SessionStart(
            override val sessionId: String,
            val workingDirectory: String
        ) : HookEvent() {
            override val eventName = HookEventName.SessionStart
        }

        /**
         * 会话结束事件
         * @param durationMs 会话持续时间（毫秒）
         */
        data class SessionEnd(
            override val sessionId: String,
            val durationMs: Long
        ) : HookEvent() {
            override val eventName = HookEventName.SessionEnd
        }

        /**
         * Claude 完成响应事件
         * @param stopReason API 返回的停止原因（end_turn / stop_sequence / max_tokens 等）
         */
        data class Stop(
            override val sessionId: String,
            val stopReason: String
        ) : HookEvent() {
            override val eventName = HookEventName.Stop
        }

        /**
         * API 错误导致 turn 结束事件
         * @param error 错误信息（来自 API 或网络层）
         */
        data class StopFailure(
            override val sessionId: String,
            val error: String
        ) : HookEvent() {
            override val eventName = HookEventName.StopFailure
        }

        /**
         * 系统通知事件
         * @param message 通知消息内容
         */
        data class Notification(
            override val sessionId: String,
            val message: String
        ) : HookEvent() {
            override val eventName = HookEventName.Notification
        }

        /**
         * 配置文件变化事件
         * @param changedFile 发生变化的配置文件路径
         */
        data class ConfigChange(
            override val sessionId: String,
            val changedFile: String
        ) : HookEvent() {
            override val eventName = HookEventName.ConfigChange
        }

        /**
         * 指令文件加载完成事件
         * @param files 加载的文件路径列表（CLAUDE.md 等）
         */
        data class InstructionsLoaded(
            override val sessionId: String,
            val files: List<String>
        ) : HookEvent() {
            override val eventName = HookEventName.InstructionsLoaded
        }

        // ── 工具调用事件 ──────────────────────────────────────────────────

        /**
         * 工具调用前事件（最重要的 Hook 事件之一）
         * 可通过返回 [HookResult.Deny] 阻断执行，或返回 [HookResult.ModifyInput] 修改输入
         *
         * @param toolName   工具名称（如 "Bash"、"Write"）
         * @param toolInput  工具输入参数（JSON 对象）
         * @param cwd        当前工作目录
         */
        data class PreToolUse(
            override val sessionId: String,
            val toolName: String,
            val toolInput: JsonObject,
            val cwd: String
        ) : HookEvent() {
            override val eventName = HookEventName.PreToolUse
        }

        /**
         * 工具调用成功后事件
         * 可通过返回 [HookResult.ForceOutput] 替换输出，或 [HookResult.SuppressOutput] 隐藏结果
         *
         * @param toolName   工具名称
         * @param toolInput  工具输入参数
         * @param toolOutput 工具执行输出（字符串）
         * @param durationMs 工具执行耗时（毫秒）
         * @param cwd        当前工作目录
         */
        data class PostToolUse(
            override val sessionId: String,
            val toolName: String,
            val toolInput: JsonObject,
            val toolOutput: String,
            val durationMs: Long,
            val cwd: String
        ) : HookEvent() {
            override val eventName = HookEventName.PostToolUse
        }

        /**
         * 工具调用失败后事件
         * @param toolName  工具名称
         * @param toolInput 工具输入参数
         * @param error     失败原因（异常信息）
         */
        data class PostToolUseFailure(
            override val sessionId: String,
            val toolName: String,
            val toolInput: JsonObject,
            val error: String
        ) : HookEvent() {
            override val eventName = HookEventName.PostToolUseFailure
        }

        /**
         * 权限确认请求事件（DEFAULT 模式弹框前）
         * @param toolName   请求权限的工具
         * @param toolInput  工具输入参数
         * @param dangerLevel 危险级别描述字符串（"safe"/"caution"/"dangerous"/"critical"）
         */
        data class PermissionRequest(
            override val sessionId: String,
            val toolName: String,
            val toolInput: JsonObject,
            val dangerLevel: String
        ) : HookEvent() {
            override val eventName = HookEventName.PermissionRequest
        }

        /**
         * 权限被自动拒绝事件（AUTO 模式分类器决策）
         * @param toolName 被拒绝的工具名称
         * @param reason   拒绝原因
         */
        data class PermissionDenied(
            override val sessionId: String,
            val toolName: String,
            val reason: String
        ) : HookEvent() {
            override val eventName = HookEventName.PermissionDenied
        }

        // ── 输入事件 ──────────────────────────────────────────────────────

        /**
         * 用户提交提示词事件（Claude 处理之前触发）
         * 可通过返回 [HookResult.ModifyPrompt] 修改提示内容
         * @param userPrompt 用户原始提示词
         */
        data class UserPromptSubmit(
            override val sessionId: String,
            val userPrompt: String
        ) : HookEvent() {
            override val eventName = HookEventName.UserPromptSubmit
        }

        // ── 子 Agent 事件 ─────────────────────────────────────────────────

        /**
         * 子 Agent 启动事件
         * @param subagentId  子 Agent 唯一标识符
         * @param description 子 Agent 的任务描述
         */
        data class SubagentStart(
            override val sessionId: String,
            val subagentId: String,
            val description: String
        ) : HookEvent() {
            override val eventName = HookEventName.SubagentStart
        }

        /**
         * 子 Agent 完成事件
         * @param subagentId 子 Agent 唯一标识符
         * @param result     子 Agent 返回的结果摘要
         */
        data class SubagentStop(
            override val sessionId: String,
            val subagentId: String,
            val result: String
        ) : HookEvent() {
            override val eventName = HookEventName.SubagentStop
        }

        // ── 任务管理事件 ──────────────────────────────────────────────────

        /**
         * 任务创建事件（TodoWrite 添加新条目时触发）
         * @param taskId      任务唯一标识符
         * @param taskContent 任务内容文本
         */
        data class TaskCreated(
            override val sessionId: String,
            val taskId: String,
            val taskContent: String
        ) : HookEvent() {
            override val eventName = HookEventName.TaskCreated
        }

        /**
         * 任务完成事件（TodoWrite 将条目标记为 completed 时触发）
         * @param taskId      任务唯一标识符
         * @param taskContent 任务内容文本
         */
        data class TaskCompleted(
            override val sessionId: String,
            val taskId: String,
            val taskContent: String
        ) : HookEvent() {
            override val eventName = HookEventName.TaskCompleted
        }

        // ── 文件系统事件 ──────────────────────────────────────────────────

        /**
         * 工作目录变更事件
         * @param newCwd 新工作目录路径
         * @param oldCwd 旧工作目录路径
         */
        data class CwdChanged(
            override val sessionId: String,
            val newCwd: String,
            val oldCwd: String
        ) : HookEvent() {
            override val eventName = HookEventName.CwdChanged
        }

        /**
         * 文件变化事件（FileWatcher 检测到内容变化）
         * @param filePath   发生变化的文件路径
         * @param changeType 变化类型（"created"/"modified"/"deleted"）
         */
        data class FileChanged(
            override val sessionId: String,
            val filePath: String,
            val changeType: String
        ) : HookEvent() {
            override val eventName = HookEventName.FileChanged
        }

        // ── 压缩事件 ──────────────────────────────────────────────────────

        /**
         * 上下文压缩前事件
         * @param tokensBefore 压缩前的 token 数量
         */
        data class PreCompact(
            override val sessionId: String,
            val tokensBefore: Int
        ) : HookEvent() {
            override val eventName = HookEventName.PreCompact
        }

        /**
         * 上下文压缩后事件
         * @param tokensAfter   压缩后的 token 数量
         * @param summaryLength 生成摘要的字符长度
         */
        data class PostCompact(
            override val sessionId: String,
            val tokensAfter: Int,
            val summaryLength: Int
        ) : HookEvent() {
            override val eventName = HookEventName.PostCompact
        }

        // ── Worktree 事件 ─────────────────────────────────────────────────

        /**
         * Worktree 创建事件
         * @param worktreePath 新创建的 worktree 路径
         * @param branch       关联的 git 分支名
         */
        data class WorktreeCreate(
            override val sessionId: String,
            val worktreePath: String,
            val branch: String
        ) : HookEvent() {
            override val eventName = HookEventName.WorktreeCreate
        }

        /**
         * Worktree 移除事件
         * @param worktreePath 被移除的 worktree 路径
         */
        data class WorktreeRemove(
            override val sessionId: String,
            val worktreePath: String
        ) : HookEvent() {
            override val eventName = HookEventName.WorktreeRemove
        }

        // ── Team 事件 ─────────────────────────────────────────────────────

        /**
         * 团队成员空闲事件
         * @param teammateId  进入空闲状态的 Agent 标识符
         * @param lastMessage 该 Agent 最后一条消息内容
         */
        data class TeammateIdle(
            override val sessionId: String,
            val teammateId: String,
            val lastMessage: String
        ) : HookEvent() {
            override val eventName = HookEventName.TeammateIdle
        }

        // ── MCP 事件 ──────────────────────────────────────────────────────

        /**
         * MCP elicitation 请求事件（Server 请求用户输入）
         * @param serverId MCP Server 标识符
         * @param prompt   Server 请求用户回答的问题/提示
         */
        data class Elicitation(
            override val sessionId: String,
            val serverId: String,
            val prompt: String
        ) : HookEvent() {
            override val eventName = HookEventName.Elicitation
        }

        /**
         * MCP elicitation 结果事件（用户完成输入后触发）
         * @param serverId  MCP Server 标识符
         * @param userInput 用户输入的内容
         */
        data class ElicitationResult(
            override val sessionId: String,
            val serverId: String,
            val userInput: String
        ) : HookEvent() {
            override val eventName = HookEventName.ElicitationResult
        }
    }

    // ==================== Hook 执行结果 ====================

    /**
     * Hook 执行结果（sealed class）— 与真实 Claude Code JSON 输出协议完全对应
     *
     * 优先级：Deny > Allow/Ask > ModifyInput/ModifyPrompt/ForceOutput > SuppressOutput > Continue
     */
    sealed class HookResult {

        /** 继续执行，不修改任何内容（Hook 无意见） */
        object Continue : HookResult()

        /**
         * 阻断工具执行，返回错误给 Claude
         * 主要用于 PreToolUse 事件，对应 permissionDecision = "deny"
         * @param reason 阻断原因（展示给用户）
         */
        data class Deny(val reason: String) : HookResult()

        /**
         * 显式允许执行（跳过用户确认弹框）
         * 对应 permissionDecision = "allow"
         */
        object Allow : HookResult()

        /**
         * 需要向用户询问是否允许
         * 对应 permissionDecision = "ask"
         * @param question 可选：展示给用户的自定义问题描述
         */
        data class Ask(val question: String?) : HookResult()

        /**
         * 修改工具输入参数（仅 PreToolUse 事件有效）
         * Hook 返回修改后的 tool_input，Claude 将使用新参数调用工具
         * @param newInput 修改后的工具输入 JSON 对象
         */
        data class ModifyInput(val newInput: JsonObject) : HookResult()

        /**
         * 强制替换工具输出（仅 PostToolUse 事件有效）
         * Hook 提供自定义输出，Claude 将看到此输出而非工具实际输出
         * @param output 替换的输出内容
         */
        data class ForceOutput(val output: String) : HookResult()

        /**
         * 抑制 UI 展示工具结果（工具正常执行，但结果不显示在聊天界面）
         * 对应 hookSpecificOutput.suppressOutput = true
         */
        object SuppressOutput : HookResult()

        /**
         * 修改用户提示词（仅 UserPromptSubmit 事件有效）
         * Hook 返回修改后的提示词，Claude 将处理新提示词
         * @param newPrompt 修改后的提示词内容
         */
        data class ModifyPrompt(val newPrompt: String) : HookResult()
    }

    // ==================== Hook 配置数据结构 ====================

    /**
     * Hook 事件配置（对应 settings.json 中 hooks 字段的单个配置条目）
     *
     * @param eventName    绑定的 Hook 事件名称
     * @param matcher      工具名匹配过滤（null = 匹配所有工具，"Bash" = 仅匹配 Bash 工具）
     * @param ifCondition  前置条件过滤（"Bash(rm *)" 语法），null = 无条件触发
     * @param hooks        当条件满足时依次执行的 Hook 定义列表
     */
    data class HookEventConfig(
        val eventName: HookEventName,
        val matcher: String? = null,
        val ifCondition: String? = null,
        val hooks: List<HookDefinition>
    )

    /**
     * Hook 执行定义（sealed class）
     * 支持三种执行方式：Shell 命令、HTTP 请求、Prompt 处理
     */
    sealed class HookDefinition {

        /**
         * Shell 命令 Hook
         * 通过 stdin 传入 JSON 事件数据，解析 stdout JSON 作为结果
         *
         * @param command   要执行的 shell 命令（支持管道、重定向等）
         * @param timeoutMs 超时时间（毫秒），超时后 process.destroy()，返回 Continue
         */
        data class Command(
            val command: String,
            val timeoutMs: Long = 10_000
        ) : HookDefinition()

        /**
         * HTTP POST Hook
         * 将事件 JSON 作为请求体发送到指定 URL，解析响应体 JSON 作为结果
         *
         * @param url        HTTP POST 端点 URL
         * @param timeoutMs  请求超时时间（毫秒）
         * @param headers    自定义请求头（如 Authorization、Content-Type）
         */
        data class Http(
            val url: String,
            val timeoutMs: Long = 5_000,
            val headers: Map<String, String> = emptyMap()
        ) : HookDefinition()

        /**
         * Prompt Hook（Claude 自身作为 Hook 处理器）
         * 当前简化实现：直接返回 Continue，完整实现需调用 Anthropic API
         *
         * @param systemPrompt 注入给 Claude 的系统提示词，指导 Claude 如何处理此 Hook
         */
        data class Prompt(val systemPrompt: String) : HookDefinition()
    }

    // ==================== 核心执行引擎 ====================

    /**
     * 触发 Hook 事件（主入口）
     *
     * 执行逻辑：
     * 1. 查找所有匹配该事件且条件满足的配置
     * 2. 按配置顺序依次执行每个 Hook 定义
     * 3. 返回第一个非 Continue 的结果（短路执行）
     * 4. 若所有 Hook 均返回 Continue，则返回 Continue
     *
     * @param event Hook 事件实例（携带事件特有数据）
     * @return      Hook 执行结果，调用方根据结果决定后续行为
     */
    suspend fun fire(event: HookEvent): HookResult {
        // 筛选匹配当前事件的所有配置（按注册顺序）
        val matchingConfigs = configs.filter { config ->
            config.eventName == event.eventName && matchesEvent(config, event)
        }

        // 没有匹配配置，直接返回 Continue（不影响主流程）
        if (matchingConfigs.isEmpty()) return HookResult.Continue

        // 依次执行所有匹配配置中的 Hook 定义（短路：遇到非 Continue 立即返回）
        for (config in matchingConfigs) {
            for (hookDef in config.hooks) {
                val result = executeHook(hookDef, event)
                if (result !is HookResult.Continue) {
                    android.util.Log.d("HookSystem",
                        "Hook ${event.eventName} intercepted by ${hookDef::class.simpleName}: $result")
                    return result
                }
            }
        }

        return HookResult.Continue
    }

    /**
     * 判断 Hook 配置是否匹配当前事件
     * 综合检查 [HookEventConfig.matcher] 和 [HookEventConfig.ifCondition]
     */
    private fun matchesEvent(config: HookEventConfig, event: HookEvent): Boolean {
        // ── 检查 matcher（工具名精确匹配）──────────────────────────────────
        if (config.matcher != null) {
            val toolName = extractToolName(event)
            // matcher 不为 "*" 时，要求工具名完全匹配
            if (config.matcher != "*" && config.matcher != toolName) return false
            // 非工具类事件但配置了 matcher，跳过（matcher 仅对工具调用事件有意义）
            if (toolName == null) return false
        }

        // ── 检查 if 条件（"ToolName(pattern)" 语法）────────────────────────
        if (config.ifCondition != null) {
            return evaluateIfCondition(config.ifCondition, event)
        }

        return true
    }

    /**
     * 从事件中提取工具名称（仅工具相关事件有工具名，其他返回 null）
     */
    private fun extractToolName(event: HookEvent): String? = when (event) {
        is HookEvent.PreToolUse -> event.toolName
        is HookEvent.PostToolUse -> event.toolName
        is HookEvent.PostToolUseFailure -> event.toolName
        is HookEvent.PermissionRequest -> event.toolName
        is HookEvent.PermissionDenied -> event.toolName
        else -> null
    }

    /**
     * 解析并评估 if 条件表达式
     *
     * 支持的语法：ToolName(pattern)
     * 例如：
     *   "Bash(rm *)"      — 匹配包含 "rm " 的 Bash 命令
     *   "Write(/etc/*)"   — 匹配写入 /etc/ 路径的 Write 工具
     *   "Bash(git push *--force*)" — 匹配强制推送命令
     *
     * pattern 支持 glob 语法：* 匹配任意字符序列，? 匹配单个字符
     *
     * @param condition if 条件字符串
     * @param event     当前事件
     * @return          条件是否满足
     */
    private fun evaluateIfCondition(condition: String, event: HookEvent): Boolean {
        // 解析 ToolName(pattern) 格式
        val regex = Regex("""(\w+)\((.+)\)""")
        val match = regex.matchEntire(condition.trim())
            ?: return true  // 无法解析的条件视为满足（宽松策略）

        val (conditionTool, pattern) = match.destructured

        // 提取事件中的工具名和输入内容
        val (actualToolName, actualInputStr) = when (event) {
            is HookEvent.PreToolUse -> Pair(event.toolName, event.toolInput.toString())
            is HookEvent.PostToolUse -> Pair(event.toolName, event.toolInput.toString())
            is HookEvent.PostToolUseFailure -> Pair(event.toolName, event.toolInput.toString())
            else -> return true  // 非工具事件，条件视为满足
        }

        // 工具名必须匹配
        if (conditionTool != actualToolName) return false

        // 将 glob 模式转为正则表达式，在输入字符串中搜索匹配
        val regexPattern = pattern
            .replace(".", "\\.")      // 转义点号
            .replace("*", ".*")       // * -> .*（匹配任意序列）
            .replace("?", ".")        // ? -> .（匹配单个字符）
        return Regex(regexPattern).containsMatchIn(actualInputStr)
    }

    /**
     * 执行单个 Hook 定义（路由到具体执行器）
     */
    private suspend fun executeHook(hookDef: HookDefinition, event: HookEvent): HookResult {
        return when (hookDef) {
            is HookDefinition.Command -> executeCommandHook(hookDef, event)
            is HookDefinition.Http    -> executeHttpHook(hookDef, event)
            is HookDefinition.Prompt  -> {
                // Prompt Hook：Claude 自身作为处理器，当前简化为直通
                // 完整实现需要异步调用 Anthropic API，解析响应后返回相应 HookResult
                android.util.Log.d("HookSystem", "Prompt hook triggered for ${event.eventName}, passing through")
                HookResult.Continue
            }
        }
    }

    /**
     * 执行 Shell 命令 Hook
     *
     * 执行流程：
     * 1. 将事件数据序列化为 JSON 字符串
     * 2. 启动子进程执行命令
     * 3. 向子进程 stdin 写入 JSON 数据
     * 4. 等待子进程完成（带超时保护）
     * 5. 读取 stdout 内容，解析为 HookResult
     *
     * 错误处理：任何异常（进程启动失败、超时等）均返回 Continue，不影响主流程
     */
    private suspend fun executeCommandHook(hook: HookDefinition.Command, event: HookEvent): HookResult {
        return withContext(Dispatchers.IO) {
            try {
                // 序列化事件数据为 stdin JSON
                val stdinJson = eventToJson(event).toString()

                // 启动子进程（stderr 合并到 stdout 便于调试）
                val process = ProcessBuilder()
                    .command("sh", "-c", hook.command)
                    .redirectErrorStream(true)
                    .start()

                // 写入 stdin 并关闭（防止进程阻塞等待更多输入）
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdinJson)
                    writer.flush()
                }

                // 带超时等待进程完成
                val completed = withTimeoutOrNull(hook.timeoutMs) {
                    process.waitFor()
                    true
                }

                if (completed == null) {
                    // 超时：强制终止进程，返回 Continue（不阻断主流程）
                    android.util.Log.w("HookSystem",
                        "Command hook timed out after ${hook.timeoutMs}ms: ${hook.command}")
                    process.destroyForcibly()
                    return@withContext HookResult.Continue
                }

                // 读取标准输出
                val stdout = process.inputStream.bufferedReader().readText()
                val exitCode = process.exitValue()

                android.util.Log.d("HookSystem",
                    "Command hook exited $exitCode, output: ${stdout.take(200)}")

                // 按协议解析输出
                parseHookOutput(stdout, exitCode, event.eventName)

            } catch (e: Exception) {
                // Hook 执行异常不影响主流程
                android.util.Log.w("HookSystem",
                    "Command hook execution failed: ${e.javaClass.simpleName}: ${e.message}")
                HookResult.Continue
            }
        }
    }

    /**
     * 执行 HTTP POST Hook
     *
     * 执行流程：
     * 1. 将事件数据序列化为 JSON 请求体
     * 2. 发送 HTTP POST 请求到指定 URL
     * 3. 读取响应体，解析为 HookResult
     *
     * 错误处理：网络错误、超时等均返回 Continue，不影响主流程
     */
    private suspend fun executeHttpHook(hook: HookDefinition.Http, event: HookEvent): HookResult {
        return withContext(Dispatchers.IO) {
            try {
                // 构建请求体（事件 JSON）
                val json = eventToJson(event).toString()
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

                // 构建请求（添加自定义 headers）
                val requestBuilder = Request.Builder()
                    .url(hook.url)
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "ClaudeCode-Android-HookSystem/1.0")

                hook.headers.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value)
                }

                // 使用独立的带超时客户端（避免全局 httpClient 超时影响）
                val timeoutClient = httpClient.newBuilder()
                    .callTimeout(hook.timeoutMs, TimeUnit.MILLISECONDS)
                    .build()

                val response = timeoutClient.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string() ?: ""

                android.util.Log.d("HookSystem",
                    "HTTP hook ${hook.url} responded ${response.code}: ${responseBody.take(200)}")

                // HTTP 响应码：2xx = 成功，其他 = 失败
                val exitCode = if (response.isSuccessful) 0 else 1
                parseHookOutput(responseBody, exitCode, event.eventName)

            } catch (e: Exception) {
                android.util.Log.w("HookSystem",
                    "HTTP hook ${hook.url} failed: ${e.javaClass.simpleName}: ${e.message}")
                HookResult.Continue
            }
        }
    }

    /**
     * 解析 Hook 输出（核心协议解析器）
     *
     * 预期的 JSON 格式（与真实 Claude Code 完全一致）：
     * {
     *   "hookSpecificOutput": {
     *     "hookEventName": "PreToolUse",            // 可选，用于调试
     *     "permissionDecision": "deny",              // allow | deny | ask
     *     "permissionDecisionReason": "原因描述",    // 展示给用户的原因
     *     "hookForceOutput": "强制输出内容",          // 仅 PostToolUse 有效
     *     "suppressOutput": false,                   // 是否抑制 UI 展示
     *     "modifiedInput": { ... },                  // 仅 PreToolUse 有效
     *     "modifiedPrompt": "修改后的提示词"          // 仅 UserPromptSubmit 有效
     *   }
     * }
     *
     * 降级策略：
     * - JSON 解析失败 + exitCode != 0 → Deny
     * - 缺少 hookSpecificOutput + exitCode != 0 → Deny
     * - 空输出 → Continue
     *
     * @param output    Hook 的 stdout 或 HTTP 响应体
     * @param exitCode  进程退出码或 HTTP 状态码（0/2xx = 成功）
     * @param eventName 触发的事件名称（用于判断哪些字段有效）
     */
    private fun parseHookOutput(output: String, exitCode: Int, eventName: HookEventName): HookResult {
        // 空输出：根据 exitCode 决定（正常退出 = Continue，异常退出 = Continue 宽松策略）
        if (output.isBlank()) {
            return HookResult.Continue
        }

        return try {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(output).jsonObject

            // 获取 hookSpecificOutput 对象
            val specific = root["hookSpecificOutput"]?.jsonObject
                ?: return if (exitCode != 0) {
                    HookResult.Deny("Hook exited with code $exitCode: ${output.take(200)}")
                } else {
                    HookResult.Continue
                }

            // 解析各个输出字段
            val decision       = specific["permissionDecision"]?.jsonPrimitive?.content
            val reason         = specific["permissionDecisionReason"]?.jsonPrimitive?.content
            val forceOutput    = specific["hookForceOutput"]?.jsonPrimitive?.content
            val suppressOutput = specific["suppressOutput"]?.jsonPrimitive?.booleanOrNull ?: false
            val modifiedInput  = specific["modifiedInput"]?.jsonObject
            val modifiedPrompt = specific["modifiedPrompt"]?.jsonPrimitive?.content

            // 按优先级匹配结果（更具体的条件在前）
            when {
                // 修改用户提示词（仅 UserPromptSubmit）
                eventName == HookEventName.UserPromptSubmit && modifiedPrompt != null ->
                    HookResult.ModifyPrompt(modifiedPrompt)

                // 修改工具输入（仅 PreToolUse）
                eventName == HookEventName.PreToolUse && modifiedInput != null ->
                    HookResult.ModifyInput(modifiedInput)

                // 强制替换工具输出（主要用于 PostToolUse）
                forceOutput != null ->
                    HookResult.ForceOutput(forceOutput)

                // 抑制 UI 输出显示
                suppressOutput ->
                    HookResult.SuppressOutput

                // permissionDecision 字段决策
                decision == "deny"  -> HookResult.Deny(reason ?: "Blocked by hook")
                decision == "allow" -> HookResult.Allow
                decision == "ask"   -> HookResult.Ask(reason)

                // 无明确指令：返回 Continue
                else -> HookResult.Continue
            }

        } catch (e: Exception) {
            // JSON 解析失败：非零退出码视为阻断（兼容返回纯文本错误的 Hook）
            android.util.Log.w("HookSystem", "Failed to parse hook output: ${e.message}")
            if (exitCode != 0) {
                HookResult.Deny("Hook failed (exit $exitCode): ${output.take(200)}")
            } else {
                HookResult.Continue
            }
        }
    }

    /**
     * 将 Hook 事件序列化为 JSON 对象（通过 stdin 传递给 Hook 命令/HTTP 端点）
     *
     * 所有事件都包含基础字段：session_id、hook_event_name
     * 各事件子类追加其特有字段（字段名与真实 Claude Code 的协议完全一致）
     */
    private fun eventToJson(event: HookEvent): JsonObject = buildJsonObject {
        // ── 公共字段（所有事件必有）──────────────────────────────────────────
        put("session_id", event.sessionId)
        put("hook_event_name", event.eventName.name)

        // ── 事件特有字段 ──────────────────────────────────────────────────────
        when (event) {
            // 生命周期事件
            is HookEvent.SessionStart -> {
                put("working_directory", event.workingDirectory)
            }
            is HookEvent.SessionEnd -> {
                put("duration_ms", event.durationMs)
            }
            is HookEvent.Stop -> {
                put("stop_reason", event.stopReason)
            }
            is HookEvent.StopFailure -> {
                put("error", event.error)
            }
            is HookEvent.Notification -> {
                put("message", event.message)
            }
            is HookEvent.ConfigChange -> {
                put("changed_file", event.changedFile)
            }
            is HookEvent.InstructionsLoaded -> {
                putJsonArray("files") {
                    event.files.forEach { add(it) }
                }
            }

            // 工具调用事件
            is HookEvent.PreToolUse -> {
                put("tool_name", event.toolName)
                put("tool_input", event.toolInput)
                put("cwd", event.cwd)
            }
            is HookEvent.PostToolUse -> {
                put("tool_name", event.toolName)
                put("tool_input", event.toolInput)
                put("tool_output", event.toolOutput)
                put("duration_ms", event.durationMs)
                put("cwd", event.cwd)
            }
            is HookEvent.PostToolUseFailure -> {
                put("tool_name", event.toolName)
                put("tool_input", event.toolInput)
                put("error", event.error)
            }
            is HookEvent.PermissionRequest -> {
                put("tool_name", event.toolName)
                put("tool_input", event.toolInput)
                put("danger_level", event.dangerLevel)
            }
            is HookEvent.PermissionDenied -> {
                put("tool_name", event.toolName)
                put("reason", event.reason)
            }

            // 输入事件
            is HookEvent.UserPromptSubmit -> {
                put("user_prompt", event.userPrompt)
            }

            // 子 Agent 事件
            is HookEvent.SubagentStart -> {
                put("subagent_id", event.subagentId)
                put("description", event.description)
            }
            is HookEvent.SubagentStop -> {
                put("subagent_id", event.subagentId)
                put("result", event.result)
            }

            // 任务管理事件
            is HookEvent.TaskCreated -> {
                put("task_id", event.taskId)
                put("task_content", event.taskContent)
            }
            is HookEvent.TaskCompleted -> {
                put("task_id", event.taskId)
                put("task_content", event.taskContent)
            }

            // 文件系统事件
            is HookEvent.CwdChanged -> {
                put("new_cwd", event.newCwd)
                put("old_cwd", event.oldCwd)
            }
            is HookEvent.FileChanged -> {
                put("file_path", event.filePath)
                put("change_type", event.changeType)
            }

            // 压缩事件
            is HookEvent.PreCompact -> {
                put("tokens_before", event.tokensBefore)
            }
            is HookEvent.PostCompact -> {
                put("tokens_after", event.tokensAfter)
                put("summary_length", event.summaryLength)
            }

            // Worktree 事件
            is HookEvent.WorktreeCreate -> {
                put("worktree_path", event.worktreePath)
                put("branch", event.branch)
            }
            is HookEvent.WorktreeRemove -> {
                put("worktree_path", event.worktreePath)
            }

            // Team 事件
            is HookEvent.TeammateIdle -> {
                put("teammate_id", event.teammateId)
                put("last_message", event.lastMessage)
            }

            // MCP 事件
            is HookEvent.Elicitation -> {
                put("server_id", event.serverId)
                put("prompt", event.prompt)
            }
            is HookEvent.ElicitationResult -> {
                put("server_id", event.serverId)
                put("user_input", event.userInput)
            }
        }
    }

    // ==================== 配置加载 ====================

    /**
     * 从 settings.json 字符串加载 Hook 配置
     *
     * 配置格式与真实 Claude Code 完全兼容：
     * {
     *   "hooks": {
     *     "PreToolUse": [
     *       {
     *         "matcher": "Bash",               // 可选：工具名过滤
     *         "if": "Bash(rm *)",              // 可选：if 条件过滤
     *         "hooks": [
     *           {
     *             "type": "command",
     *             "command": "/path/to/hook.sh",
     *             "timeout_ms": 10000          // 可选：超时（默认 10000ms）
     *           }
     *         ]
     *       }
     *     ]
     *   }
     * }
     *
     * 调用此方法会清空之前的所有配置（全量替换）
     */
    fun loadFromSettings(settingsJson: String) {
        configs.clear()

        try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(settingsJson).jsonObject
            val hooksObj = root["hooks"]?.jsonObject ?: return

            hooksObj.forEach { (eventNameStr, eventConfigElement) ->
                // 解析事件名称枚举
                val eventName = try {
                    HookEventName.valueOf(eventNameStr)
                } catch (e: IllegalArgumentException) {
                    android.util.Log.w("HookSystem", "Unknown hook event name: '$eventNameStr', skipping")
                    return@forEach
                }

                // 遍历该事件的配置数组
                val configArray = eventConfigElement.jsonArray
                for (configElement in configArray) {
                    val configObj = configElement.jsonObject
                    val matcher = configObj["matcher"]?.jsonPrimitive?.content
                    val ifCond  = configObj["if"]?.jsonPrimitive?.content

                    // 解析 hooks 数组（至少需要一个有效的 HookDefinition）
                    val hookDefs = configObj["hooks"]?.jsonArray
                        ?.mapNotNull { parseHookDefinition(it.jsonObject) }
                        ?: continue

                    if (hookDefs.isEmpty()) continue

                    configs.add(HookEventConfig(
                        eventName   = eventName,
                        matcher     = matcher,
                        ifCondition = ifCond,
                        hooks       = hookDefs
                    ))

                    android.util.Log.d("HookSystem",
                        "Registered ${hookDefs.size} hook(s) for $eventName" +
                        (if (matcher != null) " [matcher=$matcher]" else "") +
                        (if (ifCond != null) " [if=$ifCond]" else ""))
                }
            }

            android.util.Log.i("HookSystem",
                "Loaded ${configs.size} hook configs (${getRegisteredCount()} total hooks)")

        } catch (e: Exception) {
            android.util.Log.e("HookSystem", "Failed to load hook config: ${e.message}", e)
        }
    }

    /**
     * 解析单个 HookDefinition JSON 对象
     *
     * @param obj 包含 type 字段的 JSON 对象
     * @return    解析成功返回 HookDefinition，type 未知或缺少必填字段返回 null
     */
    private fun parseHookDefinition(obj: JsonObject): HookDefinition? {
        return when (val type = obj["type"]?.jsonPrimitive?.content) {
            "command" -> {
                val command = obj["command"]?.jsonPrimitive?.content
                    ?: run {
                        android.util.Log.w("HookSystem", "Command hook missing 'command' field")
                        return null
                    }
                HookDefinition.Command(
                    command   = command,
                    timeoutMs = obj["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 10_000
                )
            }
            "http" -> {
                val url = obj["url"]?.jsonPrimitive?.content
                    ?: run {
                        android.util.Log.w("HookSystem", "HTTP hook missing 'url' field")
                        return null
                    }
                // 解析自定义 headers（可选）
                val headers = obj["headers"]?.jsonObject
                    ?.mapValues { it.value.jsonPrimitive.content }
                    ?: emptyMap()
                HookDefinition.Http(
                    url       = url,
                    timeoutMs = obj["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 5_000,
                    headers   = headers
                )
            }
            "prompt" -> {
                HookDefinition.Prompt(
                    systemPrompt = obj["system_prompt"]?.jsonPrimitive?.content ?: ""
                )
            }
            else -> {
                android.util.Log.w("HookSystem", "Unknown hook type: '$type', skipping")
                null
            }
        }
    }

    // ==================== 编程式 API ====================

    /**
     * 以编程方式注册单个 Hook 配置（不影响其他已注册的配置）
     * 用于在代码中动态添加 Hook，无需修改 settings.json
     *
     * @param config 要注册的 Hook 事件配置
     */
    fun register(config: HookEventConfig) {
        configs.add(config)
        android.util.Log.d("HookSystem",
            "Programmatically registered hook for ${config.eventName}")
    }

    /**
     * 清除所有已注册的 Hook 配置
     * 通常在重新加载 settings.json 前调用（[loadFromSettings] 内部会自动调用）
     */
    fun clearAll() {
        val count = configs.size
        configs.clear()
        android.util.Log.d("HookSystem", "Cleared $count hook configs")
    }

    /**
     * 获取当前已注册的 Hook 执行器总数（所有配置中的 HookDefinition 数量之和）
     * 可用于健康检查和调试
     */
    fun getRegisteredCount(): Int = configs.sumOf { it.hooks.size }

    /**
     * 获取指定事件已注册的 Hook 配置数量
     * 可用于判断某个事件是否有 Hook 监听（避免不必要的 [fire] 调用）
     *
     * @param eventName 要查询的事件名称
     * @return          匹配的配置数量（不是 HookDefinition 数量）
     */
    fun getConfigCountForEvent(eventName: HookEventName): Int =
        configs.count { it.eventName == eventName }

    /**
     * 检查指定事件是否有任何 Hook 配置
     * 便捷方法，等价于 [getConfigCountForEvent] > 0
     */
    fun hasHooksForEvent(eventName: HookEventName): Boolean =
        configs.any { it.eventName == eventName }
}
