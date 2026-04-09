package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件精确编辑工具（字符串替换）
 *
 * 在文件中精确定位 old_string 并将其替换为 new_string，而无需覆盖整个文件。
 * 这是对已有文件进行局部修改的首选工具。
 *
 * 核心约束：old_string 必须在文件中唯一出现，否则工具会拒绝执行并报错。
 *
 * 为什么必须唯一？
 * ─────────────────────────────────────────────────────────
 * 假设文件中有两处内容完全相同的代码块，如果 old_string 同时匹配到这两处，
 * 工具无法确定应该替换哪一处，贸然替换可能导致：
 * 1. 修改了不应该修改的位置（例如误改了测试代码中的 mock 而非生产代码）
 * 2. 同时替换多处，破坏程序逻辑
 *
 * 解决方法：在 old_string 中包含足够多的上下文，使其在文件中唯一。
 * 例如，不要只写变量名 `val x`，而要写包含前后代码的更长片段。
 * ─────────────────────────────────────────────────────────
 *
 * 推荐用法：
 * - 修改函数内的某几行代码
 * - 更新配置文件中的特定字段值
 * - 替换某个特定的字符串字面量
 *
 * 不适合的场景（应使用 WriteTool）：
 * - 完全重写一个文件
 * - 创建新文件
 */
class EditTool : Tool {

    override val name: String = "edit_file"

    override val description: String = """
        精确替换文件中的某段内容（不覆盖全文）。
        在文件中找到 old_string，将其替换为 new_string。

        重要约束：old_string 必须在文件中恰好出现一次。
        如果出现零次（未找到），工具报错。
        如果出现多次（不唯一），工具报错并列出所有匹配的行号，
        此时请在 old_string 中加入更多上下文内容以保证唯一性。

        对已有文件进行局部修改时，此工具优于 write_file，
        因为它只修改指定部分，其余内容保持不变，更安全。
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 edit_file 工具接受的参数：
     * - file_path（必需）：要编辑的文件绝对路径
     * - old_string（必需）：要被替换的原始内容（必须在文件中唯一出现）
     * - new_string（必需）：替换后的新内容（可以为空字符串，表示删除 old_string）
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "file_path": {
                    "type": "string",
                    "description": "要编辑的文件的绝对路径"
                },
                "old_string": {
                    "type": "string",
                    "description": "要被替换的原始内容。必须在文件中恰好出现一次，否则工具会报错。如果匹配到多处，请加入更多上下文来确保唯一性。"
                },
                "new_string": {
                    "type": "string",
                    "description": "用于替换 old_string 的新内容。可以为空字符串（表示删除 old_string）。"
                }
            },
            "required": ["file_path", "old_string", "new_string"]
        }
    """.trimIndent()).asJsonObject

    /**
     * 执行文件编辑（精确替换）操作
     *
     * @param input JSON 对象，包含以下字段：
     *   - file_path: String（必需）要编辑的文件绝对路径
     *   - old_string: String（必需）要替换的原始文本（必须唯一）
     *   - new_string: String（必需）替换后的新文本
     *
     * @return ToolResult
     *   - 成功：确认替换操作的描述信息（包含替换发生的行号）
     *   - 失败：
     *     - old_string 未找到
     *     - old_string 出现多次（附带所有匹配的行号，帮助缩小范围）
     *     - 文件不存在、权限不足等
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 获取必需参数 file_path
        val filePath = input.get("file_path")?.asString
            ?: return@withContext ToolResult.error("缺少必需参数 'file_path'")

        // 获取必需参数 old_string
        if (!input.has("old_string")) {
            return@withContext ToolResult.error("缺少必需参数 'old_string'")
        }
        val oldString = input.get("old_string").asString

        // 获取必需参数 new_string（允许为空字符串，表示删除 old_string）
        if (!input.has("new_string")) {
            return@withContext ToolResult.error("缺少必需参数 'new_string'")
        }
        val newString = input.get("new_string").asString

        // old_string 不能为空，否则无法定位替换位置
        if (oldString.isEmpty()) {
            return@withContext ToolResult.error("'old_string' 不能为空字符串。如需在文件开头/末尾插入内容，请使用 write_file 工具覆写全文。")
        }

        val file = File(filePath)

        // 检查文件是否存在
        if (!file.exists()) {
            return@withContext ToolResult.error("文件不存在: $filePath")
        }

        // 确保是文件而非目录
        if (!file.isFile) {
            return@withContext ToolResult.error("路径指向的不是文件: $filePath")
        }

        // 检查读写权限
        if (!file.canRead()) {
            return@withContext ToolResult.error("没有读取权限: $filePath")
        }
        if (!file.canWrite()) {
            return@withContext ToolResult.error("没有写入权限: $filePath")
        }

        return@withContext try {
            // 读取文件全文内容（保留原始换行符）
            val originalContent = file.readText(Charsets.UTF_8)

            // 统计 old_string 在文件中出现的次数
            val occurrenceCount = countOccurrences(originalContent, oldString)

            when {
                occurrenceCount == 0 -> {
                    // 未找到 old_string，提供有用的错误信息帮助排查
                    ToolResult.error(
                        "在文件中未找到指定内容: $filePath\n" +
                        "请检查 old_string 是否与文件内容完全匹配（包括空格、换行符、缩进）。\n" +
                        "提示：可先使用 read_file 工具查看文件内容，再确认要替换的确切文本。"
                    )
                }

                occurrenceCount > 1 -> {
                    // 出现多次，定位所有匹配的行号以帮助 Claude 选择更精确的 old_string
                    val matchLineNumbers = findMatchLineNumbers(originalContent, oldString)
                    ToolResult.error(
                        "'old_string' 在文件中出现了 $occurrenceCount 次（不唯一），无法安全替换: $filePath\n" +
                        "匹配位置（行号）：${matchLineNumbers.joinToString(", ")}\n" +
                        "请在 old_string 中加入更多上下文（如前后相邻的代码行）以确保唯一性，然后重试。"
                    )
                }

                else -> {
                    // 恰好出现一次，安全执行替换
                    val newContent = originalContent.replace(oldString, newString)

                    // 写回文件
                    file.writeText(newContent, Charsets.UTF_8)

                    // 找出替换发生的行号（基于原始内容）
                    val replacedAtLine = findFirstMatchLine(originalContent, oldString)

                    ToolResult.success(
                        "文件编辑成功: $filePath\n" +
                        "替换位置：约第 $replacedAtLine 行\n" +
                        "已将指定内容替换为新内容。"
                    )
                }
            }

        } catch (e: SecurityException) {
            ToolResult.error("安全策略拒绝操作文件: $filePath — ${e.message}")
        } catch (e: java.io.IOException) {
            ToolResult.error("操作文件时发生 I/O 错误: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            ToolResult.error("编辑文件时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 统计子字符串在文本中出现的次数（不重叠）
     *
     * @param text    被搜索的完整文本
     * @param pattern 要搜索的子字符串
     * @return 出现次数
     */
    private fun countOccurrences(text: String, pattern: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(pattern, index)
            if (index == -1) break
            count++
            index += pattern.length  // 向前移动，避免重叠匹配
        }
        return count
    }

    /**
     * 找出子字符串所有匹配位置对应的起始行号（1-based）
     *
     * 用于在 old_string 不唯一时，告知用户所有匹配位置，
     * 帮助用户选择更精确的替换文本。
     *
     * @param text    被搜索的完整文本
     * @param pattern 要搜索的子字符串
     * @return 所有匹配起始位置对应的行号列表（1-based）
     */
    private fun findMatchLineNumbers(text: String, pattern: String): List<Int> {
        val lineNumbers = mutableListOf<Int>()
        var index = 0
        while (true) {
            index = text.indexOf(pattern, index)
            if (index == -1) break
            // 计算该位置之前有多少换行符，换行符数量 + 1 就是行号
            val lineNumber = text.substring(0, index).count { it == '\n' } + 1
            lineNumbers.add(lineNumber)
            index += pattern.length
        }
        return lineNumbers
    }

    /**
     * 找出子字符串第一次匹配的行号（1-based）
     *
     * @param text    被搜索的完整文本
     * @param pattern 要搜索的子字符串
     * @return 第一次匹配的起始行号（1-based），未找到返回 -1
     */
    private fun findFirstMatchLine(text: String, pattern: String): Int {
        val index = text.indexOf(pattern)
        if (index == -1) return -1
        return text.substring(0, index).count { it == '\n' } + 1
    }
}
