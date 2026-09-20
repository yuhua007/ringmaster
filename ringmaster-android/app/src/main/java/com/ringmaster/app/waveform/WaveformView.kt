package com.ringmaster.app.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 波形图 + 视窗缩放/平移 + 选区交互。
 *
 * 手势约定：
 * - 单指：拖手柄调选区 / 拖选区中部平移选区 / 拖选区外平移视窗（放大后最常用）
 * - 双指：捏合缩放（centroid 为锚）/ 双指拖动平移视窗
 * - 双击：复位全曲
 * - 点击选区任意处：onTap(xMs)（tap-to-preview）
 */
@Composable
fun WaveformView(
    amplitudes: FloatArray,
    durationMs: Long,
    selectionStartMs: Long,
    selectionEndMs: Long,
    viewportStartMs: Long,
    viewportEndMs: Long,
    positionMs: Long,
    onSelectionChange: (startMs: Long, endMs: Long) -> Unit,
    onViewportChange: (startMs: Long, endMs: Long) -> Unit,
    onTap: (xMs: Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0) return
    // 浅色玻璃主题取色
    val idleColor = com.ringmaster.app.ui.theme.RmColor.ContentTertiary.copy(alpha = 0.45f)
    val activeColor = com.ringmaster.app.ui.theme.RmColor.Accent
    val handleColor = com.ringmaster.app.ui.theme.RmColor.PrimaryDark
    val overlayColor = com.ringmaster.app.ui.theme.RmColor.Accent.copy(alpha = 0.14f)

    val curStart by rememberUpdatedState(selectionStartMs)
    val curEnd by rememberUpdatedState(selectionEndMs)
    val curDuration by rememberUpdatedState(durationMs)
    var dragMode by remember { mutableStateOf(WaveformDragMode.START) }
    val textMeasurer = rememberTextMeasurer()
    var lastXMs by remember { mutableLongStateOf(0L) }
    var lastX by remember { mutableStateOf(0f) }

    // 视窗状态提升到上层；换歌重置；外部按钮变化时同步进来
    var viewStart by remember { mutableStateOf(viewportStartMs) }
    var viewEnd by remember { mutableStateOf(viewportEndMs) }
    LaunchedEffect(durationMs) { onViewportChange(0L, durationMs) }
    LaunchedEffect(viewportStartMs, viewportEndMs) { viewStart = viewportStartMs; viewEnd = viewportEndMs }

    fun xToMs(x: Float, w: Float): Long =
        (viewStart + x / w * (viewEnd - viewStart)).toLong()

    fun panViewport(dxPx: Float, w: Float) {
        val span = (viewEnd - viewStart).coerceAtLeast(1L)
        val panMs = -(dxPx / w * span).toLong()
        viewStart = (viewStart + panMs).coerceIn(0L, (curDuration - span).coerceAtLeast(0L))
        viewEnd = viewStart + span
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset -> onTap(xToMs(offset.x, size.width.toFloat())) },
                    onDoubleTap = { onViewportChange(0L, curDuration) } // 复位
                )
            }
            .pointerInput(Unit) {
                // 双指：捏合缩放 + 双指拖动平移。
                // pan 自算（不过滤消费状态，规避单指手势占指导致 calculatePan 归零）；
                // zoom 设阈值（|z-1|>0.01）避免拖动时微小间距变化触发缩放、把平移拉回。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2) {
                            val centroid = event.calculateCentroid()
                            if (centroid.isSpecified) {
                                val w = size.width.toFloat()
                                var sumX = 0f
                                for (c in event.changes) sumX += c.position.x - c.previousPosition.x
                                val panX = sumX / event.changes.size
                                val zooms = event.calculateZoom()
                                if (abs(zooms - 1f) > 0.01f) {
                                    val (s, e) = zoomViewport(
                                        viewStart, viewEnd, curDuration,
                                        xToMs(centroid.x, w), zooms
                                    )
                                    viewStart = s; viewEnd = e
                                } else if (panX != 0f) {
                                    panViewport(panX, w)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    onViewportChange(viewStart, viewEnd) // 手势结束同步外层（与缩放按钮协同）
                }
            }
            .pointerInput(amplitudes, durationMs) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val xMs = xToMs(offset.x, size.width.toFloat())
                        // 命中判定按屏幕像素（±60px），不随缩放比例变化
                        val spanPx = (viewEnd - viewStart).coerceAtLeast(1L).toFloat()
                        val dS = abs(offset.x - (curStart - viewStart).toFloat() / spanPx * size.width)
                        val dE = abs(offset.x - (curEnd - viewStart).toFloat() / spanPx * size.width)
                        val nearStart = dS < 88f && dS <= dE
                        val nearEnd = dE < 88f && dE < dS
                        // 选区已占满全曲（无可移动空间）时，选区内拖动也用于平移视窗
                        val selectionMovable = curStart > 0 || curEnd < curDuration
                        dragMode = when {
                            nearStart -> WaveformDragMode.START
                            nearEnd -> WaveformDragMode.END
                            xMs in curStart..curEnd && selectionMovable -> WaveformDragMode.MOVE
                            else -> WaveformDragMode.PAN // 选区外/选区占满时单指拖动 = 平移视窗
                        }
                        lastXMs = xMs
                        lastX = offset.x
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat()
                        val dxPx = change.position.x - lastX
                        lastX = change.position.x
                        if (dragMode == WaveformDragMode.PAN) {
                            panViewport(dxPx, w)
                            return@detectDragGestures
                        }
                        val xMs = xToMs(change.position.x, w)
                        val delta = xMs - lastXMs
                        lastXMs = xMs
                        val minLen = 500L
                        val selectionStartMs = curStart
                        val selectionEndMs = curEnd
                        val (newStart, newEnd) = when (dragMode) {
                            WaveformDragMode.START -> xMs.coerceIn(0, selectionEndMs - minLen) to selectionEndMs
                            WaveformDragMode.END -> selectionStartMs to xMs.coerceIn(selectionStartMs + minLen, curDuration)
                            WaveformDragMode.MOVE -> {
                                val len = selectionEndMs - selectionStartMs
                                val s = (selectionStartMs + delta).coerceIn(0, curDuration - len)
                                s to s + len
                            }
                            WaveformDragMode.PAN -> selectionStartMs to selectionEndMs // 不会到这里
                        }
                        onSelectionChange(newStart.coerceAtMost(newEnd - minLen), newEnd)
                    },
                    onDragEnd = { if (dragMode == WaveformDragMode.PAN) onViewportChange(viewStart, viewEnd) },
                    onDragCancel = { if (dragMode == WaveformDragMode.PAN) onViewportChange(viewStart, viewEnd) }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val span = (viewEnd - viewStart).coerceAtLeast(1L)
        fun msToX(ms: Long): Float = (ms - viewStart).toFloat() / span * w

        val startF = msToX(selectionStartMs)
        val endF = msToX(selectionEndMs)

        // 选区底色
        drawRect(
            color = overlayColor,
            topLeft = Offset(startF, 0f),
            size = Size((endF - startF).coerceAtLeast(0f), h)
        )

        // 波形柱：只画视窗内的桶
        val n = amplitudes.size.coerceAtLeast(1)
        val msPerBucket = durationMs.toFloat() / n
        val firstBucket = (viewStart / msPerBucket).toInt().coerceIn(0, n - 1)
        val lastBucket = (viewEnd / msPerBucket).toInt().coerceIn(0, n - 1)
        val bucketCount = (lastBucket - firstBucket + 1).coerceAtLeast(1)
        val barSpace = w / bucketCount
        for (b in firstBucket..lastBucket) {
            val x = msToX((b * msPerBucket).toLong()) + barSpace / 2
            val tMs = b * msPerBucket
            val inSelection = tMs >= selectionStartMs && tMs <= selectionEndMs
            val amp = (amplitudes[b] * 0.9f + 0.02f) * mid
            drawLine(
                color = if (inSelection) activeColor else idleColor,
                start = Offset(x, mid - amp),
                end = Offset(x, mid + amp),
                strokeWidth = (barSpace * 0.6f).coerceAtLeast(1.5f),
                cap = StrokeCap.Round
            )
        }

        // 起止时间标签（手柄上方小胶囊）
        fun fmt(ms: Long): String {
            val total = (ms / 1000).toInt()
            return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        }
        val labelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        fun drawLabel(x: Float, text: String, alignStart: Boolean) {
            val measured = textMeasurer.measure(text = text, style = labelStyle)
            val pad = 8f
            val w = measured.size.width + pad * 2
            val h = measured.size.height + 8f
            var lx = if (alignStart) x - 7f else x - w + 7f
            lx = lx.coerceIn(4f, size.width - w - 4f)
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.55f),
                topLeft = Offset(lx, 2f),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
            )
            drawText(textLayoutResult = measured, topLeft = Offset(lx + pad, 6f))
        }
        drawLabel(startF, "起 ${fmt(selectionStartMs)}", alignStart = true)
        drawLabel(endF, "止 ${fmt(selectionEndMs)}", alignStart = false)

        // 选区边界手柄：竖线 + 顶部大号胶囊抓手（18×36dp 圆角胶囊 + 白色拖动提示线）
        val capW = 18f
        val capH = 36f
        listOf(startF, endF).forEach { x ->
            drawLine(handleColor, Offset(x, capH - 4f), Offset(x, h), strokeWidth = 5f, cap = StrokeCap.Round)
            drawRoundRect(
                color = handleColor,
                topLeft = Offset(x - capW / 2, 0f),
                size = androidx.compose.ui.geometry.Size(capW, capH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(capW / 2, capW / 2)
            )
            drawLine(
                Color.White.copy(alpha = 0.85f),
                Offset(x - capW / 2 + 4f, capH / 2),
                Offset(x + capW / 2 - 4f, capH / 2),
                strokeWidth = 2.5f,
                cap = StrokeCap.Round
            )
        }

        // 播放头
        if (positionMs > 0) {
            val x = msToX(positionMs)
            drawLine(Color(0xFF171A26), Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
        }
    }
}

private enum class WaveformDragMode { START, END, MOVE, PAN }

/** 统一缩放数学：捏合手势与外部按钮共用，保证行为一致。1x–16x。 */
fun zoomViewport(
    viewStart: Long,
    viewEnd: Long,
    duration: Long,
    anchorMs: Long,
    factor: Float
): Pair<Long, Long> {
    val span = (viewEnd - viewStart).coerceAtLeast(1L)
    val minSpan = (duration / 16L).coerceAtLeast(500L)
    val newSpan = (span / factor).toLong().coerceIn(minSpan, duration)
    val ratio = (anchorMs - viewStart).toFloat() / span
    val s = (anchorMs - newSpan * ratio).toLong().coerceIn(0L, (duration - newSpan).coerceAtLeast(0L))
    return s to (s + newSpan)
}
