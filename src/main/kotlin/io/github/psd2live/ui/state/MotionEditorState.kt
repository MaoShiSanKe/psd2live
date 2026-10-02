package io.github.psd2live.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionKey
import kotlin.math.abs

internal enum class MotionEditorView { DOPESHEET, CURVES }

/** A key by its curve and time: a curve holds one key per time, and a time survives reordering. */
internal data class MotionKeyRef(val parameterId: String, val time: Float) {
	fun matches(key: MotionKey) = abs(key.time - time) < MotionClips.TIME_EPSILON
}

/**
 * What the animation panel and the animation editor share: the clip being edited, the playhead and the
 * key selection. It is view state, not project state, so it is never part of the history; the clip itself
 * lives in the rig edits.
 */
internal class MotionEditorState {
	var clipId: String? by mutableStateOf(null)
	var playhead: Float by mutableStateOf(0f)
	var selection: Set<MotionKeyRef> by mutableStateOf(emptySet())
	/** The curve last clicked; the curve view shows it alone when no key is selected. */
	var focusedCurve: String? by mutableStateOf(null)
	var playing: Boolean by mutableStateOf(false)
	var view: MotionEditorView by mutableStateOf(MotionEditorView.DOPESHEET)
	var snapToFrames: Boolean by mutableStateOf(true)
	var autoKey: Boolean by mutableStateOf(false)
	/** Copied keys, times relative to the earliest. */
	var clipboard: List<Pair<String, MotionKey>> = emptyList()

	/** The clip a key drag started from; every drag sample is applied to it, not to the previous sample. */
	var dragOrigin: MotionClip? = null
	var dragSelection: Set<MotionKeyRef> = emptySet()

	companion object {
		private const val PRESET_PREFIX = "preset:"

		/** The editor's id for a generated motion: its override once edited, its generated tracks until then. */
		fun presetClipId(name: String) = PRESET_PREFIX + name

		/** The generated motion [clipId] names, or null for a user clip. */
		fun presetOf(clipId: String): String? = clipId.takeIf { it.startsWith(PRESET_PREFIX) }?.removePrefix(PRESET_PREFIX)
	}
}

internal object MotionKeyEdits {
	/** Inserts a parameter snapshot in one edit, creating missing curves and preserving existing handles. */
	fun pose(clip: MotionClip, values: Map<String, Float>, time: Float): MotionClip {
		val at = time.coerceIn(0f, clip.duration)
		return values.filterValues(Float::isFinite).entries.fold(clip) { next, (id, value) ->
			val previous = next.curve(id)?.keys?.firstOrNull(MotionKeyRef(id, at)::matches)
			setKey(next, id, previous?.copy(value = value) ?: MotionKey(at, value))
		}
	}
	fun keysOf(clip: MotionClip, selection: Set<MotionKeyRef>): List<Pair<String, MotionKey>> =
		clip.curves.flatMap { curve ->
			curve.keys.filter { key -> selection.any { it.parameterId == curve.parameterId && it.matches(key) } }
				.map { curve.parameterId to it }
		}

	fun setKey(clip: MotionClip, parameterId: String, key: MotionKey): MotionClip {
		val curve = clip.curve(parameterId)
		val keys = MotionClips.normalized((curve?.keys.orEmpty()) + key)
		val next = MotionCurve(parameterId, keys)
		return clip.copy(curves = if (curve == null) clip.curves + next else clip.curves.map { if (it.parameterId == parameterId) next else it })
	}

	/**
	 * Automatically records keyframes for [changes] at [time].
	 * If a parameter does not have a curve in [clip] yet (initial state changed):
	 * - It adds the curve to the clip.
	 * - If [time] > TIME_EPSILON, it archives the initial state by creating a keyframe at t=0 with [initialValues],
	 *   and adds the keyframe at [time] with the changed value.
	 * - If [time] <= TIME_EPSILON, it creates a keyframe at t=0 with the changed value.
	 * If the curve already exists:
	 * - If it has no key at t=0 and [time] > TIME_EPSILON, it also ensures a keyframe at t=0 with [initialValues].
	 * - It updates the keyframe at [time] if one exists (preserving interpolation and handles),
	 *   or inserts a new keyframe at [time].
	 */
	fun autoKey(
		clip: MotionClip,
		parameterId: String,
		time: Float,
		value: Float,
		initialValue: Float,
	): Pair<MotionClip, Set<MotionKeyRef>> =
		autoKeyMultiple(clip, mapOf(parameterId to value), mapOf(parameterId to initialValue), time)

	fun autoKeyMultiple(
		clip: MotionClip,
		changes: Map<String, Float>,
		initialValues: Map<String, Float>,
		time: Float,
	): Pair<MotionClip, Set<MotionKeyRef>> {
		if (changes.isEmpty()) return clip to emptySet()
		val at = time.coerceIn(0f, clip.duration)
		val keyRefs = mutableSetOf<MotionKeyRef>()
		val nextCurves = clip.curves.toMutableList()

		for ((paramId, value) in changes) {
			val existingIndex = nextCurves.indexOfFirst { it.parameterId == paramId }
			val initialVal = initialValues[paramId] ?: 0f

			if (existingIndex < 0) {
				val keys = if (at > MotionClips.TIME_EPSILON) {
					keyRefs += MotionKeyRef(paramId, 0f)
					keyRefs += MotionKeyRef(paramId, at)
					listOf(MotionKey(0f, initialVal), MotionKey(at, value))
				} else {
					keyRefs += MotionKeyRef(paramId, 0f)
					listOf(MotionKey(0f, value))
				}
				nextCurves += MotionCurve(paramId, keys)
			} else {
				val curve = nextCurves[existingIndex]
				val existingKey = curve.keys.firstOrNull { abs(it.time - at) < MotionClips.TIME_EPSILON }
				val updatedKey = existingKey?.copy(value = value) ?: MotionKey(at, value)
				keyRefs += MotionKeyRef(paramId, at)

				val needsInitialKey = at > MotionClips.TIME_EPSILON && curve.keys.none { it.time <= MotionClips.TIME_EPSILON }
				val baseKeys = if (needsInitialKey) {
					keyRefs += MotionKeyRef(paramId, 0f)
					curve.keys + MotionKey(0f, initialVal)
				} else {
					curve.keys
				}

				val nextKeys = MotionClips.normalized(baseKeys + updatedKey)
				nextCurves[existingIndex] = curve.copy(keys = nextKeys)
			}
		}

		return clip.copy(curves = nextCurves) to keyRefs
	}

	/** Every selected key replaced by [transform]; a curve left without keys is removed. */
	fun mapKeys(clip: MotionClip, selection: Set<MotionKeyRef>, transform: (String, MotionKey) -> MotionKey?): MotionClip =
		clip.copy(curves = clip.curves.mapNotNull { curve ->
			val picked = selection.filter { it.parameterId == curve.parameterId }
			if (picked.isEmpty()) return@mapNotNull curve
			val kept = curve.keys.filter { key -> picked.none { it.matches(key) } }
			val changed = curve.keys.filter { key -> picked.any { it.matches(key) } }.mapNotNull { transform(curve.parameterId, it) }
			// Moved keys land over the ones they reach.
			val keys = MotionClips.normalized(kept + changed)
			if (keys.isEmpty()) null else MotionCurve(curve.parameterId, keys)
		})

	/**
	 * The selected keys shifted by [dt] seconds (clamped to the clip) and [dv] units, and the selection that
	 * follows them. With [normalized], [dv] is a fraction of each curve's parameter range, so keys of
	 * parameters with different ranges move together in the curve view.
	 */
	fun move(
		clip: MotionClip,
		selection: Set<MotionKeyRef>,
		dt: Float,
		dv: Float,
		ranges: Map<String, ClosedFloatingPointRange<Float>> = emptyMap(),
		normalized: Boolean = false,
	): Pair<MotionClip, Set<MotionKeyRef>> {
		val picked = keysOf(clip, selection)
		if (picked.isEmpty()) return clip to selection
		val minTime = picked.minOf { it.second.time }
		val maxTime = picked.maxOf { it.second.time }
		val shift = dt.coerceIn(-minTime, clip.duration - maxTime)
		val moved = mutableSetOf<MotionKeyRef>()
		val next = mapKeys(clip, selection) { parameterId, key ->
			val range = ranges[parameterId]
			val delta = if (normalized && range != null) dv * (range.endInclusive - range.start) else dv
			val value = (key.value + delta).let { if (range != null) it.coerceIn(range) else it }
			key.copy(time = key.time + shift, value = value).also { moved += MotionKeyRef(parameterId, it.time) }
		}
		return next to moved
	}

	fun delete(clip: MotionClip, selection: Set<MotionKeyRef>): MotionClip = mapKeys(clip, selection) { _, _ -> null }

	/** [keys] (relative times) pasted at [at], each onto its own curve. */
	fun paste(clip: MotionClip, keys: List<Pair<String, MotionKey>>, at: Float): Pair<MotionClip, Set<MotionKeyRef>> {
		var next = clip
		val pasted = mutableSetOf<MotionKeyRef>()
		for ((parameterId, key) in keys) {
			val time = (at + key.time).coerceIn(0f, clip.duration)
			next = setKey(next, parameterId, key.copy(time = time))
			pasted += MotionKeyRef(parameterId, time)
		}
		return next to pasted
	}

	/** Relative copies of the selected keys, for [paste]. */
	fun copy(clip: MotionClip, selection: Set<MotionKeyRef>): List<Pair<String, MotionKey>> {
		val picked = keysOf(clip, selection)
		val start = picked.minOfOrNull { it.second.time } ?: return emptyList()
		return picked.map { (id, key) -> id to key.copy(time = key.time - start) }
	}

	/** A shorter clip drops the keys past its end, holding each curve's value there. */
	fun withDuration(clip: MotionClip, duration: Float): MotionClip {
		if (duration >= clip.duration) return clip.copy(duration = duration)
		return clip.copy(duration = duration, curves = clip.curves.map { curve ->
			val inside = curve.keys.filter { it.time <= duration + MotionClips.TIME_EPSILON }
			val keys = if (inside.size == curve.keys.size) inside
				else MotionClips.normalized(inside + MotionKey(duration, MotionClips.sample(curve, duration)))
			MotionCurve(curve.parameterId, keys)
		})
	}

	fun snap(time: Float, fps: Float): Float = (Math.round(time * fps) / fps)
}
