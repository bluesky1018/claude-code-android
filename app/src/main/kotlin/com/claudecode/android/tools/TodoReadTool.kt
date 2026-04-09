package com.claudecode.android.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Todo 列表读取工具
 *
 * 读取当前会话的 Todo 列表，以 JSON 格式返回所有 Todo 项目。
 * Todo 存储在 Room 数据库中，通过 TodoRepository 访问。
 *
 * Todo 数据结构：
 * ─────────────────────────────────────────────────────────
 * {
 *   "id": "unique-id-1",           // 唯一标识符（UUID 字符串）
 *   "content": "实现登录功能",       // 任务描述（祈使句形式）
 *   "status": "pending",           // 状态：pending/in_progress/completed
 *   "activeForm": "正在实现登录功能"  // 任务进行时的描述形式（现在进行时）
 * }
 * ─────────────────────────────────────────────────────────
 *
 * status 字段的含义：
 * - pending     : 任务尚未开始
 * - in_progress : 任务正在进行中（同时只应有一个 in_progress 任务）
 * - completed   : 任务已完成
 *
 * 此工具与 TodoWriteTool 配合使用：
 * - 使用 TodoReadTool 获取当前状态
 * - 修改后使用 TodoWriteTool 写回（完整替换）
 *
 * 存储说明：
 * - 实际实现中，Todo 数据存储在 Room 数据库中
 * - 每个会话有独立的 todo 列表（通过 sessionId 区分）
 * - 当前实现使用内存存储作为简化版本，集成时替换为 Room 调用
 */
class TodoReadTool : Tool {

    override val name: String = "todo_read"

    override val description: String = """
        读取当前会话的 Todo 列表，以 JSON 数组格式返回所有任务。
        每个 Todo 包含：id（唯一ID）、content（任务描述）、status（状态）、activeForm（进行时描述）。
        status 可选值：pending（待开始）、in_progress（进行中）、completed（已完成）。
        配合 todo_write 使用，先读取当前列表，修改后再写回。
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * TodoReadTool 不需要任何参数，直接返回当前会话的所有 Todo。
     */
    override val inputSchema: JsonObject = JsonParser.parseString("""
        {
            "type": "object",
            "properties": {},
            "required": []
        }
    """.trimIndent()).asJsonObject

    /**
     * 执行 Todo 读取操作
     *
     * 返回当前会话所有 Todo 的 JSON 数组字符串。
     * 如果列表为空，返回空数组 `[]`。
     *
     * @param input JSON 对象（此工具不使用任何输入参数）
     *
     * @return ToolResult
     *   - 成功：JSON 格式的 Todo 列表字符串
     *   - 失败：数据库访问错误等
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        return@withContext try {
            // 从 TodoStorage 读取当前会话的 Todo 列表
            val todos = TodoStorage.getAll()

            if (todos.isEmpty()) {
                return@withContext ToolResult.success("[]")
            }

            // 将 Todo 列表序列化为 JSON 数组字符串
            val jsonArray = com.google.gson.JsonArray()
            todos.forEach { todo ->
                val todoObj = JsonObject().apply {
                    addProperty("id", todo.id)
                    addProperty("content", todo.content)
                    addProperty("status", todo.status)
                    addProperty("activeForm", todo.activeForm)
                }
                jsonArray.add(todoObj)
            }

            // 使用 Gson 格式化输出（带缩进，便于阅读）
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            ToolResult.success(gson.toJson(jsonArray))

        } catch (e: Exception) {
            ToolResult.error("读取 Todo 列表时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/**
 * Todo 数据类
 *
 * 表示一个 Todo 任务项目。
 *
 * @param id         唯一标识符（建议使用 UUID）
 * @param content    任务描述（祈使句形式，如"实现登录功能"）
 * @param status     任务状态：pending / in_progress / completed
 * @param activeForm 任务进行时的描述（现在进行时形式，如"正在实现登录功能"）
 */
data class TodoItem(
    val id: String,
    val content: String,
    val status: String,  // "pending" | "in_progress" | "completed"
    val activeForm: String
)

/**
 * Todo 内存存储（简化实现）
 *
 * 此对象充当内存中的 Todo 数据库，用于在会话期间存储和管理 Todo 列表。
 *
 * 集成说明：
 * ─────────────────────────────────────────────────────────
 * 在实际的 Android 应用中，应将此实现替换为 Room 数据库：
 *
 * 1. 定义 Room Entity：
 * ```kotlin
 * @Entity(tableName = "todos")
 * data class TodoEntity(
 *     @PrimaryKey val id: String,
 *     val content: String,
 *     val status: String,
 *     val activeForm: String,
 *     val sessionId: String,  // 关联会话ID
 *     val createdAt: Long = System.currentTimeMillis()
 * )
 * ```
 *
 * 2. 定义 Room DAO：
 * ```kotlin
 * @Dao
 * interface TodoDao {
 *     @Query("SELECT * FROM todos WHERE sessionId = :sessionId")
 *     suspend fun getBySession(sessionId: String): List<TodoEntity>
 *
 *     @Insert(onConflict = OnConflictStrategy.REPLACE)
 *     suspend fun insertAll(todos: List<TodoEntity>)
 *
 *     @Query("DELETE FROM todos WHERE sessionId = :sessionId")
 *     suspend fun deleteBySession(sessionId: String)
 * }
 * ```
 * ─────────────────────────────────────────────────────────
 */
object TodoStorage {
    // 内存存储（实际应用中替换为 Room 数据库调用）
    private val todos = mutableListOf<TodoItem>()

    /**
     * 获取所有 Todo 项目
     * @return 当前 Todo 列表的副本
     */
    fun getAll(): List<TodoItem> = todos.toList()

    /**
     * 完全替换 Todo 列表
     * @param newTodos 新的 Todo 列表（会完全覆盖现有列表）
     */
    fun replaceAll(newTodos: List<TodoItem>) {
        todos.clear()
        todos.addAll(newTodos)
    }
}
