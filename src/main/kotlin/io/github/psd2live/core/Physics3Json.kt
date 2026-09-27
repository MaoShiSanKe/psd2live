package io.github.psd2live.core

import kotlinx.serialization.json.JsonPrimitive

/** physics3.json, written the way Cubism Editor exports it and read the way the Cubism runtime does. */
object Physics3Json {
	/** The settings of a file, in file order, and its `Fps` if it declares one. */
	data class Physics3(val settings: List<RigPhysicsEdit>, val fps: Float?)

	/** physics3.json for [settings] stepping at [fps] (no `Fps` when unlimited), or null when there are none. */
	fun write(settings: List<RigPhysicsEdit>, fps: Int): String? {
		if (settings.isEmpty()) return null
		val dictionary = settings.map { rule -> "{ \"Id\": ${JsonPrimitive(rule.id)}, \"Name\": ${JsonPrimitive(rule.name)} }" }
		return """
		{
		  "Version": 3,
		  "Meta": {
		    "PhysicsSettingCount": ${settings.size},
		    "TotalInputCount": ${settings.sumOf { it.inputs.size }},
		    "TotalOutputCount": ${settings.sumOf { it.outputs.size }},
		    "VertexCount": ${settings.sumOf { it.segments.size + 1 }},
		    ${if (fps > 0) "\"Fps\": $fps," else ""}
		    "EffectiveForces": { "Gravity": { "X": 0, "Y": -1 }, "Wind": { "X": 0, "Y": 0 } },
		    "PhysicsDictionary": [${dictionary.joinToString(",")}]
		  },
		  "PhysicsSettings": [${settings.joinToString(",", transform = ::settingJson)}]
		}
		""".trimIndent()
	}

	/** Reads vertex radii as segment lengths, as Cubism does; positions are ignored. */
	fun read(text: String): Physics3 {
		val file = org.umamo.format.moc3.Moc3.readPhysics3(text)
		val names = file.meta.physicsDictionary.associate { it.id to it.name }
		fun type(t: String) = if (t == "Angle") PhysicsSourceType.ANGLE else PhysicsSourceType.X
		return Physics3(file.physicsSettings.map { s ->
			val n = s.normalization
			RigPhysicsEdit(
				id = s.id,
				name = names[s.id]?.takeIf { it.isNotBlank() } ?: s.id,
				inputs = s.input.map { PhysicsInput(it.source.id, it.weight.content.toFloat(), type(it.type), it.reflect) },
				outputs = s.output.map { PhysicsOutput(it.destination.id, it.vertexIndex, it.scale.content.toFloat(), it.weight.content.toFloat(), type(it.type), it.reflect) },
				segments = s.vertices.drop(1).map { v ->
					PhysicsSegment(v.radius.content.toFloat(), v.mobility.content.toFloat(), v.delay.content.toFloat(), v.acceleration.content.toFloat())
				},
				normalization = PhysicsNormalization(n.position.minimum.content.toFloat(), n.position.default.content.toFloat(), n.position.maximum.content.toFloat(),
					n.angle.minimum.content.toFloat(), n.angle.default.content.toFloat(), n.angle.maximum.content.toFloat()),
			)
		}, file.meta.fps?.content?.toFloatOrNull())
	}

	/** Particle positions down the strand, root first; Cubism reads only the radii, but writes both. */
	internal fun vertexY(setting: RigPhysicsEdit): List<Float> =
		setting.segments.runningFold(0f) { y, s -> y + s.length }

	private fun settingJson(rule: RigPhysicsEdit): String {
		val inputs = rule.inputs.joinToString(",\n") { input ->
			"""    { "Source": { "Target": "Parameter", "Id": ${JsonPrimitive(input.parameter)} }, "Weight": ${input.weight}, "Type": "${input.type.jsonName}", "Reflect": ${input.reflect} }"""
		}
		val ys = vertexY(rule)
		val vertices = (listOf(null) + rule.segments).mapIndexed { i, s ->
			"""    { "Position": { "X": 0, "Y": ${ys[i]} }, "Mobility": ${s?.mobility ?: 1f}, "Delay": ${s?.delay ?: 1f}, "Acceleration": ${s?.acceleration ?: 1f}, "Radius": ${s?.length ?: 0f} }"""
		}.joinToString(",\n")
		val outputs = rule.outputs.joinToString(",\n") { output ->
			"""    { "Destination": { "Target": "Parameter", "Id": ${JsonPrimitive(output.parameter)} }, "VertexIndex": ${output.vertex}, "Scale": ${output.scale}, "Weight": ${output.weight}, "Type": "${output.type.jsonName}", "Reflect": ${output.reflect} }"""
		}
		val n = rule.normalization
		return """
		{
		  "Id": ${JsonPrimitive(rule.id)},
		  "Input": [
		$inputs
		  ],
		  "Output": [
		$outputs
		  ],
		  "Vertices": [
		$vertices
		  ],
		  "Normalization": {
		    "Position": { "Minimum": ${n.positionMin}, "Default": ${n.positionDefault}, "Maximum": ${n.positionMax} },
		    "Angle": { "Minimum": ${n.angleMin}, "Default": ${n.angleDefault}, "Maximum": ${n.angleMax} }
		  }
		}
		""".trimIndent()
	}
}
