package com.claudecode.android.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 记忆管理器 — 实现 Claude Code 的 CLAUDE.md 四层记忆系统
 *
 * ## CLAUDE.md 记忆优先级（从高到低）
 *
 * 1. 企业策略层：~/.claude/CLAUDE.md（最高优先级，企业统一配置）
 * 2. 用户全局层：~/CLAUDE.md（用户个人偏好）
 * 3. 项目层：{workingDir}/CLAUDE.md（项目特定约定）
 * 4. 子目录层：当前文件所在目录及其所有父目录的 CLAUDE.md
 *
 * 所有层级的内容都会被注入到系统提示词中，
 * 让 Claude 了解项目的编码规范、常用命令、注意事项等。
 *
 * ## 使用示例
 * CLAUDE.md 可以包含：
 * - "这个项目使用 Kotlin，不要生成 Java 代码"
 * - "运行测试的命令是 ./gradlew test"
 * - "代码风格遵循 Google Kotlin Style Guide"
 *
 * ## 为什么要分层？
 *
 * 分层设计允许不同粒度的配置共存而不相互覆盖：
 * - 企业层定义公司强制规范（如安全策略、代码审查要求）
 * - 用户层保存个人偏好（如偏好某种注释风格）
 * - 项目层记录项目约定（如该项目的特殊构建命令）
 * - 祖先目录层支持 monorepo 场景下不同子模块的特殊约定
 *
 * 这与 Claude Code 桌面版的行为完全对标，确保用户体验一致性。
 */
class MemoryManager(private val context: Context) {

    companion object {
        // CLAUDE.md 的文件名，与 Claude Code 桌面版保持一致
        const val CLAUDE_MD_FILENAME = "CLAUDE.md"

        // 企业配置目录（~/.claude/），在 Android 上映射到 App 私有目录下的 .claude 子目录
        // 之所以使用 App 私有目录，是因为 Android 沙箱机制不允许直接访问 HOME 目录
        const val ENTERPRISE_CONFIG_DIR = ".claude"

        // 日志标签，方便在 Logcat 中过滤
        private const val TAG = "MemoryManager"
    }

    /**
     * 加载指定工作目录的所有层级记忆
     *
     * 这是主要的对外接口。调用者传入当前工作目录，
     * 该方法依次查找四个层级的 CLAUDE.md 文件并合并返回。
     *
     * @param workingDirectory 当前项目的工作目录（绝对路径）
     * @return 包含所有层级记忆内容的 MemoryContext 对象
     */
    fun loadMemory(workingDirectory: String): MemoryContext {
        // 确定"用户 Home 目录"在 Android 上的等价路径
        // Android 没有传统意义上的 HOME，使用 App 的 Files 目录作为根
        val homeDir = context.filesDir.absolutePath

        // 第一层：企业策略层 — ~/.claude/CLAUDE.md
        // 这里对标 Claude Code 的 enterprise policy，优先级最高
        // 企业可以在这里强制规定所有项目都必须遵守的规范
        val enterpriseMemory = tryReadClaudeMd("$homeDir/$ENTERPRISE_CONFIG_DIR/$CLAUDE_MD_FILENAME")

        // 第二层：用户全局层 — ~/CLAUDE.md
        // 用户个人的全局偏好，适用于该用户所有项目
        // 例如："我喜欢详细的注释，请总是为复杂逻辑添加解释"
        val userGlobalMemory = tryReadClaudeMd("$homeDir/$CLAUDE_MD_FILENAME")

        // 第三层：项目层 — {workingDir}/CLAUDE.md
        // 当前项目的特定约定，团队成员共享（通常提交到 Git）
        // 例如："这个项目使用 Gradle 7.x，运行测试用 ./gradlew test"
        val projectMemory = tryReadClaudeMd("$workingDirectory/$CLAUDE_MD_FILENAME")

        // 第四层：祖先目录层 — 从工作目录向上遍历，收集所有父目录的 CLAUDE.md
        // 支持 monorepo 场景：packages/feature-a/CLAUDE.md 可以覆盖根目录的配置
        // 列表顺序：从最近的父目录到最远的祖先目录
        val ancestorMemories = collectAncestorMemory(workingDirectory)

        android.util.Log.d(TAG, "记忆加载完成：企业层=${enterpriseMemory != null}，" +
                "用户层=${userGlobalMemory != null}，项目层=${projectMemory != null}，" +
                "祖先层=${ancestorMemories.size}个")

        return MemoryContext(
            enterprise = enterpriseMemory,
            userGlobal = userGlobalMemory,
            project = projectMemory,
            ancestors = ancestorMemories
        )
    }

    /**
     * 向 CLAUDE.md 追加新内容（用于 #memory 命令）
     *
     * 当用户在对话中使用 "#memory 记住这件事" 时调用此方法。
     * 默认写入项目层（workingDir/CLAUDE.md），与 Claude Code 的 #memory 命令行为一致。
     *
     * 追加格式使用 Markdown 分隔符，保持文件可读性。
     *
     * @param content 要追加的记忆内容（用户想让 Claude 记住的信息）
     * @param targetPath 目标 CLAUDE.md 文件的完整路径
     */
    suspend fun appendMemory(content: String, targetPath: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(targetPath)

            // 如果文件或父目录不存在，则创建
            // 这允许用户在没有 CLAUDE.md 的项目中直接使用 #memory 命令
            if (!file.parentFile?.exists()!!) {
                file.parentFile?.mkdirs()
            }

            // 构建要追加的内容块
            // 使用时间戳和分隔线确保多次追加时内容清晰可读
            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()
            ).format(java.util.Date())

            val appendContent = buildString {
                // 如果文件已有内容，先加一个空行保持格式整洁
                if (file.exists() && file.length() > 0) {
                    append("\n\n")
                }
                append("## 记忆更新 ($timestamp)\n\n")
                append(content)
                append("\n")
            }

            // 追加模式写入，保留历史记忆
            file.appendText(appendContent, Charsets.UTF_8)

            android.util.Log.d(TAG, "记忆已追加到: $targetPath，内容长度: ${content.length}")
        } catch (e: Exception) {
            // 记忆写入失败不应该中断主流程，只记录错误
            android.util.Log.e(TAG, "追加记忆失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 尝试读取某个路径的 CLAUDE.md 文件
     *
     * 返回 null 而不是抛出异常，因为大多数路径不会有 CLAUDE.md，
     * 这是正常情况而非错误。调用方通过检查 null 来判断是否存在记忆。
     *
     * @param path CLAUDE.md 文件的完整路径（包含文件名）
     * @return 文件内容字符串，如果文件不存在或读取失败则返回 null
     */
    private fun tryReadClaudeMd(path: String): String? {
        return try {
            val file = File(path)
            // 只有文件存在且非空时才读取
            if (file.exists() && file.isFile && file.length() > 0) {
                val content = file.readText(Charsets.UTF_8).trim()
                // 过滤掉空文件（即使存在也没有实际价值）
                if (content.isNotEmpty()) content else null
            } else {
                null
            }
        } catch (e: Exception) {
            // 权限不足、IO 错误等情况静默处理，不影响其他层级的加载
            android.util.Log.w(TAG, "读取 CLAUDE.md 失败 ($path): ${e.message}")
            null
        }
    }

    /**
     * 收集工作目录到根目录的所有 CLAUDE.md 文件内容
     *
     * 遍历策略：从工作目录的父目录开始向上遍历，直到文件系统根目录（/）。
     * 注意：工作目录本身的 CLAUDE.md 已在 loadMemory 中作为项目层处理，
     * 这里只收集父目录及更上层的 CLAUDE.md。
     *
     * 为什么要收集祖先目录？
     * 在 monorepo 中，目录结构可能是：
     *   /repo/CLAUDE.md           <- 整个仓库的通用约定
     *   /repo/apps/CLAUDE.md      <- 所有 App 共享的约定
     *   /repo/apps/android/CLAUDE.md  <- 当前工作目录（项目层）
     *
     * 祖先层允许 /repo/ 和 /repo/apps/ 的约定也被 Claude 知晓。
     *
     * @param workingDirectory 当前工作目录（不包含在结果中）
     * @return 从最近祖先到最远祖先排列的记忆内容列表
     */
    private fun collectAncestorMemory(workingDirectory: String): List<String> {
        val result = mutableListOf<String>()
        var currentDir = File(workingDirectory).parentFile

        // 向上遍历目录树，直到到达根目录
        while (currentDir != null && currentDir.absolutePath != "/") {
            val claudeMdPath = "${currentDir.absolutePath}/$CLAUDE_MD_FILENAME"
            val content = tryReadClaudeMd(claudeMdPath)
            if (content != null) {
                result.add(content)
                android.util.Log.d(TAG, "找到祖先层记忆: $claudeMdPath")
            }
            // 继续向上一级
            currentDir = currentDir.parentFile
        }

        return result
    }
}

/**
 * 记忆上下文 — 保存所有层级读取到的记忆内容
 *
 * 这是一个不可变的数据类（data class），持有某次 loadMemory 调用的完整结果。
 * 使用不可变设计是因为记忆上下文在一次 Agent 任务期间应该保持稳定，
 * 不应该被中途修改（类似 Claude Code 在任务开始时一次性加载所有记忆）。
 */
data class MemoryContext(
    /** 企业级记忆内容，来自 ~/.claude/CLAUDE.md，null 表示该文件不存在 */
    val enterprise: String?,

    /** 用户全局记忆内容，来自 ~/CLAUDE.md，null 表示该文件不存在 */
    val userGlobal: String?,

    /** 项目级记忆内容，来自 {workingDir}/CLAUDE.md，null 表示该文件不存在 */
    val project: String?,

    /** 祖先目录记忆列表，按从近到远顺序排列（第一个元素是最近的父目录） */
    val ancestors: List<String>
) {

    /**
     * 检查是否存在任何有效的记忆内容
     *
     * 用于在系统提示词构建时判断是否需要添加"以下是您的记忆内容"前缀。
     * 如果所有层级都没有内容，就不需要在系统提示词中添加记忆区块。
     */
    fun hasContent(): Boolean {
        return enterprise != null || userGlobal != null ||
                project != null || ancestors.isNotEmpty()
    }

    /**
     * 将所有层级的记忆内容合并为一个字符串，注入到系统提示词中
     *
     * 合并顺序按优先级从高到低排列，高优先级的内容排在前面。
     * 这样当 Claude 处理系统提示词时，重要的企业策略最先被"看到"。
     *
     * 格式设计：
     * - 使用 Markdown 二级标题区分层级，保持可读性
     * - 每个层级前有说明，帮助 Claude 理解这些内容的来源和权重
     * - 祖先层按从近到远排列，近处的约定比远处的更具体
     */
    val combinedContent: String by lazy {
        buildString {
            // 记忆区块的整体标题，与 Claude Code 注入格式一致
            append("# 项目记忆（CLAUDE.md 内容）\n\n")
            append("以下是从不同层级加载的项目记忆，请在整个任务中遵守这些约定。\n\n")

            // 企业策略层（最高优先级，最先展示）
            if (enterprise != null) {
                append("## 企业策略（最高优先级，必须遵守）\n\n")
                append(enterprise)
                append("\n\n")
            }

            // 用户全局层
            if (userGlobal != null) {
                append("## 用户全局偏好\n\n")
                append(userGlobal)
                append("\n\n")
            }

            // 项目层
            if (project != null) {
                append("## 当前项目约定\n\n")
                append(project)
                append("\n\n")
            }

            // 祖先目录层（按从近到远排列）
            if (ancestors.isNotEmpty()) {
                append("## 父目录约定（从最近到最远）\n\n")
                ancestors.forEachIndexed { index, content ->
                    append("### 第 ${index + 1} 级父目录\n\n")
                    append(content)
                    append("\n\n")
                }
            }
        }.trimEnd()
    }
}
