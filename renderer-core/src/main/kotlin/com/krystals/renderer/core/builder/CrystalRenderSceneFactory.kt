package com.krystals.renderer.core.builder

import com.krystals.crystal.analysis.bonding.BondNetwork
import com.krystals.renderer.core.material.Material
import com.krystals.renderer.core.scene.RenderScene
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.RenderPalette
import com.krystals.renderer.core.style.ViewerAppearance

object CrystalRenderSceneFactory {
    fun build(
        analysis: BondNetwork,
        appearance: ViewerAppearance,
        renderConfiguration: RenderConfiguration,
        hiddenSiteIds: Set<String> = emptySet(),
        hiddenBondKeys: Set<String> = emptySet(),
        showBonds: Boolean = true,
        polyhedronSiteIds: Set<String> = emptySet(),
        structuralExpansion: Boolean = false,
    ): RenderScene {
        val atoms = analysis.atoms
        val atomMaterials = atoms.associate { atom ->
            atom.siteId to Material(
                argb = RenderPalette.resolveSiteArgb(atom.siteId, atom.species.symbol, renderConfiguration),
                opacity = appearance.atomOpacity.toDouble(),
                reflective = appearance.reflectionEnabled,
            )
        }
        val bondMaterials = atoms.associate { atom ->
            atom.siteId to Material(
                argb = RenderPalette.resolveSiteArgb(atom.siteId, atom.species.symbol, renderConfiguration),
                opacity = appearance.bondOpacity.toDouble(),
                reflective = appearance.bondReflectionEnabled,
            )
        }
        val polyhedronMaterials = atoms.associate { atom ->
            atom.siteId to Material(
                argb = RenderPalette.resolveSiteArgb(atom.siteId, atom.species.symbol, renderConfiguration),
                opacity = appearance.polyhedronOpacity.toDouble(),
                reflective = appearance.polyhedronReflectionEnabled,
                doubleSided = true,
            )
        }
        return CrystalSceneBuilder().build(
            structure = analysis.structure,
            analysis = analysis,
            options = SceneBuildOptions(
                hiddenSiteIds = hiddenSiteIds,
                hiddenBondKeys = hiddenBondKeys,
                showBonds = showBonds,
                // Polyhedra are selected in the display panel; appearance only controls material.
                polyhedronSiteIds = polyhedronSiteIds,
                atomRadiusByElement = atoms.associate { it.species.symbol to RenderPalette.defaultRadius(it.species.symbol) },
                atomMaterialBySite = atomMaterials,
                bondMaterialBySite = bondMaterials,
                polyhedronMaterialBySite = polyhedronMaterials,
                defaultBondMaterial = Material(
                    argb = appearance.uniformBondArgb,
                    opacity = appearance.bondOpacity.toDouble(),
                    reflective = appearance.bondReflectionEnabled,
                ),
                bondRadius = appearance.bondRadius.toDouble(),
                bondColorMode = appearance.bondColorMode,
                environment = appearance.toEnvironment(),
                structuralExpansion = structuralExpansion,
            ),
        )
    }
}
