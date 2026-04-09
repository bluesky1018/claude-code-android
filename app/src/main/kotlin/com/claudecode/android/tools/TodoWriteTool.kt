package com.claudecode.android.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Todo 列表写入工具
 *
 * 完全替换当前会话的 Todo 列表。接收一个 JSON 数组，
 * 将其内容完整地替换现有的 Todo 列表（不是追加，是覆盖）。
 *
 * 典型使用流程：
 * 1. 使用 TodoReadTool 读取当前 Todo 列表
 * 2. 在 JSON 数组中进行修改（添加、删除、更新状态等）
 * 3. 使用 TodoWriteTool 将修改后的完整列表写回
 *
 * 输入格式示例：
 * ─────────────────────────────────────────────────────────
 * {
 *   "todos": [
 *     {
 *       "id": "task-001",
 *       "content": "实现用户登录功能",
 *       "status": "completed",
 *       "activeForm": "正在实现用户登录功能"
 *     },
 *     {
 *       "id": "task-002",
 *       "content": "编写单元测试",
 *       "status": "in_progress",
 *       "activeForm": "正在编写单元测试"
 *     },
 *     {
 *       "id": "task-003",
 *       "content": "更新文档",
 *       "status": "pending",
 *       "activeForm": "正在更新文档"
 *     }
 *   ]
 * }
 * ─────────────────────────────────────────────────────────
 *
 * 状态约束（软约束，不强制报错，但建议遵守）：
 * - 同一时刻只应有一个 in_progress 状态的任务
 * - 如果有多个 in_progress 任务，工具会在返回结果中给出警告
 *
 * 存储说明：
 * - 使用 TodoStorage（与 TodoReadTool 共享的内存存储）
 * - 实际应用中应替换为 Room 数据库操作
 */
class TodoWriteTool : Tool {

    override val name: String = "todo_write"

    override val description: String = """
        完全替换当前会话的 Todo 列表。
        接收一个包含所有 Todo 项目的 JSON 数组，完整覆盖现有列表（不是追加）。

        每个 Todo 对象必须包含：
        - id: 唯一标识符（字符串）
        - content: 任务描述（祈使句）
        - status: "pending" | "in_progress" | "completed"
        - activeForm: 任务进行时的描述（现在进行时形式）

        注意：status 为 in_progress 的任务同时只应有一个。
        使用前建议先用 todo_read 获取当前列表，修改后再写回。
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 todo_write 工具接受的参数：
     * - todos（必需）：Todo 数组，完整替换当前列表
     */
    override val inputSchema: JsonObject = JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "todos": {
                    "type": "array",
                    "description": "要保存的 Todo 列表，会完整替换当前所有 Todo。传入空数组 [] 可清空所有 Todo。",
                    "items": {
                        "type": "object",
                        "properties": {
                            "id": {
                                "type": "string",
                                "description": "Todo 的唯一标识符（UUID 或其他唯一字符串）"
                            },
                            "content": {
                                "type": "string",
                                "description": "任务描述，使用祈使句形式，例如：'实现登录功能'、'修复空指针异常'"
                            },
                            "status": {
                                "type": "string",
                                "enum": ["pending", "in_progress", "completed"],
                                "description": "任务状态：pending（待开始）、in_progress（进行中）、completed（已完成）"
                            },
                            "activeForm": {
                                "type": "string",
                                "description": "任务进行时的描述，使用现在进行时形式，例如：'正在实现登录功能'"
                            }
                        },
                        "required": ["id", "content", "status", "activeForm"]
                    }
                }
            },
            "required": ["todos"]
        }
    """.trimIndent()).asJsonObject

    /** 允许的状态值 */
    private val VALID_STATUSES = setOf("pending", "in_progress", "completed")

    /**
     * 执行 Todo 列表写入操作
     *
     * @param input JSON 对象，包含以下字段：
     *   - todos: JsonArray（必需）完整的 Todo 列表
     *
     * @return ToolResult
     *   - 成功：写入成功的确认信息，包含各状态任务的数量统计
     *   - 失败：数据格式错误、数据库写入错误等
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 检查 todos 参数是否存在
        if (!input.has("todos")) {
            return@withContext ToolResult.error("缺少必需参数 'todos'")
        }

        val todosElement = input.get("todos")
        if (!todosElement.isJsonArray) {
            return@withContext ToolResult.error("参数 'todos' 必须是 JSON 数组")
        }

        val todosArray = todosElement.asJsonArray

        return@withContext try {
            // 解析和验证每个 Todo 项目
            val parsedTodos = mutableListOf<TodoItem>()
            val validationErrors = mutableListOf<String>()

            todosArray.forEachIndexed { index, element ->
                if (!element.isJsonObject) {
                    validationErrors.add("第 ${index + 1} 个 Todo 不是有效的 JSON 对象")
                    return@forEachIndexed
                }

                val todoObj = element.asJsonObject

                // 提取必需字段，缺失时生成默认值
                val id = todoObj.get("id")?.asString?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()  // 缺少 id 时自动生成 UUID

                val content = todoObj.get("content")?.asString
                if (content.isNullOrBlank()) {
                    validationErrors.add("第 ${index + 1} 个 Todo 缺少 'content' 字段或内容为空")
                    return@forEachIndexed
                }

                val status = todoObj.get("status")?.asString
                if (status == null || status !in VALID_STATUSES) {
                    validationErrors.add(
                        "第 ${index + 1} 个 Todo 的 'status' 无效: '$status'。" +
                        "有效值为: ${VALID_STATUSES.joinToString(", ")}"
                    )
                    return@forEachIndexed
                }

                val activeForm = todoObj.get("activeForm")?.asString ?: "正在处理: $content"

                parsedTodos.add(
                    TodoItem(
                        id = id,
                        content = content,
                        status = status,
                        activeForm = activeForm
                    )
                )
            }

            // 如果有验证错误，返回详细的错误信息
            if (validationErrors.isNotEmpty()) {
                return@withContext ToolResult.error(
                    "Todo 列表验证失败，以下项目有错误：\n" +
                    validationErrors.joinToString("\n") { "• $it" }
                )
            }

            // 检查是否有多个 in_progress 任务（软约束，发出警告但不拒绝）
            val inProgressCount = parsedTodos.count { it.status == "in_progress" }

            // 将解析好的列表写入存储
            TodoStorage.replaceAll(parsedTodos)

            // 统计各状态数量
            val pendingCount = parsedTodos.count { it.status == "pending" }
            val completedCount = parsedTodos.count { it.status == "completed" }

            // 构建成功响应
            val sb = StringBuilder()
            sb.appendLine("Todo 列表已更新，共 ${parsedTodos.size} 个任务：")
            sb.appendLine("  • 待开始 (pending):   $pendingCount 个")
            sb.appendLine("  • 进行中 (in_progress): $inProgressCount 个")
            sb.appendLine("  • 已完成 (completed):  $completedCount 个")

            // 如果有多个 in_progress 任务，给出警告
            if (inProgressCount > 1) {
                sb.appendLine()
                sb.appendLine("⚠️  警告：有 $inProgressCount 个任务处于 in_progress 状态。")
                sb.appendLine("   建议同时只保持一个任务为 in_progress。")
            }

            ToolResult.success(sb.toString().trimEnd())

        } catch (e: Exception) {
            ToolResult.error("写入 Todo 列表时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
