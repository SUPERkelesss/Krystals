package com.krystals.renderer

import com.krystals.core.Vec3
import kotlin.math.atan2
import kotlin.math.abs

/**
 * Convex-hull face enumeration for a small set of ligand points around a [center].
 *
 * Per v0.2.3: returns polygonal faces (each a list of coplanar vertices ordered around the face)
 * rather than only triangles, so a square face (e.g. a triangular prism's rectangular side) is
 * drawn as one quad instead of two triangles.
 *
 * Algorithm: enumerate hull triangles (a triangle (i,j,k) is on the hull when every other point
 * lies on the same side of its plane), then merge triangles that share an edge and are coplanar
 * (same outward normal) into larger polygons. Vertices of each merged polygon are ordered
 * counter-clockwise about the outward normal so the renderer can fill a single Path.
 *
 * For n ≤ 8 ligands the O(n⁴) cost is trivial. Returns an empty list when fewer than 3 points.
 */
fun convexHullFaces(center: Vec3, points: List<Vec3>): List<List<Vec3>> {
    if (points.size < 3) return emptyList()
    val n = points.size
    val eps = 1e-9

    // 1) Enumerate hull triangles with outward normals.
    data class Face(val indices: List<Int>, val normal: Vec3)
    val triangles = mutableListOf<Face>()
    for (i in 0 until n - 2) {
        for (j in i + 1 until n - 1) {
            for (k in j + 1 until n) {
                val pi = points[i]; val pj = points[j]; val pk = points[k]
                var normal = (pj - pi).cross(pk - pi)
                if (normal.lengthSquared() < eps) continue
                var pos = 0; var neg = 0
                for (m in 0 until n) {
                    if (m == i || m == j || m == k) continue
                    val d = normal.dot(points[m] - pi)
                    if (d > eps) pos++ else if (d < -eps) neg++
                    if (pos > 0 && neg > 0) break
                }
                if (pos > 0 && neg > 0) continue
                val faceCenter = (pi + pj + pk) / 3.0
                val ordered = if (normal.dot(faceCenter - center) >= 0) listOf(i, j, k) else listOf(i, k, j)
                if (normal.dot(faceCenter - center) < 0) normal = normal * -1.0
                triangles += Face(ordered, normal.normalized())
            }
        }
    }
    if (triangles.isEmpty()) {
        // Per v0.3.43: degenerate (fully planar) coordination. Every ligand is coplanar, so no
        // triangle has all other points strictly on one side — the hull enumerator above returns
        // nothing. Build the single polygon face directly: order the points around their centroid.
        return planarPolygonFaces(center, points)
    }

    // 2) Group coplanar triangles into faces and extract the outer boundary of each face.
    // Per v0.3.44: the boundary is the 2D convex hull of the group's vertices in the face plane
    // (extractBoundaryPolygons), so it no longer depends on the triangulation being minimal — a quad
    // split into 2 triangles OR enumerated as all C(4,3) triangles both yield one ordered quad.
    val used = BooleanArray(triangles.size)
    val coplanarEps = 1e-6
    val result = mutableListOf<List<Vec3>>()

    fun extractBoundaryPolygons(group: List<Face>): List<List<Int>> {
        // Per v0.3.44: the previous edge-count logic assumed a *minimal* triangulation (a quad split
        // into 2 triangles sharing a diagonal). But the enumerator above produces C(n,3) triangles
        // for n coplanar vertices, so every edge ended up with count 2 and the boundary came back
        // empty — quadrilateral (and larger) faces silently disappeared.
        //
        // Instead, collect every vertex that appears in the group, project into the face plane, and
        // take the 2D convex hull ordered CCW about the outward normal. Works for any triangulation.
        val indices = group.flatMap { it.indices }.toSet().sorted()
        if (indices.size < 3) return emptyList()
        // Build a 2D basis in the face plane.
        val p0 = points[indices[0]]
        var u: Vec3? = null
        for (i in 1 until indices.size) {
            val e = points[indices[i]] - p0
            if (e.lengthSquared() > 1e-18) { u = e.normalized(); break }
        }
        if (u == null) return emptyList()
        val v = group.first().normal.cross(u).normalized()
        data class P2(val idx: Int, val x: Double, val y: Double)
        val pts = indices.map { idx ->
            val d = points[idx] - p0
            P2(idx, d.dot(u), d.dot(v))
        }
        // Andrew's monotone-chain 2D convex hull (n <= 8 is trivial). Returns indices CCW.
        val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return emptyList()
        fun cross(o: P2, a: P2, b: P2) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = mutableListOf<P2>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = mutableListOf<P2>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        val hull = (lower.dropLast(1) + upper.dropLast(1))
        // The 2D basis (u, v=n×u) is right-handed about the outward normal, so monotone chain's CCW
        // hull matches the outward-CCW ordering the renderer expects.
        if (hull.size < 3) return emptyList()
        return listOf(hull.map { it.idx })
    }

    for (t in triangles.indices) {
        if (used[t]) continue
        used[t] = true
        val normal = triangles[t].normal
        // Breadth-first search for all triangles on the same face: same outward normal (parallel
        // and same direction) and, if not edge-adjacent, lying on the same plane.
        val group = mutableListOf(triangles[t])
        val queue = ArrayDeque<Int>()
        queue += t
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentNormal = triangles[current].normal
            for (u in triangles.indices) {
                if (used[u]) continue
                if (triangles[u].normal.dot(currentNormal) < 1.0 - coplanarEps) continue
                val sharesVertex = triangles[current].indices.any { it in triangles[u].indices }
                if (!sharesVertex) {
                    val v0 = points[triangles[u].indices[0]]
                    val vRef = points[triangles[current].indices[0]]
                    if (abs(currentNormal.dot(v0 - vRef)) > coplanarEps) continue
                }
                used[u] = true
                group += triangles[u]
                queue += u
            }
        }

        for (poly in extractBoundaryPolygons(group)) {
            val verts = poly.map { points[it] }
            val centroid = verts.reduce { acc, v -> acc + v } / verts.size.toDouble()
            val ref = verts[0] - centroid
            val u = (if (ref.length() < 1e-12) verts[1] - verts[0] else ref).normalized()
            val v = normal.cross(u).normalized()
            val ordered = verts.map { vec ->
                val d = vec - centroid
                Pair(vec, atan2(d.dot(v), d.dot(u)))
            }.sortedBy { it.second }.map { it.first }
            result += ordered
        }
    }
    return result
}

/**
 * Per v0.3.43: fall-back for a fully planar coordination (every ligand coplanar), where the hull
 * triangle enumerator finds no supporting triangle. Orders the points CCW around their centroid in
 * the ligand plane so the renderer can draw the polygon (and its reversed twin for double-sided
 * rendering). Returns an empty list when the points are degenerate (collinear / < 3).
 */
private fun planarPolygonFaces(center: Vec3, points: List<Vec3>): List<List<Vec3>> {
    if (points.size < 3) return emptyList()
    val p0 = points[0]
    // Find three non-collinear points to establish the plane basis.
    var basisIdx = -1
    var normal = Vec3.ZERO
    for (i in 1 until points.size) {
        val e1 = points[i] - p0
        if (e1.lengthSquared() < 1e-18) continue
        for (j in i + 1 until points.size) {
            val e2 = points[j] - p0
            val n = e1.cross(e2)
            if (n.lengthSquared() > 1e-18) { basisIdx = i; normal = n; break }
        }
        if (basisIdx >= 0) break
    }
    if (basisIdx < 0) return emptyList() // all points collinear
    val len = normal.length()
    if (normal.dot(p0 - center) > 0) normal = normal * -1.0 // outward = away from centre
    val n = normal / len
    val u = (points[basisIdx] - p0).normalized()
    val v = n.cross(u).normalized()
    val centroid = points.reduce { acc, vec -> acc + vec } / points.size.toDouble()
    val ordered = points.map { vec ->
        val d = vec - centroid
        Pair(vec, atan2(d.dot(v), d.dot(u)))
    }.sortedBy { it.second }.map { it.first }
    return listOf(ordered)
}

