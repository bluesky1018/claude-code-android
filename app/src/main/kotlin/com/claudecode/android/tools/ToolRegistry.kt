package com.claudecode.android.tools

import com.google.gson.JsonObject
import com.google.gson.JsonArray

/**
 * 工具执行结果数据类
 *
 * 封装工具执行后的输出内容和执行状态，统一所有工具的返回格式。
 *
 * @param output  工具输出的文本内容，成功时为正常结果，失败时为错误描述
 * @param isError 标识本次执行是否失败。true 表示出错，false 表示成功
 */
data class ToolResult(
    val output: String,
    val isError: Boolean = false
) {
    companion object {
        /**
         * 创建一个成功结果
         *
         * @param output 工具成功执行后返回的文本内容
         * @return 表示成功的 ToolResult 实例
         */
        fun success(output: String) = ToolResult(output = output, isError = false)

        /**
         * 创建一个错误结果
         *
         * @param message 描述错误原因的文本信息
         * @return 表示失败的 ToolResult 实例
         */
        fun error(message: String) = ToolResult(output = message, isError = true)
    }
}

/**
 * 工具定义数据类，用于向 Claude API 描述工具的能力
 *
 * Claude API 在发起工具调用前，需要接收工具的结构化定义，包括名称、用途说明
 * 和参数的 JSON Schema。此类负责承载这些信息。
 *
 * @param name        工具的唯一标识名称，需与 Tool.name 保持一致
 * @param description 向 Claude 描述工具功能的自然语言说明，越详细越好
 * @param inputSchema 描述工具接受参数的 JSON Schema 对象
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
)

/**
 * 工具接口
 *
 * 所有具体工具（ReadTool、WriteTool 等）必须实现此接口，以便工具注册表
 * 统一管理和调度。每个工具需要提供自身的描述信息以及实际执行逻辑。
 */
interface Tool {
    /**
     * 工具的唯一名称，如 "read_file"、"bash"。
     * Claude API 调用工具时会使用此名称来路由到正确的工具实现。
     */
    val name: String

    /**
     * 工具的功能描述，用于告诉 Claude 什么情况下应该调用这个工具。
     * 应该清晰说明工具的用途、适用场景和注意事项。
     */
    val description: String

    /**
     * 工具接受的输入参数的 JSON Schema 定义。
     * 必须是合法的 JSON Schema 对象，描述每个参数的类型、是否必需、含义等。
     */
    val inputSchema: JsonObject

    /**
     * 执行工具的核心方法（挂起函数，在协程中调用）
     *
     * @param input 包含工具调用参数的 JSON 对象，键名对应 inputSchema 中定义的参数名
     * @return ToolResult 封装执行结果，调用方可根据 isError 判断是否成功
     */
    suspend fun execute(input: JsonObject): ToolResult
}

/**
 * 工具注册表
 *
 * 负责管理所有可用工具的注册、查找和调度。工具注册表是工具系统的核心组件，
 * 充当工具的统一入口。Claude API 响应中包含 tool_use 块时，由注册表负责
 * 找到对应工具并执行。
 *
 * 使用示例：
 * ```kotlin
 * val registry = ToolRegistry()
 * registry.registerDefaults(workingDir = "/data/user/0/com.example/files")
 * val result = registry.execute("read_file", jsonObjectOf("file_path" to "/path/to/file.txt"))
 * ```
 */
class ToolRegistry {

    // 以工具名称为键，Tool 实例为值的注册表映射
    private val tools: MutableMap<String, Tool> = mutableMapOf()

    /**
     * 注册一个工具到注册表
     *
     * 如果已存在同名工具，新注册的工具会覆盖旧的。
     *
     * @param tool 要注册的工具实例
     */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    /**
     * 根据工具名称执行工具
     *
     * 找到对应工具后，将 input 参数传入工具的 execute 方法并返回结果。
     * 如果工具不存在，返回包含错误信息的 ToolResult。
     *
     * @param toolName 要执行的工具名称，必须与注册时的 Tool.name 一致
     * @param input    传递给工具的参数，键为参数名，值为参数值
     * @return ToolResult 工具执行结果，isError=true 时表示执行失败
     */
    suspend fun execute(toolName: String, input: JsonObject): ToolResult {
        val tool = tools[toolName]
            ?: return ToolResult.error("未找到工具: $toolName。已注册的工具: ${tools.keys.joinToString(", ")}")

        return try {
            tool.execute(input)
        } catch (e: Exception) {
            ToolResult.error("工具 '$toolName' 执行时发生未捕获异常: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 获取所有已注册工具的定义列表
     *
     * 返回的列表用于构造发送给 Claude API 的 tools 参数，告知 Claude
     * 当前会话中有哪些工具可用。
     *
     * @return List<ToolDefinition> 所有工具的定义信息列表
     */
    fun getAllDefinitions(): List<ToolDefinition> {
        return tools.values.map { tool ->
            ToolDefinition(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema
            )
        }
    }

    /**
     * 注册所有默认工具
     *
     * 初始化并注册工具系统内置的全部工具。此方法应在应用启动或会话开始时调用。
     * 每个工具都会使用提供的工作目录作为默认操作路径。
     *
     * @param workingDir 工具的默认工作目录（绝对路径），如 "/data/user/0/com.example/files"
     */
    fun registerDefaults(workingDir: String) {
        register(ReadTool())
        register(WriteTool())
        register(EditTool())
        register(GlobTool(workingDir))
        register(GrepTool(workingDir))
        register(LSTool(workingDir))
        register(BashTool(workingDir))
        register(WebSearchTool())
        register(WebFetchTool())
        register(TodoReadTool())
        register(TodoWriteTool())
    }

    /**
     * 检查特定名称的工具是否已注册
     *
     * @param toolName 工具名称
     * @return true 表示已注册，false 表示未注册
     */
    fun isRegistered(toolName: String): Boolean = tools.containsKey(toolName)

    /**
     * 获取所有已注册工具的名称列表
     *
     * @return 工具名称的 Set
     */
    fun getRegisteredToolNames(): Set<String> = tools.keys.toSet()
}
