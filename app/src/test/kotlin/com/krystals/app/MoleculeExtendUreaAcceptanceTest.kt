package com.krystals.app

import com.krystals.crystal.analysis.bonding.BondDetector
import com.krystals.crystal.analysis.bonding.toMolecules
import com.krystals.crystal.analysis.model.Expansion
import com.krystals.crystal.core.coordinate.CartesianCoordinate
import com.krystals.crystal.core.coordinate.FractionalCoordinate
import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.math.distance
import com.krystals.crystal.io.CifCodec
import com.krystals.renderer.core.builder.CrystalRenderSceneFactory
import com.krystals.renderer.core.primitive.AtomInstance
import com.krystals.renderer.core.primitive.BondInstance
import com.krystals.renderer.core.style.RenderConfiguration
import com.krystals.renderer.core.style.ViewerAppearance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoleculeExtendUreaAcceptanceTest {

    private fun parse(rel: String): com.krystals.crystal.io.ParsedStructure {
        val dir = sequenceOf(File("../res/cifs_example"), File("res/cifs_example"))
            .firstOrNull { it.isDirectory } ?: error("corpus missing")
        return CifCodec.parseStructure(File(dir, rel).readText())
    }

    @Test
    fun ureaMoleculeExtendShowsEveryMoleculeAtomAndBond() {
        val parsed = parse("08_molecular/urea_CH4N2O.cif")
        val structure = parsed.structure
        val net = BondDetector.buildNetwork(structure, parsed.bondConfiguration, Expansion(1, 1, 1))
        val molecules = net.toMolecules()
        val molAtoms = molecules.flatMap { it.atoms }
        val molBonds = molecules.flatMap { it.bonds }
        println("urea: molEntries=${molecules.map { it.name to it.atoms.size }} atoms=${molAtoms.size} bonds=${molBonds.size}")
        assertEquals(16, molAtoms.size, "2 个尿素分子共 16 原子")
        assertEquals(14, molBonds.size, "2 个尿素分子共 14 键(7 键/分子:C=O + 2C-N + 4N-H)")

        val scene = CrystalRenderSceneFactory.build(
            analysis = net,
            appearance = ViewerAppearance(),
            renderConfiguration = RenderConfiguration(),
            moleculeExtend = true,
            molecules = molecules,
        )
        val visAtoms = scene.objects.filterIsInstance<AtomInstance>().filter { it.visible }
        val visBonds = scene.objects.filterIsInstance<BondInstance>().filter { it.visible }

        // 1) 每个分子原子位置(物理)±0.05A 有可见球 —— 含跨近侧(负 frac)的原子
        //    (如 H2 的 z=-0.028 壳层球、N1 的 x=-0.144 壳层球)。
        val posById = HashMap<Int, Vec3>()
        for (m in molecules) for (ma in m.atoms) posById[ma.id] = ma.position.toVec3()
        val missing = molAtoms.count { ma ->
            val p = posById.getValue(ma.id)
            visAtoms.none { distance(it.atom.cartesianCoordinate.toVec3(), p) < 0.05 }
        }
        println("urea: missingAtoms=$missing (0 = every molecule atom sphere visible)")
        assertEquals(0, missing, "每个分子原子位置都应有可见球(含跨胞部分)")

        // 2) 每个分子键两端(±0.05A,双向)有可见键。
        var missingBonds = 0
        for (mb in molBonds) {
            val pa = posById.getValue(mb.from)
            val pb = posById.getValue(mb.to)
            val hit = visBonds.any { b ->
                (distance(b.start, pa) < 0.05 && distance(b.end, pb) < 0.05) ||
                    (distance(b.start, pb) < 0.05 && distance(b.end, pa) < 0.05)
            }
            if (!hit) missingBonds++
        }
        println("urea: moleculeBonds=${molBonds.size} missingBonds=$missingBonds")
        assertEquals(0, missingBonds, "每个分子键都应有可见场景键")

        // 3) 每分量(物理分子)的全部 8 原子球覆盖 —— 画面里 2 个尿素分子完整。
        val comps = connected(molecules[0])
        assertEquals(2, comps.size, "尿素条目 = 2 个物理分子")
        comps.forEach { comp ->
            val uncovered = comp.count { ma ->
                val p = posById.getValue(ma.id)
                visAtoms.none { distance(it.atom.cartesianCoordinate.toVec3(), p) < 0.05 }
            }
            println("urea: component atoms=${comp.size} uncovered=$uncovered")
            assertEquals(0, uncovered, "每个物理分子 8 原子球全显示")
        }
    }

    private fun connected(m: com.krystals.crystal.core.model.Molecule): List<List<com.krystals.crystal.core.model.MoleculeAtom>> {
        val atomById = m.atoms.associateBy { it.id }
        val adj = HashMap<Int, MutableList<Int>>()
        for (b in m.bonds) {
            adj.getOrPut(b.from) { mutableListOf() } += b.to
            adj.getOrPut(b.to) { mutableListOf() } += b.from
        }
        val visited = HashSet<Int>()
        val comps = mutableListOf<List<com.krystals.crystal.core.model.MoleculeAtom>>()
        for (a in m.atoms) {
            if (!visited.add(a.id)) continue
            val comp = mutableListOf<com.krystals.crystal.core.model.MoleculeAtom>()
            val stack = ArrayDeque<Int>(); stack.add(a.id)
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                comp += atomById.getValue(id)
                for (n in adj[id].orEmpty()) if (visited.add(n)) stack.add(n)
            }
            comps += comp
        }
        return comps
    }
}
