package com.dilinkauto.client.service

import com.dilinkauto.protocol.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AppCategorizer.categorize 单测：车机 app 网格的分类规则是有序关键字匹配，顺序即契约。
 */
class AppCategorizerTest {

    @Test
    fun `navigation keywords win`() {
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.autonavi.minimap"))
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.waze"))
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.baidu.BaiduMap"))
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.google.android.apps.maps"))
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.soso.map"))
    }

    @Test
    fun `navigation matching is case insensitive`() {
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.Waze"))
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.BAIDU.BaiduMap"))
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.example.NaviGation"))
    }

    @Test
    fun `navigation beats music and communication in the same name`() {
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.map.music"))
        assertEquals(AppCategory.NAVIGATION, AppCategorizer.categorize("com.map.dialer"))
    }

    @Test
    fun `music keywords`() {
        assertEquals(AppCategory.MUSIC, AppCategorizer.categorize("com.spotify.music"))
        assertEquals(AppCategory.MUSIC, AppCategorizer.categorize("com.netease.cloudmusic"))
        assertEquals(AppCategory.MUSIC, AppCategorizer.categorize("com.tencent.qqmusic"))
        assertEquals(AppCategory.MUSIC, AppCategorizer.categorize("com.example.player"))
        assertEquals(AppCategory.MUSIC, AppCategorizer.categorize("com.example.podcast"))
    }

    @Test
    fun `music beats communication in the same name`() {
        assertEquals(AppCategory.MUSIC, AppCategorizer.categorize("com.music.dialer"))
    }

    @Test
    fun `communication keywords`() {
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize("com.tencent.mm"))
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize("com.whatsapp"))
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize("org.telegram.messenger"))
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize("com.android.dialer"))
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize("com.android.sms"))
    }

    @Test
    fun `everything else is other`() {
        assertEquals(AppCategory.OTHER, AppCategorizer.categorize("com.dilinkauto.server"))
        assertEquals(AppCategory.OTHER, AppCategorizer.categorize("com.android.settings"))
        assertEquals(AppCategory.OTHER, AppCategorizer.categorize(""))
    }

    @Test
    fun `navigation is first in the enum for the car grid sort`() {
        // AppInfoProvider 按 category.id 排序；NAVIGATION 必须排在最前
        assertEquals(AppCategory.NAVIGATION, AppCategory.values().first())
    }
}
