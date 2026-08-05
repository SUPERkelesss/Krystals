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
        // Material colour depends only on the site id, and radius only on the element symbol —
        // deduplicate expanded atoms before building these tables so repeated symmetry images
        // don't re-resolve the same palette lookup (N atoms → M unique sites / K unique elements).
        val uniqueSiteAtoms = atoms.distinctBy { it.siteId }
        val uniqueElementSymbols = atoms.distinctBy { it.species.symbol }
        val atomMaterials = uniqueSiteAtoms.associate { atom ->
            atom.siteId to Material(
                argb = RenderPalette.resolveSiteArgb(atom.siteId, atom.species.symbol, renderConfiguration),
                opacity = appearance.atomOpacity.toDouble(),
                reflective = appearance.reflectionEnabled,
            )
        }
        val bondMaterials = uniqueSiteAtoms.associate { atom ->
            atom.siteId to Material(
                argb = RenderPalette.resolveSiteArgb(atom.siteId, atom.species.symbol, renderConfiguration),
                opacity = appearance.bondOpacity.toDouble(),
                reflective = appearance.bondReflectionEnabled,
            )
        }
        val polyhedronMaterials = uniqueSiteAtoms.associate { atom ->
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
                atomRadiusByElement = uniqueElementSymbols.associate { it.species.symbol to RenderPalette.defaultRadius(it.species.symbol) },
                atomMaterialBySite = atomMaterials,
                bondMaterialBySite = bondMaterials,
                polyhedronMaterialBySite = polyhedronMaterials,
                defaultBondMaterial = Material(
                    argb = appearance.uniformBondArgb,
                    opacity = appearance.bondOpacity.toDouble(),
                    reflective = appearance.bondReflectionEnabled,
                ),
                bondRadius = appearance.bondRadius.toDouble(),
                hbondRadius = appearance.hbondRadius.toDouble(),
                hbondOpacity = appearance.hbondOpacity.toDouble(),
                secondaryExtendBonds = appearance.secondaryExtendBonds,
                bondColorMode = appearance.bondColorMode,
                environment = appearance.toEnvironment(),
                structuralExpansion = structuralExpansion,
            ),
        )
    }
}
