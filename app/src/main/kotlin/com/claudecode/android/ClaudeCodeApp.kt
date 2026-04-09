package com.claudecode.android

import android.app.Application
import com.claudecode.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Application 入口类
 *
 * 负责：
 * 1. 初始化 Koin 依赖注入框架
 * 2. 注册全局模块（API 客户端、Agent Engine、工具注册表等）
 */
class ClaudeCodeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 启动 Koin 依赖注入
        // appModule 中定义了所有组件的创建方式
        startKoin {
            androidContext(this@ClaudeCodeApp)
            modules(appModule)
        }
    }
}
