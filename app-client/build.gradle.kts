plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dilinkauto.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dilinkauto.client"
        minSdk = 29
        targetSdk = 34
        versionCode = project.property("app.versionCode").toString().toInt()
        versionName = project.property("app.versionName").toString()
    }

    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    if (releaseKeystorePassword != null && releaseKeyPassword != null) {
        signingConfigs {
            create("release") {
                storeFile = file(System.getenv("RELEASE_KEYSTORE_FILE") ?: "dilink-auto-release.keystore")
                storePassword = releaseKeystorePassword
                keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "dilinkauto"
                keyPassword = releaseKeyPassword
            }
        }
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

    // Privilege flavors share the same code for now (backend selected at runtime).
    // The "root" flavor exists so later phases can strip Shizuku/sensitive
    // permissions from its manifest — banking SDKs scan declared permissions.
    flavorDimensions += "privilege"
    productFlavors {
        create("standard") {
            dimension = "privilege"
            buildConfigField("String", "PRIVILEGE_FLAVOR", "\"standard\"")
            buildConfigField("boolean", "AA_ONLY", "false")
        }
        create("root") {
            dimension = "privilege"
            buildConfigField("String", "PRIVILEGE_FLAVOR", "\"root\"")
            // Android-Auto-only build: no car flow (TCP relay, car-APK install,
            // accessibility/notification injection) — gated at runtime via this flag.
            buildConfigField("boolean", "AA_ONLY", "true")
        }
        create("bridge") {
            dimension = "privilege"
            buildConfigField("String", "PRIVILEGE_FLAVOR", "\"bridge\"")
            buildConfigField("boolean", "AA_ONLY", "false")
        }
    }

    // Apply release signing to both debug and release when env vars are available.
    // This ensures users can install any build over another without signature conflicts.
    signingConfigs.findByName("release")?.let { releaseConfig ->
        buildTypes.getByName("release").signingConfig = releaseConfig
        buildTypes.getByName("debug").signingConfig = releaseConfig
    }

    // Car-flow build outputs (embedded car APK, native daemon libs) are generated
    // into a build dir attached ONLY to the flavors that still ship them — the
    // root flavor is Android-Auto-only and must never package these artifacts.
    sourceSets {
        getByName("standard") { assets.srcDir("build/generated/server-assets") }
        getByName("bridge") { assets.srcDir("build/generated/server-assets") }
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
    // Android Auto mode: renders the VD mirror inside the stock AA host (NavigationTemplate surface)
    implementation("androidx.car.app:app:1.4.0")
}

// Build the VD server JAR and copy it to assets before the client APK is assembled.
// vd-server is a proper Gradle module — compilation handled by AGP/Kotlin.
// This task runs d8 + jar packaging. Needed by ALL flavors (the AA daemon runs
// vd-server.jar via app_process). The native .so copy lives in copyNativeLibs.
tasks.register("buildVdServer") {
    dependsOn(":vd-server:bundleLibRuntimeToJarDebug")

    val vdBuildDir = file("${rootDir}/vd-server/build/tmp/vds-d8")
    val vdClassesJar = file("${rootDir}/vd-server/build/intermediates/runtime_library_classes_jar/debug/classes.jar")
    val protocolJar = file("${rootDir}/protocol/build/intermediates/runtime_library_classes_jar/debug/classes.jar")
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

        // DEX vd-server + protocol + kotlin stdlib (needed for app_process runtime)
        exec {
            val inputs = listOf(vdClassesJar.absolutePath, protocolJar.absolutePath) +
                kotlinLibs.map { it.absolutePath }
            commandLine(listOf("java", "-cp", d8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", vdBuildDir.absolutePath) + inputs)
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
        val jarClassesDex = file("${vdBuildDir}/classes.dex")
        jarFile.delete()
        jarClassesDex.delete()
        try {
            serverDex.copyTo(jarClassesDex, overwrite = true)
            exec {
                workingDir(vdBuildDir)
                commandLine("jar", "cf", "vd-server.jar", "classes.dex")
            }
        } finally {
            jarClassesDex.delete()
        }

        // Copy to phone app assets
        assetsDir.mkdirs()
        jarFile.copyTo(file("${assetsDir}/vd-server.jar"), overwrite = true)
        println("VD server JAR built: ${jarFile.length()} bytes -> assets")
    }
}

// Copy the native daemon .so from AGP's cxx build output into the car-flow
// assets dir (build/generated/server-assets — only in the standard/bridge
// sourceSets). The root flavor's AA daemon is pure Kotlin and skips this.
tasks.register("copyNativeLibs") {
    dependsOn(":vd-server:externalNativeBuildDebug")

    val assetsDir = file("build/generated/server-assets")
    // AGP places CMake .so output under cxx/Debug/<hash>/obj/<abi>/
    val cxxObjBase = file("${rootDir}/vd-server/build/intermediates/cxx/Debug")

    outputs.upToDateWhen { false }

    doLast {
        // Walk the cxx dir tree to find the .so files regardless of hash.
        if (cxxObjBase.exists()) {
            fileTree(cxxObjBase).matching {
                include("**/obj/*/libdilinkd.so")
            }.forEach { soFile ->
                val abi = soFile.parentFile.name
                val destDir = file("${assetsDir}/native/${abi}")
                destDir.mkdirs()
                soFile.copyTo(file("${destDir}/libdilinkd.so"), overwrite = true)
                println("Native lib ${abi}: ${soFile.length()} bytes -> ${destDir}")
            }
        } else {
            println("WARNING: cxx build dir not found: ${cxxObjBase.absolutePath}")
        }
    }
}

// Embed the car (server) APK in client assets so the phone can auto-install it
// on the car. Standard/bridge only — the root flavor has no car flow.
tasks.register("embedServerApk") {
    dependsOn(":app-server:assembleDebug")
    val serverApk = file("${rootDir}/app-server/build/outputs/apk/debug/app-server-debug.apk")
    val assetsDir = file("build/generated/server-assets")

    outputs.upToDateWhen { false }

    doLast {
        assetsDir.mkdirs()
        val target = file("${assetsDir}/app-server.apk")
        if (serverApk.exists()) {
            serverApk.copyTo(target, overwrite = true)
            println("Server APK embedded: ${target.length()} bytes")
        } else {
            println("WARNING: Server APK not found at ${serverApk.absolutePath}")
        }
    }
}

// Wire asset-producing tasks per variant: every flavor needs vd-server.jar,
// but the root flavor is Android-Auto-only — no embedded car APK, no native
// daemon libs, and no :app-server/:vd-server native build forced on it.
android.applicationVariants.all {
    val variantPreBuild = tasks.named("pre${name.replaceFirstChar(Char::uppercaseChar)}Build")
    variantPreBuild.configure { dependsOn("buildVdServer") }
    if (flavorName != "root") {
        variantPreBuild.configure { dependsOn("embedServerApk", "copyNativeLibs") }
    }
}
