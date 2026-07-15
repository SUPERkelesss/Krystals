package com.krystals.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.krystals.core.CifCodec
import com.krystals.core.CrystalEditor
import com.krystals.core.ParsedStructure
import com.krystals.core.SpaceGroupCatalog
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
                Log.d("MP", "search ok: ${results.size} items for '$query'")
                results
            }
        }
    }

    suspend fun downloadCif(context: Context, materialId: String, target: File): Result<ParsedStructure> = withContext(Dispatchers.IO) {
        val key = getKey(context) ?: return@withContext Result.failure(IllegalStateException("API key not set"))
        val url = summaryUrl(
            "material_ids", materialId,
            "_fields", "material_id,structure,symmetry",
        )
        val request = apiRequest(key, url)
        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    Log.w("MP", "downloadCif failed: HTTP ${response.code} id=$materialId body=${body.take(300)}")
                    error("HTTP ${response.code}: ${body.take(200)}")
                }
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: JSONArray()
                if (data.length() == 0) {
                    Log.w("MP", "downloadCif: no structure data for $materialId")
                    error("No structure data returned")
                }
                val item = data.getJSONObject(0)
                val structure = item.getJSONObject("structure")
                val cif = structureToCif(item, structure)
                target.parentFile?.mkdirs()
                target.writeText(cif, Charsets.UTF_8)
                val parsed = CifCodec.parseStructure(cif)
                Log.d("MP", "downloadCif ok: $materialId -> ${parsed.structure.sites.size} sites")
                // Per v0.2: a freshly downloaded MP structure has no bond rules, so synthesize them.
                if (parsed.structure.bondRules.isEmpty()) {
                    parsed.copy(structure = CrystalEditor.ensureAutoBondRules(parsed.structure).structure)
                } else parsed
            }
        }
    }

    private fun structureToCif(item: JSONObject, structure: JSONObject): String {
        val lattice = structure.getJSONObject("lattice").getJSONArray("matrix")
        val matrix = (0 until 3).map { row ->
            lattice.getJSONArray(row).let { col -> Vec3(col.getDouble(0), col.getDouble(1), col.getDouble(2)) }
        }
        val a = matrix[0].length()
        val b = matrix[1].length()
        val c = matrix[2].length()
        val alpha = angleDegrees(matrix[1], matrix[2])
        val beta = angleDegrees(matrix[0], matrix[2])
        val gamma = angleDegrees(matrix[0], matrix[1])
        val sites = structure.getJSONArray("sites")
        val materialId = item.optString("material_id", "mp")
        val symmetry = item.optJSONObject("symmetry")
        val spaceGroup = symmetry?.optString("symbol", "P1") ?: "P1"
        val spaceGroupNumber = symmetry?.optInt("number", 0)?.takeIf { it in 1..230 }
        // Resolve symmetry operations from our own catalog so the downloaded CIF carries a full
        // symmetry loop (MP's structure endpoint only returns the asymmetric-unit sites). Falls back
        // to identity (x,y,z) when the symbol isn't recognized.
        val symopSources = SpaceGroupCatalog.operations(spaceGroup).map { it.source }
        return buildString {
            append("data_${materialId}\n")
            append("_symmetry_space_group_name_H-M_alt   '${spaceGroup}'\n")
            spaceGroupNumber?.let { append("_space_group_IT_number   $it\n") }
            append("_cell_length_a   ${format(a)}\n")
            append("_cell_length_b   ${format(b)}\n")
            append("_cell_length_c   ${format(c)}\n")
            append("_cell_angle_alpha   ${format(alpha)}\n")
            append("_cell_angle_beta   ${format(beta)}\n")
            append("_cell_angle_gamma   ${format(gamma)}\n")
            append("loop_\n _space_group_symop_id\n _space_group_symop_operation_xyz\n")
            symopSources.forEachIndexed { index, op -> append(" ${index + 1} '${op}'\n") }
            append("loop_\n _atom_site_label\n _atom_site_type_symbol\n _atom_site_fract_x\n _atom_site_fract_y\n _atom_site_fract_z\n _atom_site_occupancy\n")
            (0 until sites.length()).map { index ->
                val site = sites.getJSONObject(index)
                val speciesArray = site.optJSONArray("species")
                if (speciesArray == null || speciesArray.length() == 0) return@map
                val species = speciesArray.getJSONObject(0)
                val element = species.optString("element", "X")
                val frac = site.optJSONArray("abc")
                if (frac == null || frac.length() < 3) return@map
                val label = "$element${index + 1}"
                append(" $label $element ${format(frac.getDouble(0))} ${format(frac.getDouble(1))} ${format(frac.getDouble(2))} ${format(species.optDouble("occu", 1.0))}\n")
            }
        }
    }

    private fun angleDegrees(u: Vec3, v: Vec3): Double {
        val ratio = (u.dot(v) / (u.length() * v.length())).coerceIn(-1.0, 1.0)
        return Math.toDegrees(kotlin.math.acos(ratio))
    }

    private fun format(value: Double): String = "%.6f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.').ifBlank { "0" }

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        fun length() = kotlin.math.sqrt(x * x + y * y + z * z)
        fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    }
}
