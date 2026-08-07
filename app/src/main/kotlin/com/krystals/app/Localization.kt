package com.krystals.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Per v0.8.39: system-language fallback shared by [MainActivity] and KrystalsRoot. */
fun defaultSystemLanguage(): String = if (java.util.Locale.getDefault().language == "zh") "zh" else "en"

/** Per v0.8.39: "auto" resolves to the system language; any other code passes through. */
fun resolveLanguage(code: String): String = if (code == "auto") defaultSystemLanguage() else code

@Composable
fun localized(zh: String, en: String): String =
    if (LocalConfiguration.current.locales[0].language == "zh") zh else en
