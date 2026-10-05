package io.github.psd2live.ui.state

import io.github.psd2live.project.ParameterSnapshot
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Parameter

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
