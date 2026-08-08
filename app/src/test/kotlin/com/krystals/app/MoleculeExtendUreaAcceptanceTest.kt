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
import kotlin.test.assertNotNull
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

        // 4) 全方向规则:每个与 [0,1]³ 相交的分子映像(按 t ∈ [ceil(-max), floor(1-min)]
        //    独立计算,不限正负)的每个原子位置 ±0.05A 都有可见球;可见完整映像总数 = 8
        //    (2 物理分子 × 4 映像),映像原子 64 全可见。z≈0.028 处允许有球(分子 B 的
        //    H2 包裹 primary),但它必须属于一个完整映像(该映像 8 原子全有球)。
        val images = molecules.flatMap { intersectingImages(it, structure) }
        println("urea: images=${images.size} imageAtoms=${images.sumOf { it.size }}")
        assertEquals(8, images.size, "与单胞相交的映像总数 = 8")
        assertEquals(64, images.sumOf { it.size }, "映像原子总数 = 64")
        val missingImageAtoms = images.flatten().count { (_, p) ->
            visAtoms.none { distance(it.atom.cartesianCoordinate.toVec3(), p) < 0.05 }
        }
        println("urea: missingImageAtoms=$missingImageAtoms (0 = every intersecting image atom has a sphere)")
        assertEquals(0, missingImageAtoms, "每个相交映像的每个原子位置都应有可见球")
        val z028 = visAtoms.filter { ins ->
            val f = structure.lattice.toFractional(
                CartesianCoordinate(
                    ins.atom.cartesianCoordinate.x,
                    ins.atom.cartesianCoordinate.y,
                    ins.atom.cartesianCoordinate.z,
                ),
            )
            val zw = f.z - Math.floor(f.z)
            zw in 0.02..0.03
        }
        println("urea: visible spheres with wrapped z~0.028 = ${z028.size}")
        for (ins in z028) {
            val p = ins.atom.cartesianCoordinate.toVec3()
            val img = images.firstOrNull { im -> im.any { (_, q) -> distance(q, p) < 0.05 } }
            assertNotNull(img, "z~0.028 的球必须属于某个完整映像")
            val uncovered = img.count { (_, q) ->
                visAtoms.none { distance(it.atom.cartesianCoordinate.toVec3(), q) < 0.05 }
            }
            assertEquals(0, uncovered, "z~0.028 的球所在映像的 8 原子必须全有球")
        }
    }

    /** 按新规则独立计算分子 m 与 [0,1]³ 显示区相交的全部周期映像(每映像 = 原子位置列表)。 */
    private fun intersectingImages(
        m: com.krystals.crystal.core.model.Molecule,
        structure: com.krystals.crystal.core.model.CrystalStructure,
    ): List<List<Pair<Int, Vec3>>> {
        return connected(m).flatMap { comp ->
            val fr = comp.map {
                it.id to structure.lattice.toFractional(CartesianCoordinate(it.position.x, it.position.y, it.position.z))
            }
            val minX = fr.minOf { it.second.x }; val maxX = fr.maxOf { it.second.x }
            val minY = fr.minOf { it.second.y }; val maxY = fr.maxOf { it.second.y }
            val minZ = fr.minOf { it.second.z }; val maxZ = fr.maxOf { it.second.z }
            val txRange = kotlin.math.ceil(-maxX + 1e-6).toInt()..kotlin.math.floor(1.0 - minX - 1e-6).toInt()
            val tyRange = kotlin.math.ceil(-maxY + 1e-6).toInt()..kotlin.math.floor(1.0 - minY - 1e-6).toInt()
            val tzRange = kotlin.math.ceil(-maxZ + 1e-6).toInt()..kotlin.math.floor(1.0 - minZ - 1e-6).toInt()
            val result = mutableListOf<List<Pair<Int, Vec3>>>()
            for (tx in txRange) for (ty in tyRange) for (tz in tzRange) {
                result += fr.map { (id, f) ->
                    val shifted = structure.lattice.toCartesian(FractionalCoordinate(f.x + tx, f.y + ty, f.z + tz))
                    id to Vec3(shifted.x, shifted.y, shifted.z)
                }
            }
            result
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
