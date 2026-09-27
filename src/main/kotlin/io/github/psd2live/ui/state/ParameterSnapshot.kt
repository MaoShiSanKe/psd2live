package io.github.psd2live.ui.state

import androidx.compose.runtime.Immutable
import org.umamo.runtime.model.ParameterId

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
