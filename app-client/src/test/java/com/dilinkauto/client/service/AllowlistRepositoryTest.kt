package com.dilinkauto.client.service

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistRepositoryTest {

    private class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val prefs: FakePrefs) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { temp[key!!] = values?.toSet(); return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { temp[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { temp[key!!] = null; return this }
            override fun clear(): SharedPreferences.Editor { clear = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) prefs.map.clear()
                temp.forEach { (k, v) ->
                    if (v == null) prefs.map.remove(k) else prefs.map[k] = v
                }
            }
        }
    }

    private class FakeCtx : ContextWrapper(null)

    @Test
    fun `unconfigured repository returns null allowed packages`() {
        val prefs = FakePrefs()
        val repo = AllowlistRepository(
            context = FakeCtx(),
            prefs = prefs,
            isServiceRunning = { false }
        )

        assertFalse(repo.isConfigured())
        assertNull(repo.getAllowedPackages())
    }

    @Test
    fun `saveAllowedPackages stores packages and marks configured`() {
        val prefs = FakePrefs()
        var notified = false
        val repo = AllowlistRepository(
            context = FakeCtx(),
            prefs = prefs,
            isServiceRunning = {
                notified = true
                false
            }
        )

        repo.saveAllowedPackages(setOf("com.example.app1", "com.example.app2"), notifyService = true)

        assertTrue(repo.isConfigured())
        assertEquals(setOf("com.example.app1", "com.example.app2"), repo.getAllowedPackages())
        assertTrue(notified)
    }

    @Test
    fun `saveAllowedPackages respects notifyService false`() {
        val prefs = FakePrefs()
        var notified = false
        val repo = AllowlistRepository(
            context = FakeCtx(),
            prefs = prefs,
            isServiceRunning = {
                notified = true
                false
            }
        )

        repo.saveAllowedPackages(setOf("com.example.app"), notifyService = false)

        assertTrue(repo.isConfigured())
        assertFalse(notified)
    }
}
