package io.github.psd2live.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

@Immutable
data class ToolColors(
	val isDark: Boolean = true,
	val windowBackground: Color = Color(0xFF1E1F22),
	val panelBackground: Color = Color(0xFF2B2D30),
	val panelElevated: Color = Color(0xFF323438),
	val inputBackground: Color = Color(0xFF1E1F22),
	val controlBackground: Color = Color(0xFF393B40),
	val controlHover: Color = Color(0xFF45484F),
	val controlActive: Color = Color(0xFF4E5158),
	val border: Color = Color(0xFF3E4147),
	val borderHover: Color = Color(0xFF5E626B),
	val divider: Color = Color(0xFF36383D),
	val accent: Color = Color(0xFF4B7EE8),
	/** The wash over a provisional patch (a topology op's freshly created faces). Low alpha on purpose. */
	val patchFill: Color = Color(0x244B7EE8),
	val accentHover: Color = Color(0xFF5C8FF0),
	val accentText: Color = Color(0xFFFFFFFF),
	val selection: Color = Color(0xFF2E436E),
	val selectionText: Color = Color(0xFFA8C7FA),
	val textPrimary: Color = Color(0xFFDFE1E5),
	val textMuted: Color = Color(0xFF868A91),
	val textDisabled: Color = Color(0xFF5A5D63),
	val success: Color = Color(0xFF57A64A),
	val warning: Color = Color(0xFFD69D36),
	val error: Color = Color(0xFFE05252),
	val checkerLight: Color = Color(0xFF3A3D42),
	val checkerDark: Color = Color(0xFF303236),
	/** The dim laid over the workspace behind a modal dialog. */
	val scrim: Color = Color(0x99000000),
	/** A second highlight beside [accent] for live or current things: a connected agent, the history head. */
	val highlight: Color = Color(0xFF4EC9B0),
	/** The badge fill under [highlight] text. */
	val highlightContainer: Color = Color(0xFF1B4D3E),
	/** The badge fill under [error] text. */
	val errorContainer: Color = Color(0xFF4D1B1B),
	val codeBackground: Color = Color(0xFF141416),
	val codeKeyword: Color = Color(0xFFDCDCAA),
	val codeString: Color = Color(0xFFCE9178),
	val codeIdentifier: Color = Color(0xFF9CDCFE),
	/** Source badges in the log and history: who made an entry. */
	val tagSystem: Color = Color(0xFF2E3440),
	val tagSystemText: Color = Color(0xFF88C0D0),
	val tagMcp: Color = Color(0xFF1E3A3A),
	val tagMcpText: Color = Color(0xFF4EC9B0),
	val tagAgent: Color = Color(0xFF3B2E58),
	val tagAgentText: Color = Color(0xFFDCDCAA),
	val tagUser: Color = Color(0xFF1E3A5F),
	val tagUserText: Color = Color(0xFF9CDCFE),
) {
	companion object {
		val Dark = ToolColors()
		val Light = ToolColors(
			isDark = false,
			windowBackground = Color(0xFFE8EAED),
			panelBackground = Color(0xFFF7F8FA),
			panelElevated = Color(0xFFFFFFFF),
			inputBackground = Color(0xFFFFFFFF),
			controlBackground = Color(0xFFE6E8EC),
			controlHover = Color(0xFFDDE0E5),
			controlActive = Color(0xFFD0D4DA),
			border = Color(0xFFD3D6DB),
			borderHover = Color(0xFFB6BBC4),
			divider = Color(0xFFE2E4E8),
			accent = Color(0xFF3574F0),
			patchFill = Color(0x243574F0),
			accentHover = Color(0xFF4B86F5),
			accentText = Color(0xFFFFFFFF),
			selection = Color(0xFFD4E2FF),
			selectionText = Color(0xFF174EA6),
			textPrimary = Color(0xFF1F2328),
			textMuted = Color(0xFF6E7380),
			textDisabled = Color(0xFFA8ADB8),
			success = Color(0xFF3F8D36),
			warning = Color(0xFFB97810),
			error = Color(0xFFC42B2B),
			checkerLight = Color(0xFFECEEF1),
			checkerDark = Color(0xFFDEE1E6),
			scrim = Color(0x80000000),
			highlight = Color(0xFF1F7A66),
			highlightContainer = Color(0xFFE3F4EF),
			errorContainer = Color(0xFFFBE7E7),
			codeBackground = Color(0xFFEEF0F3),
			codeKeyword = Color(0xFF795E26),
			codeString = Color(0xFFA31515),
			codeIdentifier = Color(0xFF0451A5),
			tagSystem = Color(0xFFE8EEF4),
			tagSystemText = Color(0xFF3A6B8C),
			tagMcp = Color(0xFFE3F4EF),
			tagMcpText = Color(0xFF1F7A66),
			tagAgent = Color(0xFFF1ECF8),
			tagAgentText = Color(0xFF6B4FA0),
			tagUser = Color(0xFFE6F0FA),
			tagUserText = Color(0xFF1A5FA8),
		)

	}
}

@Immutable
data class ToolTypography(
	val title: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.SemiBold,
		fontSize = 13.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val header: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Medium,
		fontSize = 12.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val body: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Normal,
		fontSize = 12.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val caption: TextStyle = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Normal,
		fontSize = 11.5.sp,
		color = Color(0xFF868A91),
	),
	val mono: TextStyle = TextStyle(
		fontFamily = FontFamily.Monospace,
		fontWeight = FontWeight.Normal,
		fontSize = 11.5.sp,
		color = Color(0xFFDFE1E5),
	),
	val monoSmall: TextStyle = TextStyle(
		fontFamily = FontFamily.Monospace,
		fontWeight = FontWeight.Normal,
		fontSize = 10.5.sp,
		color = Color(0xFF868A91),
	),
)

fun toolTypography(colors: ToolColors): ToolTypography = ToolTypography(
	title = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.SemiBold,
		fontSize = 13.5.sp,
		color = colors.textPrimary,
	),
	header = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Medium,
		fontSize = 12.5.sp,
		color = colors.textPrimary,
	),
	body = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Normal,
		fontSize = 12.5.sp,
		color = colors.textPrimary,
	),
	caption = TextStyle(
		fontFamily = FontFamily.SansSerif,
		fontWeight = FontWeight.Normal,
		fontSize = 11.5.sp,
		color = colors.textMuted,
	),
	mono = TextStyle(
		fontFamily = FontFamily.Monospace,
		fontWeight = FontWeight.Normal,
		fontSize = 11.5.sp,
		color = colors.textPrimary,
	),
	monoSmall = TextStyle(
		fontFamily = FontFamily.Monospace,
		fontWeight = FontWeight.Normal,
		fontSize = 10.5.sp,
		color = colors.textMuted,
	),
)

val LocalToolColors = staticCompositionLocalOf { ToolColors() }
val LocalToolTypography = staticCompositionLocalOf { ToolTypography() }

@Composable
fun CompactToolTheme(
	colors: ToolColors = ToolColors.Dark,
	typography: ToolTypography = toolTypography(colors),
	uiScale: Float = 1.0f,
	fontScale: Float = 1.0f,
	content: @Composable () -> Unit,
) {
	val currentDensity = LocalDensity.current
	val effectiveDensity = remember(currentDensity, uiScale, fontScale) {
		Density(
			density = currentDensity.density * uiScale,
			fontScale = currentDensity.fontScale * fontScale,
		)
	}
	CompositionLocalProvider(
		LocalDensity provides effectiveDensity,
		LocalToolColors provides colors,
		LocalToolTypography provides typography,
		content = content,
	)
}
