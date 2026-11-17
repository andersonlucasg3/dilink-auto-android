package com.dilinkauto.client.service

import com.dilinkauto.protocol.AppCategory

/**
 * Classifies an installed app package into a coarse [AppCategory] for the car UI.
 *
 * Extracted from [AppListBuilder] (DRY/SRP audit S10) so the classification rules
 * are isolated from allowlist seeding and wire-encoding concerns, and can be
 * unit-tested without a `Context` or `PackageManager`.
 *
 * Rules are keyword-based on the package name. Order matters: a package matching
 * a navigation keyword never reaches the music or communication branches.
 */
object AppCategorizer {

    fun categorize(pkg: String): AppCategory = when {
        pkg.contains("map", true) || pkg.contains("navi", true) ||
        pkg.contains("waze", true) || pkg.contains("amap", true) ||
        pkg.contains("gaode", true) -> AppCategory.NAVIGATION

        pkg.contains("music", true) || pkg.contains("spotify", true) ||
        pkg.contains("podcast", true) || pkg.contains("player", true) ||
        pkg.contains("qqmusic", true) || pkg.contains("netease", true) -> AppCategory.MUSIC

        pkg.contains("whatsapp", true) || pkg.contains("telegram", true) ||
        pkg.contains("wechat", true) || pkg.contains("tencent.mm", true) ||
        pkg.contains("messenger", true) || pkg.contains("sms", true) ||
        pkg.contains("dialer", true) || pkg.contains("phone", true) -> AppCategory.COMMUNICATION

        else -> AppCategory.OTHER
    }
}
