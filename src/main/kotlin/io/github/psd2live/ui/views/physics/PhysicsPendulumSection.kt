package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/**
 * Which pendulum the sliders edit (all or one), and the pendulum list itself: add one below the selected
 * (or at the tip), remove the selected (or the last), move the selected up or down.
 */
@Composable
internal fun SegmentBar(
	count: Int,
	selected: Int?,
	onSelect: (Int?) -> Unit,
	onAdd: () -> Unit,
	onRemove: () -> Unit,
	onMove: (Int) -> Unit,
) {
	@Composable
	fun Action(text: String, tooltip: String, enabled: Boolean, onClick: () -> Unit) =
		CompactIconButton(onClick = onClick, size = 20.dp, enabled = enabled, tooltip = tooltip) {
			Text(text, style = LocalToolTypography.current.body, color = LocalToolColors.current.textPrimary)
		}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("physics.segments"), width = 76, tooltip = tr("physics.segments.tip"))
		CompactToggleChip(tr("physics.segments.all"), selected == null, { onSelect(null) }, showCheckWhenSelected = false, height = 20.dp)
		if (count <= 6) {
			for (i in 0 until count) {
				CompactToggleChip("${i + 1}", selected == i, { onSelect(i) }, showCheckWhenSelected = false, height = 20.dp)
			}
		} else {
			CompactDropdown((0 until count).toList(), selected ?: 0, { onSelect(it) }, itemLabel = { "${it + 1}" },
				modifier = Modifier.width(48.dp), height = 20.dp)
		}
		Spacer(Modifier.weight(1f))
		Action("↑", tr("physics.moveSegmentUp"), selected != null && selected > 0) { onMove(-1) }
		Action("↓", tr("physics.moveSegmentDown"), selected != null && selected < count - 1) { onMove(1) }
		Action("+", tr("physics.addSegment"), count < RigPhysicsEdit.MAX_SEGMENTS, onAdd)
		Action("−", tr("physics.removeSegment"), count > 1, onRemove)
	}
}

/** Length, shakiness, reaction and settling of the chosen segment, or of every segment at once. */
@Composable
internal fun DynamicsSliders(
	viewModel: PSD2LiveViewModel,
	setting: RigPhysicsEdit,
	segment: Int?,
	edit: ((RigPhysicsEdit) -> RigPhysicsEdit) -> Unit,
) {
	val shown = segment?.let { setting.segments[it] } ?: setting.segments.first()
	fun set(change: (PhysicsSegment) -> PhysicsSegment) = edit { s ->
		s.copy(segments = s.segments.mapIndexed { i, g -> if (segment == null || segment == i) change(g) else g })
	}
	if (segment == null) {
		SliderRow(viewModel, tr("physics.totalLength"), setting.totalLength, 0.5f..60f, "%.1f") { v -> edit { it.withTotalLength(v) } }
	} else {
		SliderRow(viewModel, tr("physics.length"), shown.length, 0.2f..30f, "%.1f") { v -> set { it.copy(length = v) } }
	}
	SliderRow(viewModel, tr("physics.shakiness"), shown.mobility, 0f..1f) { v -> set { it.copy(mobility = v) } }
	SliderRow(viewModel, tr("physics.reactionSpeed"), shown.delay, 0.05f..3f) { v -> set { it.copy(delay = v) } }
	SliderRow(viewModel, tr("physics.convergenceSpeed"), shown.acceleration, 0f..5f) { v -> set { it.copy(acceleration = v) } }
}
