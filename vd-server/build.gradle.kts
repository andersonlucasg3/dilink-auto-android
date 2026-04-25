plugins {
    id("com.android.library")
}

android {
    namespace = "com.dilinkauto.vdserver"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Task to compile the server into a standalone DEX file
tasks.register("buildServerDex") {
    dependsOn("assembleRelease")
    doLast {
        // The classes.jar from the library build contains our compiled class
        val classesJar = file("build/intermediates/compile_library_classes_jar/release/classes.jar")
        val outputDex = file("build/vd-server.dex")

        if (classesJar.exists()) {
            val d8 = "${android.sdkDirectory}/build-tools/${android.buildToolsVersion}/d8"
            exec {
                commandLine(d8, "--output", outputDex.parent, classesJar.absolutePath)
            }
            // d8 outputs classes.dex, rename it
            val generatedDex = file("build/classes.dex")
            if (generatedDex.exists()) {
                generatedDex.renameTo(outputDex)
            }
            println("VD Server DEX built: ${outputDex.absolutePath}")
        }
    }
}
