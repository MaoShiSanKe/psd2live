package io.github.psd2live.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Sampled surface cages from MHR v1.0.1 (Apache-2.0), not analytic joint fillets.
 * The tracked inner/outer surfaces supply the pose reference; the inner patch can close into
 * a zero-thickness crease. Reusable skinning templates
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
	private val boundary = BooleanArray(skins.size)

	init {
		val edges = HashMap<Long, Int>()
		for (i in triangles.indices step 3) for (k in 0..2) {
			val a = triangles[i + k]; val b = triangles[i + (k + 1) % 3]
			val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
			edges[key] = (edges[key] ?: 0) + 1
		}
		for ((edge, count) in edges) if (count == 1) {
			boundary[(edge ushr 32).toInt()] = true; boundary[edge.toInt()] = true
		}
		joints = skins.filter { !it.rigid }.map { it.to }.distinct().mapNotNull { child ->
			val c = bones[child]; val p = bones.getOrNull(c.parent) ?: return@mapNotNull null
			val kind = when {
				roles[child] == BoneRole.SHIN && roles[c.parent] == BoneRole.THIGH -> "knee"
				roles[child] == BoneRole.FOREARM && roles[c.parent] == BoneRole.UPPER_ARM -> "elbow"
				else -> "generic"
			}
			// The extracted cages describe an extended rest limb. Strongly bent authored rest
			// shapes need their own cage fitting; retain the ARAP outer shape and generic
			// inner crease instead of imposing an extended human reference cage.
			val fittedKind = if (abs(atan2(p.dirX * c.dirY - p.dirY * c.dirX, p.dirX * c.dirX + p.dirY * c.dirY)) > Math.toRadians(25.0)) "generic" else kind
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
			child to Joint(child, fittedKind, u[0], u[1], radius, (high + low) * 0.5)
		}.toMap()
	}

	/** World-space guide and dimensionless ARAP penalty. Parent rotation carries the local cage;
	 * only mixed vertices get a penalty, so neither roots nor distal rigid sections can move.
	 */
	fun guide(seed: FloatArray, angles: FloatArray, carry: List<DoubleArray>? = null, linear: FloatArray? = null): Pair<FloatArray, DoubleArray> {
		// PSD residuals must use the same skinning operator as their reference. The angular
		// interpolation is a solver initialization, not a surface reference: using it here
		// preserves the radius of the entire blend band and creates a bulge at tight bends.
		val target = linear?.copyOf() ?: seed.copyOf()
		if (linear == null) for ((v, skin) in skins.withIndex()) {
			if (skin.rigid) continue
			val bone = bones[skin.to]
			val angle = Math.toRadians(SkeletonIk.wrap((angles[skin.to] - angles[skin.from]).toDouble()))
			val parent = Math.toRadians(angles[skin.from].toDouble())
			val m = carry?.get(skin.from) ?: doubleArrayOf(cos(parent), -sin(parent), sin(parent), cos(parent))
			val x = canvas[v * 2] - bone.headX; val y = canvas[v * 2 + 1] - bone.headY
			val w = skin.weight.toDouble()
			val dx = (1 - w) * x + w * (cos(angle) * x - sin(angle) * y) - (cos(angle * w) * x - sin(angle * w) * y)
			val dy = (1 - w) * y + w * (sin(angle) * x + cos(angle) * y) - (sin(angle * w) * x + cos(angle * w) * y)
			target[v * 2] += (m[0] * dx + m[1] * dy).toFloat()
			target[v * 2 + 1] += (m[2] * dx + m[3] * dy).toFloat()
		}
		val penalties = DoubleArray(skins.size)
		for ((vertex, skin) in skins.withIndex()) {
			if (skin.rigid) continue
			val joint = joints[skin.to] ?: continue
			val bone = bones[skin.to]
			val angle = SkeletonIk.wrap((angles[skin.to] - angles[skin.from]).toDouble())
			if (abs(angle) < 1e-6) continue
			penalties[vertex] = 4.0 * skin.weight * (1.0 - skin.weight)
			if (joint.kind == "generic") continue
			if (abs(angle) > 150.0) continue
			val side = if (angle >= 0) 1.0 else -1.0
			val x = canvas[vertex * 2] - bone.headX; val y = canvas[vertex * 2 + 1] - bone.headY
			val axial = (x * joint.ux + y * joint.uy) / joint.radius
			if (abs(axial) >= 3.0) continue
			val transverse = side * (-x * joint.uy + y * joint.ux - joint.center) / joint.radius
			val fraction = ((transverse + 1.0) * 0.5).coerceIn(0.0, 1.0)
			val neutral = sample(joint.kind, 0.0, axial, fraction)
			val posed = sample(joint.kind, abs(angle), axial, fraction)
			val rotation = Math.toRadians(abs(angle))
			val w = skin.weight.toDouble()
			val taper = 1.0 - smootherstep((abs(axial) - 1.5) / 1.5)
			// Vanishing correction and tangent at either rigid end, including authored bands
			// shorter than the reference cage. Never force a template across a rigid seam.
			val fade = taper * smootherstep(4.0 * w * (1.0 - w))
			val dx = fade * (posed[0] - ((1 - w) * neutral[0] + w * (cos(rotation) * neutral[0] - sin(rotation) * neutral[1])))
			val dy = fade * side * (posed[1] - ((1 - w) * neutral[1] + w * (sin(rotation) * neutral[0] + cos(rotation) * neutral[1])))
			val parentAngle = Math.toRadians(angles[skin.from].toDouble())
			val matrix = carry?.get(skin.from) ?: doubleArrayOf(cos(parentAngle), -sin(parentAngle), sin(parentAngle), cos(parentAngle))
			val rx = (joint.ux * dx - joint.uy * dy) * joint.radius
			val ry = (joint.uy * dx + joint.ux * dy) * joint.radius
			target[vertex * 2] += (matrix[0] * rx + matrix[1] * ry).toFloat()
			target[vertex * 2 + 1] += (matrix[2] * rx + matrix[3] * ry).toFloat()
			penalties[vertex] *= 1.0 + 3.0 * taper
		}
		closeInnerFolds(target, penalties, seed, angles, carry)
		return target to penalties
	}

	/** Inner blend region permitting overlap, or its fully closed silhouette when [closed] is true.
	 * Overlap may occur beyond the compact contact cage. Interior vertices stay soft; forcing
	 * every face to have positive area, or hard flattening its interior, reintroduces artifacts. */
	fun folding(angles: FloatArray, closed: Boolean = false): BooleanArray = BooleanArray(skins.size) { v ->
		val skin = skins[v]
		val joint = if (skin.rigid) null else joints[skin.to]
		if (joint == null) false else {
			val bone = bones[skin.to]
			val angle = SkeletonIk.wrap((angles[skin.to] - angles[skin.from]).toDouble())
			val depth = foldDepth(angle)
			val x = canvas[v * 2] - bone.headX; val y = canvas[v * 2 + 1] - bone.headY
			val s = (x * joint.ux + y * joint.uy) / joint.radius
			val n = (if (angle > 0) 1.0 else -1.0) * (-x * joint.uy + y * joint.ux - joint.center) / joint.radius
			abs(angle) > 1e-6 && if (closed) boundary[v] && n >= .5 && abs(s) <= depth - .5
			else n >= 0.0 && abs(s) <= bone.blend / joint.radius
		}
	}

	/** Zero-distance contact cage using positional constraints (Muller et al., PBD, 2006).
	 * In 2D the two inner branches share the bend bisector: contact has zero thickness and
	 * permits coincident silhouettes. This is a fold constraint, not collision repulsion.
	 * The contact patch is compact: extending offset rays to their far-away intersection
	 * drags the inner contour outward at high flexion. The crease stays within one half-width
	 * of the pivot. Rest transverse coordinates parameterize the contact cage continuously:
	 * matching material points on both sides share a target without nearest-vertex pairing.
	 * This also avoids pairing different depths on an irregular triangulation.
	 */
	private fun closeInnerFolds(target: FloatArray, penalties: DoubleArray, seed: FloatArray, angles: FloatArray, carry: List<DoubleArray>?) {
		for (joint in joints.values) {
			val bone = bones[joint.child]
			val angle = SkeletonIk.wrap((angles[joint.child] - angles[bone.parent]).toDouble())
			if (abs(angle) < 1e-6) continue
			val side = if (angle > 0) 1.0 else -1.0
			val half = Math.toRadians(angle * 0.5)
			val depth = foldDepth(angle)
			val points = skins.indices.filter { !skins[it].rigid && skins[it].to == joint.child }.mapNotNull { v ->
				val x = canvas[v * 2] - bone.headX; val y = canvas[v * 2 + 1] - bone.headY
				val s = (x * joint.ux + y * joint.uy) / joint.radius
				val n = side * (-x * joint.uy + y * joint.ux - joint.center) / joint.radius
				if (n < 0.5 || abs(s) >= depth) null else v to s
			}
			val parentAngle = Math.toRadians(angles[bone.parent].toDouble())
			val m = carry?.get(bone.parent) ?: doubleArrayOf(cos(parentAngle), -sin(parentAngle), sin(parentAngle), cos(parentAngle))
			val nx = -joint.uy * cos(half) - joint.ux * sin(half)
			val ny = joint.ux * cos(half) - joint.uy * sin(half)
			val cx = m[0] * nx + m[1] * ny; val cy = m[2] * nx + m[3] * ny
			val length2 = cx * cx + cy * cy
			if (length2 < 1e-10) continue
			for ((v, s) in points) {
				val rotation = Math.toRadians(angle * skins[v].weight)
				val rx = canvas[v * 2] - bone.headX; val ry = canvas[v * 2 + 1] - bone.headY
				val vx = cos(rotation) * rx - sin(rotation) * ry; val vy = sin(rotation) * rx + cos(rotation) * ry
				val ox = seed[v * 2] - (m[0] * vx + m[1] * vy)
				val oy = seed[v * 2 + 1] - (m[2] * vx + m[3] * vy)
				// Collapse the axial material coordinate, retain its transverse position along
				// the short crease. No angular-radius or offset-ray extension contributes here.
				val t = (-rx * joint.uy + ry * joint.ux - joint.center).coerceIn(-joint.radius, joint.radius)
				val normal = side * (-rx * joint.uy + ry * joint.ux - joint.center) / joint.radius
				// The silhouette closes fully. The interior fades into it rather than being
				// hard flattened as a block; its faces may overlap through the soft solve.
				val gain = smootherstep((depth - abs(s)) / 0.5) * if (boundary[v]) 1.0 else smootherstep((normal - .25) / .75)
				target[v * 2] += ((ox + t * cx - target[v * 2]) * gain).toFloat()
				target[v * 2 + 1] += ((oy + t * cy - target[v * 2 + 1]) * gain).toFloat()
				penalties[v] += 64.0 * gain
			}
		}
	}

	companion object {
		private fun foldDepth(angle: Double) = abs(kotlin.math.tan(Math.toRadians(angle * .5))).coerceAtMost(1.5)
		private fun smootherstep(value: Double): Double {
			val t = value.coerceIn(0.0, 1.0)
			return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
		}
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
				// Uniform cubic Hermite interpolation with centred derivatives: a shared
				// tangent at every sampled section instead of piecewise-linear contour kinks.
				return DoubleArray(2) { axis ->
					fun value(i: Int) = atRow(profile[i.coerceIn(profile.indices)], axis)
					val a = value(index); val b = value(index + 1)
					val da = if (index == 0) b - a else (b - value(index - 1)) * 0.5
					val db = if (index + 1 == profile.lastIndex) b - a else (value(index + 2) - a) * 0.5
					val t2 = t * t; val t3 = t2 * t
					(2 * t3 - 3 * t2 + 1) * a + (t3 - 2 * t2 + t) * da + (-2 * t3 + 3 * t2) * b + (t3 - t2) * db
				}
			}
			val lower = atAngle(lo); val upper = atAngle(hi)
			val t = if (hi == lo) 0.0 else (a - lo) / (hi - lo)
			return DoubleArray(2) { lower[it] * (1.0 - t) + upper[it] * t }
		}
	}
}
