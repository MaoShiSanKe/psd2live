package io.github.psd2live.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionPresetSettings
import io.github.psd2live.core.SkeletonMotions
import io.github.psd2live.core.SkeletonSpec
import org.umamo.runtime.model.ParameterId

/**
 * The one-shot motion the preview plays on its own clock. Cubism plays the exported motion once its runtime
 * is up; this clock drives the software preview until then, and tells the panel what is playing.
 *
 * A motion owns its tracks from [start] to its last point and then ends itself, so a later [start] always
 * replaces it and nothing outlives its duration. The loop preset plays one cycle. An edited generated motion
 * plays its clip, and a user clip plays by its export stem (see [MotionClips.exportStems]).
 */
internal class PreviewMotionPlayer {
	/** The playing motion, lower case; observable, so the panel's badge clears the moment it ends. */
	var activeName: String? by mutableStateOf(null)
		private set
	private var clip: MotionClip? = null
	private var elapsed = 0.0

	/** Plays [name] from its start, replacing whatever is playing; false for a motion without tracks. */
	fun start(
		name: String,
		skeleton: SkeletonSpec?,
		clips: List<MotionClip> = emptyList(),
		presets: Map<String, MotionPresetSettings> = emptyMap(),
	): Boolean {
		val next = clipOf(name, skeleton, clips, presets)?.takeIf { it.curves.isNotEmpty() }
		if (next == null) {
			stop()
			return false
		}
		clip = next
		elapsed = 0.0
		activeName = name.lowercase()
		return true
	}

	/** Stops the playing motion, or only [name] when given. */
	fun stop(name: String? = null) {
		if (name != null && !name.equals(activeName, ignoreCase = true)) return
		activeName = null
		clip = null
		elapsed = 0.0
	}

	/** Advances the playing motion by [dt] seconds and returns the values it holds, empty once it has ended. */
	fun advance(dt: Float): Map<ParameterId, Float> {
		val playing = clip ?: return emptyMap()
		elapsed += dt
		if (elapsed > playing.duration) {
			stop()
			return emptyMap()
		}
		return MotionClips.sampleAll(playing, elapsed, loop = false)
	}

	companion object {
		/**
		 * What [name] plays: the user's override or clip, else the generated tracks as [presets] tune them;
		 * null when nothing does. The idle is the running animation's, never a one-shot.
		 */
		fun clipOf(
			name: String,
			skeleton: SkeletonSpec?,
			clips: List<MotionClip>,
			presets: Map<String, MotionPresetSettings> = emptyMap(),
		): MotionClip? {
			MotionClips.overrideOf(clips, name)?.let { return it }
			val stems = MotionClips.exportStems(clips)
			clips.firstOrNull { it.builtin == null && stems[it.id].equals(name, ignoreCase = true) }?.let { return it }
			if (name.equals("Idle", ignoreCase = true)) return null
			val settings = presets.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value ?: MotionPresetSettings()
			if (settings.deleted) return null
			val tracks = MotionClips.builtinTracks(name, skeleton, settings = settings).takeIf { it.isNotEmpty() } ?: return null
			return MotionClips.fromTracks("preview", name, builtin = null, loop = false, tracks = tracks)
		}

		fun isSkeletonMotion(name: String?): Boolean =
			name != null && SkeletonMotions.presets.any { it.name.equals(name, ignoreCase = true) }
	}
}
