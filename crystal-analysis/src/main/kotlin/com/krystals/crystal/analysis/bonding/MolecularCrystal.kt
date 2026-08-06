package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.periodic.Int3
import com.krystals.crystal.core.periodic.PeriodicBoundary

private val ZERO_OFFSET = Int3(0, 0, 0)

private operator fun Int3.plus(o: Int3) = Int3(x + o.x, y + o.y, z + o.z)
private operator fun Int3.minus(o: Int3) = Int3(x - o.x, y - o.y, z - o.z)
private operator fun Int3.unaryMinus() = Int3(-x, -y, -z)

/**
 * 判断键网络是否为分子晶体(molecular crystal)。
 *
 * 判据:分子晶体中每个分子是有限团簇,分子间没有共价键,因此把键网络提升到周期
 * 空间(晶胞偏移 [Int3])后,每个连通分量都是有限的;反之,共价框架、聚合物链、
 * 离子/金属网络会沿周期方向无限延伸。
 *
 * 算法(对单个晶胞内键网络):
 * 1. 把所有原子归一化到其原胞代表原子(cellOffset == (0,0,0) 的同 site 原子,
 *    分数坐标差为整数平移);键的周期偏移 = 两端真实晶胞坐标之差。归一化使
 *    跨晶胞键的端点闭合回原胞原子 —— 否则单胞内链段的跨晶胞键不会成环,
 *    无限聚合物会被误判为分子。
 * 2. 对每个连通分量做生成树(迭代 DFS);每条非树边与树路径构成一个基本环。
 * 3. 若某基本环沿周期方向的偏移和不为零(如 (0,0,0) -> (0,0,1) 的跨晶胞环),
 *    或原子与其平移映像直接成键(周期自连接),键网络无限延伸 —— 立即返回
 *    false。所有分量通过检查则返回 true。
 *
 * 结果与 [com.krystals.crystal.analysis.model.Expansion] 无关(归一化后等价于
 * 单胞键网络)。孤立原子(无键)视为单原子分子,贡献 true。
 */
fun BondNetwork.isMolecularCrystal(): Boolean {
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

    // 归一化后的原胞图:节点 = 原胞原子,边 = (邻居原胞原子, 周期偏移)。
    val edges = HashMap<Long, MutableList<Pair<Long, Int3>>>()
    for (bond in bonds) {
        val u = byId[bond.atomA] ?: continue
        val v = byId[bond.atomB] ?: continue
        val ru = rep(u) ?: continue
        val rv = rep(v) ?: continue
        val off = v.cellOffset - u.cellOffset
        if (ru.id == rv.id) {
            // 原子与其平移映像成键:周期自连接(如金属密堆积),非分子晶体。
            if (off != ZERO_OFFSET) return false
            continue
        }
        edges.getOrPut(ru.id) { mutableListOf() } += rv.id to off
        edges.getOrPut(rv.id) { mutableListOf() } += ru.id to -off
    }

    // 每个连通分量做生成树;DFS 全边检查:边偏移必须与两端展开位置一致,
    // 不一致即存在跨晶胞基本环。
    val pos = HashMap<Long, Int3>()
    for (root in cellAtoms) {
        if (root.id in pos) continue
        pos[root.id] = ZERO_OFFSET
        val stack = ArrayDeque<Long>()
        stack.addLast(root.id)
        while (stack.isNotEmpty()) {
            val u = stack.removeLast()
            val pu = pos.getValue(u)
            val uEdges = edges[u] ?: continue
            for ((v, off) in uEdges) {
                val pv = pos[v]
                if (pv == null) {
                    pos[v] = pu + off
                    stack.addLast(v)
                } else if (pu + off != pv) {
                    return false
                }
            }
        }
    }
    return true
}
