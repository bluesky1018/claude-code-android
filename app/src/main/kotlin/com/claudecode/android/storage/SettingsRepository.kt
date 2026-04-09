package com.claudecode.android.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.claudecode.android.session.PermissionMode
import org.json.JSONArray

/**
 * 设置仓库 — 管理 App 的所有用户配置
 *
 * 使用 Android SharedPreferences 存储，支持：
 * - Anthropic API Key（后续可加密存储）
 * - 默认模型选择
 * - 权限模式
 * - Brave Search API Key（WebSearch 工具）
 * - Hook 配置列表（JSON 格式）
 * - 默认工作目录
 *
 * ## 为什么用 SharedPreferences 而不是数据库？
 * 这些都是简单的键值对配置，SharedPreferences 足够用且无需 Schema 迁移。
 * 如果将来配置变复杂（如多账号），可以迁移到 DataStore 或 Room。
 *
 * ## 使用方式
 * ```kotlin
 * // 读取 API Key
 * val apiKey = settingsRepository.getApiKey()
 *
 * // 保存权限模式
 * settingsRepository.setPermissionMode(PermissionMode.ACCEPT_EDITS)
 * ```
 */
class SettingsRepository(private val context: Context) {

    companion object {
        /** SharedPreferences 文件名 */
        private const val PREFS_NAME = "claude_code_settings"

        // ===== 配置键名常量 =====
        private const val KEY_API_KEY           = "anthropic_api_key"
        private const val KEY_MODEL             = "default_model"
        private const val KEY_PERMISSION_MODE   = "permission_mode"
        private const val KEY_BRAVE_API_KEY     = "brave_search_api_key"
        private const val KEY_HOOK_CONFIGS      = "hook_configs_json"
        private const val KEY_DEFAULT_WORK_DIR  = "default_working_directory"

        // ===== 默认值 =====
        private const val DEFAULT_MODEL        = "claude-opus-4-6"
        private const val DEFAULT_WORK_DIR     = "/sdcard/claude-code"
        private val DEFAULT_PERMISSION_MODE    = PermissionMode.DEFAULT
    }

    /** 懒加载 SharedPreferences 实例（私有模式，其他 App 无法访问） */
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ===================================================
    // Anthropic API Key
    // ===================================================

    /**
     * 获取 Anthropic API Key
     *
     * @return API Key 字符串，未设置时返回空字符串
     */
    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    /**
     * 保存 Anthropic API Key
     *
     * 注意：当前直接明文存储，生产环境应使用 Android Keystore 加密。
     *
     * @param apiKey 要保存的 API Key
     */
    fun setApiKey(apiKey: String) {
        prefs.edit { putString(KEY_API_KEY, apiKey) }
    }

    // ===================================================
    // 默认模型
    // ===================================================

    /**
     * 获取默认使用的 Claude 模型
     *
     * @return 模型 ID（如 "claude-opus-4-6"）
     */
    fun getModel(): String {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    /**
     * 设置默认模型
     *
     * @param model 模型 ID（如 "claude-opus-4-6", "claude-sonnet-4-5"）
     */
    fun setModel(model: String) {
        prefs.edit { putString(KEY_MODEL, model) }
    }

    // ===================================================
    // 权限模式
    // ===================================================

    /**
     * 获取当前权限模式
     *
     * @return 权限模式枚举值
     */
    fun getPermissionMode(): PermissionMode {
        val modeName = prefs.getString(KEY_PERMISSION_MODE, DEFAULT_PERMISSION_MODE.name)
        return try {
            PermissionMode.valueOf(modeName ?: DEFAULT_PERMISSION_MODE.name)
        } catch (e: IllegalArgumentException) {
            // 读取到非法值时回退到默认模式
            DEFAULT_PERMISSION_MODE
        }
    }

    /**
     * 设置权限模式
     *
     * @param mode 新的权限模式
     */
    fun setPermissionMode(mode: PermissionMode) {
        prefs.edit { putString(KEY_PERMISSION_MODE, mode.name) }
    }

    // ===================================================
    // Brave Search API Key（WebSearch 工具使用）
    // ===================================================

    /**
     * 获取 Brave Search API Key
     *
     * @return API Key，未设置时返回空字符串
     */
    fun getBraveApiKey(): String {
        return prefs.getString(KEY_BRAVE_API_KEY, "") ?: ""
    }

    /**
     * 设置 Brave Search API Key
     *
     * @param apiKey Brave Search API Key
     */
    fun setBraveApiKey(apiKey: String) {
        prefs.edit { putString(KEY_BRAVE_API_KEY, apiKey) }
    }

    // ===================================================
    // Hook 配置（JSON 数组格式）
    // ===================================================

    /**
     * 获取 Hook 配置列表
     *
     * Hook 配置以 JSON 数组字符串形式存储，每个元素是一个 Hook 配置对象。
     * 示例格式：
     * ```json
     * [
     *   {"event": "PreToolUse", "matcher": "Bash", "command": "echo 'before bash'"},
     *   {"event": "PostToolUse", "matcher": "*", "command": "logger 'tool used'"}
     * ]
     * ```
     *
     * @return Hook 配置 JSON 数组字符串列表
     */
    fun getHookConfigs(): List<String> {
        val json = prefs.getString(KEY_HOOK_CONFIGS, "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { array.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 保存 Hook 配置列表
     *
     * @param configs Hook 配置列表（每项是 JSON 对象字符串）
     */
    fun setHookConfigs(configs: List<String>) {
        val array = JSONArray()
        configs.forEach { array.put(it) }
        prefs.edit { putString(KEY_HOOK_CONFIGS, array.toString()) }
    }

    // ===================================================
    // 默认工作目录
    // ===================================================

    /**
     * 获取 Agent 的默认工作目录
     *
     * @return 绝对路径字符串
     */
    fun getDefaultWorkingDir(): String {
        return prefs.getString(KEY_DEFAULT_WORK_DIR, DEFAULT_WORK_DIR) ?: DEFAULT_WORK_DIR
    }

    /**
     * 设置 Agent 的默认工作目录
     *
     * @param dir 绝对路径字符串（如 "/sdcard/projects"）
     */
    fun setDefaultWorkingDir(dir: String) {
        prefs.edit { putString(KEY_DEFAULT_WORK_DIR, dir) }
    }

    // ===================================================
    // 工具函数
    // ===================================================

    /**
     * 清除所有设置（恢复出厂默认值）
     * 用于"重置 App"功能
     */
    fun clearAll() {
        prefs.edit { clear() }
    }

    /**
     * 检查是否已配置 API Key
     *
     * @return true = 已配置有效的 API Key
     */
    fun hasApiKey(): Boolean {
        return getApiKey().isNotBlank()
    }
}
