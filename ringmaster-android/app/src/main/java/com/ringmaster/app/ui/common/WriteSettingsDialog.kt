package com.ringmaster.app.ui.common

import com.ringmaster.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * WRITE_SETTINGS 未授权时的引导（含降级路径）：
 * 首选引导去系统页授权（真一键）；拒绝授权的用户降级为手动设置指引。
 */
@Composable
fun WriteSettingsDialog(
    onDismiss: () -> Unit,
    onGrant: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ws_dialog_title)) },
        text = {
            Text(
                "RingMaster 只用它把铃声写入系统铃声库并设为默认，不做其他任何事。\n\n" +
                    "也可以不授权：铃声已保存在系统铃声目录，你可以手动设置——\n" +
                    "系统设置 → 声音与触感 → 电话铃声 → 选择你的铃声",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { TextButton(onClick = onGrant) { Text(stringResource(R.string.ws_grant)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ws_manual)) } }
    )
}
