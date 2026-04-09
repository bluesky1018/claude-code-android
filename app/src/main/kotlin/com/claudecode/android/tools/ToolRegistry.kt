package com.claudecode.android.tools

import com.claudecode.android.agent.AgentLoop
import kotlinx.serialization.json.*

/**
 * 工具注册表 — 管理所有可用工具
 *
 * 真实 Claude Code 工具列表（共 15 个核心工具 + MCP 动态工具）：
 * 核心工具：Read, Write, Edit, MultiEdit, Glob, Grep, LS, Bash,
 *           WebSearch, WebFetch, TodoRead, TodoWrite,
 *           NotebookRead, NotebookEdit, Agent
 * MCP 工具：通过 MCP Client 动态注入（格式：mcp__server__tool）
 */
class ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    /**
     * 注册默认工具集
     * @param workingDirectory 工作目录
     * @param agentLoopFactory Agent 工具需要的工厂函数
     */
    fun registerDefaults(
        workingDirectory: String,
        agentLoopFactory: (() -> AgentLoop)? = null
    ) {
        // 只读安全工具
        register(ReadTool())
        register(GlobTool())
        register(GrepTool())
        register(LSTool())
        register(TodoReadTool())
        register(NotebookReadTool())

        // 写操作工具（需要 CAUTION 级别权限）
        register(WriteTool())
        register(EditTool())
        register(MultiEditTool())
        register(TodoWriteTool())
        register(NotebookEditTool())

        // 危险工具（需要 DANGEROUS/CRITICAL 权限）
        register(BashTool())
        register(WebSearchTool())
        register(WebFetchTool())

        // Agent 工具（需要 agentLoopFactory）
        if (agentLoopFactory != null) {
            register(AgentTool(agentLoopFactory))
        }
    }

    /** 注册单个工具 */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /** 注销工具 */
    fun unregister(toolName: String) {
        tools.remove(toolName)
    }

    /** 执行工具调用 */
    suspend fun execute(toolUse: ToolUse): ToolResult {
        val tool = tools[toolUse.name]
            ?: return ToolResult.error("Unknown tool: ${toolUse.name}. Available: ${tools.keys.sorted().joinToString(", ")}")
        return try {
            tool.execute(toolUse.input)
        } catch (e: Exception) {
            ToolResult.error("Tool ${toolUse.name} threw exception: ${e.message}")
        }
    }

    /** 获取所有工具的 API 定义（发送给 Claude 的工具列表） */
    fun getAllDefinitions(): List<JsonObject> = tools.values.map { tool ->
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("input_schema", tool.inputSchema)
        }
    }

    /** 获取工具名列表 */
    fun getToolNames(): List<String> = tools.keys.sorted()

    /** 检查工具是否存在 */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /** 获取工具危险级别 */
    fun getDangerLevel(toolName: String): com.claudecode.android.session.PermissionManager.ToolDangerLevel {
        return when (toolName) {
            "Read", "Glob", "Grep", "LS", "TodoRead", "NotebookRead" ->
                com.claudecode.android.session.PermissionManager.ToolDangerLevel.SAFE
            "Write", "Edit", "MultiEdit", "TodoWrite", "NotebookEdit" ->
                com.claudecode.android.session.PermissionManager.ToolDangerLevel.CAUTION
            "WebFetch", "WebSearch" ->
                com.claudecode.android.session.PermissionManager.ToolDangerLevel.DANGEROUS
            "Bash", "Agent" ->
                com.claudecode.android.session.PermissionManager.ToolDangerLevel.CRITICAL
            else -> if (toolName.startsWith("mcp__"))
                com.claudecode.android.session.PermissionManager.ToolDangerLevel.CAUTION
            else
                com.claudecode.android.session.PermissionManager.ToolDangerLevel.CAUTION
        }
    }
}

/** 工具调用请求 */
data class ToolUse(
    val id: String,
    val name: String,
    val input: JsonObject
)

/** 工具执行结果 */
data class ToolResult(
    val output: String,
    val isError: Boolean = false
) {
    companion object {
        fun success(output: String) = ToolResult(output, false)
        fun error(message: String) = ToolResult(message, true)
    }
}

/** 工具接口 */
interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    suspend fun execute(input: JsonObject): ToolResult
}
