package com.ringmaster.app.ui.library

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ringmaster.app.R
import com.ringmaster.app.data.RingtoneLibrary
import com.ringmaster.app.ringtone.RingtoneSetter
import com.ringmaster.app.ui.common.WriteSettingsDialog
import com.ringmaster.app.ui.theme.RmColor
import com.ringmaster.app.ui.theme.glassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面：铃声库。
 * 底部按钮直接弹系统文件选择器，选中即进编辑器；点列表项重新编辑。
 */
@Composable
fun LibraryScreen(
    onOpenEditor: (android.net.Uri, Long, Long, Long, Long) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<RingtoneLibrary.Item>>(emptyList()) }
    var defaultUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    var showWriteSettings by remember { mutableStateOf(false) }
    var pendingSetType by remember { mutableStateOf<RingtoneSetter.Type?>(null) }
    var pendingSetItem by remember { mutableStateOf<RingtoneLibrary.Item?>(null) }
    var renameTarget by remember { mutableStateOf<RingtoneLibrary.Item?>(null) }
    var pendingContactUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var ringtoneForContact by remember { mutableStateOf<android.net.Uri?>(null) }
    val snackbar = remember { SnackbarHostState() }
    var showLocaleDialog by remember { mutableStateOf(false) }
    // 库内直接试听：共享一个播放器，playingId 标记当前播放条目
    var playingId by remember { mutableStateOf<Long?>(null) }
    var recipes by remember { mutableStateOf<Map<Long, com.ringmaster.app.data.ClipRecipe>>(emptyMap()) }
    val libPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    DisposableEffect(Unit) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) playingId = null
            }
        }
        libPlayer.addListener(listener)
        onDispose { libPlayer.release() }
    }
    val currentLocale = remember { com.ringmaster.app.data.LocalePrefs.load(context) }

    // 联系人专属铃声：系统联系人选择器 + 写单条记录权限（不读通讯录）
    val contactPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val contact = pendingContactUri ?: return@rememberLauncherForActivityResult
        val ruri = ringtoneForContact ?: return@rememberLauncherForActivityResult
        if (granted) {
            val ok = RingtoneSetter.setForContact(context, contact, ruri)
            if (ok) com.ringmaster.app.data.StatsStore.record(
                context, com.ringmaster.app.data.StatsStore.KEY_RINGS_SET
            )
            snackbarMsg = if (ok) context.getString(R.string.set_contact_done) else context.getString(R.string.set_failed)
        } else {
            snackbarMsg = context.getString(R.string.need_contact_permission)
        }
        pendingContactUri = null; ringtoneForContact = null
    }
    val pickContact = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null && ringtoneForContact != null) {
            pendingContactUri = contactUri
            contactPermission.launch(android.Manifest.permission.WRITE_CONTACTS)
        }
    }

    // 版本号（与测试APK文件名的时间戳可对照，确认装的包是否最新）
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    fun refresh() {
        scope.launch {
            items = withContext(Dispatchers.IO) { RingtoneLibrary.query(context) }
            defaultUri = RingtoneLibrary.currentDefault(context)
            recipes = items.mapNotNull { item ->
                val r = withContext(Dispatchers.IO) { com.ringmaster.app.data.RecipeStore.load(context, item.id) }
                r?.let { item.id to it }
            }.toMap()
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let { snackbar.showSnackbar(it); snackbarMsg = null }
    }

    fun trySetDefault(type: RingtoneSetter.Type, item: RingtoneLibrary.Item) {
        if (!RingtoneSetter.canWriteSettings(context)) {
            pendingSetType = type
            pendingSetItem = item
            showWriteSettings = true
            return
        }
        val ok = RingtoneSetter.setDefault(context, item.uri, type)
        if (ok) com.ringmaster.app.data.StatsStore.record(
            context, com.ringmaster.app.data.StatsStore.KEY_RINGS_SET
        )
        snackbarMsg = if (ok) context.getString(R.string.set_done, context.getString(typeNameResource(type))) else context.getString(R.string.set_failed)
        refresh()
    }

    // 从系统授权页返回（ON_RESUME）：权限已授且有挂起的设置操作 → 自动续上
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val t = pendingSetType
                val item = pendingSetItem
                if (t != null && item != null && RingtoneSetter.canWriteSettings(context)) {
                    pendingSetType = null; pendingSetItem = null
                    val ok = RingtoneSetter.setDefault(context, item.uri, t)
                    snackbarMsg = if (ok) context.getString(R.string.set_done, context.getString(typeNameResource(t))) else context.getString(R.string.set_failed)
                    refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun share(item: RingtoneLibrary.Item) {
        com.ringmaster.app.data.StatsStore.record(context, com.ringmaster.app.data.StatsStore.KEY_SHARES)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "分享铃声")) }
            .onFailure { snackbarMsg = context.getString(R.string.share_failed, it.message ?: "?") }
    }

    fun setContactRingtone(item: RingtoneLibrary.Item) {
        ringtoneForContact = item.uri
        pickContact.launch(null)
    }

    // 重新编辑：优先按剪辑配方回到原始歌曲的上次选区；无配方（源丢失/老数据）退化为编辑成品
    fun editRingtone(item: RingtoneLibrary.Item) {
        scope.launch {
            val recipe = withContext(Dispatchers.IO) {
                com.ringmaster.app.data.RecipeStore.load(context, item.id)
            }
            if (recipe != null) {
                onOpenEditor(
                    android.net.Uri.parse(recipe.sourceUri),
                    recipe.startMs, recipe.endMs,
                    recipe.fadeInMs, recipe.fadeOutMs
                )
            } else {
                onOpenEditor(item.uri, -1L, -1L, 500L, 500L)
            }
        }
    }

    if (showWriteSettings) {
        WriteSettingsDialog(
            onDismiss = {
                showWriteSettings = false
                pendingSetType = null; pendingSetItem = null
            },
            onGrant = {
                showWriteSettings = false
                RingtoneSetter.requestWriteSettings(context)
                snackbarMsg = context.getString(R.string.granted_auto)
            }
        )
    }

    if (showLocaleDialog) {
        AlertDialog(
            onDismissRequest = { showLocaleDialog = false },
            confirmButton = {},
            title = { Text(context.getString(R.string.locale_title)) },
            text = {
                Column {
                    listOf(
                        "" to context.getString(R.string.follow_system),
                        "zh" to "简体中文",
                        "en" to "English"
                    ).forEach { (code, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    com.ringmaster.app.data.LocalePrefs.save(context, code)
                                    showLocaleDialog = false
                                    (context as? android.app.Activity)?.recreate()
                                }
                                .padding(vertical = 14.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(label, Modifier.weight(1f))
                            if (currentLocale == code) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = RmColor.Accent)
                            }
                        }
                    }
                }
            }
        )
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(context.getString(R.string.rename_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            name.isNotBlank() && RingtoneLibrary.rename(context, target, name.trim())
                        }
                        snackbarMsg = if (ok) context.getString(R.string.renamed) else context.getString(R.string.rename_failed)
                        renameTarget = null
                        refresh()
                    }
                }) { Text(context.getString(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(context.getString(R.string.cancel)) } }
        )
    }

    // 底部按钮直接发起选择，选中即带文件进编辑器（不经过空编辑器中转）
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onOpenEditor(uri, -1L, -1L, 500L, 500L)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("RingMaster", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showLocaleDialog = true }) {
                    Text("EN/中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onOpenAbout) {
                    Text(
                        "v$versionName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        bottomBar = {
            Surface(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.65f), modifier = Modifier.navigationBarsPadding()) {
                Button(
                    onClick = { pickFile.launch(arrayOf("audio/*", "video/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(context.getString(R.string.new_ringtone))
                }
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = RmColor.AccentDim, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text(context.getString(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    context.getString(R.string.empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    RingtoneRow(
                        item = item,
                        isDefault = defaultUri == item.uri,
                        playingId = playingId,
                        miniWave = recipes[item.id]?.miniWave,
                        onTogglePlay = {
                            if (playingId == item.id) {
                                libPlayer.stop(); playingId = null
                            } else {
                                libPlayer.setMediaItem(androidx.media3.common.MediaItem.fromUri(item.uri))
                                libPlayer.prepare(); libPlayer.play()
                                playingId = item.id
                            }
                        },
                        onEdit = { editRingtone(item) },
                        onSetDefault = { trySetDefault(it, item) },
                        onRename = { renameTarget = item },
                        onShare = { share(item) },
                        onSetContact = { setContactRingtone(item) },
                        onDelete = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    com.ringmaster.app.data.RecipeStore.remove(context, item.id)
                                    RingtoneLibrary.delete(context, item)
                                }
                                snackbarMsg = if (ok) context.getString(R.string.deleted) else context.getString(R.string.delete_failed)
                                refresh()
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun typeNameResource(type: RingtoneSetter.Type): Int = when (type) {
    RingtoneSetter.Type.RINGTONE -> R.string.set_phone
    RingtoneSetter.Type.NOTIFICATION -> R.string.set_notification
    RingtoneSetter.Type.ALARM -> R.string.set_alarm
}

@Composable
private fun RingtoneRow(
    item: RingtoneLibrary.Item,
    isDefault: Boolean,
    playingId: Long?,
    miniWave: FloatArray?,
    onTogglePlay: () -> Unit,
    onEdit: () -> Unit,
    onSetDefault: (RingtoneSetter.Type) -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onSetContact: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .glassCard(corner = 18)
            .clickable(onClick = onTogglePlay)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = if (isDefault) RmColor.Accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 迷你波形缩略图（导出时随配方保存；旧条目显示占位线）
            androidx.compose.foundation.Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(vertical = 2.dp)
            ) {
                val data = miniWave
                if (data != null && data.isNotEmpty()) {
                    val bw = size.width / data.size
                    val color = if (playingId == item.id) RmColor.Accent
                                else RmColor.ContentSecondary.copy(alpha = 0.55f)
                    data.forEachIndexed { bi, a ->
                        val half = (a * 0.9f + 0.05f) * size.height / 2
                        drawLine(
                            color,
                            androidx.compose.ui.geometry.Offset(bi * bw + bw / 2, size.height / 2 - half),
                            androidx.compose.ui.geometry.Offset(bi * bw + bw / 2, size.height / 2 + half),
                            strokeWidth = bw * 0.62f,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    }
                } else {
                    drawLine(
                        RmColor.Divider,
                        androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                        androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                        strokeWidth = 2f
                    )
                }
            }
            Text(
                buildString {
                    append(context.getString(R.string.seconds, item.durationMs / 1000))
                    if (isDefault) append(" · ").append(context.getString(R.string.current_default))
                    append(" · ").append(context.getString(R.string.tap_to_play))
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isDefault) RmColor.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Rounded.PhoneInTalk, contentDescription = "铃声操作", tint = RmColor.Accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.set_phone)) },
                    leadingIcon = { Icon(Icons.Rounded.PhoneInTalk, null) },
                    onClick = { expanded = false; onSetDefault(RingtoneSetter.Type.RINGTONE) }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.set_notification)) },
                    leadingIcon = { Icon(Icons.Rounded.Notifications, null) },
                    onClick = { expanded = false; onSetDefault(RingtoneSetter.Type.NOTIFICATION) }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.set_alarm)) },
                    leadingIcon = { Icon(Icons.Rounded.Alarm, null) },
                    onClick = { expanded = false; onSetDefault(RingtoneSetter.Type.ALARM) }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.set_contact)) },
                    leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) },
                    onClick = { expanded = false; onSetContact() }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.rename)) },
                    leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                    onClick = { expanded = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.share_friend)) },
                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    onClick = { expanded = false; onShare() }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("重新剪辑") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { expanded = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text(context.getString(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { expanded = false; onDelete() }
                )
            }
        }
    }
}
