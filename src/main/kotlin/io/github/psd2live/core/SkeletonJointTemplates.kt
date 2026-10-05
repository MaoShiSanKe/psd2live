package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Sampled surface cages from MHR v1.0.1 (Apache-2.0), not analytic joint fillets.
 * The tracked inner/outer surfaces remain separate at flexion. Reusable skinning templates
 * (Ju et al. 2008) supply the local cage; pose-space deformation (Lewis et al. 2000) supplies
 * angle interpolation. The template is a soft constraint in the bake's ARAP solve, never a
 * replacement for an artist's rigid weights. No model inference is needed in the app.
 */
internal class SkeletonJointTemplates(
	private val canvas: FloatArray, triangles: IntArray, private val skins: List<VertexSkin>,
	private val bones: List<SkinBone>, roles: List<BoneRole>,
) {
	private class Joint(val child: Int, val kind: String, val ux: Double, val uy: Double, val radius: Double, val center: Double)
	private val joints: Map<Int, Joint>

	init {
		val edges = HashMap<Long, Int>()
		for (i in triangles.indices step 3) for (k in 0..2) {
			val a = triangles[i + k]; val b = triangles[i + (k + 1) % 3]
			val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
			edges[key] = (edges[key] ?: 0) + 1
		}
		joints = skins.filter { !it.rigid }.map { it.to }.distinct().mapNotNull { child ->
			val c = bones[child]; val p = bones.getOrNull(c.parent) ?: return@mapNotNull null
			val kind = when {
				roles[child] == BoneRole.SHIN && roles[c.parent] == BoneRole.THIGH -> "knee"
				roles[child] == BoneRole.FOREARM && roles[c.parent] == BoneRole.UPPER_ARM -> "elbow"
				else -> return@mapNotNull null
			}
			// The extracted cages describe an extended rest limb. Strongly bent authored rest
			// shapes need their own cage fitting; retain ARAP instead of imposing a straight limb.
			if (abs(atan2(p.dirX * c.dirY - p.dirY * c.dirX, p.dirX * c.dirX + p.dirY * c.dirY)) > Math.toRadians(25.0)) return@mapNotNull null
			val u = SkeletonWeights.bandNormal(bones, child)
			fun coordinates(v: Int): DoubleArray {
				val x = canvas[v * 2] - c.headX; val y = canvas[v * 2 + 1] - c.headY
				return doubleArrayOf(x * u[0] + y * u[1], -x * u[1] + y * u[0])
			}
			fun belongs(v: Int) = skins[v].let { it.from == c.parent || it.from == child || it.to == child }
			val crossings = edges.filterValues { it == 1 }.keys.mapNotNull { key ->
				val a = (key ushr 32).toInt(); val b = key.toInt()
				if (!belongs(a) || !belongs(b)) return@mapNotNull null
				val q = coordinates(a); val r = coordinates(b)
				if (q[0] * r[0] > 0 || abs(q[0] - r[0]) < 1e-9) return@mapNotNull null
				q[1] + (r[1] - q[1]) * q[0] / (q[0] - r[0])
			}
			val low = crossings.filter { it < 0 }.maxOrNull() ?: return@mapNotNull null
			val high = crossings.filter { it > 0 }.minOrNull() ?: return@mapNotNull null
			val radius = (high - low) * 0.5
			if (radius < 1.0 || radius > minOf(p.length, c.length) * 0.5) return@mapNotNull null
			child to Joint(child, kind, u[0], u[1], radius, (high + low) * 0.5)
		}.toMap()
	}

	/** World-space guide and dimensionless ARAP penalty. Parent rotation carries the local cage;
	 * only mixed vertices get a penalty, so neither roots nor distal rigid sections can move.
	 */
	fun guide(seed: FloatArray, angles: FloatArray, carry: List<DoubleArray>? = null): Pair<FloatArray, DoubleArray> {
		val target = seed.copyOf()
		val penalties = DoubleArray(skins.size)
		for ((vertex, skin) in skins.withIndex()) {
			if (skin.rigid) continue
			val joint = joints[skin.to] ?: continue
			val bone = bones[skin.to]
			val angle = SkeletonIk.wrap((angles[skin.to] - angles[skin.from]).toDouble())
			if (abs(angle) < 1e-6 || abs(angle) > 150.0) continue
			val side = if (angle >= 0) 1.0 else -1.0
			val x = canvas[vertex * 2] - bone.headX; val y = canvas[vertex * 2 + 1] - bone.headY
			val axial = (x * joint.ux + y * joint.uy) / joint.radius
			if (abs(axial) >= 3.0) continue
			val transverse = side * (-x * joint.uy + y * joint.ux - joint.center) / joint.radius
			val fraction = ((transverse + 1.0) * 0.5).coerceIn(0.0, 1.0)
			val neutral = sample(joint.kind, 0.0, axial, fraction)
			val posed = sample(joint.kind, abs(angle), axial, fraction)
			val rotation = Math.toRadians(abs(angle) * skin.weight)
			val dx = posed[0] - (cos(rotation) * neutral[0] - sin(rotation) * neutral[1])
			val dy = side * (posed[1] - (sin(rotation) * neutral[0] + cos(rotation) * neutral[1]))
			val parentAngle = Math.toRadians(angles[skin.from].toDouble())
			val matrix = carry?.get(skin.from) ?: doubleArrayOf(cos(parentAngle), -sin(parentAngle), sin(parentAngle), cos(parentAngle))
			val rx = (joint.ux * dx - joint.uy * dy) * joint.radius
			val ry = (joint.uy * dx + joint.ux * dy) * joint.radius
			target[vertex * 2] += (matrix[0] * rx + matrix[1] * ry).toFloat()
			target[vertex * 2 + 1] += (matrix[2] * rx + matrix[3] * ry).toFloat()
			val taper = 1.0 - SkeletonWeights.smoothstep((abs(axial) - 2.0).coerceAtLeast(0.0))
			penalties[vertex] = 4.0 * taper * 4.0 * skin.weight * (1.0 - skin.weight)
		}
		return target to penalties
	}

	companion object {
		private data class Row(val angle: Double, val axial: Double, val coordinates: DoubleArray)
		private val cages: Map<String, Map<Double, List<Row>>> by lazy {
			checkNotNull(SkeletonJointTemplates::class.java.getResourceAsStream("/skinning/mhr-joints.tsv"))
				.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
					val f = line.split('\t')
					f[0] to Row(f[1].toDouble(), f[2].toDouble(), f.drop(5).map(String::toDouble).toDoubleArray())
				}.toList().groupBy({ it.first }, { it.second }).mapValues { (_, rows) -> rows.groupBy { it.angle } } }
		}
		internal fun sample(kind: String, angle: Double, axial: Double, fraction: Double): DoubleArray {
			val rows = cages.getValue(kind)
			val a = angle.coerceIn(0.0, 150.0); val s = axial.coerceIn(-3.0, 3.0)
			val lo = kotlin.math.floor(a / 15.0) * 15.0; val hi = minOf(lo + 15.0, 150.0)
			fun atAngle(value: Double): DoubleArray {
				val profile = rows.getValue(value)
				val index = kotlin.math.floor((s + 3.0) * 4.0).toInt().coerceIn(0, profile.size - 2)
				val t = ((s - profile[index].axial) * 4.0).coerceIn(0.0, 1.0)
				fun atRow(row: Row, axis: Int) = row.coordinates[axis] * (1.0 - fraction) + row.coordinates[axis + 2] * fraction
				return DoubleArray(2) { atRow(profile[index], it) * (1.0 - t) + atRow(profile[index + 1], it) * t }
			}
			val lower = atAngle(lo); val upper = atAngle(hi)
			val t = if (hi == lo) 0.0 else (a - lo) / (hi - lo)
			return DoubleArray(2) { lower[it] * (1.0 - t) + upper[it] * t }
		}
	}
}
