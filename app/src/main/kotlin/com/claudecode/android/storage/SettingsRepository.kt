package com.claudecode.android.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.json.*

/**
 * 设置仓库 — 像素级复刻真实 Claude Code 的配置文件格式
 *
 * 真实 Claude Code 配置层级：
 * 1. 企业策略：系统目录（只读）
 * 2. 用户设置：~/.claude.json
 * 3. 项目设置：.claude.json（提交到 git）
 * 4. 项目本地：.claude.local.json（不提交 git）
 *
 * 配置格式与 .claude.json 完全兼容
 */
class SettingsRepository(private val context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // 加密存储（API Key 等敏感信息）
    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "claude_encrypted_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // 普通存储（非敏感设置）
    private val prefs: SharedPreferences = context.getSharedPreferences("claude_settings", Context.MODE_PRIVATE)

    // ==================== API Key ====================

    /** 存储 Anthropic API Key（加密存储） */
    fun saveApiKey(apiKey: String) = encryptedPrefs.edit().putString("api_key", apiKey).apply()
    fun getApiKey(): String = encryptedPrefs.getString("api_key", "") ?: ""
    fun hasApiKey(): Boolean = getApiKey().isNotBlank()

    // ==================== 模型设置 ====================

    fun getModel(): String = prefs.getString("model", "claude-sonnet-4-6") ?: "claude-sonnet-4-6"
    fun setModel(model: String) = prefs.edit().putString("model", model).apply()

    /** --effort 级别：low/medium/high/max */
    fun getEffortLevel(): String = prefs.getString("effort", "medium") ?: "medium"
    fun setEffortLevel(level: String) = prefs.edit().putString("effort", level).apply()

    // ==================== 权限模式 ====================

    fun getPermissionMode(): String = prefs.getString("default_mode", "default") ?: "default"
    fun setPermissionMode(mode: String) = prefs.edit().putString("default_mode", mode).apply()

    // ==================== 工具访问控制 ====================

    fun getPermissionRules(): String = prefs.getString("permission_rules", "{}") ?: "{}"
    fun setPermissionRules(json: String) = prefs.edit().putString("permission_rules", json).apply()

    // ==================== 工作目录 ====================

    fun getWorkingDirectory(): String = prefs.getString("working_dir", "/storage/emulated/0") ?: "/storage/emulated/0"
    fun setWorkingDirectory(dir: String) = prefs.edit().putString("working_dir", dir).apply()

    // ==================== Hook 配置 ====================

    fun getHookConfigs(): String = prefs.getString("hooks", "{}") ?: "{}"
    fun setHookConfigs(json: String) = prefs.edit().putString("hooks", json).apply()

    // ==================== MCP Server 配置 ====================

    fun getMcpServers(): String = prefs.getString("mcp_servers", "{}") ?: "{}"
    fun setMcpServers(json: String) = prefs.edit().putString("mcp_servers", json).apply()

    // ==================== 搜索 API ====================

    fun getBraveSearchKey(): String = encryptedPrefs.getString("brave_search_key", "") ?: ""
    fun saveBraveSearchKey(key: String) = encryptedPrefs.edit().putString("brave_search_key", key).apply()

    // ==================== 会话设置 ====================

    fun getMaxTurns(): Int? = prefs.getInt("max_turns", -1).takeIf { it > 0 }
    fun setMaxTurns(turns: Int?) = prefs.edit().putInt("max_turns", turns ?: -1).apply()

    fun getMaxBudgetUsd(): Double? = prefs.getFloat("max_budget_usd", -1f).toDouble().takeIf { it > 0 }
    fun setMaxBudgetUsd(budget: Double?) = prefs.edit().putFloat("max_budget_usd", budget?.toFloat() ?: -1f).apply()

    // ==================== 导出为 .claude.json 格式 ====================

    /**
     * 导出为真实 Claude Code 的 .claude.json 格式
     * 可用于与桌面版 Claude Code 同步配置
     */
    fun exportAsClaudeJson(): String {
        return buildJsonObject {
            put("model", getModel())
            put("effort", getEffortLevel())
            putJsonObject("permissions") {
                put("defaultMode", getPermissionMode())
                try {
                    val rules = Json.parseToJsonElement(getPermissionRules()).jsonObject
                    rules.forEach { (k, v) -> put(k, v) }
                } catch (e: Exception) { /* 忽略解析错误 */ }
            }
            try {
                val hooks = Json.parseToJsonElement(getHookConfigs()).jsonObject
                if (hooks.isNotEmpty()) put("hooks", hooks)
            } catch (e: Exception) { /* 忽略 */ }
            try {
                val mcpServers = Json.parseToJsonElement(getMcpServers()).jsonObject
                if (mcpServers.isNotEmpty()) put("mcpServers", mcpServers)
            } catch (e: Exception) { /* 忽略 */ }
        }.let { Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), it) }
    }

    /** 从 .claude.json 导入配置 */
    fun importFromClaudeJson(json: String) {
        try {
            val obj = Json.parseToJsonElement(json).jsonObject
            obj["model"]?.jsonPrimitive?.content?.let { setModel(it) }
            obj["effort"]?.jsonPrimitive?.content?.let { setEffortLevel(it) }
            obj["permissions"]?.jsonObject?.let { perms ->
                perms["defaultMode"]?.jsonPrimitive?.content?.let { setPermissionMode(it) }
                setPermissionRules(perms.toString())
            }
            obj["hooks"]?.jsonObject?.let { setHookConfigs(it.toString()) }
            obj["mcpServers"]?.jsonObject?.let { setMcpServers(it.toString()) }
        } catch (e: Exception) {
            android.util.Log.e("SettingsRepository", "Import failed: ${e.message}")
        }
    }

    /** 清除所有设置 */
    fun clearAll() {
        prefs.edit().clear().apply()
        encryptedPrefs.edit().clear().apply()
    }
}
