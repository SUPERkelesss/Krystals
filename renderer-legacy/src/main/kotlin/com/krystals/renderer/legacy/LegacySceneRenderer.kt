package com.krystals.renderer.legacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.krystals.crystal.core.model.AtomImage
import com.krystals.interaction.selection.PickResult
import com.krystals.interaction.selection.Picker
import com.krystals.interaction.state.InteractionState
import com.krystals.interaction.state.ViewerCommand
import com.krystals.renderer.core.SceneRenderer
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance

/**
 * Adapter that exposes the Canvas-Legacy backend through the shared [SceneRenderer] SPI.
 *
 * Because the legacy renderer is Compose-based, rendering is performed by the
 * [Content] composable. The app hosts this composable inside the unified [RendererHost].
 *
 * Scene / interaction / appearance state are held as Compose [MutableState] so that
 * updates from [submit]/[updateInteraction]/[configure] trigger recomposition of [Content].
 *
 * END OF LIFE: the Canvas-Legacy backend is no longer built or used by the app (which is
 * fixed on Filament). This module is retained in the repository for reference only — it is
 * excluded from settings.gradle.kts, not compiled, and receives no further maintenance.
 */
@Deprecated("Canvas-Legacy renderer is end-of-life; the app is fixed on Filament")
@Suppress("DEPRECATION")
class LegacySceneRenderer : SceneRenderer, Picker {
    private val controller = ViewerController()
    private val sceneState = mutableStateOf<RenderScene?>(null)
    private val interactionState = mutableStateOf<InteractionState?>(null)
    private val appearanceState = mutableStateOf<ViewerAppearance?>(null)
    private val renderConfigurationState = mutableStateOf<RenderConfiguration?>(null)
    private val bondValenceState = mutableStateOf<Map<String, Double>>(emptyMap())
    private var onCommand: ((ViewerCommand) -> Unit)? = null
    private var onAtomTap: ((AtomImage) -> Boolean)? = null

    fun configure(
        appearance: ViewerAppearance,
        renderConfiguration: RenderConfiguration,
        bondValenceBySite: Map<String, Double>,
        onCommand: (ViewerCommand) -> Unit,
        onAtomTap: (AtomImage) -> Boolean,
    ) {
        appearanceState.value = appearance
        renderConfigurationState.value = renderConfiguration
        bondValenceState.value = bondValenceBySite
        this.onCommand = onCommand
        this.onAtomTap = onAtomTap
    }

    override fun submit(scene: RenderScene) {
        sceneState.value = scene
    }

    override fun updateInteraction(state: InteractionState) {
        interactionState.value = state
        // Bind the controller to the latest session so its local pick() and legacy
        // fallback paths use the current camera/viewport state.
        controller.bind(state.session) {}
    }

    override fun clear() {
        sceneState.value = null
    }

    override suspend fun pick(x: Float, y: Float): PickResult? =
        controller.pick(x, y)

    @Composable
    fun Content(modifier: Modifier = Modifier) {
        val currentScene = sceneState.value ?: return
        val currentState = interactionState.value ?: return
        val currentAppearance = appearanceState.value ?: return
        val currentRenderConfiguration = renderConfigurationState.value ?: return
        val currentBondValence = bondValenceState.value
        CrystalViewport(
            scene = currentScene,
            interactionState = currentState,
            onCommand = { command ->
                val sink = onCommand
                if (sink != null) {
                    sink(command)
                } else {
                    controller.dispatch(command)
                }
            },
            appearance = currentAppearance,
            renderConfiguration = currentRenderConfiguration,
            bondValenceBySite = currentBondValence,
            onAtomTap = { atom -> onAtomTap?.invoke(atom) ?: false },
            modifier = modifier,
        )
    }

    override fun close() {
        clear()
    }
}
