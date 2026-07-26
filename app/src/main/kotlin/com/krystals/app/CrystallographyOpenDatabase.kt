package com.krystals.app

import android.util.Log
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.ParsedStructure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class CodSearchResult(
    val fileId: String,
    val formula: String,
    val spaceGroup: String,
    val sgNumber: String,
    val name: String,
    val nel: Int,
)

data class CodMirror(val testUrl: String, val apiBase: String)

/**
 * Import from the Crystallography Open Database (COD). Unlike [MaterialsProject], COD requires no
 * API key. Searching hits `result.php?format=csv`; downloading fetches the raw CIF at
 * `<file>.cif`. Both are plain HTTP GETs with an explicit User-Agent.
 *
 * Per v0.6.3: supports multiple COD mirrors. On page open the app tests all mirrors concurrently
 * and selects the one with the lowest latency for subsequent queries.
 */
object CrystallographyOpenDatabase {
    val MIRRORS = listOf(
        CodMirror("https://www.crystallography.net", "https://www.crystallography.net/cod"),
        CodMirror("http://qiserver.ugr.es/cod/", "http://qiserver.ugr.es/cod"),
        CodMirror("http://cod.ibt.lt/", "http://cod.ibt.lt/cod"),
    )

    @Volatile
    private var selectedMirror: CodMirror = MIRRORS.first()

    // Per v0.6.3: guard so the mirror is only tested once per process lifetime.
    // On cold start this is false → test runs; subsequent COD page opens reuse the result.
    private var mirrorTested = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    enum class SearchMode { FORMULA, ELEMENT, TEXT }

    /**
     * Per v0.6.3: test all mirrors concurrently; the FIRST successful response wins.
     * Once a mirror is selected it persists for the process lifetime. Cold start (app
     * reopen) resets everything so the test runs fresh on next COD search entry.
     */
    suspend fun testMirrors(): CodMirror? {
        if (mirrorTested) return selectedMirror
        return withContext(Dispatchers.IO) {
            coroutineScope {
            val deferreds = MIRRORS.map { mirror ->
                async {
                    runCatching {
                        val start = System.currentTimeMillis()
                        val request = Request.Builder()
                            .url(mirror.testUrl)
                            .get()
                            .header("User-Agent", "Krystals/${com.krystals.app.BuildConfig.VERSION_NAME}")
                            .build()
                        testClient.newCall(request).execute().use { response ->
                            val elapsed = System.currentTimeMillis() - start
                            Log.d("COD", "mirror ${mirror.testUrl} responded: HTTP ${response.code} in ${elapsed}ms")
                            mirror
                        }
                    }.getOrNull()
                }
            }
            kotlinx.coroutines.selects.select<CodMirror?> {
                deferreds.forEach { d -> d.onAwait { it } }
            }
        }
    }.also { winner ->
            if (winner != null) {
                selectedMirror = winner
                mirrorTested = true
            }
        }
    }

    fun selectMirror(mirror: CodMirror) {
        selectedMirror = mirror
    }

    private fun request(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Accept", "text/csv, text/plain, chemical/x-cif, */*")
        // Same explicit-UA rationale as MaterialsProject: avoid being filtered by the server.
        .header("User-Agent", "Krystals/${com.krystals.app.BuildConfig.VERSION_NAME} (Android; crystallography.net COD)")
        .build()

    /**
     * COD's `formula` column stores Hill-ordered formulas space-separated (`- O2 Si -`), so the
     * `formula` query parameter must also be Hill-ordered and space-separated. Per v0.3.3 we parse
     * the input and reorder it to Hill convention rather than only inserting spaces:
     *  - with carbon: C first, then H (if any), then the remaining elements alphabetically;
     *  - without carbon: all elements alphabetically (H is not special).
     * Stoichiometric counts follow their element (count 1 omitted), e.g. `SiO2` → `O2 Si`,
     * `CH4O` → `C H4 O`, `CaCO3` → `C Ca O3`.
     */
    private fun formulaToCodParam(formula: String): String {
        val tokens = Regex("[A-Z][a-z]?\\d*").findAll(formula.trim())
            .map { it.value }
            .filter { it.isNotEmpty() }
            .map { token ->
                val match = Regex("([A-Z][a-z]?)(\\d*)").matchEntire(token) ?: return@map null
                val element = match.groupValues[1]
                val count = match.groupValues[2].ifBlank { "1" }
                element to count
            }
            .filterNotNull()
            .filter { it.first.isNotEmpty() }
            .toList()
        if (tokens.isEmpty()) return formula.trim()
        val hasCarbon = tokens.any { it.first == "C" }
        val sorted = if (hasCarbon) {
            val (carbons, rest) = tokens.partition { it.first == "C" }
            val (hydrogens, others) = rest.partition { it.first == "H" }
            carbons + hydrogens + others.sortedBy { it.first }
        } else {
            tokens.sortedBy { it.first }
        }
        return sorted.joinToString(" ") { (element, count) ->
            if (count == "1") element else "$element$count"
        }
    }

    /**
     * Split an element query like `Si O` or `Si,O` into individual element symbols for COD's
     * `el1`/`el2`/... parameters. Whitespace and commas are separators; trailing counts are
     * stripped (`O2` → `O`) so a formula-style input still works as an element list.
     */
    private fun splitElements(query: String): List<String> =
        query.trim().split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("\\d+$"), "") } // strip trailing stoichiometric counts
            .filter { it.isNotBlank() }

    suspend fun search(query: String, mode: SearchMode, maxElements: Int? = null): Result<List<CodSearchResult>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.success(emptyList())
        val base = selectedMirror.apiBase.toHttpUrl()
        val builder = base.newBuilder()
            .addPathSegment("result.php")
            .addQueryParameter("format", "csv")
            .addQueryParameter("count", "50")
        when (mode) {
            SearchMode.FORMULA -> builder.addQueryParameter("formula", formulaToCodParam(trimmed))
            SearchMode.ELEMENT -> {
                splitElements(trimmed).forEachIndexed { i, el ->
                    builder.addQueryParameter("el${i + 1}", el)
                }
                // Per v0.3.5: cap the number of distinct elements in returned structures via COD's
                // `strictmin`/`strictmax` (SQL: `nel BETWEEN strictmin AND strictmax`). The previous
                // code used `nel2`, but `nel1`/`nel2` are "NOT these elements" symbol slots, not a
                // count, so `nel2=N` was silently ignored. strictmin/strictmax is always sent as a
                // range so maxElements=N means "at most N elements".
                val max = maxElements?.coerceIn(1, 8) ?: 8
                builder.addQueryParameter("strictmin", "1")
                builder.addQueryParameter("strictmax", max.toString())
            }
            SearchMode.TEXT -> builder.addQueryParameter("text", trimmed)
        }
        val url = builder.build()
        runCatching {
            client.newCall(request(url)).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("COD", "search failed: HTTP ${response.code} url=$url body=${body.take(300)}")
                    error("HTTP ${response.code}: ${body.take(200)}")
                }
                val results = parseCsv(body)
                Log.d("COD", "search ok: ${results.size} items for '$trimmed' ($mode)")
                results
            }
        }
    }

    /**
     * Parse COD's CSV response: leading `#` comment lines, one header line, then quoted data rows.
     * Fields are double-quoted with `""` escaping internal quotes.
     */
    private fun parseCsv(body: String): List<CodSearchResult> {
        val lines = body.lines()
        var headerIndex = -1
        val headers = mutableListOf<String>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            // First non-comment line is the header.
            headers.addAll(splitCsvLine(line))
            headerIndex = i
            break
        }
        if (headerIndex < 0) return emptyList()
        val idx = { name: String -> headers.indexOfFirst { it.equals(name, ignoreCase = true) } }
        val iFile = idx("file"); val iFormula = idx("formula"); val iSg = idx("sg")
        val iSgNumber = idx("sgNumber"); val iChem = idx("chemname"); val iMineral = idx("mineral")
        val iCommon = idx("commonname"); val iNel = idx("nel")
        val results = mutableListOf<CodSearchResult>()
        for (i in (headerIndex + 1) until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val fields = splitCsvLine(line)
            if (fields.size < headers.size) continue
            val fileId = fields.getOrNull(iFile)?.takeIf { it.isNotBlank() } ?: continue
            fun col(n: Int) = fields.getOrNull(n)?.trim().orEmpty()
            val name = listOf(col(iChem), col(iMineral), col(iCommon)).firstOrNull { it.isNotBlank() }.orEmpty()
            results += CodSearchResult(
                fileId = fileId,
                formula = col(iFormula),
                spaceGroup = col(iSg),
                sgNumber = col(iSgNumber),
                name = name,
                nel = col(iNel).toIntOrNull() ?: 0,
            )
        }
        return results
    }

    /** Split a CSV line honoring double-quoted fields with `""` escaping. */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i += 2; continue }
                        inQuotes = false; i++
                    } else { sb.append(c); i++ }
                }
                else -> {
                    when (c) {
                        '"' -> { inQuotes = true; i++ }
                        ',' -> { out += sb.toString(); sb.clear(); i++ }
                        else -> { sb.append(c); i++ }
                    }
                }
            }
        }
        out += sb.toString()
        return out
    }

    suspend fun downloadCif(fileId: String, target: File): Result<ParsedStructure> = withContext(Dispatchers.IO) {
        val base = selectedMirror.apiBase.toHttpUrl()
        val url = base.newBuilder()
            .addPathSegment("$fileId.cif")
            .build()
        runCatching {
            client.newCall(request(url)).execute().use { response ->
                val cif = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("COD", "downloadCif failed: HTTP ${response.code} id=$fileId body=${cif.take(300)}")
                    error("HTTP ${response.code}: ${cif.take(200)}")
                }
                if (!cif.contains(Regex("(?im)^\\s*data_"))) error("COD did not return a CIF for $fileId")
                target.parentFile?.mkdirs()
                target.writeText(cif, Charsets.UTF_8)
                val parsed = CifCodec.parseStructure(cif)
                Log.d("COD", "downloadCif ok: $fileId -> ${parsed.structure.sites.size} sites, sg=${parsed.structure.spaceGroup.symbol}")
                // Per v0.5.0: bond-rule synthesis is deferred to the caller's async path so the UI
                // can show a "computing" overlay — return the parsed structure as-is here.
                parsed
            }
        }
    }
}
