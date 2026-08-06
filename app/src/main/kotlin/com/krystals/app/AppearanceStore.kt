package com.krystals.app

import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.core.style.BondColorMode
import com.krystals.renderer.core.style.FrameMode
import com.krystals.renderer.core.style.LineStyle
import com.krystals.renderer.core.style.ViewerAppearance
import org.json.JSONObject

/**
 * Per v0.5.2a: persistence for the global ViewerAppearance. The appearance is a rendering-only
 * setting (not part of the CIF), so it is stored as a JSON string under [KEY] in the "krystals"
 * SharedPreferences namespace alongside theme/language/etc. Fields the stored object lacks fall back
 * to [ViewerAppearance]'s defaults, so old saves stay valid when new fields are added.
 */
object AppearanceStore {
    const val KEY = "appearance_json"

    fun ViewerAppearance.toJson(): String = JSONObject().apply {
        put("backgroundArgb", backgroundArgb.toString())
        put("reflectionEnabled", reflectionEnabled)
        put("lightAzimuth", lightAzimuth)
        put("lightElevation", lightElevation)
        put("lightIntensity", lightIntensity)
        put("diffusion", diffusion)
        put("atomOpacity", atomOpacity)
        put("frameMode", frameMode.name)
        put("lineStyle", lineStyle.name)
        put("bondRadius", bondRadius)
        put("bondOpacity", bondOpacity)
        put("bondColorMode", bondColorMode.name)
        put("uniformBondArgb", uniformBondArgb.toString())
        put("bondReflectionEnabled", bondReflectionEnabled)
        put("polyhedronEnabled", polyhedronEnabled)
        put("polyhedronOpacity", polyhedronOpacity)
        put("polyhedronReflectionEnabled", polyhedronReflectionEnabled)
        put("showAxes", showAxes)
        put("axisMode", axisMode.name)
        put("axisOffsetX", axisOffsetX)
        put("axisOffsetY", axisOffsetY)
        put("depthOfFieldEnabled", depthOfFieldEnabled)
        put("dofNear", dofNear)
        put("dofFar", dofFar)
    }.toString()

    /** Parse a saved appearance string; any missing/corrupt field falls back to the default. */
    fun fromJson(json: String): ViewerAppearance? = runCatching {
        val o = JSONObject(json)
        val d = ViewerAppearance()
        // Per v0.5.4: 景深标度改为「近=正/远=负」(near>=far)。旧存档按「近=负」(near<far)存,
        // 读入时翻符号迁移到新约定;新存档(near>=far)原样保留。解析一次供两个字段共用。
        val rawDofNear = o.optDouble("dofNear", d.dofNear.toDouble()).toFloat()
        val rawDofFar = o.optDouble("dofFar", d.dofFar.toDouble()).toFloat()
        val legacyDof = rawDofNear < rawDofFar
        ViewerAppearance(
            backgroundArgb = o.optString("backgroundArgb", d.backgroundArgb.toString()).toLong(),
            reflectionEnabled = o.optBoolean("reflectionEnabled", d.reflectionEnabled),
            lightAzimuth = o.optDouble("lightAzimuth", d.lightAzimuth.toDouble()).toFloat(),
            lightElevation = o.optDouble("lightElevation", d.lightElevation.toDouble()).toFloat(),
            lightIntensity = o.optDouble("lightIntensity", d.lightIntensity.toDouble()).toFloat(),
            diffusion = o.optDouble("diffusion", d.diffusion.toDouble()).toFloat(),
            atomOpacity = o.optDouble("atomOpacity", d.atomOpacity.toDouble()).toFloat(),
            frameMode = runCatching { FrameMode.valueOf(o.optString("frameMode", d.frameMode.name)) }.getOrDefault(d.frameMode),
            lineStyle = runCatching { LineStyle.valueOf(o.optString("lineStyle", d.lineStyle.name)) }.getOrDefault(d.lineStyle),
            bondRadius = o.optDouble("bondRadius", d.bondRadius.toDouble()).toFloat(),
            bondOpacity = o.optDouble("bondOpacity", d.bondOpacity.toDouble()).toFloat(),
            bondColorMode = runCatching { BondColorMode.valueOf(o.optString("bondColorMode", d.bondColorMode.name)) }.getOrDefault(d.bondColorMode),
            uniformBondArgb = o.optString("uniformBondArgb", d.uniformBondArgb.toString()).toLong(),
            bondReflectionEnabled = o.optBoolean("bondReflectionEnabled", d.bondReflectionEnabled),
            polyhedronEnabled = o.optBoolean("polyhedronEnabled", d.polyhedronEnabled),
            polyhedronOpacity = o.optDouble("polyhedronOpacity", d.polyhedronOpacity.toDouble()).toFloat(),
            polyhedronReflectionEnabled = o.optBoolean("polyhedronReflectionEnabled", d.polyhedronReflectionEnabled),
            showAxes = o.optBoolean("showAxes", d.showAxes),
            axisMode = runCatching { AxisMode.valueOf(o.optString("axisMode", d.axisMode.name)) }.getOrDefault(d.axisMode),
            axisOffsetX = o.optDouble("axisOffsetX", d.axisOffsetX.toDouble()).toFloat(),
            axisOffsetY = o.optDouble("axisOffsetY", d.axisOffsetY.toDouble()).toFloat(),
            depthOfFieldEnabled = o.optBoolean("depthOfFieldEnabled", d.depthOfFieldEnabled),
            dofNear = if (legacyDof) -rawDofNear else rawDofNear,
            dofFar = if (legacyDof) -rawDofFar else rawDofFar,
        )
    }.getOrNull()
}
