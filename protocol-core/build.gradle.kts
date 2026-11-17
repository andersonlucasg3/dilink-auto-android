// :protocol-core —— 平台无关的协议核心（纯 Kotlin/JVM）。
// 这里只放不依赖 Android API 的代码，供 :protocol（Android）与 :app-desktop（桌面）共用，
// 保证协议只有一份实现，避免两套代码漂移。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Connection 的公开 API 暴露了协程（CoroutineScope / Flow），故用 api 传递
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // issue #41：60s 天气 API 的 JSON 解析。Gson 是纯 JVM 库，放 protocol-core 一格，
    // 解析逻辑（WeatherParser）对 app-server 隐藏 Gson 类型（只返回 WeatherData），
    // 故用 implementation 而非 api —— app-server 不直接依赖 Gson。
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}