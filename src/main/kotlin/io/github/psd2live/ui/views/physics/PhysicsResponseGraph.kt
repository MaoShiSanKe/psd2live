package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsResponse
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.util.Locale

/**
 * How the group answers a standard pull (fully right for a second, then let go), one curve per output as a
 * fraction of its range, redrawn as the group changes. The faint curve is the pull itself.
 */
@Composable
internal fun ResponseGraph(setting: RigPhysicsEdit, ranges: Map<String, PhysicsEngine.Range>, fps: Int, label: (String) -> String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val trace = remember(setting, ranges, fps) { PhysicsResponse.trace(setting, ranges, fps) }
	Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Text(tr("physics.response"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
		Canvas(
			Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(3.dp)).background(colors.inputBackground)
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp)),
		) {
			val w = size.width
			val h = size.height
			val end = trace.times.last().coerceAtLeast(0.01f)
			fun x(t: Float) = t / end * w
			fun y(v: Float) = h / 2f - v.coerceIn(-1.1f, 1.1f) * (h / 2f - 4f)
			for (t in 1..(end * 2).toInt()) drawLine(colors.border.copy(alpha = 0.5f), Offset(x(t / 2f), 0f), Offset(x(t / 2f), h), 0.8f)
			drawLine(colors.border, Offset(0f, y(0f)), Offset(w, y(0f)), 1f)
			for (v in listOf(-1f, 1f)) drawLine(colors.border.copy(alpha = 0.6f), Offset(0f, y(v)), Offset(w, y(v)), 0.8f,
				pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
			drawLine(colors.textMuted.copy(alpha = 0.6f), Offset(x(trace.hold), 0f), Offset(x(trace.hold), h), 1f)
			fun curve(values: FloatArray, color: Color, width: Float) {
				val path = androidx.compose.ui.graphics.Path()
				values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(trace.times[i]), y(v)) else path.lineTo(x(trace.times[i]), y(v)) }
				drawPath(path, color, style = Stroke(width))
			}
			curve(trace.drag, colors.textMuted.copy(alpha = 0.45f), 1f)
			setting.outputs.forEachIndexed { j, o -> trace.outputs[o.parameter]?.let { curve(it, outputColor(j), 1.6f) } }
		}
		setting.outputs.forEachIndexed { j, o ->
			if (o.parameter !in trace.outputs) return@forEachIndexed
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				Box(Modifier.size(6.dp).clip(CircleShape).background(outputColor(j)))
				Text(
					tr("physics.responseLine", label(o.parameter), String.format(Locale.US, "%.0f", trace.peak(o.parameter) * 100f),
						String.format(Locale.US, "%.1f", (trace.settleTime(o.parameter) - trace.hold).coerceAtLeast(0f))),
					style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}
