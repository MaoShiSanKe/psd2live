package io.github.psd2live.render

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.RigCanvasSupport
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId

/**
 * One textured mesh of the artwork pass, as both painters draw it.
 *
 * @property page        Atlas page the mesh samples.
 * @property opacity     Final opacity, the drawable's own times the pass alpha and any dimming.
 * @property tintColor   ARGB colour washed over the mesh's triangles after it is drawn, 0 for none.
 * @property tintAlpha   Strength of that wash.
 * @property maskIds     Meshes whose triangles clip this one; empty draws it unclipped.
 */
internal class ArtworkDraw(
	val drawableId: DrawableId,
	val page: Int,
	val opacity: Float,
	val tintColor: Int,
	val tintAlpha: Float,
	val maskIds: List<DrawableId>,
)

/** How strongly a hovered part is washed with its component colour. */
const val HOVER_TINT_STRENGTH = 0.35f

/** What the artwork pass shows, decided once for both the Skia painter and the GPU renderer. */
data class ArtworkOptions(
	val alpha: Float = 1f,
	val visibleLayerIds: Set<String>? = null,
	val drawOrderOverrides: Map<String, Float> = emptyMap(),
	val dimUnselected: Boolean = false,
	val highlightedLayerIds: Set<String>? = null,
	val dimmedAlphaMultiplier: Float = 0.22f,
	val tintLayerIds: Set<String>? = null,
	val tintColor: Int = 0,
	val tintAlpha: Float = HOVER_TINT_STRENGTH,
)

internal object ArtworkDrawList {
	/** The meshes of [model] at [geometry] to draw, back to front. */
	fun build(model: RigPreviewModel, geometry: DeformedGeometry, options: ArtworkOptions): List<ArtworkDraw> {
		val drawables = model.rig.puppet.drawables.filter { it.mesh != null && it.id in geometry.worldPositions }
			.sortedBy { RigCanvasSupport.displayOrder(model, it, geometry, options.drawOrderOverrides) }
		val byId = model.rig.puppet.drawables.associateBy { it.id }
		val visible = options.visibleLayerIds
		val out = ArrayList<ArtworkDraw>(drawables.size)
		for (drawable in drawables) {
			val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
			if (visible != null && layerId != null && layerId !in visible) continue
			if (visible != null && layerId == null && drawable.id.raw !in visible && !drawable.isVisible) continue
			val highlighted = options.highlightedLayerIds == null ||
				(layerId != null && layerId in options.highlightedLayerIds) || drawable.id.raw in options.highlightedLayerIds
			val dim = if (options.dimUnselected && !highlighted) options.dimmedAlphaMultiplier else 1f
			val opacity = ((geometry.opacity[drawable.id] ?: drawable.opacity) * options.alpha * dim).coerceIn(0f, 1f)
			if (opacity <= 0.001f) continue
			val page = model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
			val tinted = options.tintColor != 0 && options.tintLayerIds != null &&
				((layerId != null && layerId in options.tintLayerIds) || drawable.id.raw in options.tintLayerIds)
			// An inverted mask has always drawn unclipped on the editing canvas; the preview shows it exactly.
			val masks = if (drawable.maskedBy.isEmpty() || drawable.invertMask) emptyList()
				else drawable.maskedBy.filter { id ->
					val source = byId[id]
					source != null && source.isVisible && source.mesh != null && id in geometry.worldPositions
				}
			out += ArtworkDraw(drawable.id, page, opacity, if (tinted) options.tintColor else 0, options.tintAlpha, masks)
		}
		return out
	}
}
