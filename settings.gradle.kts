pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先：本机直连 Maven Central 不稳定（大文件反复被截断），
        // 阿里云镜像作为首选源，缺失的构件会自动回退到下面的官方源。
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "DiLink-Auto"

include(":protocol-core")
include(":protocol")
include(":app-client")
include(":app-server")
include(":vd-server")
include(":app-desktop")
