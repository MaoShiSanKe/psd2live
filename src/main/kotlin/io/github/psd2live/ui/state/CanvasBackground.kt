package io.github.psd2live.ui.state

import androidx.compose.runtime.Immutable

enum class CanvasBackgroundKind(val id: String) {
	CHECKER("checker"),
	SOLID("solid"),
	/** The window itself goes see-through behind the canvas, showing the desktop. */
	TRANSPARENT("transparent");

	companion object {
		fun fromId(id: String?): CanvasBackgroundKind = entries.firstOrNull { it.id == id } ?: CHECKER
	}
}

/**
 * What every canvas paints behind the artwork. An application preference like the theme, not
 * project data. Colours are 0xRRGGBB; null follows the theme's checker colours.
 */
@Immutable
data class CanvasBackground(
	val kind: CanvasBackgroundKind = CanvasBackgroundKind.CHECKER,
	val solidColor: Int? = null,
	val checkerLight: Int? = null,
	val checkerDark: Int? = null,
	val checkerSize: Int = DEFAULT_CHECKER_SIZE,
) {
	/** Window transparency is fixed when a window is created, so this is what the windows key on. */
	val windowTransparent: Boolean get() = kind == CanvasBackgroundKind.TRANSPARENT

	companion object {
		const val DEFAULT_CHECKER_SIZE = 14
		val CHECKER_SIZES = listOf(8, 14, 24, 40)
	}
}
