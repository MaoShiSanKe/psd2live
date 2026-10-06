package io.github.psd2live.core

import kotlin.math.hypot
import kotlin.math.abs

/** Taubin's lambda/mu signal filter (SIGGRAPH 1995) on the movable joint surface.
 * https://research.ibm.com/publications/signal-processing-approach-to-fair-surface-design
 * A positive and negative Laplacian pass suppress alternating wrinkles without the systematic
 * shrinkage of repeated positive averaging. Interior cotangent weights smooth the deformation
 * between contour vertices, where the painted silhouette often lies. Boundary arc lengths keep
 * straight contour sections fixed. The pose mask pins rigid handles and closed contact points.
 */
internal class SkeletonSurfaceFairing(rest: FloatArray, triangles: IntArray) {
	private val neighbors = Array(rest.size / 2) { mutableListOf<Pair<Int, Double>>() }
	private val surface = Array(rest.size / 2) { mutableListOf<Pair<Int, Double>>() }
	init {
		val edges = HashMap<Long, Int>()
		val cotangents = HashMap<Long, Double>()
		for (i in triangles.indices step 3) for (k in 0..2) {
			val a = triangles[i + k]; val b = triangles[i + (k + 1) % 3]
			val c = triangles[i + (k + 2) % 3]
			val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
			edges[key] = (edges[key] ?: 0) + 1
			val ux = (rest[a * 2] - rest[c * 2]).toDouble(); val uy = (rest[a * 2 + 1] - rest[c * 2 + 1]).toDouble()
			val vx = (rest[b * 2] - rest[c * 2]).toDouble(); val vy = (rest[b * 2 + 1] - rest[c * 2 + 1]).toDouble()
			val area = abs(ux * vy - uy * vx)
			cotangents[key] = (cotangents[key] ?: 0.0) + if (area > 1e-8) (ux * vx + uy * vy) / area else 0.0
		}
		for ((key, cotangent) in cotangents) {
			val a = (key ushr 32).toInt(); val b = key.toInt(); val weight = cotangent.coerceAtLeast(1e-6)
			surface[a] += b to weight; surface[b] += a to weight
		}
		for ((key, count) in edges) if (count == 1) {
			val a = (key ushr 32).toInt(); val b = key.toInt()
			val length = hypot((rest[a * 2] - rest[b * 2]).toDouble(), (rest[a * 2 + 1] - rest[b * 2 + 1]).toDouble()).coerceAtLeast(1e-6)
			neighbors[a] += b to length; neighbors[b] += a to length
		}
	}

	fun apply(points: FloatArray, mask: DoubleArray) {
		if (mask.none { it > 0.0 }) return
		var source = points.copyOf(); var destination = FloatArray(points.size)
		repeat(4) {
			for (coefficient in doubleArrayOf(.5, -1.0 / 1.9)) {
				source.copyInto(destination)
				for (v in neighbors.indices) {
					if (mask[v] <= 0.0 || surface[v].isEmpty()) continue
					for (axis in 0..1) {
						val average = if (neighbors[v].size == 2) {
							val (a, la) = neighbors[v][0]; val (b, lb) = neighbors[v][1]
							(source[a * 2 + axis] * lb + source[b * 2 + axis] * la) / (la + lb)
						} else if (neighbors[v].isEmpty()) {
							surface[v].sumOf { (next, weight) -> source[next * 2 + axis] * weight } / surface[v].sumOf { it.second }
						} else source[v * 2 + axis].toDouble() // keep non-manifold boundary junctions fixed
						destination[v * 2 + axis] = (source[v * 2 + axis] + coefficient * mask[v] * (average - source[v * 2 + axis])).toFloat()
					}
				}
				val swap = source; source = destination; destination = swap
			}
		}
		source.copyInto(points)
	}
}
