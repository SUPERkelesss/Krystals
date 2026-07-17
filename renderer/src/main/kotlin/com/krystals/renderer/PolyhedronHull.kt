package com.krystals.renderer

import com.krystals.core.Vec3
import kotlin.math.atan2

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
    if (triangles.isEmpty()) return emptyList()

    // 2) Merge coplanar triangles sharing an edge. Two triangles are coplanar when their outward
    //    normals are parallel (dot ≈ 1, same sign) and a shared edge exists.
    val merged = mutableListOf<MutableList<Int>>()
    val used = BooleanArray(triangles.size)
    fun coplanar(a: Vec3, b: Vec3) = a.dot(b) > 1.0 - 1e-6
    for (t in triangles.indices) {
        if (used[t]) continue
        used[t] = true
        val poly = triangles[t].indices.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            for (u in triangles.indices) {
                if (used[u]) continue
                if (!coplanar(triangles[t].normal, triangles[u].normal)) continue
                val shared = poly.filter { it in triangles[u].indices }
                when (shared.size) {
                    // Merge: insert the third vertex of u between the two shared vertices of poly,
                    // but only when those two are adjacent in the polygon cycle (otherwise the
                    // shared edge is a diagonal and merging would self-intersect the polygon).
                    2 -> {
                        val third = triangles[u].indices.first { it !in shared }
                        val a = poly.indexOf(shared[0])
                        val b = poly.indexOf(shared[1])
                        val size = poly.size
                        val insertAfter = when {
                            (a + 1) % size == b -> a
                            (b + 1) % size == a -> b
                            else -> -1
                        }
                        if (insertAfter >= 0) {
                            poly.add((insertAfter + 1) % size, third)
                            used[u] = true
                            changed = true
                        } else {
                            // The two shared vertices are already non-adjacent in poly — i.e. the
                            // shared edge is a diagonal of the merged polygon. Since u is coplanar
                            // with poly and its vertices all lie inside poly's face, u is already
                            // covered by poly. Absorb it without adding a vertex, so it is not
                            // later seeded as a stray overlapping triangle on the same face.
                            used[u] = true
                            changed = true
                        }
                    }
                    // 3 shared vertices: the triangle lies entirely inside this polygon (it's one of
                    // the C(4,3)=4 triangles of a coplanar quad). Absorb it without adding a vertex,
                    // so its edges are not drawn as a stray diagonal over the merged face.
                    3 -> {
                        used[u] = true
                        changed = true
                    }
                }
            }
        }
        merged += poly
    }

    // 3) Order each polygon's vertices counter-clockwise about its normal (already roughly ordered
    //    from the merge, but re-sort by angle around the face centroid projected onto its plane).
    return merged.map { idxs ->
        val verts = idxs.map { points[it] }
        val centroid = verts.reduce { acc, v -> acc + v } / verts.size.toDouble()
        // Use the first non-degenerate in-plane basis to compute angles.
        val ref = verts[0] - centroid
        val normal = triangles.first { it.indices[0] in idxs }.normal
        // basis u = ref normalized; basis v = normal × u
        var u = ref
        val ul = u.length()
        if (ul < 1e-12) {
            // fallback: any in-plane vector
            u = verts[1] - verts[0]
        }
        u = u.normalized()
        val v = normal.cross(u).normalized()
        verts.map { v3 ->
            val d = v3 - centroid
            Pair(v3, atan2(d.dot(v), d.dot(u)))
        }.sortedBy { it.second }.map { it.first }
    }
}
