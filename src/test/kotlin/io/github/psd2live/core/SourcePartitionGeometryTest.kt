package io.github.psd2live.core

import org.umamo.edit.VertexSource
import org.umamo.runtime.model.DrawableMesh
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.test.*

class SourcePartitionGeometryTest {
    private val canvas = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
    private fun mesh() = DrawableMesh(floatArrayOf(3f, 2f, 15f, 4f, 18f, 17f, 1f, 13f), canvas.copyOf(), intArrayOf(0, 1, 2, 0, 2, 3))
    private fun area(mesh: DrawableMesh) = mesh.indices.indices.step(3).sumOf { offset ->
        val a = mesh.indices[offset] * 2; val b = mesh.indices[offset + 1] * 2; val c = mesh.indices[offset + 2] * 2
        val xy = mesh.uvs
        abs(((xy[b] - xy[a]).toDouble() * (xy[c + 1] - xy[a + 1]) - (xy[c] - xy[a]) * (xy[b + 1] - xy[a + 1])) * 0.5)
    }

    @Test fun concaveAndCrossingPathsPartitionEveryTriangleWithoutLosingAreaOrVertexAncestry() {
        val polygons = listOf(
            listOf(2.0 to -2.0, 7.0 to -2.0, 7.0 to 4.0, 4.0 to 4.0, 4.0 to 12.0, 2.0 to 12.0),
            listOf(1.0 to 1.0, 9.0 to 9.0, 1.0 to 9.0, 9.0 to 1.0),
            listOf(3.25 to -5.0, 5.75 to -5.0, 5.75 to 15.0, 3.25 to 15.0),
        )
        for (polygon in polygons) {
            val original = mesh()
            val plan = SourcePartitionGeometry.polygon(original, canvas, polygon)
            assertEquals(area(original), plan.pieces.sumOf { area(it.mesh) }, 0.00001)
            val path = Path2D.Double().apply {
                moveTo(polygon.first().first, polygon.first().second)
                polygon.drop(1).forEach { lineTo(it.first, it.second) }; closePath()
            }
            plan.pieces.forEachIndexed { owner, piece ->
                for (offset in piece.mesh.indices.indices step 3) {
                    val indices = piece.mesh.indices.sliceArray(offset until offset + 3)
                    val x = indices.map { piece.mesh.uvs[it * 2].toDouble() }.average()
                    val y = indices.map { piece.mesh.uvs[it * 2 + 1].toDouble() }.average()
                    assertEquals(owner == 0, path.contains(x, y))
                }
                piece.sources.forEachIndexed { index, source ->
                    RasterMeshJournal.validateSource(source, original.vertexCount)
                    fun value(values: FloatArray, axis: Int) = when (source) {
                        is VertexSource.FromOld -> values[source.oldIndex * 2 + axis]
                        is VertexSource.BarycentricOf -> values[source.oldA * 2 + axis] * source.wa +
                            values[source.oldB * 2 + axis] * source.wb + values[source.oldC * 2 + axis] * source.wc
                        else -> error("Unexpected source")
                    }
                    for (axis in 0..1) {
                        assertEquals(value(original.positions, axis), piece.mesh.positions[index * 2 + axis], 0.00001f)
                        assertEquals(value(canvas, axis), piece.mesh.uvs[index * 2 + axis], 0.00001f)
                    }
                }
            }
            plan.ownerByVertex.forEachIndexed { index, owner ->
                val piece = plan.pieces[owner]
                assertTrue(piece.oldToNew[index] >= 0)
                assertEquals(VertexSource.FromOld(index), piece.sources[piece.oldToNew[index]])
            }
        }
    }

    @Test fun adjacentTrianglesWeldTheSameCutEdgeAndRetainTheirOriginalOrientation() {
        val original = mesh()
        val polygon = listOf(5.0 to -1.0, 11.0 to -1.0, 11.0 to 11.0, 5.0 to 11.0)
        val plan = SourcePartitionGeometry.polygon(original, canvas, polygon)
        for (piece in plan.pieces) {
            val center = piece.mesh.uvs.indices.step(2).filter { abs(piece.mesh.uvs[it] - 5f) < 1e-5f && abs(piece.mesh.uvs[it + 1] - 5f) < 1e-5f }
            assertEquals(1, center.size)
            assertTrue(piece.mesh.indices.count { it == center.single() / 2 } >= 2)
            for (offset in piece.mesh.indices.indices step 3) {
                val a = piece.mesh.indices[offset] * 2; val b = piece.mesh.indices[offset + 1] * 2; val c = piece.mesh.indices[offset + 2] * 2
                val xy = piece.mesh.uvs
                assertTrue((xy[b] - xy[a]) * (xy[c + 1] - xy[a + 1]) - (xy[c] - xy[a]) * (xy[b + 1] - xy[a + 1]) > 0f)
            }
        }
    }

    @Test fun componentPartitionRetainsUnusedGlueVerticesAndRejectsAnIslandSpanningTriangle() {
        val xy = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 8f, 8f, 10f, 8f, 8f, 10f, 100f, 100f)
        val original = DrawableMesh(xy, xy.copyOf(), intArrayOf(0, 1, 2, 3, 4, 5))
        val plan = SourcePartitionGeometry.components(original, xy, intArrayOf(0, 0, 0, 1, 1, 1, 1), 2)
        assertEquals(3, plan.pieces[0].mesh.vertexCount); assertEquals(4, plan.pieces[1].mesh.vertexCount)
        assertEquals(VertexSource.FromOld(6), plan.pieces[1].sources[plan.pieces[1].oldToNew[6]])
        assertFailsWith<IllegalArgumentException> { SourcePartitionGeometry.components(original, xy, intArrayOf(0, 1, 0, 1, 1, 1, 1), 2) }
    }

    @Test fun cancellationAndEmptyPiecesNeverMutateTheOriginalMesh() {
        val original = mesh(); val positions = original.positions.copyOf(); var checks = 0
        assertFailsWith<IllegalStateException> { SourcePartitionGeometry.polygon(original, canvas,
            listOf(4.0 to -1.0, 7.0 to -1.0, 7.0 to 11.0, 4.0 to 11.0)) { if (++checks == 3) error("Cancelled") } }
        assertFailsWith<IllegalArgumentException> { SourcePartitionGeometry.polygon(original, canvas,
            listOf(20.0 to 20.0, 30.0 to 20.0, 30.0 to 30.0, 20.0 to 30.0)) }
        assertContentEquals(positions, original.positions)
    }
}
