package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件读取工具
 *
 * 读取指定文件的内容，支持按行范围读取，返回带行号的格式化文本。
 * 每行输出格式为：`     1\t第一行内容`（行号右对齐5位，后跟制表符，再跟行内容）。
 *
 * 该工具对应 Claude Code 中的 read_file / Read 工具，主要用途：
 * - 查看源代码文件
 * - 读取配置文件
 * - 检查日志文件的特定片段
 *
 * 单次最多读取 2000 行，防止超大文件导致内存溢出或响应过长。
 */
class ReadTool : Tool {

    override val name: String = "read_file"

    override val description: String = """
        读取文件内容并以带行号的格式返回。
        支持通过 offset 和 limit 参数读取文件的特定行范围。
        单次最多读取 2000 行。
        返回格式：每行以行号开头（右对齐5位），后跟制表符，再跟行内容。
        同时返回文件总行数和是否被截断的元信息。
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 read_file 工具接受的参数：
     * - file_path（必需）：要读取的文件绝对路径
     * - offset（可选）：从第几行开始读取（1-based，默认从第1行开始）
     * - limit（可选）：最多读取多少行（默认2000行）
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "要读取的文件的绝对路径，例如 /data/user/0/com.example/files/main.kt"
                },
                "offset": {
                    "type": "integer",
                    "description": "从第几行开始读取（1-based）。例如 offset=10 表示从第10行开始。默认为1（从文件开头）"
                },
                "limit": {
                    "type": "integer",
                    "description": "最多读取多少行。例如 limit=50 表示最多读取50行。默认为2000，最大值也是2000"
                }
            },
            "required": ["file_path"]
        }
    """.trimIndent()).asJsonObject

    /**
     * 单次最多读取的行数限制，防止超大文件撑爆内存或返回过长的响应
     */
    private val MAX_LINES = 2000

    /**
     * 执行文件读取操作
     *
     * @param input JSON 对象，包含以下字段：
     *   - file_path: String（必需）要读取的文件绝对路径
     *   - offset: Int?（可选）从第几行开始读取，1-based，默认为1
     *   - limit: Int?（可选）最多读取多少行，默认为2000
     *
     * @return ToolResult
     *   - 成功：带行号的文件内容 + 元信息（总行数、是否截断）
     *   - 失败：错误描述（文件不存在、不是文件、读取权限不足等）
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 获取必需参数 file_path
        val filePath = input.get("file_path")?.asString
            ?: return@withContext ToolResult.error("缺少必需参数 'file_path'")

        // 获取可选参数 offset（行偏移量，1-based，默认为1）
        val offset = input.get("offset")?.asInt ?: 1

        // 获取可选参数 limit（最多读取行数，默认并限制为 MAX_LINES）
        val limit = (input.get("limit")?.asInt ?: MAX_LINES).coerceAtMost(MAX_LINES)

        // 校验 offset 合法性
        if (offset < 1) {
            return@withContext ToolResult.error("参数 'offset' 必须大于等于 1，当前值: $offset")
        }

        val file = File(filePath)

        // 检查文件是否存在
        if (!file.exists()) {
            return@withContext ToolResult.error("文件不存在: $filePath")
        }

        // 确保目标是文件而非目录
        if (!file.isFile) {
            return@withContext ToolResult.error("路径指向的不是文件: $filePath（可能是目录，请使用 list_directory 工具列出目录内容）")
        }

        // 检查文件读取权限
        if (!file.canRead()) {
            return@withContext ToolResult.error("没有读取权限: $filePath")
        }

        return@withContext try {
            // 读取所有行
            val allLines = file.readLines()
            val totalLines = allLines.size

            // offset 为 1-based，转换为 0-based 索引
            val startIndex = (offset - 1).coerceAtLeast(0)

            // 检查 offset 是否超出文件实际行数
            if (startIndex >= totalLines && totalLines > 0) {
                return@withContext ToolResult.error(
                    "offset($offset) 超出文件总行数($totalLines): $filePath"
                )
            }

            // 截取需要的行范围
            val endIndex = (startIndex + limit).coerceAtMost(totalLines)
            val selectedLines = allLines.subList(startIndex, endIndex)

            // 是否因为 limit 限制而截断了内容
            val isTruncated = endIndex < totalLines

            // 构建带行号的输出
            // 行号格式：右对齐5位（与 cat -n 的格式一致，便于对齐）
            val sb = StringBuilder()
            selectedLines.forEachIndexed { index, line ->
                val lineNumber = startIndex + index + 1  // 转换回 1-based 行号
                sb.append("%5d\t%s\n".format(lineNumber, line))
            }

            // 追加元信息，帮助 Claude 理解文件的整体结构
            sb.append("\n")
            sb.append("--- 文件信息 ---\n")
            sb.append("文件路径: $filePath\n")
            sb.append("文件总行数: $totalLines\n")
            sb.append("已显示: 第 ${startIndex + 1} 行 至 第 $endIndex 行\n")
            if (isTruncated) {
                sb.append("内容已截断：还有 ${totalLines - endIndex} 行未显示。")
                sb.append("可使用 offset=${endIndex + 1} 继续读取。\n")
            }

            ToolResult.success(sb.toString())

        } catch (e: OutOfMemoryError) {
            // 文件过大导致内存溢出
            ToolResult.error("文件过大，无法完整读入内存: $filePath")
        } catch (e: SecurityException) {
            ToolResult.error("安全策略拒绝读取文件: $filePath — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("读取文件时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
