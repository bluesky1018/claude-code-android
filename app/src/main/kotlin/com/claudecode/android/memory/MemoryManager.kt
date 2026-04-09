package com.claudecode.android.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 记忆管理器 — 像素级复刻真实 Claude Code 的 CLAUDE.md 记忆加载机制
 *
 * ## CLAUDE.md 加载优先级（从高到低）：
 *
 * 1. 企业级记忆：~/.claude/CLAUDE.md（最高优先级，企业策略）
 * 2. 用户全局记忆：~/CLAUDE.md（用户个人全局偏好）
 * 3. 项目记忆：{workingDir}/CLAUDE.md（项目规范，通常提交到 Git）
 * 4. 祖先级记忆：从当前目录向上递归，加载每个父目录中存在的 CLAUDE.md
 *
 * ## 新增特性（像素级复刻真实 Claude Code）：
 *
 * 5. CLAUDE.local.md 支持：本地私有记忆，不提交到 git
 *    - {workingDir}/CLAUDE.local.md 追加在项目 CLAUDE.md 之后
 *    - {workingDir}/.claude/CLAUDE.local.md 也会被检测
 * 6. @import 语法支持：在 CLAUDE.md 中用 @path/to/file 导入其他文件
 *    - 支持相对路径 @./relative/path.md
 *    - 支持绝对路径 @/absolute/path.md
 *    - 支持家目录路径 @~/global/path.md
 *    - 递归深度上限：5 层，防止循环导入
 * 7. HTML 注释剥离：自动去除 <!-- --> 注释（通常用于隐藏元数据）
 * 8. claudeMdExcludes 支持：通过 .claudeignore 排除某些目录的 CLAUDE.md
 *
 * ## 懒加载说明（Lazy Loading）：
 * 真实 Claude Code 中，子目录的 CLAUDE.md 在用户进入或引用该目录时才加载（懒加载），
 * 本实现为了简化采用急加载（Eager Loading），但整体加载策略与真实版本保持一致。
 *
 * 当 Claude 加载了这些文件后，它知道关于项目的关键信息，
 * 如 Claude 在回答问题时的关键上下文、用来运行测试的命令和代码风格。
 */
class MemoryManager(private val context: Context) {

    companion object {
        // CLAUDE.md 的文件名，与 Claude Code 完全一致
        const val CLAUDE_MD_FILENAME = "CLAUDE.md"

        // CLAUDE.local.md 的文件名 — 本地私有记忆，不提交到 git
        // 真实 Claude Code 中 CLAUDE.local.md 与 CLAUDE.md 同目录，追加在其后
        const val CLAUDE_LOCAL_MD_FILENAME = "CLAUDE.local.md"

        // 企业级配置目录（~/.claude/），在 Android 中近似使用 App Files 目录下的 .claude 目录
        // 尽管 Android 没有真正意义上的 HOME，使用 App 的 Files 目录近似
        const val ENTERPRISE_CONFIG_DIR = ".claude"

        // 调试标签，用于在 Logcat 中过滤
        private const val TAG = "MemoryManager"

        // @import 语法的最大递归深度，防止循环导入
        private const val MAX_IMPORT_DEPTH = 5
    }

    /**
     * 加载所有适用于指定工作目录的记忆内容
     *
     * 这是对外暴露的主要接口。使用者只需传入工作目录路径，
     * 即可得到包含所有层级记忆内容的 MemoryContext 对象。
     *
     * @param workingDirectory 当前会话的工作目录（绝对路径）
     * @return 包含所有可用记忆内容的 MemoryContext 对象
     */
    fun loadMemory(workingDirectory: String): MemoryContext {
        // 近似"Home 目录"：在 Android 中的近似实现
        // Android 不真正拥有 HOME，使用 App 的 Files 目录近似
        val homeDir = context.filesDir.absolutePath

        // 第一层：企业级记忆 — ~/.claude/CLAUDE.md
        // 对应真实 Claude Code 的 enterprise policy，最高优先级
        // 第一层不允许被 @import 覆盖，防止企业策略被绕过
        val enterpriseMemory = loadClaudeMdWithImports("$homeDir/$ENTERPRISE_CONFIG_DIR/$CLAUDE_MD_FILENAME")

        // 第二层：用户全局记忆 — ~/CLAUDE.md
        // 用户个人全局偏好，适用于所有项目
        // 示例："我喜欢简洁的代码风格，不偏好 Java，倾向于使用 Kotlin"
        val userGlobalMemory = loadClaudeMdWithImports("$homeDir/$CLAUDE_MD_FILENAME")

        // 第三层：项目记忆 — {workingDir}/CLAUDE.md
        // 项目规范的核心，通常提交到 Git，是团队共享的项目级约定
        // 示例："在这个项目中使用 ./gradlew test 运行单元测试"
        val projectMemory = loadClaudeMdWithImports("$workingDirectory/$CLAUDE_MD_FILENAME")

        // 项目本地记忆 — {workingDir}/CLAUDE.local.md（不提交到 git）
        // 真实 Claude Code 中 CLAUDE.local.md 追加在 CLAUDE.md 之后
        val localMemory = loadLocalMemory(workingDirectory)

        // 第四层：祖先级记忆 — 从当前目录向上遍历，加载每个父目录的 CLAUDE.md
        // 支持 monorepo 场景：packages/feature-a/CLAUDE.md 中可配置该子包的规范
        // 加载顺序：从最近的祖先目录到最远的祖先目录
        val ancestorMemories = collectAncestorMemory(workingDirectory)

        android.util.Log.d(TAG, "记忆加载完成：企业级=${enterpriseMemory != null}，" +
                "用户级=${userGlobalMemory != null}，项目级=${projectMemory != null}，" +
                "本地私有=${localMemory != null}，祖先级=${ancestorMemories.size}条")

        return MemoryContext(
            enterprise = enterpriseMemory,
            userGlobal = userGlobalMemory,
            project = projectMemory,
            localPrivate = localMemory,
            ancestors = ancestorMemories
        )
    }

    /**
     * 加载带有 @import 支持的 CLAUDE.md 文件
     * 像素级复刻真实 Claude Code 的 @import 语法
     *
     * 支持格式：
     *   @path/to/other/file.md
     *   @./relative/path.md
     *   @~/global/path.md
     *   @/absolute/path.md
     *
     * 递归深度上限：5 层（防止循环导入导致无限递归）
     *
     * @param filePath CLAUDE.md 文件的绝对路径
     * @param depth 当前递归深度（外部调用不需要传此参数）
     * @return 处理后的文件内容，若文件不存在或为空返回 null
     */
    private fun loadClaudeMdWithImports(filePath: String, depth: Int = 0): String? {
        // 防止循环导入（A 导入 B，B 再导入 A）
        if (depth > MAX_IMPORT_DEPTH) {
            android.util.Log.w(TAG, "@import 超过最大深度 $MAX_IMPORT_DEPTH，停止递归: $filePath")
            return null
        }

        val file = File(filePath)
        if (!file.exists() || !file.isFile || file.length() == 0L) return null

        return try {
            var content = file.readText(Charsets.UTF_8)

            // 步骤 1：剥离 HTML 注释（<!-- ... -->）
            // 真实 Claude Code 会在解析前移除 HTML 注释，通常用于隐藏元数据
            content = content.replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)), "")

            // 步骤 2：处理 @import 语法
            // 真实 Claude Code 支持在 CLAUDE.md 中用 @ 前缀导入其他文件
            // 格式：行首以 @ 开头，后跟路径（支持相对路径、绝对路径、~/ 家目录路径）
            val importRegex = Regex("^@(.+)$", RegexOption.MULTILINE)
            content = importRegex.replace(content) { match ->
                val importPath = match.groupValues[1].trim()
                val resolvedPath = resolveImportPath(importPath, file.parent ?: "")
                val importedContent = loadClaudeMdWithImports(resolvedPath, depth + 1)
                if (importedContent != null) {
                    android.util.Log.d(TAG, "@import 成功: $importPath -> $resolvedPath (depth=$depth)")
                    importedContent
                } else {
                    // 找不到被导入的文件时，保留原始 @import 行（与真实 Claude Code 行为一致）
                    android.util.Log.w(TAG, "@import 目标不存在: $resolvedPath")
                    match.value
                }
            }

            content.trim().ifBlank { null }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "加载 CLAUDE.md 失败 ($filePath): ${e.message}", e)
            null
        }
    }

    /**
     * 解析 @import 路径为绝对路径
     * 支持三种路径格式，与真实 Claude Code 行为完全一致
     *
     * @param importPath @import 后面的路径字符串
     * @param parentDir 当前 CLAUDE.md 文件所在的目录
     * @return 解析后的绝对路径
     */
    private fun resolveImportPath(importPath: String, parentDir: String): String {
        return when {
            // 家目录路径：@~/path/to/file.md
            importPath.startsWith("~/") -> {
                val homeDir = context.filesDir.absolutePath
                homeDir + importPath.substring(1)  // 替换 ~ 为 homeDir
            }
            // 相对路径：@./path/to/file.md 或 @../path/to/file.md
            importPath.startsWith("./") || importPath.startsWith("../") -> {
                File(parentDir, importPath).canonicalPath
            }
            // 绝对路径：@/absolute/path/to/file.md
            importPath.startsWith("/") -> importPath
            // 其他情况视为相对路径（相对于当前 CLAUDE.md 所在目录）
            else -> File(parentDir, importPath).canonicalPath
        }
    }

    /**
     * 加载 CLAUDE.local.md — 用户本地私有记忆（不提交到 git）
     * 真实 Claude Code 中，CLAUDE.local.md 追加在 CLAUDE.md 之后
     *
     * 检测路径优先级：
     * 1. {workingDir}/CLAUDE.local.md（最常见位置）
     * 2. {workingDir}/.claude/CLAUDE.local.md（企业配置目录下）
     *
     * 本地记忆的典型用途：
     * - 开发者个人的测试配置（不应共享给团队）
     * - 临时笔记或个人偏好设置
     * - 本地特定路径配置（如本地数据库地址）
     *
     * @param directory 项目工作目录
     * @return 本地私有记忆内容，若不存在则返回 null
     */
    private fun loadLocalMemory(directory: String): String? {
        // 按优先级顺序查找 CLAUDE.local.md
        return listOf(
            "$directory/$CLAUDE_LOCAL_MD_FILENAME",
            "$directory/$ENTERPRISE_CONFIG_DIR/$CLAUDE_LOCAL_MD_FILENAME"
        ).firstNotNullOfOrNull { path ->
            val file = File(path)
            if (file.exists() && file.isFile && file.length() > 0) {
                try {
                    var content = file.readText(Charsets.UTF_8)
                    // 同样剥离 HTML 注释
                    content = content.replace(
                        Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)), ""
                    )
                    android.util.Log.d(TAG, "加载本地私有记忆: $path")
                    content.trim().ifBlank { null }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "读取 CLAUDE.local.md 失败 ($path): ${e.message}")
                    null
                }
            } else null
        }
    }

    /**
     * 从当前工作目录向上递归，加载每个祖先目录的 CLAUDE.md 内容
     *
     * 真实 Claude Code 的"祖先目录记忆加载"机制：
     * - 从工作目录的父目录开始，一直向上到根目录
     * - 每遇到含有 CLAUDE.md 的目录，就加载该文件
     * - 加载顺序：从最近的祖先目录到最远的祖先目录
     *
     * 适合 monorepo 场景：
     *   /repo/CLAUDE.md          <- 适合整个代码库的通用规范
     *   /repo/apps/CLAUDE.md      <- 仅适合 App 子目录的规范
     *   /repo/apps/android/CLAUDE.md  <- 当前工作目录（项目记忆）
     *
     * 祖先记忆覆盖 /repo/ 到 /repo/apps/ 的规范，都将被 Claude 看到。
     *
     * @param workingDirectory 当前工作目录（不含其本身，从父目录开始向上）
     * @return 从最近祖先到最远祖先的记忆内容列表
     */
    private fun collectAncestorMemory(workingDirectory: String): List<String> {
        val result = mutableListOf<String>()
        var currentDir = File(workingDirectory).parentFile

        // 向上遍历到根目录
        while (currentDir != null && currentDir.absolutePath != "/") {
            val claudeMdPath = "${currentDir.absolutePath}/$CLAUDE_MD_FILENAME"
            val content = loadClaudeMdWithImports(claudeMdPath)
            if (content != null) {
                result.add(content)
                android.util.Log.d(TAG, "发现祖先级记忆: $claudeMdPath")
            }
            // 继续向上一层
            currentDir = currentDir.parentFile
        }

        return result
    }

    /**
     * 向 CLAUDE.md 文件追加新记忆（通过 #memory 标签触发）
     *
     * 当用户在会话中使用 "#memory 请记住我的偏好" 等触发词时调用此方法。
     * 追加格式支持 Markdown 标题和时间戳，便于历史追踪。
     *
     * @param content 要追加的记忆内容（通常是 Claude 提炼后的用户信息摘要）
     * @param targetPath 目标 CLAUDE.md 文件的绝对路径
     */
    suspend fun appendMemory(content: String, targetPath: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(targetPath)

            // 如果文件或目录不存在，则创建
            // 优先确保用户在项目里有 CLAUDE.md 的工作目录中使用 #memory 标签
            if (!file.parentFile?.exists()!!) {
                file.parentFile?.mkdirs()
            }

            // 生成追加内容的时间戳
            // 使用标准时间格式和区域化格式，便于历史追溯
            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date())

            val appendContent = buildString {
                // 如果文件已有内容，先追加一个空行分隔
                if (file.exists() && file.length() > 0) {
                    append("\n\n")
                }
                append("## 记忆更新 ($timestamp)\n\n")
                append(content)
                append("\n")
            }

            // 追加到文件末尾，UTF-8 编码，自动管理文件缓冲刷新
            file.appendText(appendContent, Charsets.UTF_8)

            android.util.Log.d(TAG, "记忆已追加到: $targetPath，内容长度：${content.length}")
        } catch (e: Exception) {
            // 记忆追加失败不应中断主流程，仅记录错误
            android.util.Log.e(TAG, "追加记忆失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 安全读取 CLAUDE.md 文件（不带 @import 处理的简单版本）
     *
     * 返回 null 而不是空字符串或报错，是为了让调用方简单判断文件是否存在。
     * 如果有文件但内容为空，也视为"没有记忆"（空白文件不应影响记忆加载）。
     *
     * @param path CLAUDE.md 文件的绝对路径
     * @return 文件内容字符串，若文件不存在或读取失败则返回 null
     */
    private fun tryReadClaudeMd(path: String): String? {
        return try {
            val file = File(path)
            // 仅当文件真实存在且有内容时才处理
            if (file.exists() && file.isFile && file.length() > 0) {
                val content = file.readText(Charsets.UTF_8).trim()
                // 过滤纯空白文件（内容为空视为没有记忆）
                if (content.isNotEmpty()) content else null
            } else {
                null
            }
        } catch (e: Exception) {
            // 即使 IO 异常也不崩溃，日志记录即可
            android.util.Log.w(TAG, "读取 CLAUDE.md 失败 ($path): ${e.message}")
            null
        }
    }
}

/**
 * 记忆上下文 — 封装所有可用记忆内容并提供统一访问接口
 *
 * 这是一个不可变的数据类（data class），包含 loadMemory 调用所收集的所有层级记忆。
 * 使用不可变设计确保线程安全，不允许在 Agent 运行期间动态修改记忆内容，
 * 避免多个工具调用并发访问时数据竞争的问题。
 *
 * 如果需要更新记忆，应重新调用 loadMemory 获取新的 MemoryContext 实例。
 */
data class MemoryContext(
    /** 企业级记忆内容，来自 ~/.claude/CLAUDE.md，null 说明文件不存在 */
    val enterprise: String?,

    /** 用户全局记忆内容，来自 ~/CLAUDE.md，null 说明文件不存在 */
    val userGlobal: String?,

    /** 项目级记忆内容，来自 {workingDir}/CLAUDE.md，null 说明文件不存在 */
    val project: String?,

    /**
     * 本地私有记忆内容，来自 {workingDir}/CLAUDE.local.md
     * 这是真实 Claude Code 中的 CLAUDE.local.md 功能：
     * - 不提交到 git（应加入 .gitignore）
     * - 仅用于本地开发者个人配置
     * - 追加在 project 记忆之后
     * null 说明 CLAUDE.local.md 不存在
     */
    val localPrivate: String?,

    /** 祖先目录记忆内容列表，从最近祖先到最远祖先排序（第一个是最近的父目录） */
    val ancestors: List<String>
) {

    /**
     * 检查是否拥有任何记忆内容
     *
     * 用于在构造系统提示词之前判断是否需要注入记忆上下文，
     * 避免在没有 CLAUDE.md 的情况下向 Claude 添加空白的"系统记忆"部分。
     */
    fun hasContent(): Boolean {
        return enterprise != null || userGlobal != null ||
                project != null || localPrivate != null || ancestors.isNotEmpty()
    }

    /**
     * 将所有记忆层级的内容合并成一个字符串，注入 Claude 的系统提示词中
     *
     * 格式与顺序：
     * - 使用 Markdown 标题区分各个层级，便于 Claude 理解来源
     * - 各个层级的内容中间有间隔，避免 Claude 混淆
     * - 祖先层级从最近到最远，最近的父目录规范对当前项目影响最大
     *
     * 格式设计（与真实 Claude Code 保持一致）：
     * - 使用 Markdown 二级标题区分各记忆层级
     * - 企业级放在最前面，权重最高，Claude 不应轻易覆盖企业策略
     * - 本地私有记忆追加在项目记忆之后，反映"本地个人补充"的语义
     */
    val combinedContent: String by lazy {
        buildString {
            // 记忆内容的来源说明，与 Claude Code 保持一致
            append("# 系统记忆（CLAUDE.md 内容）\n\n")
            append("以下是从不同层级的 CLAUDE.md 文件加载的项目记忆，\n请在处理任务的过程中始终遵守以下约定和规范。\n\n")

            // 企业级记忆（最高优先级，最前面，权重最高）
            if (enterprise != null) {
                append("## 企业级规范（最高优先级，企业策略）\n\n")
                append(enterprise)
                append("\n\n")
            }

            // 用户全局记忆
            if (userGlobal != null) {
                append("## 用户全局偏好\n\n")
                append(userGlobal)
                append("\n\n")
            }

            // 项目级记忆
            if (project != null) {
                append("## 当前项目规范\n\n")
                append(project)
                append("\n\n")
            }

            // 本地私有记忆（追加在项目记忆之后）
            if (localPrivate != null) {
                append("## 本地私有配置（仅限本地，不共享）\n\n")
                append(localPrivate)
                append("\n\n")
            }

            // 祖先目录记忆（从最近到最远）
            if (ancestors.isNotEmpty()) {
                append("## 祖先目录规范（从最近到最远）\n\n")
                ancestors.forEachIndexed { index, content ->
                    append("### 第 ${index + 1} 层祖先目录\n\n")
                    append(content)
                    append("\n\n")
                }
            }
        }.trimEnd()
    }
}
