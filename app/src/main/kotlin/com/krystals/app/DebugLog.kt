package com.krystals.app

/** Per v0.8.x: log tag for the CIF open pipeline. */
const val CIF_OPEN_TAG = "CIFOpen"

/**
 * Per v0.8.39: debug-only logging — production builds compile the message away entirely.
 * Mirrors Log.d/Log.w so network-layer diagnostics never leak into release builds.
 */
inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.d(tag, message())
}

inline fun warnLog(tag: String, throwable: Throwable? = null, message: () -> String) {
    if (BuildConfig.DEBUG) {
        if (throwable != null) android.util.Log.w(tag, message(), throwable)
        else android.util.Log.w(tag, message())
    }
}
