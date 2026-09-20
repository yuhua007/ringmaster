package com.ringmaster.app.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.ringmaster.app.R
import com.ringmaster.app.audio.AiffDecoder
import com.ringmaster.app.audio.AmrDecoder
import com.ringmaster.app.audio.AudioCutter
import com.ringmaster.app.audio.WaveformExtractor
import com.ringmaster.app.ringtone.RingtoneSetter
import com.ringmaster.app.ui.theme.RmColor
import com.ringmaster.app.ui.theme.RmTimecodeStyle
import com.ringmaster.app.ui.theme.glassCard
import com.ringmaster.app.waveform.WaveformView
import com.ringmaster.app.waveform.zoomViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 编辑器：波形选段 + 试听 + 剪切导出/设为铃声。
 * 布局按设计系统 ANDROID.md：波形为主角，底部固定三键操作条（AI/试听/导出）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    initialStartMs: Long = -1L,
    initialEndMs: Long = -1L,
    initialFadeInMs: Long = 500L,
    initialFadeOutMs: Long = 500L,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var waveform by remember { mutableStateOf<WaveformExtractor.Waveform?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var selStart by remember { mutableLongStateOf(0L) }
    var selEnd by remember { mutableLongStateOf(0L) }
    var fadeInMs by remember { mutableLongStateOf(initialFadeInMs) }
    var fadeOutMs by remember { mutableLongStateOf(initialFadeOutMs) }
    var vpStart by remember { mutableLongStateOf(0L) }
    var loopPreview by remember { mutableStateOf(false) }
    var undoBuf by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var smartCandidates by remember { mutableStateOf<List<com.ringmaster.app.audio.ChorusDetector.Result>?>(null) }
    var smartIndex by remember { mutableIntStateOf(0) }
    var vpEnd by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("") }
    var cutFile by remember { mutableStateOf<File?>(null) }
    var savedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingContactUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var ringtoneDisplayName by remember { mutableStateOf("") }
    var ringtoneForContact by remember { mutableStateOf<android.net.Uri?>(null) }
    var showExportSheet by remember { mutableStateOf(false) }
    var showWriteSettings by remember { mutableStateOf(false) }
    var pendingRingtoneType by remember { mutableStateOf<RingtoneSetter.Type?>(null) }
    var log by remember { mutableStateOf("") }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 联系人专属铃声：系统联系人选择器（不读通讯录）+ 写单条记录权限
    val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val contact = pendingContactUri ?: return@rememberLauncherForActivityResult
        val ruri = ringtoneForContact ?: return@rememberLauncherForActivityResult
        if (granted) {
            val ok = RingtoneSetter.setForContact(context, contact, ruri)
            snackbarMsg = if (ok) context.getString(R.string.set_contact_done) else context.getString(R.string.set_failed)
        } else {
            snackbarMsg = context.getString(R.string.need_contact_permission)
        }
        pendingContactUri = null; ringtoneForContact = null
    }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { contactUri ->
        if (contactUri != null && savedUri != null) {
            pendingContactUri = contactUri
            ringtoneForContact = savedUri
            contactPermission.launch(android.Manifest.permission.WRITE_CONTACTS)
        }
    }

    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = false } }
    DisposableEffect(Unit) { onDispose { player.release() } }

    LaunchedEffect(isPlaying) {
        while (isActive && isPlaying) {
            positionMs = selStart + player.currentPosition
            if (!player.isPlaying && player.playbackState == ExoPlayer.STATE_ENDED) {
                isPlaying = false; positionMs = selStart
            }
            delay(80)
        }
    }
    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { snackbar.showSnackbar(it); snackbarMsg = null }
    }

    fun appendLog(s: String) { log = "$s\n$log" }

    /**
     * 播放 [fromMs, toMs) 选区。
     * AMR/AIFF 的 ExoPlayer 定位（seek）不可靠，先走我们的管线转临时 m4a 再播。
     */
    fun startPreview(fromMs: Long, toMs: Long) {
        val uri = inputUri ?: return
        positionMs = fromMs
        val needsConvert = AmrDecoder(context).sniff(uri) != null || AiffDecoder(context).sniff(uri) != null
        if (needsConvert) {
            scope.launch {
                busy = true; busyText = context.getString(R.string.preparing_preview)
                val tmp = File(context.cacheDir, "pv_${System.currentTimeMillis()}.m4a")
                AudioCutter(context).cut(uri, fromMs, toMs, fadeInMs = 0, fadeOutMs = 0, dest = tmp)
                    .onSuccess { r ->
                        player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(r.outputFile)))
                        player.prepare(); player.play(); isPlaying = true
                    }
                    .onFailure { snackbarMsg = context.getString(R.string.preview_failed, it.message ?: "?") }
                busy = false
            }
        } else {
            player.setMediaItem(clippedItem(uri, fromMs, toMs))
            player.prepare(); player.play(); isPlaying = true
        }
    }

    fun togglePreview() {
        if (isPlaying) { player.pause(); isPlaying = false }
        else startPreview(selStart, selEnd)
    }

    fun extractWaveform(restoreStart: Long = -1L, restoreEnd: Long = -1L) {
        val uri = inputUri ?: return
        scope.launch {
            busy = true; busyText = context.getString(R.string.analyzing)
            val t0 = System.currentTimeMillis()
            WaveformExtractor(context).extract(uri).onSuccess { wf ->
                waveform = wf
                durationMs = if (wf.durationMs > 0) wf.durationMs else 30_000L
                if (restoreStart >= 0 && restoreEnd > restoreStart) {
                    // 从配方恢复上次编辑现场
                    selStart = restoreStart.coerceIn(0L, durationMs)
                    selEnd = restoreEnd.coerceIn(selStart + 500L, durationMs)
                } else {
                    selStart = 0L              // 默认全选整曲；智能选区留给 AI 按钮
                    selEnd = durationMs
                }
                appendLog(context.getString(R.string.waveform_log, wf.amplitudes.size, wf.durationMs / 1000, System.currentTimeMillis() - t0))
            }.onFailure {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val msg = it.message ?: "?"
                snackbarMsg = when {
                    ext == "wma" -> context.getString(R.string.wma_unsupported)
                    msg.contains("instantiate extractor", ignoreCase = true) ->
                        context.getString(R.string.not_standard_audio)
                    else -> context.getString(R.string.cannot_read, msg)
                }
                appendLog(context.getString(R.string.extract_failed, it.message ?: "?"))
            }
            busy = false
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            player.stop(); isPlaying = false; positionMs = 0
            inputUri = uri
            cutFile = null
            waveform = null
            durationMs = 0
            smartCandidates = null; smartIndex = 0
            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
            extractWaveform()
        }
    }

    // 从铃声库/首页选择进入：直接加载已有文件重新编辑（MediaStore uri 免持久化授权）
    LaunchedEffect(initialUri) {
        if (initialUri != null && inputUri == null) {
            inputUri = initialUri
            fileName = runCatching {
                context.contentResolver.query(
                    initialUri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()?.removePrefix("RM ")
                ?: initialUri.lastPathSegment?.substringAfterLast('/')?.removePrefix("RM ")
                ?: "音频"
            extractWaveform(initialStartMs, initialEndMs)
        }
    }

    /** 波形卡片分享：用波形数据+当前选区直接绘制品牌卡片 PNG（不依赖截屏 API）。 */
    suspend fun makeShareCard(): android.net.Uri? = withContext(Dispatchers.Default) {
        runCatching {
            val wf = waveform ?: return@runCatching null
            val card = android.graphics.Bitmap.createBitmap(840, 1188, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(card)
            c.drawColor(android.graphics.Color.parseColor("#121212"))
            // 金色微光晕
            val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    420f, 560f, 520f,
                    android.graphics.Color.parseColor("#26C8A24B"),
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            c.drawCircle(420f, 560f, 520f, glow)
            // 波形（数据直绘：未选灰、选中金、手柄亮金）
            val n = wf.amplitudes.size.coerceAtLeast(1)
            val msPerBucket = if (durationMs > 0) durationMs.toFloat() / n else 0f
            val x0 = 40f; val waveW = 760f; val midY = 585f; val maxHalf = 215f
            val step = maxOf(1, n / 380)
            val idleP = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#3C3C3C")
                strokeWidth = 6f; strokeCap = android.graphics.Paint.Cap.ROUND
            }
            val selP = android.graphics.Paint(idleP).apply { color = android.graphics.Color.parseColor("#C8A24B") }
            for (i in 0 until n step step) {
                val x = x0 + i.toFloat() / n * waveW
                val t = i * msPerBucket
                val inSel = t >= selStart && t <= selEnd
                val half = (wf.amplitudes[i] * 0.9f + 0.02f) * maxHalf
                c.drawLine(x, midY - half, x, midY + half, if (inSel) selP else idleP)
            }
            val handleP = android.graphics.Paint(idleP).apply {
                color = android.graphics.Color.parseColor("#E9C873"); strokeWidth = 8f
            }
            if (durationMs > 0) {
                val sx = x0 + selStart.toFloat() / durationMs * waveW
                val ex = x0 + selEnd.toFloat() / durationMs * waveW
                c.drawLine(sx, midY - 245f, sx, midY + 245f, handleP)
                c.drawLine(ex, midY - 245f, ex, midY + 245f, handleP)
            }
            // 曲名 + 选段时间
            val name = fileName.removePrefix("RM ").substringBeforeLast('.')
            val trunc = if (name.length > 18) name.take(17) + "…" else name
            val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#F5F5F2"); textSize = 52f; isFakeBoldText = true
            }
            c.drawText(trunc, 40f, 230f, tp)
            val sp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#9E9E99"); textSize = 36f
            }
            c.drawText("${"%.1f".format(selStart / 1000.0)}s – ${"%.1f".format(selEnd / 1000.0)}s", 40f, 296f, sp)
            // 品牌
            val bp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#C8A24B"); textSize = 46f; isFakeBoldText = true
            }
            c.drawText("RingMaster", 40f, 1064f, bp)
            val gp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#9E9E99"); textSize = 30f
            }
            c.drawText("Cut the part you love", 40f, 1116f, gp)
            val f = File(context.cacheDir, "card_" + System.currentTimeMillis() + ".png")
            f.outputStream().use { card.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        }.getOrNull()
    }

    fun shareCutFile() {
        val f = cutFile ?: return
        com.ringmaster.app.data.StatsStore.record(context, com.ringmaster.app.data.StatsStore.KEY_SHARES)
        val sharedUri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", f
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(android.content.Intent.EXTRA_STREAM, sharedUri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(android.content.Intent.createChooser(intent, "分享铃声")) }
            .onFailure { snackbarMsg = context.getString(R.string.share_failed, it.message ?: "?") }
    }

    fun cutAndExport() {
        val uri = inputUri ?: return
        scope.launch {
            busy = true; busyText = context.getString(R.string.exporting)
            val dest = File(context.cacheDir, "cut_${System.currentTimeMillis()}.m4a")
            val t0 = System.currentTimeMillis()
            AudioCutter(context).cut(uri, selStart, selEnd, fadeInMs = fadeInMs, fadeOutMs = fadeOutMs, dest = dest)
                .onSuccess { r ->
                    cutFile = r.outputFile
                    appendLog(context.getString(R.string.export_log, r.durationMs, r.decodeMs, r.encodeMs, System.currentTimeMillis() - t0))
                    // 导出即保存到铃声库（不设默认），并记录剪辑配方（原始文件+选区），支撑"重新编辑"回到现场
                    val wf2 = waveform
                    runCatching {
                        val sUri = withContext(Dispatchers.IO) {
                            RingtoneSetter.saveToRingtoneDir(
                                context, r.outputFile,
                                fileName.substringBeforeLast('.'),
                                RingtoneSetter.Type.RINGTONE
                            )
                        }
                        savedUri = sUri
                        ringtoneDisplayName = fileName.substringBeforeLast('.')
                        appendLog(context.getString(R.string.saved_library))
                        withContext(Dispatchers.IO) {
                            com.ringmaster.app.data.RecipeStore.save(
                                context,
                                com.ringmaster.app.data.ClipRecipe(
                                    ringtoneId = android.content.ContentUris.parseId(sUri),
                                    sourceUri = uri.toString(),
                                    sourceName = fileName,
                                    startMs = selStart,
                                    endMs = selEnd,
                                    fadeInMs = fadeInMs,
                                    fadeOutMs = fadeOutMs,
                                    createdAt = System.currentTimeMillis(),
                                    miniWave = run {
                                        // 采样选区段波形为 60 点迷你包络（首页缩略图）
                                        val amps = wf2?.amplitudes ?: FloatArray(0)
                                        if (amps.isEmpty()) null else run {
                                        val s0 = ((selStart.toFloat() / durationMs) * amps.size).toInt().coerceIn(0, amps.size - 1)
                                        val s1 = ((selEnd.toFloat() / durationMs) * amps.size).toInt().coerceIn(s0 + 1, amps.size)
                                        val segAmp = amps.copyOfRange(s0, s1)
                                        FloatArray(60) { k ->
                                            val a = k * segAmp.size / 60
                                            val b = ((k + 1) * segAmp.size / 60).coerceAtLeast(a + 1)
                                            var m = 0f
                                            for (x in a until minOf(b, segAmp.size)) if (segAmp[x] > m) m = segAmp[x]
                                            m
                                        }
                                        }
                                    }
                                )
                            )
                            com.ringmaster.app.data.StatsStore.record(
                                context, com.ringmaster.app.data.StatsStore.KEY_RINGTONES
                            )
                        }
                    }.onFailure { snackbarMsg = context.getString(R.string.export_save_failed, it.message ?: "?") }
                    showExportSheet = true
                }
                .onFailure { snackbarMsg = context.getString(R.string.export_failed, it.message ?: "?") }
            busy = false
        }
    }

    fun applyRingtone(type: RingtoneSetter.Type) {
        val uri = savedUri ?: return // 已随导出保存，这里只设默认
        if (!RingtoneSetter.canWriteSettings(context)) {
            pendingRingtoneType = type
            showWriteSettings = true
            return
        }
        scope.launch {
            runCatching {
                val ok = RingtoneSetter.setDefault(context, uri, type)
                if (ok) com.ringmaster.app.data.StatsStore.record(
                    context, com.ringmaster.app.data.StatsStore.KEY_RINGS_SET
                )
                snackbarMsg = if (ok) context.getString(
                    R.string.set_done,
                    context.getString(when (type) {
                        RingtoneSetter.Type.RINGTONE -> R.string.set_phone
                        RingtoneSetter.Type.NOTIFICATION -> R.string.set_notification
                        RingtoneSetter.Type.ALARM -> R.string.set_alarm
                    })
                ) else context.getString(R.string.set_failed)
            }.onFailure { snackbarMsg = "设置失败：${it.message}" }
        }
    }

    if (showWriteSettings) {
        com.ringmaster.app.ui.common.WriteSettingsDialog(
            onDismiss = {
                showWriteSettings = false
                pendingRingtoneType = null // 用户选手动路径，不再自动重试
                snackbarMsg = context.getString(R.string.saved_manual_later)
            },
            onGrant = {
                showWriteSettings = false
                RingtoneSetter.requestWriteSettings(context)
                snackbarMsg = context.getString(R.string.granted_auto)
            }
        )
    }

    // 从系统授权页返回（ON_RESUME）：权限已授且有待设置操作 → 自动续上，无需重新导出
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val t = pendingRingtoneType
                if (t != null && RingtoneSetter.canWriteSettings(context) && cutFile != null) {
                    pendingRingtoneType = null
                    applyRingtone(t)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = context.getString(R.string.back))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        fileName.ifEmpty { context.getString(R.string.new_ringtone_title) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (initialStartMs >= 0) {
                        // 配方编辑模式：明确告知正在编辑的是原曲
                        Text(
                            "编辑原曲 · 蓝色选区 = 你的铃声",
                            style = MaterialTheme.typography.labelSmall,
                            color = RmColor.Accent
                        )
                    } else if (durationMs > 0) {
                        Text(
                            context.getString(R.string.full_selection, durationMs / 1000, "%.1f".format((selEnd - selStart) / 1000.0)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        bottomBar = {
            // 设计系统：底部固定三键操作条（避开手势导航条）
            Surface(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.65f), modifier = Modifier.navigationBarsPadding()) {
                Column {
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            busyText, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                val wf = waveform ?: return@FilledTonalIconButton
                                scope.launch {
                                    busy = true; busyText = context.getString(R.string.smart_searching)
                                    com.ringmaster.app.data.StatsStore.record(
                                        context, com.ringmaster.app.data.StatsStore.KEY_AI_USED
                                    )
                                    val cands = withContext(Dispatchers.Default) {
                                        smartCandidates
                                            ?: com.ringmaster.app.audio.ChorusDetector.detectCandidates(wf.amplitudes, durationMs)
                                                .also { smartCandidates = it }
                                    }
                                    val idx = smartIndex % cands.size
                                    val r = cands[idx]
                                    selStart = r.startMs; selEnd = r.endMs
                                    smartIndex = smartIndex + 1
                                    val range = "%.1f".format(r.startMs / 1000.0) + "–" + "%.1f".format(r.endMs / 1000.0)
                                    appendLog(
                                        context.getString(R.string.smart_candidate, idx + 1, cands.size, r.startMs / 1000 / 60, range) + " " +
                                            (if (r.watermarkAvoidedMs > 0) context.getString(R.string.watermark_avoided) else "")
                                    )
                                    snackbarMsg = context.getString(R.string.smart_candidate, idx + 1, cands.size,
                                        "%.1f".format(r.startMs / 1000.0), "%.1f".format(r.endMs / 1000.0))
                                    busy = false
                                }
                            },
                            enabled = waveform != null && !busy,
                            modifier = Modifier.size(52.dp)
                        ) { Icon(Icons.Rounded.AutoAwesome, contentDescription = context.getString(R.string.smart_cut)) }
                        FilledTonalIconButton(
                            onClick = { togglePreview() },
                            enabled = waveform != null,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = context.getString(if (isPlaying) R.string.stop else R.string.preview)
                            )
                        }
                        Button(
                            onClick = { cutAndExport() },
                            enabled = waveform != null && !busy,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(context.getString(R.string.export_ringtone))
                        }
                    }
                }
            }
        }
    ) { padding ->
        val wf = waveform
        if (wf == null) {
            // 未选文件：中央导入引导
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = RmColor.AccentDim, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text(context.getString(R.string.empty_editor_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    context.getString(R.string.empty_editor_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { pickFile.launch(arrayOf("audio/*", "video/*")) },
                    enabled = !busy,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(context.getString(R.string.pick_file)) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WaveformView(
                    modifier = Modifier
                        .glassCard(corner = 20)
                        .padding(horizontal = 2.dp),
                    amplitudes = wf.amplitudes,
                    durationMs = durationMs,
                    selectionStartMs = selStart,
                    selectionEndMs = selEnd,
                    viewportStartMs = vpStart,
                    viewportEndMs = vpEnd,
                    positionMs = positionMs,
                    onSelectionChange = { s, e ->
                        undoBuf = selStart to selEnd
                        selStart = s; selEnd = e
                        if (isPlaying) {
                            player.setMediaItem(clippedItem(inputUri!!, s, e))
                            player.prepare(); player.play()
                        }
                    },
                    onViewportChange = { s, e -> vpStart = s; vpEnd = e },
                    onTap = { xMs ->
                        val target = xMs.coerceIn(selStart, selEnd)
                        if (isPlaying) {
                            player.seekTo((target - selStart).coerceAtLeast(0))
                        } else {
                            startPreview(target, selEnd)
                        }
                    }
                )

                // 缩放控制行
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        context.getString(R.string.gesture_hint2) + " · " + context.getString(R.string.start_end_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalIconButton(
                            onClick = {
                                loopPreview = !loopPreview
                                player.repeatMode = if (loopPreview)
                                    androidx.media3.common.Player.REPEAT_MODE_ONE
                                else androidx.media3.common.Player.REPEAT_MODE_OFF
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Rounded.Repeat, context.getString(R.string.loop_preview),
                                tint = if (loopPreview) RmColor.Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        undoBuf?.let { buf ->
                            FilledTonalIconButton(
                                onClick = { selStart = buf.first; selEnd = buf.second; undoBuf = null },
                                modifier = Modifier.size(40.dp)
                            ) { Icon(Icons.Rounded.Undo, context.getString(R.string.undo_selection)) }
                        }
                        FilledTonalIconButton(onClick = {
                            val (s, e) = zoomViewport(vpStart, vpEnd, durationMs, (vpStart + vpEnd) / 2, 1f / 1.6f)
                            vpStart = s; vpEnd = e
                        }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.ZoomOut, "缩小波形") }
                        FilledTonalIconButton(onClick = {
                            val (s, e) = zoomViewport(vpStart, vpEnd, durationMs, (vpStart + vpEnd) / 2, 1.6f)
                            vpStart = s; vpEnd = e
                        }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.ZoomIn, "放大波形") }
                        FilledTonalIconButton(
                            onClick = { vpStart = 0L; vpEnd = durationMs },
                            modifier = Modifier.size(40.dp)
                        ) { Icon(Icons.Rounded.FitScreen, "全曲视图") }
                    }
                }

                // 起止区：左列=起点（设切点/微调/起点时间），右列=终点（微调/设切点/终点时间）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    if (isPlaying) {
                                        undoBuf = selStart to selEnd
                                        selStart = positionMs.coerceIn(0L, selEnd - 500)
                                    }
                                },
                                enabled = isPlaying,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Text(
                                    context.getString(R.string.start_label),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPlaying) RmColor.Accent else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalIconButton(onClick = { selStart = (selStart - 100).coerceAtLeast(0) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.Remove, context.getString(R.string.move_start_back))
                            }
                            FilledTonalIconButton(onClick = { selStart = (selStart + 100).coerceAtMost(selEnd - 500) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.Add, context.getString(R.string.move_start_forward))
                            }
                        }
                        Text(
                            "起点 ${"%.1f".format(selStart / 1000.0)}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = RmColor.Accent,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalIconButton(onClick = { selEnd = (selEnd - 100).coerceAtLeast(selStart + 500) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.Remove, context.getString(R.string.move_end_back))
                            }
                            FilledTonalIconButton(onClick = { selEnd = (selEnd + 100).coerceAtMost(durationMs) }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Rounded.Add, context.getString(R.string.move_end_forward))
                            }
                            FilledTonalButton(
                                onClick = {
                                    if (isPlaying) {
                                        undoBuf = selStart to selEnd
                                        selEnd = positionMs.coerceAtLeast(selStart + 500)
                                    }
                                },
                                enabled = isPlaying,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                modifier = Modifier.height(40.dp)
                            ) {
                                Text(
                                    context.getString(R.string.end_label),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isPlaying) RmColor.Accent else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "终点 ${"%.1f".format(selEnd / 1000.0)}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = RmColor.Accent,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                Text(
                    "全曲 ${durationMs / 1000} 秒 · 选段 ${"%.1f".format((selEnd - selStart) / 1000.0)} 秒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // 淡入淡出调节（导出时应用）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            context.getString(R.string.fade_in, if (fadeInMs <= 0) context.getString(R.string.off) else "%.1f".format(fadeInMs / 1000.0)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = (fadeInMs / 3000f).coerceIn(0f, 1f),
                            onValueChange = { fadeInMs = (kotlin.math.round(it * 30) * 100).toLong() },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            context.getString(R.string.fade_out, if (fadeOutMs <= 0) context.getString(R.string.off) else "%.1f".format(fadeOutMs / 1000.0)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = (fadeOutMs / 3000f).coerceIn(0f, 1f),
                            onValueChange = { fadeOutMs = (kotlin.math.round(it * 30) * 100).toLong() },
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // 重新选文件
                TextButton(onClick = {
                    fadeInMs = 500L; fadeOutMs = 500L
                    selStart = 0L; selEnd = durationMs
                    vpStart = 0L; vpEnd = durationMs
                }) { Text(context.getString(R.string.reset_defaults)) }
                TextButton(onClick = { pickFile.launch(arrayOf("audio/*", "video/*")) }, enabled = !busy) {
                    Text(context.getString(R.string.change_file))
                }

                if (log.isNotEmpty()) {
                    Text(
                        log,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // 导出成功后的去向选择
    if (showExportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExportSheet = false },
            containerColor = RmColor.BgElevated
        ) {
            Text(
                context.getString(R.string.sheet_saved_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            // 铃声名称：导出后即可自定义（保存立即生效）
            var nameInput by remember { mutableStateOf(ringtoneDisplayName) }
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                singleLine = true,
                label = { Text(context.getString(R.string.rename_title)) },
                trailingIcon = {
                    IconButton(onClick = {
                        if (nameInput.isNotBlank() && savedUri != null && nameInput != ringtoneDisplayName) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    com.ringmaster.app.data.RingtoneLibrary.renameByUri(
                                        context, savedUri!!, nameInput.trim()
                                    )
                                }
                                ringtoneDisplayName = nameInput.trim()
                                snackbarMsg = if (ok) context.getString(R.string.renamed)
                                             else context.getString(R.string.rename_failed)
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Check, contentDescription = context.getString(R.string.save),
                            tint = RmColor.Accent)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
            )
            Text(
                context.getString(R.string.sheet_saved_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(16.dp))
            listOf(
                Triple(Icons.Rounded.PhoneInTalk, "设为电话铃声", RingtoneSetter.Type.RINGTONE),
                Triple(Icons.Rounded.Notifications, "设为通知铃声", RingtoneSetter.Type.NOTIFICATION),
                Triple(Icons.Rounded.Alarm, "设为闹钟铃声", RingtoneSetter.Type.ALARM)
            ).forEach { (icon, label, type) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Icon(icon, null, tint = RmColor.Accent) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    trailingContent = {
                        TextButton(onClick = {
                            showExportSheet = false
                            applyRingtone(type)
                        }) { Text(context.getString(R.string.set)) }
                    }
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ListItem(
                headlineContent = { Text(context.getString(R.string.contact_android_only)) },
                leadingContent = { Icon(Icons.Rounded.PersonAdd, null, tint = RmColor.Accent) },
                modifier = Modifier.padding(horizontal = 8.dp),
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                trailingContent = {
                    TextButton(onClick = { showExportSheet = false; pickContact.launch(null) }) { Text(context.getString(R.string.choose)) }
                }
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ListItem(
                headlineContent = { Text(context.getString(R.string.share_card)) },
                leadingContent = { Icon(Icons.Rounded.Share, null, tint = RmColor.Accent) },
                modifier = Modifier.padding(horizontal = 8.dp),
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                trailingContent = {
                    TextButton(onClick = {
                        showExportSheet = false
                        scope.launch {
                            busy = true
                            val cardUri = makeShareCard()
                            busy = false
                            if (cardUri != null) {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(android.content.Intent.EXTRA_STREAM, cardUri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(android.content.Intent.createChooser(intent, "RingMaster")) }
                            } else snackbarMsg = context.getString(R.string.preview_failed, "no waveform")
                        }
                    }) { Text(context.getString(R.string.share)) }
                }
            )
                        ListItem(
                headlineContent = { Text(context.getString(R.string.share_friend)) },
                leadingContent = { Icon(Icons.Rounded.Share, null, tint = RmColor.Accent) },
                modifier = Modifier.padding(horizontal = 8.dp),
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                trailingContent = {
                    TextButton(onClick = { showExportSheet = false; shareCutFile() }) { Text(context.getString(R.string.share)) }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun fmtFade(ms: Long): String =
    if (ms <= 0) "关" else "${"%.1f".format(ms / 1000.0)}s"

private fun clippedItem(uri: Uri, startMs: Long, endMs: Long): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
        )
        .build()
