plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)  // Kotlin Symbol Processing，用于 Room 代码生成
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"  // JSON 序列化
}

android {
    namespace = "com.claudecode.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudecode.android"
        minSdk = 26        // Android 8.0+，支持大多数现代设备
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true     // 启用 Jetpack Compose
    }
}

dependencies {
    // AndroidX 核心
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Room DB — 用于持久化会话历史、记忆、定时任务
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)   // KSP 代码生成

    // WorkManager — 用于后台定时任务
    implementation(libs.androidx.work.runtime.ktx)

    // Kotlin 协程
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Ktor HTTP Client — 用于 Anthropic API 的 SSE 流式通信
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.logging)

    // jsoup — HTML 解析，用于 WebFetch 工具提取网页文本
    implementation(libs.jsoup)

    // Sora Editor — 代码编辑器（支持语法高亮、自动补全）
    implementation(libs.sora.editor)
    implementation(libs.sora.editor.treesitter)
    implementation(libs.sora.editor.textmate)

    // Markdown 渲染 — Compose 原生，无需 WebView
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)

    // 图片加载
    implementation(libs.coil.compose)

    // Koin 依赖注入
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
