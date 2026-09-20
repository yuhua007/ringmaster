package com.ringmaster.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ringmaster.app.ui.editor.EditorScreen
import com.ringmaster.app.ui.library.LibraryScreen
import com.ringmaster.app.ui.theme.RmTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 来自系统分享面板的音频/视频 uri（SEND intent） */
    private var shareUri: Uri? = null

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
            if (uri != null) shareUri = uri
        }
    }


    // App 内语言切换：启动时按偏好包裹 Locale Context（""=跟随系统，无需包裹）
    override fun attachBaseContext(newBase: android.content.Context) {
        val locale = com.ringmaster.app.data.LocalePrefs.load(newBase)
        val wrapped = if (locale.isEmpty()) newBase else {
            newBase.createConfigurationContext(
                android.content.res.Configuration(newBase.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(locale))
                }
            )
        }
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        // 本地行为计数：活跃天数（成就/留存数据基础，Phase 4 消费）
        (application as com.ringmaster.app.RingMasterApp).appScope.launch {
            com.ringmaster.app.data.StatsStore.recordActive(applicationContext)
            // 工程债：清理 24h 前的导出/试听临时文件，防缓存堆积
            com.ringmaster.app.data.CacheCleaner.clean(applicationContext)
        }
        // Android 15 (targetSdk 35) 强制 edge-to-edge；显式声明深色系统栏样式（浅色图标）
        // 浅色玻璃主题：状态栏/导航栏用深色图标
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            RmTheme {
                Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize().background(com.ringmaster.app.ui.theme.RmColor.EnvGradient)
                    ) {
                    val nav = rememberNavController()
                    androidx.compose.runtime.LaunchedEffect(shareUri) {
                        shareUri?.let {
                            nav.navigate("editor?uri=" + Uri.encode(it.toString()) + "&start=-1&end=-1&fadeIn=500&fadeOut=500")
                            shareUri = null
                        }
                    }
                    NavHost(navController = nav, startDestination = "library") {
                        composable("library") {
                            LibraryScreen(
                                onOpenEditor = { uri, start, end, fi, fo ->
                                    nav.navigate("editor?uri=${Uri.encode(uri.toString())}&start=$start&end=$end&fadeIn=$fi&fadeOut=$fo")
                                },
                                onOpenAbout = { nav.navigate("about") }
                            )
                        }
                        composable(
                            route = "editor?uri={uri}&start={start}&end={end}&fadeIn={fadeIn}&fadeOut={fadeOut}",
                            arguments = listOf(
                                navArgument("uri") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                                navArgument("start") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("end") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("fadeIn") { type = NavType.LongType; defaultValue = 500L },
                                navArgument("fadeOut") { type = NavType.LongType; defaultValue = 500L }
                            )
                        ) { entry ->
                            val uri = entry.arguments?.getString("uri")?.toUri()
                            EditorScreen(
                                initialUri = uri,
                                initialStartMs = entry.arguments?.getLong("start") ?: -1L,
                                initialEndMs = entry.arguments?.getLong("end") ?: -1L,
                                initialFadeInMs = entry.arguments?.getLong("fadeIn") ?: 500L,
                                initialFadeOutMs = entry.arguments?.getLong("fadeOut") ?: 500L,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable("about") {
                            com.ringmaster.app.ui.about.AboutScreen(
                                onBack = { nav.popBackStack() },
                                onOpenDiag = { nav.navigate("diag") }
                            )
                        }
                        composable("diag") {
                            com.ringmaster.app.ui.diag.FormatDiagScreen(onBack = { nav.popBackStack() })
                        }
                    }
                    }
                }
            }
        }
    }
}
