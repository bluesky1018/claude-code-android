package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 目录列表工具
 *
 * 以树状结构列出目录中的文件和子目录，类似于 `tree` 命令。
 * 文件显示其大小，目录以 `/` 结尾标记。
 *
 * 输出示例：
 * ─────────────────────────────────────────────────────────
 * /data/user/0/com.example/files/
 * ├── app/
 * │   ├── src/
 * │   │   └── main/
 * │   │       └── (目录包含 15 个文件，已截断)
 * │   └── build.gradle.kts (1.2 KB)
 * ├── README.md (3.4 KB)
 * └── settings.gradle.kts (512 B)
 * ─────────────────────────────────────────────────────────
 *
 * 显示深度限制为3层，超过3层的子目录会显示摘要信息而非详细内容，
 * 防止在大型项目中输出过多内容。
 *
 * @param defaultWorkingDir 默认列出的目录，当 path 参数未提供时使用
 */
class LSTool(private val defaultWorkingDir: String) : Tool {

    override val name: String = "list_directory"

    override val description: String = """
        以树状结构列出目录内容，文件显示大小，目录以 / 结尾标记。
        最多显示3层深度，超过3层的子目录显示摘要（包含文件数量）而非详细内容。
        结果按类型排序：目录在前，文件在后；同类型内按名称字母顺序排列。
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 list_directory 工具接受的参数：
     * - path（可选）：要列出的目录绝对路径，默认为工作目录
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "要列出内容的目录绝对路径。默认为当前工作目录。"
                }
            },
            "required": []
        }
    """.trimIndent()).asJsonObject

    /** 最大显示深度（超过此深度的子目录只显示摘要） */
    private val MAX_DEPTH = 3

    /**
     * 执行目录列表操作
     *
     * @param input JSON 对象，包含以下字段：
     *   - path: String?（可选）要列出的目录绝对路径
     *
     * @return ToolResult
     *   - 成功：树状目录结构文本
     *   - 失败：错误描述
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 获取可选参数 path，默认使用工作目录
        val dirPath = input.get("path")?.asString ?: defaultWorkingDir

        val dir = File(dirPath)

        // 检查目录是否存在
        if (!dir.exists()) {
            return@withContext ToolResult.error("目录不存在: $dirPath")
        }

        // 确保是目录
        if (!dir.isDirectory) {
            return@withContext ToolResult.error("指定路径不是目录: $dirPath\n如果要查看文件内容，请使用 read_file 工具。")
        }

        // 检查读取权限
        if (!dir.canRead()) {
            return@withContext ToolResult.error("没有权限读取目录: $dirPath")
        }

        return@withContext try {
            val sb = StringBuilder()

            // 显示根目录路径
            sb.appendLine("${dir.absolutePath}/")

            // 递归构建树状结构
            buildTree(dir, sb, prefix = "", depth = 1)

            ToolResult.success(sb.toString())

        } catch (e: SecurityException) {
            ToolResult.error("安全策略拒绝访问目录: $dirPath — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("列出目录内容时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 递归构建树状目录结构文本
     *
     * 使用 tree 命令风格的 ASCII 艺术线条（├── 和 └──）来表示层级关系。
     *
     * @param dir    当前要展开的目录
     * @param sb     输出字符串构建器
     * @param prefix 当前行的前缀（包含上级目录的树形线条）
     * @param depth  当前深度（1-based）
     */
    private fun buildTree(dir: File, sb: StringBuilder, prefix: String, depth: Int) {
        // 获取目录内容，按类型和名称排序（目录在前，文件在后）
        val children = dir.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: return

        val lastIndex = children.size - 1

        children.forEachIndexed { index, child ->
            val isLast = index == lastIndex

            // 选择树形连接符：最后一项用 └──，其余用 ├──
            val connector = if (isLast) "└── " else "├── "

            if (child.isDirectory) {
                // 显示目录名（以 / 结尾）
                sb.appendLine("$prefix$connector${child.name}/")

                if (depth < MAX_DEPTH) {
                    // 未达到最大深度，递归展开子目录
                    // 计算子目录内容的前缀：最后一项后面是空格，其余是竖线
                    val childPrefix = prefix + if (isLast) "    " else "│   "
                    buildTree(child, sb, childPrefix, depth + 1)
                } else {
                    // 已达最大深度，只显示子目录的文件数量摘要
                    val childCount = child.listFiles()?.size ?: 0
                    if (childCount > 0) {
                        val childPrefix = prefix + if (isLast) "    " else "│   "
                        sb.appendLine("${childPrefix}└── (包含 $childCount 个条目，深度已达上限，使用 list_directory 单独查看)")
                    }
                }
            } else {
                // 显示文件名和大小
                val sizeStr = formatFileSize(child.length())
                sb.appendLine("$prefix$connector${child.name} ($sizeStr)")
            }
        }
    }

    /**
     * 将文件字节数格式化为人类可读的大小字符串
     *
     * 例如：
     * - 512 → "512 B"
     * - 1536 → "1.5 KB"
     * - 2097152 → "2.0 MB"
     *
     * @param bytes 文件字节数
     * @return 格式化后的大小字符串
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
            else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}
