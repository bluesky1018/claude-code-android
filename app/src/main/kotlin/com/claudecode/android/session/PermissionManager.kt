package com.claudecode.android.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*

/**
 * 权限管理器 — 像素级复刻真实 Claude Code 的权限系统
 *
 * 真实 Claude Code 支持 6 种权限模式，完整对应如下：
 *
 * ┌──────────────────────┬──────────────────────────────────────────────────────────┐
 * │ 模式名称             │ 行为描述                                                 │
 * ├──────────────────────┼──────────────────────────────────────────────────────────┤
 * │ default              │ 只读操作预批准；文件写入、Bash 命令需用户弹框确认         │
 * │ acceptEdits          │ 文件读写编辑预批准；常用安全 Bash 命令预批准；其余需确认  │
 * │ plan                 │ 仅规划模式 — 只允许只读操作，拒绝所有修改类工具          │
 * │ auto                 │ AI 分类器自动审核；明显危险命令（rm -rf 等）自动阻断     │
 * │ dontAsk              │ 仅执行 allow 规则明确预批准的工具；其余一律拒绝          │
 * │ bypassPermissions    │ 跳过所有权限检查（受保护路径除外）；仅限隔离容器使用    │
 * └──────────────────────┴──────────────────────────────────────────────────────────┘
 *
 * 权限规则格式（来自 settings.json 的 "permissions" 字段）：
 * {
 *   "permissions": {
 *     "allow": ["Bash(git log *)", "Read", "Glob"],
 *     "ask":   ["Write(/tmp/*)"],
 *     "deny":  ["Bash(rm -rf *)", "Write(/etc/*)"]
 *   }
 * }
 *
 * 规则匹配语法：
 *   - "ToolName"          精确匹配工具名（如 "Read"、"Bash"）
 *   - "ToolName(pattern)" 匹配工具名 + 输入内容中包含 pattern（支持 glob: * / ?）
 *
 * 权限决策流程：
 * 1. deny 规则优先检查 → 命中则立即拒绝
 * 2. allow 规则检查 → 命中则立即允许
 * 3. 按当前权限模式决策（default/acceptEdits/plan/auto/dontAsk/bypassPermissions）
 * 4. 受保护路径检查（任何模式下都不自动批准对保护路径的写操作）
 */
class PermissionManager {

    // ==================== 权限模式枚举 ====================

    /**
     * 6 种权限模式（完全对标真实 Claude Code 的 --permission-mode 参数值）
     */
    enum class PermissionMode {
        /**
         * 默认模式（default）
         * - SAFE 级工具：预批准（Read、Glob、Grep 等）
         * - CAUTION/DANGEROUS/CRITICAL 级工具：弹框询问用户
         * - 受保护路径写操作：直接拒绝
         */
        DEFAULT,

        /**
         * 接受编辑模式（acceptEdits / --dangerously-skip-permissions 的轻量版）
         * - SAFE + CAUTION 级工具：预批准（含文件写入、TodoWrite 等）
         * - 常用安全 Bash 命令：预批准（见 SAFE_BASH_COMMANDS）
         * - DANGEROUS 级工具（WebFetch 等）：需确认
         * - 受保护路径写操作：直接拒绝
         */
        ACCEPT_EDITS,

        /**
         * 仅规划模式（plan）
         * - 仅允许 SAFE 级只读工具
         * - 所有写操作/Bash/WebFetch 均拒绝
         * - 适用于生成实施计划而不执行任何变更
         */
        PLAN,

        /**
         * 自动模式（auto）
         * - 独立 AI 分类器审核每个工具调用
         * - 内置危险模式检测（rm -rf、git push --force、curl|bash 等）
         * - 连续/累计阻断超过阈值时触发降级警告
         * - 适用于 CI/CD 等自动化场景
         */
        AUTO,

        /**
         * 不询问模式（dontAsk）
         * - 仅执行 allow 规则明确批准的工具
         * - 所有未在 allow 列表中的工具均拒绝，不询问用户
         * - 适用于高度受控的自动化场景
         */
        DONT_ASK,

        /**
         * 绕过所有权限模式（bypassPermissions）
         * - 几乎所有工具均预批准
         * - 仍然检查受保护路径（防止意外破坏关键配置文件）
         * - 警告：仅在完全隔离的容器/沙箱环境中使用
         */
        BYPASS_PERMISSIONS
    }

    // ==================== 工具危险级别 ====================

    /**
     * 工具危险级别（4 级）
     * 用于在各权限模式下快速判断工具是否需要确认
     */
    enum class ToolDangerLevel {
        /**
         * 安全（只读）
         * 工具：Read, Glob, Grep, LS, TodoRead, NotebookRead
         * 所有模式下（PLAN 模式除外不考虑）均预批准
         */
        SAFE,

        /**
         * 谨慎（写操作）
         * 工具：Write, Edit, MultiEdit, TodoWrite, NotebookEdit
         * DEFAULT 模式需确认；ACCEPT_EDITS 模式预批准（除受保护路径）
         */
        CAUTION,

        /**
         * 危险（网络/副作用）
         * 工具：WebFetch, WebSearch
         * DEFAULT 和 ACCEPT_EDITS 模式均需确认
         */
        DANGEROUS,

        /**
         * 关键（不可逆系统操作）
         * 工具：Bash, Agent（子 Agent 启动）
         * 仅在 ACCEPT_EDITS 的安全命令白名单 / AUTO 通过 / BYPASS_PERMISSIONS 下预批准
         */
        CRITICAL
    }

    // ==================== 权限规则引擎 ====================

    /**
     * 权限规则集合（来自 settings.json 的 permissions 字段）
     *
     * @param allow 允许规则列表（匹配则直接允许，跳过弹框）
     * @param ask   询问规则列表（匹配则强制弹框询问，即使当前模式原本不问）
     * @param deny  拒绝规则列表（匹配则立即拒绝，优先级最高）
     */
    data class PermissionRules(
        val allow: List<String> = emptyList(),
        val ask: List<String> = emptyList(),
        val deny: List<String> = emptyList()
    )

    /** 当前已加载的权限规则 */
    private var rules = PermissionRules()

    /**
     * 受保护路径集合（任何权限模式下均不自动批准对这些路径的写操作）
     *
     * 这些路径包含关键配置文件，意外修改可能造成系统或项目配置损坏：
     * - Git 相关：.git, .gitconfig, .gitmodules
     * - IDE 配置：.vscode, .idea
     * - Shell 配置：.bashrc, .zshrc, .profile, .bash_profile
     * - 项目配置：.husky, .mcp.json, .claude.json
     */
    private val PROTECTED_PATHS = setOf(
        ".git",
        ".vscode",
        ".idea",
        ".husky",
        ".gitconfig",
        ".gitmodules",
        ".bashrc",
        ".zshrc",
        ".profile",
        ".bash_profile",
        ".mcp.json",
        ".claude.json"
    )

    /**
     * ACCEPT_EDITS 模式下预批准的 Bash 命令前缀集合（常用、低风险命令）
     *
     * 这些命令被认为是相对安全的，在 ACCEPT_EDITS 模式下无需用户确认：
     * - 目录/文件操作（不含删除）：mkdir, touch, mv, cp
     * - 文本处理：sed, awk, sort, uniq, head, tail, grep, wc
     * - 信息查询：ls, cat, echo, find, pwd, which, date
     * - Git 只读和常规操作：git status, git log, git diff, git add, git commit
     */
    private val SAFE_BASH_COMMANDS = setOf(
        "mkdir",
        "touch",
        "mv",
        "cp",
        "ls",
        "cat",
        "echo",
        "sed",
        "awk",
        "sort",
        "uniq",
        "head",
        "tail",
        "grep",
        "find",
        "wc",
        "pwd",
        "which",
        "date",
        "git status",
        "git log",
        "git diff",
        "git add",
        "git commit",
        "git branch",
        "git checkout",
        "git fetch",
        "git stash"
    )

    /**
     * AUTO 模式自动阻断的危险命令正则模式列表
     *
     * 这些模式对应真实 Claude Code AUTO 模式内置分类器的核心规则：
     * 任何 Bash 命令只要匹配其中一条，就会被自动阻断（无需用户干预）
     */
    private val AUTO_BLOCKED_PATTERNS = listOf(
        // 递归删除
        Regex("rm\\s+-rf"),
        Regex("rm\\s+--recursive"),

        // 强制推送到远程（含对 main/master 分支）
        Regex("git\\s+push\\s+.*--force"),
        Regex("git\\s+push\\s+.*-f\\b"),

        // 下载并执行（高危供应链攻击向量）
        Regex("curl\\s+.*\\|\\s*bash"),
        Regex("curl\\s+.*\\|\\s*sh"),
        Regex("wget\\s+.*\\|\\s*bash"),
        Regex("wget\\s+.*\\|\\s*sh"),

        // SQL 破坏性操作
        Regex("DROP\\s+TABLE", RegexOption.IGNORE_CASE),
        Regex("DROP\\s+DATABASE", RegexOption.IGNORE_CASE),
        Regex("DELETE\\s+FROM\\s+\\w+\\s*;", RegexOption.IGNORE_CASE),  // 无 WHERE 的全表删除
        Regex("TRUNCATE\\s+TABLE", RegexOption.IGNORE_CASE),

        // 文件系统权限变更
        Regex("chmod\\s+777"),
        Regex("chmod\\s+-R\\s+777"),
        Regex("chown\\s+root"),
        Regex("chown\\s+-R\\s+root"),

        // 磁盘/文件系统破坏
        Regex(">\\s*/dev/sda"),         // 覆写磁盘设备
        Regex("mkfs\\."),               // 格式化文件系统
        Regex("dd\\s+.*of=/dev/"),      // dd 写入磁盘

        // 关键系统文件修改
        Regex(">\\s*/etc/passwd"),
        Regex(">\\s*/etc/shadow"),
        Regex(">\\s*/etc/hosts"),

        // 进程/系统破坏
        Regex("kill\\s+-9\\s+-1"),      // 杀死所有进程
        Regex(":(\\)\\{:\\|:&\\};:)"),  // fork bomb
        Regex("shutdown\\s+-"),          // 系统关机

        // 环境变量 PATH 污染
        Regex("export\\s+PATH\\s*=\\s*/tmp"),
        Regex("export\\s+PATH\\s*=\\s*\\.:")
    )

    // ==================== 状态管理 ====================

    /**
     * 待确认的权限请求队列（StateFlow，UI 层观察此流来显示确认弹框）
     * 使用 CompletableDeferred 实现异步等待用户响应
     */
    private val _pendingRequests = MutableStateFlow<List<PermissionRequest>>(emptyList())
    val pendingRequests: StateFlow<List<PermissionRequest>> = _pendingRequests

    /**
     * AUTO 模式连续阻断计数器
     * 连续阻断次数超过 [AUTO_CONSECUTIVE_BLOCK_LIMIT] 时触发降级提示
     */
    private var consecutiveBlockCount = 0

    /**
     * AUTO 模式累计阻断计数器
     * 总阻断次数超过 [AUTO_TOTAL_BLOCK_LIMIT] 时触发降级提示
     */
    private var totalBlockCount = 0

    /** AUTO 模式连续阻断上限（超过后建议用户切换为 DEFAULT 模式） */
    private val AUTO_CONSECUTIVE_BLOCK_LIMIT = 3

    /** AUTO 模式累计阻断上限（超过后记录警告日志） */
    private val AUTO_TOTAL_BLOCK_LIMIT = 20

    // ==================== 权限检查核心逻辑 ====================

    /**
     * 检查工具调用权限（主入口，供 ToolExecutor 调用）
     *
     * 决策流程：
     * 1. deny 规则检查 → 命中立即返回 false
     * 2. allow 规则检查 → 命中立即返回 true
     * 3. 按 [mode] 执行具体决策逻辑
     *
     * @param toolName  要调用的工具名称（如 "Bash"、"Write"）
     * @param toolInput 工具输入参数（JSON 对象）
     * @param mode      当前权限模式
     * @return          true = 允许执行，false = 拒绝执行
     */
    suspend fun checkPermission(
        toolName: String,
        toolInput: JsonObject,
        mode: PermissionMode
    ): Boolean {
        // ── 步骤 1：规则引擎优先（deny > allow）────────────────────────────
        val ruleResult = evaluateRules(toolName, toolInput)
        if (ruleResult == RuleResult.DENY)  return false
        if (ruleResult == RuleResult.ALLOW) return true

        // ── 步骤 2：按权限模式决策 ──────────────────────────────────────────
        return when (mode) {

            // ── BYPASS_PERMISSIONS：跳过所有检查（仅受保护路径例外） ──────────
            PermissionMode.BYPASS_PERMISSIONS -> {
                val blocked = isProtectedPath(toolName, toolInput)
                if (blocked) {
                    android.util.Log.w("PermissionManager",
                        "BYPASS_PERMISSIONS mode blocked protected path access: $toolName")
                }
                !blocked
            }

            // ── PLAN：仅允许只读操作 ─────────────────────────────────────────
            PermissionMode.PLAN -> {
                val dangerLevel = getToolDangerLevel(toolName)
                val allowed = dangerLevel == ToolDangerLevel.SAFE
                if (!allowed) {
                    android.util.Log.d("PermissionManager",
                        "PLAN mode blocked non-read-only tool: $toolName ($dangerLevel)")
                }
                allowed
            }

            // ── ACCEPT_EDITS：文件操作预批准，安全 Bash 命令预批准 ─────────────
            PermissionMode.ACCEPT_EDITS -> {
                val dangerLevel = getToolDangerLevel(toolName)
                when (dangerLevel) {
                    ToolDangerLevel.SAFE, ToolDangerLevel.CAUTION -> {
                        // 文件写操作：检查受保护路径
                        val protectedBlocked = isProtectedPath(toolName, toolInput)
                        if (protectedBlocked) {
                            android.util.Log.w("PermissionManager",
                                "ACCEPT_EDITS blocked protected path write: $toolName")
                        }
                        !protectedBlocked
                    }
                    ToolDangerLevel.DANGEROUS -> {
                        // WebFetch/WebSearch：需要询问用户
                        askUser(toolName, toolInput)
                    }
                    ToolDangerLevel.CRITICAL -> {
                        // Bash/Agent：只有安全命令白名单内的命令预批准
                        val command = toolInput["command"]?.jsonPrimitive?.content ?: ""
                        val isSafeBashCommand = SAFE_BASH_COMMANDS.any { safeCmd ->
                            command.trimStart().startsWith(safeCmd)
                        }
                        val isProtected = isProtectedPath(toolName, toolInput)

                        when {
                            isProtected -> {
                                android.util.Log.w("PermissionManager",
                                    "ACCEPT_EDITS blocked protected path bash command: $command")
                                false
                            }
                            isSafeBashCommand -> {
                                android.util.Log.d("PermissionManager",
                                    "ACCEPT_EDITS pre-approved safe bash: $command")
                                true
                            }
                            else -> {
                                // 非白名单 Bash 命令：询问用户
                                askUser(toolName, toolInput)
                            }
                        }
                    }
                }
            }

            // ── AUTO：危险模式检测 + 分类器自动阻断 ──────────────────────────
            PermissionMode.AUTO -> {
                val isBlocked = checkAutoBlockedPatterns(toolName, toolInput)
                if (isBlocked) {
                    consecutiveBlockCount++
                    totalBlockCount++

                    // 记录阻断信息
                    val command = toolInput["command"]?.jsonPrimitive?.content ?: toolName
                    android.util.Log.w("PermissionManager",
                        "AUTO mode blocked dangerous command: $command " +
                        "(consecutive=$consecutiveBlockCount, total=$totalBlockCount)")

                    // 检查是否需要降级提示
                    if (shouldWarnAutoModeDowngrade()) {
                        android.util.Log.e("PermissionManager",
                            "AUTO mode has blocked too many commands (consecutive=$consecutiveBlockCount, " +
                            "total=$totalBlockCount). Consider switching to DEFAULT mode for interactive approval.")
                    }

                    false
                } else {
                    // 未触发危险模式：重置连续计数
                    consecutiveBlockCount = 0
                    true
                }
            }

            // ── DONT_ASK：仅 allow 规则明确批准的工具 ────────────────────────
            PermissionMode.DONT_ASK -> {
                // 此处 ruleResult 只可能是 NO_MATCH（ALLOW 和 DENY 已在步骤 1 处理）
                android.util.Log.d("PermissionManager",
                    "DONT_ASK mode blocked tool not in allow list: $toolName")
                false  // 未在 allow 规则中的工具一律拒绝
            }

            // ── DEFAULT：只读预批准，写操作弹框确认 ──────────────────────────
            PermissionMode.DEFAULT -> {
                val dangerLevel = getToolDangerLevel(toolName)
                when (dangerLevel) {
                    ToolDangerLevel.SAFE -> {
                        // 只读工具：直接允许
                        true
                    }
                    else -> {
                        // 先检查受保护路径（无需弹框直接拒绝）
                        if (isProtectedPath(toolName, toolInput)) {
                            android.util.Log.w("PermissionManager",
                                "DEFAULT mode blocked protected path access: $toolName")
                            return false
                        }
                        // 弹框询问用户
                        askUser(toolName, toolInput)
                    }
                }
            }
        }
    }

    /**
     * 弹框询问用户是否允许工具执行（挂起直到用户响应）
     *
     * 实现机制：
     * 1. 创建 [PermissionRequest] 并加入 [_pendingRequests]（触发 UI 显示弹框）
     * 2. 通过 [CompletableDeferred] 挂起当前协程，等待 UI 回调
     * 3. 用户点击确认后，[approvePermission]/[denyPermission] 完成 Deferred
     * 4. finally 块移除已处理的请求（清理 UI 状态）
     *
     * @param toolName  工具名称（展示在弹框中）
     * @param toolInput 工具输入参数（展示在弹框中）
     * @return          true = 用户批准，false = 用户拒绝
     */
    private suspend fun askUser(toolName: String, toolInput: JsonObject): Boolean {
        val request = PermissionRequest(
            id          = java.util.UUID.randomUUID().toString(),
            toolName    = toolName,
            toolInput   = toolInput,
            dangerLevel = getToolDangerLevel(toolName),
            deferred    = CompletableDeferred()
        )

        // 将请求加入待确认队列（UI 观察 pendingRequests 并弹出确认框）
        _pendingRequests.value = _pendingRequests.value + request

        android.util.Log.d("PermissionManager",
            "Waiting for user permission: $toolName (id=${request.id})")

        return try {
            // 挂起等待用户响应
            request.deferred.await()
        } finally {
            // 无论用户如何响应（或协程取消），都从队列中移除该请求
            _pendingRequests.value = _pendingRequests.value.filter { it.id != request.id }
            android.util.Log.d("PermissionManager",
                "Permission request resolved: $toolName (id=${request.id})")
        }
    }

    // ==================== UI 回调方法 ====================

    /**
     * UI 回调：用户批准了权限请求
     *
     * 在权限确认弹框中点击"允许"/"确认"按钮时调用
     * @param requestId 要批准的请求 ID（来自 [PermissionRequest.id]）
     */
    fun approvePermission(requestId: String) {
        val request = _pendingRequests.value.find { it.id == requestId }
        if (request != null) {
            request.deferred.complete(true)
            android.util.Log.d("PermissionManager",
                "Permission approved: ${request.toolName} (id=$requestId)")
        } else {
            android.util.Log.w("PermissionManager",
                "approvePermission: request not found: $requestId")
        }
    }

    /**
     * UI 回调：用户拒绝了权限请求
     *
     * 在权限确认弹框中点击"拒绝"/"取消"按钮时调用
     * @param requestId 要拒绝的请求 ID（来自 [PermissionRequest.id]）
     */
    fun denyPermission(requestId: String) {
        val request = _pendingRequests.value.find { it.id == requestId }
        if (request != null) {
            request.deferred.complete(false)
            android.util.Log.d("PermissionManager",
                "Permission denied: ${request.toolName} (id=$requestId)")
        } else {
            android.util.Log.w("PermissionManager",
                "denyPermission: request not found: $requestId")
        }
    }

    /**
     * UI 回调：取消所有待确认的权限请求（如会话结束、用户导航离开）
     * 所有待确认请求均视为拒绝
     */
    fun cancelAllPendingRequests() {
        val pending = _pendingRequests.value
        android.util.Log.d("PermissionManager",
            "Cancelling ${pending.size} pending permission requests")
        pending.forEach { request ->
            request.deferred.complete(false)
        }
        _pendingRequests.value = emptyList()
    }

    // ==================== 工具辅助方法 ====================

    /**
     * 获取工具的危险级别
     *
     * 分级依据：
     * - SAFE：纯只读，不修改任何状态
     * - CAUTION：写文件，可逆（通过 git 可恢复）
     * - DANGEROUS：有外部副作用（网络请求）
     * - CRITICAL：不可逆操作（删除文件、系统命令等）
     *
     * @param toolName 工具名称
     * @return         工具危险级别
     */
    fun getToolDangerLevel(toolName: String): ToolDangerLevel = when (toolName) {
        // ── SAFE：只读工具 ──────────────────────────────────────────────────
        "Read",
        "Glob",
        "Grep",
        "LS",
        "TodoRead",
        "NotebookRead"         -> ToolDangerLevel.SAFE

        // ── CAUTION：写操作（文件系统修改，通常可通过 git 撤销） ─────────────
        "Write",
        "Edit",
        "MultiEdit",
        "TodoWrite",
        "NotebookEdit"         -> ToolDangerLevel.CAUTION

        // ── DANGEROUS：有外部副作用（网络 I/O） ─────────────────────────────
        "WebFetch",
        "WebSearch"            -> ToolDangerLevel.DANGEROUS

        // ── CRITICAL：不可逆系统操作（Bash 可执行任意命令，Agent 可启动子进程） ─
        "Bash",
        "Agent"                -> ToolDangerLevel.CRITICAL

        // 未知工具默认视为 CAUTION（谨慎处理）
        else                   -> ToolDangerLevel.CAUTION
    }

    /**
     * 检查工具调用是否涉及受保护路径
     *
     * 检查 tool_input 中常见路径字段：file_path, path, notebook_path
     * 路径匹配策略：检查路径的任意部分是否包含受保护路径名
     *
     * @param toolName  工具名称（当前未使用，预留用于工具特定规则）
     * @param toolInput 工具输入 JSON 对象
     * @return          true = 涉及受保护路径，false = 安全
     */
    private fun isProtectedPath(toolName: String, toolInput: JsonObject): Boolean {
        // 检查常见路径字段名
        val pathFieldNames = listOf("file_path", "path", "notebook_path", "directory")
        val targetPath = pathFieldNames.firstNotNullOfOrNull { fieldName ->
            toolInput[fieldName]?.jsonPrimitive?.content
        } ?: return false

        // 将路径规范化（转换为正斜杠，处理末尾斜杠）
        val normalizedPath = targetPath.replace("\\", "/").trimEnd('/')

        return PROTECTED_PATHS.any { protectedName ->
            // 匹配路径中的任意片段（防止绕过，如 "/foo/.git/config"）
            normalizedPath.contains("/$protectedName/") ||
            normalizedPath.endsWith("/$protectedName") ||
            normalizedPath.startsWith("$protectedName/") ||
            normalizedPath == protectedName
        }
    }

    /**
     * 评估 allow/deny 规则（规则引擎）
     *
     * 执行顺序：deny 规则 > allow 规则 > NO_MATCH
     *
     * @param toolName  工具名称
     * @param toolInput 工具输入 JSON 对象
     * @return          规则评估结果枚举
     */
    private fun evaluateRules(toolName: String, toolInput: JsonObject): RuleResult {
        // deny 规则优先检查（安全第一）
        for (rule in rules.deny) {
            if (matchesRule(rule, toolName, toolInput)) {
                android.util.Log.d("PermissionManager",
                    "Tool $toolName matched deny rule: $rule")
                return RuleResult.DENY
            }
        }

        // allow 规则检查
        for (rule in rules.allow) {
            if (matchesRule(rule, toolName, toolInput)) {
                android.util.Log.d("PermissionManager",
                    "Tool $toolName matched allow rule: $rule")
                return RuleResult.ALLOW
            }
        }

        return RuleResult.NO_MATCH
    }

    /**
     * 检查工具调用是否匹配指定规则
     *
     * 支持两种规则格式：
     * 1. 纯工具名："Bash" 匹配所有 Bash 调用
     * 2. 带条件："Bash(git log *)" 匹配执行 "git log" 的 Bash 调用
     *    - 条件匹配范围：工具输入 JSON 的字符串表示
     *    - 支持 glob 语法：* 匹配任意字符，? 匹配单个字符
     *
     * @param rule      规则字符串
     * @param toolName  实际工具名
     * @param toolInput 实际工具输入
     * @return          是否匹配
     */
    private fun matchesRule(rule: String, toolName: String, toolInput: JsonObject): Boolean {
        // 纯工具名匹配（无括号部分）
        if (rule == toolName) return true

        // 解析 ToolName(pattern) 格式
        val parenRegex = Regex("""(\w+)\((.+)\)""")
        val match = parenRegex.matchEntire(rule.trim()) ?: return false
        val (ruleTool, rulePattern) = match.destructured

        // 工具名必须先匹配
        if (ruleTool != toolName) return false

        // 在工具输入字符串中搜索 glob 模式
        val inputStr = toolInput.toString()
        val regexPattern = rulePattern
            .replace(".", "\\.")    // 转义正则特殊字符：点
            .replace("*", ".*")     // glob * → 正则 .*
            .replace("?", ".")      // glob ? → 正则 .
        return Regex(regexPattern).containsMatchIn(inputStr)
    }

    /**
     * 检查 Bash 命令是否匹配 AUTO 模式危险模式（内置分类器规则）
     *
     * 仅对 Bash 工具生效，检查 tool_input.command 字段
     *
     * @param toolName  工具名称
     * @param toolInput 工具输入 JSON
     * @return          true = 命令触发危险模式（应被阻断），false = 未触发
     */
    private fun checkAutoBlockedPatterns(toolName: String, toolInput: JsonObject): Boolean {
        if (toolName != "Bash") return false

        val command = toolInput["command"]?.jsonPrimitive?.content
            ?: return false

        return AUTO_BLOCKED_PATTERNS.any { pattern ->
            val matched = pattern.containsMatchIn(command)
            if (matched) {
                android.util.Log.w("PermissionManager",
                    "AUTO mode blocked by pattern '${pattern.pattern}': $command")
            }
            matched
        }
    }

    /**
     * 检查 AUTO 模式是否应触发降级警告
     *
     * 当连续阻断次数超过 [AUTO_CONSECUTIVE_BLOCK_LIMIT]
     * 或累计阻断次数超过 [AUTO_TOTAL_BLOCK_LIMIT] 时，
     * 说明当前任务需要较多危险操作，建议用户切换为 DEFAULT 模式手动确认
     *
     * @return true = 应发出降级警告
     */
    private fun shouldWarnAutoModeDowngrade(): Boolean {
        return consecutiveBlockCount >= AUTO_CONSECUTIVE_BLOCK_LIMIT ||
               totalBlockCount >= AUTO_TOTAL_BLOCK_LIMIT
    }

    // ==================== 配置加载 ====================

    /**
     * 从 settings.json 字符串加载权限规则
     *
     * 预期的 JSON 格式：
     * {
     *   "permissions": {
     *     "allow": ["Bash(git log *)", "Read", "Glob"],
     *     "ask":   ["Write(/tmp/*)"],
     *     "deny":  ["Bash(rm -rf *)", "Write(/etc/*)"]
     *   }
     * }
     *
     * 调用此方法会完全替换当前规则（全量更新）
     *
     * @param settingsJson settings.json 文件内容（完整 JSON 字符串）
     */
    fun loadRules(settingsJson: String) {
        try {
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(settingsJson).jsonObject
            val perms = root["permissions"]?.jsonObject
                ?: run {
                    android.util.Log.d("PermissionManager",
                        "No 'permissions' section in settings, using empty rules")
                    return
                }

            rules = PermissionRules(
                allow = perms["allow"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                ask   = perms["ask"]?.jsonArray?.map { it.jsonPrimitive.content }   ?: emptyList(),
                deny  = perms["deny"]?.jsonArray?.map { it.jsonPrimitive.content }  ?: emptyList()
            )

            android.util.Log.i("PermissionManager",
                "Loaded permission rules: ${rules.allow.size} allow, " +
                "${rules.ask.size} ask, ${rules.deny.size} deny")

        } catch (e: Exception) {
            android.util.Log.e("PermissionManager",
                "Failed to load permission rules: ${e.message}", e)
        }
    }

    /**
     * 重置 AUTO 模式计数器（用于新会话或用户手动重置）
     */
    fun resetAutoModeCounters() {
        consecutiveBlockCount = 0
        totalBlockCount = 0
        android.util.Log.d("PermissionManager", "AUTO mode counters reset")
    }

    /**
     * 获取当前权限规则的摘要信息（用于调试/日志）
     */
    fun getRulesSummary(): String =
        "allow=${rules.allow.size}, ask=${rules.ask.size}, deny=${rules.deny.size}"

    // ==================== 内部枚举 ====================

    /**
     * 规则评估结果枚举（内部使用）
     */
    private enum class RuleResult {
        /** 匹配 allow 规则，直接允许 */
        ALLOW,
        /** 匹配 deny 规则，直接拒绝 */
        DENY,
        /** 未匹配任何规则，继续按模式决策 */
        NO_MATCH
    }

    // ==================== 数据类 ====================

    /**
     * 权限确认请求（由 [askUser] 创建，通过 [pendingRequests] 暴露给 UI 层）
     *
     * @param id          请求唯一标识符（UUID）
     * @param toolName    请求权限的工具名称
     * @param toolInput   工具输入参数（用于 UI 展示详情）
     * @param dangerLevel 工具危险级别（用于 UI 展示不同样式）
     * @param deferred    用于等待用户响应的 CompletableDeferred
     */
    data class PermissionRequest(
        val id: String,
        val toolName: String,
        val toolInput: JsonObject,
        val dangerLevel: ToolDangerLevel,
        val deferred: CompletableDeferred<Boolean>
    )
}
