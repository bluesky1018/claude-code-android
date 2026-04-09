package com.claudecode.android.tools

import kotlinx.serialization.json.*

/**
 * NotebookEdit 工具 — 编辑 Jupyter Notebook (.ipynb) 文件
 * 
 * 支持操作：
 * - replace: 替换指定 cell 的内容
 * - insert: 在指定位置插入新 cell
 * - delete: 删除指定 cell
 */
class NotebookEditTool : Tool {
    
    override val name = "NotebookEdit"
    
    override val description = """
        Edit a Jupyter Notebook cell. Supports replace, insert, and delete operations.
        Cell numbers are 0-indexed.
        
        - edit_mode "replace": Replace the source of cell at cell_number with new_source
        - edit_mode "insert": Insert a new cell after cell_number (or at beginning if cell_id not specified)  
        - edit_mode "delete": Delete the cell at cell_number
    """.trimIndent()
    
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("notebook_path") {
                put("type", "string")
                put("description", "Absolute path to the .ipynb file")
            }
            putJsonObject("cell_number") {
                put("type", "integer")
                put("description", "The cell index to edit (0-indexed)")
            }
            putJsonObject("new_source") {
                put("type", "string")
                put("description", "New content for the cell")
            }
            putJsonObject("cell_type") {
                put("type", "string")
                put("description", "Cell type for insert: 'code' or 'markdown'")
            }
            putJsonObject("edit_mode") {
                put("type", "string")
                put("description", "Operation: 'replace', 'insert', or 'delete'")
            }
        }
        putJsonArray("required") {
            add("notebook_path")
            add("new_source")
        }
    }
    
    override suspend fun execute(input: JsonObject): ToolResult {
        val path = input["notebook_path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing notebook_path")
        val cellNumber = input["cell_number"]?.jsonPrimitive?.intOrNull ?: 0
        val newSource = input["new_source"]?.jsonPrimitive?.content ?: ""
        val cellType = input["cell_type"]?.jsonPrimitive?.content ?: "code"
        val editMode = input["edit_mode"]?.jsonPrimitive?.content ?: "replace"
        
        val file = java.io.File(path)
        if (!file.exists()) return ToolResult.error("Notebook not found: $path")
        
        return try {
            val notebookJson = Json.parseToJsonElement(file.readText()).jsonObject
            val cells = notebookJson["cells"]?.jsonArray?.toMutableList()
                ?: return ToolResult.error("Invalid notebook format")
            
            when (editMode) {
                "replace" -> {
                    if (cellNumber < 0 || cellNumber >= cells.size)
                        return ToolResult.error("Cell #$cellNumber out of range")
                    
                    val originalCell = cells[cellNumber].jsonObject
                    val updatedCell = buildJsonObject {
                        // 保留原始 cell 的所有字段
                        originalCell.forEach { (k, v) -> 
                            if (k != "source") put(k, v) 
                        }
                        // 替换 source
                        put("source", newSource)
                        // 清除旧的 outputs（代码变了，输出也应重置）
                        if (originalCell["cell_type"]?.jsonPrimitive?.content == "code") {
                            putJsonArray("outputs") {}
                            put("execution_count", JsonNull)
                        }
                    }
                    cells[cellNumber] = updatedCell
                }
                
                "insert" -> {
                    val newCell = buildJsonObject {
                        put("cell_type", cellType)
                        put("source", newSource)
                        put("metadata", buildJsonObject {})
                        if (cellType == "code") {
                            putJsonArray("outputs") {}
                            put("execution_count", JsonNull)
                        }
                        putJsonArray("id") {}  // nbformat 4.5+
                    }
                    val insertAt = minOf(cellNumber + 1, cells.size)
                    cells.add(insertAt, newCell)
                }
                
                "delete" -> {
                    if (cellNumber < 0 || cellNumber >= cells.size)
                        return ToolResult.error("Cell #$cellNumber out of range")
                    cells.removeAt(cellNumber)
                }
                
                else -> return ToolResult.error("Invalid edit_mode: $editMode. Use 'replace', 'insert', or 'delete'")
            }
            
            // 写回文件
            val updatedNotebook = buildJsonObject {
                notebookJson.forEach { (k, v) ->
                    if (k != "cells") put(k, v)
                }
                putJsonArray("cells") {
                    cells.forEach { add(it) }
                }
            }
            
            file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), updatedNotebook))
            ToolResult.success("Successfully $editMode cell #$cellNumber in $path")
            
        } catch (e: Exception) {
            ToolResult.error("Failed to edit notebook: ${e.message}")
        }
    }
}
