package com.krystals.app.ui

/**
 * Per v0.7.0: single home for every app-level colour dependency. Theme-dependent values are
 * resolved via [floatingBall]/[highlight]; the rest are plain constants. Rebranching the brand
 * or re-tuning a theme touches exactly this file (Theme.kt derives its schemes from it).
 */
object AppPalette {
    // ── Brand ──
    /** 深紫:light-theme primary、选中描边、dark 悬浮球底。 */
    const val BRAND_DEEP = 0xFF7542A5L
    /** 浅紫:dark-theme primary、dark 模式高亮。 */
    const val BRAND_LIGHT = 0xFFCFA7F5L
    /** 中紫:theme secondary、锁定测量面板/单色键预设。 */
    const val BRAND_MID = 0xFF9966CCL
    /** 预览球体固定灰(v0.7.0 起与主题无关)。 */
    const val SPHERE_GRAY = 0xFF5A5A60L

    // ── Viewer background ──
    const val BG_DARK = 0xFF101014L
    const val BG_LIGHT = 0xFFF8F8FBL
    const val BG_PURPLE = 0xFF4D2D6EL

    // ── Export overlay / on-screen axes ──
    const val AXIS_X = 0xFFE57373L
    const val AXIS_Y = 0xFF81C784L
    const val AXIS_Z = 0xFF64B5F6L
    const val HUB_LIGHT = 0xFFE0E0E0L
    const val HUB_DARK = 0xFF68686FL

    // ── Theme-dependent resolution ──
    /** Floating-ball palette: dark theme = deep container + light content; light = inverted. */
    fun floatingBall(dark: Boolean): Pair<Long, Long> =
        if (dark) BRAND_DEEP to BRAND_LIGHT else BRAND_LIGHT to BRAND_DEEP

    /** Selected-state accent: light purple on dark surfaces, deep purple on light surfaces. */
    fun highlight(dark: Boolean): Long = if (dark) BRAND_LIGHT else BRAND_DEEP
}
