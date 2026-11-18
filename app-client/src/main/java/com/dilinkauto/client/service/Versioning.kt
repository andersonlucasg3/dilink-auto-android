package com.dilinkauto.client.service

/**
 * 车机端 APK 安装路径使用的版本号解析与比较。
 *
 * 纯函数，车机安装状态机与单测都能直接用，不牵任何状态。
 *
 * 支持两种形态：
 *
 * **1. 语义化版本（当前格式，issue #8 起）**
 *  - "0.17.0"        → base "0.17.0", isDev=false, devNum=0
 *  - "0.17.0-dev"    → base "0.17.0", isDev=true,  devNum=0
 *  - "0.17.0-dev-02" → base "0.17.0", isDev=true,  devNum=2
 *  - "0.17.0-SNAPSHOT" / "0.17.0-dev-abc" → 不是 dev 形态；base=整串, isDev=false
 *
 * **2. 日期-时间版本（兼容旧 fork 包，issue #224）**
 *  - "20261117-2202" → base 整串, isDev=false, devNum=0，按 [20261117, 2202] 两段比较
 *  - 本仓库发版已回到语义化版本；这里保留日期分支只为与车上仍安装的旧 fork 包比较，
 *    不要再用它生成版本号（见 docs/release.md §1）。
 *
 * 排序规则：语义化版本 release > 同 base 的 dev，dev 之间按 devNum；日期格式逐段数值比较，
 * 且日期格式一律大于任何语义化版本（`20261117` 作为首段远大于常见主版本号，无需特判，
 * 但首段上界应保持在 8 位十进制内，即 2100 年前不会撞上 int 上限）。
 */

// "0.17.0-dev-02" → ("0.17.0", true, 2)
// "0.17.0-dev"    → ("0.17.0", true, 0)
// "0.17.0"        → ("0.17.0", false, 0)
// "20261117-2202" → ("20261117-2202", false, 0)
internal data class ParsedVersion(val base: String, val isDev: Boolean, val devNum: Int)

/** 日期-时间版本号：`YYYYMMDD-HHMM`，如 `20261117-2202`。 */
private val DATE_VERSION = Regex("""^(\d{8})-(\d{4})$""")

private val DEV_SUFFIX = Regex("""^(.*)-dev(?:-(\d+))?$""")

internal fun parseVersion(v: String): ParsedVersion {
    val m = DEV_SUFFIX.find(v)
    return if (m != null) {
        ParsedVersion(m.groupValues[1], true, m.groupValues[2].toIntOrNull() ?: 0)
    } else {
        ParsedVersion(v, false, 0)
    }
}

/**
 * 把 base 串拆成用于逐段比较的数字序列。
 *
 * 日期格式优先匹配，拆成 `[YYYYMMDD, HHMM]`；其余走历史逻辑按 `.` 切段、非数字 coerce 成 0
 * （`"1.2.beta"` 当作 `"1.2.0"`，对线上的畸形输入保持健壮）。
 */
private fun compareSegments(base: String): List<Int> {
    DATE_VERSION.find(base)?.let { m ->
        val day = m.groupValues[1].toIntOrNull()
        val minute = m.groupValues[2].toIntOrNull()
        if (day != null && minute != null) return listOf(day, minute)
    }
    return base.split(".").map { it.toIntOrNull() ?: 0 }
}

/**
 * 比较两个版本串：`a` 更新返回正数，`b` 更新返回负数，相等返回 0。
 *
 * 段内非数字一律 coerce 成 0，所以 `"1.2.beta"` 按 `"1.2.0"` 比较——对线上畸形输入保持健壮。
 * 日期格式见文件头注释（issue #224）。
 */
internal fun compareVersions(a: String, b: String): Int {
    val (baseA, devA, numA) = parseVersion(a)
    val (baseB, devB, numB) = parseVersion(b)
    val aParts = compareSegments(baseA)
    val bParts = compareSegments(baseB)
    val maxLen = maxOf(aParts.size, bParts.size)
    for (i in 0 until maxLen) {
        val aVal = aParts.getOrElse(i) { 0 }
        val bVal = bParts.getOrElse(i) { 0 }
        if (aVal != bVal) return aVal.compareTo(bVal)
    }
    if (!devA && devB) return 1
    if (devA && !devB) return -1
    return numA.compareTo(numB)
}
