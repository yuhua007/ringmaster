package com.ringmaster.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * RingMaster 设计令牌 —— 磨砂玻璃浅色主题（frosted-glass-ui 规范）。
 *
 * 规范要点（docs + skill）：
 * - 表面 = 白色 + 透明度（0.78→0.55 渐变），白色主导 75-85%
 * - 文字近黑藏青 #171A26，次文字 #565D6E
 * - 主按钮深藏青 #1D2133 底 + 白字；强调色蓝紫 #5B5BD6
 * - 描边高光 rgba(255,255,255,.35)；阴影只用大而柔
 * - dark-first 已废弃：浅色玻璃为唯一首发主题
 */
object RmColor {
    val Accent = Color(0xFF5B5BD6)              // 蓝紫强调（波形选中/手柄/图标）
    val AccentPress = Color(0xFF4B4BC2)
    val AccentDim = Accent.copy(alpha = 0.35f)
    val PrimaryDark = Color(0xFF1D2133)         // 主按钮深藏青
    val BgBase = Color(0xFFE9EDF6)              // 环境底（柔和冷灰蓝）
    val BgElevated = Color.White.copy(alpha = 0.72f)   // 玻璃卡
    val BgElevated2 = Color.White.copy(alpha = 0.55f)  // 次级玻璃
    val ContentPrimary = Color(0xFF171A26)
    val ContentSecondary = Color(0xFF565D6E)
    val ContentTertiary = Color(0xFF9AA1B0)
    val Divider = Color(0xFF171A26).copy(alpha = 0.08f)
    val Success = Color(0xFF2E9E6B)
    val Error = Color(0xFFC0472F)

    /** 环境渐变（玻璃透出的柔和冷色） */
    val EnvGradient = Brush.verticalGradient(
        listOf(Color(0xFFEFF2FA), Color(0xFFE3E9F5), Color(0xFFDDE4F2))
    )
}

private val RmColors = lightColorScheme(
    primary = RmColor.PrimaryDark,
    onPrimary = Color.White,
    secondary = RmColor.Accent,
    onSecondary = Color.White,
    background = Color.Transparent,      // 让环境渐变透出
    surface = Color.Transparent,
    surfaceVariant = RmColor.BgElevated,
    onBackground = RmColor.ContentPrimary,
    onSurface = RmColor.ContentPrimary,
    onSurfaceVariant = RmColor.ContentSecondary,
    outline = RmColor.ContentPrimary.copy(alpha = 0.12f),
    error = RmColor.Error
)

private val RmTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp),
)

/** 时间码样式：等宽数字防跳变（MASTER.md §3）。 */
val RmTimecodeStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    color = RmColor.ContentPrimary
)

@Composable
fun RmTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = RmColors, typography = RmTypography, content = content)
}
