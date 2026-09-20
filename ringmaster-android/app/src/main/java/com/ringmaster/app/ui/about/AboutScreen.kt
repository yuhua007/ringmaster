package com.ringmaster.app.ui.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.ringmaster.app.R
import com.ringmaster.app.ui.theme.RmColor

/**
 * 关于/隐私页：商店审核要求 App 内可访问隐私声明（GDPR），
 * 同时把「零数据收集」作为显性卖点呈现。
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenDiag: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
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
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = context.getString(R.string.back))
                }
                Text(context.getString(R.string.about_title), style = MaterialTheme.typography.titleMedium)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = RmColor.Accent,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("RingMaster", style = MaterialTheme.typography.titleLarge)
            Text(
                "v$versionName",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = RmColor.Divider)
            Spacer(Modifier.height(20.dp))
            Text(
                context.getString(R.string.privacy_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                context.getString(R.string.about_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(24.dp))
            // 打赏（Ko-fi 链接，注册后替换为真实链接）
            val kofiUrl = "https://ko-fi.com/ringmaster"
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(kofiUrl))
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RmColor.Accent,
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Icon(Icons.Rounded.Favorite, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(context.getString(R.string.support_dev))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                context.getString(R.string.about_contact),
                style = MaterialTheme.typography.bodyMedium,
                color = RmColor.Accent,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(40.dp))
            // 开发诊断工具入口（低调收在关于页底部）
            TextButton(onClick = onOpenDiag) {
                Text(
                    context.getString(R.string.about_diag_link),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
