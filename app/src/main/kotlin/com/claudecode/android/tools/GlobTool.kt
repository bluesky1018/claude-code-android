package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * 文件路径 Glob 匹配工具
 *
 * 使用 glob 模式匹配文件系统中的文件路径，类似于 shell 中的 `find . -name "*.kt"` 命令。
 * 底层使用 Java NIO 的 PathMatcher 实现，支持标准的 glob 语法。
 *
 * 常用 glob 模式示例：
 * ─────────────────────────────────────────────────────────
 * **/*.kt        — 递归匹配所有 .kt 文件（双星号表示任意深度的路径）
 * src/**         — 匹配 src 目录下所有文件和子目录
 * *.{kt,java}    — 匹配当前目录下的 .kt 和 .java 文件
 * **/Test*.kt    — 递归匹配所有以 Test 开头的 .kt 文件
 * app/src/**     — 匹配 app/src 目录下所有内容
 * **/*.{xml,json} — 递归匹配所有 XML 和 JSON 文件
 * ─────────────────────────────────────────────────────────
 *
 * Glob 语法说明：
 * - `*`  匹配同一目录层级下的任意字符（不含路径分隔符）
 * - `**` 匹配任意深度的路径（含路径分隔符）
 * - `?`  匹配单个字符
 * - `{a,b}` 匹配 a 或 b
 * - `[abc]` 匹配括号内的任意一个字符
 *
 * @param defaultWorkingDir 默认搜索目录，当 path 参数未提供时使用
 */
class GlobTool(private val defaultWorkingDir: String) : Tool {

    override val name: String = "glob"

    override val description: String = """
        使用 glob 模式匹配文件路径，返回所有匹配的文件路径列表（按修改时间倒序排列）。

        常用模式示例：
        - **/*.kt      — 递归找所有 Kotlin 文件
        - src/**       — src 目录下所有文件
        - *.{kt,java}  — 当前目录的 kt 或 java 文件
        - **/Test*.kt  — 所有测试文件

        最多返回 1000 个匹配文件。
        结果按文件最后修改时间倒序排列（最新修改的文件在前）。
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 glob 工具接受的参数：
     * - pattern（必需）：glob 匹配模式
     * - path（可选）：搜索的根目录，默认使用工作目录
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "glob 匹配模式，例如 '**/*.kt'、'src/**'、'*.{kt,java}'。支持 * ? ** {} [] 等通配符。"
                },
                "path": {
                    "type": "string",
                    "description": "搜索的根目录（绝对路径）。默认为工作目录。glob 模式相对于此目录进行匹配。"
                }
            },
            "required": ["pattern"]
        }
    """.trimIndent()).asJsonObject

    /** 单次返回的最大文件数量，防止结果过多影响性能 */
    private val MAX_RESULTS = 1000

    /**
     * 执行 Glob 文件匹配
     *
     * @param input JSON 对象，包含以下字段：
     *   - pattern: String（必需）glob 匹配模式
     *   - path: String?（可选）搜索根目录，默认为工作目录
     *
     * @return ToolResult
     *   - 成功：每行一个匹配的文件绝对路径，附带统计信息
     *   - 失败：错误描述（目录不存在、模式非法等）
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 获取必需参数 pattern
        val pattern = input.get("pattern")?.asString
            ?: return@withContext ToolResult.error("缺少必需参数 'pattern'")

        // 获取可选参数 path，默认使用工作目录
        val searchDir = input.get("path")?.asString ?: defaultWorkingDir

        val rootDir = File(searchDir)

        // 检查搜索目录是否存在
        if (!rootDir.exists()) {
            return@withContext ToolResult.error("搜索目录不存在: $searchDir")
        }

        // 确保是目录
        if (!rootDir.isDirectory) {
            return@withContext ToolResult.error("指定路径不是目录: $searchDir")
        }

        return@withContext try {
            val rootPath = Paths.get(searchDir)

            // 构建 PathMatcher，使用 glob 语法
            // Java NIO 的 glob matcher 需要完整路径，因此模式需要加上根目录前缀
            val fullPattern = "glob:$searchDir/$pattern"
            val matcher = try {
                FileSystems.getDefault().getPathMatcher(fullPattern)
            } catch (e: java.util.regex.PatternSyntaxException) {
                return@withContext ToolResult.error("glob 模式语法错误: '$pattern' — ${e.message}")
            } catch (e: Exception) {
                return@withContext ToolResult.error("无效的 glob 模式: '$pattern' — ${e.message}")
            }

            // 收集匹配的文件路径及其修改时间
            val matchedFiles = mutableListOf<Pair<Path, Long>>()

            Files.walk(rootPath).use { stream ->
                stream.forEach { path ->
                    // 只匹配文件（不包含目录）
                    if (Files.isRegularFile(path) && matcher.matches(path)) {
                        val lastModified = try {
                            Files.readAttributes(path, BasicFileAttributes::class.java)
                                .lastModifiedTime().toMillis()
                        } catch (e: Exception) {
                            0L  // 获取修改时间失败时使用 0，不影响主流程
                        }
                        matchedFiles.add(Pair(path, lastModified))

                        // 超过最大限制则停止遍历（通过抛异常跳出 stream.forEach）
                        if (matchedFiles.size > MAX_RESULTS) {
                            return@forEach
                        }
                    }
                }
            }

            // 判断是否达到上限
            val isTruncated = matchedFiles.size > MAX_RESULTS
            val resultFiles = if (isTruncated) {
                matchedFiles.take(MAX_RESULTS)
            } else {
                matchedFiles
            }

            // 按修改时间倒序排列（最近修改的文件排在前面）
            val sortedFiles = resultFiles.sortedByDescending { it.second }

            if (sortedFiles.isEmpty()) {
                return@withContext ToolResult.success(
                    "没有找到匹配 '$pattern' 的文件。\n" +
                    "搜索目录: $searchDir"
                )
            }

            // 构建输出：每行一个文件的绝对路径
            val sb = StringBuilder()
            sortedFiles.forEach { (path, _) ->
                sb.appendLine(path.toAbsolutePath().toString())
            }

            // 追加统计信息
            sb.append("\n共找到 ${sortedFiles.size} 个文件")
            if (isTruncated) {
                sb.append("（已达最大限制 $MAX_RESULTS，实际可能更多）")
            }
            sb.append("\n搜索目录: $searchDir\n模式: $pattern")

            ToolResult.success(sb.toString())

        } catch (e: java.nio.file.InvalidPathException) {
            ToolResult.error("无效的路径: $searchDir — ${e.message}")
        } catch (e: SecurityException) {
            ToolResult.error("没有权限访问目录: $searchDir — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("执行 glob 匹配时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
