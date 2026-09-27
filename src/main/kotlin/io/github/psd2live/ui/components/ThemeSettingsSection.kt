package io.github.psd2live.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.ColorToken
import io.github.psd2live.ui.theme.ColorTokenGroup
import io.github.psd2live.ui.theme.CustomTheme
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.ThemeCatalog
import io.github.psd2live.ui.theme.ThemeCodec
import io.github.psd2live.ui.theme.ToolColors
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

private const val THEME_CARD_COLUMNS = 4

/**
 * The theme category of the settings window: every theme as a clickable preview card, then the
 * editor for the selected one. Built-in themes are read-only; editing starts from a copy, which
 * stores only the colours the user changed on top of its built-in base.
 */
@Composable
internal fun SettingsThemeSection(
	themeId: String,
	customThemes: List<CustomTheme>,
	onSelect: (String) -> Unit,
	onDuplicate: (String) -> Unit,
	onChange: (CustomTheme) -> Unit,
	onDelete: (String) -> Unit,
	onImport: (String) -> Boolean,
) {
	val colors = LocalToolColors.current
	var importFailed by remember { mutableStateOf(false) }

	SettingsSectionDescription(tr("dialog.settings.theme.desc"))

	ThemeSectionTitle(tr("theme.builtIn"))
	ThemeCardGrid(
		cards = ThemeCatalog.builtIns.map { Triple(it.id, tr(it.nameKey), it.colors) },
		selectedId = themeId,
		onSelect = onSelect,
	)

	ThemeSectionTitle(tr("theme.custom")) {
		CompactButton(
			text = tr("theme.custom.import"),
			onClick = { importFailed = !onImport(readClipboardText().orEmpty()) },
			height = 20.dp,
		)
		CompactButton(
			text = tr("theme.custom.newFromCurrent"),
			onClick = { onDuplicate(themeId) },
			height = 20.dp,
		)
	}
	if (importFailed) {
		Text(tr("theme.custom.importFailed"), style = LocalToolTypography.current.caption, color = colors.error)
	}
	if (customThemes.isEmpty()) {
		SettingsSectionDescription(tr("theme.custom.empty"))
	} else {
		ThemeCardGrid(
			cards = customThemes.map { Triple(it.id, it.name, it.resolve()) },
			selectedId = themeId,
			onSelect = onSelect,
		)
	}

	Divider(color = colors.divider, thickness = 1.dp)

	val custom = customThemes.firstOrNull { it.id == themeId }
	if (custom == null) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(Modifier.weight(1f)) { SettingsSectionDescription(tr("theme.builtIn.readOnly")) }
			CompactButton(
				text = tr("theme.customize"),
				onClick = { onDuplicate(themeId) },
				isPrimary = true,
				height = 24.dp,
			)
		}
	} else {
		CustomThemeEditor(
			theme = custom,
			onChange = onChange,
			onDuplicate = { onDuplicate(custom.id) },
			onDelete = { onDelete(custom.id) },
		)
	}
}

@Composable
private fun ThemeSectionTitle(text: String, actions: @Composable () -> Unit = {}) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = text,
			style = typography.header.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
			color = colors.textPrimary,
			modifier = Modifier.weight(1f),
		)
		actions()
	}
}

@Composable
private fun ThemeCardGrid(
	cards: List<Triple<String, String, ToolColors>>,
	selectedId: String,
	onSelect: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		for (row in cards.chunked(THEME_CARD_COLUMNS)) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				for ((id, name, palette) in row) {
					ThemeCard(
						name = name,
						palette = palette,
						selected = id == selectedId,
						onClick = { onSelect(id) },
						modifier = Modifier.weight(1f),
					)
				}
				// Keep a short last row's cards the same width as the full rows above.
				repeat(THEME_CARD_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
			}
		}
	}
}

/**
 * A miniature of the window drawn in the card's own [palette], not the active one, so every theme
 * can be judged side by side: window, a panel with a title and muted line, a selected row, a
 * primary button and the highlight dot.
 */
@Composable
private fun ThemeCard(
	name: String,
	palette: ToolColors,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val isHovered by interactionSource.collectIsHoveredAsState()
	val shape = RoundedCornerShape(4.dp)

	Column(
		modifier = modifier
			.hoverable(interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(58.dp)
				.clip(shape)
				.background(palette.windowBackground)
				.border(
					BorderStroke(
						if (selected) 2.dp else 1.dp,
						when {
							selected -> colors.accent
							isHovered -> colors.borderHover
							else -> colors.border
						},
					),
					shape,
				)
				.padding(start = 6.dp, top = 6.dp),
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.fillMaxHeight()
					.background(palette.panelBackground, RoundedCornerShape(topStart = 3.dp))
					.border(BorderStroke(1.dp, palette.border), RoundedCornerShape(topStart = 3.dp))
					.padding(6.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					Box(Modifier.width(26.dp).height(4.dp).background(palette.textPrimary, CircleShape))
					Box(Modifier.size(5.dp).background(palette.highlight, CircleShape))
				}
				Box(
					Modifier.fillMaxWidth().height(9.dp).background(palette.selection, RoundedCornerShape(1.dp)),
					contentAlignment = Alignment.CenterStart,
				) {
					Box(Modifier.padding(start = 3.dp).width(20.dp).height(3.dp).background(palette.selectionText, CircleShape))
				}
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					Box(Modifier.width(18.dp).height(3.dp).background(palette.textMuted, CircleShape))
					Box(Modifier.width(16.dp).height(8.dp).background(palette.accent, RoundedCornerShape(1.dp)))
				}
			}
		}
		Text(
			text = name,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
			color = if (selected) colors.textPrimary else colors.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun CustomThemeEditor(
	theme: CustomTheme,
	onChange: (CustomTheme) -> Unit,
	onDuplicate: () -> Unit,
	onDelete: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val resolved = theme.resolve()
	var name by remember(theme.id) { mutableStateOf(theme.name) }
	val commitName = {
		val trimmed = name.trim()
		if (trimmed.isEmpty()) name = theme.name else if (trimmed != theme.name) onChange(theme.copy(name = trimmed))
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		CompactTextField(
			value = name,
			onValueChange = { name = it },
			onCommit = commitName,
			onFocusLost = commitName,
			placeholder = tr("theme.custom.name"),
			modifier = Modifier.weight(1f),
		)
		CompactButton(text = tr("theme.custom.duplicate"), onClick = onDuplicate, height = 24.dp)
		CompactButton(
			text = tr("theme.custom.copyCode"),
			onClick = { writeClipboardText(ThemeCodec.encode(theme)) },
			height = 24.dp,
		)
		CompactButton(text = tr("theme.custom.delete"), onClick = onDelete, danger = true, height = 24.dp)
	}
	SettingsSectionDescription(tr("theme.custom.hint", tr(ThemeCatalog.builtIn(theme.baseId).nameKey)))

	for (group in ColorTokenGroup.entries) {
		val tokens = ColorToken.entries.filter { it.group == group }
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Text(
				text = tr(group.labelKey),
				style = typography.header.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary,
			)
			for (pair in tokens.chunked(2)) {
				Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
					for (token in pair) {
						ThemeTokenRow(
							token = token,
							color = token.read(resolved),
							overridden = token in theme.overrides,
							onColor = { onChange(theme.withColor(token, it)) },
							onReset = { onChange(theme.withoutColor(token)) },
							modifier = Modifier.weight(1f),
						)
					}
					if (pair.size == 1) Spacer(Modifier.weight(1f))
				}
			}
		}
	}
}

/**
 * One colour: its name, a chip opening the picker, the hex value, and a reset shown once it
 * differs from the base. The picker is opaque, so a translucent token (scrim, patch wash) keeps its
 * alpha when recoloured; typing `#AARRGGBB` sets the alpha as well.
 */
@Composable
private fun ThemeTokenRow(
	token: ColorToken,
	color: Color,
	overridden: Boolean,
	onColor: (Color) -> Unit,
	onReset: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var hex by remember(color) { mutableStateOf(ThemeCodec.formatColor(color)) }
	val commitHex = {
		val parsed = ThemeCodec.parseColor(hex)
		if (parsed == null) hex = ThemeCodec.formatColor(color) else if (parsed != color) onColor(parsed)
	}

	Row(
		modifier = modifier.height(24.dp),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = tr(token.labelKey),
			style = typography.body.copy(fontSize = 11.5.sp, fontWeight = if (overridden) FontWeight.Medium else FontWeight.Normal),
			color = if (overridden) colors.textPrimary else colors.textMuted,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		PaintColorChip(
			color = color.copy(alpha = 1f),
			onColorChanged = { onColor(it.copy(alpha = color.alpha)) },
			modifier = Modifier.size(18.dp),
			popupOffset = 22.dp,
		)
		CompactTextField(
			value = hex,
			onValueChange = { hex = it },
			onCommit = commitHex,
			onFocusLost = commitHex,
			isMono = true,
			height = 22.dp,
			modifier = Modifier.width(86.dp),
		)
		Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
			if (overridden) {
				CompactIconButton(onClick = onReset, tooltip = tr("theme.token.reset"), size = 20.dp) {
					IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
				}
			}
		}
	}
}

private fun readClipboardText(): String? = runCatching {
	Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
}.getOrNull()

private fun writeClipboardText(text: String) {
	runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
}
