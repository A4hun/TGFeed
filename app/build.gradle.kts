import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

// Telegram API 凭证从 local.properties 读取（勿提交到 git）。
// 留空时 App 自动进入演示模式（Mock 数据源，可正常体验竖滑播放）。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val apiId = (localProps.getProperty("API_ID") ?: "0").toIntOrNull() ?: 0
val apiHash = localProps.getProperty("API_HASH") ?: ""

// 发布签名配置：keystore 文件在项目根（git 已忽略），密码走 local.properties（不进源码库）
val keystorePass = localProps.getProperty("KEYSTORE_PASSWORD") ?: ""
val keyAliasName = localProps.getProperty("KEY_ALIAS") ?: "tgfeed"
val releaseKeystore = rootProject.file("tgfeed-release.keystore")

android {
    namespace = "dev.tgfeed"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.tgfeed"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        buildConfigField("int", "API_ID", apiId.toString())
        buildConfigField("String", "API_HASH", "\"$apiHash\"")
    }

    // 发布签名：keystore 存在且给了密码才签（CI/开源场景没 keystore 也能构建未签名包）
    signingConfigs {
        if (releaseKeystore.exists() && keystorePass.isNotBlank()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystorePass
                keyAlias = keyAliasName
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // R382 双包共存（用户 11:4x 裁决「正式包与 debug 包区分开，不许互相覆盖」）：
            // release 走独立包名后缀，dev.tgfeed 恒为 debug 槽位（既有安装的登录态/星图
            // 数据零搬家）；debug 应用名加「（开发）」后缀，桌面/通知双图标可辨。
            resValue("string", "app_name", "TGFeed（开发）")
        }
        release {
            // R382：release 独立包名 dev.tgfeed.release——与 debug 并行安装互不覆盖
            // （签名本就异构，同 id 会导致 install -r 失败/卸载互删）；深链 http(s)/tg
            // 双装=选择器二选一（共存惯例语义）；广场/账号协议按账号面走不受包名影响。
            applicationIdSuffix = ".release"
            resValue("string", "app_name", "TGFeed")
            // R8 混淆 + 资源裁剪：TDLib 保活规则在 proguard-rules.pro（缺了 JNI 必崩）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists() && keystorePass.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // 单测里 android.util.Log 等桩方法返回默认值而非抛 "not mocked"
        // （排序链/采样层打日志不崩；真正的 org.json 由下面的 testImplementation 提供）
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
        // R125 包体裁剪：FFmpeg 软解只保留 arm64-v8a（真机主力）与 x86_64（模拟器），
        // x86/armeabi-v7a 老设备不打包——那里行为与接入前一致（走 R95 平台软解链），
        // 不会因接入变差；nextlib 每 ABI 约 6MB，裁两个老 ABI 省约一半增量
        jniLibs.excludes += setOf(
            "lib/x86/libavcodec.so",
            "lib/x86/libavutil.so",
            "lib/x86/libswresample.so",
            "lib/x86/libswscale.so",
            "lib/x86/libmedia3ext.so",
            "lib/armeabi-v7a/libavcodec.so",
            "lib/armeabi-v7a/libavutil.so",
            "lib/armeabi-v7a/libswresample.so",
            "lib/armeabi-v7a/libswscale.so",
            "lib/armeabi-v7a/libmedia3ext.so",
            // R292：ML Kit OCR native 管线同款 ABI 裁剪（与 FFmpeg 同带——x86/armeabi-v7a
            // 不打包，arm64-v8a 真机+x86_64 模拟器保留；四 ABI 全带 = 41MB，裁后 22.7MB，
            // 落回 content-understanding-eval.md 的 +10~25MB 估算带内）
            "lib/x86/libmlkit_google_ocr_pipeline.so",
            "lib/armeabi-v7a/libmlkit_google_ocr_pipeline.so",
        )
    }
}

dependencies {
    // TDLib 预编译 AAR（由 scripts/fetch-tdlib.ps1 下载到 app/libs）
    implementation(files("libs/core-release.aar"))
    implementation(files("libs/ktx-release.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    // 系统悬浮窗的 ComposeView 需要手工挂 ViewTree 三件套（OverlayWindowHolder）
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // 后台音频模式：MediaSessionService 基座（AudioBackgroundService 用）
    implementation("androidx.media3:media3-session:1.4.1")
    // R125 FFmpeg 软解兜底：官方 androidx.media3:media3-decoder-ffmpeg 只发 Java 壳，
    // native 库需 NDK+FFmpeg 源码自编译（Windows 构建链过重），故用社区预编译件。
    // 选型核验记录：
    //  - org.jellyfin.media3:media3-ffmpeg-decoder：AAR 里的视频渲染器是空壳
    //    （supportsFormat 恒 0/createDecoder 恒 null，字节码实证），只有音频能用，排除；
    //  - nextlib-media3ext（NextPlayer 项目维护）：FfmpegVideoRenderer/FfmpegAudioRenderer
    //    均为真实现（FFmpeg 6.1 拆分 so：avcodec/avutil/swresample/swscale/media3ext，
    //    解码器覆盖 hevc/h264/vp9/av1/mpeg2 等 + 常见音频）；编译基线是 media3 1.7.1，
    //    其对 media3 的全部符号引用已逐一核验在 1.4.1 存在（构造器含继承链）。
    // exclude 说明：POM 传递依赖 media3 1.7.1（runtime 也会顶升版本）与 kotlin-stdlib
    // 2.1.21（metadata 比本项目 Kotlin 2.0.20 新，编译会告警/失败），全部掐断，
    // 强制留在项目现有的 media3 1.4.1 / Kotlin 2.0.20 基线上
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0") {
        exclude(group = "androidx.media3")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // R292 视频封面 OCR 试点（T1a）：ML Kit Text Recognition v2 **bundled** 中文模型
    // （中文模型自带拉丁文识别，一举两得）。选型唯一出处 content-understanding-eval.md §2.1：
    //  - bundled = 模型打包进 APK，全离线零外发（隐私红线）；
    //  - 无 GMS 运行时依赖 = 华为鸿蒙（HarmonyOS 无 GMS 档）适用（docs/DEVICE-COMPAT.md 红线）；
    //  - thin 变体（Play Services 动态下发模型）在无 GMS 设备直接出局，已否决。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ---- R121 测试地基：纯 JVM 单测（src/test） ----
    testImplementation("junit:junit:4.13.2")
    // mockk：给 LocalStore 造 filesDir 指向临时目录的假 Context、
    // 给游标编解码/轮换采样扩展函数造不触发 native 加载的假 TdLibGateway 接收体
    testImplementation("io.mockk:mockk:1.13.13")
    // android.jar 自带的 org.json 是打桩版（所有方法抛异常）；引入真实现覆盖之
    testImplementation("org.json:json:20240303")
}
