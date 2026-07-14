package com.krystals.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun localized(zh: String, en: String): String =
    if (LocalConfiguration.current.locales[0].language == "zh") zh else en
