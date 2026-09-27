package io.github.psd2live.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.CanvasBackground
import io.github.psd2live.ui.state.CanvasBackgroundKind
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/**
 * The canvas header's background dropdown: the kind, then the colours of that kind. Picking a kind
 * keeps the menu open so its colours can be tuned straight away, except that switching to or from
 * transparent rebuilds the window, which closes the menu with it.
 */
@Composable
fun CanvasBackgroundMenuItems(
	background: CanvasBackground,
	onChange: (CanvasBackground) -> Unit,
) {
	val colors = LocalToolColors.current
	AppMenuHeader(tr("canvas.background"))
	for (kind in CanvasBackgroundKind.entries) {
		AppMenuItem(
			text = tr("canvas.background.${kind.id}"),
			isChecked = background.kind == kind,
			icon = { tint -> BackgroundKindIcon(kind, tint) },
			onClick = { if (background.kind != kind) onChange(background.copy(kind = kind)) },
		)
	}
	AppMenuSeparator()
	when (background.kind) {
		CanvasBackgroundKind.CHECKER -> {
			SettingRow(tr("canvas.background.colors")) {
				ColorChip(background.checkerLight?.let(::rgbColor) ?: colors.checkerLight) {
					onChange(background.copy(checkerLight = it))
				}
				ColorChip(background.checkerDark?.let(::rgbColor) ?: colors.checkerDark) {
					onChange(background.copy(checkerDark = it))
				}
			}
			SettingRow(tr("canvas.background.cellSize")) {
				for (size in CanvasBackground.CHECKER_SIZES) {
					SizeChip(size, background.checkerSize == size) { onChange(background.copy(checkerSize = size)) }
				}
			}
		}
		CanvasBackgroundKind.SOLID -> SettingRow(tr("canvas.background.color")) {
			ColorChip(background.solidColor?.let(::rgbColor) ?: colors.checkerDark) {
				onChange(background.copy(solidColor = it))
			}
		}
		CanvasBackgroundKind.TRANSPARENT -> Text(
			text = tr("canvas.background.transparent.hint"),
			style = LocalToolTypography.current.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
			modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
		)
	}
	val customized = background.solidColor != null || background.checkerLight != null ||
		background.checkerDark != null || background.checkerSize != CanvasBackground.DEFAULT_CHECKER_SIZE
	if (background.kind != CanvasBackgroundKind.TRANSPARENT) {
		AppMenuItem(
			text = tr("canvas.background.reset"),
			enabled = customized,
			onClick = { onChange(CanvasBackground(kind = background.kind)) },
		)
	}
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
	val colors = LocalToolColors.current
	Row(
		modifier = Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(
			text = label,
			style = LocalToolTypography.current.body.copy(fontSize = 11.5.sp),
			color = colors.textPrimary,
			modifier = Modifier.weight(1f),
		)
		content()
	}
}

@Composable
private fun ColorChip(color: Color, onPicked: (Int) -> Unit) {
	PaintColorChip(
		color = color,
		onColorChanged = { onPicked(it.toArgb() and 0xFFFFFF) },
		modifier = Modifier.size(width = 26.dp, height = 18.dp),
		popupOffset = 22.dp,
		shape = RoundedCornerShape(3.dp),
	)
}

@Composable
private fun SizeChip(size: Int, active: Boolean, onClick: () -> Unit) {
	val colors = LocalToolColors.current
	Text(
		text = "$size",
		style = LocalToolTypography.current.body.copy(
			fontSize = 10.5.sp,
			fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
		),
		color = if (active) colors.accent else colors.textMuted,
		modifier = Modifier
			.width(24.dp)
			.clip(RoundedCornerShape(3.dp))
			.background(if (active) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
			.border(1.dp, if (active) colors.accent.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(3.dp))
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.clickable(onClick = onClick)
			.padding(vertical = 2.dp),
		textAlign = androidx.compose.ui.text.style.TextAlign.Center,
	)
}

@Composable
private fun BackgroundKindIcon(kind: CanvasBackgroundKind, tint: Color) {
	Canvas(Modifier.size(12.dp)) {
		val cell = size.width / 2
		when (kind) {
			CanvasBackgroundKind.CHECKER -> {
				drawRect(tint, Offset.Zero, Size(cell, cell))
				drawRect(tint, Offset(cell, cell), Size(cell, cell))
			}
			CanvasBackgroundKind.SOLID -> drawRect(tint)
			CanvasBackgroundKind.TRANSPARENT -> {}
		}
		drawRect(tint, style = Stroke(width = 1.dp.toPx()))
	}
}

private fun rgbColor(rgb: Int): Color = Color(0xFF000000L or (rgb.toLong() and 0xFFFFFF))
