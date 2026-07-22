package com.krystals.renderer.legacy

import com.krystals.renderer.core.style.RenderConfiguration as CoreRenderConfiguration
import com.krystals.renderer.core.style.RenderPalette as CoreRenderPalette

@Deprecated("Use com.krystals.renderer.core.style.FrameMode")
typealias FrameMode = com.krystals.renderer.core.style.FrameMode

@Deprecated("Use com.krystals.renderer.core.style.LineStyle")
typealias LineStyle = com.krystals.renderer.core.style.LineStyle

@Deprecated("Use com.krystals.renderer.core.style.BondColorMode")
typealias BondColorMode = com.krystals.renderer.core.style.BondColorMode

@Deprecated("Use com.krystals.renderer.core.style.AxisMode")
typealias AxisMode = com.krystals.renderer.core.style.AxisMode

@Deprecated("Use com.krystals.renderer.core.style.ViewerAppearance")
typealias ViewerAppearance = com.krystals.renderer.core.style.ViewerAppearance

@Deprecated("Use com.krystals.renderer.core.style.RenderConfiguration")
typealias RenderConfiguration = CoreRenderConfiguration

@Deprecated("Use com.krystals.renderer.core.style.RenderPalette")
object RenderPalette {
    fun defaultRadius(symbol: String) = CoreRenderPalette.defaultRadius(symbol)
    fun elementArgb(symbol: String) = CoreRenderPalette.elementArgb(symbol)
    fun vestaArgb(symbol: String) = CoreRenderPalette.vestaArgb(symbol)
    fun resolveArgb(symbol: String, configuration: CoreRenderConfiguration = CoreRenderConfiguration()) =
        CoreRenderPalette.resolveArgb(symbol, configuration)
    fun resolveSiteArgb(siteId: String, symbol: String, configuration: CoreRenderConfiguration) =
        CoreRenderPalette.resolveSiteArgb(siteId, symbol, configuration)
}
