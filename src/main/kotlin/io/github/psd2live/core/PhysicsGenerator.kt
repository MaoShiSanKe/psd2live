package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import kotlinx.serialization.json.JsonPrimitive

/**
 * The physics groups a model carries: generated ones (hair and eye presets, skeleton follow-through,
 * swing pendulums) and the user's, which replace generated ones by ID or by output. Export, preview,
 * the panel and MCP all read the same [catalog].
 */
object PhysicsGenerator {
	const val FRONT_HAIR_ID = "PhysicsHairFront"
	const val BACK_HAIR_ID = "PhysicsHairBack"
	const val EYE_JELLY_ID = "PhysicsEyeJelly"
	val presetIds = listOf(BACK_HAIR_ID, FRONT_HAIR_ID, EYE_JELLY_ID)

	/**
	 * The fixed physics rate the export declares (physics3.json `Fps`, the CMO3 physics FPS), so a model
	 * swings the same at any frame rate and the same in every runtime that reads it.
	 */
	const val FPS = 60f

	/** One flag per preset: whether the art has the part, or whether the user left the preset on. */
	data class Presets(val frontHair: Boolean, val backHair: Boolean, val eyeJelly: Boolean) {
		operator fun get(id: String) = when (id) {
			FRONT_HAIR_ID -> frontHair
			BACK_HAIR_ID -> backHair
			EYE_JELLY_ID -> eyeJelly
			else -> false
		}

		companion object {
			val All = Presets(true, true, true)

			fun present(analysis: PipelineAnalysis?): Presets {
				val layers = analysis?.layers.orEmpty().filter { it.opaquePixels > 0 }
				return Presets(layers.any { it.semantic.tag == SemanticTag.FRONT_HAIR },
					layers.any { it.semantic.tag == SemanticTag.BACK_HAIR },
					layers.any { it.semantic.tag == SemanticTag.IRIDES })
			}

			fun enabled(config: PipelineConfig) = Presets(config.physicsFrontHair, config.physicsBackHair, config.physicsEyeJelly)
		}
	}

	/** The head and body sway every hanging part follows. */
	internal fun headAndBodyInputs(available: Set<String>?) = listOf(
		PhysicsInput("ParamAngleX", 60f, PhysicsSourceType.X),
		PhysicsInput("ParamAngleZ", 60f, PhysicsSourceType.ANGLE),
		PhysicsInput("ParamBodyAngleX", 40f, PhysicsSourceType.X),
		PhysicsInput("ParamBodyAngleZ", 40f, PhysicsSourceType.ANGLE),
	).filter { available == null || it.parameter in available }

	/** Hair and eye presets for the parts [present] in the art. */
	internal fun presetRules(present: Presets, available: Set<String>? = null): List<RigPhysicsEdit> = buildList {
		if (present.backHair) add(hairRule(BACK_HAIR_ID, tr("model.physics.backHair"), "ParamHairBack", 2.061f, 15f, 0.95f, 0.8f, 1.5f, 30f, available))
		if (present.frontHair) add(hairRule(FRONT_HAIR_ID, tr("model.physics.frontHair"), "ParamHairFront", 1.522f, 7.9f, 0.77f, 1.45f, 0.8f, 10f, available))
		if (present.eyeJelly) add(RigPhysicsEdit(
			id = EYE_JELLY_ID,
			name = tr("model.physics.eyeJelly"),
			inputs = listOf(PhysicsInput("ParamEyeLOpen", 50f, PhysicsSourceType.X), PhysicsInput("ParamEyeROpen", 50f, PhysicsSourceType.X))
				.filter { available == null || it.parameter in available },
			outputs = listOf(PhysicsOutput("ParamEyeBallForm", 2, 0.32f)),
			segments = listOf(PhysicsSegment(1f, 0.88f, 0.18f, 1.9f), PhysicsSegment(1f, 0.80f, 0.32f, 2.2f)),
			normalization = PhysicsNormalization(-1f, 0f, 1f, -10f, 0f, 10f),
		))
	}

	private fun hairRule(id: String, name: String, output: String, scale: Float, length: Float, mobility: Float, delay: Float,
		acceleration: Float, angle: Float, available: Set<String>?) = RigPhysicsEdit(
		id, name, headAndBodyInputs(available), listOf(PhysicsOutput(output, 1, scale)),
		listOf(PhysicsSegment(length, mobility, delay, acceleration)), PhysicsNormalization(angleMin = -angle, angleMax = angle),
	)

	/**
	 * Follow-through on the loose appendages: every tail segment after the first trails the one it hangs
	 * from, and wings trail the body. Arms and legs stay off physics - they are posed and animated
	 * directly, and a simulation would overwrite both.
	 */
	internal fun skeletonRules(spec: SkeletonSpec?, available: Set<String>): List<RigPhysicsEdit> {
		if (spec?.enabled != true) return emptyList()
		return spec.bones.filter { it.role == BoneRole.WING || (it.role == BoneRole.TAIL && it.chainIndex > 1) }
			.mapNotNull { bone ->
				val input = when (bone.role) {
					BoneRole.TAIL -> spec.bone(bone.parentId ?: "")?.takeIf { it.role == BoneRole.TAIL }?.parameterId
						?: "ParamBodyAngleZ"
					else -> "ParamBodyAngleZ"
				}
				if (input !in available || bone.parameterId !in available || input == bone.parameterId) return@mapNotNull null
				val tail = bone.role == BoneRole.TAIL
				RigPhysicsEdit("PhysicsSkel_${bone.id}", bone.name, listOf(PhysicsInput(input)),
					listOf(PhysicsOutput(bone.parameterId, 1, if (tail) 0.75f else 0.4f)),
					listOf(PhysicsSegment((bone.length / 30f).coerceIn(3f, 16f), 0.55f, if (tail) 0.65f else 0.9f, 0.8f)),
					PhysicsNormalization(angleMin = -30f, angleMax = 30f))
			}
	}

	/**
	 * One pendulum per direction of every regenerating swing, with a vertex per segment. Left/right swings
	 * take the hair inputs. A rigid pendulum hanging straight down ignores vertical travel, so up/down swings
	 * feed head and body pitch in as sideways travel, and the resulting angle drives the up/down forms.
	 */
	internal fun swingRules(swings: List<RigSwingEdit>, available: Set<String>): List<RigPhysicsEdit> = swings.flatMap { swing ->
		if (swing.parameterIds.any { it !in available }) return@flatMap emptyList()
		swing.motions.mapNotNull { motion ->
			val physics = motion.physics ?: return@mapNotNull null
			val inputs = when (motion.kind) {
				SwingKind.LATERAL -> headAndBodyInputs(null)
				SwingKind.VERTICAL -> listOf(
					PhysicsInput("ParamAngleY", 60f, PhysicsSourceType.X),
					PhysicsInput("ParamBodyAngleY", 40f, PhysicsSourceType.X),
					PhysicsInput("ParamAngleZ", 20f, PhysicsSourceType.ANGLE),
				)
			}.filter { it.parameter in available && it.parameter !in swing.parameterIds }
			if (inputs.isEmpty()) return@mapNotNull null
			val id = swingPhysicsId(swing, motion.kind)
			val segment = PhysicsSegment(physics.length / motion.segments, physics.mobility, physics.delay, physics.acceleration)
			RigPhysicsEdit(
				id = id,
				name = if (swing.motions.size == 1) swing.name else "${swing.name} ${id.substringAfterLast('_')}",
				inputs = inputs,
				outputs = motion.parameterIds.mapIndexed { k, parameter -> PhysicsOutput(parameter, k + 1, physics.outputScale) },
				segments = List(motion.segments) { segment },
				normalization = PhysicsNormalization(angleMin = -30f, angleMax = 30f),
			)
		}
	}

	/** `PhysicsSwing_<id>` for a swing in one direction; with both, suffixed `_X` (left/right) or `_Y` (up/down). */
	internal fun swingPhysicsId(swing: RigSwingEdit, kind: SwingKind = swing.motions.first().kind): String =
		"PhysicsSwing_${swing.id}" + if (swing.motions.size == 1) "" else if (kind == SwingKind.LATERAL) "_X" else "_Y"

	/** The swing a generated group belongs to, if it is a swing pendulum. */
	fun swingOf(groupId: String, swings: List<RigSwingEdit>): RigSwingEdit? =
		swings.firstOrNull { swing -> swing.motions.any { swingPhysicsId(swing, it.kind) == groupId } }

	/**
	 * Every group in display order - presets, skeleton, swings, then the user's own - with what it
	 * exports and why it would not. A user group claims its outputs first; a generated group on a claimed
	 * output steps aside.
	 */
	fun catalog(present: Presets, enabledPresets: Presets, overlay: RigEditOverlay, available: Set<String>): List<PhysicsGroup> {
		val generated = presetRules(present, available).map { it to PhysicsOrigin.PRESET } +
			skeletonRules(overlay.skeleton, available).map { it to PhysicsOrigin.SKELETON } +
			swingRules(overlay.swingEdits, available).map { it to PhysicsOrigin.SWING }
		val generatedIds = generated.mapTo(HashSet()) { it.first.id }
		val authored = overlay.physicsEdits.associateBy { it.id }
		fun enabled(id: String, origin: PhysicsOrigin) =
			if (origin == PhysicsOrigin.PRESET) enabledPresets[id] else id !in overlay.disabledPhysicsIds
		val rows = generated.map { (rule, origin) ->
			val setting = authored[rule.id] ?: rule
			PhysicsGroup(setting, origin, rule, enabled(rule.id, origin), issueOf(setting, available))
		} + overlay.physicsEdits.filter { it.id !in generatedIds }.map { edit ->
			PhysicsGroup(edit, PhysicsOrigin.CUSTOM, null, enabled(edit.id, PhysicsOrigin.CUSTOM), issueOf(edit, available))
		}
		// The user's groups claim outputs in the order they were authored, then generated ones in order.
		val claimed = HashMap<String, String>()
		val resolved = rows.associateBy { it.id }.toMutableMap()
		val userFirst = overlay.physicsEdits.mapNotNull { resolved[it.id] } + rows.filter { it.id !in authored }
		for (row in userFirst) {
			if (!row.enabled || row.issue != null) continue
			val user = row.id in authored
			val owner = row.setting.outputParameters.firstNotNullOfOrNull { p -> claimed[p]?.let { p to it } }
			resolved[row.id] = when {
				owner == null -> row.also { r -> r.setting.outputParameters.forEach { claimed[it] = r.id } }
				!user && owner.second in authored -> row.copy(shadowedBy = owner.second)
				else -> row.copy(issue = PhysicsIssue(PhysicsIssue.Code.OUTPUT_TAKEN, owner.first, owner.second))
			}
		}
		return rows.map { resolved.getValue(it.id) }
	}

	fun catalog(analysis: PipelineAnalysis?, config: PipelineConfig, available: Set<String>): List<PhysicsGroup> =
		catalog(Presets.present(analysis), Presets.enabled(config), config.rigEdits, available)

	/** What exports: nothing when physics is off or the model is mesh-only. */
	fun active(analysis: PipelineAnalysis?, config: PipelineConfig, available: Set<String>): List<RigPhysicsEdit> =
		if (!config.generatePhysics || config.meshOnly) emptyList()
		else catalog(analysis, config, available).filter { it.active }.map { it.setting }

	fun issueOf(setting: RigPhysicsEdit, available: Set<String>): PhysicsIssue? {
		setting.parameters.firstOrNull { it !in available }?.let { return PhysicsIssue(PhysicsIssue.Code.MISSING_PARAMETER, it) }
		if (setting.inputs.isEmpty()) return PhysicsIssue(PhysicsIssue.Code.NO_INPUT)
		if (setting.outputs.isEmpty()) return PhysicsIssue(PhysicsIssue.Code.NO_OUTPUT)
		setting.inputs.firstOrNull { input -> setting.outputs.any { it.parameter == input.parameter } }
			?.let { return PhysicsIssue(PhysicsIssue.Code.FEEDBACK, it.parameter) }
		return null
	}

	/** physics3.json for [settings], or null when there are none. */
	fun json(settings: List<RigPhysicsEdit>): String? {
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
		    "Fps": ${FPS.toInt()},
		    "EffectiveForces": { "Gravity": { "X": 0, "Y": -1 }, "Wind": { "X": 0, "Y": 0 } },
		    "PhysicsDictionary": [${dictionary.joinToString(",")}]
		  },
		  "PhysicsSettings": [${settings.joinToString(",", transform = ::settingJson)}]
		}
		""".trimIndent()
	}

	/** The settings of a physics3.json and its `Fps`, the way Cubism reads them: vertex radii, not positions. */
	fun fromPhysics3(text: String): Pair<List<RigPhysicsEdit>, Float?> {
		val file = org.umamo.format.moc3.Moc3.readPhysics3(text)
		val names = file.meta.physicsDictionary.associate { it.id to it.name }
		fun type(t: String) = if (t == "Angle") PhysicsSourceType.ANGLE else PhysicsSourceType.X
		return file.physicsSettings.map { s ->
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
		} to file.meta.fps?.content?.toFloatOrNull()
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
