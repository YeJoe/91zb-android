import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 真号凭证的注入通道（**不要把凭证写进源码**）。
 *
 * 这个仓库是公开的（`github.com/YeJoe/91zb-android`）。真号的 JWE 一旦提交进去，
 * 等于把账号的关注/发言能力挂到公网上 —— GitHub 不会识别这种第三方串，不会自动撤销，
 * 只能等被人扫到。所以凭证走两条带外的通道，都只在构建期存在：
 *
 *  1. **本地**：`local.properties`（已在 `.gitignore` 里）——
 *     ```
 *     tm.appToken=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
 *     tm.jwe=eyJhbGciOiJkaXIi...
 *     tm.userId=1422303235
 *     tm.channelId=tui1
 *     ```
 *  2. **CI**：仓库 Secrets —— `TM_APP_TOKEN` / `TM_JWE` / `TM_USER_ID` / `TM_CHANNEL_ID`
 *
 * 两边都没有时全部注入空串，App 退回"无凭证"状态：看直播照常，关注/弹幕需要用户自己在
 * 设置页粘一份。也就是说**开源出去的代码是可编译、可运行的，只是不带别人的账号**。
 */
val credProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cred(localKey: String, envKey: String): String =
    (System.getenv(envKey) ?: credProps.getProperty(localKey) ?: "").trim()

val tmAppToken: String = cred("tm.appToken", "TM_APP_TOKEN")
val tmJwe: String = cred("tm.jwe", "TM_JWE")
val tmUserId: String = cred("tm.userId", "TM_USER_ID")
val tmChannelId: String = cred("tm.channelId", "TM_CHANNEL_ID")

/** 生成 Kotlin 字符串字面量（转义反斜杠与引号；凭证里不会有换行，换行也一并吃掉） */
fun kstr(s: String): String {
    val esc = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
    return "\"$esc\""
}

/**
 * 版本号来源：
 *   本地 / CI 都可用环境变量覆盖，CI 里注入 GITHUB_RUN_NUMBER 当 build 号，
 *   设置页的"关于"就能显示出是第几次构建。
 */
val appVersionName: String = System.getenv("APP_VERSION_NAME") ?: "1.0.0"
val appVersionCode: Int = (System.getenv("APP_VERSION_CODE") ?: "1").toIntOrNull() ?: 1

// CI 里由 base64 secret 还原出的 keystore 路径；没有就留空 → 走 debug 签名
val keystorePath: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.tm.live"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tm.live"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // 只打包 arm64-v8a。
        //
        // 这不是为了省体积 —— APK 里放了 libkiwi.so（云层隧道 SDK），而它只有
        // arm64 版。APK 一旦同时带 x86_64 的库，PackageManager 会按设备首选 ABI
        // 把 primaryCpuAbi 选成 x86_64，进程就是 x86_64，`libkiwi.so` 根本不会
        // 被释放到 nativeLibraryDir，隧道永远起不来。
        //
        // 只留 arm64 之后，带 ARM 转译层的 x86 设备（模拟器 ro.dalvik.vm.native.bridge）
        // 会把进程拉成 arm64 —— 源 App 本来就是这么跑的，实测可用。
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }

        // 凭证：构建期注入，运行时由 Prefs 当默认值读（见 Prefs.Defaults）。
        // 注意这里**只进 BuildConfig**，不落 SharedPreferences —— 用户换了号以后
        // 设置页那份（Secure 加密存）优先级更高，清空即回到这里的值。
        buildConfigField("String", "TM_APP_TOKEN", kstr(tmAppToken))
        buildConfigField("String", "TM_JWE", kstr(tmJwe))
        buildConfigField("String", "TM_USER_ID", kstr(tmUserId))
        buildConfigField("String", "TM_CHANNEL_ID", kstr(tmChannelId))
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // **不要**加 applicationIdSuffix。
            //
            // 原来这里是 `applicationIdSuffix = ".debug"`，包名会变成
            // `com.tm.live.debug` —— 与已装的 `com.tm.live` 是两个不同应用，
            // `adb install -r` 必然报 INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能先卸载。
            // 目标是「新包直接覆盖旧包」，所以 debug 与 release 必须同包名同签名。
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 不做按 abi 的 splits：上面的 abiFilters 已经把包限成 arm64-v8a 单 ABI，
    // 再 split 只会多出一个 "-arm64-v8a" 后缀的重复包。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        // 打开 BuildConfig：上面几个凭证字段要靠它注入
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST"
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // 封面图：Coil 只做"URL → Bitmap + LruCache + 生命周期取消"，不引 OkHttp，
    // 自带 HttpURLConnection 引擎。列表里几百个头像全靠它去重与复用。
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 播放：ExoPlayer + RTMP 扩展 + HLS（网关降级用）
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-rtmp:1.5.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
