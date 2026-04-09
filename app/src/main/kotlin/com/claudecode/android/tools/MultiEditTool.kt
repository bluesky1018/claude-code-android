package com.claudecode.android.tools

import kotlinx.serialization.json.*

/**
 * MultiEdit 工具 — 像素级复刻真实 Claude Code 的原子多处编辑工具
 * 
 * 与 Edit 工具的区别：
 * - Edit：每次调用只能修改一处
 * - MultiEdit：一次调用修改多处，所有修改要么全部成功，要么全部回滚（原子性）
 * 
 * 真实 Claude Code 中 MultiEdit 的使用场景：
 * - 重命名变量（多处替换）
 * - 更新函数签名（定义 + 所有调用处）
 * - 批量修复同类 bug
 */
class MultiEditTool : Tool {
    
    override val name = "MultiEdit"
    
    override val description = """
        Make multiple precise edits to a single file atomically.
        All edits are applied in order. If any edit fails, ALL edits are rolled back.
        
        Each edit in the `edits` array has:
        - old_string: The exact text to replace (must be unique in the file at the time of this edit)
        - new_string: The replacement text
        - replace_all: (optional, default false) Replace all occurrences instead of requiring uniqueness
        
        IMPORTANT: Edits are applied sequentially, so earlier edits may affect the uniqueness 
        of later edits. Plan your edits accordingly.
    """.trimIndent()
    
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("file_path") {
                put("type", "string")
                put("description", "Absolute path to the file to edit")
            }
            putJsonObject("edits") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("old_string") {
                            put("type", "string")
                            put("description", "The exact text to replace")
                        }
                        putJsonObject("new_string") {
                            put("type", "string")
                            put("description", "The replacement text")
                        }
                        putJsonObject("replace_all") {
                            put("type", "boolean")
                            put("description", "Replace all occurrences (default: false)")
                        }
                    }
                    putJsonArray("required") {
                        add("old_string")
                        add("new_string")
                    }
                }
                put("description", "Array of edits to apply atomically")
            }
        }
        putJsonArray("required") {
            add("file_path")
            add("edits")
        }
    }
    
    override suspend fun execute(input: JsonObject): ToolResult {
        val filePath = input["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing file_path")
        
        val editsArray = input["edits"]?.jsonArray
            ?: return ToolResult.error("Missing edits array")
        
        val file = java.io.File(filePath)
        if (!file.exists()) return ToolResult.error("File not found: $filePath")
        
        // 读取原始内容（用于回滚）
        val originalContent = file.readText()
        var currentContent = originalContent
        
        val appliedEdits = mutableListOf<String>()
        
        try {
            for ((index, editElement) in editsArray.withIndex()) {
                val edit = editElement.jsonObject
                val oldString = edit["old_string"]?.jsonPrimitive?.content
                    ?: return rollbackAndError(file, originalContent, "Edit #${index + 1}: missing old_string")
                val newString = edit["new_string"]?.jsonPrimitive?.content
                    ?: return rollbackAndError(file, originalContent, "Edit #${index + 1}: missing new_string")
                val replaceAll = edit["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
                
                if (!currentContent.contains(oldString)) {
                    return rollbackAndError(
                        file, originalContent,
                        "Edit #${index + 1}: old_string not found in file (may have been affected by a previous edit):\n${oldString.take(100)}"
                    )
                }
                
                if (!replaceAll) {
                    // 校验唯一性
                    val occurrences = currentContent.split(oldString).size - 1
                    if (occurrences > 1) {
                        return rollbackAndError(
                            file, originalContent,
                            "Edit #${index + 1}: old_string matches $occurrences locations. Use replace_all=true or provide more context."
                        )
                    }
                    currentContent = currentContent.replaceFirst(oldString, newString)
                } else {
                    currentContent = currentContent.replace(oldString, newString)
                }
                
                appliedEdits.add("Edit #${index + 1}: replaced ${if (replaceAll) "all" else "1"} occurrence(s)")
            }
            
            // 所有编辑成功，写入文件
            file.writeText(currentContent)
            
            return ToolResult.success(
                "Successfully applied ${appliedEdits.size} edits to $filePath:\n" +
                appliedEdits.joinToString("\n")
            )
            
        } catch (e: Exception) {
            // 异常时回滚
            file.writeText(originalContent)
            return ToolResult.error("Failed to apply edits (rolled back): ${e.message}")
        }
    }
    
    private fun rollbackAndError(file: java.io.File, original: String, message: String): ToolResult {
        file.writeText(original)  // 回滚
        return ToolResult.error("MultiEdit rolled back. $message")
    }
}
