package com.krystals.crystal.renderer

import com.krystals.crystal.core.math.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class DihedralPlaneGeometryTest {
    @Test fun buildsTwoPlanesWithBcAsLongEdge() {
        val planes = DihedralPlaneGeometryBuilder.build(Vec3(0.0, 2.0, 0.0), Vec3.ZERO, Vec3(3.0, 0.0, 0.0), Vec3(3.0, 0.0, 4.0))
        assertEquals(2, planes.size); assertTrue(abs((planes[0].vertices[3] - planes[0].vertices[0]).length() - 2.0) < 1e-9); assertTrue(abs((planes[1].vertices[3] - planes[1].vertices[0]).length() - 4.0) < 1e-9)
    }
    @Test fun skipsDegenerateInputs() { assertTrue(DihedralPlaneGeometryBuilder.build(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO).isEmpty()) }
}
