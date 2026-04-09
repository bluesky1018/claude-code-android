package com.claudecode.android.tools

import kotlinx.serialization.json.*

/**
 * NotebookRead 工具 — 读取 Jupyter Notebook (.ipynb) 文件
 * 
 * Jupyter Notebook 格式说明：
 * - JSON 格式，包含 cells 数组
 * - 每个 cell 有 cell_type（code/markdown/raw）和 source
 * - code cell 还有 outputs 数组（执行结果）
 */
class NotebookReadTool : Tool {
    
    override val name = "NotebookRead"
    
    override val description = """
        Read a Jupyter Notebook (.ipynb) file and return its contents.
        Returns all cells with their type, source, and outputs in a readable format.
        For large notebooks, use cell_number to read specific cells.
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
                put("description", "Optional: read only this specific cell (0-indexed)")
            }
        }
        putJsonArray("required") { add("notebook_path") }
    }
    
    override suspend fun execute(input: JsonObject): ToolResult {
        val path = input["notebook_path"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing notebook_path")
        val cellNumber = input["cell_number"]?.jsonPrimitive?.intOrNull
        
        val file = java.io.File(path)
        if (!file.exists()) return ToolResult.error("Notebook not found: $path")
        if (!path.endsWith(".ipynb")) return ToolResult.error("File must have .ipynb extension")
        
        return try {
            val notebook = Json.parseToJsonElement(file.readText()).jsonObject
            val cells = notebook["cells"]?.jsonArray ?: return ToolResult.error("Invalid notebook: no cells")
            val kernelSpec = notebook["metadata"]?.jsonObject?.get("kernelspec")?.jsonObject
            val language = kernelSpec?.get("language")?.jsonPrimitive?.content ?: "unknown"
            
            val output = buildString {
                appendLine("Notebook: $path")
                appendLine("Language: $language")
                appendLine("Total cells: ${cells.size}")
                appendLine("=" .repeat(60))
                
                val targetCells = if (cellNumber != null) {
                    if (cellNumber < 0 || cellNumber >= cells.size)
                        return ToolResult.error("Cell #$cellNumber out of range (0-${cells.size - 1})")
                    listOf(Pair(cellNumber, cells[cellNumber].jsonObject))
                } else {
                    cells.mapIndexed { i, c -> Pair(i, c.jsonObject) }
                }
                
                for ((idx, cell) in targetCells) {
                    val cellType = cell["cell_type"]?.jsonPrimitive?.content ?: "unknown"
                    val source = cell["source"]?.let { src ->
                        when (src) {
                            is JsonArray -> src.joinToString("") { it.jsonPrimitive.content }
                            is JsonPrimitive -> src.content
                            else -> ""
                        }
                    } ?: ""
                    
                    appendLine("\n[Cell #$idx - $cellType]")
                    appendLine(source)
                    
                    // 输出结果（仅 code cell）
                    if (cellType == "code") {
                        val outputs = cell["outputs"]?.jsonArray ?: return@buildString
                        if (outputs.isNotEmpty()) {
                            appendLine("[Output]")
                            for (output in outputs) {
                                val outputObj = output.jsonObject
                                val outputType = outputObj["output_type"]?.jsonPrimitive?.content
                                when (outputType) {
                                    "stream" -> {
                                        val text = outputObj["text"]?.let { t ->
                                            when (t) {
                                                is JsonArray -> t.joinToString("") { it.jsonPrimitive.content }
                                                is JsonPrimitive -> t.content
                                                else -> ""
                                            }
                                        } ?: ""
                                        append(text)
                                    }
                                    "execute_result", "display_data" -> {
                                        val data = outputObj["data"]?.jsonObject
                                        val textOutput = data?.get("text/plain")?.let { t ->
                                            when (t) {
                                                is JsonArray -> t.joinToString("") { it.jsonPrimitive.content }
                                                is JsonPrimitive -> t.content
                                                else -> ""
                                            }
                                        }
                                        if (textOutput != null) appendLine(textOutput)
                                    }
                                    "error" -> {
                                        val ename = outputObj["ename"]?.jsonPrimitive?.content
                                        val evalue = outputObj["evalue"]?.jsonPrimitive?.content
                                        appendLine("ERROR: $ename: $evalue")
                                    }
                                }
                            }
                        }
                    }
                    appendLine("-".repeat(40))
                }
            }
            
            ToolResult.success(output)
        } catch (e: Exception) {
            ToolResult.error("Failed to parse notebook: ${e.message}")
        }
    }
}
