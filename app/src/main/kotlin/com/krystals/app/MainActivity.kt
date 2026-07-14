package com.krystals.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun attachBaseContext(newBase: Context) {
        val preferences = newBase.getSharedPreferences("krystals", Context.MODE_PRIVATE)
        val fallback = if (Locale.getDefault().language == "zh") "zh" else "en"
        val language = preferences.getString("language", fallback) ?: fallback
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(if (language == "zh") "zh-CN" else "en"))
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUri = intent?.data
        setContent {
            KrystalsRoot(
                activity = this,
                incomingUri = incomingUri,
                consumeIncomingUri = { incomingUri = null; intent?.data = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = intent.data
    }
}
