package com.claudecode.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.claudecode.android.ui.chat.ChatScreen
import com.claudecode.android.ui.sessions.SessionListScreen
import com.claudecode.android.ui.settings.SettingsScreen
import com.claudecode.android.ui.theme.ClaudeCodeTheme

/**
 * MainActivity - 应用程序唯一的 Activity
 *
 * 采用单 Activity 架构（Single Activity Architecture），所有页面通过
 * Compose Navigation 在同一个 Activity 内切换，避免多 Activity 带来的
 * 状态管理复杂性和动画不流畅问题。
 *
 * enableEdgeToEdge() 启用沉浸式体验：
 * - 内容延伸到状态栏和导航栏区域
 * - 由各个 Composable 通过 WindowInsets 自行处理安全区域
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用边到边（Edge-to-Edge）沉浸式显示模式
        // 这会让应用内容绘制到系统状态栏和导航栏后面
        enableEdgeToEdge()

        setContent {
            // 应用全局主题包裹，确保所有子 Composable 能访问 MaterialTheme
            ClaudeCodeTheme {
                // Surface 作为最外层容器，使用主题背景色
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 初始化导航控制器并启动导航
                    val navController = rememberNavController()
                    AppNavigation(navController = navController)
                }
            }
        }
    }
}

/**
 * AppNavigation - 应用全局导航图
 *
 * 使用 Jetpack Compose Navigation 定义所有路由：
 * - "chat"：主聊天界面（默认首页）
 * - "sessions"：历史会话列表
 * - "settings"：应用设置
 *
 * 导航设计说明：
 * - 以 "chat" 为起始目的地，用户打开应用直接进入聊天
 * - 各页面通过 navController 互相跳转，保持单一导航栈
 *
 * @param navController 导航控制器，由父级传入以便灵活控制
 */
@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        // 默认首页：聊天界面
        startDestination = AppRoutes.CHAT
    ) {
        // 路由：主聊天界面
        // 这是应用的核心功能页面，用户在此与 Claude Code Agent 交互
        composable(route = AppRoutes.CHAT) {
            ChatScreen(navController = navController)
        }

        // 路由：历史会话列表
        // 展示所有历史对话记录，支持继续旧会话
        composable(route = AppRoutes.SESSIONS) {
            SessionListScreen(navController = navController)
        }

        // 路由：应用设置
        // 配置 API Key、模型选择、权限模式等参数
        composable(route = AppRoutes.SETTINGS) {
            SettingsScreen(navController = navController)
        }
    }
}

/**
 * AppRoutes - 路由常量定义
 *
 * 将所有路由字符串集中管理，避免在各处硬编码字符串
 * 导致路由名称不一致的问题
 */
object AppRoutes {
    /** 主聊天界面路由 */
    const val CHAT = "chat"

    /** 历史会话列表路由 */
    const val SESSIONS = "sessions"

    /** 应用设置路由 */
    const val SETTINGS = "settings"
}

// ===== Preview 预览 =====

/**
 * 预览：深色主题导航
 */
@Preview(
    name = "深色主题导航",
    showBackground = true,
    backgroundColor = 0xFF0D0D0D
)
@Composable
fun AppNavigationDarkPreview() {
    ClaudeCodeTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val navController = rememberNavController()
            AppNavigation(navController = navController)
        }
    }
}

/**
 * 预览：浅色主题导航
 */
@Preview(
    name = "浅色主题导航",
    showBackground = true,
    backgroundColor = 0xFFFFFFFF
)
@Composable
fun AppNavigationLightPreview() {
    ClaudeCodeTheme(darkTheme = false) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val navController = rememberNavController()
            AppNavigation(navController = navController)
        }
    }
}
