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
        versionCode = 46
        versionName = "0.13.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
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
}

// Build the VD server JAR and copy to assets before the client APK is assembled
tasks.register("buildVdServer") {
    val vdSrcDir = file("${rootDir}/vd-server/src/main/java")
    val vdBuildDir = file("${rootDir}/vd-server/build")
    val androidJar = "${android.sdkDirectory}/platforms/android-${android.compileSdk}/android.jar"
    val d8Jar = file("${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/lib/d8.jar")
    val assetsDir = file("src/main/assets")
    val serverAssetsDir = file("${rootDir}/app-server/src/main/assets")

    // Always rebuild — fast enough and avoids stale cache issues
    outputs.upToDateWhen { false }

    doLast {
        val classesDir = file("${vdBuildDir}/classes")
        classesDir.deleteRecursively()
        classesDir.mkdirs()

        // Clean stale artifacts from assets
        file("${assetsDir}/vd-server.dex").delete()
        file("${serverAssetsDir}/vd-server.dex").delete()

        // Compile Java sources
        val javaSources = fileTree(vdSrcDir).matching { include("**/*.java") }.files
        exec {
            commandLine(listOf("javac", "-source", "17", "-target", "17",
                "-cp", androidJar,
                "-d", classesDir.absolutePath) + javaSources.map { it.absolutePath })
        }

        // DEX all class files (includes inner/anonymous classes)
        val classFiles = fileTree(classesDir).matching { include("**/*.class") }.files
        exec {
            commandLine(listOf("java", "-cp", d8Jar.absolutePath,
                "com.android.tools.r8.D8",
                "--output", vdBuildDir.absolutePath) + classFiles.map { it.absolutePath })
        }

        // Rename to vd-server.dex (delete old first — renameTo fails silently on Windows if dest exists)
        val classesDex = file("${vdBuildDir}/classes.dex")
        val serverDex = file("${vdBuildDir}/vd-server.dex")
        serverDex.delete()
        if (!classesDex.renameTo(serverDex)) {
            // Fallback: copy and delete
            classesDex.copyTo(serverDex, overwrite = true)
            classesDex.delete()
        }

        // Create JAR (ZIP containing classes.dex)
        val jarFile = file("${vdBuildDir}/vd-server.jar")
        jarFile.delete()
        exec {
            workingDir(vdBuildDir)
            commandLine("python3", "-c",
                "import zipfile; z=zipfile.ZipFile('vd-server.jar','w'); z.write('vd-server.dex','classes.dex'); z.close()")
        }

        // Copy to assets
        assetsDir.mkdirs()
        serverAssetsDir.mkdirs()
        jarFile.copyTo(file("${assetsDir}/vd-server.jar"), overwrite = true)
        jarFile.copyTo(file("${serverAssetsDir}/vd-server.jar"), overwrite = true)

        println("VD server JAR built: ${jarFile.length()} bytes -> assets")
    }
}

// Embed the car (server) APK in client assets so the phone can auto-install it on the car
tasks.register("embedServerApk") {
    dependsOn(":app-server:assembleDebug")
    val serverApk = file("${rootDir}/app-server/build/outputs/apk/debug/app-server-debug.apk")
    val assetsDir = file("src/main/assets")

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

tasks.named("preBuild") {
    dependsOn("buildVdServer", "embedServerApk")
}
