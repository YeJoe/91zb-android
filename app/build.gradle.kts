plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jjlive.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jjlive.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 10
        versionName = "1.9.1-half"
    }

    // 固定签名：CI 每次重新生成 debug keystore 会导致签名变化，
    // 装新包必须先卸载（丢令牌）。改用仓库内固定 keystore，后续可以直接覆盖安装。
    signingConfigs {
        create("stable") {
            storeFile = file("jjlive.jks")
            storePassword = "jjlive123"
            keyAlias = "jjlive"
            keyPassword = "jjlive123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // WebView 资源加载器：把 assets/web 下的 wasm 播放器以 https 源托管，
    // 这样 jessibuca 的 Worker / fetch(decoder.wasm) 才能正常同源加载
    implementation("androidx.webkit:webkit:1.11.0")
}
