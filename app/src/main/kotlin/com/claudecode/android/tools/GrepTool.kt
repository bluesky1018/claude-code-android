package com.claudecode.android.tools

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 文件内容正则搜索工具（类似 grep -rn）
 *
 * 在文件中搜索符合正则表达式的行，支持递归搜索目录，可按文件名模式过滤。
 * 对于每个匹配，还会显示前后各1行的上下文，方便理解匹配的含义。
 *
 * 输出格式示例：
 * ─────────────────────────────────────────────────────────
 * /path/to/file.kt:42:    fun someFunction() {
 * /path/to/file.kt:43:        val result = targetExpression    <-- 匹配行
 * /path/to/file.kt:44:        return result
 * ─────────────────────────────────────────────────────────
 *
 * 自动跳过的目录（这些目录通常不包含有意义的源代码）：
 * - .git         版本控制元数据
 * - node_modules NPM 依赖
 * - build        构建输出
 * - .gradle      Gradle 缓存
 * - .idea        IDE 配置
 * - out          编译输出
 *
 * @param defaultWorkingDir 默认搜索目录，当 path 参数未提供时使用
 */
class GrepTool(private val defaultWorkingDir: String) : Tool {

    override val name: String = "grep"

    override val description: String = """
        在文件中搜索正则表达式，返回匹配行及上下文（前后各1行）。
        类似 grep -rn 命令。

        输出格式：文件路径:行号:行内容
        每个匹配会显示匹配行及其前后各1行的上下文。
        最多返回 50 个匹配。

        自动跳过 .git、node_modules、build、.gradle 等目录。

        支持完整的 Java 正则表达式语法，例如：
        - fun\\s+\\w+  — 搜索函数定义
        - TODO|FIXME  — 搜索 TODO 或 FIXME 注释
        - import.*okhttp  — 搜索 okhttp 相关导入
    """.trimIndent()

    /**
     * 工具的 JSON Schema 定义
     *
     * 描述 grep 工具接受的参数：
     * - pattern（必需）：正则表达式模式
     * - path（可选）：搜索目录，默认工作目录
     * - include（可选）：文件名过滤 glob，如 "*.kt" 只搜索 Kotlin 文件
     */
    override val inputSchema: JsonObject = com.google.gson.JsonParser.parseString("""
        {
            "type": "object",
            "properties": {
                "pattern": {
                    "type": "string",
                    "description": "要搜索的正则表达式（Java 正则语法）。例如：'fun\\s+\\w+'、'TODO|FIXME'、'import.*retrofit'"
                },
                "path": {
                    "type": "string",
                    "description": "搜索的目录（绝对路径）。默认为工作目录。"
                },
                "include": {
                    "type": "string",
                    "description": "文件名过滤 glob 模式，只搜索符合此模式的文件。例如：'*.kt' 只搜索 Kotlin 文件，'*.{kt,java}' 搜索 Kotlin 和 Java 文件。不提供则搜索所有文本文件。"
                }
            },
            "required": ["pattern"]
        }
    """.trimIndent()).asJsonObject

    /** 单次搜索最多返回的匹配数量 */
    private val MAX_MATCHES = 50

    /** 每个匹配显示的上下文行数（前后各几行） */
    private val CONTEXT_LINES = 1

    /** 需要跳过的目录名（这些目录不包含有意义的源代码） */
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", "out", "__pycache__", ".DS_Store")

    /**
     * 执行正则搜索
     *
     * @param input JSON 对象，包含以下字段：
     *   - pattern: String（必需）正则表达式
     *   - path: String?（可选）搜索目录
     *   - include: String?（可选）文件名过滤 glob
     *
     * @return ToolResult
     *   - 成功：匹配结果（格式 "文件路径:行号:内容"）及上下文，附带统计信息
     *   - 失败：错误描述（目录不存在、正则语法错误等）
     */
    override suspend fun execute(input: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        // 获取必需参数 pattern
        val patternStr = input.get("pattern")?.asString
            ?: return@withContext ToolResult.error("缺少必需参数 'pattern'")

        // 获取可选参数 path，默认使用工作目录
        val searchDir = input.get("path")?.asString ?: defaultWorkingDir

        // 获取可选参数 include（文件名过滤 glob）
        val includeGlob = input.get("include")?.asString

        val rootDir = File(searchDir)

        // 检查搜索目录
        if (!rootDir.exists()) {
            return@withContext ToolResult.error("搜索目录不存在: $searchDir")
        }
        if (!rootDir.isDirectory) {
            return@withContext ToolResult.error("指定路径不是目录: $searchDir")
        }

        // 编译正则表达式（MULTILINE 模式，CASE_INSENSITIVE 可选）
        val regex = try {
            Pattern.compile(patternStr)
        } catch (e: PatternSyntaxException) {
            return@withContext ToolResult.error(
                "正则表达式语法错误: '$patternStr'\n错误详情: ${e.message}"
            )
        }

        // 构建文件名 PathMatcher（如果提供了 include 参数）
        val fileMatcher = if (includeGlob != null) {
            try {
                FileSystems.getDefault().getPathMatcher("glob:$includeGlob")
            } catch (e: Exception) {
                return@withContext ToolResult.error("文件名过滤 glob 模式无效: '$includeGlob' — ${e.message}")
            }
        } else null

        return@withContext try {
            val results = mutableListOf<String>()
            var totalMatchCount = 0
            var searchedFileCount = 0

            // 递归遍历目录
            searchDirectory(rootDir, regex, fileMatcher, results, { totalMatchCount++ }, { searchedFileCount++ })

            totalMatchCount = results.size

            if (results.isEmpty()) {
                val includeInfo = if (includeGlob != null) "，文件过滤: $includeGlob" else ""
                return@withContext ToolResult.success(
                    "没有找到匹配 '$patternStr' 的内容。\n" +
                    "搜索目录: $searchDir$includeInfo\n" +
                    "已搜索文件数: $searchedFileCount"
                )
            }

            // 构建输出
            val sb = StringBuilder()
            val displayResults = results.take(MAX_MATCHES)
            displayResults.forEach { sb.appendLine(it) }

            sb.append("\n--- 搜索结果 ---\n")
            sb.append("共找到 ${minOf(totalMatchCount, MAX_MATCHES)} 个匹配")
            if (totalMatchCount > MAX_MATCHES) {
                sb.append("（已达最大限制 $MAX_MATCHES，更多结果未显示）")
            }
            sb.append("\n搜索目录: $searchDir")
            sb.append("\n搜索模式: $patternStr")
            if (includeGlob != null) sb.append("\n文件过滤: $includeGlob")

            ToolResult.success(sb.toString())

        } catch (e: SecurityException) {
            ToolResult.error("没有权限访问目录: $searchDir — ${e.message}")
        } catch (e: Exception) {
            ToolResult.error("执行搜索时发生错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 递归搜索目录中的文件
     *
     * @param dir            当前搜索目录
     * @param regex          编译好的正则表达式
     * @param fileMatcher    文件名过滤器（可为 null 表示不过滤）
     * @param results        收集匹配结果的列表（格式化字符串）
     * @param onMatch        每找到一个匹配时的回调（用于计数）
     * @param onFileSearched 每搜索完一个文件时的回调（用于计数）
     */
    private fun searchDirectory(
        dir: File,
        regex: Pattern,
        fileMatcher: java.nio.file.PathMatcher?,
        results: MutableList<String>,
        onMatch: () -> Unit,
        onFileSearched: () -> Unit
    ) {
        // 达到最大匹配数量时停止
        if (results.size >= MAX_MATCHES) return

        val children = dir.listFiles() ?: return

        for (child in children.sortedBy { it.name }) {
            if (results.size >= MAX_MATCHES) break

            if (child.isDirectory) {
                // 跳过不需要搜索的目录
                if (child.name in SKIP_DIRS) continue
                searchDirectory(child, regex, fileMatcher, results, onMatch, onFileSearched)
            } else if (child.isFile) {
                // 检查文件名是否符合 include 过滤条件
                if (fileMatcher != null) {
                    val fileName = Paths.get(child.name)
                    if (!fileMatcher.matches(fileName)) continue
                }

                // 跳过二进制文件
                if (isBinaryFile(child)) continue

                searchFile(child, regex, results)
                onFileSearched()
            }
        }
    }

    /**
     * 在单个文件中搜索正则匹配，并收集带上下文的结果
     *
     * @param file    要搜索的文件
     * @param regex   编译好的正则表达式
     * @param results 收集匹配结果的列表
     */
    private fun searchFile(file: File, regex: Pattern, results: MutableList<String>) {
        if (results.size >= MAX_MATCHES) return

        try {
            val lines = file.readLines(Charsets.UTF_8)
            val filePath = file.absolutePath

            for (lineIndex in lines.indices) {
                if (results.size >= MAX_MATCHES) break

                val line = lines[lineIndex]
                val matcher = regex.matcher(line)

                if (matcher.find()) {
                    // 找到匹配，收集上下文
                    val contextStart = maxOf(0, lineIndex - CONTEXT_LINES)
                    val contextEnd = minOf(lines.size - 1, lineIndex + CONTEXT_LINES)

                    val sb = StringBuilder()
                    for (ctxIndex in contextStart..contextEnd) {
                        val lineNum = ctxIndex + 1  // 转为 1-based 行号
                        val ctxLine = lines[ctxIndex]
                        // 匹配行用 > 标记，上下文行用普通格式
                        if (ctxIndex == lineIndex) {
                            sb.appendLine("$filePath:$lineNum:$ctxLine")
                        } else {
                            sb.appendLine("$filePath:$lineNum-$ctxLine")
                        }
                    }
                    // 在上下文块之间加空行，便于阅读
                    results.add(sb.toString())
                }
            }
        } catch (e: Exception) {
            // 忽略单个文件的读取错误，继续处理其他文件
        }
    }

    /**
     * 简单判断文件是否为二进制文件
     *
     * 通过读取文件前 8KB 的字节，检测是否含有 NULL 字节来判断。
     * 这不是完美的方法，但对大多数情况有效。
     *
     * @param file 要检测的文件
     * @return true 表示可能是二进制文件，false 表示可能是文本文件
     */
    private fun isBinaryFile(file: File): Boolean {
        return try {
            val bytes = file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                val read = stream.read(buffer)
                buffer.take(read)
            }
            // 检测 NULL 字节（二进制文件的典型特征）
            bytes.any { it == 0.toByte() }
        } catch (e: Exception) {
            false  // 读取失败时，假设是文本文件，让后续处理决定
        }
    }
}
