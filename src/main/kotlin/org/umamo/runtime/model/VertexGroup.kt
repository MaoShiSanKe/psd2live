package org.umamo.runtime.model

/**
 * What a [VertexGroup]'s weights mean to the simulation. Editor-only: no runtime format has vertex groups,
 * so every exporter ignores them and the simulation bakes their effect into ordinary keyforms.
 */
enum class VertexGroupKind(val jsonName: String) {
	/** Held to an anchor that follows the rig; the weight is how firmly, below 1 a soft hold. */
	PIN("pin"),
	/** Simulated vertices that collide (a skirt's side edges, hair tips). */
	COLLIDE("collide"),
	/** Vertices whose triangles push simulated vertices away, following the rig as it deforms (legs, face). */
	COLLIDER("collider"),
	/** Stretch and bend stiffness multiplier. */
	STIFFNESS("stiffness"),
	/** Mass multiplier: heavier hems and tips lag and overshoot more. */
	MASS("mass"),
	/** Local damping. */
	DAMPING("damping"),
	/** How much wind and force fields act. */
	WIND("wind"),
	/** Spring back toward the rest shape, which keeps the drawn silhouette. */
	GOAL("goal");

	companion object {
		fun parse(text: String): VertexGroupKind = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
			?: throw IllegalArgumentException("Unknown vertex group kind: $text (${entries.joinToString { it.jsonName }})")
	}
}

/**
 * A named 0..1 weight per vertex of one ArtMesh, as Blender's vertex groups. [weights] has one entry per
 * mesh vertex; topology edits remap it the way they remap keyform deltas.
 */
class VertexGroup(
	val name: String,
	val drawableId: DrawableId,
	val kind: VertexGroupKind,
	val weights: FloatArray,
) {
	init {
		require(name.isNotBlank() && name.none(Char::isISOControl)) { "Vertex group name is required" }
		require(weights.all { it.isFinite() && it in 0f..1f }) { "Vertex group weights must be within 0..1" }
	}

	fun copy(
		name: String = this.name,
		drawableId: DrawableId = this.drawableId,
		kind: VertexGroupKind = this.kind,
		weights: FloatArray = this.weights,
	): VertexGroup = VertexGroup(name, drawableId, kind, weights)

	override fun equals(other: Any?): Boolean = other is VertexGroup && name == other.name &&
		drawableId == other.drawableId && kind == other.kind && weights.contentEquals(other.weights)

	override fun hashCode(): Int = ((name.hashCode() * 31 + drawableId.hashCode()) * 31 + kind.hashCode()) * 31 + weights.contentHashCode()

	override fun toString(): String = "VertexGroup($name, ${drawableId.raw}, $kind, ${weights.size} vertices)"
}
