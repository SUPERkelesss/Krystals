package com.krystals.app

import com.krystals.crystal.io.CifCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Per v0.7.0: dump how the app PARSES the 16 CIF symmetry operations —
 *  the expand output fed to spglib depends entirely on them. */
class UnknownSpaceGroupSymopTest {

    @Test
    fun dumpParsedSymops() {
        val root = File(System.getProperty("user.dir"))
        val cif = File(root, ".todos/cod-1544612.cif")
        val parsed = CifCodec.parseStructure(cif.readText(), autoConvertConventional = false)
        println("parsed ${parsed.structure.symmetryOperations.size} symops:")
        parsed.structure.symmetryOperations.forEachIndexed { i, op ->
            val r = op.rotation
            println("  $i: R=[[${r.a.x.toInt()},${r.a.y.toInt()},${r.a.z.toInt()}],[${r.b.x.toInt()},${r.b.y.toInt()},${r.b.z.toInt()}],[${r.c.x.toInt()},${r.c.y.toInt()},${r.c.z.toInt()}]] t=(${op.translation.x},${op.translation.y},${op.translation.z}) src=${op.source}")
        }
        assertTrue(parsed.structure.symmetryOperations.size >= 16)
    }
}
