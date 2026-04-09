package com.claudecode.android.tools

import com.claudecode.android.agent.AgentLoop
import com.claudecode.android.agent.AgentSession
import kotlinx.serialization.json.*

/**
 * Agent 工具 — 创建子 Agent（子智能体）
 * 
 * 像素级复刻真实 Claude Code 的 Agent 工具核心机制：
 * - 子 Agent 在独立的上下文窗口中运行（不占父 Agent 的 200K token）
 * - 子 Agent 完成后只返回摘要给父 Agent
 * - 支持独立的工具集
 * - 父 Agent 等待子 Agent 完成（同步）
 * 
 * 真实 Claude Code 中，子 Agent 通过 .claude/agents/*.md 配置文件定义
 */
class AgentTool(
    private val agentLoopFactory: () -> AgentLoop
) : Tool {
    
    override val name = "Agent"
    
    override val description = """
        Launch a sub-agent to handle a specific subtask in an isolated context window.
        The sub-agent has its own conversation history and tool set.
        Results are summarized and returned to you when the sub-agent completes.
        
        Use when:
        - A subtask is complex enough to need many tool calls but doesn't need full context
        - You want to parallelize work (multiple sub-agents for independent tasks)
        - Delegating specialized work (e.g., "review this code", "research this API")
    """.trimIndent()
    
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "The task to give the sub-agent")
            }
            putJsonObject("description") {
                put("type", "string")
                put("description", "Short description of this sub-agent's purpose (3-5 words)")
            }
            putJsonObject("working_directory") {
                put("type", "string")
                put("description", "Working directory for the sub-agent (defaults to current)")
            }
            putJsonObject("tools") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "List of tool names available to sub-agent (defaults to all tools)")
            }
            putJsonObject("max_turns") {
                put("type", "integer")
                put("description", "Maximum iterations for the sub-agent (optional)")
            }
        }
        putJsonArray("required") { add("prompt") }
    }
    
    override suspend fun execute(input: JsonObject): ToolResult {
        val prompt = input["prompt"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing prompt for sub-agent")
        val description = input["description"]?.jsonPrimitive?.content ?: "Sub-agent"
        val workingDirectory = input["working_directory"]?.jsonPrimitive?.content
        val maxTurns = input["max_turns"]?.jsonPrimitive?.intOrNull
        
        return try {
            // 创建独立的子 Agent Session（独立上下文窗口）
            val subSession = AgentSession(
                workingDirectory = workingDirectory ?: "",
                isSubAgent = true,
                parentAgentDescription = description
            ).apply {
                this.maxTurns = maxTurns
            }
            
            // 启动子 Agent Loop
            val agentLoop = agentLoopFactory()
            var finalResult = ""
            
            agentLoop.run(
                userMessage = prompt,
                session = subSession,
                onComplete = { result ->
                    finalResult = result
                }
            )
            
            if (finalResult.isBlank()) {
                ToolResult.error("Sub-agent ($description) completed with no output")
            } else {
                ToolResult.success("Sub-agent result:\n$finalResult")
            }
            
        } catch (e: Exception) {
            ToolResult.error("Sub-agent ($description) failed: ${e.message}")
        }
    }
}
