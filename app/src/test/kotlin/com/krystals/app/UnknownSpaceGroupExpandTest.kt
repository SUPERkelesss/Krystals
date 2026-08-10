package com.krystals.app

import com.krystals.crystal.analysis.expansion.SymmetryExpander
import com.krystals.crystal.analysis.model.PeriodicTable
import com.krystals.crystal.io.CifCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per v0.7.0: end-to-end check of the data fed to spglib for the user's repro
 * file cod-1544612.cif (sg '?', 16 symops, ASU sites). Simulates
 * refineUnknownSpaceGroup's input construction (expand -> Z array) in pure
 * Kotlin; the native call itself cannot run on the JVM.
 */
class UnknownSpaceGroupExpandTest {

    @Test
    fun expandProducesFullCellAndZMapping() {
        val root = File(System.getProperty("user.dir"))
        val cif = File(root, ".todos/cod-1544612.cif")
        assertTrue(cif.exists(), "repro CIF missing at ${cif.absolutePath}")
        val parsed = CifCodec.parseStructure(cif.readText(), autoConvertConventional = false)
        println("parsed sg='${parsed.structure.spaceGroup.symbol}' number=${parsed.structure.spaceGroup.number} sites=${parsed.structure.sites.size} symops=${parsed.structure.symmetryOperations.size}")
        // The gate: '?' with null number must be unrecognised.
        val number = parsed.structure.spaceGroup.number
        assertTrue(number == null || number !in 1..230, "sg '?' must not be recognised")
        assertTrue(parsed.structure.symmetryOperations.size >= 16, "CIF symops must be parsed")

        // Simulate refineUnknownSpaceGroup's input construction (same logic:
        // global dedupe with 1e3 grid -> real elements get their Z, placeholders >= 119).
        val expanded0 = SymmetryExpander.expand(parsed.structure)
        val seenGlobal = HashSet<Pair<String, Triple<Int, Int, Int>>>()
        val expanded = expanded0.filter { atom ->
            val fc = atom.fractionalCoordinate
            val key = atom.species.symbol to Triple(
                Math.round(fc.x * 1e3).toInt(),
                Math.round(fc.y * 1e3).toInt(),
                Math.round(fc.z * 1e3).toInt(),
            )
            seenGlobal.add(key)
        }
        println("expanded raw=${expanded0.size} after-global-dedupe=${expanded.size}")
        assertTrue(expanded.size > parsed.structure.sites.size, "expansion must generate the full cell")

        val symbolToZ = HashMap<String, Int>()
        var nextPlaceholder = 119
        val lines = StringBuilder()
        expanded.forEach { atom ->
            val z = symbolToZ.getOrPut(atom.species.symbol) {
                val real = PeriodicTable.symbols.indexOf(atom.species.symbol) + 1
                if (real > 0) real else nextPlaceholder++
            }
            lines.append("%.6f %.6f %.6f %d\n".format(atom.fractionalCoordinate.x, atom.fractionalCoordinate.y, atom.fractionalCoordinate.z, z))
        }
        File(System.getProperty("user.dir"), "build/tmp/cod-1544612-expanded.txt").apply {
            parentFile.mkdirs(); writeText(lines.toString())
        }
        val zs = symbolToZ.values
        println("wrote ${expanded.size} lines to build/tmp/cod-1544612-expanded.txt")
        println("z range: ${zs.min()}..${zs.max()}, distinct: ${zs.toSet().size}")
        val placeholders = expanded.mapNotNull { atom -> symbolToZ[atom.species.symbol] }.filter { it >= 119 }
        println("placeholder (X) atoms: ${placeholders.size}")
        assertTrue(placeholders.isNotEmpty(), "guest-site X atoms must map to placeholder Z")
    }

    @Test
    fun atomicSymbolsMatchCifContent() {
        val root = File(System.getProperty("user.dir"))
        val cif = File(root, ".todos/cod-1544612.cif")
        val parsed = CifCodec.parseStructure(cif.readText(), autoConvertConventional = false)
        val symbols = parsed.structure.sites.map { it.species.symbol }.toSet()
        println("parsed species: $symbols")
        assertTrue("Si" in symbols, "Si must parse")
        assertTrue("O" in symbols, "O must parse")
        assertTrue("X" in symbols, "placeholder X must parse as species X")
    }
}
