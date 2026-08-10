package com.krystals.crystal.core.model

import com.krystals.crystal.core.periodic.Int3

/**
 * 一根氢键:供体氢原子([donorId])与受体原子([acceptorId])之间的弱相互作用。
 *
 * 与普通键分离存储:氢键不是共价键 —— 不参与分子晶体判据、共价配位,
 * 渲染为独立样式(细/半透明)。本类型是纯数据载体,crystal-core 不依赖
 * crystal-analysis 的 BondRule;检测层(BondDetector)从源规则转换时把
 * 消费端需要的字段(ruleKey/extendAtoB/extendBtoA)拷贝进来。
 */
data class HydrogenBond(
    /** 供体氢原子 id(AtomImage.id)。 */
    val donorId: Long,
    /** 受体原子 id(AtomImage.id)。 */
    val acceptorId: Long,
    val distance: Double,
    /** 供体位点标签(源 BondRule.siteA)。 */
    val siteA: String,
    /** 受体位点标签(源 BondRule.siteB)。 */
    val siteB: String,
    /** 可见性 key = 源规则 key(sorted pair + "\u0000hbond"),与 hiddenBondPairs 匹配。 */
    val ruleKey: String,
    /** 跨胞扩展标志(源规则拷贝;生产氢键规则恒 false)。 */
    val extendAtoB: Boolean = false,
    val extendBtoA: Boolean = false,
    /** 受体相对供体的周期偏移(与 Bond.offsetB 同义)。 */
    val offsetB: Int3 = Int3(0, 0, 0),
    /** True when this contact came from automatic H-bond detection. */
    val isAutoDetected: Boolean = false,
) {
    /** 与 BondRule.shouldExtendAcrossCell 相同的语义,保持消费端行为不变。 */
    fun shouldExtendAcrossCell(insideSiteId: String, outsideAtomIsExternal: Boolean): Boolean {
        if (!outsideAtomIsExternal) return true
        return when {
            siteA == siteB -> extendAtoB || extendBtoA
            insideSiteId == siteA -> extendAtoB
            insideSiteId == siteB -> extendBtoA
            else -> false
        }
    }
}
