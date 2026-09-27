package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import java.util.Locale
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.model.Parameter
import java.awt.Cursor

/** "+ Add" with the parameters a group can take; [driven] ones are shown but belong to another group. */
@Composable
internal fun AddParameterButton(
	text: String,
	parameters: List<Parameter>,
	exclude: Set<String>,
	driven: Map<String, String>,
	label: (String) -> String,
	presets: List<Pair<String, () -> Unit>> = emptyList(),
	onAdd: (String) -> Unit,
) {
	var open by remember { mutableStateOf(false) }
	Box {
		CompactButton(text = "+ $text", onClick = { open = true }, height = 18.dp)
		TreeContextMenu(expanded = open, onDismissRequest = { open = false }, maxWidth = 320.dp) {
			for ((name, apply) in presets) CompactMenuItem(name, { open = false; apply() })
			if (presets.isNotEmpty()) CompactMenuDivider()
			ParameterMenuItems(parameters.filter { it.id.raw !in exclude }, driven, label) { open = false; onAdd(it) }
		}
	}
}

/** The model's standard inputs first, then the rest in panel order, in a scrolling list. */
@Composable
internal fun ColumnScope.ParameterMenuItems(parameters: List<Parameter>, driven: Map<String, String>, label: (String) -> String, onPick: (String) -> Unit) {
	val common = listOf("ParamAngleX", "ParamAngleY", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleY", "ParamBodyAngleZ")
	val sorted = parameters.sortedBy { p -> common.indexOf(p.id.raw).let { if (it < 0) Int.MAX_VALUE else it } }
	Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
		if (sorted.isEmpty()) CompactMenuSection(tr("physics.noParameters"))
		for (p in sorted) {
			val owner = driven[p.id.raw]
			CompactMenuItem(label(p.id.raw), { onPick(p.id.raw) }, enabled = owner == null,
				trailingText = owner?.let { tr("physics.drivenBy", it) } ?: p.id.raw.takeIf { it != label(it) })
		}
	}
}

@Composable
internal fun ParameterPicker(
	value: String,
	parameters: List<Parameter>,
	exclude: Set<String>,
	driven: Map<String, String>,
	label: (String) -> String,
	modifier: Modifier = Modifier,
	onPick: (String) -> Unit,
) {
	val items = (listOf(value) + parameters.map { it.id.raw }.filter { it !in exclude }).distinct()
	CompactDropdown(items, value, onPick, modifier = modifier, height = 22.dp, itemLabel = label,
		itemEnabled = { it == value || it !in driven })
}

@Composable
internal fun InputRow(
	viewModel: PSD2LiveViewModel,
	input: PhysicsInput,
	parameters: List<Parameter>,
	setting: RigPhysicsEdit,
	label: (String) -> String,
	onChange: (PhysicsInput) -> Unit,
	onRemove: () -> Unit,
) {
	val colors = LocalToolColors.current
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			ParameterPicker(input.parameter, parameters, setting.parameters.toSet(), emptyMap(), label, Modifier.weight(1f)) { onChange(input.copy(parameter = it)) }
			TypeChips(input.type) { onChange(input.copy(type = it)) }
			CompactToggleChip("⇄", input.reflect, { onChange(input.copy(reflect = !input.reflect)) }, showCheckWhenSelected = false, height = 20.dp)
			CompactIconButton(onClick = onRemove, size = 20.dp, tooltip = tr("physics.remove")) {
				IconClose(tint = colors.textMuted, modifier = Modifier.size(9.dp))
			}
		}
		SliderRow(viewModel, tr("physics.weight"), input.weight, 0f..100f, "%.0f%%") { onChange(input.copy(weight = it.coerceIn(0f, 100f))) }
	}
}

@Composable
internal fun OutputRow(
	viewModel: PSD2LiveViewModel,
	index: Int,
	output: PhysicsOutput,
	selected: Boolean,
	onSelect: () -> Unit,
	parameters: List<Parameter>,
	setting: RigPhysicsEdit,
	driven: Map<String, String>,
	label: (String) -> String,
	/** How far the output has swung toward its parameter's end (1 = exactly), or null before it moved. */
	reach: Float?,
	onChange: (PhysicsOutput) -> Unit,
	onRemove: () -> Unit,
) {
	val colors = LocalToolColors.current
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			// The dot shows this output's range on the pendulum.
			Box(
				Modifier.size(14.dp).clip(CircleShape).background(if (selected) outputColor(index).copy(alpha = 0.3f) else Color.Transparent)
					.clickable(onClick = onSelect).pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
				contentAlignment = Alignment.Center,
			) { Box(Modifier.size(7.dp).clip(CircleShape).background(outputColor(index))) }
			ParameterPicker(output.parameter, parameters, setting.parameters.toSet(), driven, label, Modifier.weight(1f)) { onChange(output.copy(parameter = it)) }
			CompactToggleChip("⇄", output.reflect, { onChange(output.copy(reflect = !output.reflect)) }, showCheckWhenSelected = false, height = 20.dp)
			CompactIconButton(onClick = onRemove, size = 20.dp, tooltip = tr("physics.remove")) {
				IconClose(tint = colors.textMuted, modifier = Modifier.size(9.dp))
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			FieldLabel(tr("physics.vertex"), width = 76, tooltip = tr("physics.vertex.tip"))
			val count = setting.segments.size
			if (count <= 6) {
				for (v in 1..count) CompactToggleChip("$v", output.vertex == v, { onChange(output.copy(vertex = v)) }, showCheckWhenSelected = false, height = 20.dp)
			} else {
				CompactDropdown((1..count).toList(), output.vertex, { onChange(output.copy(vertex = it)) }, modifier = Modifier.width(48.dp), height = 20.dp)
			}
			Spacer(Modifier.weight(1f))
			// Cubism Editor's maximum output: over 100% the parameter is clamped at its end.
			Text(
				tr("physics.reach", reach?.let { String.format(Locale.US, "%.0f%%", it * 100f) } ?: "–"),
				style = LocalToolTypography.current.monoSmall,
				color = if (reach != null && reach > 1.005f) colors.warning else colors.textMuted,
				maxLines = 1,
			)
		}
		SliderRow(viewModel, tr("physics.outputScale"), output.scale, 0f..5f) { onChange(output.copy(scale = it)) }
		if (output.type == PhysicsSourceType.X) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(tr("physics.outputX"), style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp), color = colors.warning, modifier = Modifier.weight(1f))
				CompactButton(tr("physics.outputToAngle"), { onChange(output.copy(type = PhysicsSourceType.ANGLE)) }, height = 18.dp)
			}
		}
	}
}

@Composable
internal fun TypeChips(type: PhysicsSourceType, onChange: (PhysicsSourceType) -> Unit) {
	for (t in PhysicsSourceType.entries) {
		CompactToggleChip(tr("physics.type.${t.name.lowercase()}"), type == t, { onChange(t) }, showCheckWhenSelected = false, height = 20.dp)
	}
}

/**
 * Sizing the outputs from what they reached, as Cubism Editor's output adjustment does: fit scales each
 * output so its largest swing just reaches the parameter's end; reset starts measuring again.
 */
@Composable
internal fun OutputReachBar(onFit: () -> Unit, onReset: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		Box(Modifier.weight(1f)) { FieldLabel(tr("physics.reach.hint"), width = 400, tooltip = tr("physics.reach.tip")) }
		CompactButton(tr("physics.fitScale"), onFit, height = 20.dp)
		CompactButton(tr("physics.resetReach"), onReset, height = 20.dp)
	}
}
