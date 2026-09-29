package io.github.psd2live.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.psd2live.core.BakeResult
import io.github.psd2live.core.BakeShape
import io.github.psd2live.core.BakeWrite
import io.github.psd2live.core.PoseEase

/**
 * One pose of the bake dialog's sequence: the bone parameters as they were when it was captured, and
 * optionally a bone whose tip is pulled to canvas point ([ikX], [ikY]) instead of turned by those values.
 */
internal data class BakePoseRow(
	val id: Int,
	val time: Float,
	val values: Map<String, Float>,
	val ikBoneId: String? = null,
	val ikX: Float = 0f,
	val ikY: Float = 0f,
	val ease: PoseEase = PoseEase.SMOOTH,
)

/** What the current dialog inputs bake to, or why they cannot. */
internal class BakePreview(val result: BakeResult?, val error: String?)

/**
 * The skeleton bake dialog's inputs and its latest preview. It is view state, not project state, so it is
 * never part of the history; only the bake it commits is.
 */
internal class SkeletonBakeState {
	var open: Boolean by mutableStateOf(false)
	var rows: List<BakePoseRow> by mutableStateOf(emptyList())
	var fps: Float by mutableStateOf(30f)
	/** In the parameter's units: degrees for a bone. */
	var tolerance: Float by mutableStateOf(0.5f)
	var shape: BakeShape by mutableStateOf(BakeShape.BEZIER)
	/** The bones to bake; null is every bone the poses hold. */
	var boneIds: Set<String>? by mutableStateOf(null)
	/** The time range to bake; null follows the first and last pose. */
	var start: Float? by mutableStateOf(null)
	var end: Float? by mutableStateOf(null)
	/** The clip to write into; null writes a new one named [newName]. */
	var targetClipId: String? by mutableStateOf(null)
	var newName: String by mutableStateOf("")
	var write: BakeWrite by mutableStateOf(BakeWrite.REPLACE)
	var preview: BakePreview? by mutableStateOf(null)
	var computing: Boolean by mutableStateOf(false)
	/** Where the preview scrubber poses the canvas; playing advances it. */
	var previewTime: Float by mutableStateOf(0f)
	var previewPlaying: Boolean by mutableStateOf(false)
	var nextRowId: Int = 1
}
