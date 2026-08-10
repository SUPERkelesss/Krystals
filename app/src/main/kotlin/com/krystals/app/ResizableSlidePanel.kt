package com.krystals.app

import androidx.core.content.edit
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Per v0.7.0: a slide-in side/bottom panel with a draggable resize handle and a per-orientation
 * persisted size ratio. Shared by EditorPanel, CommentsPanel and DisplayPanel — the panel chrome
 * (overlay scrim, slide animation, drag handle, portrait/landscape layout, ratio persistence) is
 * implemented once here; callers only supply their content.
 *
 * [content] receives [closePanel] so in-panel close buttons can run the same animated dismiss
 * (fade-out + delayed [onDismiss]) as tapping the scrim.
 */
@Composable
fun ResizableSlidePanel(
    ratioKey: String,
    defaultRatio: Float,
    onDismiss: () -> Unit,
    content: @Composable (closePanel: () -> Unit) -> Unit,
) {
    // Per v0.7.0: slide-in animation state.
    var visible by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val slideProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(150, easing = FastOutSlowInEasing),
        label = "panelSlide",
    )
    LaunchedEffect(visible) {
        if (!visible && dismissed) { delay(150); onDismiss() }
    }
    fun closePanel() { dismissed = true; visible = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val panelPrefs = LocalContext.current.getSharedPreferences("panel_sizes", android.content.Context.MODE_PRIVATE)
        val prefKey = "${ratioKey}_${if (landscape) "landscape" else "portrait"}"
        var panelRatio by remember(landscape) { mutableFloatStateOf(panelPrefs.getFloat(prefKey, defaultRatio)) }
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = slideProgress }.background(Color.Black.copy(alpha = 0.22f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::closePanel,
            ),
        )
        val panelModifier = if (landscape) {
            Modifier.fillMaxHeight().fillMaxWidth(panelRatio).align(Alignment.CenterEnd)
                .graphicsLayer { translationX = (1f - slideProgress) * widthPx }
        } else {
            Modifier.fillMaxWidth().fillMaxHeight(panelRatio).align(Alignment.BottomCenter)
                .graphicsLayer { translationY = (1f - slideProgress) * heightPx }
        }
        Surface(
            tonalElevation = 8.dp,
            modifier = panelModifier,
        ) {
            // Per v0.3.3: in landscape the drag handle is a vertical bar on the left, so the panel
            // content sits beside it in a Row (a Column with a fillMaxHeight first child would leave
            // no height for the tabs/content — the cause of the blank landscape panel).
            val handleModifier = if (landscape) {
                Modifier.fillMaxHeight().width(12.dp)
            } else {
                Modifier.fillMaxWidth().height(12.dp)
            }
            val dividerModifier = if (landscape) {
                Modifier.fillMaxHeight().width(9.dp)
            } else {
                Modifier.fillMaxWidth().height(9.dp)
            }
            val handle = @Composable {
                // Per v0.7.0: 24.dp drag hit target (the visible divider stays pinned to the panel
                // edge, pre-v0.7.0 style); persist the ratio once when the drag ends (or is
                // cancelled) instead of writing SharedPreferences on every drag frame.
                // Per v0.7.0: handle/divider halved to 12.dp / 9.dp per user request.
                Box(
                    Modifier
                        .then(handleModifier)
                        .pointerInput(landscape) {
                            detectDragGestures(
                                onDragEnd = { panelPrefs.edit { putFloat(prefKey, panelRatio) } },
                                onDragCancel = { panelPrefs.edit { putFloat(prefKey, panelRatio) } },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (landscape) {
                                        panelRatio = (panelRatio - amount.x / widthPx).coerceIn(0.2f, 0.95f)
                                    } else {
                                        panelRatio = (panelRatio - amount.y / heightPx).coerceIn(0.2f, 0.95f)
                                    }
                                },
                            )
                        },
                ) {
                    // Per v0.7.0: pin the visible divider to the panel edge (as pre-v0.7.0) so no
                    // background strip shows above it; only the drag hit target is 24.dp.
                    Box(
                        Modifier.then(dividerModifier)
                            // In Compose 1.11 the 1-D Alignment.Start is an Alignment.Horizontal that
                            // is NOT an Alignment, so BoxScope.align() rejects it. CenterStart/TopStart
                            // are declared Alignment; the divider fills the other axis so its
                            // alignment on that axis is irrelevant.
                            .align(if (landscape) Alignment.CenterStart else Alignment.TopStart)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    handle()
                    Box(Modifier.weight(1f).fillMaxHeight()) { content(::closePanel) }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    handle()
                    content(::closePanel)
                }
            }
        }
    }
}
