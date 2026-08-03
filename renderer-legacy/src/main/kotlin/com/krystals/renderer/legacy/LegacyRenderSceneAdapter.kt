package com.krystals.renderer.legacy

import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.scene.RenderScene

/** Bridges the backend-neutral scene snapshot to the unchanged Canvas engine. */
@Deprecated("Canvas-Legacy renderer is end-of-life; the app is fixed on Filament")
object LegacyRenderSceneAdapter {
    fun build(
        analysis: BondNetwork,
        appearance: ViewerAppearance,
        renderConfiguration: RenderConfiguration,
        visibility: ViewerVisibility,
        structuralExpansion: Boolean = false,
    ): RenderScene {
        return CrystalRenderSceneFactory.build(
            analysis = analysis,
            appearance = appearance,
            renderConfiguration = renderConfiguration,
            hiddenSiteIds = visibility.hiddenSites,
            hiddenBondKeys = visibility.hiddenBondPairs,
            showBonds = visibility.showBonds,
            polyhedronSiteIds = visibility.polyhedronSites,
            structuralExpansion = structuralExpansion,
        )
    }

    fun toBondNetwork(scene: RenderScene): BondNetwork = BondNetwork(
        atoms = scene.atoms.map { it.atom },
        bonds = scene.bonds.map { it.bond },
        structure = scene.structure,
        expansion = scene.expansion,
    )
}
