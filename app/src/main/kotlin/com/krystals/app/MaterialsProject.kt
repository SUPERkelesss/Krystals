package com.krystals.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.krystals.core.CifCodec
import com.krystals.core.CrystalEditor
import com.krystals.core.ParsedStructure
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
        // Fetch the conventional-standard structure + real space group from the summary endpoint.
        // The new API's `structure` field is the full conventional cell (all symmetry-equivalent
        // atoms already expanded, e.g. mp-aaaffcsd SiO2 → 48 sites matching nsites), and `symmetry`
        // carries the real space-group number/symbol. We write the sites verbatim with an identity
        // symmetry operation (P1 'x,y,z') so CrystalEngine expands 1:1 — no doubling — while the
        // real space-group label is preserved for display. (The legacy CIF endpoint was abandoned:
        // it rejects the new letter-format mp-ids with HTTP 400 and, for the old numeric ids it
        // still accepts, returns a P1-expanded CIF with no real symmetry operations.)
        val url = summaryUrl("material_ids", materialId, "_fields", "material_id,structure,symmetry")
        val request = apiRequest(key, url)
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("MP", "downloadCif failed: HTTP ${response.code} id=$materialId body=${body.take(300)}")
                    error("HTTP ${response.code}: ${body.take(200)}")
                }
                val data = JSONObject(body).optJSONArray("data")
                val item = data?.optJSONObject(0) ?: error("No material found for $materialId")
                val cif = buildCif(materialId, item)
                target.parentFile?.mkdirs()
                target.writeText(cif, Charsets.UTF_8)
                val parsed = CifCodec.parseStructure(cif)
                Log.d("MP", "downloadCif ok: $materialId -> ${parsed.structure.sites.size} sites, sg=${parsed.structure.spaceGroupName}")
                // A freshly downloaded MP structure has no bond rules, so synthesize them.
                if (parsed.structure.bondRules.isEmpty()) {
                    parsed.copy(structure = CrystalEditor.ensureAutoBondRules(parsed.structure).structure)
                } else parsed
            }
        }
    }

    /**
     * Build a self-contained CIF from a summary-endpoint material object. The sites are written
     * verbatim (already the full conventional cell) with an identity symmetry operation so the
     * structure round-trips 1:1 through CrystalEngine; the real space-group number/symbol are
     * recorded as scalars for display and crystal-system inference.
     */
    private fun buildCif(materialId: String, item: JSONObject): String {
        val structure = item.optJSONObject("structure") ?: error("Material $materialId has no structure")
        val lattice = structure.optJSONObject("lattice") ?: error("Material $materialId has no lattice")
        val sites = structure.optJSONArray("sites") ?: JSONArray()
        val symmetry = item.optJSONObject("symmetry")
        val sgNumber = symmetry?.optInt("number", 0)?.takeIf { it > 0 }
        val sgSymbol = symmetry?.optString("symbol")?.takeIf { it.isNotBlank() } ?: "P1"

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
        // Identity operation only: the sites are already the full conventional cell, so no further
        // symmetry expansion is wanted (a non-identity loop would double the atoms).
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
