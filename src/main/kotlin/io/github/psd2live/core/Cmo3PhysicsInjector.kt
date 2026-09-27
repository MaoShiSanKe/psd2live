package io.github.psd2live.core

import io.github.psd2live.i18n.tr
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsInput
import org.umamo.format.cmo3.model.gen.CPhysicsOutput
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsSourceType
import org.umamo.format.cmo3.model.gen.CPhysicsVertex
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.cmo3.type.CArrayList
import java.util.UUID

/** Writes editable Cubism physics settings into a fresh CMO3 graph. */
internal object Cmo3PhysicsInjector {
	fun inject(root: CModelSource, rules: List<RigPhysicsEdit>, fps: Int = RigEditOverlay.DEFAULT_PHYSICS_FPS): Int {
		val physicsSet = root.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet
			?: error(tr("error.cmo3MissingPhysicsSet"))
		// The pipeline only injects into its own fresh graph, so replace the known empty collection
		// with the exact carray_list type expected by the editor instead of using an unsafe cast.
		val sources = CArrayList<Any?>().also { physicsSet._sourceCubismPhysics = it }
		val parameterSet = root.parameterSourceSet as? CParameterSourceSet
			?: error(tr("error.cmo3MissingParameterSet"))
		val parameters = elements(parameterSet._sources).filterIsInstance<CParameterSource>()
		val parameterById = parameters.associateBy { ((it.id as? Id)?.idstr).orEmpty() }
		if (rules.isEmpty()) return 0
		for (rule in rules) {
			val setting = CPhysicsSettingsSource().apply {
				name = rule.name
				guid = guid("CPhysicsSettingsGuid", rule.name)
				id = Id("CPhysicsSettingId").apply { idstr = rule.id }
				inputs = CArrayList<Any?>(
					rule.inputs.map { inputRule -> input(rule, parameterById, inputRule) },
				)
				outputs = CArrayList<Any?>(
					rule.outputs.map { outputRule ->
						val output = parameterById[outputRule.parameter]
							?: error(tr("error.cmo3MissingPhysicsOutput", outputRule.parameter))
						CPhysicsOutput().apply {
							guid = guid("CPhysicsDataGuid", "out_${rule.id}_${outputRule.parameter}")
							destination = output.guid
							vertexIndex = outputRule.vertex
							val x = outputRule.type == PhysicsSourceType.X
							translationScale = vector(if (x) outputRule.scale else 0f, 0f)
							angleScale = if (x) 0f else outputRule.scale
							weight = outputRule.weight
							type = if (x) CPhysicsSourceType.SRC_TO_X else CPhysicsSourceType.SRC_TO_G_ANGLE
							isReverse = outputRule.reflect
						}
					},
				)
				val ys = Physics3Json.vertexY(rule)
				vertices = CArrayList<Any?>(
					(listOf(null) + rule.segments).mapIndexed { index, segment -> vertex(rule, index, ys[index], segment) },
				)
				val n = rule.normalization
				normalizedPositionValueMax = n.positionMax
				normalizedPositionValueMin = n.positionMin
				normalizedPositionDefaultValue = n.positionDefault
				normalizedAngleValueMax = n.angleMax
				normalizedAngleValueMin = n.angleMin
				normalizedAngleDefaultValue = n.angleDefault
			}
			sources.add(setting)
		}
		physicsSet.selectedCubismPhysics = guid("CPhysicsSettingsGuid", "physics-selection")
		physicsSet.settingFPS = fps
		return rules.size
	}

	private fun input(
		rule: RigPhysicsEdit,
		parameterById: Map<String, CParameterSource>,
		input: PhysicsInput,
	): CPhysicsInput {
		val parameter = parameterById[input.parameter] ?: error(tr("error.cmo3MissingPhysicsInput", input.parameter))
		return CPhysicsInput().apply {
			guid = guid("CPhysicsDataGuid", "in_${rule.id}_${input.parameter}")
			source = parameter.guid
			angleScale = 0f
			translationScale = vector(0f, 0f)
			weight = input.weight
			type = when (input.type) {
				PhysicsSourceType.X -> CPhysicsSourceType.SRC_TO_X
				PhysicsSourceType.ANGLE -> CPhysicsSourceType.SRC_TO_G_ANGLE
			}
			isReverse = input.reflect
		}
	}

	/** [segment] is null for the root particle. */
	private fun vertex(rule: RigPhysicsEdit, index: Int, y: Float, segment: PhysicsSegment?): CPhysicsVertex =
		CPhysicsVertex().apply {
			guid = guid("CPhysicsDataGuid", "v${index}_${rule.id}")
			position = vector(0f, y)
			mobility = segment?.mobility ?: 1f
			delay = segment?.delay ?: 1f
			acceleration = segment?.acceleration ?: 1f
			radius = segment?.length ?: 0f
		}

	private fun vector(x: Float, y: Float): GVector2 = GVector2().apply {
		this.x = x
		this.y = y
	}

	private fun guid(kind: String, note: String): Guid = Guid(kind).apply {
		uuid = UUID.randomUUID().toString()
		this.note = note
	}

	private fun elements(value: Any?): List<Any?> = when (value) {
		is Iterable<*> -> value.toList()
		is Array<*> -> value.toList()
		else -> emptyList()
	}
}
