package com.krystals.crystal.core.model

import com.krystals.crystal.core.coordinate.CartesianCoordinate

/**
 * 有限分子中的一个原子。
 *
 * 与周期晶体中的 [Site]/[AtomImage] 不同,分子原子没有晶格、没有分数坐标、
 * 没有对称展开 —— 位置直接用笛卡尔坐标表达。
 */
data class MoleculeAtom(
    val id: Int,
    val label: String,
    val species: Species,
    val position: CartesianCoordinate,
)

/**
 * 有限分子中的一根键,通过原子 id 引用两端。
 *
 * [order] 为键级:1.0 = 单键,2.0 = 双键,3.0 = 三键,0.5/1.5 等用于芳香或
 * 离域键。不同于周期晶体的键(依赖 [Site] 规则 + 周期边界),分子键是
 * 原子 id 之间的显式连接,没有偏移量。
 */
data class MoleculeBond(
    val from: Int,
    val to: Int,
    val order: Double = 1.0,
) {
    /** 该键是否连接到指定原子。 */
    fun connects(atomId: Int): Boolean = from == atomId || to == atomId

    /** 给定一端,返回另一端;若 [atomId] 不在两端则返回 null。 */
    fun otherEnd(atomId: Int): Int? = when (atomId) {
        from -> to
        to -> from
        else -> null
    }
}

/**
 * 有限分子:承载有限数量的原子与键。
 *
 * 与 [CrystalStructure] 的本质区别:分子是非周期的 —— 没有晶格、没有空间群、
 * 没有对称展开,原子用笛卡尔坐标,键是原子 id 之间的显式连接。
 *
 * 不变量:原子 id 唯一;每根键的两端都引用存在的原子;键不自环。
 * 违反任一不变量会在构造时抛出 [IllegalArgumentException]。
 */
data class Molecule(
    val name: String,
    val atoms: List<MoleculeAtom>,
    val bonds: List<MoleculeBond> = emptyList(),
) {
    init {
        require(atoms.map { it.id }.distinct().size == atoms.size) {
            "Molecule '$name' has duplicate atom ids"
        }
        val atomIds = atoms.mapTo(HashSet()) { it.id }
        for (bond in bonds) {
            require(bond.from != bond.to) {
                "Molecule '$name' has a self-loop bond on atom ${bond.from}"
            }
            require(bond.from in atomIds) {
                "Molecule '$name' bond ${bond.from}-${bond.to} references unknown atom id ${bond.from}"
            }
            require(bond.to in atomIds) {
                "Molecule '$name' bond ${bond.from}-${bond.to} references unknown atom id ${bond.to}"
            }
        }
    }

    val atomCount: Int get() = atoms.size
    val bondCount: Int get() = bonds.size

    /** 按 id 查找原子;不存在时返回 null。 */
    fun atom(id: Int): MoleculeAtom? = atoms.firstOrNull { it.id == id }

    /** 连接到指定原子的所有键。 */
    fun bondsOf(atomId: Int): List<MoleculeBond> = bonds.filter { it.connects(atomId) }

    /** 通过键连接到指定原子的相邻原子 id(不含自身)。 */
    fun neighborsOf(atomId: Int): List<Int> = bondsOf(atomId).mapNotNull { it.otherEnd(atomId) }
}
