package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.hypot
import java.util.PriorityQueue

/**
 * One bone of a limb as the skinning sees it: its rest segment in canvas pixels, the index of its parent
 * within the same limb (-1 at the limb's root), and the half width of the band around its head joint in
 * which vertices blend from the parent into it.
 */
internal class SkinBone(
	val parent: Int,
	val headX: Double,
	val headY: Double,
	val tailX: Double,
	val tailY: Double,
	val blend: Double,
) {
	val length: Double get() = hypot(tailX - headX, tailY - headY)
	val dirX: Double get() = (tailX - headX) / length.coerceAtLeast(1e-9)
	val dirY: Double get() = (tailY - headY) / length.coerceAtLeast(1e-9)
}

/**
 * How one vertex follows its limb: rigidly with bone [from], or blended with its child [to] by
 * [weight]. [from] == [to] (weight 0) is a vertex that is rigid to one bone.
 */
internal class VertexSkin(val from: Int, val to: Int, val weight: Float) {
	val rigid: Boolean get() = from == to || weight <= 0f
}

/**
 * Automatic weights and the joint blend.
 *
 * Every vertex follows at most two bones, and only across one joint: the parent and the child that meet
 * there. The blend runs across a band centered on the joint whose axis is the bisector of the two bones,
 * so the band is symmetric however far the limb is bent at rest.
 *
 * The rig uses normalized linear blend skinning for bone transforms and an ARAP correction for
 * the mixed joint regions. Vertices outside those regions are exact rigid handles; the correction
 * can therefore improve a tight bend without pulling on the entire upper arm or thigh.
 */
internal object SkeletonWeights {
	/** Default half width of a joint band, as a fraction of the shorter of the two bones meeting there. */
	private const val DEFAULT_BLEND = 0.35

	/** Hard cap on the half width, so the bands at the two ends of one bone never overlap. */
	private const val MAX_BLEND = 0.45

	/** The half width of [bone]'s head joint band given its parent's length, clamped to the geometry. */
	fun blendHalfWidth(bone: SkeletonBone, parentLength: Float?): Double {
		if (parentLength == null) return 0.0
		val shorter = minOf(bone.length, parentLength).toDouble()
		val wanted = bone.blendWidth?.toDouble() ?: (shorter * DEFAULT_BLEND)
		return wanted.coerceIn(1.0, (shorter * MAX_BLEND).coerceAtLeast(1.0))
	}

	/**
	 * The axis of the band at [child]'s head joint: the unit bisector of the parent and child directions.
	 * Signed distance along it is how far past the joint a point lies.
	 */
	fun bandNormal(bones: List<SkinBone>, child: Int): DoubleArray {
		val c = bones[child]
		val p = bones.getOrNull(c.parent) ?: return doubleArrayOf(c.dirX, c.dirY)
		val sx = p.dirX + c.dirX
		val sy = p.dirY + c.dirY
		val length = hypot(sx, sy)
		return if (length < 1e-6) doubleArrayOf(c.dirX, c.dirY) else doubleArrayOf(sx / length, sy / length)
	}

	/** Signed distance of ([x], [y]) past [child]'s head joint along its band axis. */
	fun bandDistance(bones: List<SkinBone>, child: Int, x: Double, y: Double): Double {
		val n = bandNormal(bones, child)
		val c = bones[child]
		return (x - c.headX) * n[0] + (y - c.headY) * n[1]
	}

	/** Cubic smoothstep of [t] clamped to [0, 1]; flat at both ends so the band has no crease at its edges. */
	fun smoothstep(t: Double): Double {
		val x = t.coerceIn(0.0, 1.0)
		return x * x * (3.0 - 2.0 * x)
	}

	/**
	 * Skins every vertex of [canvas] (interleaved x, y in canvas pixels) to [bones].
	 *
	 * The nearest bone gives each vertex an initial weight. The mesh edges then diffuse ambiguous
	 * weights from rigid regions within each connected component. Near both joints of a short bone
	 * the closer joint wins.
	 */
	fun skin(canvas: FloatArray, bones: List<SkinBone>, triangles: IntArray): List<VertexSkin> {
		require(bones.isNotEmpty())
		val children = bones.indices.groupBy { bones[it].parent }
		val initial = (0 until canvas.size / 2).map { vertex ->
			val x = canvas[vertex * 2].toDouble()
			val y = canvas[vertex * 2 + 1].toDouble()
			val primary = bones.indices.minBy { segmentDistance(bones[it], x, y) }

			var best: VertexSkin? = null
			var bestDistance = Double.MAX_VALUE
			fun consider(parent: Int, child: Int) {
				val half = bones[child].blend
				if (half <= 0.0) return
				val s = bandDistance(bones, child, x, y)
				// The head joint only matters while the vertex is short of the far edge of its band; a
				// tail joint only once the vertex reaches the band's near edge.
				val relevant = if (child == primary) s < half else s > -half
				if (!relevant || abs(s) >= bestDistance) return
				bestDistance = abs(s)
				val weight = smoothstep((s + half) / (2.0 * half)).toFloat()
				best = when {
					weight <= 0f -> VertexSkin(parent, parent, 0f)
					weight >= 1f -> VertexSkin(child, child, 0f)
					else -> VertexSkin(parent, child, weight)
				}
			}
			bones[primary].parent.takeIf { it >= 0 }?.let { consider(it, primary) }
			for (child in children[primary].orEmpty()) consider(primary, child)
			best ?: VertexSkin(primary, primary, 0f)
		}
		if (triangles.isEmpty()) return initial
		return followMeshBranches(canvas, triangles, bones, initial)
	}

	/**
	 * A band is an infinite line in canvas space. On a broad or branching drawing, a digit can extend
	 * sideways into the parent's side of that line although it is connected to the child's rigid region.
	 * Use distance along the mesh surface to the rigid regions to resolve those lateral vertices. The
	 * original axial blend remains at the joint centre and at both edges of the band.
	 */
	private fun followMeshBranches(
		canvas: FloatArray, triangles: IntArray, bones: List<SkinBone>, initial: List<VertexSkin>,
	): List<VertexSkin> {
		val count = initial.size
		val edges = Array(count) { HashMap<Int, Double>() }
		fun edge(a: Int, b: Int) {
			if (a !in 0 until count || b !in 0 until count || a == b) return
			val length = hypot((canvas[a * 2] - canvas[b * 2]).toDouble(), (canvas[a * 2 + 1] - canvas[b * 2 + 1]).toDouble())
			edges[a][b] = minOf(edges[a][b] ?: Double.POSITIVE_INFINITY, length)
			edges[b][a] = minOf(edges[b][a] ?: Double.POSITIVE_INFINITY, length)
		}
		for (at in 0 until triangles.size - 2 step 3) {
			val a = triangles[at]; val b = triangles[at + 1]; val c = triangles[at + 2]
			edge(a, b); edge(b, c); edge(c, a)
		}
		fun distances(seeds: List<Int>): DoubleArray {
			val result = DoubleArray(count) { Double.POSITIVE_INFINITY }
			val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
			for (seed in seeds) { result[seed] = 0.0; queue.add(0.0 to seed) }
			while (queue.isNotEmpty()) {
				val (distance, vertex) = queue.remove()
				if (distance > result[vertex]) continue
				for ((neighbor, length) in edges[vertex]) {
					val next = distance + length
					if (next < result[neighbor]) { result[neighbor] = next; queue.add(next to neighbor) }
				}
			}
			return result
		}
		val result = initial.toMutableList()
		for (child in bones.indices) {
			val parent = bones[child].parent
			val half = bones[child].blend
			if (parent < 0 || half <= 0.0) continue
			val normal = bandNormal(bones, child)
			val axial = DoubleArray(count) { vertex ->
				val dx = canvas[vertex * 2] - bones[child].headX
				val dy = canvas[vertex * 2 + 1] - bones[child].headY
				dx * normal[0] + dy * normal[1]
			}
			val parentSeeds = (0 until count).filter { initial[it].from == parent && initial[it].rigid && axial[it] <= -half }
			val childSeeds = (0 until count).filter { initial[it].from == child && initial[it].rigid && axial[it] >= half }
			if (parentSeeds.isEmpty() || childSeeds.isEmpty()) continue
			val fromParent = distances(parentSeeds)
			val fromChild = distances(childSeeds)
			for (vertex in 0 until count) {
				val skin = initial[vertex]
				if ((skin.from != parent && skin.from != child) || (skin.to != parent && skin.to != child)) continue
				val s = axial[vertex]
				if (abs(s) >= half) continue
				val parentDistance = fromParent[vertex]
				val childDistance = fromChild[vertex]
				if (!parentDistance.isFinite() || !childDistance.isFinite() || parentDistance + childDistance <= 1e-9) continue
				val dx = canvas[vertex * 2] - bones[child].headX
				val dy = canvas[vertex * 2 + 1] - bones[child].headY
				val lateral = abs(dx * -normal[1] + dy * normal[0])
				val across = smoothstep(lateral / half)
				val edgeFade = (1.0 - (s / half) * (s / half)).coerceIn(0.0, 1.0)
				val geometric = parentDistance / (parentDistance + childDistance)
				val original = when {
					skin.from == child -> 1.0
					skin.rigid -> 0.0
					else -> skin.weight.toDouble()
				}
				val weight = (original + (geometric - original) * across * edgeFade).coerceIn(0.0, 1.0).toFloat()
				result[vertex] = when {
					weight <= 0f -> VertexSkin(parent, parent, 0f)
					weight >= 1f -> VertexSkin(child, child, 0f)
					else -> VertexSkin(parent, child, weight)
				}
			}
		}
		return result
	}

	/** Distance from ([x], [y]) to [bone]'s segment. */
	fun segmentDistance(bone: SkinBone, x: Double, y: Double): Double {
		val dx = bone.tailX - bone.headX
		val dy = bone.tailY - bone.headY
		val lengthSquared = dx * dx + dy * dy
		val t = if (lengthSquared < 1e-12) 0.0 else (((x - bone.headX) * dx + (y - bone.headY) * dy) / lengthSquared).coerceIn(0.0, 1.0)
		return hypot(x - (bone.headX + dx * t), y - (bone.headY + dy * t))
	}
}
