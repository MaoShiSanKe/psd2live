package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshResolutionTest {
	@Test fun unitScaleFollowsTheDocumentOnlyInDocumentUnits() {
		assertEquals(1f, MeshResolution.unitScale(MeshUnits.PIXELS, 8192, 8192))
		assertEquals(1f, MeshResolution.unitScale(MeshUnits.DOCUMENT, 1280, 1280))
		assertEquals(1f, MeshResolution.unitScale(MeshUnits.DOCUMENT, 2048, 1024))
		assertEquals(3f, MeshResolution.unitScale(MeshUnits.DOCUMENT, 4000, 6144))
		// A document barely past the reference is meshed as it is.
		assertEquals(1f, MeshResolution.unitScale(MeshUnits.DOCUMENT, 2080, 2080))
	}

	@Test fun reductionKeepsEveryPaintedPixelCovered() {
		val width = 301
		val height = 97
		val source = raster(width, height) { x, y -> x == 150 || (y == 40 && x in 10..12) }
		assertNull(MeshResolution.reduce(width, height, source, 1f))
		val reduced = assertNotNull(MeshResolution.reduce(width, height, source, 2.5f))
		assertEquals(121, reduced.width)
		assertEquals(39, reduced.height)
		for (y in 0 until height) for (x in 0 until width) {
			if (source[(y * width + x) * 4 + 3].toInt() == 0) continue
			val rx = (x / reduced.scale).toInt()
			val ry = (y / reduced.scale).toInt()
			assertEquals(-1, reduced.rgba[(ry * reduced.width + rx) * 4 + 3].toInt(), "($x, $y) lost at ($rx, $ry)")
		}
	}

	/** The same drawing at three times the resolution gets the same mesh, three times as large. */
	@Test fun documentUnitsGiveTheSameMeshAtAnyResolution() {
		val factor = 3
		val small = drawing(1)
		val large = drawing(factor)
		val settings = MeshSettings(maxEdgeDistance = 12f, interiorDensity = 26f)
		val reference = assertNotNull(AdaptiveMeshGenerator.generate(small.width, small.height, small.rgba, 8, settings))
		val started = System.nanoTime()
		val scaled = assertNotNull(AdaptiveMeshGenerator.generate(large.width, large.height, large.rgba, 8, settings, factor.toFloat()))
		val scaledMillis = (System.nanoTime() - started) / 1_000_000
		val referenceVertices = reference.positions.size / 2
		val scaledVertices = scaled.positions.size / 2
		assertTrue(abs(scaledVertices - referenceVertices) <= max(8, referenceVertices / 10),
			"$scaledVertices vertices at ${factor}x against $referenceVertices at 1x")
		val referenceBounds = bounds(reference.positions)
		val scaledBounds = bounds(scaled.positions)
		for (side in 0..3) assertTrue(abs(scaledBounds[side] - referenceBounds[side] * factor) <= factor * 1.5f,
			"bounds ${scaledBounds.toList()} against ${referenceBounds.map { it * factor }}")
		assertCovers(scaled, large)

		val pixelStarted = System.nanoTime()
		val pixels = assertNotNull(AdaptiveMeshGenerator.generate(large.width, large.height, large.rgba, 8, settings))
		val pixelMillis = (System.nanoTime() - pixelStarted) / 1_000_000
		val pixelVertices = pixels.positions.size / 2
		println("mesh at ${factor}x: $scaledVertices vertices in $scaledMillis ms; in source pixels $pixelVertices in $pixelMillis ms")
		assertTrue(scaledVertices * 2 < pixelVertices, "document units must not mesh source pixels: $scaledVertices against $pixelVertices")
	}

	private class Raster(val width: Int, val height: Int, val rgba: ByteArray)

	/** A body with a hole and a one-pixel hair strand, drawn at [scale]. */
	private fun drawing(scale: Int): Raster {
		val width = 360 * scale
		val height = 280 * scale
		val rgba = raster(width, height) { x, y ->
			val u = (x + 0.5) / scale
			val v = (y + 0.5) / scale
			val dx = (u - 170) / 140
			val dy = (v - 140) / 100
			val hx = (u - 200) / 22
			val hy = (v - 130) / 18
			val body = dx * dx + dy * dy <= 1.0 && hx * hx + hy * hy > 1.0
			val strand = x in (322 * scale) until (322 * scale + 1) && v in 40.0..250.0
			body || strand
		}
		return Raster(width, height, rgba)
	}

	private fun raster(width: Int, height: Int, inside: (Int, Int) -> Boolean): ByteArray {
		val rgba = ByteArray(width * height * 4)
		for (y in 0 until height) for (x in 0 until width) if (inside(x, y)) rgba[(y * width + x) * 4 + 3] = -1
		return rgba
	}

	private fun bounds(positions: FloatArray): FloatArray {
		val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
		for (i in positions.indices step 2) {
			out[0] = min(out[0], positions[i]); out[1] = min(out[1], positions[i + 1])
			out[2] = max(out[2], positions[i]); out[3] = max(out[3], positions[i + 1])
		}
		return out
	}

	/** Every opaque pixel centre lies in some triangle. */
	private fun assertCovers(mesh: AdaptiveMeshGenerator.Result, raster: Raster) {
		val covered = BooleanArray(raster.width * raster.height)
		val p = mesh.positions
		for (t in mesh.indices.indices step 3) {
			val ax = p[mesh.indices[t] * 2].toDouble(); val ay = p[mesh.indices[t] * 2 + 1].toDouble()
			val bx = p[mesh.indices[t + 1] * 2].toDouble(); val by = p[mesh.indices[t + 1] * 2 + 1].toDouble()
			val cx = p[mesh.indices[t + 2] * 2].toDouble(); val cy = p[mesh.indices[t + 2] * 2 + 1].toDouble()
			val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
			if (area == 0.0) continue
			val x0 = max(0, minOf(ax, bx, cx).toInt() - 1); val x1 = min(raster.width - 1, maxOf(ax, bx, cx).toInt() + 1)
			val y0 = max(0, minOf(ay, by, cy).toInt() - 1); val y1 = min(raster.height - 1, maxOf(ay, by, cy).toInt() + 1)
			for (y in y0..y1) for (x in x0..x1) {
				val px = x + 0.5; val py = y + 0.5
				val w0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
				val w1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
				if (w0 >= -1e-6 && w1 >= -1e-6 && w0 + w1 <= 1 + 1e-6) covered[y * raster.width + x] = true
			}
		}
		var missing = 0
		for (i in covered.indices) if (raster.rgba[i * 4 + 3].toInt() != 0 && !covered[i]) missing++
		assertEquals(0, missing, "opaque pixels outside the mesh")
	}
}
