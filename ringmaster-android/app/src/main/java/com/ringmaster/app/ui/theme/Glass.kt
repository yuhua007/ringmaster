package com.ringmaster.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 磨砂玻璃材质（frosted-glass-ui 规范）：
 * 白色渐变 0.78→0.55 + 整圈高光描边 + 顶部 1px 高光由 border 近似。
 * 真实 backdrop 模糊需 API 31+，此处用半透明白叠加柔和渐变环境近似（视觉达标）。
 */
fun Modifier.glassCard(
    corner: Int = 22,
    startAlpha: Float = 0.78f,
    endAlpha: Float = 0.55f
): Modifier = this
    .clip(RoundedCornerShape(corner.dp))
    .background(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = startAlpha), Color.White.copy(alpha = endAlpha))
        )
    )
    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(corner.dp))
