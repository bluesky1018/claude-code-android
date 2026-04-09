package com.claudecode.android.di

import android.content.Context
import com.claudecode.android.agent.AgentLoop
import com.claudecode.android.agent.SystemPromptBuilder
import com.claudecode.android.api.AnthropicClient
import com.claudecode.android.context.ContextManager
import com.claudecode.android.heartbeat.HeartbeatManager
import com.claudecode.android.hooks.HookSystem
import com.claudecode.android.mcp.McpClient
import com.claudecode.android.memory.AutoMemoryManager
import com.claudecode.android.memory.MemoryManager
import com.claudecode.android.scheduler.TaskScheduler
import com.claudecode.android.session.PermissionManager
import com.claudecode.android.skills.SkillsManager
import com.claudecode.android.storage.SessionRepository
import com.claudecode.android.storage.SettingsRepository
import com.claudecode.android.tools.ToolRegistry
import com.claudecode.android.ui.chat.ChatViewModel
import com.claudecode.android.ui.sessions.SessionListViewModel
import com.claudecode.android.ui.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin 依赖注入模块 — 串联所有核心组件
 *
 * 组件依赖关系：
 * SettingsRepository → AnthropicClient → ContextManager → AgentLoop
 * MemoryManager + AutoMemoryManager + SkillsManager → SystemPromptBuilder (object)
 * HookSystem → AgentLoop
 * PermissionManager → AgentLoop
 * McpClient → ToolRegistry → AgentLoop
 * SessionRepository → AgentLoop
 * AgentLoop → HeartbeatManager
 * AgentLoop → TaskScheduler
 * AgentLoop → ChatViewModel
 */
val appModule = module {

    // ==================== 存储层 ====================

    /** 设置仓库（加密 SharedPreferences） */
    single { SettingsRepository(androidContext()) }

    /** 会话仓库（Room DB + JSONL 文件） */
    single { SessionRepository(androidContext()) }

    // ==================== API 层 ====================

    /** Anthropic API 客户端（支持 SSE 流式、Prompt Caching、指数退避重试） */
    single {
        val settings = get<SettingsRepository>()
        AnthropicClient(apiKey = settings.getApiKey())
    }

    // ==================== 记忆系统 ====================

    /** CLAUDE.md 记忆管理器（4层：企业/用户/项目/子目录，支持 @import、CLAUDE.local.md） */
    single { MemoryManager(androidContext()) }

    /** Auto Memory 管理器（MEMORY.md，限 200行/25KB） */
    single { AutoMemoryManager(androidContext()) }

    // ==================== Skills 系统 ====================

    /** Skills（技能）管理器（.claude/skills/*.md 懒加载） */
    single { SkillsManager() }

    // ==================== 上下文管理 ====================

    /** 上下文压缩管理器（三级压缩策略：85%警告→90%压缩→95%强制截断） */
    single { ContextManager(get()) }

    // ==================== Hook 系统 ====================

    /** Hook 系统（23个事件，支持 command/http/prompt 三种 Hook 类型） */
    single {
        val hookSystem = HookSystem(androidContext())
        // 从设置中加载 Hook 配置
        val settings = get<SettingsRepository>()
        val hookConfig = settings.getHookConfigs()
        if (hookConfig.isNotBlank() && hookConfig != "{}") {
            hookSystem.loadFromSettings(hookConfig)
        }
        hookSystem
    }

    // ==================== 权限系统 ====================

    /** 权限管理器（6种模式：default/acceptEdits/plan/auto/dontAsk/bypassPermissions） */
    single {
        val permManager = PermissionManager()
        val settings = get<SettingsRepository>()
        val rules = settings.getPermissionRules()
        if (rules.isNotBlank() && rules != "{}") {
            permManager.loadRules(rules)
        }
        permManager
    }

    // ==================== MCP 客户端 ====================

    /** MCP (Model Context Protocol) 客户端（JSON-RPC 2.0，支持 stdio/http） */
    single {
        val mcpClient = McpClient()
        // MCP Server 连接在 AgentLoop 初始化时按需建立
        mcpClient
    }

    // ==================== 工具注册表 ====================

    /** 工具注册表（15 个核心工具 + MCP 动态工具） */
    single {
        ToolRegistry()
        // 注意：registerDefaults() 在 AgentLoop 初始化时调用（需要 agentLoopFactory 引用）
    }

    // ==================== Agent 核心引擎 ====================

    /** Agent Loop（核心引擎，像素级复刻 Claude Code 的 ReAct 循环） */
    single {
        val agentLoop = AgentLoop(
            apiClient = get(),
            toolRegistry = get(),
            contextManager = get(),
            hookSystem = get(),
            permissionManager = get(),
            memoryManager = get(),
            autoMemoryManager = get(),
            skillsManager = get(),
            mcpClient = get(),
            sessionRepository = get(),
            settingsRepository = get()
        )

        // 注册默认工具（包括 Agent 工具，传入 agentLoop 自身的工厂函数）
        val toolRegistry = get<ToolRegistry>()
        toolRegistry.registerDefaults(
            workingDirectory = get<SettingsRepository>().getWorkingDirectory(),
            agentLoopFactory = { agentLoop }  // Agent 工具创建子 Agent 时使用
        )

        agentLoop
    }

    // ==================== 心跳机制 ====================

    /** 心跳管理器（30秒间隔，监控 token 使用量、运行时间） */
    single { HeartbeatManager(get()) }

    // ==================== 定时任务 ====================

    /** 定时任务调度器（WorkManager + Cron 表达式） */
    single { TaskScheduler(androidContext()) }

    // ==================== ViewModels ====================

    /** 对话界面 ViewModel */
    viewModel {
        ChatViewModel(
            agentLoop = get(),
            permissionManager = get(),
            settingsRepository = get(),
            sessionRepository = get(),
            mcpClient = get()
        )
    }

    /** 会话列表 ViewModel */
    viewModel {
        SessionListViewModel(sessionRepository = get())
    }

    /** 设置页面 ViewModel */
    viewModel {
        SettingsViewModel(
            settingsRepository = get(),
            mcpClient = get(),
            skillsManager = get()
        )
    }
}

/** 测试用替代模块（单元测试时使用） */
val testModule = module {
    single { SettingsRepository(androidContext()) }
    single { AnthropicClient(apiKey = "test-key") }
    single { ToolRegistry() }
    single { HookSystem(androidContext()) }
    single { PermissionManager() }
    single { McpClient() }
    single { MemoryManager(androidContext()) }
    single { AutoMemoryManager(androidContext()) }
    single { SkillsManager() }
}
