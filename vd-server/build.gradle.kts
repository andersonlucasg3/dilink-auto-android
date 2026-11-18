plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilinkauto.vdserver"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        // issue #8: 让 vd-server.dex 带编译期版本常量，app_process 启动时
        // 第一行就能打出可读版本号。vd-server 本是 library 且无 BuildConfig，
        // 这里打开 buildConfig 并注入两个字段；versionName 单点来自根工程。
        buildConfigField("String", "VERSION_NAME",
            "\"${rootProject.extra["appVersionName"]}\"")
        buildConfigField("String", "BUILD_REVISION",
            "\"${rootProject.extra["buildRevision"]}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Same as app-client / app-server: unit tests that load a class whose body
        // references framework types (e.g. PipelineFpsCoercionTest calling
        // PipelineServer.coerceFps) must not blow up with "Method ... not mocked"
        // on the mockable android.jar. app-client/app-server already set this.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // vd-server had NO test source set at all, which left the encode hot path
    // (frame header layout, writeAll deadline, adaptive bitrate) completely
    // unguarded. Added so DRY-3 / SRP-4 can be done with a regression net.
    testImplementation("junit:junit:4.13.2")
}
