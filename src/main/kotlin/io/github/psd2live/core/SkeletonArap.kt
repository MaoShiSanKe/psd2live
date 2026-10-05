package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 2D vertex-spokes ARAP, Sorkine & Alexa (2007), equations 3, 6 and 9.
 * https://igl.ethz.ch/projects/ARAP/arap_web.pdf
 * The joint band is free; rigid skin regions are Dirichlet handles. The topology and cotangent
 * Laplacian are reused for every bake sample. Local rotations use the closed-form 2D polar fit;
 * the global SPD system is solved with diagonally preconditioned conjugate gradients.
 */
internal class SkeletonArap(private val rest: FloatArray, private val triangles: IntArray, movable: BooleanArray) {
	private class Edge(val a: Int, val b: Int, val weight: Double)
	private val count = rest.size / 2
	private val edges: List<Edge>
	private val free = movable.copyOf()
	private val diagonal = DoubleArray(count)

	init {
		val weights = HashMap<Long, Double>()
		fun add(a: Int, b: Int, opposite: Int) {
			val ux = (rest[a * 2] - rest[opposite * 2]).toDouble()
			val uy = (rest[a * 2 + 1] - rest[opposite * 2 + 1]).toDouble()
			val vx = (rest[b * 2] - rest[opposite * 2]).toDouble()
			val vy = (rest[b * 2 + 1] - rest[opposite * 2 + 1]).toDouble()
			val area = abs(ux * vy - uy * vx)
			val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
			weights[key] = (weights[key] ?: 0.0) + if (area > 1e-8) (ux * vx + uy * vy) / (2.0 * area) else 0.0
		}
		for (i in triangles.indices step 3) {
			val a = triangles[i]; val b = triangles[i + 1]; val c = triangles[i + 2]
			add(a, b, c); add(b, c, a); add(c, a, b)
		}
		// Non-Delaunay/degenerate input can have nonpositive edge weights. Positive weights keep
		// the local energy and global system well defined without changing the artwork's topology.
		edges = weights.map { (key, weight) -> Edge((key ushr 32).toInt(), key.toInt(), weight.coerceAtLeast(1e-6)) }
		val neighbors = Array(count) { ArrayList<Int>() }
		for (e in edges) {
			diagonal[e.a] += e.weight; diagonal[e.b] += e.weight
			neighbors[e.a] += e.b; neighbors[e.b] += e.a
		}
		val seen = BooleanArray(count)
		for (start in 0 until count) if (!seen[start]) {
			val component = ArrayList<Int>()
			component += start; seen[start] = true
			var i = 0
			while (i < component.size) for (next in neighbors[component[i++]]) if (!seen[next]) {
				seen[next] = true; component += next
			}
			// An island with no rigid region has no positional constraints. Keep its skinning result
			// rather than inventing an attachment or solving a singular translation mode.
			if (component.all { free[it] }) for (vertex in component) free[vertex] = false
		}
	}

	fun solve(target: FloatArray, seed: FloatArray = target, guide: FloatArray = seed, guideWeights: DoubleArray = DoubleArray(count)): FloatArray {
		if (free.none { it }) return target
		val penalty = DoubleArray(count) { if (free[it]) diagonal[it] * guideWeights[it] else 0.0 }
		val x = DoubleArray(count) { (if (free[it]) seed else target)[it * 2].toDouble() }
		val y = DoubleArray(count) { (if (free[it]) seed else target)[it * 2 + 1].toDouble() }
		val c = DoubleArray(count)
		val s = DoubleArray(count)
		val dot = DoubleArray(count)
		val cross = DoubleArray(count)
		val bx = DoubleArray(count)
		val by = DoubleArray(count)
		repeat(30) {
			dot.fill(0.0); cross.fill(0.0); bx.fill(0.0); by.fill(0.0)
			for (e in edges) {
				val rx = (rest[e.a * 2] - rest[e.b * 2]).toDouble()
				val ry = (rest[e.a * 2 + 1] - rest[e.b * 2 + 1]).toDouble()
				val dx = x[e.a] - x[e.b]; val dy = y[e.a] - y[e.b]
				val d = e.weight * (rx * dx + ry * dy)
				val t = e.weight * (rx * dy - ry * dx)
				dot[e.a] += d; dot[e.b] += d; cross[e.a] += t; cross[e.b] += t
			}
			for (i in 0 until count) {
				val length = hypot(dot[i], cross[i])
				c[i] = if (length > 1e-12) dot[i] / length else 1.0
				s[i] = if (length > 1e-12) cross[i] / length else 0.0
			}
			for (e in edges) {
				val rx = (rest[e.a * 2] - rest[e.b * 2]).toDouble()
				val ry = (rest[e.a * 2 + 1] - rest[e.b * 2 + 1]).toDouble()
				val dx = e.weight * 0.5 * ((c[e.a] + c[e.b]) * rx - (s[e.a] + s[e.b]) * ry)
				val dy = e.weight * 0.5 * ((s[e.a] + s[e.b]) * rx + (c[e.a] + c[e.b]) * ry)
				bx[e.a] += dx; bx[e.b] -= dx; by[e.a] += dy; by[e.b] -= dy
				if (!free[e.b]) { bx[e.a] += e.weight * x[e.b]; by[e.a] += e.weight * y[e.b] }
				if (!free[e.a]) { bx[e.b] += e.weight * x[e.a]; by[e.b] += e.weight * y[e.a] }
			}
			for (i in 0 until count) {
				bx[i] += penalty[i] * guide[i * 2]; by[i] += penalty[i] * guide[i * 2 + 1]
			}
			val nextX = x.copyOf(); val nextY = y.copyOf()
			global(nextX, bx, penalty); global(nextY, by, penalty)
			val step = safeStep(x, y, nextX, nextY)
			var change = 0.0
			for (i in 0 until count) {
				val dx = step * (nextX[i] - x[i]); val dy = step * (nextY[i] - y[i])
				x[i] += dx; y[i] += dy; change = maxOf(change, abs(dx), abs(dy))
			}
			if (change < 1e-4) return pack(x, y)
		}
		return pack(x, y)
	}

	private fun pack(x: DoubleArray, y: DoubleArray) = FloatArray(rest.size) { if (it % 2 == 0) x[it / 2].toFloat() else y[it / 2].toFloat() }

	/** libigl's flip-avoiding line-search principle: cap the step before the first area zero.
	 * https://libigl.github.io/dox/flip__avoiding__line__search_8h.html
	 * The fixed-rotation ARAP global energy is quadratic, so a capped descent to its minimizer
	 * also decreases that energy. Keep a small area margin for float keyform storage.
	 */
	private fun safeStep(x: DoubleArray, y: DoubleArray, nx: DoubleArray, ny: DoubleArray): Double {
		var step = 1.0
		for (i in triangles.indices step 3) {
			val a = triangles[i]; val b = triangles[i + 1]; val c = triangles[i + 2]
			val ux = x[b] - x[a]; val uy = y[b] - y[a]; val vx = x[c] - x[a]; val vy = y[c] - y[a]
			val dux = nx[b] - nx[a] - ux; val duy = ny[b] - ny[a] - uy
			val dvx = nx[c] - nx[a] - vx; val dvy = ny[c] - ny[a] - vy
			val restArea = (rest[b * 2] - rest[a * 2]).toDouble() * (rest[c * 2 + 1] - rest[a * 2 + 1]) -
				(rest[b * 2 + 1] - rest[a * 2 + 1]).toDouble() * (rest[c * 2] - rest[a * 2])
			if (abs(restArea) < 1e-8) continue
			val sign = if (restArea > 0) 1.0 else -1.0
			val q = sign * (dux * dvy - duy * dvx)
			val l = sign * (ux * dvy + dux * vy - uy * dvx - duy * vx)
			val constant = sign * (ux * vy - uy * vx) - abs(restArea) * 0.01
			if (constant <= 0.0) continue // a pre-existing invalid seed is not repaired by line search
			fun cap(root: Double) { if (root > 0.0) step = minOf(step, root * 0.8) }
			if (abs(q) < 1e-12) { if (l < 0.0) cap(-constant / l) } else {
				val discriminant = l * l - 4.0 * q * constant
				if (discriminant >= 0.0) {
					val numerator = -0.5 * (l + Math.copySign(kotlin.math.sqrt(discriminant), l))
					cap(numerator / q)
					if (abs(numerator) > 1e-20) cap(constant / numerator)
				}
			}
		}
		return step.coerceIn(0.0, 1.0)
	}

	private fun multiply(input: DoubleArray, out: DoubleArray, penalty: DoubleArray) {
		for (i in 0 until count) out[i] = if (free[i]) (diagonal[i] + penalty[i]) * input[i] else 0.0
		for (e in edges) if (free[e.a] && free[e.b]) {
			out[e.a] -= e.weight * input[e.b]; out[e.b] -= e.weight * input[e.a]
		}
	}

	private fun global(position: DoubleArray, rhs: DoubleArray, penalty: DoubleArray) {
		val product = DoubleArray(count)
		multiply(position, product, penalty)
		val r = DoubleArray(count) { if (free[it]) rhs[it] - product[it] else 0.0 }
		val z = DoubleArray(count) { if (free[it]) r[it] / (diagonal[it] + penalty[it]) else 0.0 }
		val direction = z.copyOf()
		fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
		var rz = dot(r, z)
		for (iteration in 0 until minOf(count * 2, 200)) {
			if (rz < 1e-12) break
			multiply(direction, product, penalty)
			val denominator = dot(direction, product)
			if (denominator <= 1e-20) break
			val alpha = rz / denominator
			for (i in 0 until count) if (free[i]) {
				position[i] += alpha * direction[i]; r[i] -= alpha * product[i]; z[i] = r[i] / (diagonal[i] + penalty[i])
			}
			val next = dot(r, z)
			val beta = next / rz
			for (i in 0 until count) direction[i] = z[i] + beta * direction[i]
			rz = next
		}
	}
}
