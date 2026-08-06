package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.periodic.PeriodicBoundary

private val ZERO_OFFSET = Int3(0, 0, 0)

private operator fun Int3.plus(o: Int3) = Int3(x + o.x, y + o.y, z + o.z)
private operator fun Int3.minus(o: Int3) = Int3(x - o.x, y - o.y, z - o.z)
private operator fun Int3.unaryMinus() = Int3(-x, -y, -z)

/**
 * 原胞归一化键图:把键网络(含 shell/边界映像)折叠回原胞原子集合上的周期图。
 *
 * 节点 = 原胞原子(cellOffset == (0,0,0) 的非 shell 原子);边 = (邻居原胞原子,
 * 周期偏移)。键的周期偏移 = 两端真实晶胞坐标之差,归一化使跨晶胞键的端点闭合
 * 回原胞原子 —— 否则单胞内链段的跨晶胞键不会成环,无限聚合物会被误判为分子。
 *
 * 氢键([Bond.rule].[BondRule.isHBond])是分子间弱相互作用,不参与分子判据,
 * 构建时直接排除。原子与其平移映像成键(周期自连接)时保留自环边
 * (同 id、偏移非零),由调用方判定是否非法。
 */
internal data class CellBondGraph(
    val cellAtoms: List<AtomImage>,               // 原胞原子(归一化目标节点)
    val edges: Map<Long, List<Pair<Long, Int3>>>, // 原子 id → (邻居原胞原子 id, 周期偏移)
)

/** 构建 [CellBondGraph]:排除氢键,把所有原子归一化到原胞代表,边带周期偏移。 */
internal fun BondNetwork.toCellBondGraph(): CellBondGraph {
    val byId = atoms.associateBy { it.id }
    // 原胞原子:非壳且位于零晶胞偏移 —— 归一化的目标节点集合。
    val cellAtoms = atoms.filter { !it.isShell && it.cellOffset == ZERO_OFFSET }
    val repBySite = cellAtoms.groupBy { it.siteId }

    /** 把任意原子(含 shell/边界映像)映射回原胞代表原子;找不到则返回 null。 */
    fun rep(x: AtomImage): AtomImage? {
        val candidates = repBySite[x.siteId] ?: return null
        return candidates.firstOrNull {
            PeriodicBoundary.isIntegerTranslation(x.fractionalCoordinate - it.fractionalCoordinate)
        }
    }

    val edges = HashMap<Long, MutableList<Pair<Long, Int3>>>()
    for (bond in bonds) {
        // 氢键是分子间弱作用,不参与分子晶体判据(冰/尿素等氢键晶体仍为分子晶体)。
        if (bond.rule.isHBond) continue
        val u = byId[bond.atomA] ?: continue
        val v = byId[bond.atomB] ?: continue
        val ru = rep(u) ?: continue
        val rv = rep(v) ?: continue
        val off = v.cellOffset - u.cellOffset
        if (ru.id == rv.id) {
            // 周期自连接(如金属密堆积):保留自环边,由调用方判定是否非法。
            edges.getOrPut(ru.id) { mutableListOf() } += ru.id to off
            continue
        }
        edges.getOrPut(ru.id) { mutableListOf() } += rv.id to off
        edges.getOrPut(rv.id) { mutableListOf() } += ru.id to -off
    }
    return CellBondGraph(cellAtoms, edges)
}

/**
 * 判断键网络是否为分子晶体(molecular crystal)。
 *
 * 判据:分子晶体中每个分子是有限团簇,分子间没有共价键,因此把键网络提升到周期
 * 空间(晶胞偏移 [Int3])后,每个连通分量都是有限的;反之,共价框架、聚合物链、
 * 离子/金属网络会沿周期方向无限延伸。氢键([Bond.rule].[BondRule.isHBond])是
 * 分子间弱相互作用,不参与判据 —— 冰、尿素等氢键晶体仍视为分子晶体。
 *
 * 算法(对单个晶胞内键网络,基于共享的 [toCellBondGraph] 归一化图):
 * 1. 归一化图把跨晶胞键的端点闭合回原胞原子,边带周期偏移。
 * 2. 对每个连通分量做生成树(迭代 DFS);每条非树边与树路径构成一个基本环。
 * 3. 若某基本环沿周期方向的偏移和不为零(如 (0,0,0) -> (0,0,1) 的跨晶胞环),
 *    或原子与其平移映像直接成键(自环边、周期自连接),键网络无限延伸 ——
 *    立即返回 false。所有分量通过检查则返回 true。
 *
 * 结果与 [com.krystals.crystal.analysis.model.Expansion] 无关(归一化后等价于
 * 单胞键网络)。孤立原子(无键)视为单原子分子,贡献 true。
 */
fun BondNetwork.isMolecularCrystal(): Boolean {
    val g = toCellBondGraph()
    val pos = HashMap<Long, Int3>()
    for (root in g.cellAtoms) {
        if (root.id in pos) continue
        pos[root.id] = ZERO_OFFSET
        val stack = ArrayDeque<Long>()
        stack.addLast(root.id)
        while (stack.isNotEmpty()) {
            val u = stack.removeLast()
            val pu = pos.getValue(u)
            for ((v, off) in g.edges[u] ?: continue) {
                val pv = pos[v]
                if (pv == null) {
                    pos[v] = pu + off
                    stack.addLast(v)
                } else if (pu + off != pv) {
                    // 含自环(ru==rv、off≠0)与跨晶胞基本环:无限延伸,非分子晶体。
                    return false
                }
            }
        }
    }
    return true
}
