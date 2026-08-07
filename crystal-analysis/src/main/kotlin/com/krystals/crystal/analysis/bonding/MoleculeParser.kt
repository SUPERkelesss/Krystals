package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.Molecule
import com.krystals.crystal.core.model.MoleculeAtom
import com.krystals.crystal.core.model.MoleculeBond
import com.krystals.crystal.core.periodic.Int3

private operator fun Int3.plus(o: Int3) = Int3(x + o.x, y + o.y, z + o.z)

/**
 * 把分子晶体键网络解析为有限分子列表:每个连通分量一个 [Molecule](原子与键并列)。
 *
 * 复用 [toCellBondGraph] 的共享归一化图(排除氢键、shell/边界原子归一化回原胞
 * 代表),对每个未访问原胞原子开新分子,BFS 沿键展开并累积物理晶胞偏移
 * (根 = (0,0,0),子 = 父 + 键偏移)。跨晶胞键两端坐标因此自动落在相邻晶胞,
 * 分子空间自洽、键长正确 —— 无需给 [MoleculeBond] 增加偏移字段,不动 crystal-core。
 *
 * 输出与 [com.krystals.crystal.analysis.model.Expansion] 无关;孤立原子(无键)
 * 同样产单原子 [Molecule]。键级:当前 [Bond] 无键级概念,一律 1.0。结果适用于
 * 分子晶体(isMolecularCrystal() == true 的网络);周期自连接等无限网络的自环边
 * (原子与其平移映像)在分子内无意义,跳过。
 */
fun BondNetwork.toMolecules(): List<Molecule> {
    val g = toCellBondGraph()
    val byId = g.cellAtoms.associateBy { it.id }
    // 连通分量:每个物理分子一个分量(原子 + 物理位置 + 键)。
    class Component(val atoms: List<AtomImage>, val pos: Map<Long, Int3>, val bonds: List<Pair<Int, Int>>)
    val components = mutableListOf<Component>()
    val visited = HashSet<Long>()
    for (root in g.cellAtoms) {
        if (root.id in visited) continue
        val pos = HashMap<Long, Int3>()
        pos[root.id] = Int3(0, 0, 0)
        val componentAtoms = mutableListOf<AtomImage>()
        val bondPairs = HashSet<Pair<Int, Int>>()
        val stack = ArrayDeque<Long>()
        stack.addLast(root.id)
        visited.add(root.id)
        while (stack.isNotEmpty()) {
            val u = stack.removeLast()
            componentAtoms += byId.getValue(u)
            val pu = pos.getValue(u)
            for ((v, off) in g.edges[u] ?: continue) {
                // 分子内键按 (min, max) 原子对去重(双向边会被两端各记录一次)。
                if (u != v) bondPairs += minOf(u, v).toInt() to maxOf(u, v).toInt()
                if (v !in visited) {
                    pos[v] = pu + off
                    visited.add(v)
                    stack.addLast(v)
                }
            }
        }
        components += Component(componentAtoms, pos, bondPairs.toList())
    }
    // 按"原子位点组合"(site 集合)分组:一个分子项对应一个位点组合,合并所有可通过
    // 对称操作重合的物理分子 —— 如 A、B 两 site 组成的二聚体,晶胞内无论有多少个
    // A-B 分子,只生成一个分子项;其开关控制该位点组合的全部原子与键。
    val grouped = LinkedHashMap<List<String>, MutableList<Component>>()
    for (comp in components) {
        val key = comp.atoms.map { it.siteId }.distinct().sorted()
        grouped.getOrPut(key) { mutableListOf() } += comp
    }
    val molecules = mutableListOf<Molecule>()
    for ((_, comps) in grouped) {
        val firstAtoms = comps.first().atoms.map { a ->
            MoleculeAtom(
                id = a.id.toInt(),
                label = a.siteLabel,
                species = a.species,
                position = structure.lattice.toCartesian(a.fractionalCoordinate + comps.first().pos.getValue(a.id)),
                siteId = a.siteId,
            )
        }
        val allAtoms = comps.flatMap { comp ->
            comp.atoms.map { a ->
                MoleculeAtom(
                    id = a.id.toInt(),
                    label = a.siteLabel,
                    species = a.species,
                    position = structure.lattice.toCartesian(a.fractionalCoordinate + comp.pos.getValue(a.id)),
                    siteId = a.siteId,
                )
            }
        }
        val allBonds = comps.flatMap { it.bonds }.distinct()
        molecules += Molecule(
            // 名称用单个分子的组成式(对称等价分子拓扑相同),而非合并后的原子总数。
            name = formulaFor(firstAtoms).ifEmpty { "Molecule ${molecules.size + 1}" },
            atoms = allAtoms,
            bonds = allBonds.map { (from, to) -> MoleculeBond(from, to, order = 1.0) },
        )
    }
    return molecules
}

/**
 * 组成化学式(Hill 式:碳最前、氢次之,其余按 [PeriodicTable.symbols] 顺序),
 * 计数 > 1 才写数字(如 "H2O"、"C6H6"、"P4")。未知元素按计数降序兜底。
 */
private fun formulaFor(atoms: List<MoleculeAtom>): String {
    val counts = HashMap<String, Int>()
    for (atom in atoms) counts[atom.species.symbol] = (counts[atom.species.symbol] ?: 0) + 1
    val symbols = PeriodicTable.symbols
    val byHillOrder = compareBy<Map.Entry<String, Int>> { (symbol, _) ->
        when (symbol) {
            "C" -> 0
            "H" -> 1
            else -> symbols.indexOf(symbol).let { index -> if (index < 0) Int.MAX_VALUE else index + 2 }
        }
    }
    return counts.entries
        .sortedWith(byHillOrder.thenByDescending { it.value })
        .joinToString("") { (symbol, count) -> symbol + if (count > 1) count.toString() else "" }
}
