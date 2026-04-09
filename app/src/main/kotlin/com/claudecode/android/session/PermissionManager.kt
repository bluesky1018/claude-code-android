package com.claudecode.android.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 权限管理器 — 控制 Agent 可以执行哪些操作
 *
 * ## 四种权限模式（完全对标 Claude Code）
 *
 * 1. DEFAULT（默认）
 *    - 危险操作（Write/Edit/Bash）：弹出确认对话框
 *    - 安全操作（Read/Glob/Grep）：自动通过
 *
 * 2. ACCEPT_EDITS（接受编辑）
 *    - 文件读写操作：自动通过（不再弹窗）
 *    - Bash 命令：仍需确认
 *    适合：你信任 Claude 的编辑，但不想让它随意执行命令
 *
 * 3. BYPASS_ALL（跳过所有权限，对应 --dangerously-skip-permissions）
 *    - 所有操作：自动通过
 *    适合：CI/CD 环境、自动化脚本
 *    注意：Claude 可以执行任何操作，谨慎使用！
 *
 * 4. PLAN_ONLY（仅规划，不执行）
 *    - 所有工具调用：自动拒绝
 *    - Claude 只能阅读和规划，不能修改任何内容
 *    适合：你想先审查计划再决定是否执行
 */
enum class PermissionMode {
    /** 默认模式：危险操作需要确认 */
    DEFAULT,

    /** 接受编辑模式：文件操作自动通过，Bash 仍需确认 */
    ACCEPT_EDITS,

    /** 跳过所有权限：全部自动通过（危险！） */
    BYPASS_ALL,

    /** 仅规划模式：所有操作自动拒绝 */
    PLAN_ONLY
}

/**
 * 工具危险等级
 *
 * 根据工具对系统的潜在影响分为四个等级
 */
enum class ToolDangerLevel {
    /**
     * 安全操作：只读，不修改系统状态
     * 包括：Read, Glob, Grep, LS, TodoRead, WebSearch, WebFetch
     */
    SAFE,

    /**
     * 谨慎操作：会修改文件内容，但影响范围可预期
     * 包括：Write, Edit, MultiEdit, TodoWrite
     */
    CAUTION,

    /**
     * 危险操作：可能执行任意系统命令，影响难以预测
     * 包括：Bash
     */
    DANGEROUS,

    /**
     * 极度危险：启动子 Agent，行为完全不可预测
     * 包括：Agent（子 Agent 调用）
     */
    CRITICAL
}

/**
 * 权限审批请求
 *
 * 当需要用户确认时，创建此对象并展示在 UI 上。
 * 用户点击确认或拒绝后，通过 approveRequest/denyRequest 响应。
 *
 * @param requestId 唯一标识符，用于匹配用户响应
 * @param toolName 工具名称（如 "Bash", "Write"）
 * @param toolInputSummary 工具参数摘要（展示给用户看）
 * @param dangerLevel 危险等级
 * @param createdAt 请求创建时间（毫秒时间戳）
 */
data class PermissionRequest(
    val requestId: String,
    val toolName: String,
    val toolInputSummary: String,
    val dangerLevel: ToolDangerLevel,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 权限管理器
 *
 * 核心流程：
 * 1. Agent 准备调用工具前，先调用 checkPermission()
 * 2. 如果需要用户确认，创建 PermissionRequest 并挂起协程
 * 3. UI 监听 pendingRequests，展示确认对话框
 * 4. 用户点击确认/拒绝，调用 approveRequest/denyRequest
 * 5. 挂起的协程恢复执行，返回 true/false
 */
class PermissionManager {

    // 待审批请求列表（UI 通过此 Flow 监听并展示对话框）
    private val _pendingRequests = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PermissionRequest>> = _pendingRequests.asStateFlow()

    // 挂起等待中的审批回调（requestId → CompletableDeferred<Boolean>）
    // CompletableDeferred 是协程版本的 Promise/Future
    private val pendingApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()

    /**
     * 检查是否允许执行指定工具
     *
     * 根据权限模式和工具危险等级决定：
     * - 自动通过（返回 true）
     * - 自动拒绝（返回 false）
     * - 挂起等待用户确认
     *
     * @param toolName 工具名称
     * @param toolInput 工具调用的输入参数（JSON 字符串）
     * @param mode 当前权限模式
     * @return true = 允许执行，false = 拒绝执行
     */
    suspend fun checkPermission(
        toolName: String,
        toolInput: String,
        mode: PermissionMode
    ): Boolean {
        val dangerLevel = getDangerLevel(toolName)

        return when (mode) {
            // 跳过所有权限：直接允许
            PermissionMode.BYPASS_ALL -> true

            // 仅规划模式：直接拒绝所有工具调用
            PermissionMode.PLAN_ONLY -> false

            // 接受编辑模式：安全和谨慎操作自动通过，危险以上需要确认
            PermissionMode.ACCEPT_EDITS -> when (dangerLevel) {
                ToolDangerLevel.SAFE    -> true
                ToolDangerLevel.CAUTION -> true   // 文件编辑自动通过
                ToolDangerLevel.DANGEROUS,
                ToolDangerLevel.CRITICAL -> requestUserApproval(toolName, toolInput)
            }

            // 默认模式：安全操作自动通过，其余需要确认
            PermissionMode.DEFAULT -> when (dangerLevel) {
                ToolDangerLevel.SAFE -> true
                ToolDangerLevel.CAUTION,
                ToolDangerLevel.DANGEROUS,
                ToolDangerLevel.CRITICAL -> requestUserApproval(toolName, toolInput)
            }
        }
    }

    /**
     * 挂起当前协程，等待用户在 UI 上做出审批决定
     *
     * 这是实现"用户确认对话框"的核心机制：
     * 1. 创建 CompletableDeferred（相当于一个"承诺"）
     * 2. 将审批请求加入 pendingRequests（UI 会观察并弹出对话框）
     * 3. 挂起协程，等待 CompletableDeferred.complete() 被调用
     * 4. 用户点击确认/拒绝后，deferredResult.complete(true/false) 被调用
     * 5. 协程恢复，返回用户的决定
     *
     * @param toolName 工具名称
     * @param toolInput 工具输入（展示给用户）
     * @return 用户决定：true = 允许，false = 拒绝
     */
    suspend fun requestUserApproval(
        toolName: String,
        toolInput: String
    ): Boolean {
        // 生成唯一请求 ID
        val requestId = UUID.randomUUID().toString()

        // 截取工具输入的前 200 个字符作为摘要（避免 UI 显示过长）
        val summary = toolInput.take(200).let {
            if (toolInput.length > 200) "$it..." else it
        }

        // 创建审批请求
        val request = PermissionRequest(
            requestId        = requestId,
            toolName         = toolName,
            toolInputSummary = summary,
            dangerLevel      = getDangerLevel(toolName)
        )

        // 创建 CompletableDeferred，用于挂起/恢复协程
        val deferredResult = CompletableDeferred<Boolean>()
        pendingApprovals[requestId] = deferredResult

        // 将请求加入待审批列表（UI 会观察此变化并弹出对话框）
        _pendingRequests.value = _pendingRequests.value + request

        return try {
            // 挂起协程，等待用户决定
            // 此时协程不会占用线程，而是挂起等待
            deferredResult.await()
        } finally {
            // 无论结果如何，从列表中移除请求（清理 UI）
            _pendingRequests.value = _pendingRequests.value.filter { it.requestId != requestId }
            pendingApprovals.remove(requestId)
        }
    }

    /**
     * UI 层调用：用户点击"允许"后调用此方法
     *
     * @param requestId 审批请求的唯一 ID
     */
    fun approveRequest(requestId: String) {
        pendingApprovals[requestId]?.complete(true)
    }

    /**
     * UI 层调用：用户点击"拒绝"后调用此方法
     *
     * @param requestId 审批请求的唯一 ID
     */
    fun denyRequest(requestId: String) {
        pendingApprovals[requestId]?.complete(false)
    }

    /**
     * 获取工具的危险等级
     *
     * 根据工具名称映射到对应的 ToolDangerLevel。
     * 未知工具默认视为 DANGEROUS（宁可误判也不放行）。
     *
     * @param toolName 工具名称（不区分大小写）
     * @return 对应的危险等级
     */
    fun getDangerLevel(toolName: String): ToolDangerLevel {
        return when (toolName.lowercase()) {
            // 安全操作：只读工具
            "read", "glob", "grep", "ls",
            "todoread", "websearch", "webfetch",
            "notebookread" -> ToolDangerLevel.SAFE

            // 谨慎操作：文件修改工具
            "write", "edit", "multiedit",
            "todowrite", "notebookedit" -> ToolDangerLevel.CAUTION

            // 危险操作：命令执行
            "bash" -> ToolDangerLevel.DANGEROUS

            // 极度危险：子 Agent 调用
            "agent", "task" -> ToolDangerLevel.CRITICAL

            // 未知工具：保守处理，视为危险
            else -> ToolDangerLevel.DANGEROUS
        }
    }

    /**
     * 拒绝所有待审批请求（用于 Session 强制终止时）
     */
    fun denyAllPending() {
        pendingApprovals.values.forEach { it.complete(false) }
    }
}
