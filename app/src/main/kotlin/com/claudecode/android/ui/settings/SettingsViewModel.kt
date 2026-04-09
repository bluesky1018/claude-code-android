package com.claudecode.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudecode.android.mcp.McpClient
import com.claudecode.android.skills.SkillsManager
import com.claudecode.android.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 设置页面 ViewModel — 集成所有新配置项
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val mcpClient: McpClient,
    private val skillsManager: SkillsManager
) : ViewModel() {

    data class SettingsUiState(
        val apiKey: String = "",
        val showApiKey: Boolean = false,
        val selectedModel: String = "claude-sonnet-4-6",
        val permissionMode: String = "default",
        val effortLevel: String = "medium",
        val braveSearchKey: String = "",
        val workingDirectory: String = "",
        val maxTurns: Int? = null,
        val maxBudgetUsd: Double? = null,
        val mcpServersJson: String = "{}",
        val hookConfigJson: String = "{}",
        val permissionRulesJson: String = "{}",
        val skillsCount: Int = 0,
        val showClearDataDialog: Boolean = false,
        val saveSuccess: Boolean = false,
        val errorMessage: String? = null
    )

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val availableModels = listOf(
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-haiku-4-5"
    )

    val permissionModes = listOf(
        "default" to "默认（读取免确认）",
        "acceptEdits" to "接受编辑（文件操作免确认）",
        "plan" to "仅规划（只读）",
        "auto" to "自动模式（AI 分类审核）",
        "dontAsk" to "不询问（仅预批准工具）",
        "bypassPermissions" to "跳过所有权限（危险）"
    )

    val effortLevels = listOf(
        "low" to "低（快速）",
        "medium" to "中（均衡）",
        "high" to "高（深度）",
        "max" to "最大（仅 Opus 4.6）"
    )

    init { loadSettings() }

    private fun loadSettings() {
        _uiState.update { state -> state.copy(
            apiKey = settingsRepository.getApiKey(),
            selectedModel = settingsRepository.getModel(),
            permissionMode = settingsRepository.getPermissionMode(),
            effortLevel = settingsRepository.getEffortLevel(),
            braveSearchKey = settingsRepository.getBraveSearchKey(),
            workingDirectory = settingsRepository.getWorkingDirectory(),
            maxTurns = settingsRepository.getMaxTurns(),
            maxBudgetUsd = settingsRepository.getMaxBudgetUsd(),
            mcpServersJson = settingsRepository.getMcpServers(),
            hookConfigJson = settingsRepository.getHookConfigs(),
            permissionRulesJson = settingsRepository.getPermissionRules()
        )}
    }

    fun setApiKey(key: String) = _uiState.update { it.copy(apiKey = key) }
    fun toggleApiKeyVisibility() = _uiState.update { it.copy(showApiKey = !it.showApiKey) }
    fun setModel(model: String) = _uiState.update { it.copy(selectedModel = model) }
    fun setPermissionMode(mode: String) = _uiState.update { it.copy(permissionMode = mode) }
    fun setEffortLevel(level: String) = _uiState.update { it.copy(effortLevel = level) }
    fun setBraveSearchKey(key: String) = _uiState.update { it.copy(braveSearchKey = key) }
    fun setWorkingDirectory(dir: String) = _uiState.update { it.copy(workingDirectory = dir) }
    fun setMaxTurns(turns: Int?) = _uiState.update { it.copy(maxTurns = turns) }
    fun setMaxBudgetUsd(budget: Double?) = _uiState.update { it.copy(maxBudgetUsd = budget) }
    fun setMcpServersJson(json: String) = _uiState.update { it.copy(mcpServersJson = json) }
    fun setHookConfigJson(json: String) = _uiState.update { it.copy(hookConfigJson = json) }
    fun setPermissionRulesJson(json: String) = _uiState.update { it.copy(permissionRulesJson = json) }

    fun saveSettings() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                settingsRepository.saveApiKey(state.apiKey)
                settingsRepository.setModel(state.selectedModel)
                settingsRepository.setPermissionMode(state.permissionMode)
                settingsRepository.setEffortLevel(state.effortLevel)
                settingsRepository.saveBraveSearchKey(state.braveSearchKey)
                settingsRepository.setWorkingDirectory(state.workingDirectory)
                settingsRepository.setMaxTurns(state.maxTurns)
                settingsRepository.setMaxBudgetUsd(state.maxBudgetUsd)
                settingsRepository.setMcpServers(state.mcpServersJson)
                settingsRepository.setHookConfigs(state.hookConfigJson)
                settingsRepository.setPermissionRules(state.permissionRulesJson)

                _uiState.update { it.copy(saveSuccess = true, errorMessage = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "保存失败：${e.message}") }
            }
        }
    }

    fun exportConfig(): String = settingsRepository.exportAsClaudeJson()

    fun importConfig(json: String) {
        settingsRepository.importFromClaudeJson(json)
        loadSettings()
        _uiState.update { it.copy(saveSuccess = true) }
    }

    fun showClearDataDialog() = _uiState.update { it.copy(showClearDataDialog = true) }
    fun dismissClearDataDialog() = _uiState.update { it.copy(showClearDataDialog = false) }

    fun clearAllData() {
        settingsRepository.clearAll()
        _uiState.update { SettingsUiState() }
    }
}
