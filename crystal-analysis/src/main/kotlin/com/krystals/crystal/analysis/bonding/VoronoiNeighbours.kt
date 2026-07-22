package com.krystals.crystal.analysis.bonding

import com.krystals.crystal.core.math.Vec3
import com.krystals.crystal.core.model.AtomImage
import com.krystals.crystal.core.model.CrystalStructure
import com.krystals.crystal.core.periodic.Int3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil

class VoronoiSearchLimitExceededException(
    val estimatedCandidatesPerCenter: Long,
    val shellX: Int,
    val shellY: Int,
    val shellZ: Int,
) : IllegalStateException(
    "Periodic Voronoi search exceeds the safe candidate limit " +
        "($estimatedCandidatesPerCenter candidates per center; shell=$shellX,$shellY,$shellZ)",
)

/** Periodic 3D Voronoi neighbours, represented once per undirected periodic atom pair. */
internal object VoronoiNeighbours {
    private const val EPS = 1e-8
    private const val MIN_FACE_AREA = 1e-8
    private const val MAX_CANDIDATES_PER_CENTER = 500_000L

    private data class Candidate(
        val atomId: Long,
        val offset: Int3,
        val displacement: Vec3,
        val distance: Double,
    )

    private data class Face(
        val vertices: List<Vec3>,
        val neighbour: Candidate? = null,
    )

    private data class EdgeKey(
        val atomA: Long,
        val atomB: Long,
        val dx: Int,
        val dy: Int,
        val dz: Int,
    )

    /**
     * Builds each atom's periodic Voronoi cell by clipping a convex polyhedron against the
     * perpendicular bisector to every atom image in the surrounding 3 x 3 x 3 cells. A candidate
     * is a neighbour only when its bisector leaves a non-zero-area face on the completed cell.
     *
     * Multiple periodic images of one atom are retained. This is necessary for coordination
     * numbers: a Cs site in CsCl, for example, has eight Cl neighbours even though all eight images
     * refer to the same expanded atom id.
     */
    fun find(structure: CrystalStructure, atoms: List<AtomImage>): List<Triple<Long, Long, Double>> {
        if (atoms.isEmpty()) return emptyList()
        val lattice = structure.lattice.matrix
        val edges = LinkedHashMap<EdgeKey, Double>()

        for (center in atoms) {
            val initialCandidates = candidates(center, atoms, structure, 1, 1, 1)
            val provisionalCell = buildCell(initialCandidates)
            if (provisionalCell.isEmpty()) continue

            // Any plane whose displacement q is longer than twice the farthest cell vertex cannot
            // intersect the cell: q.x <= |q||x| < |q|^2/2. Convert that Cartesian bound through
            // the inverse lattice to find a finite, complete periodic-image search range. This is
            // important for strongly skewed, non-reduced cells where a relevant image can have an
            // offset outside the usual -1..1 shell.
            val cellRadius = provisionalCell
                .flatMap { it.vertices }
                .maxOfOrNull { it.length() }
                ?: continue
            val relevantDistance = 2.0 * cellRadius + EPS
            val inverse = lattice.inverse()
            val shellX = ceil(Vec3(inverse.a.x, inverse.b.x, inverse.c.x).length() * relevantDistance + 1.0).toInt()
            val shellY = ceil(Vec3(inverse.a.y, inverse.b.y, inverse.c.y).length() * relevantDistance + 1.0).toInt()
            val shellZ = ceil(Vec3(inverse.a.z, inverse.b.z, inverse.c.z).length() * relevantDistance + 1.0).toInt()
            val completeCandidates = candidates(center, atoms, structure, shellX, shellY, shellZ)
                .filter { it.distance <= relevantDistance }
            val faces = buildCell(completeCandidates)

            for (face in faces) {
                val neighbour = face.neighbour ?: continue
                if (faceArea(face.vertices) <= MIN_FACE_AREA) continue
                val key = canonicalKey(center.id, neighbour.atomId, neighbour.offset)
                edges.merge(key, neighbour.distance, ::minOf)
            }
        }

        return edges.entries
            .sortedWith(compareBy({ it.key.atomA }, { it.key.atomB }, { it.key.dx }, { it.key.dy }, { it.key.dz }))
            .map { (key, distance) -> Triple(key.atomA, key.atomB, distance) }
    }

    private fun candidates(
        center: AtomImage,
        atoms: List<AtomImage>,
        structure: CrystalStructure,
        shellX: Int,
        shellY: Int,
        shellZ: Int,
    ): List<Candidate> {
        require(shellX >= 0 && shellY >= 0 && shellZ >= 0)
        ensureCandidateBudget(atoms.size, shellX, shellY, shellZ)
        val lattice = structure.lattice.matrix
        val centerPosition = center.cartesianCoordinate.toVec3()
        return buildList {
            for (other in atoms) {
                val otherPosition = other.cartesianCoordinate.toVec3()
                for (dx in -shellX..shellX) for (dy in -shellY..shellY) for (dz in -shellZ..shellZ) {
                    if (center.id == other.id && dx == 0 && dy == 0 && dz == 0) continue
                    val offset = Int3(dx, dy, dz)
                    val translation = lattice.a * dx.toDouble() +
                        lattice.b * dy.toDouble() + lattice.c * dz.toDouble()
                    val displacement = otherPosition + translation - centerPosition
                    val distance = displacement.length()
                    if (distance > EPS) add(Candidate(other.id, offset, displacement, distance))
                }
            }
        }.sortedBy { it.distance }
    }

    private fun ensureCandidateBudget(atomCount: Int, shellX: Int, shellY: Int, shellZ: Int) {
        var candidateCount = atomCount.toLong()
        for (shell in intArrayOf(shellX, shellY, shellZ)) {
            val imageCount = 2L * shell + 1L
            candidateCount = if (candidateCount > Long.MAX_VALUE / imageCount) {
                Long.MAX_VALUE
            } else {
                candidateCount * imageCount
            }
        }
        if (candidateCount > MAX_CANDIDATES_PER_CENTER) {
            throw VoronoiSearchLimitExceededException(candidateCount, shellX, shellY, shellZ)
        }
    }

    private fun buildCell(candidates: List<Candidate>): List<Face> {
        if (candidates.isEmpty()) return emptyList()
        val halfExtent = candidates.maxOf { it.distance } + 1.0
        var faces = boundingCube(halfExtent)
        for (candidate in candidates) {
            faces = clip(faces, candidate)
            if (faces.isEmpty()) break
        }
        return faces
    }

    private fun clip(faces: List<Face>, candidate: Candidate): List<Face> {
        val normal = candidate.displacement
        val limit = candidate.distance * candidate.distance / 2.0
        val result = ArrayList<Face>(faces.size + 1)
        val capPoints = mutableListOf<Vec3>()

        for (face in faces) {
            val clipped = clipPolygon(face.vertices, normal, limit, capPoints)
            if (clipped.size >= 3 && faceArea(clipped) > MIN_FACE_AREA) {
                result += face.copy(vertices = clipped)
            }
        }

        val uniqueCapPoints = deduplicate(capPoints)
        if (uniqueCapPoints.size >= 3) {
            val ordered = orderOnPlane(uniqueCapPoints, normal)
            if (faceArea(ordered) > MIN_FACE_AREA) result += Face(ordered, candidate)
        }
        return result
    }

    private fun clipPolygon(
        vertices: List<Vec3>,
        normal: Vec3,
        limit: Double,
        capPoints: MutableList<Vec3>,
    ): List<Vec3> {
        if (vertices.isEmpty()) return emptyList()
        val output = mutableListOf<Vec3>()
        for (i in vertices.indices) {
            val current = vertices[i]
            val next = vertices[(i + 1) % vertices.size]
            val currentDistance = normal.dot(current) - limit
            val nextDistance = normal.dot(next) - limit
            val currentInside = currentDistance <= EPS
            val nextInside = nextDistance <= EPS

            if (currentInside) output += current
            if (currentInside != nextInside) {
                val t = currentDistance / (currentDistance - nextDistance)
                val intersection = current + (next - current) * t
                output += intersection
                capPoints += intersection
            } else if (abs(currentDistance) <= EPS) {
                capPoints += current
            }
        }
        return deduplicateConsecutive(output)
    }

    private fun boundingCube(r: Double): List<Face> {
        val p000 = Vec3(-r, -r, -r)
        val p001 = Vec3(-r, -r, r)
        val p010 = Vec3(-r, r, -r)
        val p011 = Vec3(-r, r, r)
        val p100 = Vec3(r, -r, -r)
        val p101 = Vec3(r, -r, r)
        val p110 = Vec3(r, r, -r)
        val p111 = Vec3(r, r, r)
        return listOf(
            Face(listOf(p000, p001, p011, p010)),
            Face(listOf(p100, p110, p111, p101)),
            Face(listOf(p000, p100, p101, p001)),
            Face(listOf(p010, p011, p111, p110)),
            Face(listOf(p000, p010, p110, p100)),
            Face(listOf(p001, p101, p111, p011)),
        )
    }

    private fun orderOnPlane(points: List<Vec3>, normal: Vec3): List<Vec3> {
        val centroid = points.reduce { sum, point -> sum + point } / points.size.toDouble()
        val unitNormal = normal.normalized()
        val reference = when {
            abs(unitNormal.x) <= abs(unitNormal.y) && abs(unitNormal.x) <= abs(unitNormal.z) -> Vec3(1.0, 0.0, 0.0)
            abs(unitNormal.y) <= abs(unitNormal.z) -> Vec3(0.0, 1.0, 0.0)
            else -> Vec3(0.0, 0.0, 1.0)
        }
        val u = unitNormal.cross(reference).normalized()
        val v = unitNormal.cross(u).normalized()
        return points.sortedBy { point ->
            val relative = point - centroid
            atan2(relative.dot(v), relative.dot(u))
        }
    }

    private fun faceArea(vertices: List<Vec3>): Double {
        if (vertices.size < 3) return 0.0
        val origin = vertices.first()
        var area = 0.0
        for (i in 1 until vertices.lastIndex) {
            area += (vertices[i] - origin).cross(vertices[i + 1] - origin).length() / 2.0
        }
        return area
    }

    private fun deduplicate(points: List<Vec3>): List<Vec3> {
        val result = mutableListOf<Vec3>()
        for (point in points) {
            if (result.none { (it - point).lengthSquared() <= EPS * EPS }) result += point
        }
        return result
    }

    private fun deduplicateConsecutive(points: List<Vec3>): List<Vec3> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf<Vec3>()
        for (point in points) {
            if (result.isEmpty() || (result.last() - point).lengthSquared() > EPS * EPS) result += point
        }
        if (result.size > 1 && (result.first() - result.last()).lengthSquared() <= EPS * EPS) {
            result.removeAt(result.lastIndex)
        }
        return result
    }

    private fun canonicalKey(atomA: Long, atomB: Long, offsetB: Int3): EdgeKey {
        if (atomA < atomB) return EdgeKey(atomA, atomB, offsetB.x, offsetB.y, offsetB.z)
        if (atomA > atomB) return EdgeKey(atomB, atomA, -offsetB.x, -offsetB.y, -offsetB.z)

        val positive = Triple(offsetB.x, offsetB.y, offsetB.z)
        val negative = Triple(-offsetB.x, -offsetB.y, -offsetB.z)
        val usePositive = compareOffsets(positive, negative) <= 0
        val offset = if (usePositive) positive else negative
        return EdgeKey(atomA, atomB, offset.first, offset.second, offset.third)
    }

    private fun compareOffsets(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int =
        when {
            a.first != b.first -> a.first.compareTo(b.first)
            a.second != b.second -> a.second.compareTo(b.second)
            else -> a.third.compareTo(b.third)
        }
}
