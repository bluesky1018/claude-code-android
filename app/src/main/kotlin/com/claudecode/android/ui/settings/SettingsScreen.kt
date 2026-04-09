package com.claudecode.android.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.claudecode.android.ui.theme.ClaudeCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// ===== 权限模式枚举 =====

/**
 * PermissionMode - Agent 权限模式枚举
 *
 * 定义 Agent 执行操作时的权限策略：
 * - DEFAULT：每次敏感操作需用户确认（最安全）
 * - ACCEPT_EDITS：自动允许文件编辑，Bash 命令仍需确认
 * - BYPASS_ALL：完全自动化，所有操作无需确认（谨慎使用！）
 * - PLAN_ONLY：只生成执行计划，不实际执行任何操作
 */
enum class PermissionMode(val displayName: String, val description: String) {
    DEFAULT(
        displayName = "默认模式",
        description = "每次涉及文件修改或命令执行时，都会弹窗请求用户确认。最安全，适合初次使用。"
    ),
    ACCEPT_EDITS(
        displayName = "自动接受编辑",
        description = "文件读写操作自动批准，Bash 命令仍需手动确认。适合日常开发任务。"
    ),
    BYPASS_ALL(
        displayName = "全自动模式",
        description = "⚠️ 所有操作均自动批准，无需任何确认。效率最高但风险最大，请在受信环境使用。"
    ),
    PLAN_ONLY(
        displayName = "仅规划模式",
        description = "Agent 只生成执行计划和分析报告，不执行任何实际操作。适合代码审查场景。"
    )
}

// ===== 模型选项 =====

/**
 * ModelOption - 可选 AI 模型
 *
 * 列出当前支持的 Claude 模型，以及各自的特点说明，
 * 帮助用户根据需求选择合适的模型。
 */
enum class ModelOption(val modelId: String, val displayName: String, val description: String) {
    OPUS_4_6(
        modelId = "claude-opus-4-6",
        displayName = "Claude Opus 4.6",
        description = "最强大的模型，复杂推理和代码任务首选"
    ),
    SONNET_4_5(
        modelId = "claude-sonnet-4-5",
        displayName = "Claude Sonnet 4.5",
        description = "性能与速度的最佳平衡，日常开发推荐"
    ),
    HAIKU_4(
        modelId = "claude-haiku-4",
        displayName = "Claude Haiku 4",
        description = "响应最快，适合简单任务和快速迭代"
    )
}

// ===== Settings UI 状态 =====

/**
 * SettingsUiState - 设置界面的完整 UI 状态
 *
 * @param apiKey Anthropic API Key
 * @param selectedModel 当前选中的模型
 * @param permissionMode 当前权限模式
 * @param braveApiKey Brave Search API Key（用于 WebSearch 工具）
 * @param isSaved 是否已保存，用于显示保存成功提示
 */
data class SettingsUiState(
    val apiKey: String = "",
    val selectedModel: ModelOption = ModelOption.SONNET_4_5,
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
    val braveApiKey: String = "",
    val isSaved: Boolean = false
)

/**
 * SettingsViewModel - 设置界面的 ViewModel
 *
 * 负责管理设置状态的读取和持久化。
 * 实际实现应通过 DataStore 或 SharedPreferences 持久化设置。
 */
class SettingsViewModel : ViewModel() {

    /** 内部可变状态 */
    private val _uiState = MutableStateFlow(SettingsUiState())

    /** 对外暴露的只读状态 */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /**
     * updateApiKey - 更新 API Key
     * @param key 新的 API Key 值
     */
    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key, isSaved = false) }
    }

    /**
     * updateSelectedModel - 更新选中模型
     * @param model 要切换的模型选项
     */
    fun updateSelectedModel(model: ModelOption) {
        _uiState.update { it.copy(selectedModel = model, isSaved = false) }
    }

    /**
     * updatePermissionMode - 更新权限模式
     * @param mode 新的权限模式
     */
    fun updatePermissionMode(mode: PermissionMode) {
        _uiState.update { it.copy(permissionMode = mode, isSaved = false) }
    }

    /**
     * updateBraveApiKey - 更新 Brave Search API Key
     * @param key 新的 Brave API Key
     */
    fun updateBraveApiKey(key: String) {
        _uiState.update { it.copy(braveApiKey = key, isSaved = false) }
    }

    /**
     * saveSettings - 保存所有设置
     * 实际实现应将数据持久化到 DataStore
     */
    fun saveSettings() {
        // 实际保存逻辑（DataStore / SharedPreferences）
        _uiState.update { it.copy(isSaved = true) }
    }

    /**
     * clearAllData - 清除所有应用数据
     * 包括 API Key、会话历史、缓存等
     */
    fun clearAllData() {
        _uiState.value = SettingsUiState()
    }
}

/**
 * SettingsScreen - 应用设置主界面
 *
 * 采用分组卡片展示，每组设置有明确的标题和说明：
 * 1. API 配置组：API Key 和模型选择
 * 2. Agent 行为组：权限模式设置
 * 3. 工具配置组：Brave Search API Key
 * 4. 关于组：版本信息和数据管理
 *
 * @param navController 导航控制器，用于返回上一页
 * @param viewModel 设置 ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 清除数据确认对话框的显示状态
    var showClearDataDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    // 返回按钮
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // 保存按钮
                    TextButton(
                        onClick = { viewModel.saveSettings() }
                    ) {
                        Text(
                            text = if (uiState.isSaved) "已保存 ✓" else "保存",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // 可滚动的设置列表
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === 第一组：API 配置 ===
            SettingsGroup(title = "API 配置") {
                // API Key 输入框
                ApiKeyField(
                    apiKey = uiState.apiKey,
                    onApiKeyChange = { viewModel.updateApiKey(it) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 模型选择下拉框
                ModelSelector(
                    selectedModel = uiState.selectedModel,
                    onModelSelected = { viewModel.updateSelectedModel(it) }
                )
            }

            // === 第二组：Agent 行为 ===
            SettingsGroup(title = "Agent 行为") {
                Text(
                    text = "权限模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                // 权限模式单选组
                PermissionModeSelector(
                    selectedMode = uiState.permissionMode,
                    onModeSelected = { viewModel.updatePermissionMode(it) }
                )
            }

            // === 第三组：工具配置 ===
            SettingsGroup(title = "工具配置") {
                // Brave Search API Key
                SecretTextField(
                    label = "Brave Search API Key",
                    hint = "用于 WebSearch 工具（可选）",
                    value = uiState.braveApiKey,
                    onValueChange = { viewModel.updateBraveApiKey(it) }
                )
            }

            // === 第四组：关于 ===
            SettingsGroup(title = "关于") {
                // 版本信息行
                SettingsInfoRow(
                    label = "版本",
                    value = "v0.1.0"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // GitHub 链接按钮
                Button(
                    onClick = {
                        // 打开系统浏览器访问 GitHub 仓库
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/bluesky1018/claude-code-android")
                        )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("在 GitHub 上查看源码")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 清除数据按钮（红色警告样式）
                Button(
                    onClick = { showClearDataDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清除所有数据")
                }
            }

            // 底部间距
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 清除数据确认对话框
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text("确认清除数据") },
            text = {
                Text(
                    "此操作将清除所有会话历史记录、API Key 设置和应用缓存，且无法恢复。确定要继续吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllData()
                        showClearDataDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * SettingsGroup - 设置分组容器
 *
 * 用 Card 组件包裹一组相关的设置项，提供视觉分组效果。
 * 顶部显示组标题，内容区域带内边距。
 *
 * @param title 分组标题
 * @param content 分组内的设置内容
 */
@Composable
fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        // 分组标题
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        // 分组内容卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * ApiKeyField - API Key 输入框
 *
 * 密码类型输入框，右侧有眼睛图标可切换显示/隐藏。
 * 输入内容会以圆点遮盖，保护 API Key 安全。
 *
 * @param apiKey 当前 API Key 值
 * @param onApiKeyChange API Key 变化回调
 */
@Composable
fun ApiKeyField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit
) {
    // 控制是否显示明文的状态
    var isVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text("Anthropic API Key") },
        placeholder = { Text("sk-ant-...") },
        modifier = Modifier.fillMaxWidth(),
        // 根据 isVisible 决定显示明文还是圆点
        visualTransformation = if (isVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password
        ),
        // 右侧切换显示/隐藏的眼睛图标
        trailingIcon = {
            IconButton(onClick = { isVisible = !isVisible }) {
                Icon(
                    imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (isVisible) "隐藏" else "显示",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        singleLine = true,
        supportingText = {
            Text(
                text = "在 https://console.anthropic.com 获取",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/**
 * SecretTextField - 通用密钥输入框
 *
 * 类似 ApiKeyField，用于其他需要保密的输入场景（如 Brave API Key）
 *
 * @param label 输入框标签
 * @param hint 占位符提示文字
 * @param value 当前值
 * @param onValueChange 值变化回调
 */
@Composable
fun SecretTextField(
    label: String,
    hint: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (isVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password
        ),
        trailingIcon = {
            IconButton(onClick = { isVisible = !isVisible }) {
                Icon(
                    imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (isVisible) "隐藏" else "显示",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        singleLine = true
    )
}

/**
 * ModelSelector - 模型选择下拉框
 *
 * 展示可用模型列表，用户点击后弹出下拉菜单进行选择。
 * 每个选项显示模型名称和简要说明。
 *
 * @param selectedModel 当前选中的模型
 * @param onModelSelected 模型选择回调
 */
@Composable
fun ModelSelector(
    selectedModel: ModelOption,
    onModelSelected: (ModelOption) -> Unit
) {
    // 控制下拉菜单展开状态
    var isExpanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = "AI 模型",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // 模拟 OutlinedTextField 样式的点击区域
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = true },
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(
                1.dp,
                if (isExpanded) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.background
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedModel.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = selectedModel.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = "选择模型",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 下拉菜单
            DropdownMenu(
                expanded = isExpanded,
                onDismissRequest = { isExpanded = false }
            ) {
                ModelOption.entries.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (model == selectedModel) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    text = model.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onModelSelected(model)
                            isExpanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * PermissionModeSelector - 权限模式单选组
 *
 * 展示所有权限模式选项，每个选项包含：
 * - RadioButton 单选按钮
 * - 模式名称（加粗）
 * - 模式说明文字（灰色小字）
 *
 * @param selectedMode 当前选中的权限模式
 * @param onModeSelected 权限模式选择回调
 */
@Composable
fun PermissionModeSelector(
    selectedMode: PermissionMode,
    onModeSelected: (PermissionMode) -> Unit
) {
    // selectableGroup 确保整组语义正确，供无障碍功能使用
    Column(modifier = Modifier.selectableGroup()) {
        PermissionMode.entries.forEachIndexed { index, mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = (mode == selectedMode),
                        onClick = { onModeSelected(mode) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                RadioButton(
                    selected = (mode == selectedMode),
                    onClick = null, // 点击事件由 Row 处理
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = mode.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (mode == selectedMode) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = mode.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mode == PermissionMode.BYPASS_ALL) {
                            MaterialTheme.colorScheme.error  // 全自动模式用红色警告
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // 分隔线（最后一项不加）
            if (index < PermissionMode.entries.size - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/**
 * SettingsInfoRow - 信息展示行
 *
 * 用于显示只读的键值对信息，如版本号。
 *
 * @param label 标签文字
 * @param value 显示值
 */
@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

// ===== Preview 预览 =====

/**
 * 预览：设置界面（深色主题）
 */
@Preview(name = "设置界面-深色", showBackground = true, backgroundColor = 0xFF0D0D0D)
@Composable
fun SettingsScreenDarkPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        SettingsScreen(navController = rememberNavController())
    }
}

/**
 * 预览：设置界面（浅色主题）
 */
@Preview(name = "设置界面-浅色", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
fun SettingsScreenLightPreview() {
    ClaudeCodeTheme(darkTheme = false) {
        SettingsScreen(navController = rememberNavController())
    }
}

/**
 * 预览：权限模式选择器
 */
@Preview(name = "权限模式选择", showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
fun PermissionModeSelectorPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Card(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                PermissionModeSelector(
                    selectedMode = PermissionMode.DEFAULT,
                    onModeSelected = {}
                )
            }
        }
    }
}
