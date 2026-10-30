plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
