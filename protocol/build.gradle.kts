plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilinkauto.protocol"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // Same as app-client / app-server / vd-server: the ADB crypto encoder
        // (AdbCrypto.encodePublicKey) and TcpAdbConnection's logging reach
        // android.util.Base64 / android.util.Log on the mockable jar. With this
        // flag those calls return defaults instead of throwing
        // "Method ... not mocked", so the pure framing/crypto helpers in
        // com.dilinkauto.protocol.adb stay unit-testable on the JVM.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 平台无关的协议核心（api：上游模块可直接使用 FrameCodec / Messages / Connection 等）
    api(project(":protocol-core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ForegroundNotifier 使用 NotificationCompat。这里只声明编译期 API：
    // core-ktx 是 compile-time-only，两个使用方（app-client / app-server）
    // 本来就已 implementation 引入它，运行时由它们提供。
    // 不用 implementation 是因为本模块定位是"平台无关协议层"，不该强制
    // 传递 androidx 依赖给其它消费者。
    //
    // 版本选择是被离线缓存逼出来的，不是版本偏好的表达：
    //  core-ktx 1.12.0 -> lifecycle-runtime 2.3.1（缓存里没有）
    //  core      1.13.1 -> lifecycle-runtime 2.6.2 -> lifecycle-common 2.6.2（也没有）
    // 缓存里 lifecycle-common 只有 2.6.1 / 2.7.0 / 2.8.4，而 lifecycle 与本模块
    //  无关（只用 NotificationCompat），故直接排除以命中缓存。
    //
    // 更根本的问题是：本模块定位"平台无关协议层"，却因为 ForegroundNotifier
    // 被迫依赖 androidx。待办 —— 见 docs/audit-srp-dry.md DRY-6 修复记录，
    // 建议把该类移到 app-client / app-server 各自的同构小类，或新建一个
    // 独立的 app-common 模块。
    compileOnly("androidx.core:core-ktx:1.12.0") {
        exclude(group = "androidx.lifecycle")
    }

    // ADB framing (AdbProtocol) and the pure crypto helpers in AdbCrypto are
    // plain JVM — they get the project's usual JUnit4 unit tests. No coroutines
    // needed: the proposed tests are blocking/plain @Test methods.
    testImplementation("junit:junit:4.13.2")
}
