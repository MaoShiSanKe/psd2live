package io.github.psd2live.core

import kotlinx.serialization.json.*

/**
 * A named snapshot of parameter values, usually a pose of the skeleton. Applying one sets those parameters
 * in the preview; it never touches the rig, so a limb pose stays a set of bone angles rather than a blend
 * shape that would tear the limb from its bones (see [SkeletonPoses]).
 */
data class PosePreset(val id: String, val name: String, val values: Map<String, Float>) {
	init {
		require(ID.matches(id)) { "Pose ID must be 1..64 letters, digits or underscores starting with a letter" }
		require(name.isNotBlank() && name.none(Char::isISOControl)) { "A pose needs a name" }
		require(values.isNotEmpty()) { "A pose needs a parameter" }
		require(values.size <= MAX_VALUES) { "A pose holds at most $MAX_VALUES parameters" }
		require(values.keys.all { it.isNotBlank() } && values.values.all { it.isFinite() }) { "Pose values must be finite" }
	}

	companion object {
		val ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
		const val MAX_VALUES = 512
		const val MAX_POSES = 256

		/** A pose ID unused by [presets]. */
		fun newId(presets: List<PosePreset>): String {
			var index = presets.size + 1
			while (presets.any { it.id == "pose_$index" }) index++
			return "pose_$index"
		}

		/** [base], or [base] with the first free number, unused as a pose name. */
		fun uniqueName(presets: List<PosePreset>, base: String): String {
			val taken = presets.mapTo(HashSet()) { it.name.lowercase() }
			if (base.lowercase() !in taken) return base
			var index = 2
			while ("$base $index".lowercase() in taken) index++
			return "$base $index"
		}

		fun toJson(preset: PosePreset): JsonObject = buildJsonObject {
			put("id", preset.id)
			put("name", preset.name)
			putJsonObject("values") { preset.values.toSortedMap().forEach { (parameter, value) -> put(parameter, value) } }
		}

		fun fromJson(o: JsonObject): PosePreset = PosePreset(
			id = o.getValue("id").jsonPrimitive.content,
			name = o.getValue("name").jsonPrimitive.content,
			values = o.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.float },
		)
	}
}
