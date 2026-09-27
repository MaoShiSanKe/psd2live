package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.AppMenuHeader
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/**
 * "+" menu of the workspace strip: presets on the left, each with its icon and a small layout
 * sketch; the hovered one's arrangement is drawn larger on the right with its purpose.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NewWorkspaceMenu(
	expanded: Boolean,
	onDismissRequest: () -> Unit,
	onCreate: (WorkspacePreset) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var hovered by remember(expanded) { mutableStateOf(WorkspacePreset.EDIT) }
	DropdownMenu(
		expanded = expanded,
		onDismissRequest = onDismissRequest,
		modifier = Modifier
			.background(colors.panelElevated)
			.border(BorderStroke(1.dp, colors.border)),
	) {
		AppMenuHeader(tr("workspace.new"))
		Row(Modifier.padding(bottom = 4.dp)) {
			Column(Modifier.width(196.dp)) {
				WorkspacePreset.entries.forEach { preset ->
					if (preset == WorkspacePreset.BLANK) {
						Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(colors.divider))
					}
					PresetRow(
						preset = preset,
						highlighted = preset == hovered,
						onHover = { hovered = preset },
						onClick = {
							onDismissRequest()
							onCreate(preset)
						},
					)
				}
			}
			Box(Modifier.width(1.dp).height(PREVIEW_PANE_HEIGHT).background(colors.divider))
			Column(
				modifier = Modifier.width(236.dp).height(PREVIEW_PANE_HEIGHT).padding(horizontal = 10.dp, vertical = 4.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				WorkspaceLayoutThumbnail(
					preset = hovered,
					labels = true,
					modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
				)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					WorkspacePresetIcon(hovered, colors.textPrimary, Modifier.size(13.dp))
					Text(
						text = hovered.title(),
						style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
						color = colors.textPrimary,
					)
				}
				Text(
					text = hovered.description(),
					style = typography.caption.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
					color = colors.textMuted,
				)
			}
		}
	}
}

private val PREVIEW_PANE_HEIGHT = 236.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PresetRow(
	preset: WorkspacePreset,
	highlighted: Boolean,
	onHover: () -> Unit,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val content = if (highlighted) colors.selectionText else colors.textPrimary
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(30.dp)
			.background(if (highlighted) colors.selection else Color.Transparent)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.onPointerEvent(PointerEventType.Enter) { onHover() }
			.clickable(onClick = onClick)
			.padding(horizontal = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Box(Modifier.size(15.dp), contentAlignment = Alignment.Center) {
			WorkspacePresetIcon(preset, content, Modifier.size(14.dp))
		}
		Text(
			text = preset.title(),
			style = typography.body.copy(fontSize = 11.5.sp),
			color = content,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		WorkspaceLayoutThumbnail(preset, labels = false, modifier = Modifier.width(34.dp).height(21.dp))
	}
}

/**
 * The dock a new workspace of [preset] opens with, drawn from the same tree the dock builds.
 * Canvases are tinted (edit in the accent, preview in green); [labels] names each panel group.
 */
@Composable
internal fun WorkspaceLayoutThumbnail(
	preset: WorkspacePreset,
	labels: Boolean,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val (layout, workspace) = remember(preset) { presetVisibleLayout(preset) }
	val modes = remember(workspace) { workspace.canvases.associate { it.id to it.mode } }
	val gap = if (labels) 3.dp else 1.dp
	val shape = RoundedCornerShape(if (labels) 4.dp else 2.dp)
	Box(
		modifier
			.clip(shape)
			.background(colors.windowBackground)
			.border(1.dp, colors.border, shape)
			.padding(gap),
	) {
		if (layout == null) {
			EmptyLayoutSketch(labels, Modifier.fillMaxSize())
		} else {
			LayoutSketchNode(layout, modes, labels, gap, Modifier.fillMaxSize())
		}
	}
}

@Composable
private fun LayoutSketchNode(
	node: DockNode,
	modes: Map<String, CanvasMode>,
	labels: Boolean,
	gap: Dp,
	modifier: Modifier,
) {
	val first = node.first
	val second = node.second
	if (first != null && second != null) {
		if (node.horizontal) Row(modifier) {
			LayoutSketchNode(first, modes, labels, gap, Modifier.weight(node.ratio).fillMaxHeight())
			Spacer(Modifier.width(gap))
			LayoutSketchNode(second, modes, labels, gap, Modifier.weight(1f - node.ratio).fillMaxHeight())
		} else Column(modifier) {
			LayoutSketchNode(first, modes, labels, gap, Modifier.weight(node.ratio).fillMaxWidth())
			Spacer(Modifier.height(gap))
			LayoutSketchNode(second, modes, labels, gap, Modifier.weight(1f - node.ratio).fillMaxWidth())
		}
		return
	}
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val lead = node.selected.takeIf { it in node.modules } ?: node.modules.firstOrNull().orEmpty()
	val mode = modes[lead]
	val tint = when (mode) {
		CanvasMode.EDIT -> colors.accent
		CanvasMode.PREVIEW -> colors.success
		null -> colors.textMuted
	}
	val fill = if (mode != null) tint.copy(alpha = if (labels) 0.22f else 0.45f) else colors.controlBackground
	Box(modifier.clip(RoundedCornerShape(if (labels) 3.dp else 1.dp)).background(fill)) {
		if (!labels) return@Box
		Column(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp)) {
			// The tint and glyph already mark a canvas; its label only needs the mode.
			val title = if (mode != null) tr(if (mode == CanvasMode.EDIT) "tab.edit" else "tab.preview") else moduleTitle(lead)
			val extra = node.modules.size - 1
			Text(
				text = if (extra > 0) "$title +$extra" else title,
				style = typography.caption.copy(fontSize = 8.5.sp, lineHeight = 10.sp),
				color = if (mode != null) colors.textPrimary else colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (mode != null) {
				Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					if (mode == CanvasMode.EDIT) IconPencil(tint, Modifier.size(14.dp))
					else IconEye(visible = true, modifier = Modifier.size(16.dp), tint = tint)
				}
			}
		}
	}
}

@Composable
private fun EmptyLayoutSketch(labels: Boolean, modifier: Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Box(modifier, contentAlignment = Alignment.Center) {
		Canvas(Modifier.fillMaxSize()) {
			val inset = if (labels) 4.dp.toPx() else 1.dp.toPx()
			drawRoundRect(
				color = colors.textMuted.copy(alpha = 0.6f),
				topLeft = Offset(inset, inset),
				size = Size(size.width - inset * 2, size.height - inset * 2),
				cornerRadius = androidx.compose.ui.geometry.CornerRadius(if (labels) 3.dp.toPx() else 1f),
				style = Stroke(
					width = 1f,
					pathEffect = PathEffect.dashPathEffect(if (labels) floatArrayOf(5f, 4f) else floatArrayOf(2f, 2f)),
				),
			)
		}
		if (labels) {
			Text(
				text = tr("workspace.preset.blank.hint"),
				style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
			)
		}
	}
}

/** One glyph per preset, shared by the "+" menu and the workspace chips. */
@Composable
internal fun WorkspacePresetIcon(preset: WorkspacePreset, tint: Color, modifier: Modifier = Modifier.size(14.dp)) {
	when (preset) {
		WorkspacePreset.EDIT -> IconPencil(tint, modifier)
		WorkspacePreset.PREVIEW -> IconEye(visible = true, modifier = modifier, tint = tint)
		WorkspacePreset.PHYSICS -> IconPhysics(active = false, modifier = modifier, tint = tint)
		else -> Canvas(modifier) {
			val w = size.width
			val h = size.height
			val line = 1.3f
			val stroke = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round)
			when (preset) {
				// A triangulated patch.
				WorkspacePreset.MESH -> {
					val a = Offset(w * 0.15f, h * 0.82f)
					val b = Offset(w * 0.5f, h * 0.15f)
					val c = Offset(w * 0.85f, h * 0.82f)
					val m = Offset(w * 0.5f, h * 0.60f)
					drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }, tint, style = stroke)
					listOf(a, b, c).forEach { drawLine(tint.copy(alpha = 0.7f), it, m, strokeWidth = 1f) }
					drawCircle(tint, radius = w * 0.07f, center = m)
				}
				// Two parameter sliders.
				WorkspacePreset.RIG -> {
					listOf(0.32f to 0.32f, 0.70f to 0.64f).forEach { (y, knob) ->
						drawLine(tint.copy(alpha = 0.7f), Offset(w * 0.12f, h * y), Offset(w * 0.88f, h * y), strokeWidth = line, cap = StrokeCap.Round)
						drawCircle(tint, radius = w * 0.11f, center = Offset(w * knob, h * y))
					}
				}
				// A timeline with keyframes.
				WorkspacePreset.ANIMATION -> {
					drawLine(tint.copy(alpha = 0.7f), Offset(w * 0.08f, h * 0.62f), Offset(w * 0.92f, h * 0.62f), strokeWidth = line, cap = StrokeCap.Round)
					listOf(0.24f, 0.52f, 0.78f).forEach { x ->
						val r = w * 0.11f
						drawPath(Path().apply {
							moveTo(w * x, h * 0.62f - r); lineTo(w * x + r, h * 0.62f)
							lineTo(w * x, h * 0.62f + r); lineTo(w * x - r, h * 0.62f); close()
						}, tint)
					}
					drawLine(tint, Offset(w * 0.40f, h * 0.18f), Offset(w * 0.40f, h * 0.86f), strokeWidth = 1f)
				}
				// A trunk with one branch forking off, like the history tree.
				WorkspacePreset.HISTORY -> {
					val root = Offset(w * 0.30f, h * 0.84f)
					val fork = Offset(w * 0.30f, h * 0.50f)
					val head = Offset(w * 0.30f, h * 0.16f)
					val branch = Offset(w * 0.74f, h * 0.24f)
					drawLine(tint.copy(alpha = 0.7f), root, head, strokeWidth = line, cap = StrokeCap.Round)
					drawPath(Path().apply {
						moveTo(fork.x, fork.y)
						quadraticTo(branch.x, fork.y, branch.x, branch.y)
					}, tint.copy(alpha = 0.7f), style = stroke)
					listOf(root, fork, head, branch).forEach { drawCircle(tint, radius = w * 0.1f, center = it) }
				}
				// An empty dashed frame with a plus.
				else -> {
					drawRoundRect(
						tint, topLeft = Offset(w * 0.12f, h * 0.12f), size = Size(w * 0.76f, h * 0.76f),
						cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f),
						style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.2f, 1.8f))),
					)
					drawLine(tint, Offset(w * 0.5f, h * 0.34f), Offset(w * 0.5f, h * 0.66f), strokeWidth = line, cap = StrokeCap.Round)
					drawLine(tint, Offset(w * 0.34f, h * 0.5f), Offset(w * 0.66f, h * 0.5f), strokeWidth = line, cap = StrokeCap.Round)
				}
			}
		}
	}
}

@Composable
private fun IconPencil(tint: Color, modifier: Modifier) {
	Canvas(modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(width = 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		drawPath(Path().apply {
			moveTo(w * 0.18f, h * 0.82f)
			lineTo(w * 0.22f, h * 0.62f)
			lineTo(w * 0.68f, h * 0.16f)
			lineTo(w * 0.84f, h * 0.32f)
			lineTo(w * 0.38f, h * 0.78f)
			close()
		}, tint, style = stroke)
		drawLine(tint, Offset(w * 0.58f, h * 0.26f), Offset(w * 0.74f, h * 0.42f), strokeWidth = 1.1f)
	}
}
