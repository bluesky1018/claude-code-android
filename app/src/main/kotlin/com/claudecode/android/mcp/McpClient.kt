package com.claudecode.android.mcp

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.*

/**
 * MCP (Model Context Protocol) 客户端 — 像素级复刻真实 Claude Code 的 MCP 机制
 *
 * MCP 是 Claude Code 最大的缺失功能之一（占比 0%），本文件实现基础 MCP 客户端。
 *
 * 真实 Claude Code MCP 工作方式：
 * 1. 配置 MCP Server：claude mcp add <name> <command>
 * 2. MCP Server 通过 stdin/stdout 通信（JSON-RPC 2.0 协议）
 * 3. MCP 工具自动注入到工具定义列表
 * 4. 工具名格式：mcp__<server-name>__<tool-name>
 *
 * 支持的 MCP Server 类型：
 * - stdio: 通过进程 stdin/stdout 通信（最常用）
 * - http: 通过 HTTP SSE 通信
 *
 * MCP 协议版本：2025-03-26（当前最新）
 */
class McpClient {

    companion object {
        private const val TAG = "McpClient"
        const val MCP_TOOL_PREFIX = "mcp__"  // 真实 Claude Code 的工具名前缀
        const val PROTOCOL_VERSION = "2025-03-26"
    }

    // ==================== MCP Server 配置 ====================

    /**
     * MCP Server 配置
     * 存储在 ~/.claude/mcp_servers.json 或 .claude.json 的 mcpServers 字段
     */
    data class McpServerConfig(
        val name: String,
        val type: ServerType,
        val command: String? = null,        // stdio 类型用
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val url: String? = null,            // http 类型用
        val enabled: Boolean = true,
        val deferLoading: Boolean = false   // 懒加载（真实 Claude Code 特性）
    ) {
        enum class ServerType { STDIO, HTTP }
    }

    // ==================== MCP Tool 定义 ====================

    /**
     * MCP Tool 描述（由 MCP Server 返回）
     * 格式化后以 mcp__<server>__<tool> 的形式注入到工具列表
     */
    data class McpToolDefinition(
        val serverName: String,
        val toolName: String,
        val description: String,
        val inputSchema: JsonObject,
        val eagerInputStreaming: Boolean = false,  // 真实 Claude Code 特性
        val strict: Boolean = false                 // 强制 JSON Schema 校验
    ) {
        /** 完整工具名（注入到 Anthropic API 的格式） */
        val fullName: String get() = "$MCP_TOOL_PREFIX${serverName}__$toolName"
    }

    // ==================== JSON-RPC 2.0 消息结构 ====================

    data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Long,
        val method: String,
        val params: JsonObject? = null
    )

    data class JsonRpcResponse(
        val jsonrpc: String,
        val id: Long?,
        val result: JsonElement? = null,
        val error: JsonRpcError? = null
    )

    data class JsonRpcError(
        val code: Int,
        val message: String,
        val data: JsonElement? = null
    )

    // ==================== 活跃连接管理 ====================

    private val activeConnections = mutableMapOf<String, McpConnection>()
    private val discoveredTools = mutableMapOf<String, List<McpToolDefinition>>()
    private var requestIdCounter = 1L

    // ==================== 连接管理 ====================

    /**
     * 连接到 MCP Server 并发现工具
     * @return 发现的工具列表
     */
    suspend fun connect(config: McpServerConfig): List<McpToolDefinition> {
        if (!config.enabled) return emptyList()

        return try {
            val connection = when (config.type) {
                McpServerConfig.ServerType.STDIO -> connectStdio(config)
                McpServerConfig.ServerType.HTTP -> connectHttp(config)
            }

            activeConnections[config.name] = connection

            // 初始化握手
            initialize(connection, config.name)

            // 发现工具列表
            val tools = discoverTools(connection, config.name)
            discoveredTools[config.name] = tools

            Log.i(TAG, "Connected to MCP server '${config.name}', discovered ${tools.size} tools")
            tools

        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to MCP server '${config.name}': ${e.message}")
            emptyList()
        }
    }

    /**
     * 执行 MCP 工具调用
     * @param fullToolName 格式：mcp__serverName__toolName
     */
    suspend fun callTool(fullToolName: String, toolInput: JsonObject): String {
        // 解析工具名
        val parts = fullToolName.removePrefix(MCP_TOOL_PREFIX).split("__", limit = 2)
        if (parts.size != 2) return "Error: invalid MCP tool name format: $fullToolName"

        val (serverName, toolName) = parts
        val connection = activeConnections[serverName]
            ?: return "Error: MCP server '$serverName' not connected"

        return try {
            val request = JsonRpcRequest(
                id = requestIdCounter++,
                method = "tools/call",
                params = buildJsonObject {
                    put("name", toolName)
                    put("arguments", toolInput)
                }
            )

            val response = sendRequest(connection, request)

            if (response.error != null) {
                "MCP Error ${response.error.code}: ${response.error.message}"
            } else {
                parseToolResult(response.result)
            }

        } catch (e: Exception) {
            "MCP tool call failed: ${e.message}"
        }
    }

    /**
     * 获取所有已发现工具的 API 定义（注入到 Anthropic API 的工具列表）
     */
    fun getAllToolDefinitions(): List<JsonObject> {
        return discoveredTools.values.flatten().map { mcpTool ->
            buildJsonObject {
                put("name", mcpTool.fullName)
                put("description", mcpTool.description)
                put("input_schema", mcpTool.inputSchema)
            }
        }
    }

    /**
     * 检查工具名是否为 MCP 工具
     */
    fun isMcpTool(toolName: String): Boolean = toolName.startsWith(MCP_TOOL_PREFIX)

    /**
     * 断开所有 MCP Server 连接
     */
    fun disconnectAll() {
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
        discoveredTools.clear()
    }

    // ==================== 私有方法 ====================

    private fun connectStdio(config: McpServerConfig): McpConnection {
        val commandParts = mutableListOf<String>()
        if (config.command != null) commandParts.add(config.command)
        commandParts.addAll(config.args)

        val processBuilder = ProcessBuilder(commandParts)
            .redirectErrorStream(false)

        // 注入环境变量
        if (config.env.isNotEmpty()) {
            processBuilder.environment().putAll(config.env)
        }

        val process = processBuilder.start()
        return McpConnection.Stdio(process)
    }

    private fun connectHttp(config: McpServerConfig): McpConnection {
        val url = config.url ?: throw IllegalArgumentException("HTTP MCP server requires url")
        return McpConnection.Http(url)
    }

    /**
     * MCP 初始化握手
     * 真实协议版本：2025-03-26
     */
    private suspend fun initialize(connection: McpConnection, serverName: String) {
        val request = JsonRpcRequest(
            id = requestIdCounter++,
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                putJsonObject("clientInfo") {
                    put("name", "claude-code-android")
                    put("version", "1.0.0")
                }
                putJsonObject("capabilities") {
                    putJsonObject("tools") {}
                    putJsonObject("resources") {}
                    putJsonObject("prompts") {}
                }
            }
        )

        sendRequest(connection, request)

        // 发送 initialized 通知（JSON-RPC notification，无 id）
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        sendNotification(connection, notification)
    }

    /**
     * 发现 MCP Server 提供的工具列表
     */
    private suspend fun discoverTools(connection: McpConnection, serverName: String): List<McpToolDefinition> {
        val request = JsonRpcRequest(
            id = requestIdCounter++,
            method = "tools/list"
        )

        val response = sendRequest(connection, request)
        if (response.error != null) return emptyList()

        val toolsArray = response.result?.jsonObject?.get("tools")?.jsonArray ?: return emptyList()

        return toolsArray.mapNotNull { toolElement ->
            try {
                val toolObj = toolElement.jsonObject
                val toolName = toolObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val description = toolObj["description"]?.jsonPrimitive?.content ?: ""
                val inputSchema = toolObj["inputSchema"]?.jsonObject ?: buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                }

                McpToolDefinition(
                    serverName = serverName,
                    toolName = toolName,
                    description = description,
                    inputSchema = inputSchema
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseToolResult(result: JsonElement?): String {
        if (result == null) return ""
        val content = result.jsonObject["content"]?.jsonArray ?: return result.toString()

        return content.joinToString("\n") { element ->
            val obj = element.jsonObject
            when (obj["type"]?.jsonPrimitive?.content) {
                "text" -> obj["text"]?.jsonPrimitive?.content ?: ""
                "image" -> "[Image: ${obj["mimeType"]?.jsonPrimitive?.content}]"
                "resource" -> "[Resource: ${obj["uri"]?.jsonPrimitive?.content}]"
                else -> obj.toString()
            }
        }
    }

    private suspend fun sendRequest(connection: McpConnection, request: JsonRpcRequest): JsonRpcResponse {
        val json = buildJsonObject {
            put("jsonrpc", request.jsonrpc)
            put("id", request.id)
            put("method", request.method)
            if (request.params != null) put("params", request.params)
        }.toString()

        val responseJson = connection.sendAndReceive(json)

        return try {
            val obj = Json.parseToJsonElement(responseJson).jsonObject
            JsonRpcResponse(
                jsonrpc = obj["jsonrpc"]?.jsonPrimitive?.content ?: "2.0",
                id = obj["id"]?.jsonPrimitive?.longOrNull,
                result = obj["result"],
                error = obj["error"]?.jsonObject?.let { errorObj ->
                    JsonRpcError(
                        code = errorObj["code"]?.jsonPrimitive?.int ?: -1,
                        message = errorObj["message"]?.jsonPrimitive?.content ?: "Unknown error",
                        data = errorObj["data"]
                    )
                }
            )
        } catch (e: Exception) {
            JsonRpcResponse(jsonrpc = "2.0", id = request.id,
                error = JsonRpcError(-32603, "Failed to parse response: ${e.message}"))
        }
    }

    private fun sendNotification(connection: McpConnection, notification: JsonObject) {
        connection.send(notification.toString())
    }

    // ==================== 连接抽象 ====================

    sealed class McpConnection {
        abstract suspend fun sendAndReceive(json: String): String
        abstract fun send(json: String)
        abstract fun close()

        /**
         * Stdio 连接 — 通过进程 stdin/stdout 通信
         * 这是最常用的 MCP Server 连接方式
         */
        class Stdio(private val process: Process) : McpConnection() {
            private val writer = process.outputStream.bufferedWriter()
            private val reader = process.inputStream.bufferedReader()
            private val mutex = kotlinx.coroutines.sync.Mutex()

            override suspend fun sendAndReceive(json: String): String {
                return mutex.withLock {
                    withContext(Dispatchers.IO) {
                        writer.write(json + "\n")
                        writer.flush()
                        reader.readLine() ?: throw IOException("MCP server closed connection")
                    }
                }
            }

            override fun send(json: String) {
                writer.write(json + "\n")
                writer.flush()
            }

            override fun close() {
                writer.close()
                reader.close()
                process.destroy()
            }
        }

        /**
         * HTTP 连接 — 通过 HTTP POST 通信（简化实现）
         */
        class Http(private val url: String) : McpConnection() {
            override suspend fun sendAndReceive(json: String): String {
                return withContext(Dispatchers.IO) {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.outputStream.write(json.toByteArray())
                    connection.inputStream.bufferedReader().readText()
                }
            }

            override fun send(json: String) {
                // HTTP MCP 的通知通过 fire-and-forget POST 发送
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { sendAndReceive(json) }
                }
            }

            override fun close() {}
        }
    }

    /**
     * 从 JSON 配置加载 MCP Server 列表
     * 格式与 .claude.json 的 mcpServers 字段完全一致
     */
    fun loadConfigs(json: String): List<McpServerConfig> {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val servers = root["mcpServers"]?.jsonObject ?: return emptyList()

            servers.map { (name, value) ->
                val obj = value.jsonObject
                val typeStr = obj["type"]?.jsonPrimitive?.content ?: "stdio"
                McpServerConfig(
                    name = name,
                    type = if (typeStr == "http") McpServerConfig.ServerType.HTTP else McpServerConfig.ServerType.STDIO,
                    command = obj["command"]?.jsonPrimitive?.content,
                    args = obj["args"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    env = obj["env"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                    url = obj["url"]?.jsonPrimitive?.content,
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    deferLoading = obj["deferLoading"]?.jsonPrimitive?.booleanOrNull ?: false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MCP configs: ${e.message}")
            emptyList()
        }
    }
}
