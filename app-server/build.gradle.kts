plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilinkauto.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dilinkauto.server"
        minSdk = 26
        targetSdk = 34
        // 版本号由根 build.gradle.kts 单点算出（issue #224），与 app-client 同源。
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String

        // 高德 Web 服务 key（天气城市反查用，issue #157）。来源优先级：
        // `-Pamap.key=` > `local.properties` 的 `amap.key` > 环境变量 `AMAP_KEY`。
        // 缺省空串 → 城市解析只走 IP 兜底；真实 key 不入库（与 keystore 同规矩）。
        val localPropsFile = rootProject.file("local.properties")
        val amapKeyFromLocal = if (localPropsFile.exists()) {
            localPropsFile.readLines()
                .firstOrNull { it.trimStart().startsWith("amap.key=") }
                ?.substringAfter("amap.key=")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else null
        val amapKey = (project.findProperty("amap.key") as String?)
            ?: amapKeyFromLocal
            ?: System.getenv("AMAP_KEY")
            ?: ""
        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    // 与 app-client 同源的发布签名：正式 keystore 只在口令环境变量齐全时启用，
    // 缺失且没显式放行 debug 签名时，release 构建在配置阶段直接失败（audit B-12），
    // 不再产出装不上的 -unsigned.apk（车机端此前完全没有 release signingConfig）。
    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val wantsRelease = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true)
    }
    // 显式放行「release 变体 + debug 签名」，语义同 app-client：
    // 只在本地拿不到正式 keystore 口令时才用，必须显式置 1。
    val allowDebugSignedRelease = System.getenv("ALLOW_DEBUG_SIGNED_RELEASE") == "1"
    if (releaseKeystorePassword != null && releaseKeyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("RELEASE_KEYSTORE_FILE") ?: "../app-client/dilink-auto-release.keystore")
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "dilinkauto"
                keyPassword = releaseKeyPassword
            }
        }
    } else if (wantsRelease && !allowDebugSignedRelease) {
        throw GradleException(
            "Release build requested but RELEASE_KEYSTORE_PASSWORD / " +
                "RELEASE_KEY_PASSWORD are not set. Set both, or set " +
                "ALLOW_DEBUG_SIGNED_RELEASE=1 to sign the release variant with the debug key."
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 正式 keystore 可用时，release 用正式签名；否则按显式放行走 debug key，
    // 避免 release 变体落成 unsigned。
    signingConfigs.findByName("release")?.let { releaseConfig ->
        buildTypes.getByName("release").signingConfig = releaseConfig
    }
    if (signingConfigs.findByName("release") == null && allowDebugSignedRelease && wantsRelease) {
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("debug")
        logger.lifecycle("[signing] ALLOW_DEBUG_SIGNED_RELEASE=1 — release 变体使用 debug key 签名")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // ADB client provided by protocol module (TcpAdbConnection)
}
