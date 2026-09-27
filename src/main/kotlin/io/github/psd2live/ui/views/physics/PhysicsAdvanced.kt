package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/** The numbers the canvas and sliders cover, for exact values and the rarely changed ones. */
@Composable
internal fun AdvancedSection(viewModel: PSD2LiveViewModel, setting: RigPhysicsEdit, edit: ((RigPhysicsEdit) -> RigPhysicsEdit) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	@Composable
	fun Number(value: Float, min: Double, max: Double, step: Double, decimals: Int, modifier: Modifier, onChange: (Float) -> Unit) =
		NumberField(viewModel, value, min, max, step, decimals, modifier, onChange)
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("ID: ${setting.id}", style = typography.monoSmall, color = colors.textMuted)

		SubTitle(tr("physics.segmentTable"))
		Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel("#", 16)
			for (key in listOf("physics.col.length", "physics.col.mobility", "physics.col.delay", "physics.col.acceleration")) {
				Text(tr(key), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
			}
		}
		setting.segments.forEachIndexed { i, s ->
			fun set(change: (PhysicsSegment) -> PhysicsSegment) = edit { e -> e.copy(segments = e.segments.mapIndexed { j, g -> if (j == i) change(g) else g }) }
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
				FieldLabel("${i + 1}", 16)
				Number(s.length, 0.01, 500.0, 0.5, 1, Modifier.weight(1f)) { v -> set { it.copy(length = v) } }
				Number(s.mobility, 0.0, 1.0, 0.05, 2, Modifier.weight(1f)) { v -> set { it.copy(mobility = v) } }
				Number(s.delay, 0.01, 10.0, 0.05, 2, Modifier.weight(1f)) { v -> set { it.copy(delay = v) } }
				Number(s.acceleration, 0.0, 20.0, 0.1, 2, Modifier.weight(1f)) { v -> set { it.copy(acceleration = v) } }
			}
		}

		if (setting.outputs.isNotEmpty()) {
			SubTitle(tr("physics.outputDetails"))
			setting.outputs.forEachIndexed { i, o ->
				fun set(change: (PhysicsOutput) -> PhysicsOutput) = edit { e -> e.copy(outputs = e.outputs.mapIndexed { j, x -> if (j == i) change(x) else x }) }
				Text(o.parameter, style = typography.monoSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					FieldLabel(tr("physics.outputScale"))
					Number(o.scale, -100.0, 100.0, 0.1, 3, Modifier.weight(1f)) { v -> set { it.copy(scale = v) } }
					FieldLabel(tr("physics.weight"), 40)
					Number(o.weight, 0.0, 100.0, 5.0, 0, Modifier.weight(1f)) { v -> set { it.copy(weight = v) } }
				}
			}
		}

		SubTitle(tr("physics.inputNormalization"))
		val n = setting.normalization
		fun norm(change: (PhysicsNormalization) -> PhysicsNormalization) = edit { e -> e.copy(normalization = change(e.normalization)) }
		Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel("", 48)
			for (key in listOf("physics.min", "physics.center", "physics.max")) {
				Text(tr(key), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel(tr("physics.positionX"), 48)
			Number(n.positionMin, -1000.0, 1000.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(positionMin = v) } }
			Number(n.positionDefault, -1000.0, 1000.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(positionDefault = v) } }
			Number(n.positionMax, -1000.0, 1000.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(positionMax = v) } }
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel(tr("physics.angle"), 48)
			Number(n.angleMin, -360.0, 360.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(angleMin = v) } }
			Number(n.angleDefault, -360.0, 360.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(angleDefault = v) } }
			Number(n.angleMax, -360.0, 360.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(angleMax = v) } }
		}
		Text(tr("physics.normalizationHint"), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
	}
}

@Composable
internal fun SubTitle(text: String) {
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
		color = LocalToolColors.current.textPrimary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun NumberField(
	viewModel: PSD2LiveViewModel,
	value: Float,
	min: Double,
	max: Double,
	step: Double,
	decimals: Int,
	modifier: Modifier,
	onChange: (Float) -> Unit,
) {
	CompactNumberSpinner(value.toDouble(), { onChange(it.toFloat()) }, modifier = modifier, min = min, max = max, step = step, decimals = decimals,
		height = 20.dp, onEditStart = { viewModel.beginEditorField(PHYSICS_FIELD) }, onEditEnd = { viewModel.endEditorField(PHYSICS_FIELD) })
}
