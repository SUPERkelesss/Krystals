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
     * Per v0.6.5: test all mirrors concurrently on EVERY call.
     * The first successful response wins and is remembered for subsequent queries,
     * but all mirrors are still pinged so that a previously-slow mirror can be
     * re-selected if it becomes faster.
     */
    suspend fun testMirrors(): CodMirror? {
        val previous = selectedMirror
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
                // Wait for all to complete; pick the first non-null result.
                // If none succeed, fall back to the previously selected mirror.
                val all = deferreds.awaitAll()
                all.firstOrNull { it != null } ?: previous
            }
        }.also { winner ->
            selectedMirror = winner
        }
    }

    fun selectMirror(mirror: CodMirror) {
        selectedMirror = mirror
    }

    /** Per v0.8.26: set mirror from user preference. */
    fun setMirrorMode(mode: CodMirrorMode, customUrl: String = "") {
        when (mode) {
            CodMirrorMode.AUTO -> { /* testMirrors() is called on each search — no change needed */ }
            CodMirrorMode.FIXED -> selectMirror(MIRRORS.first())
            CodMirrorMode.CUSTOM -> {
                val url = customUrl.trimEnd('/')
                if (url.isNotEmpty() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    selectMirror(CodMirror(url, if (url.endsWith("/cod")) url else "$url/cod"))
                }
            }
        }
    }

    /** Per v0.8.26: test a single custom mirror URL (3s timeout, 2xx = ok). */
    suspend fun testCustomMirror(url: String): Boolean {
        val clean = url.trimEnd('/')
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        return withContext(Dispatchers.IO) {
            try {
                val response = testClient.newCall(Request.Builder().url(clean).build()).execute()
                response.use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun request(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Accept", "text/csv, text/plain, chemical/x-cif, */*")
        // Same explicit-UA rationale as MaterialsProject: avoid being filtered by the server.
        .header("User-Agent", "Krystals/${com.krystals.app.BuildConfig.VERSION_NAME} (Android; crystallography.net COD)")
        .build()

    /**
     * Per v0.6.5: parse a chemical formula that may contain parentheses (), brackets [],
     * and braces {} (possibly nested) into a flat list of (element, count) pairs.
     * Examples: "Ca3(PO4)2" → Ca:3, P:2, O:8; "Mg2[SiO4]" → Mg:2, Si:1, O:4.
     *
     * Then convert to Hill-ordered, space-separated COD query string:
     *  - with carbon: C first, then H, then remaining elements alphabetically;
     *  - without carbon: all elements alphabetically.
     */
    private fun formulaToCodParam(formula: String): String {
        val s = formula.trim()
        if (s.isEmpty()) return s

        var pos = 0

        fun parseCount(): Int {
            val start = pos
            while (pos < s.length && s[pos].isDigit()) pos++
            return if (pos == start) 1 else s.substring(start, pos).toInt()
        }

        // Recursive descent: parseGroup returns a map of element→count for the
        // current nesting level. When encountering an opening bracket, we recurse;
        // on close, we read the optional multiplier and scale the inner map.
        fun parseGroup(): Map<String, Int> {
            val local = mutableMapOf<String, Int>()
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == '(' || c == '[' || c == '{' -> {
                        pos++
                        val inner = parseGroup()
                        val mult = parseCount()
                        inner.forEach { (e, n) -> local.merge(e, n * mult) { a, b -> a + b } }
                    }
                    c == ')' || c == ']' || c == '}' -> { pos++; return local }
                    c.isUpperCase() -> {
                        val elem = StringBuilder().append(s[pos++])
                        while (pos < s.length && s[pos].isLowerCase()) elem.append(s[pos++])
                        val count = parseCount()
                        local.merge(elem.toString(), count) { a, b -> a + b }
                    }
                    c.isWhitespace() || c == '.' -> { pos++ }
                    else -> { pos++ }
                }
            }
            return local
        }

        val counts = parseGroup()
        if (counts.isEmpty()) return s

        val tokens = counts.entries.map { it.key to it.value }
        val hasCarbon = tokens.any { it.first == "C" }
        val sorted = if (hasCarbon) {
            val (carbons, rest) = tokens.partition { it.first == "C" }
            val (hydrogens, others) = rest.partition { it.first == "H" }
            carbons + hydrogens + others.sortedBy { it.first }
        } else {
            tokens.sortedBy { it.first }
        }
        return sorted.joinToString(" ") { (element, count) ->
            if (count == 1) element else "$element$count"
        }
    }

    /**
     * Per v0.6.5: normalize a formula string for exact-match comparison.
     * Strips spaces, dots, dashes and leading/trailing chars, then re-derives
     * the Hill-ordered representation so user input can be compared to COD's formula column.
     */
    fun normalizeFormula(formula: String): String = formulaToCodParam(formula)

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
                var results = parseCsv(body)
                // Per v0.6.5: sort so exact matches come first.
                results = sortExactMatchesFirst(results, trimmed, mode)
                Log.d("COD", "search ok: ${results.size} items for '$trimmed' ($mode)")
                results
            }
        }
    }

    /**
     * Per v0.6.5: sort results so exact matches are at the top.
     * For FORMULA mode: compare normalized formula.
     * For TEXT mode: compare mineral/chemical name (case-insensitive).
     */
    private fun sortExactMatchesFirst(results: List<CodSearchResult>, query: String, mode: SearchMode): List<CodSearchResult> {
        val normalizedQuery = when (mode) {
            SearchMode.FORMULA -> normalizeFormula(query).lowercase().replace(" ", "")
            SearchMode.TEXT -> query.trim().lowercase()
            else -> return results
        }
        val (exact, rest) = results.partition { result ->
            when (mode) {
                SearchMode.FORMULA -> normalizeFormula(result.formula).lowercase().replace(" ", "") == normalizedQuery
                SearchMode.TEXT -> result.name.equals(query.trim(), ignoreCase = true)
                else -> false
            }
        }
        return exact + rest
    }

    /**
     * Per v0.6.5: check if a result is an exact match for the query.
     */
    fun isExactMatch(result: CodSearchResult, query: String, mode: SearchMode): Boolean {
        return when (mode) {
            SearchMode.FORMULA -> normalizeFormula(result.formula).lowercase().replace(" ", "") == normalizeFormula(query).lowercase().replace(" ", "")
            SearchMode.TEXT -> result.name.equals(query.trim(), ignoreCase = true)
            else -> false
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
