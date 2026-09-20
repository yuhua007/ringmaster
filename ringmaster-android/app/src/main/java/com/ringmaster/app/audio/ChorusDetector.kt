package com.ringmaster.app.audio

import kotlin.math.max
import kotlin.math.min

/**
 * V0 能量法高潮检测（规格见 docs/ALGORITHM.md）。
 * 输入为 WaveformExtractor 的振幅包络（桶级 ≈45ms 分辨率），零额外解码成本，O(n)。
 *
 * 多候选：detectCandidates 返回按时间顺序的候选段列表（第一段副歌、第二段…），
 * UI 层每次点击循环切换，让用户在多个副歌间挑选。
 */
object ChorusDetector {

    data class Result(
        val startMs: Long,
        val endMs: Long,
        val confident: Boolean,
        val watermarkAvoidedMs: Long = 0L  // >0 表示检测到片尾水印并从该时间点截断
    )

    /** 单结果快捷入口（取第一个候选 = 第一段副歌）。 */
    fun detect(amplitudes: FloatArray, durationMs: Long): Result =
        detectCandidates(amplitudes, durationMs).firstOrNull()
            ?: Result(0L, min(30000L, max(0L, durationMs)), false)

    /**
     * 多候选检测：按时间顺序返回最多 [maxCandidates] 个候选段
     * （第一段 = 最早的副歌段），每个已做 30s 裁剪与边界修正。
     */
    fun detectCandidates(
        amplitudes: FloatArray,
        durationMs: Long,
        maxCandidates: Int = 5
    ): List<Result> {
        val n = amplitudes.size
        if (n <= 0 || durationMs <= 0) {
            return listOf(Result(0L, min(30000L, max(0L, durationMs)), false))
        }
        val msPerBucket = durationMs.toDouble() / n

        // 片尾水印（抖音/小红书等下载视频的固定口播）：剔除后参与选区计算
        val watermarkEnd = detectTrailingWatermark(amplitudes, durationMs, msPerBucket)
        val effectiveDuration = when {
            watermarkEnd != null -> watermarkEnd
            durationMs > 1500 -> durationMs - 1000L
            else -> durationMs
        }

        // 1. 平滑（前缀和滑动窗口，抑制瞬态尖峰）
        val win = max(1, n / 300)
        val smooth = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += amplitudes[i]
            if (i >= win) sum -= amplitudes[i - win]
            smooth[i] = (sum / minOf(i + 1, win)).toFloat()
        }

        // 2. 基线
        val mean = smooth.sum() / n
        if (mean <= 0.001f) {
            return listOf(Result(0L, min(30000L, effectiveDuration), false))
        }
        val threshold = 1.5f * mean

        // 3. 候选段：>threshold 连续桶；间隔 <1s 合并（副歌常被小缺口打断）
        val minGapBuckets = max(1, (1000.0 / msPerBucket).toInt())
        class Seg(val s: Int, val e: Int)
        val segs = mutableListOf<Seg>()
        var i = 0
        while (i < n) {
            if (smooth[i] > threshold) {
                var j = i
                while (j < n && smooth[j] > threshold) j++
                val last = segs.lastOrNull()
                if (last != null && i - last.e <= minGapBuckets) {
                    segs[segs.size - 1] = Seg(last.s, j)
                } else segs += Seg(i, j)
                i = j
            } else i++
        }

        // 碎段过滤（<1s 多为换气/瞬态）+ 时间顺序 + 上限
        val raw = segs
            .filter { (it.e - it.s) * msPerBucket >= 1000.0 }
            .take(maxCandidates)
        if (raw.isEmpty()) {
            val fb = strongestWindow(amplitudes, effectiveDuration, msPerBucket)
            return listOf(fb.copy(watermarkAvoidedMs = watermarkEnd ?: 0L))
        }

        // 重复性验证：副歌的本质是"重复出现"——与其他区域包络相关性高的段才是真副歌，
        // 剔除间奏/前奏/桥段这类"高能但不重复"的误判
        val scored = raw.map { seg ->
            seg to repetitionScore(seg.s, seg.e, amplitudes)
        }
        val repeated = scored.filter { it.second >= 0.55f }
        val chosen = (if (repeated.isNotEmpty()) repeated else scored).map { it.first }

        val candidates = chosen.map { seg ->
            processSegment(seg.s, seg.e, smooth, mean, threshold, n, msPerBucket, effectiveDuration)
                .copy(watermarkAvoidedMs = watermarkEnd ?: 0L)
        }
        if (candidates.isNotEmpty()) return candidates

        // 无相对高潮（全程均匀高能）：取最强 30s 窗
        val fallback = strongestWindow(amplitudes, effectiveDuration, msPerBucket)
        return listOf(fallback.copy(watermarkAvoidedMs = watermarkEnd ?: 0L))
    }

    /** 单段管线：30s 能量窗裁剪 / 短段扩展 / ±800ms 内局部低谷切点。 */
    private fun processSegment(
        segS: Int,
        segE: Int,
        smooth: FloatArray,
        mean: Float,
        threshold: Float,
        n: Int,
        msPerBucket: Double,
        effectiveDuration: Long
    ): Result {
        var startBucket = segS
        var endBucket = segE

        // 30s 目标：超长取段内能量最大 30s 窗；过短向两侧扩展
        val targetMs = 30000.0
        val segMs = (endBucket - startBucket) * msPerBucket
        if (segMs > targetMs) {
            val winLen = (targetMs / msPerBucket).toInt().coerceAtLeast(1)
            var bestS = startBucket
            var bestE = 0.0
            var wSum = 0.0
            for (k in startBucket until endBucket) {
                wSum += smooth[k]
                if (k - startBucket >= winLen) wSum -= smooth[k - winLen]
                if (k - startBucket + 1 >= winLen && wSum > bestE) { bestE = wSum; bestS = k - winLen + 1 }
            }
            startBucket = bestS
            endBucket = bestS + winLen
        } else if (segMs < 15000.0) {
            val lowBar = 0.7f * threshold
            var s = startBucket
            var e = endBucket
            while (s > 0 && smooth[s - 1] >= lowBar) s--
            while (e < n - 1 && smooth[e] >= lowBar) e++
            startBucket = s; endBucket = e
        }

        // 边界修正：在 ±800ms 内找局部能量低谷作为切点（贴合段落间隙，不多包前奏/间奏）
        val look = max(1, (800.0 / msPerBucket).toInt())
        val sFrom = (startBucket - look).coerceAtLeast(0)
        var s2 = startBucket
        var sVal = Float.MAX_VALUE
        for (k in sFrom..startBucket) if (smooth[k] < sVal) { sVal = smooth[k]; s2 = k }
        val eTo = (endBucket + look).coerceAtMost(n - 1)
        var e2 = endBucket
        var eVal = Float.MAX_VALUE
        for (k in endBucket..eTo) if (smooth[k] < eVal) { eVal = smooth[k]; e2 = k }

        val startMs = (s2 * msPerBucket).toLong().coerceIn(0, effectiveDuration)
        val endMs = (e2 * msPerBucket).toLong().coerceIn(startMs + 500, effectiveDuration)
        return Result(startMs, endMs, true)
    }

    /**
     * 重复性得分：段包络（归一化）与全曲其他位置的最优互相关系数 0..1。
     * 副歌重复 2+ 次 → 得分高；间奏/前奏独一段 → 得分低。
     */
    private fun repetitionScore(segS: Int, segE: Int, amplitudes: FloatArray): Float {
        val n = amplitudes.size
        val len = segE - segS
        if (len < 10 || len * 3 >= n) return 1f // 段太短或占全曲过大时不惩罚
        val seg = amplitudes.copyOfRange(segS, segE)
        val segMax = seg.max().coerceAtLeast(0.01f)
        var best = 0f
        var pos = 0
        val step = max(1, len / 2)
        while (pos + len <= n) {
            if (kotlin.math.abs(pos - segS) > len) {
                var wMax = 0f
                for (k in pos until pos + len) if (amplitudes[k] > wMax) wMax = amplitudes[k]
                if (wMax > 0.01f) {
                    var dot = 0f; var na = 0f; var nb = 0f
                    for (k in 0 until len) {
                        val a = seg[k] / segMax
                        val b = amplitudes[pos + k] / wMax
                        dot += a * b; na += a * a; nb += b * b
                    }
                    val corr = (dot / (kotlin.math.sqrt(na * nb) + 1e-6f))
                    if (corr > best) best = corr
                }
            }
            pos += step
        }
        return best
    }

    /**
     * 片尾水印检测（抖音/小红书等下载视频）：
     * 识别"尾部静音(>=0.3s)后出现孤立短声音块(<=5s)"的结构——音乐内部不会有此形态。
     * 命中返回内容真实结束点（水印整体截断）；未命中返回 null。
     */
    private fun detectTrailingWatermark(amplitudes: FloatArray, durationMs: Long, msPerBucket: Double): Long? {
        val n = amplitudes.size
        if (durationMs < 8000) return null
        val silenceBar = 0.06f
        val minSilenceBuckets = max(1, (300.0 / msPerBucket).toInt())
        val maxTailBuckets = (5000.0 / msPerBucket).toInt()
        var lastVoice = n - 1
        while (lastVoice >= 0 && amplitudes[lastVoice] < silenceBar) lastVoice--
        if (lastVoice < 0) return null
        var tailStart = lastVoice
        var i = lastVoice
        while (i >= 0 && (lastVoice - i) <= maxTailBuckets) {
            if (amplitudes[i] >= silenceBar) tailStart = i
            i--
        }
        val tailLen = lastVoice - tailStart + 1
        if (tailLen > maxTailBuckets) return null
        var gap = 0
        var j = tailStart - 1
        while (j >= 0 && amplitudes[j] < silenceBar) { gap++; j-- }
        if (gap < minSilenceBuckets || j < 0) return null
        val contentEndMs = (j * msPerBucket).toLong()
        if (contentEndMs < 5000) return null
        return contentEndMs
    }

    /**
     * Fallback：无相对高潮（全程均匀高能的短视频属此类）时，
     * 取平滑能量最高的 30 秒滑窗——直接选最精彩一段。
     */
    private fun strongestWindow(amplitudes: FloatArray, durationMs: Long, msPerBucket: Double): Result {
        val n = amplitudes.size
        val win = max(1, n / 300)
        val smooth = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += amplitudes[i]
            if (i >= win) sum -= amplitudes[i - win]
            smooth[i] = (sum / minOf(i + 1, win)).toFloat()
        }
        val mean = smooth.sum() / n
        if (mean <= 0.001f) return Result(0L, min(30000L, durationMs), false)
        val winLen = (30000.0 / msPerBucket).toInt().coerceAtLeast(1).coerceAtMost(n)
        var bestS = 0
        var bestE = -1.0
        var wSum = 0.0
        for (k in 0 until n) {
            wSum += smooth[k]
            if (k >= winLen) wSum -= smooth[k - winLen]
            if (k + 1 >= winLen && wSum > bestE) { bestE = wSum; bestS = k + 1 - winLen }
        }
        val start = (bestS * msPerBucket).toLong().coerceIn(0, durationMs - 500)
        return Result(start, (start + 30000).coerceAtMost(durationMs), true)
    }
}
