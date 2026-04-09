package com.claudecode.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ===== 深色主题颜色定义 =====
// 整体风格：仿终端黑色风格，适合开发者使用场景

/** 深色背景色 - 接近纯黑，减少夜间眼部疲劳 */
val DarkBackground = Color(0xFF0D0D0D)

/** 深色表面色 - 比背景略亮，用于卡片和对话框 */
val DarkSurface = Color(0xFF1A1A1A)

/** 深色次表面色 - 用于输入框、工具调用卡片等次级容器 */
val DarkSurfaceVariant = Color(0xFF252525)

/** Claude Code 终端绿 - 主操作按钮、链接、成功状态 */
val TerminalGreen = Color(0xFF00D26A)

/** 终端绿暗色变体 - 用于按下状态和次级强调 */
val TerminalGreenDark = Color(0xFF009E50)

/** 深色主题文字颜色 - 主要文本 */
val DarkOnBackground = Color(0xFFE8E8E8)

/** 深色主题次要文字 - 时间戳、辅助信息 */
val DarkOnSurfaceVariant = Color(0xFF8A8A8A)

/** 工具调用卡片边框颜色 */
val DarkOutline = Color(0xFF333333)

/** 错误颜色 - 用于错误消息和失败状态 */
val DarkError = Color(0xFFFF4444)

/** 用户消息气泡颜色 - 深蓝色 */
val UserBubbleDark = Color(0xFF1E3A5F)

// ===== 浅色主题颜色定义 =====
// 整体风格：简洁白色，以 Anthropic 品牌橙色为主色调

/** 浅色背景色 - 纯白，清晰专业 */
val LightBackground = Color(0xFFFFFFFF)

/** 浅色表面色 - 极浅灰，用于卡片区分 */
val LightSurface = Color(0xFFF8F8F8)

/** 浅色次表面色 */
val LightSurfaceVariant = Color(0xFFF0F0F0)

/** Anthropic 品牌橙色 - 主操作按钮，体现品牌特色 */
val AnthropicOrange = Color(0xFFD97706)

/** Anthropic 橙暗色变体 - 按下状态 */
val AnthropicOrangeDark = Color(0xFFB45309)

/** 浅色主题文字颜色 */
val LightOnBackground = Color(0xFF1A1A1A)

/** 浅色主题次要文字 */
val LightOnSurfaceVariant = Color(0xFF6B6B6B)

/** 浅色主题边框 */
val LightOutline = Color(0xFFE0E0E0)

/** 浅色错误颜色 */
val LightError = Color(0xFFDC2626)

/** 用户消息气泡颜色 - 浅色主题下的蓝色 */
val UserBubbleLight = Color(0xFF2563EB)

// ===== 深色 ColorScheme 配置 =====
private val DarkColorScheme = darkColorScheme(
    // 主色：终端绿，用于 FAB、选中状态、强调元素
    primary = TerminalGreen,
    // 主色容器：暗绿色背景，用于 Chip 等容器
    primaryContainer = Color(0xFF003A1E),
    // 在主色上显示的文字颜色
    onPrimary = Color(0xFF000000),
    // 在主色容器上显示的文字颜色
    onPrimaryContainer = TerminalGreen,
    // 次要色：用于次级操作
    secondary = Color(0xFF00A855),
    secondaryContainer = Color(0xFF002E15),
    onSecondary = Color(0xFF000000),
    onSecondaryContainer = Color(0xFF00D26A),
    // 背景：近黑色
    background = DarkBackground,
    onBackground = DarkOnBackground,
    // 表面：卡片、对话框等
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    // 边框和分隔线
    outline = DarkOutline,
    outlineVariant = Color(0xFF2A2A2A),
    // 错误状态
    error = DarkError,
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF3A0000),
    onErrorContainer = Color(0xFFFF4444),
    // 反色：用于深色主题下的浅色元素
    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = TerminalGreenDark,
    // 半透明着色层
    scrim = Color(0x99000000),
)

// ===== 浅色 ColorScheme 配置 =====
private val LightColorScheme = lightColorScheme(
    // 主色：Anthropic 橙色
    primary = AnthropicOrange,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF92400E),
    secondary = Color(0xFFB45309),
    secondaryContainer = Color(0xFFFDE68A),
    onSecondary = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF78350F),
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFE8E8E8),
    error = LightError,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    inverseSurface = Color(0xFF1A1A1A),
    inverseOnSurface = Color(0xFFE8E8E8),
    inversePrimary = AnthropicOrange,
    scrim = Color(0x99000000),
)

// ===== Typography 排版系统 =====
// 代码区域使用等宽字体，普通文本使用默认字体

val ClaudeCodeTypography = androidx.compose.material3.Typography(
    // 正文大字号 - 用于消息正文
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    // 正文中字号 - 用于设置项描述
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    // 正文小字号 - 用于时间戳、辅助信息
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // 标签大 - 用于按钮文字
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    // 标签中 - 用于工具调用卡片标签
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    // 标签小 - 用于极小的辅助标签
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,  // 等宽字体，适合显示代码标签
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    // 标题大 - 顶部 AppBar 标题
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // 标题中 - 对话框标题
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    // 标题小 - 设置分组标题
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    // 展示大 - 暂未使用
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    // 头部大 - 会话列表标题
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    // 头部中 - 空状态标题
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    // 头部小 - 分组标题
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
)

/**
 * ClaudeCodeTheme - 应用全局主题
 *
 * 设计原则：
 * - 深色模式：仿终端风格，黑色背景 + 终端绿，适合开发者夜间使用
 * - 浅色模式：简洁白色 + Anthropic 品牌橙，白天使用更舒适
 * - 支持 Android 12+ 动态颜色（可选关闭）
 *
 * @param darkTheme 是否使用深色模式，默认跟随系统
 * @param dynamicColor 是否启用动态颜色（Android 12+），默认关闭以保持品牌一致性
 * @param content 主题包裹的内容
 */
@Composable
fun ClaudeCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 默认关闭动态颜色，保持 Claude Code 独特的终端风格
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // 根据条件选择对应的 ColorScheme
    val colorScheme = when {
        // Android 12+ 动态颜色（Monet 系统）
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        // 深色模式：使用终端绿主题
        darkTheme -> DarkColorScheme
        // 浅色模式：使用 Anthropic 橙主题
        else -> LightColorScheme
    }

    // 获取当前 View，用于设置系统状态栏颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 获取 Activity 的 Window，设置状态栏颜色与背景一致
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            // 根据主题设置状态栏图标颜色（深色主题用浅色图标，浅色主题用深色图标）
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    // 应用 Material 3 主题
    MaterialTheme(
        colorScheme = colorScheme,
        typography = ClaudeCodeTypography,
        content = content
    )
}

// ===== 扩展颜色属性 =====
// 提供语义化的颜色访问，方便在 Composable 中使用

/** 代码文本颜色 - 等宽字体代码区域 */
val androidx.compose.material3.ColorScheme.codeTextColor: Color
    get() = if (this == DarkColorScheme) Color(0xFF00D26A) else Color(0xFF166534)

/** 工具调用成功颜色 */
val androidx.compose.material3.ColorScheme.toolSuccessColor: Color
    get() = if (this.background == DarkBackground) Color(0xFF00D26A) else Color(0xFF059669)

/** 工具调用失败颜色 */
val androidx.compose.material3.ColorScheme.toolErrorColor: Color
    get() = if (this.background == DarkBackground) DarkError else LightError

/** 用户消息气泡颜色 */
val androidx.compose.material3.ColorScheme.userBubbleColor: Color
    get() = if (this.background == DarkBackground) UserBubbleDark else UserBubbleLight
