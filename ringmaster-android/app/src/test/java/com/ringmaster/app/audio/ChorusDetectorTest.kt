package com.ringmaster.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChorusDetector 单元测试（golden test 起点：双端一致性断言的数据集与判据由此确立）。
 */
class ChorusDetectorTest {

    private fun shape(n: Int, low: Float, high: Float, from: Double, to: Double): FloatArray =
        FloatArray(n) { i -> val r = i.toDouble() / n; if (r in from..to) high else low }

    @Test
    fun highEnergyMiddleDetectedAndClippedTo30s() {
        val amp = shape(4000, 0.1f, 0.6f, 0.33, 0.67) // 180s 曲，中段约 60s 高能
        val r = ChorusDetector.detect(amp, 180_000)
        assertTrue("confident expected", r.confident)
        assertTrue("start in middle third, was ${r.startMs}", r.startMs >= 50_000)
        val len = r.endMs - r.startMs
        assertTrue("clipped to ~30s (+/-2s boundary), was $len", len in 29_000..35_000)
    }

    @Test
    fun uniformHighEnergyPicksStrongestWindow() {
        val amp = FloatArray(2000) { 0.5f } // 全程均匀高能（短视频全程高潮）
        val r = ChorusDetector.detect(amp, 90_000)
        assertTrue("uniform energy should not report not-found", r.confident)
        assertTrue(r.endMs > r.startMs)
    }

    @Test
    fun silentInputFallsBackGracefully() {
        val r = ChorusDetector.detect(FloatArray(1000) { 0f }, 60_000)
        assertFalse(r.confident)
        assertTrue(r.endMs >= r.startMs)
    }

    @Test
    fun emptyInputSafe() {
        val r = ChorusDetector.detect(FloatArray(0), 0)
        assertFalse(r.confident)
    }

    @Test
    fun shortHighlightExpands() {
        val amp = shape(3000, 0.1f, 0.8f, 0.49, 0.53) // 60s 曲中约 2.4s 高能
        val r = ChorusDetector.detect(amp, 60_000)
        assertTrue(r.confident)
        assertTrue("expected expansion beyond raw segment, len=${r.endMs - r.startMs}",
            r.endMs - r.startMs > 3_000)
    }

    @Test
    fun boundaryCorrectionBounded() {
        // 均匀背景（无静音点）：边界修正不得把选区拉到全曲
        val amp = shape(4000, 0.15f, 0.9f, 0.4, 0.6)
        val r = ChorusDetector.detect(amp, 180_000)
        val len = r.endMs - r.startMs
        assertTrue("selection must stay bounded, was $len", len in 29_000..40_000)
    }

    @Test
    fun multipleChorusesReturnedInTimeOrder() {
        // 240s 曲：三段高能（40s / 110s / 190s 附近）
        val n = 4000
        val amp = FloatArray(n) { i ->
            val sec = i.toDouble() / n * 240
            when {
                sec in 35.0..55.0 -> 0.7f
                sec in 105.0..130.0 -> 0.75f
                sec in 185.0..215.0 -> 0.8f
                else -> 0.08f
            }
        }
        val cands = ChorusDetector.detectCandidates(amp, 240_000)
        assertTrue("expected >=3 candidates, got ${cands.size}", cands.size >= 3)
        // 时间升序
        for (k in 1 until cands.size) {
            assertTrue("candidates must be time-ordered", cands[k].startMs > cands[k - 1].startMs)
        }
        // 第一个候选 = 第一段副歌（35s 附近）
        assertTrue("first candidate near 35-55s, was ${cands[0].startMs}",
            cands[0].startMs in 25_000..60_000)
        // 单结果入口 = 第一个候选
        val single = ChorusDetector.detect(amp, 240_000)
        assertTrue(single.startMs == cands[0].startMs)
    }
}
