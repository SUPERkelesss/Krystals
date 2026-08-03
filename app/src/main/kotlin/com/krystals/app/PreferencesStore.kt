package com.krystals.app

import android.content.SharedPreferences
import com.krystals.app.ui.ThemeMode

/** Per v0.8.26: bond-rule strategy applied when opening a file. */
enum class BondRuleMode { AUTO, SMART_IONIC, BONDING, VDW }

/** Default "extend bonds across cell" behaviour for new tabs. */
enum class ExtendBondsDefault { ALL, METALS_ONLY, NEVER }

/** Default polyhedron visibility for new tabs. */
enum class PolyhedraDefault { ALL, METALS_ONLY, NEVER }

/** COD download mirror selection mode. */
enum class CodMirrorMode { AUTO, FIXED, CUSTOM }

/** Export image quality. */
enum class ExportQuality { HIGH, LOW }

/**
 * Pure data class holding all user-preference values.
 *
 * No Android dependencies — can be tested on JVM.
 * Serialization to/from [SharedPreferences] is handled by [PreferencesStore].
 */
data class SettingsValues(
    val language: String = "auto",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoCheckUpdate: Boolean = true,
    val ballCollapsedAlpha: Float = 0.45f,
    val showLockButton: Boolean = true,
    val showLegend: Boolean = true,
    val autoBondRules: Boolean = true,
    val bondRuleMode: BondRuleMode = BondRuleMode.AUTO,
    val autoConvertCell: Boolean = true,
    val defaultShowBonds: Boolean = true,
    val defaultExtendBonds: ExtendBondsDefault = ExtendBondsDefault.METALS_ONLY,
    val defaultPolyhedra: PolyhedraDefault = PolyhedraDefault.NEVER,
    val codMirrorMode: CodMirrorMode = CodMirrorMode.AUTO,
    val codFixedIndex: Int = 0,
    val codCustomUrl: String = "",
    val exportQuality: ExportQuality = ExportQuality.HIGH,
    val exportShowAxes: Boolean = false,
    val exportShowMeasurements: Boolean = false,
) {
    companion object {
        fun defaults() = SettingsValues()
    }
}

/** SharedPreferences key constants and read/write helpers for [SettingsValues]. */
object PreferencesStore {

    // ── Key constants ──
    const val KEY_LANGUAGE = "language"
    const val KEY_THEME = "theme"
    const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
    const val KEY_BALL_COLLAPSED_ALPHA = "ball_collapsed_alpha"
    const val KEY_SHOW_LOCK_BUTTON = "show_lock_button"
    const val KEY_SHOW_LEGEND = "show_legend"
    const val KEY_AUTO_BOND_RULES = "auto_bond_rules"
    const val KEY_BOND_RULE_MODE = "bond_rule_mode"
    const val KEY_AUTO_CONVERT_CELL = "auto_convert_cell"
    const val KEY_DEFAULT_SHOW_BONDS = "default_show_bonds"
    const val KEY_DEFAULT_EXTEND_BONDS = "default_extend_bonds"
    const val KEY_DEFAULT_POLYHEDRA = "default_polyhedra"
    const val KEY_COD_MIRROR_MODE = "cod_mirror_mode"
    const val KEY_COD_FIXED_INDEX = "cod_fixed_index"
    const val KEY_COD_CUSTOM_URL = "cod_custom_url"
    const val KEY_EXPORT_QUALITY = "export_quality"
    const val KEY_EXPORT_SHOW_AXES = "export_show_axes"
    const val KEY_EXPORT_SHOW_MEASUREMENTS = "export_show_measurements"

    /** Read [SettingsValues] from persistent storage, falling back to defaults. */
    fun load(prefs: SharedPreferences): SettingsValues {
        val defaults = SettingsValues.defaults()
        return SettingsValues(
            language = prefs.getString(KEY_LANGUAGE, defaults.language) ?: defaults.language,
            theme = try {
                ThemeMode.valueOf(prefs.getString(KEY_THEME, defaults.theme.name) ?: defaults.theme.name)
            } catch (_: IllegalArgumentException) { defaults.theme },
            autoCheckUpdate = prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, defaults.autoCheckUpdate),
            ballCollapsedAlpha = prefs.getFloat(KEY_BALL_COLLAPSED_ALPHA, defaults.ballCollapsedAlpha),
            showLockButton = prefs.getBoolean(KEY_SHOW_LOCK_BUTTON, defaults.showLockButton),
            showLegend = prefs.getBoolean(KEY_SHOW_LEGEND, defaults.showLegend),
            autoBondRules = prefs.getBoolean(KEY_AUTO_BOND_RULES, defaults.autoBondRules),
            bondRuleMode = try {
                BondRuleMode.valueOf(prefs.getString(KEY_BOND_RULE_MODE, defaults.bondRuleMode.name) ?: defaults.bondRuleMode.name)
            } catch (_: IllegalArgumentException) { defaults.bondRuleMode },
            autoConvertCell = prefs.getBoolean(KEY_AUTO_CONVERT_CELL, defaults.autoConvertCell),
            defaultShowBonds = prefs.getBoolean(KEY_DEFAULT_SHOW_BONDS, defaults.defaultShowBonds),
            defaultExtendBonds = try {
                ExtendBondsDefault.valueOf(prefs.getString(KEY_DEFAULT_EXTEND_BONDS, defaults.defaultExtendBonds.name) ?: defaults.defaultExtendBonds.name)
            } catch (_: IllegalArgumentException) { defaults.defaultExtendBonds },
            defaultPolyhedra = try {
                PolyhedraDefault.valueOf(prefs.getString(KEY_DEFAULT_POLYHEDRA, defaults.defaultPolyhedra.name) ?: defaults.defaultPolyhedra.name)
            } catch (_: IllegalArgumentException) { defaults.defaultPolyhedra },
            codMirrorMode = try {
                CodMirrorMode.valueOf(prefs.getString(KEY_COD_MIRROR_MODE, defaults.codMirrorMode.name) ?: defaults.codMirrorMode.name)
            } catch (_: IllegalArgumentException) { defaults.codMirrorMode },
            codFixedIndex = prefs.getInt(KEY_COD_FIXED_INDEX, defaults.codFixedIndex),
            codCustomUrl = prefs.getString(KEY_COD_CUSTOM_URL, defaults.codCustomUrl) ?: defaults.codCustomUrl,
            exportQuality = try {
                ExportQuality.valueOf(prefs.getString(KEY_EXPORT_QUALITY, defaults.exportQuality.name) ?: defaults.exportQuality.name)
            } catch (_: IllegalArgumentException) { defaults.exportQuality },
            exportShowAxes = prefs.getBoolean(KEY_EXPORT_SHOW_AXES, defaults.exportShowAxes),
            exportShowMeasurements = prefs.getBoolean(KEY_EXPORT_SHOW_MEASUREMENTS, defaults.exportShowMeasurements),
        )
    }

    /** Persist [SettingsValues] to SharedPreferences. */
    fun save(prefs: SharedPreferences, settings: SettingsValues) {
        prefs.edit()
            .putString(KEY_LANGUAGE, settings.language)
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_AUTO_CHECK_UPDATE, settings.autoCheckUpdate)
            .putFloat(KEY_BALL_COLLAPSED_ALPHA, settings.ballCollapsedAlpha)
            .putBoolean(KEY_SHOW_LOCK_BUTTON, settings.showLockButton)
            .putBoolean(KEY_SHOW_LEGEND, settings.showLegend)
            .putBoolean(KEY_AUTO_BOND_RULES, settings.autoBondRules)
            .putString(KEY_BOND_RULE_MODE, settings.bondRuleMode.name)
            .putBoolean(KEY_AUTO_CONVERT_CELL, settings.autoConvertCell)
            .putBoolean(KEY_DEFAULT_SHOW_BONDS, settings.defaultShowBonds)
            .putString(KEY_DEFAULT_EXTEND_BONDS, settings.defaultExtendBonds.name)
            .putString(KEY_DEFAULT_POLYHEDRA, settings.defaultPolyhedra.name)
            .putString(KEY_COD_MIRROR_MODE, settings.codMirrorMode.name)
            .putInt(KEY_COD_FIXED_INDEX, settings.codFixedIndex)
            .putString(KEY_COD_CUSTOM_URL, settings.codCustomUrl)
            .putString(KEY_EXPORT_QUALITY, settings.exportQuality.name)
            .putBoolean(KEY_EXPORT_SHOW_AXES, settings.exportShowAxes)
            .putBoolean(KEY_EXPORT_SHOW_MEASUREMENTS, settings.exportShowMeasurements)
            .apply()
    }

    /** Clear all keys managed by PreferencesStore (existing non-settings keys untouched). */
    fun clearAll(prefs: SharedPreferences) {
        prefs.edit()
            .remove(KEY_LANGUAGE)
            .remove(KEY_THEME)
            .remove(KEY_AUTO_CHECK_UPDATE)
            .remove(KEY_BALL_COLLAPSED_ALPHA)
            .remove(KEY_SHOW_LOCK_BUTTON)
            .remove(KEY_SHOW_LEGEND)
            .remove(KEY_AUTO_BOND_RULES)
            .remove(KEY_BOND_RULE_MODE)
            .remove(KEY_AUTO_CONVERT_CELL)
            .remove(KEY_DEFAULT_SHOW_BONDS)
            .remove(KEY_DEFAULT_EXTEND_BONDS)
            .remove(KEY_DEFAULT_POLYHEDRA)
            .remove(KEY_COD_MIRROR_MODE)
            .remove(KEY_COD_FIXED_INDEX)
            .remove(KEY_COD_CUSTOM_URL)
            .remove(KEY_EXPORT_QUALITY)
            .remove(KEY_EXPORT_SHOW_AXES)
            .remove(KEY_EXPORT_SHOW_MEASUREMENTS)
            .apply()
    }
}
