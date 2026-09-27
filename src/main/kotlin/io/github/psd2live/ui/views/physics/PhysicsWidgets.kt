package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.util.Locale
import kotlin.math.abs

internal const val PHYSICS_FIELD = "physics-field"

/** Output colors, shared by the pendulum, the response curve and the output rows. */
internal val OUTPUT_COLORS = listOf(Color(0xFF5C9CF5), Color(0xFFE5A04B), Color(0xFF5CC08A), Color(0xFFD27AD6), Color(0xFFE06C75), Color(0xFF56C2C9))

internal fun outputColor(index: Int) = OUTPUT_COLORS[index % OUTPUT_COLORS.size]

/** Half an output parameter's range: the most it moves either way from its default. */
internal fun halfRange(range: PhysicsEngine.Range?) =
	range?.let { maxOf(abs(it.max - it.default), abs(it.min - it.default)) }?.coerceAtLeast(1e-6f) ?: 1f

@Composable
internal fun SliderRow(
	viewModel: PSD2LiveViewModel,
	title: String,
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	format: String = "%.2f",
	onChange: (Float) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(title)
		CompactSlider(
			value, { onChange((it * 100f).toInt() / 100f) },
			onValueChangeStarted = viewModel::beginEditorGesture,
			onValueChangeFinished = viewModel::endEditorGesture,
			modifier = Modifier.weight(1f), valueRange = range,
		)
		Text(String.format(Locale.US, format, value), style = typography.monoSmall, color = colors.textPrimary, modifier = Modifier.width(34.dp))
	}
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun FieldLabel(text: String, width: Int = 64, tooltip: String? = null) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val label = @Composable {
		Text(
			text, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted,
			modifier = Modifier.width(width.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
		)
	}
	if (tooltip == null) return label()
	TooltipArea(
		tooltip = {
			Surface(color = colors.panelElevated, shape = RoundedCornerShape(3.dp), border = BorderStroke(1.dp, colors.border), elevation = 4.dp) {
				Text(tooltip, style = typography.caption.copy(fontSize = 10.sp), color = colors.textPrimary,
					modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 6.dp, vertical = 3.dp))
			}
		},
		delayMillis = 300,
	) { label() }
}

@Composable
internal fun Hint(text: String) {
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp), color = LocalToolColors.current.warning)
}
