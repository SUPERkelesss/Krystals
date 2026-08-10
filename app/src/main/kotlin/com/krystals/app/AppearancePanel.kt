@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import com.krystals.app.ui.AppPalette
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.krystals.renderer.core.style.AxisMode
import com.krystals.renderer.core.style.BondColorMode
import com.krystals.renderer.core.style.FrameMode
import com.krystals.renderer.core.style.LineStyle
import com.krystals.renderer.core.style.ViewerAppearance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun AppearanceDialog(
    tab: DocumentTab,
    onDismiss: () -> Unit,
    onApplied: (ViewerAppearance) -> Unit = {},
    // Per v0.5.2a: press-and-hold Preview callbacks. onPreviewStart hands the in-dialog appearance
    // up so the viewer can render with it; onPreviewEnd restores the dialog.
    onPreviewStart: (ViewerAppearance) -> Unit = {},
    onPreviewEnd: () -> Unit = {},
    backgroundFollowTheme: Boolean = true,
    onBackgroundFollowThemeChange: (Boolean) -> Unit = {},
) {
    var appearance by remember { mutableStateOf(tab.appearance) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var bondColorPickerOpen by remember { mutableStateOf(false) }
    var followTheme by remember { mutableStateOf(backgroundFollowTheme) }
    // Per v0.7.0: previews are 2D Canvas again — no dedicated preview Filament engine to release.
    // Per v0.6.5: precompute dark/light for the follow-theme clickable.
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isDarkSurface = !surfaceColor.isLight()
    // Per v0.5.3a: preview hides the dialog visually (alpha 0) but keeps it mounted so the
    // Preview button's pointerInput survives the press-and-hold and onPreviewEnd fires on release.
    // (v0.5.2a unmounted the dialog on press, killing the gesture → stuck preview.)
    var previewing by remember { mutableStateOf(false) }
    var resetConfirmOpen by remember { mutableStateOf(false) }
    val frameLabels = listOf(localized("不显示框线", "No frame"), localized("单个晶胞", "Single cell"), localized("所有框线", "All frames"))
    val lineLabels = listOf(localized("实线", "Solid"), localized("虚线", "Dashed"))
    val bondColorLabels = listOf(localized("双色圆柱", "Bicolor cylinder"), localized("单色圆柱", "Unicolor cylinder"))
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.alpha(if (previewing) 0f else 1f),
    ) {
        val dialogView = LocalView.current
        val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow, previewing) {
            val window = dialogWindow ?: return@DisposableEffect onDispose {}
            val originalDimAmount = window.attributes.dimAmount
            if (previewing) window.setDimAmount(0f)
            onDispose { runCatching { window.setDimAmount(originalDimAmount) } }
        }
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                Text(localized("调整外观", "Appearance"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                Column(Modifier.fillMaxWidth().height(480.dp).verticalScroll(rememberScrollState())) {
            Text(localized("背景色", "Background"), fontWeight = FontWeight.Bold)
            FlowRow(verticalArrangement = Arrangement.Center) {
                // Follow Theme: half-black/half-white circle, default option.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Box(
                        Modifier.size(40.dp).then(
                            if (followTheme) Modifier.border(2.dp, Color(AppPalette.BRAND_DEEP), RoundedCornerShape(20.dp)) else Modifier
                        ).clickable {
                            followTheme = true
                            // Per v0.6.5: do NOT call onBackgroundFollowThemeChange or update appearance here.
                            // The theme background is applied only on save or preview.
                        }
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawArc(color = Color.Black, startAngle = 90f, sweepAngle = 180f, useCenter = true, size = size)
                            drawArc(color = Color.White, startAngle = 270f, sweepAngle = 180f, useCenter = true, size = size)
                        }
                    }
                    Text(localized("跟随主题", "Follow Theme"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
                listOf(
                    AppPalette.BG_DARK to localized("深色", "Dark"),
                    AppPalette.BG_LIGHT to localized("浅色", "Light"),
                    AppPalette.BG_PURPLE to localized("紫色", "Purple"),
                ).forEach { (argb, label) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                        Box(Modifier.size(40.dp).background(colorFromArgb(argb), RoundedCornerShape(20.dp))
                            .then(if (appearance.backgroundArgb == argb && !followTheme) Modifier.border(2.dp, Color(AppPalette.BRAND_DEEP), RoundedCornerShape(20.dp)) else Modifier)
                            .clickable { followTheme = false; appearance = appearance.copy(backgroundArgb = argb) })
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Box(Modifier.size(40.dp)
                        .background(
                            Brush.sweepGradient(
                                RAINBOW_SWEEP_COLORS
                            ),
                            RoundedCornerShape(20.dp),
                        )
                        .then(if (!followTheme) Modifier.border(2.dp, Color(AppPalette.BRAND_DEEP), RoundedCornerShape(20.dp)) else Modifier)
                        .clickable { followTheme = false; colorPickerOpen = true })
                    Text(localized("自定义", "Custom"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("框线", "Frame"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    DropdownField(localized("模式", "Mode"), frameLabels[appearance.frameMode.ordinal], frameLabels) { appearance = appearance.copy(frameMode = FrameMode.entries[frameLabels.indexOf(it)]) }
                }
                // Per v0.5.3: line style is meaningless when no frame is drawn, so hide it.
                if (appearance.frameMode != FrameMode.NONE) Column(Modifier.weight(1f)) {
                    DropdownField(localized("线型", "Line"), lineLabels[appearance.lineStyle.ordinal], lineLabels) { appearance = appearance.copy(lineStyle = LineStyle.entries[lineLabels.indexOf(it)]) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // Per v0.6.5: Axes section — separate from frame, with position sliders.
            Text(localized("坐标轴", "Axes"), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            val axisLabels = listOf("abc", "XYZ")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = appearance.showAxes, onCheckedChange = { appearance = appearance.copy(showAxes = it) })
                if (appearance.showAxes) {
                    Spacer(Modifier.weight(1f))
                    DropdownField(localized("坐标系", "System"), axisLabels[appearance.axisMode.ordinal], axisLabels) {
                        appearance = appearance.copy(axisMode = AxisMode.entries[axisLabels.indexOf(it)])
                    }
                }
            }
            if (appearance.showAxes) {
                LabeledSlider(localized("坐标轴 x 坐标", "Axis X position"), appearance.axisOffsetX, 0f..1f, decimals = 2) { appearance = appearance.copy(axisOffsetX = it) }
                LabeledSlider(localized("坐标轴 y 坐标", "Axis Y position"), appearance.axisOffsetY, 0f..1f, decimals = 2) { appearance = appearance.copy(axisOffsetY = it) }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("原子", "Atoms"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("原子反射", "Atom reflection"), appearance.reflectionEnabled) { appearance = appearance.copy(reflectionEnabled = it) }
            LabeledSlider(localized("原子不透明度", "Atom opacity"), appearance.atomOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(atomOpacity = it) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("化学键", "Bonds"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            ToggleRow(localized("键反射", "Bond reflection"), appearance.bondReflectionEnabled) { appearance = appearance.copy(bondReflectionEnabled = it) }
            LabeledSlider(localized("键半径", "Bond radius"), appearance.bondRadius, 0.01f..0.15f, decimals = 2) { appearance = appearance.copy(bondRadius = it) }
            LabeledSlider(localized("化学键不透明度", "Bond opacity"), appearance.bondOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(bondOpacity = it) }
            DropdownField(localized("键颜色", "Bond color"), bondColorLabels[appearance.bondColorMode.ordinal], bondColorLabels) { appearance = appearance.copy(bondColorMode = BondColorMode.entries[bondColorLabels.indexOf(it)]) }
            if (appearance.bondColorMode == BondColorMode.UNICOLOR) FlowRow {
                listOf(0xFF9A90A0, AppPalette.BRAND_MID, 0xFFFFFFFF, 0xFF333333).forEach { argb -> Box(Modifier.padding(5.dp).size(38.dp).background(colorFromArgb(argb), CircleShape).then(if (appearance.uniformBondArgb == argb) Modifier.border(2.dp, Color(AppPalette.BRAND_DEEP), CircleShape) else Modifier).clickable { appearance = appearance.copy(uniformBondArgb = argb) }) }
                // Per v0.5.3: custom colour swatch opens the colour wheel for the uniform bond colour.
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp)) {
                    Box(Modifier.size(38.dp)
                        .background(
                            Brush.sweepGradient(
                                RAINBOW_SWEEP_COLORS
                            ),
                            CircleShape,
                        )
                        .clickable { bondColorPickerOpen = true })
                    Text(localized("自定义", "Custom"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("多面体", "Polyhedra"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("多面体反射", "Polyhedron reflection"), appearance.polyhedronReflectionEnabled) { appearance = appearance.copy(polyhedronReflectionEnabled = it) }
            LabeledSlider(localized("多面体不透明度", "Polyhedron opacity"), appearance.polyhedronOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(polyhedronOpacity = it) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            // Per v0.7.0: hydrogen-bond appearance section.
            Text(localized("氢键", "H-Bonds"), fontWeight = FontWeight.Bold)
            LabeledSlider(localized("氢键半径", "H-bond radius"), appearance.hbondRadius, 0.01f..0.15f, decimals = 2) { appearance = appearance.copy(hbondRadius = it) }
            LabeledSlider(localized("氢键不透明度", "H-bond opacity"), appearance.hbondOpacity, 0f..1f, percentage = true) { appearance = appearance.copy(hbondOpacity = it) }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("世界光源", "World light"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LabeledSlider(localized("光源方位角", "Light azimuth"), appearance.lightAzimuth, 0f..360f) { appearance = appearance.copy(lightAzimuth = it) }
                    LabeledSlider(localized("光源高度角", "Light elevation"), appearance.lightElevation, 0f..89.9f) { appearance = appearance.copy(lightElevation = it) }
                    LabeledSlider(localized("反射强度", "Reflection intensity"), appearance.lightIntensity, 0f..1f, percentage = true) { appearance = appearance.copy(lightIntensity = it) }
                    LabeledSlider(localized("反射扩散", "Reflection diffusion"), appearance.diffusion, 0f..1f, percentage = true) { appearance = appearance.copy(diffusion = it) }
                }
                AtomAppearancePreview(appearance, Modifier.padding(start = 10.dp).size(112.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(localized("景深", "Depth cueing"), fontWeight = FontWeight.Bold)
            ToggleRow(localized("景深", "Depth cueing"), appearance.depthOfFieldEnabled) { appearance = appearance.copy(depthOfFieldEnabled = it) }
            if (appearance.depthOfFieldEnabled) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // The depth-cue mix runs from 1 to 0 as depth increases: far/start -> near/end.
                        LabeledSlider(localized("起始值", "Start"), appearance.dofFar, -5f..5f) { v -> appearance = appearance.copy(dofFar = v.coerceAtMost(appearance.dofNear)) }
                        LabeledSlider(localized("终止值", "End"), appearance.dofNear, -5f..5f) { v -> appearance = appearance.copy(dofNear = v.coerceAtLeast(appearance.dofFar)) }
                    }
                    DepthCueingPreview(appearance, Modifier.padding(start = 10.dp).size(112.dp))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
        }
        // Per v0.6.3: Restore defaults button at the bottom center.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { resetConfirmOpen = true }) {
                Text(localized("恢复默认设置", "Restore Defaults"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Per v0.5.2a: Preview (press-and-hold, rounded) / Cancel (text) / Save (rounded).
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Preview: press-and-hold via detectTapGestures.onPress; no onClick.
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                previewing = true
                                val previewAppearance = if (followTheme) appearance.copy(backgroundArgb = if (isDarkSurface) AppPalette.BG_DARK else AppPalette.BG_LIGHT) else appearance
                                onPreviewStart(previewAppearance)
                                tryAwaitRelease()
                                onPreviewEnd()
                                previewing = false
                            },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(localized("预览", "Preview"), color = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(localized("取消", "Cancel")) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onBackgroundFollowThemeChange(followTheme)
                    val savedAppearance = if (followTheme) appearance.copy(backgroundArgb = if (isDarkSurface) AppPalette.BG_DARK else AppPalette.BG_LIGHT) else appearance
                    onApplied(savedAppearance); onDismiss()
                },
                shape = RoundedCornerShape(16.dp),
            ) { Text(localized("保存", "Save")) }
        }
        }
    }
    }

    if (colorPickerOpen) {
        ColorPickerDialog(
            initialArgb = appearance.backgroundArgb,
            onDismiss = { colorPickerOpen = false },
            onColorSelected = { color ->
                appearance = appearance.copy(backgroundArgb = color)
                colorPickerOpen = false
            },
        )
    }

    if (resetConfirmOpen) {
        AlertDialog(
            onDismissRequest = { resetConfirmOpen = false },
            title = { Text(localized("确认", "Confirm")) },
            text = { Text(localized("确认将外观设置为默认设置吗？", "Reset all appearance settings to defaults?")) },
            confirmButton = { TextButton(onClick = { resetConfirmOpen = false; appearance = ViewerAppearance(); followTheme = true }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = { TextButton(onClick = { resetConfirmOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, modifier = Modifier.weight(1f)); androidx.compose.material3.Switch(checked, onChecked) } }

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, percentage: Boolean = false, steps: Int = 0, decimals: Int = 1, onValue: (Float) -> Unit) {
    Text("$label  ${formatSliderValue(value, percentage, decimals)}")
    Slider(value, onValue, valueRange = range, steps = steps)
}

internal fun formatSliderValue(value: Float, percentage: Boolean = false, decimals: Int = 1): String =
    if (percentage) String.format(Locale.ROOT, "%.0f%%", value * 100)
    else String.format(Locale.ROOT, "%.${decimals}f", value)

@Composable
private fun AtomAppearancePreview(appearance: ViewerAppearance, modifier: Modifier = Modifier) {
    // Per v0.7.0: back to the 2D Canvas preview (the Filament off-screen render of
    // v0.7.0 failed to display in the dialog on device). Sphere grey adapts to the
    // theme: light grey ball on dark surfaces, dark grey ball on light surfaces.
    val bgCompose = MaterialTheme.colorScheme.surfaceVariant
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = bgCompose) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val radius = size.minDimension * 0.38f
            val center = Offset(size.width / 2f, size.height / 2f)
            val opacity = appearance.atomOpacity.coerceIn(0f, 1f)
            // Per v0.7.0: fixed dark grey sphere regardless of theme.
            val gray = Color(AppPalette.SPHERE_GRAY)
            val sphereColor = gray.copy(alpha = opacity)
            val theta = appearance.lightAzimuth / 180f * PI.toFloat()
            val phi = appearance.lightElevation / 180f * PI.toFloat()
            val cosPhi = cos(phi)
            val lightOffset = radius * .95f * cosPhi
            val highlight = Offset(center.x + cos(theta) * lightOffset, center.y - sin(theta) * lightOffset)
            drawCircle(sphereColor, radius, center)
            if (appearance.reflectionEnabled && opacity > 0.01f) {
                val highlightBrush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = appearance.lightIntensity.coerceIn(.05f, 1f) * opacity), Color.Transparent),
                    center = highlight,
                    radius = radius * (0.35f + 1.65f * appearance.diffusion),
                )
                drawCircle(highlightBrush, radius, center)
            }
            drawCircle(Color.Black.copy(alpha = .3f * opacity), radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
        }
    }
}

/** Five depth samples (−3, −1.5, 0, +1.5, +3) with the continuous opacity-depth curve plotted above them. */

@Composable
private fun DepthCueingPreview(appearance: ViewerAppearance, modifier: Modifier = Modifier) {
    val bgCompose = MaterialTheme.colorScheme.surfaceVariant
    // Per v0.7.0: the five spheres are drawn as 2D Canvas again (the Filament off-screen
    // render of v0.7.0 failed to display on device); the fog curve + axis labels stay Canvas.
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = bgCompose) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val near = appearance.dofNear.coerceIn(-5f, 5f)
            val far = appearance.dofFar.coerceIn(-5f, 5f)
            val depths = listOf(-3f, -1.5f, 0f, 1.5f, 3f)
            val radius = size.minDimension * 0.105f
            val xStart = radius * 1.25f
            val xEnd = size.width - radius * 1.25f
            val xPositions = depths.indices.map { index ->
                xStart + (xEnd - xStart) * index / depths.lastIndex.toFloat()
            }
            val chartTop = size.height * 0.10f
            val chartBottom = size.height * 0.43f
            val atomY = size.height * 0.73f
            val isLight = bgCompose.isLight()
            val lineColor = if (isLight) Color.Black else Color.White
            val guideColor = lineColor.copy(alpha = 0.48f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
            drawLine(guideColor, Offset(xStart, chartTop), Offset(xEnd, chartTop), 1.2f, pathEffect = dash)
            drawLine(guideColor, Offset(xStart, chartBottom), Offset(xEnd, chartBottom), 1.2f, pathEffect = dash)

            // Continuous opacity-depth curve: y plots OPACITY (1−fog), not fog.
            // fog=0 (full opacity) → top; fog=1 (transparent) → bottom.
            val samples = 60
            val curve = Path().apply {
                for (i in 0..samples) {
                    val d = -3f + 6f * i / samples
                    val fog = depthCueFog(d, near, far)
                    val y = chartTop + fog * (chartBottom - chartTop)
                    val x = xStart + (xEnd - xStart) * i / samples
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(curve, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))

            depths.forEachIndexed { index, depth ->
                val fog = depthCueFog(depth, near, far)
                val y = chartTop + fog * (chartBottom - chartTop)
                drawCircle(lineColor, 2.4f, Offset(xPositions[index], y))
                drawPreviewSphere(Offset(xPositions[index], atomY), radius, appearance, fog, bgCompose)
            }

            val nc = drawContext.canvas.nativeCanvas
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isLight) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
                textSize = radius * 0.72f
            }
            // Opacity axis labels (1 = top/full opacity, 0 = bottom/transparent).
            nc.drawText("1", xStart - radius, chartTop + p.textSize * 0.35f, p)
            nc.drawText("0", xStart - radius, chartBottom + p.textSize * 0.35f, p)
            // Depth axis labels: only −3, 0, +3 (skip −1.5 and +1.5).
            val labelY = atomY + radius * 1.8f
            depths.forEachIndexed { index, depth ->
                if (depth == -1.5f || depth == 1.5f) return@forEachIndexed
                val label = depth.toInt().toString()
                val tw = p.measureText(label)
                nc.drawText(label, xPositions[index] - tw / 2f, labelY, p)
            }
        }
    }
}

/** Draws one lit preview sphere at [c] with radius [r], world-light highlight; colour blends
 *  toward [bg] by [fog] (opacity unchanged), mirroring the renderer's depth cueing.
 *  Per v0.7.0: sphere grey adapts to the theme (light grey on dark, dark grey on light). */

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPreviewSphere(
    c: Offset, r: Float, appearance: ViewerAppearance, fog: Float, bg: Color,
) {
    // Per v0.7.0: fixed dark grey sphere regardless of theme.
    val base = Color(AppPalette.SPHERE_GRAY)
    drawCircle(base.blend(bg, fog), r, c)
    if (appearance.reflectionEnabled) {
        val theta = appearance.lightAzimuth / 180f * PI.toFloat()
        val phi = appearance.lightElevation / 180f * PI.toFloat()
        val cosPhi = cos(phi)
        val highlight = Offset(c.x + cos(theta) * cosPhi * r * .95f, c.y - sin(theta) * cosPhi * r * .95f)
        val highlightBrush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = appearance.lightIntensity.coerceIn(.05f, 1f) * (1f - fog)), Color.Transparent),
            center = highlight,
            radius = r * (0.35f + 1.65f * appearance.diffusion),
        )
        drawCircle(highlightBrush, r, c)
    }
    drawCircle(Color.Black.copy(alpha = .3f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
}

/**
 * Linear fog on the normalized supercell depth scale (-5 far, +5 near). Returns fog in 0..1
 * (0 = near/no fade, 1 = far/fully faded). Mirrors [CrystalViewport.dofFog].
 */

private fun depthCueFog(d: Float, near: Float, far: Float): Float {
    if (near <= far) return if (d >= near) 0f else 1f
    if (d >= near) return 0f
    if (d <= far) return 1f
    return ((near - d) / (near - far)).coerceIn(0f, 1f)
}

/** Per v0.5.3a: blend this colour toward [target] by [t] (0..1). Mirrors the renderer's blend. */

private fun Color.blend(target: Color, t: Float) = Color(
    red + (target.red - red) * t,
    green + (target.green - green) * t,
    blue + (target.blue - blue) * t,
    alpha,
)

private fun androidx.compose.ui.graphics.Color.isLight(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) > 0.5f
