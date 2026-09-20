package com.ringmaster.app.ui.diag

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ringmaster.app.R
import com.ringmaster.app.audio.FormatProbe
import kotlinx.coroutines.launch

/**
 * 格式自测页：选任意文件 → 四段探针报告（容器/解码/波形/剪切）。
 * 用于系统性排查"哪些格式读不了"以及断在哪一环。
 */
@Composable
fun FormatDiagScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val diagnosingText = stringResource(R.string.diagnosing)
    val passedText = stringResource(R.string.all_passed)
    val failedText = stringResource(R.string.has_failures)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "?"
            busy = true
            report = diagnosingText
            scope.launch {
                val r = FormatProbe.probe(context, uri)
                report = r.text + if (r.allPassed) "\n\n$passedText" else "\n\n$failedText"
                busy = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleMedium)
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.diag_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { pickFile.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(stringResource(if (fileName.isEmpty()) R.string.pick_any_file else R.string.pick_another, fileName)) }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.diagnosing), style = MaterialTheme.typography.labelMedium) }
            Text(
                report,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            )
        }
    }
}
