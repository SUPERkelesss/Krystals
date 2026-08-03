package com.krystals.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.krystals.crystal.io.CifCodec
import com.krystals.crystal.io.ParsedStructure
import com.krystals.crystal.analysis.editing.CrystalEditor
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
    val energyAboveHull: Double?,
)

object MaterialsProject {
    private const val BASE_HOST = "api.materialsproject.org"
    private const val PREFS_KEY = "mp_api_key"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val testClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Test connectivity to the Materials Project API host. Any HTTP response (even 4xx/5xx)
     * counts as reachable — only network-level failures are treated as unreachable.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://$BASE_HOST/")
                .get()
                .header("User-Agent", "Krystals/${com.krystals.app.BuildConfig.VERSION_NAME}")
                .build()
            testClient.newCall(request).execute().use { response ->
                Log.d("MP", "connection test: HTTP ${response.code}")
                true
            }
        }.getOrDefault(false)
    }

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

    /**
     * Per v0.8.0: Search uses only the new API. Legacy API support has been removed.
     */
    suspend fun search(context: Context, query: String, fuzzy: Boolean = false): Result<List<MpSearchResult>> = withContext(Dispatchers.IO) {
        val key = getKey(context) ?: return@withContext Result.failure(IllegalStateException("API key not set"))
        val formula = if (fuzzy) "*${query.trim()}*" else query.trim()
        val url = summaryUrl(
            "formula", formula,
            "_limit", "50",
            "_fields", "material_id,formula_pretty,nsites,symmetry,energy_above_hull",
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
                        energyAboveHull = if (item.has("energy_above_hull") && !item.isNull("energy_above_hull")) item.optDouble("energy_above_hull") else null,
                    )
                }
                // Per v0.7.1: sort by energy_above_hull ascending (nulls last).
                val sorted = results.sortedWith(compareBy(nullsLast()) { it.energyAboveHull })
                Log.d("MP", "search ok: ${sorted.size} items for '$query'")
                sorted
            }
        }
    }

    /**
     * Per v0.8.0: Download uses the new API. The next-gen `structure` field is the
     * conventional standard cell, written here with identity symmetry operations so the
     * CIF is self-consistent. After parsing, if the cell is already conventional (cubic
     * metric for Im-3m, orthorhombic metric for Cmce, etc.), restore the full space-group
     * operations so the saved CIF matches the original MP conventional cell. Otherwise
     * CifCodec will convert a genuine primitive cell to conventional as usual.
     */
    suspend fun downloadCif(context: Context, materialId: String, target: File, autoConvertConventional: Boolean = true): Result<ParsedStructure> = withContext(Dispatchers.IO) {
        val key = getKey(context) ?: return@withContext Result.failure(IllegalStateException("API key not set"))
        runCatching {
            // Fetch the conventional structure + real symmetry from the new API.
            val item = fetchNextgenStructure(key, materialId)
            val symmetry = item.optJSONObject("symmetry")
            val realNumber = symmetry?.optInt("number", 0)?.takeIf { it > 0 }
            val realSymbol = symmetry?.optString("symbol")?.takeIf { it.isNotBlank() }

            // Build a CIF with the real space group and identity symmetry operations.
            // The cell atoms are all listed explicitly, so no expansion is needed at parse time.
            val cif = buildCif(materialId, item, realSymbol, realNumber)
            target.parentFile?.mkdirs()
            target.writeText(cif, Charsets.UTF_8)
            val parsed = CifCodec.parseStructure(cif)

            // Per v0.8.0: Avoid double-conventionalization. If CifCodec recognized the cell
            // as already conventional (metric fallback), restore full symmetry operations so
            // the saved file matches the MP conventional CIF. If it is a genuine primitive
            // cell, CifCodec has already converted it and we leave its symmetry ops as-is.
            val finalStructure = if (CrystalEditor.isConventionalCell(parsed.structure)) {
                parsed.structure.copy(
                    symmetryOperations = SpaceGroupCatalog.operations(parsed.structure.spaceGroup.symbol),
                    isConventional = true,
                )
            } else {
                parsed.structure
            }
            Log.d(
                "MP",
                "downloadCif ok: $materialId -> ${finalStructure.sites.size} sites, " +
                    "sg=${finalStructure.spaceGroup.symbol}, conventional=${finalStructure.isConventional}",
            )
            parsed.copy(structure = finalStructure)
        }
    }

    /** Fetch a next-gen summary item including the conventional `structure` and `symmetry`. Throws on HTTP/parse error. */
    private fun fetchNextgenStructure(key: String, materialId: String): JSONObject {
        val url = summaryUrl("material_ids", materialId, "_fields", "material_id,structure,symmetry")
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

    private data class SpaceGroupRef(val number: Int?, val symbol: String?)

    /**
     * Build a self-contained CIF from a summary-endpoint material object.
     * Per v0.8.0: writes the real space group (not P1) so CifCodec can detect the Bravais
     * lattice type. Symmetry operations are set to identity because the downloaded cell has
     * all atoms listed explicitly — no expansion is wanted at this stage. downloadCif later
     * restores full space-group operations when the cell is recognized as conventional.
     */
    private fun buildCif(materialId: String, item: JSONObject, realSymbol: String?, realNumber: Int?): String {
        val structure = item.optJSONObject("structure") ?: error("Material $materialId has no structure")
        val lattice = structure.optJSONObject("lattice") ?: error("Material $materialId has no lattice")
        val sites = structure.optJSONArray("sites") ?: JSONArray()
        val sgNumber = realNumber ?: 1
        val sgSymbol = realSymbol ?: "P1"

        fun fmt(v: Double): String = "%.8f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.').ifBlank { "0" }
        val sb = StringBuilder()
        sb.append("data_").append(materialId.replace(Regex("[^A-Za-z0-9_-]"), "_")).append('\n')
        sb.append("_symmetry_space_group_name_H-M   '").append(sgSymbol).append("'\n")
        sb.append("_symmetry_Int_Tables_number   ").append(sgNumber).append('\n')
        sb.append("_cell_length_a   ").append(fmt(lattice.optDouble("a"))).append('\n')
        sb.append("_cell_length_b   ").append(fmt(lattice.optDouble("b"))).append('\n')
        sb.append("_cell_length_c   ").append(fmt(lattice.optDouble("c"))).append('\n')
        sb.append("_cell_angle_alpha   ").append(fmt(lattice.optDouble("alpha"))).append('\n')
        sb.append("_cell_angle_beta   ").append(fmt(lattice.optDouble("beta"))).append('\n')
        sb.append("_cell_angle_gamma   ").append(fmt(lattice.optDouble("gamma"))).append('\n')
        // Identity operation only: the sites are written verbatim (primitive cell), so no further
        // symmetry expansion is wanted at this stage. primaryToConvention will restore full ops.
        sb.append("loop_\n _symmetry_equiv_pos_site_id\n _symmetry_equiv_pos_as_xyz\n  1 'x, y, z'\n")
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
        // Per v0.7.1: do NOT mark as conventional — the MP API returns a primitive cell.
        // CifCodec will detect it as primitive (identity ops, no centering translations)
        // and apply primitive→conventional conversion via CrystalEditor.convertToConventional.
        return sb.toString()
    }
}
