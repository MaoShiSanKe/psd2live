package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/**
 * How many of a toolbar's [labels] fit beside its [iconCount] 22dp buttons in [maxWidth]: labels appear in
 * order as the panel widens, each only once everything before it fits.
 */
@Composable
internal fun shownToolLabels(labels: List<String>, iconCount: Int, maxWidth: Dp): Int {
	val labelStyle = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp)
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current
	var used = 22.dp * iconCount + 3.dp * (iconCount + 1) + 5.dp + 8.dp
	var shown = 0
	for (label in labels) {
		val width = with(density) { measurer.measure(label, labelStyle).size.width.toDp() } + PanelToolLabelGap
		if (used + width > maxWidth) break
		used += width
		shown++
	}
	return shown
}

/** Collapsible folder-style header row, as the parameters panel draws its folders. */
@Composable
internal fun PanelSectionRow(
	title: String,
	open: Boolean,
	onToggle: () -> Unit,
	modifier: Modifier = Modifier,
	count: Int? = null,
	icon: (@Composable () -> Unit)? = { IconFolder(tint = LocalToolColors.current.accent, modifier = Modifier.size(12.dp)) },
	trailing: (@Composable RowScope.() -> Unit)? = null,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	Row(
		modifier = modifier
			.fillMaxWidth()
			.background(if (hovered) colors.controlHover.copy(alpha = 0.55f) else colors.panelElevated.copy(alpha = 0.55f))
			.hoverable(interaction)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
			.clickable(interactionSource = interaction, indication = null, onClick = onToggle)
			.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconChevron(expanded = open, tint = colors.textMuted, modifier = Modifier.size(10.dp))
		Spacer(Modifier.width(4.dp))
		if (icon != null) {
			icon()
			Spacer(Modifier.width(4.dp))
		}
		Text(
			text = title,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		if (count != null) Text(text = "$count", style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
		trailing?.invoke(this)
	}
}

/** A chevron turned to point up or down, for move-up and move-down buttons. */
@Composable
internal fun IconArrowVertical(up: Boolean, tint: Color, modifier: Modifier = Modifier.size(11.dp)) {
	IconChevron(expanded = true, tint = tint, modifier = if (up) modifier.rotate(180f) else modifier)
}
