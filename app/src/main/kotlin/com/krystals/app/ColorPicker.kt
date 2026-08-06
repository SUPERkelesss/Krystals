@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.krystals.app

import com.krystals.crystal.analysis.editing.*
import com.krystals.crystal.analysis.model.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.sqrt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ColorPickerDialog(initialArgb: Long, onDismiss: () -> Unit, onColorSelected: (Long) -> Unit) {
    val initialHsv = remember(initialArgb) { argbToHsv(initialArgb) }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    val density = LocalDensity.current
    val wheelSize = 220.dp
    val wheelPx = with(density) { wheelSize.toPx() }
    val radiusPx = wheelPx / 2f

    fun updateFromPosition(position: Offset) {
        val center = Offset(radiusPx, radiusPx)
        val dx = position.x - center.x
        val dy = position.y - center.y
        val dist = sqrt(dx * dx + dy * dy)
        saturation = (dist / radiusPx).coerceIn(0f, 1f)
        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (degrees < 0) degrees += 360f
        hue = degrees
    }

    val selectedColor = remember(hue, saturation, value) {
        hsvToArgb(0xFF, floatArrayOf(hue, saturation, value))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("选择颜色", "Choose color")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(wheelSize)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                updateFromPosition(down.position)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    updateFromPosition(change.position)
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(
                            brush = Brush.sweepGradient(
                                RAINBOW_SWEEP_COLORS,
                                center = center,
                            ),
                            radius = radius,
                            center = center,
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(Color.White, Color.Transparent),
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                        )
                        val angleRad = Math.toRadians(hue.toDouble())
                        val ix = center.x + saturation * radius * cos(angleRad).toFloat()
                        val iy = center.y + saturation * radius * sin(angleRad).toFloat()
                        drawCircle(Color.Black, 8f, Offset(ix, iy), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                        drawCircle(Color.White, 6f, Offset(ix, iy), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                    }
                }
                Text(localized("亮度", "Brightness"), modifier = Modifier.padding(top = 8.dp))
                Slider(value = value, onValueChange = { value = it }, valueRange = 0f..1f)
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(48.dp)
                        .background(colorFromArgb(selectedColor), RoundedCornerShape(8.dp))
                )
            }
        },
        confirmButton = { TextButton(onClick = { onColorSelected(selectedColor); onDismiss() }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


private fun argbToHsv(argb: Long): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb.toInt(), hsv)
    return hsv
}


private fun hsvToArgb(alpha: Int, hsv: FloatArray): Long {
    return android.graphics.Color.HSVToColor(alpha, hsv).toLong() and 0xFFFFFFFFL
}


@Composable
internal fun DropdownField(label: String, value: String, options: List<String>, onValue: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth().clickable { open = true })
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { open = false; onValue(option) }) } }
    }
}


internal val RAINBOW_SWEEP_COLORS = listOf(
    Color.Red, Color(0xFFFFA500), Color.Yellow,
    Color.Green, Color.Cyan, Color.Blue,
    Color.Magenta, Color.Red,
)

/** Per v0.5.3a: true when this color reads as light (luminance above mid-grey). */
