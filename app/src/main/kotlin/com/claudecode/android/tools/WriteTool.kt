package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件写入工具（完全覆盖）
 *
 * 将指定内容写入文件。如果文件已存在，会完全覆盖原有内容。
 * 如果文件的父目录不存在，会自动创建所有必要的父目录。
 *
 * 重要提示：
 * - 本工具会覆盖文件的全部内容，如果只需要修改文件的某一部分，
 *   应优先使用 EditTool（精确字符串替换），避免意外丢失其余内容。
 * - 本工具适合以下场景：
 *   1. 创建全新文件
 *   2. 需要完全重写某个文件
 *   3. 写入生成的代码或配置（无需保留旧内容）
 * - 对已有文件的局部修改，请使用 EditTool，它更安全精确。
 */
class WriteTool : Tool {

    override val name: String = "write_file"

    override val description: String = """
        将内容写入文件（完全覆盖）。
        如果文件不存在，会自动创建。如果文件已存在，会完全覆盖原有内容。
        会自动创建所有必要的父目录。

        警告：此工具会覆盖文件的全部内容。如果只需修改文件的某一部分，
        请优先使用 edit_file 工具进行精确替换，而不是用此工具覆盖全文。

        适用场景：
        1. 创建全新文件
        2. 完全重写某个文件的内容
        3. 写入程序生成的内容
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 write_file 工具接受的参数：
     * - file_path（必需）：目标文件的绝对路径
     * - content（必需）：要写入文件的完整内容
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "目标文件的绝对路径。如果父目录不存在会自动创建。例如：/data/user/0/com.example/files/output.txt"
                },
                "content": {
                    "type": "string",
                    "description": "要写入文件的完整内容。注意：此内容会覆盖文件原有的全部内容。"
                }
            },
            "required": ["file_path", "content"]
        }
    """.trimIndent()).asJsonObject

    /**
     * 执行文件写入操作
     *
     * @param input JSON 对象，包含以下字段：
     *   - file_path: String（必需）目标文件的绝对路径
     *   - content: String（必需）要写入的完整文件内容
     *
     * @return ToolResult
     *   - 成功：包含写入文件路径和字节数的确认信息
     *   - 失败：错误描述（权限不足、磁盘空间不足等）
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 获取必需参数 file_path
        val filePath = input.get("file_path")?.asString
            ?: return@withContext ToolResult.error("缺少必需参数 'file_path'")

        // 获取必需参数 content
        // 注意：content 可以是空字符串（表示写入空文件），所以不能用 isNullOrEmpty() 判断是否缺失
        if (!input.has("content")) {
            return@withContext ToolResult.error("缺少必需参数 'content'")
        }
        val content = input.get("content").asString

        val file = File(filePath)

        // 检查路径不是目录
        if (file.exists() && file.isDirectory) {
            return@withContext ToolResult.error("路径指向的是目录，无法写入: $filePath")
        }

        return@withContext try {
            // 自动创建父目录（如果不存在）
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                val created = parentDir.mkdirs()
                if (!created) {
                    return@withContext ToolResult.error("无法创建父目录: ${parentDir.absolutePath}")
                }
            }

            // 检查父目录的写入权限
            if (parentDir != null && !parentDir.canWrite()) {
                return@withContext ToolResult.error("没有权限在此目录写入文件: ${parentDir.absolutePath}")
            }

            // 写入文件内容（UTF-8 编码）
            file.writeText(content, Charsets.UTF_8)

            // 返回成功信息，包含写入的文件路径和内容大小
            val byteCount = content.toByteArray(Charsets.UTF_8).size
            val lineCount = content.lines().size
            ToolResult.success(
                "文件写入成功: $filePath\n" +
                "内容大小: $byteCount 字节，$lineCount 行"
            )

        } catch (e: SecurityException) {
            ToolResult.error("安全策略拒绝写入文件: $filePath — ${e.message}")
        } catch (e: java.io.IOException) {
            ToolResult.error("写入文件时发生 I/O 错误: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            ToolResult.error("写入文件时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
