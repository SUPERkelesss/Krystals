package com.krystals.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.ParsedStructure
import com.krystals.crystal.core.symmetry.SpaceGroupCatalog
import com.krystals.crystal.core.symmetry.SymmetryOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class MpSearchResult(
    val materialId: String,
    val formula: String,
    val crystalSystem: String,
    val spaceGroup: String,
    val nsites: Int,
)

object MaterialsProject {
    private const val BASE_HOST = "api.materialsproject.org"
    private const val LEGACY_CIF_HOST = "legacy.materialsproject.org"
    private const val LEGACY_CIF_PATH = "rest/v1/materials"
    private const val PREFS_KEY = "mp_api_key"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("krystals", Context.MODE_PRIVATE)

    fun hasKey(context: Context): Boolean = !prefs(context).getString(PREFS_KEY, null).isNullOrBlank()

    fun getKey(context: Context): String? = prefs(context).getString(PREFS_KEY, null)

    fun saveKey(context: Context, key: String) {
        prefs(context).edit().putString(PREFS_KEY, key).apply()
    }

    /**
     * Build a URL for the Materials Project REST API.
     * The trailing slash (empty path segment) is required — the API gateway rejects paths without it.
     */
    private fun apiUrl(path: String, vararg queryPairs: String): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme("https")
            .host(BASE_HOST)
        path.split("/").filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        builder.addPathSegment("") // trailing slash required by MP API
        var i = 0
        while (i < queryPairs.size) {
            builder.addQueryParameter(queryPairs[i], queryPairs[i + 1])
            i += 2
        }
        return builder.build()
    }

    private fun summaryUrl(vararg queryPairs: String): HttpUrl = apiUrl("materials/summary", *queryPairs)

    private fun apiRequest(key: String, url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("X-API-Key", key)
        .header("Accept", "application/json")
        // The MP gateway rejects requests with OkHttp's default User-Agent (HTTP 403 Forbidden).
        // Send an explicit UA so the request is accepted.
        .header("User-Agent", "Krystals/${com.krystals.app.BuildConfig.VERSION_NAME} (Android; materialsproject.org)")
        .build()

    /** Legacy REST request (no API key) for the public CIF endpoint. CDN requires a UA too. */
    private fun legacyCifRequest(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json, chemical/x-cif, text/plain, */*")
        .header("User-Agent", "Krystals/${com.krystals.app.BuildConfig.VERSION_NAME} (Android; materialsproject.org)")
        .build()

    suspend fun validateKey(key: String): Boolean = withContext(Dispatchers.IO) {
        val url = summaryUrl("_limit", "1")
        val request = apiRequest(key, url)
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Log.d("MP", "validateKey ok: HTTP ${response.code} bodyLen=${body.length}")
                    body.isNotBlank()
                } else {
                    Log.w("MP", "validateKey failed: HTTP ${response.code} body=${body.take(300)}")
                    false
                }
            }
        }.onFailure { Log.w("MP", "validateKey exception", it) }.getOrDefault(false)
    }

    suspend fun search(context: Context, query: String, fuzzy: Boolean = false): Result<List<MpSearchResult>> = withContext(Dispatchers.IO) {
        val key = getKey(context) ?: return@withContext Result.failure(IllegalStateException("API key not set"))
        // MP's `formula` parameter: a bare formula matches the exact reduced form (e.g. "SiO2").
        // Wrapping with `*` wildcards enables partial matches (e.g. "*O2", "Si*", "*SiO*"). The MP
        // gateway does not always accept `*`-wrapped queries, so fuzzy search is opt-in via a checkbox.
        val formula = if (fuzzy) "*${query.trim()}*" else query.trim()
        val url = summaryUrl(
            "formula", formula,
            "_limit", "50",
            "_fields", "material_id,formula_pretty,nsites,symmetry",
            // Per v0.3.5: return material_id in legacy numeric form (mp-22862) instead of the
            // padded alpha form (mp-aaaabhvi) for materials below the cut point. The alpha id is
            // the numeric id base-26 encoded; the legacy /cif endpoint only accepts numeric ids,
            // so requesting legacy form here maximises the chance downloadCif can fetch the
            // conventional cell from legacy instead of falling back to a primitive P1 cell.
            "id_format", "legacy",
        )
        val request = apiRequest(key, url)
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("MP", "search failed: HTTP ${response.code} url=$url body=${body.take(300)}")
                    error("HTTP ${response.code}: ${body.take(200)}")
                }
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: JSONArray()
                val results = (0 until data.length()).map { index ->
                    val item = data.getJSONObject(index)
                    val symmetry = item.optJSONObject("symmetry") ?: JSONObject()
                    MpSearchResult(
                        materialId = item.optString("material_id", "N/A"),
                        formula = item.optString("formula_pretty", "N/A"),
                        crystalSystem = symmetry.optString("crystal_system", "N/A"),
                        spaceGroup = symmetry.optString("symbol", "N/A"),
                        nsites = item.optInt("nsites", 0),
                    )
                }
                // The summary API returns whatever mp-id format the database uses (the legacy
                // normalization filter was removed — it emptied results because new-API ids no
                // longer match ^mp-\d+$, which is why search returned nothing while validateKey
                // passed). The structure+symmetry are fetched per-id at download time via the
                // same summary endpoint; do not pre-filter here.
                Log.d("MP", "search ok: ${results.size} items for '$query'")
                results
            }
        }
    }

    suspend fun downloadCif(context: Context, materialId: String, target: File): Result<ParsedStructure> = withContext(Dispatchers.IO) {
        val key = getKey(context) ?: return@withContext Result.failure(IllegalStateException("API key not set"))
        runCatching {
            // Per v0.3.5: the next-gen `summary.structure` field is the primitive cell (e.g. NaCl
            // mp-22862 → 2-atom rhombohedral cell), NOT the conventional cell. Writing it verbatim
            // with the real space-group label produces a "2-atom Fm-3m" contradiction. The legacy
            // CIF endpoint returns the conventional standardized cell but only for old numeric
            // mp-ids; new letter-format ids get HTTP 400 there. So: try legacy first (conventional,
            // no key needed); on failure fall back to next-gen primitive, written as P1 so the cell
            // and space group stay self-consistent.
            val realSpaceGroup = fetchRealSpaceGroup(key, materialId)
            val parsed = tryLegacyCif(materialId, target)?.let { legacy ->
                // Legacy returns the full conventional cell with all sites listed and a P1 header.
                // Restore the real space-group label for display, but keep an EXPLICIT identity op
                // list so CrystalStructure.effectiveSymmetryOperations does not fall back to the full
                // op set of the real space group (which would re-expand the already-complete cell).
                val correctedSymbol = realSpaceGroup?.symbol ?: legacy.structure.spaceGroup.symbol
                val correctedNumber = realSpaceGroup?.number ?: legacy.structure.spaceGroup.number
                val corrected = legacy.structure.copy(
                    spaceGroup = SpaceGroupCatalog.resolve(correctedSymbol, correctedNumber),
                    symmetryOperations = listOf(SymmetryOperation.IDENTITY),
                )
                Log.d("MP", "downloadCif ok (legacy): $materialId -> ${corrected.sites.size} sites, sg=${corrected.spaceGroup.symbol}")
                legacy.copy(structure = corrected)
            } ?: run {
                // New letter-format id (or legacy unavailable): use the primitive cell from next-gen,
                // written as P1 so there is no primitive-cell-vs-Fm-3m-label contradiction.
                val cif = buildCif(materialId, fetchNextgenStructure(key, materialId), asPrimitiveP1 = true)
                target.parentFile?.mkdirs()
                target.writeText(cif, Charsets.UTF_8)
                val p = CifCodec.parseStructure(cif)
                Log.d("MP", "downloadCif ok (primitive): $materialId -> ${p.structure.sites.size} sites, sg=${p.structure.spaceGroup.symbol}")
                p
            }
            Log.d("MP", "downloadCif ok: $materialId -> ${parsed.structure.sites.size} sites, sg=${parsed.structure.spaceGroup.symbol}")
            parsed
        }
    }

    /** Real space-group (number/symbol) reported by next-gen for [materialId], or null on failure. */
    private fun fetchRealSpaceGroup(key: String, materialId: String): SpaceGroupRef? {
        val url = summaryUrl("material_ids", materialId, "_fields", "material_id,symmetry", "id_format", "legacy")
        return runCatching {
            client.newCall(apiRequest(key, url)).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                val item = JSONObject(body).optJSONArray("data")?.optJSONObject(0) ?: return@use null
                val sym = item.optJSONObject("symmetry") ?: return@use null
                val number = sym.optInt("number", 0).takeIf { it > 0 }
                val symbol = sym.optString("symbol").takeIf { it.isNotBlank() }
                if (number != null || symbol != null) SpaceGroupRef(number, symbol) else null
            }
        }.onFailure { Log.w("MP", "fetchRealSpaceGroup failed for $materialId", it) }.getOrNull()
    }

    /** Fetch a next-gen summary item including the primitive `structure`. Throws on HTTP/parse error. */
    private fun fetchNextgenStructure(key: String, materialId: String): JSONObject {
        val url = summaryUrl("material_ids", materialId, "_fields", "material_id,structure,symmetry", "id_format", "legacy")
        client.newCall(apiRequest(key, url)).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.w("MP", "fetchNextgenStructure failed: HTTP ${response.code} id=$materialId body=${body.take(300)}")
                error("HTTP ${response.code}: ${body.take(200)}")
            }
            val data = JSONObject(body).optJSONArray("data")
            return data?.optJSONObject(0) ?: error("No material found for $materialId")
        }
    }

    private fun legacyCifUrl(materialId: String): HttpUrl {
        val builder = HttpUrl.Builder().scheme("https").host(LEGACY_CIF_HOST)
        LEGACY_CIF_PATH.split("/").filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        builder.addPathSegment(materialId)
        builder.addPathSegment("cif")
        return builder.build()
    }

    /**
     * Try the legacy public CIF endpoint. Returns the parsed conventional cell (written as P1 by
     * legacy CifWriter, with the real space-group label restored later by the caller) and writes
     * the CIF to [target], or null if legacy is unavailable for this id (HTTP 4xx/non-CIF). Legacy
     * serves only old numeric mp-ids; new letter-format ids are rejected here so the caller falls
     * back to primitive.
     */
    private fun tryLegacyCif(materialId: String, target: File): ParsedStructure? {
        val request = legacyCifRequest(legacyCifUrl(materialId))
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d("MP", "legacy CIF unavailable for $materialId: HTTP ${response.code}")
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                // The legacy endpoint sometimes returns JSON `{"cif": "...", ...}` and sometimes raw
                // CIF text; accept either. Reject anything that is not a valid CIF.
                val cifText = runCatching {
                    val obj = JSONObject(body)
                    obj.optString("cif").takeIf { it.isNotBlank() } ?: body
                }.getOrDefault(body)
                if (!cifText.contains(Regex("(?im)^\\s*data_")) || !cifText.contains("_cell_length_a")) {
                    Log.d("MP", "legacy CIF for $materialId was not a valid CIF; falling back to primitive")
                    return@use null
                }
                target.parentFile?.mkdirs()
                target.writeText(cifText, Charsets.UTF_8)
                CifCodec.parseStructure(cifText)
            }
        }.onFailure { Log.w("MP", "tryLegacyCif failed for $materialId", it) }.getOrNull()
    }

    private data class SpaceGroupRef(val number: Int?, val symbol: String?)

    /**
     * Build a self-contained CIF from a summary-endpoint material object. Per v0.3.5 the next-gen
     * `structure` field is the primitive cell, so it must be written as P1 ([asPrimitiveP1]=true)
     * to keep the cell and space group self-consistent — the real space-group label is NOT applied,
     * because the primitive coordinates are not in the conventional setting that label implies.
     * Sites are written verbatim with an identity symmetry operation so CrystalEngine does not
     * re-expand them.
     */
    private fun buildCif(materialId: String, item: JSONObject, asPrimitiveP1: Boolean = false): String {
        val structure = item.optJSONObject("structure") ?: error("Material $materialId has no structure")
        val lattice = structure.optJSONObject("lattice") ?: error("Material $materialId has no lattice")
        val sites = structure.optJSONArray("sites") ?: JSONArray()
        val symmetry = item.optJSONObject("symmetry")
        // When writing the primitive cell as P1, force the label to P1/1 regardless of the real
        // symmetry so there is no primitive-cell-vs-real-space-group contradiction.
        val realNumber = symmetry?.optInt("number", 0)?.takeIf { it > 0 }
        val realSymbol = symmetry?.optString("symbol")?.takeIf { it.isNotBlank() }
        val sgNumber = if (asPrimitiveP1) 1 else realNumber
        val sgSymbol = if (asPrimitiveP1) "P1" else (realSymbol ?: "P1")

        fun fmt(v: Double): String = "%.8f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.').ifBlank { "0" }
        val sb = StringBuilder()
        sb.append("data_").append(materialId.replace(Regex("[^A-Za-z0-9_-]"), "_")).append('\n')
        sb.append("_space_group_name_H-M_alt   '").append(sgSymbol).append("'\n")
        sgNumber?.let { sb.append("_space_group_IT_number   ").append(it).append('\n') }
        sb.append("_cell_length_a   ").append(fmt(lattice.optDouble("a"))).append('\n')
        sb.append("_cell_length_b   ").append(fmt(lattice.optDouble("b"))).append('\n')
        sb.append("_cell_length_c   ").append(fmt(lattice.optDouble("c"))).append('\n')
        sb.append("_cell_angle_alpha   ").append(fmt(lattice.optDouble("alpha"))).append('\n')
        sb.append("_cell_angle_beta   ").append(fmt(lattice.optDouble("beta"))).append('\n')
        sb.append("_cell_angle_gamma   ").append(fmt(lattice.optDouble("gamma"))).append('\n')
        // Identity operation only: the sites are written verbatim (primitive cell for the fallback
        // path, already listed in full), so no further symmetry expansion is wanted.
        sb.append("loop_\n _space_group_symop_id\n _space_group_symop_operation_xyz\n  1 'x, y, z'\n")
        sb.append("loop_\n _atom_site_label\n _atom_site_type_symbol\n _atom_site_fract_x\n _atom_site_fract_y\n _atom_site_fract_z\n _atom_site_occupancy\n")
        val labelCount = HashMap<String, Int>()
        for (i in 0 until sites.length()) {
            val site = sites.optJSONObject(i) ?: continue
            val element = site.optJSONArray("species")?.optJSONObject(0)?.optString("element")?.takeIf { it.isNotBlank() } ?: "X"
            val n = labelCount.getOrDefault(element, 0) + 1
            labelCount[element] = n
            val label = "$element$n"
            val abc = site.optJSONArray("abc")
            val x = if (abc != null) fmt(abc.optDouble(0)) else "0"
            val y = if (abc != null) fmt(abc.optDouble(1)) else "0"
            val z = if (abc != null) fmt(abc.optDouble(2)) else "0"
            sb.append(" ").append(label).append(" ").append(element).append(" ").append(x).append(" ").append(y).append(" ").append(z).append(" 1.0\n")
        }
        return sb.toString()
    }
}
