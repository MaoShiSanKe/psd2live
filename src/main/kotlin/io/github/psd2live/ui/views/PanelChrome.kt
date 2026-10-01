package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconCollapseAll
import io.github.psd2live.ui.components.IconExpandAll
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.IconSearch
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor

/*
 * Panel toolbars. Every dock panel opens with one 28dp strip: 22dp controls 3dp apart on the elevated
 * panel color, a divider under it. Left to right:
 *   search - create and other primary actions - | filters and switches - (spacer) -
 *   show preview, expand all, collapse all, reset (always last).
 * Actions are tinted textPrimary, view and navigation buttons textMuted, anything switched on accent.
 * Commit buttons (cancel, done, apply) stay in the panel's footer.
 */

/** Height of a toolbar row's controls. */
internal val PanelToolHeight = 22.dp

internal val PanelToolLabelGap = 8.dp

/** Below this toolbar width the search field folds into an icon. */
private val PanelSearchDockWidth = 240.dp

/** A docked search field takes this share of the toolbar, within [PanelSearchFieldWidths]. */
private const val PanelSearchFieldShare = 0.4f
private val PanelSearchFieldWidths = 120.dp..220.dp

/** The search a [PanelToolbar] puts first. */
internal class PanelSearch(
	val query: String,
	val onQueryChange: (String) -> Unit,
	val placeholder: String,
)

/**
 * A panel's toolbar. [content] is told how many of [labels] fit beside its [iconCount] controls (the
 * search not counted) and [reservedWidth] of other content, for [PanelToolButton]'s showLabel.
 * [secondary] rows sit under it on the same background.
 *
 * A wide panel keeps the search field open; a narrow one shows a search icon, and the field it opens
 * takes the whole row until Escape or its clear button closes it. A query keeps it open.
 */
@Composable
internal fun PanelToolbar(
	modifier: Modifier = Modifier,
	labels: List<String> = emptyList(),
	iconCount: Int = 0,
	search: PanelSearch? = null,
	reservedWidth: Dp = 0.dp,
	secondary: (@Composable ColumnScope.() -> Unit)? = null,
	content: @Composable RowScope.(labelsShown: Int) -> Unit,
) {
	val colors = LocalToolColors.current
	Column(Modifier.fillMaxWidth()) {
		Column(
			modifier = modifier
				.fillMaxWidth()
				.background(colors.panelElevated)
				.padding(horizontal = 4.dp, vertical = 3.dp),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			BoxWithConstraints(Modifier.fillMaxWidth().height(PanelToolHeight)) {
				val docked = search != null && maxWidth >= PanelSearchDockWidth
				var opened by remember { mutableStateOf(false) }
				val expanded = search != null && !docked && (opened || search.query.isNotEmpty())
				val fieldWidth = (maxWidth * PanelSearchFieldShare).coerceIn(PanelSearchFieldWidths.start, PanelSearchFieldWidths.endInclusive)
				val room = maxWidth - reservedWidth
				val labelRoom = when {
					search == null -> room
					docked -> room - fieldWidth - 3.dp
					else -> room - PanelToolHeight - 3.dp
				}
				val labelsShown = shownToolLabels(labels, iconCount, labelRoom)
				Row(
					modifier = Modifier.fillMaxSize(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(3.dp),
				) {
					when {
						search == null -> content(labelsShown)
						docked -> {
							PanelSearchField(
								search = search,
								focusOnShow = false,
								showClose = search.query.isNotEmpty(),
								onClose = { search.onQueryChange("") },
								modifier = Modifier.width(fieldWidth),
							)
							content(labelsShown)
						}
						expanded -> PanelSearchField(
							search = search,
							focusOnShow = opened,
							showClose = true,
							onClose = {
								search.onQueryChange("")
								opened = false
							},
							modifier = Modifier.weight(1f),
						)
						else -> {
							PanelIconButton(onClick = { opened = true }, tooltip = search.placeholder) {
								IconSearch(tint = colors.textMuted)
							}
							content(labelsShown)
						}
					}
				}
			}
			secondary?.invoke(this)
		}
		Divider(color = colors.divider)
	}
}

@Composable
private fun PanelSearchField(
	search: PanelSearch,
	focusOnShow: Boolean,
	showClose: Boolean,
	onClose: () -> Unit,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	val focus = remember { FocusRequester() }
	if (focusOnShow) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
	CompactTextField(
		value = search.query,
		onValueChange = search.onQueryChange,
		placeholder = search.placeholder,
		leadingIcon = { IconSearch(tint = colors.textMuted) },
		trailingIcon = if (!showClose) null else {
			{
				CompactIconButton(onClick = onClose, tooltip = tr("parameters.clearSearch"), size = 16.dp) {
					IconClose(modifier = Modifier.size(8.dp), tint = colors.textMuted)
				}
			}
		},
		modifier = modifier
			.focusRequester(focus)
			.onPreviewKeyEvent { event ->
				if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
					onClose()
					true
				} else false
			},
		height = PanelToolHeight,
	)
}

/** A toolbar's icon-only button. */
@Composable
internal fun PanelIconButton(
	onClick: () -> Unit,
	tooltip: String,
	enabled: Boolean = true,
	icon: @Composable () -> Unit,
) {
	CompactIconButton(onClick = onClick, enabled = enabled, size = PanelToolHeight, tooltip = tooltip, content = icon)
}

/** Expand all and collapse all, the pair before a toolbar's reset. */
@Composable
internal fun PanelExpandCollapseButtons(
	onExpandAll: () -> Unit,
	onCollapseAll: () -> Unit,
	enabled: Boolean = true,
) {
	val colors = LocalToolColors.current
	PanelIconButton(onClick = onExpandAll, enabled = enabled, tooltip = tr("canvas.hierarchy.expandAll")) {
		IconExpandAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
	}
	PanelIconButton(onClick = onCollapseAll, enabled = enabled, tooltip = tr("canvas.hierarchy.collapseAll")) {
		IconCollapseAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
	}
}

/** The reset at a toolbar's right end. */
@Composable
internal fun PanelResetButton(onClick: () -> Unit, tooltip: String, enabled: Boolean = true) {
	PanelIconButton(onClick = onClick, enabled = enabled, tooltip = tooltip) {
		IconReset(modifier = Modifier.size(11.dp), tint = LocalToolColors.current.textPrimary)
	}
}

/** Opens a preview canvas, for panels that act on the previewed model, while none is open. */
@Composable
internal fun PanelShowPreviewButton(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	if (state.previewLive) return
	PanelIconButton(onClick = { viewModel.ensurePreviewCanvas(focus = true) }, tooltip = tr("window.showPreview")) {
		IconEye(visible = true, modifier = Modifier.size(12.dp), tint = LocalToolColors.current.textMuted)
	}
}

/** Muted one-line text in a toolbar: a canvas name, a count, a status. */
@Composable
internal fun PanelToolbarText(
	text: String,
	modifier: Modifier = Modifier,
	color: Color = LocalToolColors.current.textMuted,
	textAlign: TextAlign? = null,
) {
	Text(
		text = text,
		style = LocalToolTypography.current.caption.copy(fontSize = 10.5.sp),
		color = color,
		textAlign = textAlign,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier,
	)
}

/** A toolbar's title, for panels that show one thing: the selection, the tool, the mesh. */
@Composable
internal fun PanelToolbarTitle(text: String, modifier: Modifier = Modifier) {
	Text(
		text = text,
		style = LocalToolTypography.current.body.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
		color = LocalToolColors.current.textPrimary,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier,
	)
}

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

/** Icon button that slides its text label in beside the icon when the toolbar has room. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PanelToolButton(
	label: String,
	showLabel: Boolean,
	onClick: () -> Unit,
	enabled: Boolean,
	tooltip: String,
	active: Boolean = false,
	icon: @Composable () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	val pressed by interaction.collectIsPressedAsState()
	TooltipArea(tooltip = { ParameterTooltip(tooltip) }, delayMillis = 400) {
		Row(
			modifier = Modifier
				.height(PanelToolHeight)
				.widthIn(min = PanelToolHeight)
				.background(
					when {
						!enabled -> Color.Transparent
						pressed -> colors.controlActive
						hovered -> colors.controlHover
						else -> colors.controlBackground
					},
					RoundedCornerShape(2.dp),
				)
				.border(
					BorderStroke(1.dp, if (active) colors.accent else if (hovered && enabled) colors.borderHover else colors.border),
					RoundedCornerShape(2.dp),
				)
				.hoverable(interaction)
				.clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
				.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
				.padding(horizontal = 5.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) { icon() }
			AnimatedVisibility(
				visible = showLabel,
				enter = expandHorizontally(tween(160)) + fadeIn(tween(160)),
				exit = shrinkHorizontally(tween(160)) + fadeOut(tween(120)),
			) {
				Text(
					text = label,
					style = typography.caption.copy(fontSize = 10.5.sp),
					color = when {
						!enabled -> colors.textDisabled
						active -> colors.accent
						else -> colors.textPrimary
					},
					maxLines = 1,
					softWrap = false,
					modifier = Modifier.padding(start = 4.dp),
				)
			}
		}
	}
}

@Composable
internal fun PanelToolbarSeparator() {
	Box(
		Modifier
			.padding(horizontal = 2.dp)
			.width(1.dp)
			.height(14.dp)
			.background(LocalToolColors.current.divider),
	)
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
