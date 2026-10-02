package io.github.psd2live.core

/**
 * How far the body parameters move the body at their full values, before the body strength scales them:
 * the amounts [BodyStance] turns, opens, leans and resizes the body by, and the Body Z lean and the breath
 * of the breath warp ([RigBuilder.bodySecondaryWarpPoint]).
 *
 * Every value is in the unit the model presets show it in - degrees, percent or a multiple of the torso's
 * length - so the panel, the MCP `settings` tool and the project file all read the same numbers. [fields]
 * lists them in the panel's order with their groups and ranges.
 */
data class BodyMotionTuning(
	/** Body X: degrees the upper body turns. */
	val turnDegrees: Float = 12f,
	/** Body X: degrees each arm swings about its shoulder against the turn, so the hand trails the body. */
	val armSwingDegrees: Float = 4f,
	/** Body X: the hips sideways, percent of the legs' length. */
	val hipShift: Float = 2.5f,
	/** Body Y down: the hips lowered, percent of the legs' length. */
	val sink: Float = 3.5f,
	/** Body Y up: the hips raised, percent of the legs' length. */
	val rise: Float = 1.2f,
	/** Body Y down: degrees the bending knees turn in, beyond the little they always do. */
	val kneesIn: Float = 35f,
	/** Body Y down: the back shortened, percent of the torso's length. */
	val openShorten: Float = 1.5f,
	/** Body Y down: the shoulders opened, percent of their width. */
	val openWiden: Float = 2f,
	/** Body Y up: the back stretched, percent of the torso's length. */
	val standStretch: Float = 1.5f,
	/** Body Y up: the shoulders drawn in, percent of their width. */
	val standNarrow: Float = 1.5f,
	/** Body lean: degrees the body bows leaning in. */
	val leanDegrees: Float = 28f,
	/** Body lean: degrees the body bows leaning back. */
	val leanBackDegrees: Float = 12f,
	/** Body lean: the share of the bow the pelvis takes about the hip joints, percent. */
	val pelvisShare: Float = 30f,
	/** Proportion at full chibi: the head drawn larger, percent. */
	val headGrow: Float = 15f,
	/** Proportion at full chibi: the chest drawn wider at the shoulders, percent. */
	val chestGrow: Float = 8f,
	/** Proportion at full chibi: the torso and the arms drawn shorter, percent. */
	val bodyShrink: Float = 6f,
	/** Proportion at full chibi: the legs and the skirt drawn shorter, percent. */
	val legShrink: Float = 10f,
	/** Body Z: degrees the upper body leans sideways about the waist. */
	val bodyZDegrees: Float = 3f,
	/** Breath: the shoulders lifted at a full breath, percent of the torso's length. */
	val breathLift: Float = 3f,
	/** Breath: the chest widened at a full breath, percent of its width. */
	val breathWiden: Float = 2.5f,
	/** The torso's depth front to back, percent of its width; the turn and the lean read it as a solid this deep. */
	val torsoDepth: Float = 70f,
	/** How far in front of the torso the turn is seen from, in torso lengths: nearer draws the near side larger. */
	val turnCameraDistance: Float = 3f,
	/** How far in front of the torso the lean is seen from, in torso lengths: nearer draws a lean in larger. */
	val leanCameraDistance: Float = 9f,
) {
	/** The panel's groups, in order. */
	enum class Group { TURN, OPEN, LEAN, PROPORTION, BREATH, DEPTH }

	/** The unit a value is shown in. */
	enum class Unit { DEGREES, PERCENT, TORSO_LENGTHS }

	/** One value: its [id] in the project file and the MCP tool, its [group], [unit] and [range], and its [step] in the panel. */
	class Field(
		val id: String,
		val group: Group,
		val unit: Unit,
		val range: ClosedFloatingPointRange<Float>,
		val step: Float,
		val get: (BodyMotionTuning) -> Float,
		val set: (BodyMotionTuning, Float) -> BodyMotionTuning,
	) {
		val default: Float get() = get(BodyMotionTuning())
	}

	/** This tuning with the value [id] set to [value], clamped to its range. */
	fun with(id: String, value: Float): BodyMotionTuning {
		val field = requireNotNull(fieldById[id]) { "Unknown body motion value: $id" }
		require(value.isFinite()) { "$id must be a number" }
		return field.set(this, value.coerceIn(field.range))
	}

	/** Every value by its id, in the panel's order. */
	fun toMap(): Map<String, Float> = fields.associateTo(LinkedHashMap()) { it.id to it.get(this) }

	companion object {
		val fields: List<Field> = listOf(
			Field("turnDegrees", Group.TURN, Unit.DEGREES, 0f..30f, 0.5f, { it.turnDegrees }) { t, v -> t.copy(turnDegrees = v) },
			Field("armSwingDegrees", Group.TURN, Unit.DEGREES, -15f..15f, 0.5f, { it.armSwingDegrees }) { t, v -> t.copy(armSwingDegrees = v) },
			Field("hipShift", Group.TURN, Unit.PERCENT, 0f..10f, 0.1f, { it.hipShift }) { t, v -> t.copy(hipShift = v) },
			Field("sink", Group.OPEN, Unit.PERCENT, 0f..10f, 0.1f, { it.sink }) { t, v -> t.copy(sink = v) },
			Field("rise", Group.OPEN, Unit.PERCENT, 0f..5f, 0.1f, { it.rise }) { t, v -> t.copy(rise = v) },
			Field("kneesIn", Group.OPEN, Unit.DEGREES, 0f..60f, 1f, { it.kneesIn }) { t, v -> t.copy(kneesIn = v) },
			Field("openShorten", Group.OPEN, Unit.PERCENT, 0f..6f, 0.1f, { it.openShorten }) { t, v -> t.copy(openShorten = v) },
			Field("openWiden", Group.OPEN, Unit.PERCENT, 0f..6f, 0.1f, { it.openWiden }) { t, v -> t.copy(openWiden = v) },
			Field("standStretch", Group.OPEN, Unit.PERCENT, 0f..6f, 0.1f, { it.standStretch }) { t, v -> t.copy(standStretch = v) },
			Field("standNarrow", Group.OPEN, Unit.PERCENT, 0f..6f, 0.1f, { it.standNarrow }) { t, v -> t.copy(standNarrow = v) },
			Field("leanDegrees", Group.LEAN, Unit.DEGREES, 0f..45f, 0.5f, { it.leanDegrees }) { t, v -> t.copy(leanDegrees = v) },
			Field("leanBackDegrees", Group.LEAN, Unit.DEGREES, 0f..30f, 0.5f, { it.leanBackDegrees }) { t, v -> t.copy(leanBackDegrees = v) },
			Field("pelvisShare", Group.LEAN, Unit.PERCENT, 0f..100f, 1f, { it.pelvisShare }) { t, v -> t.copy(pelvisShare = v) },
			Field("headGrow", Group.PROPORTION, Unit.PERCENT, 0f..40f, 0.5f, { it.headGrow }) { t, v -> t.copy(headGrow = v) },
			Field("chestGrow", Group.PROPORTION, Unit.PERCENT, 0f..20f, 0.5f, { it.chestGrow }) { t, v -> t.copy(chestGrow = v) },
			Field("bodyShrink", Group.PROPORTION, Unit.PERCENT, 0f..20f, 0.5f, { it.bodyShrink }) { t, v -> t.copy(bodyShrink = v) },
			Field("legShrink", Group.PROPORTION, Unit.PERCENT, 0f..30f, 0.5f, { it.legShrink }) { t, v -> t.copy(legShrink = v) },
			Field("bodyZDegrees", Group.BREATH, Unit.DEGREES, 0f..10f, 0.1f, { it.bodyZDegrees }) { t, v -> t.copy(bodyZDegrees = v) },
			Field("breathLift", Group.BREATH, Unit.PERCENT, 0f..10f, 0.1f, { it.breathLift }) { t, v -> t.copy(breathLift = v) },
			Field("breathWiden", Group.BREATH, Unit.PERCENT, 0f..8f, 0.1f, { it.breathWiden }) { t, v -> t.copy(breathWiden = v) },
			Field("torsoDepth", Group.DEPTH, Unit.PERCENT, 10f..150f, 1f, { it.torsoDepth }) { t, v -> t.copy(torsoDepth = v) },
			Field("turnCameraDistance", Group.DEPTH, Unit.TORSO_LENGTHS, 1.5f..20f, 0.1f, { it.turnCameraDistance }) { t, v -> t.copy(turnCameraDistance = v) },
			Field("leanCameraDistance", Group.DEPTH, Unit.TORSO_LENGTHS, 2f..30f, 0.1f, { it.leanCameraDistance }) { t, v -> t.copy(leanCameraDistance = v) },
		)

		val fieldById: Map<String, Field> = fields.associateBy { it.id }

		/** [values] by id over [base]: unknown ids are skipped and every value is clamped to its range. */
		fun fromMap(values: Map<String, Float>, base: BodyMotionTuning = BodyMotionTuning()): BodyMotionTuning =
			values.entries.fold(base) { tuning, (id, value) ->
				val field = fieldById[id]
				if (field == null || !value.isFinite()) tuning else field.set(tuning, value.coerceIn(field.range))
			}
	}
}
