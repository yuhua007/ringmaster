import java.util.Properties

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ringmaster.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ringmaster.app"
        minSdk = 26
        targetSdk = 35
        // 测试版自动版本：versionCode 用分钟级时间戳（单调递增），
        // versionName 含构建时刻（MMdd.HHmm），与测试APK文件名可对照
        versionCode = (System.currentTimeMillis() / 60_000L).toInt()
        // 1.0.0 开源版（测试期仍带分钟级时间戳便于区分构建）
        versionName = "1.0.0-oss." + DateTimeFormatter.ofPattern("MMdd.HHmm").format(LocalDateTime.now())
    }

    // 签名信息存 local.properties（不入库；gradleProperty 只认 gradle.properties，故手动读）
    val rmLocalProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val rmStoreFile = rmLocalProps.getProperty("rm.storeFile")

    signingConfigs {
        create("release") {
            if (rmStoreFile != null) {
                storeFile = file(rmStoreFile)
                storePassword = rmLocalProps.getProperty("rm.storePassword")
                keyAlias = rmLocalProps.getProperty("rm.keyAlias")
                keyPassword = rmLocalProps.getProperty("rm.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 工程债：R8 混淆 + 资源裁剪（icons-extended 收益明显），正式签名
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (rmStoreFile != null) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
