package com.claudecode.android.di

import android.content.Context
import com.claudecode.android.agent.AgentLoop
import com.claudecode.android.api.AnthropicClient
import com.claudecode.android.context.ContextManager
import com.claudecode.android.heartbeat.HeartbeatManager
import com.claudecode.android.hooks.HookSystem
import com.claudecode.android.memory.MemoryManager
import com.claudecode.android.scheduler.TaskScheduler
import com.claudecode.android.session.PermissionManager
import com.claudecode.android.storage.SettingsRepository
import com.claudecode.android.tools.ToolRegistry
import com.claudecode.android.ui.viewmodel.ChatViewModel
import com.claudecode.android.ui.viewmodel.SessionListViewModel
import com.claudecode.android.ui.viewmodel.SettingsViewModel
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Koin 依赖注入模块
 *
 * ## 什么是依赖注入（DI）？
 * DI 是一种设计模式，让组件不需要自己创建依赖，而是由外部注入。
 * 好处：
 * - 方便单元测试（可以注入 mock 对象）
 * - 组件之间解耦
 * - 全局单例管理（如 AnthropicClient 只需要一个实例）
 *
 * ## Koin 基本用法
 * - single { ... }：单例，整个 App 生命周期只创建一次
 * - factory { ... }：每次注入都创建新实例
 * - viewModel { ... }：ViewModel 实例，和界面生命周期绑定
 *
 * ## 依赖图（从底层到顶层）
 * Context（Android 系统提供）
 *   ↓
 * SettingsRepository（读取配置）
 *   ↓
 * AnthropicClient（API 通信）
 *   ↓
 * ToolRegistry, MemoryManager, ContextManager（工具和上下文）
 *   ↓
 * HookSystem, PermissionManager（横切关注点）
 *   ↓
 * AgentLoop（核心引擎）
 *   ↓
 * TaskScheduler, HeartbeatManager（调度和监控）
 *   ↓
 * ViewModel（UI 层）
 */

/**
 * 主应用模块
 * 包含所有层级的依赖声明
 */
val appModule = module {

    // ===================================================
    // 存储层（最底层，其他组件依赖它读取配置）
    // ===================================================

    /**
     * 设置仓库 — 单例
     * 读取 SharedPreferences 中的 API Key、默认模型等配置
     * get() 会自动注入 Context（由 androidContext() 提供）
     */
    single { SettingsRepository(androidContext()) }

    // ===================================================
    // API 层（网络通信）
    // ===================================================

    /**
     * Anthropic API 客户端 — 单例
     * 整个 App 只需要一个 HTTP 客户端实例（节省资源）
     * 从 SettingsRepository 读取 API Key
     */
    single {
        val settings = get<SettingsRepository>()
        AnthropicClient(
            apiKey = settings.getApiKey(),
            model  = settings.getModel()
        )
    }

    // ===================================================
    // 工具层（Agent 可以调用的工具集合）
    // ===================================================

    /**
     * 工具注册表 — 单例
     * 注册所有可用工具（Read/Write/Bash/Glob 等）
     * 工作目录从 SettingsRepository 读取
     */
    single {
        val settings = get<SettingsRepository>()
        ToolRegistry().apply {
            registerDefaults(workingDir = settings.getDefaultWorkingDir())
        }
    }

    // ===================================================
    // 核心引擎层
    // ===================================================

    /**
     * 内存管理器 — 单例
     * 管理 Agent 的对话历史和上下文压缩
     */
    single { MemoryManager(get()) }

    /**
     * 上下文管理器 — 单例
     * 管理系统 Prompt 的动态构建（SYSTEM_PROMPT 等）
     */
    single { ContextManager(get()) }

    /**
     * Hook 系统 — 单例
     * 拦截 Agent 行为（PreToolUse/PostToolUse/Stop 等 Hook）
     */
    single { HookSystem() }

    /**
     * 权限管理器 — 单例
     * 控制工具调用权限，管理用户确认对话框
     */
    single { PermissionManager() }

    /**
     * Agent 核心引擎 — 单例
     * 协调所有组件，驱动 Agent 循环
     * 依赖：AnthropicClient, ToolRegistry, MemoryManager,
     *        ContextManager, HookSystem, PermissionManager
     */
    single {
        AgentLoop(
            client            = get(),
            toolRegistry      = get(),
            memoryManager     = get(),
            contextManager    = get(),
            hookSystem        = get(),
            permissionManager = get()
        )
    }

    // ===================================================
    // 调度层（后台任务管理）
    // ===================================================

    /**
     * 定时任务调度器 — 单例
     * 管理 WorkManager 定时任务的注册、暂停、恢复
     */
    single { TaskScheduler(androidContext(), get()) }

    /**
     * 心跳管理器 — 单例
     * 监控 Agent Session 的健康状态
     */
    single { HeartbeatManager() }

    // ===================================================
    // UI 层（ViewModel，每个 Activity/Fragment 独立实例）
    // ===================================================

    /**
     * 聊天界面 ViewModel
     * 管理聊天消息流、工具调用结果展示
     * 依赖：AgentLoop（执行对话）, PermissionManager（权限弹窗）
     */
    viewModel { ChatViewModel(get(), get()) }

    /**
     * 设置界面 ViewModel
     * 管理 API Key、模型选择等设置的读写
     */
    viewModel { SettingsViewModel(get()) }

    /**
     * Session 列表 ViewModel
     * 展示历史 Agent Session 列表
     */
    viewModel { SessionListViewModel(get()) }
}

/**
 * 测试专用模块
 *
 * 在单元测试中替换真实实现为 Mock 对象，
 * 避免测试时真正调用 API 或修改文件。
 *
 * 使用方式：
 * ```kotlin
 * startKoin {
 *     modules(testModule)  // 替代 appModule
 * }
 * ```
 */
val testModule = module {
    // 使用 Mock SettingsRepository（返回固定测试数据）
    single { SettingsRepository(androidContext()) }

    // 使用 Mock AnthropicClient（返回预设响应，不真正调用 API）
    single {
        AnthropicClient(
            apiKey = "test-api-key",
            model  = "claude-opus-4-6"
        )
    }

    // 其他组件保持真实实现（可以在真实工具注册表上测试）
    single { ToolRegistry().apply { registerDefaults(workingDir = "/tmp/test") } }
    single { MemoryManager(get()) }
    single { ContextManager(get()) }
    single { HookSystem() }
    single { PermissionManager() }
    single { AgentLoop(get(), get(), get(), get(), get(), get()) }
    single { HeartbeatManager() }
    viewModel { ChatViewModel(get(), get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { SessionListViewModel(get()) }
}

/**
 * Koin 初始化入口
 *
 * 在 Application.onCreate() 中调用此函数完成 Koin 初始化。
 *
 * 示例（在 Application 类中调用）：
 * ```kotlin
 * class ClaudeCodeApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         initKoin(this)
 *     }
 * }
 * ```
 *
 * @param context Android Application Context
 */
fun initKoin(context: Context) {
    startKoin {
        // 注入 Android Context（供 single { androidContext() } 使用）
        androidContext(context)
        // 加载应用模块
        modules(appModule)
    }
}
