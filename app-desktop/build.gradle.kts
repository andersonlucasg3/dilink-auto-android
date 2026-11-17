// :app-desktop —— Windows 接收端（桌面镜像客户端）。
// 复用 :protocol-core 的协议实现，手机侧零改动。
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
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

/**
 * 运行与打包（Compose 插件自带 `run` / `createDistributable` 等任务，
 * 因此这里不引入 `application` 插件——两者都注册 `run`，会直接冲突）。
 *
 * 打包：`jpackage` 产出免安装的 app-image（自带精简 JRE）。
 * 复制/解压 `build/compose/binaries/main/app/DiLinkAuto` 整个目录到一台
 * 没装过 JDK 的 Windows 上，双击 `DiLinkAuto.exe` 即可运行。
 * 执行命令：`.\gradlew.bat :app-desktop:createDistributable`
 */
compose.desktop {
    application {
        mainClass = "com.dilinkauto.desktop.DesktopMainKt"
        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "DiLinkAuto"
            packageVersion = "1.0.0"
            description = "DiLink Auto 桌面接收端"
            vendor = "DiLink Auto"
            // AppImage 的窗口应用不需要控制台；日志走 %APPDATA%\DiLinkAuto\desktop.log
            windows {
                console = false
                // 固定 UUID：后续若改做 msi/exe 安装包，升级时才会原地覆盖而不是并存两份
                upgradeUuid = "8F2E6C64-9B3A-4D2E-9C57-2A1B4E7F0D31"
            }
        }
    }
}

dependencies {
    implementation(project(":protocol-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Compose Desktop：窗口外壳（视频画面本身用 Swing Canvas，走 SwingPanel 嵌入）。
    implementation(compose.desktop.currentOs)

    // JavaCV / FFmpeg 软解。只引 Windows x86_64 原生库，避免 javacv-platform 拉全平台。
    // 许可提示：JavaCV 默认捆绑的 FFmpeg 是 GPL 构建（本项目 MIT），当前仅自用不分发；
    // 日后公开分发二进制须换成 LGPL 构建，或改走 Media Foundation（见 .plan 3.2）。
    implementation("org.bytedeco:javacv:1.5.10")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")
    implementation("org.bytedeco:javacpp:1.5.10:windows-x86_64")

    // JNA：只为调用 kernel32.SetThreadExecutionState（会话期间阻止本机息屏）。
    // 不引 jna-platform —— 需要的那一个函数直接映射即可，省一个多兆的依赖。
    implementation("net.java.dev.jna:jna:5.14.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}