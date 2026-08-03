package com.krystals.app

import android.content.SharedPreferences
import com.krystals.app.ui.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic tests for [SettingsValues] and [PreferencesStore].
 * Uses a fake SharedPreferences backed by an in-memory map (no real storage).
 */
class PreferencesStoreTest {

    private class FakePrefs : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)
        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int =
            data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long =
            data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float =
            data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = key != null && key in data
        override fun edit(): SharedPreferences.Editor = FakeEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(private val data: MutableMap<String, Any>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) if (value != null) data[key] = value else data.remove(key)
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) if (values != null) data[key] = values else data.remove(key)
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) data[key] = value; return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) data[key] = value; return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) data[key] = value; return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) data[key] = value; return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) data.remove(key); return this
        }
        override fun clear(): SharedPreferences.Editor { data.clear(); return this }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    @Test
    fun defaultsAreSane() {
        val d = SettingsValues.defaults()
        assertEquals("auto", d.language)
        assertEquals(ThemeMode.SYSTEM, d.theme)
        assertTrue(d.autoCheckUpdate)
        assertEquals(0.45f, d.ballCollapsedAlpha)
        assertTrue(d.showLockButton)
        assertTrue(d.showLegend)
        assertTrue(d.autoBondRules)
        assertEquals(BondRuleMode.AUTO, d.bondRuleMode)
        assertTrue(d.autoConvertCell)
        assertTrue(d.defaultShowBonds)
        assertEquals(ExtendBondsDefault.METALS_ONLY, d.defaultExtendBonds)
        assertEquals(PolyhedraDefault.NEVER, d.defaultPolyhedra)
        assertEquals(CodMirrorMode.AUTO, d.codMirrorMode)
        assertEquals(0, d.codFixedIndex)
        assertEquals("", d.codCustomUrl)
        assertEquals(ExportQuality.HIGH, d.exportQuality)
        assertFalse(d.exportShowAxes)
        assertFalse(d.exportShowMeasurements)
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val prefs = FakePrefs()
        val original = SettingsValues(
            language = "en",
            theme = ThemeMode.DARK,
            autoCheckUpdate = false,
            ballCollapsedAlpha = 0.2f,
            showLockButton = false,
            showLegend = false,
            autoBondRules = false,
            bondRuleMode = BondRuleMode.BONDING,
            autoConvertCell = false,
            defaultShowBonds = false,
            defaultExtendBonds = ExtendBondsDefault.ALL,
            defaultPolyhedra = PolyhedraDefault.ALL,
            codMirrorMode = CodMirrorMode.FIXED,
            codFixedIndex = 2,
            codCustomUrl = "https://example.com",
            exportQuality = ExportQuality.LOW,
            exportShowAxes = true,
            exportShowMeasurements = true,
        )
        PreferencesStore.save(prefs, original)
        val loaded = PreferencesStore.load(prefs)
        assertEquals(original, loaded)
    }

    @Test
    fun loadMissingKeysReturnsDefaults() {
        val prefs = FakePrefs()
        val loaded = PreferencesStore.load(prefs)
        assertEquals(SettingsValues.defaults(), loaded)
    }

    @Test
    fun clearAllRemovesAllKeys() {
        val prefs = FakePrefs()
        val settings = SettingsValues(language = "zh", theme = ThemeMode.LIGHT)
        PreferencesStore.save(prefs, settings)
        PreferencesStore.clearAll(prefs)
        assertEquals(SettingsValues.defaults(), PreferencesStore.load(prefs))
    }

    @Test
    fun corruptedEnumValuesFallBackToDefaults() {
        val prefs = FakePrefs()
        prefs.edit().putString(PreferencesStore.KEY_BOND_RULE_MODE, "GARBAGE").putString(PreferencesStore.KEY_COD_MIRROR_MODE, "NOPE").apply()
        val loaded = PreferencesStore.load(prefs)
        assertEquals(BondRuleMode.AUTO, loaded.bondRuleMode)
        assertEquals(CodMirrorMode.AUTO, loaded.codMirrorMode)
    }

    @Test
    fun partialLoadMergesWithDefaults() {
        val prefs = FakePrefs()
        prefs.edit().putString(PreferencesStore.KEY_LANGUAGE, "zh").putFloat(PreferencesStore.KEY_BALL_COLLAPSED_ALPHA, 0.1f).apply()
        val loaded = PreferencesStore.load(prefs)
        assertEquals("zh", loaded.language)
        assertEquals(0.1f, loaded.ballCollapsedAlpha)
        assertEquals(ThemeMode.SYSTEM, loaded.theme)
        assertTrue(loaded.autoCheckUpdate)
    }
}
