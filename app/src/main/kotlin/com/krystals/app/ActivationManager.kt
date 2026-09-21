package com.krystals.app

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * Sponsor-reminder dismissal: verifies a 16-char activation code against the set of valid codes
 * baked into the build at compile time.
 *
 * Codes are never stored in the app as plaintext. Instead, `:app:generateActivationCodes` emits a
 * gitignored `activation-secrets.gradle.kts` that sets the project extras `activationSalt` and
 * `activationHashes` (a comma-joined list of `sha256(salt + code)` lowercase hex digests). Those
 * flow into [com.krystals.app.BuildConfig.ACTIVATION_SALT] and [com.krystals.app.BuildConfig.ACTIVATION_HASHES].
 *
 * When the secrets file is absent (e.g. a clean clone from github), both fields are empty strings
 * and [isActivated] always returns false — the build still succeeds and only the optional sponsor
 * reminder remains enabled. The user's entered code is validated by hashing it with the same salt and checking
 * membership in the hash set; on success the code itself is persisted so it can be re-validated on
 * every launch (a single code may be reused indefinitely by the same sponsor).
 */
object ActivationManager {
    private const val PREFS_CODE = "activation_code"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("krystals", Context.MODE_PRIVATE)

    private val hashes: Set<String> by lazy {
        val raw = com.krystals.app.BuildConfig.ACTIVATION_HASHES
        if (raw.isEmpty()) emptySet() else raw.split(',').filter { it.isNotEmpty() }.toHashSet()
    }

    /** sha256(salt + code) as lowercase hex, mirroring the generator in app/build.gradle.kts. */
    private fun hash(code: String): String {
        val salt = com.krystals.app.BuildConfig.ACTIVATION_SALT
        val bytes = (salt + code).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** True when [code] is one of the 1000 valid codes baked into this build. */
    private fun isValidCode(code: String): Boolean =
        hashes.isNotEmpty() && hash(code) in hashes

    /** True if this device holds a still-valid code that dismisses sponsor reminders. */
    fun isActivated(context: Context): Boolean {
        val code = prefs(context).getString(PREFS_CODE, null) ?: return false
        return isValidCode(code)
    }

    /** Validates [code]; on success persists it and returns true. Returns false otherwise. */
    fun activate(context: Context, code: String): Boolean {
        val normalised = code.trim().uppercase()
        if (!isValidCode(normalised)) return false
        prefs(context).edit { putString(PREFS_CODE, normalised) }
        return true
    }

    fun getActivationCode(context: Context): String? = prefs(context).getString(PREFS_CODE, null)
}
