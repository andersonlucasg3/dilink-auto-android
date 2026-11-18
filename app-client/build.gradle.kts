import java.net.URLClassLoader
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 变体判据：本次构建是否请求了 release 变体。签名配置与内嵌车机包都用它，
// 保证「release 客户端装 release 车机包」这一对签名关系不会分叉（issue #37）。
val wantsReleaseBuild = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

android {
    namespace = "com.dilinkauto.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dilinkauto.client"
        minSdk = 29
        targetSdk = 34
        // 版本号由根 build.gradle.kts 单点算出：
        // versionName 是语义化版本，versionCode 手工维护，两个模块保证同源。
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
        // issue #8: 日志可读版本号。BUILD_REVISION 由根 build.gradle.kts 单点算出，
        // app-client 与 vd-server 同源；VERSION_NAME/VERSION_CODE 已由 AGP 自动生成。
        buildConfigField("String", "BUILD_REVISION",
            "\"${rootProject.extra["buildRevision"]}\"")
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val wantsRelease = gradle.startParameter.taskNames.any {
        it.contains("release", ignoreCase = true)
    }
    // 显式放行「release 变体 + debug 签名」：只在本地想拿混淆后的安装包、又拿不到
    // 正式 keystore 口令时使用。必须显式设 ALLOW_DEBUG_SIGNED_RELEASE=1 才生效，
    // 默认行为仍是缺口令即失败（audit B-12），不会静默产出 unsigned 包。
    val allowDebugSignedRelease = System.getenv("ALLOW_DEBUG_SIGNED_RELEASE") == "1"
    if (releaseKeystorePassword != null && releaseKeyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("RELEASE_KEYSTORE_FILE") ?: "dilink-auto-release.keystore")
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "dilinkauto"
                keyPassword = releaseKeyPassword
            }
        }
    } else if (wantsRelease && !allowDebugSignedRelease) {
        // Audit B-12: without this the release build silently produced an
        // UNSIGNED APK (uninstallable over a properly signed build, and
        // publishable to a release channel by accident). A release build with
        // no signing configuration is always a mistake — fail at configuration
        // time with the exact variables that are missing.
        throw GradleException(
            "Release build requested but RELEASE_KEYSTORE_PASSWORD / " +
                "RELEASE_KEY_PASSWORD are not set. Set both (the keystore " +
                "itself is never committed — see docs/release.md)."
        )
    } else {
        logger.lifecycle(
            "[signing] release signing env vars not set — debug builds use the default " +
                "debug key; release builds would fail fast (audit B-12)."
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

    // Apply release signing to both debug and release when env vars are available.
    // This ensures users can install any build over another without signature conflicts.
    signingConfigs.findByName("release")?.let { releaseConfig ->
        buildTypes.getByName("release").signingConfig = releaseConfig
        buildTypes.getByName("debug").signingConfig = releaseConfig
    }
    // 没有正式 keystore、但显式放行了 debug 签名：release 变体改用 debug key 签，
    // 否则 AGP 会产出装不上的 -unsigned.apk。
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
    implementation("dev.mobile:dadb:1.2.10")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

// Build the VD server JAR and copy to assets before the client APK is assembled.
// vd-server is a proper Gradle module — compilation handled by AGP/Kotlin.
// This task just runs d8 + jar packaging.
tasks.register("buildVdServer") {
    dependsOn(":vd-server:bundleLibRuntimeToJarDebug")
    // 下面 doLast 直接读 :protocol 的 debug classes.jar；只依赖 :vd-server 的 debug
    // 任务时，release-only 构建里这个 jar 可能不在任务图里，D8 会以
    // NoSuchFileException 失败（本地 release 构建复现，issue #37 验证时发现）。
    dependsOn(":protocol:bundleLibRuntimeToJarDebug")
    // :protocol-core 是纯 JVM 模块（PlatformLog 等协议基元都在这里）。Phase 0 把它
    // 从 :protocol 拆出后，下面那份 inputs 曾漏掉它 —— 编译期正常、d8 也正常，直到
    // app_process 启动 vd-server 时才以 NoClassDefFoundError: PlatformLog 崩掉，
    // 且因为崩在最早一行日志之前，vd-server.log 完全空白，极难定位。
    dependsOn(":protocol-core:jar")

    val vdBuildDir = file("${rootDir}/vd-server/build/tmp/vds-d8")
    val vdClassesJar = file("${rootDir}/vd-server/build/intermediates/runtime_library_classes_jar/debug/classes.jar")
    val protocolJar = file("${rootDir}/protocol/build/intermediates/runtime_library_classes_jar/debug/classes.jar")
    val protocolCoreJar = file("${rootDir}/protocol-core/build/libs/protocol-core.jar")
    val d8Jar = file("${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/lib/d8.jar")
    val assetsDir = file("src/main/assets")

    // Kotlin stdlib + coroutines (needed at runtime by vd-server via app_process)
    val kotlinLibs = project.configurations.detachedConfiguration(
        project.dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:1.9.22"),
        project.dependencies.create("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.7.3")
    ).resolve()

    outputs.upToDateWhen { false }

    doLast {
        vdBuildDir.deleteRecursively()
        vdBuildDir.mkdirs()

        // Clean stale artifacts from assets
        file("${assetsDir}/vd-server.dex").delete()

        // DEX vd-server + protocol + protocol-core + kotlin stdlib (needed for app_process runtime)
        val inputs = listOf(vdClassesJar.absolutePath, protocolJar.absolutePath, protocolCoreJar.absolutePath) +
            kotlinLibs.map { it.absolutePath }
        val d8Args = (listOf("--output", vdBuildDir.absolutePath) + inputs).toTypedArray()

        val d8ClassLoader = URLClassLoader(arrayOf(d8Jar.toURI().toURL()), javaClass.classLoader)
        try {
            val d8Class = d8ClassLoader.loadClass("com.android.tools.r8.D8")
            val mainMethod = d8Class.getMethod("main", Array<String>::class.java)
            mainMethod.invoke(null, d8Args)
        } finally {
            d8ClassLoader.close()
        }

        // Rename to vd-server.dex (delete old first — renameTo fails silently on Windows if dest exists)
        val classesDex = file("${vdBuildDir}/classes.dex")
        val serverDex = file("${vdBuildDir}/vd-server.dex")
        serverDex.delete()
        if (!classesDex.renameTo(serverDex)) {
            classesDex.copyTo(serverDex, overwrite = true)
            classesDex.delete()
        }

        // Create JAR (ZIP containing vd-server.dex as classes.dex)
        val jarFile = file("${vdBuildDir}/vd-server.jar")
        jarFile.delete()
        ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            serverDex.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }

        // Copy to phone app assets only (car deploys it over USB-ADB)
        assetsDir.mkdirs()
        jarFile.copyTo(file("${assetsDir}/vd-server.jar"), overwrite = true)

        println("VD server JAR built: ${jarFile.length()} bytes -> assets")
    }
}

// Embed the car (server) APK in client assets so the phone can auto-install it on the car
//
// Audit B-05: a missing source APK used to be a println warning, so a release
// build could silently ship a *stale* app-server.apk left over from a previous
// run; the target is now deleted before the check and a missing source is a
// hard build error.
//
// issue #37: 内嵌的车机包必须与客户端变体同签名。release 客户端内嵌
// app-server-release.apk（正式签名），debug 客户端内嵌 app-server-debug.apk，
// 否则手机把 debug 签名的车机包装到已装正式签名包的车上会被 PM 以
// INSTALL_FAILED_UPDATE_INCOMPATIBLE 拒绝。
val serverVariant = if (wantsReleaseBuild) "release" else "debug"
val serverVariantTask = "assemble" + serverVariant.replaceFirstChar { it.uppercase() }
tasks.register("embedServerApk") {
    dependsOn(":app-server:$serverVariantTask")
    val serverApk = file("${rootDir}/app-server/build/outputs/apk/$serverVariant/app-server-$serverVariant.apk")
    val assetsDir = file("src/main/assets")

    // Declared so Gradle can see what this task consumes/produces; still always
    // runs (a stale embedded APK is worse than a re-copy).
    inputs.file(serverApk).withPropertyName("serverApk")
    outputs.file("${assetsDir}/app-server.apk").withPropertyName("embeddedApk")
    outputs.upToDateWhen { false }

    doLast {
        assetsDir.mkdirs()
        val target = file("${assetsDir}/app-server.apk")
        // Delete first: a stale copy must never survive a failed embed.
        if (target.exists()) target.delete()
        if (!serverApk.exists()) {
            throw GradleException(
                "Server APK not found at ${serverApk.absolutePath} — " +
                    "run :app-server:$serverVariantTask first. Refusing to embed a stale APK."
            )
        }
        serverApk.copyTo(target, overwrite = true)
        println("Server APK embedded ($serverVariant variant): ${target.length()} bytes")
    }
}

tasks.named("preBuild") {
    dependsOn("buildVdServer", "embedServerApk")
}
