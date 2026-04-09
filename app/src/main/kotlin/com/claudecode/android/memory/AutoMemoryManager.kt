package com.claudecode.android.memory

import android.content.Context
import java.io.File

/**
 * Auto Memory 管理器 — 像素级复刻 Claude Code 的 MEMORY.md 机制
 *
 * MEMORY.md 是 Claude 自动写入的学习记录（不同于用户写的 CLAUDE.md）
 * 存储位置：每个 git worktree 独立
 * 加载限制：最多 200 行或 25KB（取先到者）
 * 写入时机：Claude 观察到用户偏好或纠正时自动调用
 */
class AutoMemoryManager(private val context: Context) {

    companion object {
        const val MEMORY_FILENAME = "MEMORY.md"
        const val MAX_LINES = 200
        const val MAX_SIZE_BYTES = 25 * 1024  // 25KB
    }

    /**
     * 加载 Auto Memory 内容
     * 遵循真实 Claude Code 的 200行/25KB 限制
     */
    fun loadAutoMemory(workingDirectory: String): String? {
        val memoryFile = findMemoryFile(workingDirectory) ?: return null

        val content = memoryFile.readText()
        if (content.isBlank()) return null

        // 应用 200行 或 25KB 限制（取先到者）
        val lines = content.lines()
        return if (lines.size <= MAX_LINES && content.toByteArray().size <= MAX_SIZE_BYTES) {
            content
        } else {
            // 按行限制截断
            val linesTruncated = lines.take(MAX_LINES).joinToString("\n")
            // 再按大小限制截断
            if (linesTruncated.toByteArray().size > MAX_SIZE_BYTES) {
                val bytes = content.toByteArray()
                String(bytes, 0, MAX_SIZE_BYTES, Charsets.UTF_8)
            } else {
                linesTruncated
            }
        }
    }

    /**
     * 追加记忆条目
     * Claude 发现用户偏好时调用此方法
     */
    fun appendMemory(workingDirectory: String, content: String) {
        val memoryFile = getOrCreateMemoryFile(workingDirectory)
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm", java.util.Locale.US
        ).format(java.util.Date())

        memoryFile.appendText("\n\n<!-- Added $timestamp -->\n$content")

        // 裁剪到 200 行限制
        trimToLimit(memoryFile)
    }

    /**
     * 更新记忆（替换同主题的旧记忆）
     */
    fun updateMemory(workingDirectory: String, topic: String, content: String) {
        val memoryFile = getOrCreateMemoryFile(workingDirectory)
        val existing = memoryFile.readText()

        // 如果找到相关内容块则替换，否则追加
        if (existing.contains("## $topic")) {
            val newContent = existing.replace(
                Regex("## $topic.*?(?=## |$)", RegexOption.DOT_MATCHES_ALL),
                "## $topic\n$content\n\n"
            )
            memoryFile.writeText(newContent)
        } else {
            appendMemory(workingDirectory, "## $topic\n$content")
        }

        trimToLimit(memoryFile)
    }

    /**
     * 清除所有 Auto Memory
     */
    fun clearMemory(workingDirectory: String) {
        findMemoryFile(workingDirectory)?.delete()
    }

    /**
     * 查找 MEMORY.md 文件
     * 优先查找 .claude/MEMORY.md，其次是工作目录根
     */
    private fun findMemoryFile(workingDirectory: String): File? {
        val candidates = listOf(
            File("$workingDirectory/.claude/$MEMORY_FILENAME"),
            File("$workingDirectory/$MEMORY_FILENAME")
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    private fun getOrCreateMemoryFile(workingDirectory: String): File {
        val claudeDir = File("$workingDirectory/.claude")
        claudeDir.mkdirs()
        return File(claudeDir, MEMORY_FILENAME).also {
            if (!it.exists()) it.createNewFile()
        }
    }

    /** 按 200行/25KB 限制裁剪文件 */
    private fun trimToLimit(file: File) {
        val lines = file.readLines()
        if (lines.size > MAX_LINES) {
            file.writeText(lines.takeLast(MAX_LINES).joinToString("\n"))
        }
        if (file.length() > MAX_SIZE_BYTES) {
            val bytes = file.readBytes()
            file.writeBytes(bytes.takeLast(MAX_SIZE_BYTES).toByteArray())
        }
    }
}
