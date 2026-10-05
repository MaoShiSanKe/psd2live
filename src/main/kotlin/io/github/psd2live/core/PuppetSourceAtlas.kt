package io.github.psd2live.core

import org.umamo.format.raster.RasterImage
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.AtlasPlacement as UmamoAtlasPlacement

/**
 * Bridges PSD2Live's packed atlas into Umamo's document-side [PuppetAtlas] / [ArtSource] model so
 * [org.umamo.interop.cmo3.Cmo3Conversion.freshCmo3] can write real source layers instead of crop stand-ins.
 */
internal object PuppetSourceAtlas {
	const val SOURCE_ID_RAW = "art-0"

	fun tileIdFor(layerId: String, sourceIdRaw: String = SOURCE_ID_RAW): AtlasTileId = AtlasTileId("$sourceIdRaw/$layerId")

	fun build(
		analysis: PipelineAnalysis,
		atlas: PackedAtlas,
		sourceName: String = "artwork.psd",
		sourceIdRaw: String = SOURCE_ID_RAW,
	): Pair<PuppetAtlas, List<ArtSource>> {
		val sourceId = ArtSourceId(sourceIdRaw)
		val layersById = analysis.layers.associateBy { it.source.id.raw }
		val inventory = ArrayList<ArtSourceLayer>()
		val tiles = ArrayList<AtlasTile>()
		val seen = LinkedHashSet<String>()
		for ((layerId, placement) in atlas.placementByLayerId) {
			if (!seen.add(layerId)) continue
			val layer = layersById[layerId] ?: continue
			val source = layer.source
			inventory += ArtSourceLayer(
				key = source.id.raw,
				name = source.name,
				groupPath = source.groupPath,
				left = source.bounds.left,
				top = source.bounds.top,
				width = source.bounds.width,
				height = source.bounds.height,
				visible = source.visible,
			)
			tiles += AtlasTile(
				id = tileIdFor(layerId, sourceIdRaw),
				name = source.name,
				width = source.raster.width,
				height = source.raster.height,
				placement = UmamoAtlasPlacement(
					pageIndex = placement.page,
					positionX = placement.x.toFloat(),
					positionY = placement.y.toFloat(),
					scaleX = placement.scale.toFloat(),
					scaleY = placement.scale.toFloat(),
					rotationDegrees = 0f,
				),
				source = SourceLayerRef(
					sourceId = sourceId,
					layerKey = source.id.raw,
					stableKey = source.idIsStable,
				),
			)
		}
		val pages = atlas.pages.map { page -> AtlasPage(page.image.width, page.image.height) }
		val puppetAtlas = PuppetAtlas(pages = pages, tiles = tiles, storedUvsAddressPages = true)
		val artSource = ArtSource(
			id = sourceId,
			name = sourceName,
			path = null,
			format = "psd",
			layers = inventory,
		)
		return puppetAtlas to listOf(artSource)
	}

	fun rastersByTile(analysis: PipelineAnalysis, sourceIdRaw: String = SOURCE_ID_RAW): Map<AtlasTileId, RasterImage> =
		analysis.layers.associate { layer ->
			val raster = layer.source.raster
			tileIdFor(layer.source.id.raw, sourceIdRaw) to RasterImage(raster.width, raster.height, raster.rgba)
		}
}
