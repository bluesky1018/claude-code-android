package com.claudecode.android.skills

import android.util.Log
import java.io.File

/**
 * Skills（技能）管理器 — 像素级复刻真实 Claude Code 的 Skills 系统
 *
 * Skills 是 Claude Code 的扩展机制，类似于 slash command 的扩展：
 *
 * 存储位置：
 * - 全局：~/.claude/skills/*.md
 * - 项目：.claude/skills/*.md
 *
 * 技能文件格式（YAML frontmatter + Markdown body）：
 * ```
 * ---
 * name: code-review
 * description: Perform a thorough code review
 * trigger: /review
 * ---
 * When reviewing code, you should...
 * ```
 *
 * 注入到系统提示词：
 * - 仅在用户调用 `/skillname` 或 Claude 判断相关时才加载
 * - 懒加载，节省 token
 */
class SkillsManager {

    companion object {
        private const val TAG = "SkillsManager"
        const val SKILLS_DIR = ".claude/skills"
        const val GLOBAL_SKILLS_DIR = "skills"  // ~/.claude/skills/
    }

    data class Skill(
        val name: String,
        val description: String,
        val trigger: String?,          // 触发关键词，如 "/review"
        val content: String,           // 技能说明内容（注入到系统提示词）
        val filePath: String,
        val scope: Scope = Scope.PROJECT
    ) {
        enum class Scope { GLOBAL, PROJECT }
    }

    private val loadedSkills = mutableMapOf<String, Skill>()

    /**
     * 加载所有技能文件
     * @param workingDirectory 当前项目目录
     * @param homeDirectory 用户主目录（~/.claude/）
     */
    fun loadSkills(workingDirectory: String, homeDirectory: String) {
        loadedSkills.clear()

        // 加载全局技能（~/.claude/skills/*.md）
        val globalSkillsDir = File("$homeDirectory/$GLOBAL_SKILLS_DIR")
        if (globalSkillsDir.exists()) {
            globalSkillsDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
                parseSkillFile(file, Skill.Scope.GLOBAL)?.let { skill ->
                    loadedSkills[skill.name] = skill
                    Log.d(TAG, "Loaded global skill: ${skill.name}")
                }
            }
        }

        // 加载项目技能（.claude/skills/*.md），覆盖同名全局技能
        val projectSkillsDir = File("$workingDirectory/$SKILLS_DIR")
        if (projectSkillsDir.exists()) {
            projectSkillsDir.listFiles { f -> f.extension == "md" }?.forEach { file ->
                parseSkillFile(file, Skill.Scope.PROJECT)?.let { skill ->
                    loadedSkills[skill.name] = skill
                    Log.d(TAG, "Loaded project skill: ${skill.name}")
                }
            }
        }
    }

    /**
     * 解析技能文件（YAML frontmatter + Markdown body）
     */
    private fun parseSkillFile(file: File, scope: Skill.Scope): Skill? {
        return try {
            val content = file.readText()

            // 解析 YAML frontmatter
            val frontmatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)
            val match = frontmatterRegex.matchEntire(content.trim())

            val (frontmatter, body) = if (match != null) {
                Pair(match.groupValues[1], match.groupValues[2].trim())
            } else {
                // 无 frontmatter，使用文件名作为技能名
                Pair("", content.trim())
            }

            // 解析 frontmatter 字段
            val fields = parseFrontmatter(frontmatter)
            val skillName = fields["name"] ?: file.nameWithoutExtension
            val description = fields["description"] ?: "Skill: $skillName"
            val trigger = fields["trigger"]

            Skill(
                name = skillName,
                description = description,
                trigger = trigger,
                content = body,
                filePath = file.absolutePath,
                scope = scope
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill file ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * 解析简单的 YAML frontmatter
     * 仅支持 key: value 格式
     */
    private fun parseFrontmatter(yaml: String): Map<String, String> {
        return yaml.lines()
            .mapNotNull { line ->
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim()
                    val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                    Pair(key, value)
                } else null
            }
            .toMap()
    }

    /**
     * 根据用户输入判断是否触发技能
     * 返回匹配的技能（用于注入系统提示词）
     */
    fun findTriggeredSkills(userInput: String): List<Skill> {
        return loadedSkills.values.filter { skill ->
            skill.trigger != null && userInput.contains(skill.trigger, ignoreCase = true)
        }
    }

    /**
     * 根据名称查找技能
     */
    fun findSkillByName(name: String): Skill? = loadedSkills[name]

    /**
     * 获取所有技能的描述列表（用于注入系统提示词概览）
     */
    fun getAllSkillDescriptions(): List<com.claudecode.android.agent.SystemPromptBuilder.SkillDescription> {
        return loadedSkills.values.map { skill ->
            com.claudecode.android.agent.SystemPromptBuilder.SkillDescription(
                name = skill.name,
                description = skill.description,
                triggerKeyword = skill.trigger
            )
        }
    }

    /**
     * 获取特定技能的完整内容（懒加载，按需注入系统提示词）
     */
    fun getSkillContent(skillName: String): String? {
        return loadedSkills[skillName]?.content
    }

    /** 创建新技能文件 */
    fun createSkill(
        workingDirectory: String,
        name: String,
        description: String,
        content: String,
        trigger: String? = null
    ) {
        val skillsDir = File("$workingDirectory/$SKILLS_DIR")
        skillsDir.mkdirs()

        val frontmatter = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            if (trigger != null) appendLine("trigger: $trigger")
            appendLine("---")
        }

        File(skillsDir, "$name.md").writeText("$frontmatter\n$content")
        loadSkills(workingDirectory, System.getProperty("user.home") ?: "")
    }

    /** 获取已加载技能数量 */
    fun getSkillCount(): Int = loadedSkills.size
}
