plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    // Compose Desktop（JetBrains 版，仅 :app-desktop 使用）。1.6.2 对应 Kotlin 1.9.22。
    id("org.jetbrains.compose") version "1.6.2" apply false
}

// Build revision: <short sha>[-dirty] — computed once here at root
// configuration so app-client 与 vd-server 两边的 BuildConfig.BUILD_REVISION
// 同源（见 issue #8）。纯 JDK ProcessBuilder，与 Gradle API 版本无关；
// 无 git 时回退 "unknown"，构建照常进行，日志只会显示 +gunknown。
extra["buildRevision"] = try {
    fun runGit(vararg cmd: String): String =
        ProcessBuilder(*cmd).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
    val sha = runGit("git", "rev-parse", "--short", "HEAD")
    val dirty = runGit("git", "status", "--porcelain")
    sha + if (dirty.isNotEmpty()) "-dirty" else ""
} catch (_: Exception) {
    "unknown"
}

// 版本号单点在根工程算出，app-client / app-server / vd-server 共用。
//
// 版本号形态沿用本仓库既有的**语义化版本**：`app.versionName=0.19.0`
// （预发布用 `0.19.1-dev-3`）。正式版 tag 是 `v0.19.0`，预发布 tag 是
// `v0.19.1-dev-3`，两条发布流水线分别匹配（见 .github/workflows）。
//
// Android 的 versionCode 是单调递增 int（降级会被 `pm install -r` 拒绝），
// 由 `app.versionCode` 手工维护：发版时版本名 +1、versionCode 也 +1。
// 两者都拿不到时直接失败，不产出没有版本号的包。
run {
    fun prop(key: String): String? =
        (project.findProperty(key) as String?)?.trim()?.takeIf { it.isNotEmpty() }

    val rawName = prop("app.versionName")
        ?: throw GradleException("gradle.properties 缺少 app.versionName（版本号唯一来源）")
    val nameOk = Regex("""^\d+\.\d+\.\d+(-dev(-\d+)?)?$""").matches(rawName)
    if (!nameOk) {
        throw GradleException(
            "app.versionName=$rawName 不是语义化版本（期望 MAJOR.MINOR.PATCH 或 MAJOR.MINOR.PATCH-dev-N）"
        )
    }
    val code = prop("app.versionCode")?.toIntOrNull()
        ?: throw GradleException(
            "gradle.properties 缺少可用的 app.versionCode（整数，必须单调递增）"
        )

    extra["appVersionName"] = rawName
    extra["appVersionCode"] = code
    logger.lifecycle("[version] $rawName → versionCode $code")
}
