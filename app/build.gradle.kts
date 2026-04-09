plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.claudecode.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudecode.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ==================== 核心 Android ====================
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // ==================== Jetpack Compose UI ====================
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ==================== Kotlin 协程 ====================
    implementation(libs.kotlinx.coroutines.android)

    // ==================== 序列化 ====================
    implementation(libs.kotlinx.serialization.json)

    // ==================== HTTP 客户端（Ktor — SSE 流式输出）====================
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ==================== OkHttp（Hook HTTP 端点 + WebFetch）====================
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // ==================== HTML 解析（WebFetch 工具）====================
    implementation(libs.jsoup)

    // ==================== 代码编辑器（Sora Editor）====================
    implementation(libs.sora.editor)
    implementation(libs.sora.editor.language.treesitter)

    // ==================== Markdown 渲染 ====================
    implementation(libs.multiplatform.markdown.renderer.m3)
    implementation(libs.multiplatform.markdown.renderer.code)

    // ==================== WorkManager（定时任务调度器）====================
    implementation(libs.androidx.work.runtime.ktx)

    // ==================== Room DB（会话元数据持久化）====================
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ==================== 加密存储（API Key 安全存储）====================
    implementation(libs.androidx.security.crypto)

    // ==================== 依赖注入（Koin）====================
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // ==================== 图标扩展 ====================
    implementation(libs.androidx.material.icons.extended)

    // ==================== 测试 ====================
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
