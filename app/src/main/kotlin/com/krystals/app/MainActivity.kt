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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun attachBaseContext(newBase: Context) {
        val preferences = newBase.getSharedPreferences("krystals", Context.MODE_PRIVATE)
        val fallback = defaultSystemLanguage()
        val language = resolveLanguage(preferences.getString(PreferencesStore.KEY_LANGUAGE, fallback) ?: fallback)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(if (language == "zh") "zh-CN" else "en"))
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger()
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

    /**
     * Per v0.5.3b: write uncaught exceptions (incl. OOM) to `filesDir/krystals-crash.log` so the
     * stack trace survives a crash — the app had no logging before, so OOM traces were lost. The
     * handler delegates to the previous one after writing; `runCatching` guards the handler itself
     * so it never masks the original throwable.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val log = File(filesDir, "krystals-crash.log")
                val sw = StringWriter()
                sw.append("\n==== thread=${thread.name} ====\n")
                throwable.printStackTrace(PrintWriter(sw))
                log.appendText(sw.toString())
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
