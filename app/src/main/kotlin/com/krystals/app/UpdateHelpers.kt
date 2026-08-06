
package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun String.ensureCifExtension() = if (endsWith(".cif", true)) this else "$this.cif"

// ── Per v0.6.5: Update check and APK download/install helpers ──────────────────

/** Version info fetched from the remote JSON. */

internal data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val packageName: String,
)

/** Fetch version info from the remote JSON. Returns null on failure. */

internal suspend fun fetchUpdateInfo(): UpdateInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url("https://www.kelesss.art/lib/krystals-release/current_version.json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = org.json.JSONObject(body)
            UpdateInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName", ""),
                packageName = json.optString("packageName", ""),
            ).takeIf { it.versionCode > 0 && it.packageName.isNotBlank() }
        }
    }.getOrNull()
}

/** Download the APK and trigger installation. Returns true on success. */

internal suspend fun downloadAndInstallApk(
    context: android.content.Context,
    packageName: String,
    onProgress: (Float) -> Unit,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val downloadUrl = "https://www.kelesss.art/lib/krystals-release/$packageName"
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder().url(downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext false
            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()
            val apkDir = java.io.File(context.cacheDir, "apk_updates").apply { mkdirs() }
            val apkFile = java.io.File(apkDir, packageName)
            body.byteStream().use { input ->
                java.io.FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            onProgress((totalRead.toFloat() / contentLength).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }
            // Trigger APK installation.
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val installIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }
    }.onFailure { it.printStackTrace() }.getOrDefault(false)
}

