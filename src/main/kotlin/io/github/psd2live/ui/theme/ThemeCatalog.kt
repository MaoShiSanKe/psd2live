package io.github.psd2live.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.util.UUID

/** The groups the theme editor lists the tokens under, in display order. */
enum class ColorTokenGroup(val labelKey: String) {
	SURFACE("theme.group.surface"),
	CONTROL("theme.group.control"),
	ACCENT("theme.group.accent"),
	TEXT("theme.group.text"),
	STATUS("theme.group.status"),
	CODE("theme.group.code"),
	TAG("theme.group.tag"),
	CANVAS("theme.group.canvas"),
}

/**
 * Every editable colour of [ToolColors], addressable by a stable [key] so a custom theme can be
 * stored and shared as a list of overrides rather than a full palette.
 *
 * A token with a [cascade] is a seed: overriding it also re-derives the colours that are normally
 * tinted from it (a new accent brings its hover, selection and patch wash along), so a custom theme
 * stays coherent after changing one colour. An explicit override of a derived token still wins.
 */
enum class ColorToken(
	val key: String,
	val group: ColorTokenGroup,
	val read: (ToolColors) -> Color,
	val write: (ToolColors, Color) -> ToolColors,
	val cascade: ((ToolColors, Color) -> ToolColors)? = null,
) {
	WINDOW_BACKGROUND("windowBackground", ColorTokenGroup.SURFACE, { it.windowBackground }, { c, v -> c.copy(windowBackground = v) }),
	PANEL_BACKGROUND("panelBackground", ColorTokenGroup.SURFACE, { it.panelBackground }, { c, v -> c.copy(panelBackground = v) }, ToolColors::withSurfaceFamily),
	PANEL_ELEVATED("panelElevated", ColorTokenGroup.SURFACE, { it.panelElevated }, { c, v -> c.copy(panelElevated = v) }),
	INPUT_BACKGROUND("inputBackground", ColorTokenGroup.SURFACE, { it.inputBackground }, { c, v -> c.copy(inputBackground = v) }),
	SCRIM("scrim", ColorTokenGroup.SURFACE, { it.scrim }, { c, v -> c.copy(scrim = v) }),

	CONTROL_BACKGROUND("controlBackground", ColorTokenGroup.CONTROL, { it.controlBackground }, { c, v -> c.copy(controlBackground = v) }),
	CONTROL_HOVER("controlHover", ColorTokenGroup.CONTROL, { it.controlHover }, { c, v -> c.copy(controlHover = v) }),
	CONTROL_ACTIVE("controlActive", ColorTokenGroup.CONTROL, { it.controlActive }, { c, v -> c.copy(controlActive = v) }),
	BORDER("border", ColorTokenGroup.CONTROL, { it.border }, { c, v -> c.copy(border = v) }),
	BORDER_HOVER("borderHover", ColorTokenGroup.CONTROL, { it.borderHover }, { c, v -> c.copy(borderHover = v) }),
	DIVIDER("divider", ColorTokenGroup.CONTROL, { it.divider }, { c, v -> c.copy(divider = v) }),

	ACCENT("accent", ColorTokenGroup.ACCENT, { it.accent }, { c, v -> c.copy(accent = v) }, ToolColors::withAccentFamily),
	ACCENT_HOVER("accentHover", ColorTokenGroup.ACCENT, { it.accentHover }, { c, v -> c.copy(accentHover = v) }),
	ACCENT_TEXT("accentText", ColorTokenGroup.ACCENT, { it.accentText }, { c, v -> c.copy(accentText = v) }),
	SELECTION("selection", ColorTokenGroup.ACCENT, { it.selection }, { c, v -> c.copy(selection = v) }),
	SELECTION_TEXT("selectionText", ColorTokenGroup.ACCENT, { it.selectionText }, { c, v -> c.copy(selectionText = v) }),
	PATCH_FILL("patchFill", ColorTokenGroup.ACCENT, { it.patchFill }, { c, v -> c.copy(patchFill = v) }),
	HIGHLIGHT("highlight", ColorTokenGroup.ACCENT, { it.highlight }, { c, v -> c.copy(highlight = v) }, ToolColors::withHighlightFamily),
	HIGHLIGHT_CONTAINER("highlightContainer", ColorTokenGroup.ACCENT, { it.highlightContainer }, { c, v -> c.copy(highlightContainer = v) }),

	TEXT_PRIMARY("textPrimary", ColorTokenGroup.TEXT, { it.textPrimary }, { c, v -> c.copy(textPrimary = v) }, ToolColors::withTextFamily),
	TEXT_MUTED("textMuted", ColorTokenGroup.TEXT, { it.textMuted }, { c, v -> c.copy(textMuted = v) }),
	TEXT_DISABLED("textDisabled", ColorTokenGroup.TEXT, { it.textDisabled }, { c, v -> c.copy(textDisabled = v) }),

	SUCCESS("success", ColorTokenGroup.STATUS, { it.success }, { c, v -> c.copy(success = v) }),
	WARNING("warning", ColorTokenGroup.STATUS, { it.warning }, { c, v -> c.copy(warning = v) }),
	ERROR("error", ColorTokenGroup.STATUS, { it.error }, { c, v -> c.copy(error = v) }, ToolColors::withErrorFamily),
	ERROR_CONTAINER("errorContainer", ColorTokenGroup.STATUS, { it.errorContainer }, { c, v -> c.copy(errorContainer = v) }),

	CODE_BACKGROUND("codeBackground", ColorTokenGroup.CODE, { it.codeBackground }, { c, v -> c.copy(codeBackground = v) }),
	CODE_KEYWORD("codeKeyword", ColorTokenGroup.CODE, { it.codeKeyword }, { c, v -> c.copy(codeKeyword = v) }),
	CODE_STRING("codeString", ColorTokenGroup.CODE, { it.codeString }, { c, v -> c.copy(codeString = v) }),
	CODE_IDENTIFIER("codeIdentifier", ColorTokenGroup.CODE, { it.codeIdentifier }, { c, v -> c.copy(codeIdentifier = v) }),

	TAG_SYSTEM("tagSystem", ColorTokenGroup.TAG, { it.tagSystem }, { c, v -> c.copy(tagSystem = v) }),
	TAG_SYSTEM_TEXT("tagSystemText", ColorTokenGroup.TAG, { it.tagSystemText }, { c, v -> c.copy(tagSystemText = v) }),
	TAG_MCP("tagMcp", ColorTokenGroup.TAG, { it.tagMcp }, { c, v -> c.copy(tagMcp = v) }),
	TAG_MCP_TEXT("tagMcpText", ColorTokenGroup.TAG, { it.tagMcpText }, { c, v -> c.copy(tagMcpText = v) }),
	TAG_AGENT("tagAgent", ColorTokenGroup.TAG, { it.tagAgent }, { c, v -> c.copy(tagAgent = v) }),
	TAG_AGENT_TEXT("tagAgentText", ColorTokenGroup.TAG, { it.tagAgentText }, { c, v -> c.copy(tagAgentText = v) }),
	TAG_USER("tagUser", ColorTokenGroup.TAG, { it.tagUser }, { c, v -> c.copy(tagUser = v) }),
	TAG_USER_TEXT("tagUserText", ColorTokenGroup.TAG, { it.tagUserText }, { c, v -> c.copy(tagUserText = v) }),

	CHECKER_LIGHT("checkerLight", ColorTokenGroup.CANVAS, { it.checkerLight }, { c, v -> c.copy(checkerLight = v) }),
	CHECKER_DARK("checkerDark", ColorTokenGroup.CANVAS, { it.checkerDark }, { c, v -> c.copy(checkerDark = v) }),
	;

	val labelKey: String get() = "theme.token.$key"

	companion object {
		fun fromKey(key: String): ColorToken? = entries.firstOrNull { it.key == key }

		/**
		 * The order seeds cascade in. Text goes first because the surface family tints toward it,
		 * and the surface before accent and status because those blend into the panel colour.
		 */
		internal val CASCADE_ORDER = listOf(TEXT_PRIMARY, PANEL_BACKGROUND, ACCENT, HIGHLIGHT, ERROR)
	}
}

private val NearBlack = Color(0xFF111111)

/**
 * Re-derives the panel-toned colours from [panel], tinting toward [ToolColors.textPrimary]. The
 * ratios reproduce the hand-tuned dark and light palettes closely, and [ToolColors.isDark] follows
 * the new panel so a light panel on a dark base still gets light-theme shading.
 */
fun ToolColors.withSurfaceFamily(panel: Color): ToolColors {
	val dark = panel.luminance() < 0.4f
	val ink = textPrimary
	return copy(
		isDark = dark,
		panelBackground = panel,
		panelElevated = if (dark) lerp(panel, ink, 0.04f) else lerp(panel, Color.White, 0.8f),
		inputBackground = if (dark) lerp(panel, Color.Black, 0.3f) else lerp(panel, Color.White, 0.8f),
		controlBackground = lerp(panel, ink, 0.08f),
		controlHover = lerp(panel, ink, 0.13f),
		controlActive = lerp(panel, ink, 0.18f),
		border = lerp(panel, ink, if (dark) 0.1f else 0.15f),
		borderHover = lerp(panel, ink, 0.28f),
		divider = lerp(panel, ink, if (dark) 0.06f else 0.08f),
		checkerLight = if (dark) lerp(panel, ink, 0.08f) else lerp(panel, ink, 0.04f),
		checkerDark = if (dark) lerp(panel, ink, 0.03f) else lerp(panel, ink, 0.1f),
		codeBackground = if (dark) lerp(panel, Color.Black, 0.5f) else lerp(panel, ink, 0.05f),
	)
}

fun ToolColors.withTextFamily(text: Color): ToolColors = copy(
	textPrimary = text,
	textMuted = lerp(text, panelBackground, 0.45f),
	textDisabled = lerp(text, panelBackground, 0.68f),
)

fun ToolColors.withAccentFamily(accent: Color): ToolColors = copy(
	accent = accent,
	accentHover = lerp(accent, Color.White, 0.12f),
	accentText = if (accent.luminance() > 0.5f) NearBlack else Color.White,
	patchFill = accent.copy(alpha = 0.14f),
	selection = lerp(panelBackground, accent, if (isDark) 0.3f else 0.18f),
	selectionText = if (isDark) lerp(accent, Color.White, 0.55f) else lerp(accent, Color.Black, 0.35f),
)

fun ToolColors.withHighlightFamily(highlight: Color): ToolColors = copy(
	highlight = highlight,
	highlightContainer = lerp(panelBackground, highlight, if (isDark) 0.28f else 0.12f),
)

fun ToolColors.withErrorFamily(error: Color): ToolColors = copy(
	error = error,
	errorContainer = lerp(panelBackground, error, if (isDark) 0.25f else 0.1f),
)

/** Applies [overrides] on top of this palette: seeds cascade first, then every override is written as given. */
fun ToolColors.withOverrides(overrides: Map<ColorToken, Color>): ToolColors {
	if (overrides.isEmpty()) return this
	var result = this
	for (seed in ColorToken.CASCADE_ORDER) {
		val value = overrides[seed] ?: continue
		result = seed.cascade!!(result, value)
	}
	for ((token, value) in overrides) result = token.write(result, value)
	return result
}

/**
 * The few colours a whole palette is derived from. Code and tag colours come from the dark or light
 * template, since they read well on any background of the same lightness.
 */
data class ThemeSeeds(
	val isDark: Boolean,
	val window: Color,
	val panel: Color,
	val text: Color,
	val accent: Color,
	val highlight: Color,
	val success: Color,
	val warning: Color,
	val error: Color,
) {
	fun derive(): ToolColors {
		val template = if (isDark) ToolColors.Dark else ToolColors.Light
		return template
			.copy(textPrimary = text)
			.withSurfaceFamily(panel)
			.copy(isDark = isDark, windowBackground = window, success = success, warning = warning)
			.withTextFamily(text)
			.withAccentFamily(accent)
			.withHighlightFamily(highlight)
			.withErrorFamily(error)
	}
}

/** A theme shipped with the app. Its name is localised from [nameKey]. */
@Immutable
data class BuiltInTheme(val id: String, val colors: ToolColors) {
	val nameKey: String get() = "theme.preset.$id"
}

/**
 * A user theme: a built-in [baseId] plus the colours the user changed. Storing only the overrides
 * keeps a custom theme following fixes to its base, and lets "reset" on a token simply drop it.
 */
@Immutable
data class CustomTheme(
	val id: String,
	val name: String,
	val baseId: String,
	val overrides: Map<ColorToken, Color> = emptyMap(),
) {
	fun resolve(): ToolColors = ThemeCatalog.builtIn(baseId).colors.withOverrides(overrides)

	/** Sets [token] to [color], or drops the override when it already equals the base value. */
	fun withColor(token: ColorToken, color: Color): CustomTheme {
		val without = copy(overrides = overrides - token)
		return if (token.read(without.resolve()) == color) without else copy(overrides = overrides + (token to color))
	}

	fun withoutColor(token: ColorToken): CustomTheme = copy(overrides = overrides - token)
}

object ThemeCatalog {
	const val DARK_ID = "dark"
	const val LIGHT_ID = "light"

	val builtIns: List<BuiltInTheme> = listOf(
		BuiltInTheme(DARK_ID, ToolColors.Dark),
		BuiltInTheme(LIGHT_ID, ToolColors.Light),
		BuiltInTheme(
			"midnight",
			ThemeSeeds(
				isDark = true,
				window = Color(0xFF11141D),
				panel = Color(0xFF1A1E2B),
				text = Color(0xFFD5DBEA),
				accent = Color(0xFF7AA2F7),
				highlight = Color(0xFF73DACA),
				success = Color(0xFF9ECE6A),
				warning = Color(0xFFE0AF68),
				error = Color(0xFFF7768E),
			).derive(),
		),
		BuiltInTheme(
			"nord",
			ThemeSeeds(
				isDark = true,
				window = Color(0xFF2E3440),
				panel = Color(0xFF3B4252),
				text = Color(0xFFECEFF4),
				accent = Color(0xFF88C0D0),
				highlight = Color(0xFF8FBCBB),
				success = Color(0xFFA3BE8C),
				warning = Color(0xFFEBCB8B),
				error = Color(0xFFBF616A),
			).derive(),
		),
		BuiltInTheme(
			"forest",
			ThemeSeeds(
				isDark = true,
				window = Color(0xFF171C19),
				panel = Color(0xFF212823),
				text = Color(0xFFDCE4DD),
				accent = Color(0xFF5FB887),
				highlight = Color(0xFFD7B96A),
				success = Color(0xFF7CC47F),
				warning = Color(0xFFDDA24A),
				error = Color(0xFFE06A5E),
			).derive(),
		),
		BuiltInTheme(
			"highContrast",
			ThemeSeeds(
				isDark = true,
				window = Color(0xFF000000),
				panel = Color(0xFF0C0C0C),
				text = Color(0xFFFFFFFF),
				accent = Color(0xFFFFD21F),
				highlight = Color(0xFF3FF0C8),
				success = Color(0xFF5CE65C),
				warning = Color(0xFFFFA630),
				error = Color(0xFFFF5C5C),
			).derive().let {
				// Borders carry the layout here, so they get far more contrast than the ratio gives.
				it.copy(border = Color(0xFF8A8A8A), borderHover = Color(0xFFFFFFFF), divider = Color(0xFF5A5A5A))
			},
		),
		BuiltInTheme(
			"solarizedLight",
			ThemeSeeds(
				isDark = false,
				window = Color(0xFFEEE8D5),
				panel = Color(0xFFFDF6E3),
				text = Color(0xFF073642),
				accent = Color(0xFF268BD2),
				highlight = Color(0xFF2AA198),
				success = Color(0xFF859900),
				warning = Color(0xFFB58900),
				error = Color(0xFFDC322F),
			).derive(),
		),
		BuiltInTheme(
			"sakura",
			ThemeSeeds(
				isDark = false,
				window = Color(0xFFF2E6EA),
				panel = Color(0xFFFCF7F8),
				text = Color(0xFF3A2A30),
				accent = Color(0xFFD6588A),
				highlight = Color(0xFF2E9E8A),
				success = Color(0xFF3F8D36),
				warning = Color(0xFFB97810),
				error = Color(0xFFC42B2B),
			).derive(),
		),
	)

	fun builtIn(id: String): BuiltInTheme = builtIns.firstOrNull { it.id == id } ?: builtIns.first()

	fun isBuiltIn(id: String): Boolean = builtIns.any { it.id == id }

	/** The palette for [id], falling back to the dark theme when a custom theme was deleted elsewhere. */
	fun resolve(id: String, customThemes: List<CustomTheme>): ToolColors =
		customThemes.firstOrNull { it.id == id }?.resolve() ?: builtIn(id).colors

	fun newCustomId(): String = "custom-" + UUID.randomUUID().toString().substring(0, 8)
}

/**
 * Plain-text form of a custom theme, used both for the preference store and for sharing through
 * the clipboard. One `key=value` per line so it survives hand editing; unknown keys and malformed
 * colours are skipped, so a theme written by a newer version still loads what it can.
 */
object ThemeCodec {
	private const val HEADER = "psd2live-theme 1"

	fun encode(theme: CustomTheme): String = buildString {
		appendLine(HEADER)
		appendLine("name=${theme.name.replace('\n', ' ').replace('\r', ' ')}")
		appendLine("base=${theme.baseId}")
		for (token in ColorToken.entries) {
			val color = theme.overrides[token] ?: continue
			appendLine("${token.key}=${formatColor(color)}")
		}
	}

	/** Null when [text] is not a theme at all; [id] is assigned by the caller. */
	fun decode(text: String, id: String): CustomTheme? {
		val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
		if (lines.firstOrNull()?.startsWith("psd2live-theme") != true) return null
		var name = ""
		var base = ThemeCatalog.DARK_ID
		val overrides = linkedMapOf<ColorToken, Color>()
		for (line in lines.drop(1)) {
			val eq = line.indexOf('=')
			if (eq <= 0) continue
			val key = line.substring(0, eq).trim()
			val value = line.substring(eq + 1).trim()
			when (key) {
				"name" -> name = value
				"base" -> base = if (ThemeCatalog.isBuiltIn(value)) value else ThemeCatalog.DARK_ID
				else -> {
					val token = ColorToken.fromKey(key) ?: continue
					overrides[token] = parseColor(value) ?: continue
				}
			}
		}
		return CustomTheme(id = id, name = name, baseId = base, overrides = overrides)
	}

	/** `#RRGGBB` for opaque colours, `#AARRGGBB` otherwise. */
	fun formatColor(color: Color): String {
		val argb = color.toArgb()
		val alpha = (argb ushr 24) and 0xFF
		return if (alpha == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
	}

	fun parseColor(text: String): Color? {
		val hex = text.trim().removePrefix("#")
		if (hex.any { it !in "0123456789abcdefABCDEF" }) return null
		return when (hex.length) {
			6 -> Color(0xFF000000L or hex.toLong(16))
			8 -> Color(hex.toLong(16))
			else -> null
		}
	}
}
