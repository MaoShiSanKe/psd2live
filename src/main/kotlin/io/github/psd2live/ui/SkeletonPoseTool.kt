package io.github.psd2live.ui

import androidx.compose.ui.geometry.Offset
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonPoseSolver
import io.github.psd2live.core.SkeletonPoses
import io.github.psd2live.core.SkeletonRig
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.SkeletonWeights
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.math.hypot

internal typealias PosedBone = io.github.psd2live.core.PosedBone

internal typealias BoneHit = io.github.psd2live.core.BoneHit

/**
 * The canvas pose tool: bones drawn where the rig currently holds them, turned by dragging their body
 * and pulled by their tip.
 *
 * Posing writes parameters, never geometry. A bone's parameter is its angle in degrees (signed by
 * [SkeletonBone.direction]), so a drag's angle change converts straight into a parameter change, and
 * the rig the export writes is exactly the rig being posed.
 */
internal object SkeletonPoseTool {
	/** Screen pixels within which the pointer grabs a bone's tip. */
	private const val TIP_RADIUS = 10f

	/** Every limb bone of [spec] where [model] holds it at [values]; see [SkeletonPoseSolver.posed]. */
	fun posed(model: PuppetModel, spec: SkeletonSpec?, values: Map<ParameterId, Float>): List<PosedBone> =
		SkeletonPoseSolver.posed(model, spec, values)

	/** The bone under [pos] (screen), tips first, then the nearest body within a bone-proportional reach. */
	fun hit(bones: List<PosedBone>, pos: Offset, viewport: CanvasViewport): BoneHit? {
		fun screen(x: Float, y: Float) = Offset(viewport.x(x).toFloat(), (viewport.offsetY + y * viewport.scale).toFloat())
		bones.asReversed().firstOrNull { (screen(it.tailX, it.tailY) - pos).getDistance() <= TIP_RADIUS }
			?.let { return BoneHit(it.bone.id, tip = true) }
		var best: PosedBone? = null
		var bestDistance = Float.MAX_VALUE
		for (posed in bones) {
			val h = screen(posed.headX, posed.headY)
			val t = screen(posed.tailX, posed.tailY)
			val dx = t.x - h.x
			val dy = t.y - h.y
			val lengthSquared = dx * dx + dy * dy
			if (lengthSquared < 1f) continue
			val u = (((pos.x - h.x) * dx + (pos.y - h.y) * dy) / lengthSquared).coerceIn(0f, 1f)
			val distance = hypot(pos.x - (h.x + u * dx), pos.y - (h.y + u * dy))
			val reach = (kotlin.math.sqrt(lengthSquared) * 0.14f).coerceIn(6f, 18f)
			if (distance <= reach && distance < bestDistance) {
				best = posed
				bestDistance = distance
			}
		}
		return best?.let { BoneHit(it.bone.id, tip = false) }
	}

	/** The parameter values that turn [hit]'s bone toward canvas point ([x], [y]); see [SkeletonPoseSolver.drag]. */
	fun drag(
		spec: SkeletonSpec,
		bones: List<PosedBone>,
		hit: BoneHit,
		x: Float,
		y: Float,
		values: Map<ParameterId, Float>,
		ik: Boolean,
	): Map<ParameterId, Float> = SkeletonPoseSolver.drag(spec, bones, hit, x, y, values, ik)

	/** Every limb and pose parameter back at rest. */
	fun rest(spec: SkeletonSpec?): Map<ParameterId, Float> =
		if (spec?.enabled != true) emptyMap()
		else SkeletonRig.limbBones(spec).associate { ParameterId(it.parameterId) to 0f } +
			SkeletonPoses.rigPoses.associate { it.id to 0f }

	/**
	 * Per-vertex weights for the heat map: for every skinned mesh, each vertex's two bones and the weight
	 * of the second, the same answer the bake used, keyed by drawable. Computed on the rest pose, so it is
	 * worth caching per model.
	 */
	fun weights(model: PuppetModel, spec: SkeletonSpec?): Map<DrawableId, Pair<List<SkeletonBone>, List<io.github.psd2live.core.VertexSkin>>> {
		if (spec?.enabled != true) return emptyMap()
		// The body halves bend warps rather than skin meshes, so only limb meshes have weights.
		val bones = SkeletonRig.jointBones(spec)
		val parentOf = SkeletonRig.jointParents(spec)
		val rootOf = SkeletonRig.skinRoots(bones, parentOf)
		val trees = bones.groupBy { rootOf.getValue(it.id) }
		val rest = SkeletonRig.restCanvas(model)
		val out = LinkedHashMap<DrawableId, Pair<List<SkeletonBone>, List<io.github.psd2live.core.VertexSkin>>>()
		for (bone in bones) for (id in bone.drawableIds) {
			val drawableId = DrawableId(id)
			if (drawableId in out) continue
			val canvas = rest[drawableId] ?: continue
			val triangles = model.drawables.firstOrNull { it.id == drawableId }?.mesh?.indices ?: continue
			val tree = trees.getValue(rootOf.getValue(bone.id))
			val skinBones = SkeletonRig.skinBones(tree, parentOf)
			out[drawableId] = tree to SkeletonWeights.skin(canvas, skinBones, triangles)
		}
		return out
	}
}
