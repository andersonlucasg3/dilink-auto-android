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

// 版本号单点在根工程算出，app-client / app-server / vd-server 共用（issue #224）。
//
// 版本号形态是**日期-时间**：`app.versionName=20261117-2202`（YYYYMMDD-HHMM）。
// 每次有空就发一版，语义化版本号的 +1 没有信息量，日期时间能直接读出这包是哪天哪一轮。
//
// Android 的 versionCode 仍是单调递增 int（降级会被 `pm install -r` 拒绝），由 versionName
// **派生**而不是手填：`20261117-2202` → `26111722`（YYMMDDHH）。这样改一个日期就够，
// 不会出现「包换了、code 忘了 +1、车机装不上」。
// 同一小时内多次构建得到同一个 code —— `pm install -r` 允许同 code 覆盖安装，符合日常迭代。
//
// 兼容：`app.versionName` 不是日期格式时（如历史的 `1.0.3`），回退读 `app.versionCode`，
// 老分支与老构建脚本不受影响；两者都拿不到时直接失败，不产出没有版本号的包。
run {
    fun prop(key: String): String? =
        (project.findProperty(key) as String?)?.trim()?.takeIf { it.isNotEmpty() }

    val rawName = prop("app.versionName")
        ?: throw GradleException("gradle.properties 缺少 app.versionName（版本号唯一来源，issue #224）")

    // YYYYMMDD-HHMM 共 12 位数字 + 1 个连字符
    val digits = rawName.filter { it.isDigit() }
    val isDateVersion = Regex("""^\d{8}-\d{4}$""").matches(rawName)
    if (isDateVersion && digits.length == 12) {
        // YYMMDD + HH：丢掉世纪位与分钟位，得到 8 位十进制。
        // 2100 年前恒小于 Int.MAX_VALUE（2147483647），不会溢出。
        val derived = (digits.substring(2, 8) + digits.substring(8, 10)).toInt()
        extra["appVersionName"] = rawName
        extra["appVersionCode"] = derived
        logger.lifecycle("[version] $rawName → versionCode $derived（由日期派生）")
    } else {
        val legacyCode = prop("app.versionCode")?.toIntOrNull()
            ?: throw GradleException(
                "app.versionName=$rawName 不是日期-时间格式（YYYYMMDD-HHMM），" +
                    "且 gradle.properties 没有可用的 app.versionCode（issue #224）"
            )
        extra["appVersionName"] = rawName
        extra["appVersionCode"] = legacyCode
        logger.lifecycle("[version] $rawName → versionCode $legacyCode（非日期格式，读 app.versionCode）")
    }
}
