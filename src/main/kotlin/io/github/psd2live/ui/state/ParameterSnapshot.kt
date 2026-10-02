package io.github.psd2live.ui.state

import androidx.compose.runtime.Immutable
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Parameter

/**
 * A saved pose: every parameter value at the moment it was taken. Stored with the project,
 * not the rig, so it never enters history or export; loading one only moves the preview pose.
 */
@Immutable
data class ParameterSnapshot(
    val id: String,
    /** Given once when saved and kept after other snapshots are deleted. */
    val number: Int,
    /** Blank until renamed; the bar then shows [number]. */
    val name: String,
    val values: Map<ParameterId, Float>,
)

/** A hover belongs to one input session, never to the saved project or authored pose. */
internal class ParameterSnapshotPreview(
    val snapshotId: String,
    val generation: Long,
    val workspaceId: String,
    val canvasId: String,
)

/** Old snapshots can name deleted axes, omit new axes, or predate a range change. */
internal fun ParameterSnapshot.previewValues(parameters: List<Parameter>): Map<ParameterId, Float> =
    parameters.associate { parameter ->
        parameter.id to (values[parameter.id]?.takeIf { it.isFinite() } ?: parameter.default)
            .coerceIn(parameter.min, parameter.max)
    }
