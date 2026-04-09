package com.claudecode.android.agent

import com.claudecode.android.memory.MemoryContext

/**
 * 系统提示词构建器 — 像素级复刻真实 Claude Code 的系统提示词
 *
 * 真实 Claude Code 的系统提示词约 5000 tokens，包含：
 * 1. 身份定义
 * 2. 工具使用规范（每个工具的详细限制）
 * 3. 行为准则
 * 4. 安全约束
 * 5. 动态环境信息
 * 6. CLAUDE.md 注入
 * 7. Auto Memory 注入
 * 8. Skills 注入（如有）
 */
object SystemPromptBuilder {

    /** 受保护路径 — 任何模式都不得自动写入 */
    val PROTECTED_PATHS = setOf(
        ".git", ".vscode", ".idea", ".husky",
        ".gitconfig", ".gitmodules", ".bashrc", ".zshrc",
        ".mcp.json", ".claude.json"
    )

    /**
     * 构建完整系统提示词
     * @param workingDirectory 当前工作目录
     * @param memoryContext CLAUDE.md 记忆上下文
     * @param autoMemory MEMORY.md 内容（最多 200 行/25KB）
     * @param availableTools 当前可用工具列表
     * @param skills 已加载的 Skills 描述
     * @param permissionMode 当前权限模式
     * @param currentDate 当前日期
     */
    fun build(
        workingDirectory: String,
        memoryContext: MemoryContext? = null,
        autoMemory: String? = null,
        availableTools: List<String> = emptyList(),
        skills: List<SkillDescription> = emptyList(),
        permissionMode: String = "default",
        currentDate: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()),
        osInfo: String = "Android ${android.os.Build.VERSION.RELEASE}",
        platform: String = "android"
    ): String {
        return buildString {
            appendLine(IDENTITY_SECTION)
            appendLine()
            appendLine(CAPABILITIES_SECTION)
            appendLine()
            appendLine(buildEnvironmentSection(workingDirectory, currentDate, osInfo, platform))
            appendLine()
            appendLine(TOOL_GUIDELINES_SECTION)
            appendLine()
            appendLine(BEHAVIOR_PRINCIPLES_SECTION)
            appendLine()
            appendLine(SAFETY_CONSTRAINTS_SECTION)
            appendLine()
            appendLine(buildPermissionSection(permissionMode))

            // 注入 CLAUDE.md 记忆
            if (memoryContext != null && memoryContext.hasContent()) {
                appendLine()
                appendLine("<CLAUDE_MD_CONTENTS>")
                appendLine(memoryContext.combinedContent)
                appendLine("</CLAUDE_MD_CONTENTS>")
            }

            // 注入 Auto Memory (MEMORY.md)
            if (!autoMemory.isNullOrBlank()) {
                appendLine()
                appendLine("<MEMORY_MD_CONTENTS>")
                appendLine(autoMemory.lines().take(200).joinToString("\n"))
                appendLine("</MEMORY_MD_CONTENTS>")
            }

            // 注入 Skills
            if (skills.isNotEmpty()) {
                appendLine()
                appendLine("<AVAILABLE_SKILLS>")
                skills.forEach { skill ->
                    appendLine("## ${skill.name}")
                    appendLine(skill.description)
                    appendLine()
                }
                appendLine("</AVAILABLE_SKILLS>")
            }
        }
    }

    // ==================== 各节内容 ====================

    /** 身份定义 — 与真实 Claude Code 高度一致 */
    private val IDENTITY_SECTION = """
        You are Claude Code, Anthropic's official AI assistant for software development, running on Android.
        You help developers write, edit, debug, and understand code.
        You operate in an agentic loop: you think, use tools, observe results, and repeat until the task is complete.

        You are precise, helpful, and efficient. You do what is asked — nothing more, nothing less.
        You never make up information. If you are unsure, you say so.
    """.trimIndent()

    /** 能力说明 */
    private val CAPABILITIES_SECTION = """
        ## Core Capabilities

        You can:
        - Read, write, and edit files in the working directory
        - Execute shell commands (Bash)
        - Search files by name (Glob) or content (Grep)
        - Browse the web (WebSearch, WebFetch)
        - Manage TODO lists (TodoRead, TodoWrite)
        - Spawn sub-agents for complex subtasks (Agent)
        - Read and edit Jupyter notebooks (NotebookRead, NotebookEdit)

        You cannot:
        - Access the internet except through the WebSearch and WebFetch tools
        - Run GUI applications
        - Access hardware beyond what Android provides
    """.trimIndent()

    /** 动态环境信息 */
    private fun buildEnvironmentSection(
        workingDirectory: String,
        currentDate: String,
        osInfo: String,
        platform: String
    ): String = """
        ## Current Environment

        - Date: $currentDate
        - Platform: $platform
        - OS: $osInfo
        - Working Directory: $workingDirectory
        - Shell: ${if (isTermuxAvailable()) "bash (via Termux)" else "Android shell (limited)"}
    """.trimIndent()

    private fun isTermuxAvailable(): Boolean = try {
        Class.forName("com.termux.app.TermuxActivity")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    /** 工具使用详细规范 — 像素级对标真实 Claude Code */
    private val TOOL_GUIDELINES_SECTION = """
        ## Tool Usage Guidelines

        ### Read Tool
        - Use for reading file contents
        - When reading large files, use offset and limit parameters
        - Prefer to read only what you need; don't read entire codebases
        - Line numbers start at 1

        ### Write Tool
        - Creates new files or overwrites existing ones
        - ALWAYS read a file before overwriting it to avoid losing content
        - Prefer Edit over Write for modifying existing files
        - Automatically creates parent directories

        ### Edit Tool
        - For making precise changes to existing files
        - old_string MUST be unique in the file — if not, use replace_all=true or provide more context
        - Preserve exact indentation (tabs vs spaces)
        - Prefer small, focused edits over large rewrites

        ### MultiEdit Tool
        - For making multiple edits to the same file atomically
        - All edits applied in order; if any fails, all are rolled back
        - Use when making several related changes to a single file

        ### Glob Tool
        - Returns files sorted by modification time (most recently modified first)
        - Maximum 1000 results
        - Supports patterns like "**/*.kt", "src/**/*.{ts,tsx}"
        - Use before Read to find relevant files

        ### Grep Tool
        - Uses regex for content search
        - Searches recursively by default
        - Skips .git, build, node_modules, .gradle directories
        - Maximum 50 results with context lines
        - Use -i for case-insensitive search

        ### Bash Tool
        - For executing shell commands
        - Default timeout: 30 seconds
        - Working directory persists between calls in the same session
        - Use for git commands, build commands, test running
        - CAUTION: Side effects are real and irreversible

        ### WebSearch Tool
        - Always include a "Sources:" section at the end of your response when using search results
        - Search results may be outdated; verify critical information

        ### WebFetch Tool
        - Fetches web page content as text
        - HTML is converted to readable text
        - Maximum 10,000 characters returned

        ### TodoRead / TodoWrite Tools
        - Use to track progress on multi-step tasks
        - Update todo status as you complete each step
        - Keep exactly one task in_progress at a time

        ### Agent Tool
        - Creates a sub-agent with its own isolated context window
        - Use for independent subtasks that don't need the full conversation history
        - Sub-agent result is summarized back to parent context
    """.trimIndent()

    /** 行为准则 — 完全对标真实 Claude Code 规则 */
    private val BEHAVIOR_PRINCIPLES_SECTION = """
        ## Behavior Principles

        1. **Do what is asked, nothing more**: Complete the exact task requested. Do not add unrequested features.
        2. **Prefer editing over creating**: Always prefer to edit existing files rather than creating new ones.
        3. **No proactive documentation**: Do not create README.md, CHANGELOG.md, or similar documentation files unless explicitly asked.
        4. **No unsolicited improvements**: Do not refactor, rename, or "improve" code that wasn't mentioned in the request.
        5. **Minimal footprint**: Make the smallest change necessary to accomplish the task.
        6. **No emojis in files**: Never add emojis to files or code unless explicitly asked.
        7. **Verify before delete**: Never delete files or directories without explicit confirmation.
        8. **Explain destructive actions**: When a Bash command has irreversible effects, explain what it does before running.
        9. **Check uniqueness before Edit**: Before using the Edit tool, verify old_string is unique in the file.
        10. **Use absolute paths**: Always use absolute paths in tool calls, never relative paths.
        11. **Batching is efficient**: When making multiple edits to a file, use MultiEdit rather than multiple Edit calls.
        12. **Read before edit**: Always read a file before editing it (unless creating new).
    """.trimIndent()

    /** 安全约束 */
    private val SAFETY_CONSTRAINTS_SECTION = """
        ## Safety Constraints

        ### Protected Paths (never write without explicit permission)
        - .git/ directory and all contents
        - .vscode/, .idea/ (IDE configurations)
        - .husky/ (git hooks)
        - .gitconfig, .gitmodules
        - .bashrc, .zshrc, .profile (shell configs)
        - .mcp.json, .claude.json (Claude configs)
        - /etc/, /usr/, /bin/, /sbin/ (system directories)

        ### Dangerous Commands Requiring Confirmation
        - rm -rf (recursive delete)
        - git push --force (force push)
        - DROP TABLE, DELETE FROM (destructive SQL)
        - chmod 777, chown root (permission changes)
        - curl | bash (download and execute)
        - Any command modifying system files

        ### Never Do
        - Execute downloaded/untrusted code without review
        - Store API keys or secrets in files that will be committed
        - Make network requests with sensitive user data
        - Modify .git internals directly
    """.trimIndent()

    /** 权限模式相关指令 */
    private fun buildPermissionSection(mode: String): String = when (mode) {
        "acceptEdits" -> """
            ## Permission Mode: Accept Edits
            File read/write/edit operations are pre-approved.
            Bash commands still require confirmation unless they are safe (mkdir, touch, mv, cp, sed, ls, cat, echo).
        """.trimIndent()

        "bypassPermissions" -> """
            ## Permission Mode: Bypass All Permissions
            All operations are pre-approved. Use with caution in isolated/sandboxed environments only.
        """.trimIndent()

        "plan" -> """
            ## Permission Mode: Plan Only
            You may ONLY read files and gather information. Do NOT execute any write operations, Bash commands, or modifications.
            Present your plan and wait for the user to switch to a different mode before executing.
        """.trimIndent()

        "auto" -> """
            ## Permission Mode: Auto
            An AI classifier reviews each operation. Clearly dangerous operations (rm -rf, force push to main) are blocked automatically.
        """.trimIndent()

        else -> """
            ## Permission Mode: Default
            Read operations are pre-approved.
            Write, Edit, and Bash operations require user confirmation unless they are clearly safe.
        """.trimIndent()
    }

    data class SkillDescription(
        val name: String,
        val description: String,
        val triggerKeyword: String? = null
    )
}
