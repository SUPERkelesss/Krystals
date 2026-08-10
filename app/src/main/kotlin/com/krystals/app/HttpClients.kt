package com.krystals.app

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Per v0.7.0: shared OkHttp clients grouped by timeout profile (was 6 ad-hoc builders). */
object HttpClients {
    /** 15s/30s: COD/MP query traffic. */
    val default: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** 10s/10s: connectivity/health checks (mirror test, key validation, update check). */
    val quick: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    /** 30s/60s: large downloads (APK). */
    val download: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
}
