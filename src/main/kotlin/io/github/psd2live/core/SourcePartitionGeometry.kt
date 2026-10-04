package io.github.psd2live.core

import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DrawableMesh
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToLong

/** Cut texture triangles while retaining their exact affine relationship to the original mesh. */
internal object SourcePartitionGeometry {
    data class Piece(val mesh: DrawableMesh, val sources: List<VertexSource>, val oldToNew: IntArray)
    data class Plan(val pieces: List<Piece>, val ownerByVertex: IntArray)

    private data class Point(val x: Double, val y: Double, val weights: DoubleArray)
    private const val EPS = 1e-8

    /** Mesh UVs in the returned pieces are source-canvas pixels, independent of atlas packing. */
    fun polygon(mesh: DrawableMesh, canvas: FloatArray, polygon: List<Pair<Double, Double>>,
                checkpoint: () -> Unit = {}): Plan {
        validate(mesh, canvas)
        require(polygon.size in 3..32 && polygon.all { it.first.isFinite() && it.second.isFinite() })
        val path = Path2D.Double().apply {
            moveTo(polygon.first().first, polygon.first().second)
            polygon.drop(1).forEach { (x, y) -> lineTo(x, y) }
            closePath()
        }
        val builders = List(2) { Builder(mesh, canvas) }
        for (offset in mesh.indices.indices step 3) {
            checkpoint()
            val triangle = mesh.indices.sliceArray(offset until offset + 3)
            val initial = triangle.mapIndexed { index, vertex ->
                Point(canvas[vertex * 2].toDouble(), canvas[vertex * 2 + 1].toDouble(), DoubleArray(3) { if (it == index) 1.0 else 0.0 })
            }
            if (abs(area(initial)) < EPS) {
                val piece = if (path.contains(initial.map { it.x }.average(), initial.map { it.y }.average())) 0 else 1
                builders[piece].triangle(initial, triangle)
                continue
            }
            var cells = listOf(initial)
            // All edge lines subdivide a triangle into convex cells. Classifying each cell with
            // the same winding rule as pixel partitioning also supports concave and crossing paths.
            for (edge in polygon.indices) {
                checkpoint()
                val a = polygon[edge]; val b = polygon[(edge + 1) % polygon.size]
                val dx = b.first - a.first; val dy = b.second - a.second
                val length = hypot(dx, dy)
                if (length < EPS) continue
                fun distance(p: Point) = (dx * (p.y - a.second) - dy * (p.x - a.first)) / length
                cells = cells.flatMap { cell ->
                    val distances = cell.map(::distance)
                    if (distances.min() >= -EPS || distances.max() <= EPS) listOf(cell)
                    else listOf(clip(cell, distances, true), clip(cell, distances, false))
                        .filter { it.size >= 3 && abs(area(it)) >= EPS }
                }
            }
            for (cell in cells) {
                checkpoint()
                val owner = if (path.contains(cell.map { it.x }.average(), cell.map { it.y }.average())) 0 else 1
                for (i in 1 until cell.lastIndex) builders[owner].triangle(listOf(cell[0], cell[i], cell[i + 1]), triangle)
            }
        }
        val owners = IntArray(mesh.vertexCount) { vertex ->
            if (path.contains(canvas[vertex * 2].toDouble(), canvas[vertex * 2 + 1].toDouble())) 0 else 1
        }
        // An unused old vertex can still own a Glue or a manually authored vertex weight.
        owners.forEachIndexed { vertex, owner -> builders[owner].old(vertex) }
        checkpoint()
        return Plan(builders.map { it.finish() }, owners)
    }

    fun components(mesh: DrawableMesh, canvas: FloatArray, owners: IntArray, count: Int,
                   checkpoint: () -> Unit = {}): Plan {
        validate(mesh, canvas)
        require(count >= 2 && owners.size == mesh.vertexCount && owners.all { it in 0 until count })
        val builders = List(count) { Builder(mesh, canvas) }
        for (offset in mesh.indices.indices step 3) {
            checkpoint()
            val triangle = mesh.indices.sliceArray(offset until offset + 3)
            require(triangle.all { owners[it] == owners[triangle[0]] }) { "A connected triangle cannot span components" }
            val indices = triangle.map { builders[owners[it]].old(it) }
            builders[owners[triangle[0]]].indices.addAll(indices)
        }
        owners.forEachIndexed { vertex, owner -> builders[owner].old(vertex) }
        checkpoint()
        return Plan(builders.map { it.finish() }, owners.copyOf())
    }

    private fun validate(mesh: DrawableMesh, canvas: FloatArray) {
        RasterMeshJournal.validateMesh(mesh)
        require(canvas.size == mesh.positions.size && canvas.all(Float::isFinite)) { "Invalid partition texture coordinates" }
    }

    private fun area(points: List<Point>): Double = points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]
        a.x * b.y - b.x * a.y
    } * 0.5

    private fun clip(points: List<Point>, distances: List<Double>, positive: Boolean): List<Point> {
        val result = ArrayList<Point>()
        fun inside(d: Double) = if (positive) d >= 0.0 else d <= 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            val a = points[i]; val b = points[j]; val da = distances[i]; val db = distances[j]
            if (inside(da)) result += a
            if (inside(da) != inside(db)) {
                val t = (da / (da - db)).coerceIn(0.0, 1.0)
                result += Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t,
                    DoubleArray(3) { a.weights[it] + (b.weights[it] - a.weights[it]) * t })
            }
        }
        return result
    }

    private class Builder(val original: DrawableMesh, val canvas: FloatArray) {
        val indices = ArrayList<Int>()
        private val positions = ArrayList<Float>()
        private val uvs = ArrayList<Float>()
        private val sources = ArrayList<VertexSource>()
        private val vertices = HashMap<List<Pair<Int, Long>>, Int>()
        private val oldToNew = IntArray(original.vertexCount) { -1 }

        fun old(vertex: Int): Int = vertex(Point(canvas[vertex * 2].toDouble(), canvas[vertex * 2 + 1].toDouble(),
            doubleArrayOf(1.0, 0.0, 0.0)), intArrayOf(vertex, vertex, vertex))

        fun triangle(points: List<Point>, triangle: IntArray) {
            val ids = points.map { vertex(it, triangle) }
            if (ids.distinct().size == 3) indices.addAll(ids)
        }

        private fun vertex(point: Point, triangle: IntArray): Int {
            val weights = point.weights.map { it.coerceAtLeast(0.0) }
            val total = weights.sum()
            val normalized = weights.map { it / total }
            val claims = triangle.indices.filter { normalized[it] > EPS }
                .groupBy { triangle[it] }.map { (id, components) -> id to components.sumOf { normalized[it] } }
                .sortedBy { it.first }
            val key = claims.map { (id, weight) -> id to (weight * 1_000_000_000.0).roundToLong() }
            return vertices.getOrPut(key) {
                val index = sources.size
                val old = claims.singleOrNull()?.first
                val source = if (old != null) VertexSource.FromOld(old) else VertexSource.BarycentricOf(
                    triangle[0], triangle[1], triangle[2], normalized[0].toFloat(), normalized[1].toFloat(), normalized[2].toFloat())
                sources += source
                for (axis in 0..1) {
                    positions += if (old != null) original.positions[old * 2 + axis] else
                        triangle.indices.sumOf { original.positions[triangle[it] * 2 + axis] * normalized[it] }.toFloat()
                    uvs += if (old != null) canvas[old * 2 + axis] else
                        triangle.indices.sumOf { canvas[triangle[it] * 2 + axis] * normalized[it] }.toFloat()
                }
                if (old != null) oldToNew[old] = index
                index
            }
        }

        fun finish(): Piece {
            require(indices.isNotEmpty()) { "Each source partition must intersect the existing mesh" }
            val mesh = DrawableMesh(positions.toFloatArray(), uvs.toFloatArray(), indices.toIntArray())
            RasterMeshJournal.validateMesh(mesh)
            return Piece(mesh, sources.toList(), oldToNew.copyOf())
        }
    }
}
