package com.krystals.renderer.legacy

import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.renderer.core.builder.CrystalSceneBuilder
import com.krystals.renderer.core.builder.SceneBuildOptions
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.scene.RenderScene

/** Bridges the backend-neutral scene snapshot to the unchanged Canvas engine. */
object LegacyRenderSceneAdapter {
    fun build(
        analysis: BondNetwork,
        appearance: ViewerAppearance,
        renderConfiguration: RenderConfiguration,
        visibility: ViewerVisibility,
    ): RenderScene {
        val atoms = analysis.atoms
        val atomMaterials = atoms.associate { atom ->
            atom.siteId to Material(
                argb = RenderPalette.resolveSiteArgb(atom.siteId, atom.species.symbol, renderConfiguration),
                opacity = appearance.atomOpacity.toDouble().coerceIn(0.0, 1.0),
                reflective = appearance.reflectionEnabled,
            )
        }
        val polyhedronMaterials = atoms.associate { atom ->
            atom.siteId to Material(
                argb = RenderPalette.resolveSiteArgb(atom.siteId, atom.species.symbol, renderConfiguration),
                opacity = appearance.polyhedronOpacity.toDouble().coerceIn(0.0, 1.0),
                reflective = appearance.polyhedronReflectionEnabled,
                doubleSided = true,
            )
        }
        val radii = atoms.associate { atom ->
            atom.species.symbol to RenderPalette.defaultRadius(atom.species.symbol)
        }
        return CrystalSceneBuilder().build(
            structure = analysis.structure,
            analysis = analysis,
            options = SceneBuildOptions(
                hiddenSiteIds = visibility.hiddenSites,
                hiddenBondKeys = visibility.hiddenBondPairs,
                showBonds = visibility.showBonds,
                polyhedronSiteIds = if (appearance.polyhedronEnabled) visibility.polyhedronSites else emptySet(),
                atomRadiusByElement = radii,
                atomMaterialBySite = atomMaterials,
                polyhedronMaterialBySite = polyhedronMaterials,
                defaultBondMaterial = Material(
                    argb = appearance.uniformBondArgb,
                    opacity = appearance.bondOpacity.toDouble().coerceIn(0.0, 1.0),
                    reflective = appearance.bondReflectionEnabled,
                ),
                bondRadius = appearance.bondRadius.toDouble(),
            ),
        )
    }

    fun toBondNetwork(scene: RenderScene): BondNetwork = BondNetwork(
        atoms = scene.atoms.map { it.atom },
        bonds = scene.bonds.map { it.bond },
        structure = scene.structure,
        expansion = scene.expansion,
    )
}
