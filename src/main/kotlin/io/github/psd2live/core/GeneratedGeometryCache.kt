package io.github.psd2live.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.format.art.SourceArt
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

/**
 * The most recent rig generated from a saved generation source, before its UVs are moved onto the
 * current texture atlas.
 *
 * Once a project keeps a separate generation input, painting changes only the current pixels, so the
 * geometry rig (meshes, skeleton skinning, deformers) is identical across paint commits and is the
 * dominant rebuild cost. The key holds every generation input exactly; the only relaxation is the
 * point payload of `canvas_geometry` journal entries, which no generation stage reads (it is replayed
 * afterwards onto the generated base). Entry order and ids stay in the key.
 */
internal class GeneratedGeometryCache {
	internal class Entry(val atlas: PackedAtlas, val rig: BuiltRig)

	private data class LayerKey(
		val type: Class<*>, val id: String, val name: String, val groupPath: String, val kind: Any, val visible: Boolean,
		val idIsStable: Boolean, val order: Int, val bounds: Any, val opacity: Float, val clipped: Boolean, val blend: Any,
		val channelMask: Any, val width: Int, val height: Int, val digest: String,
	)

	private data class GroupKey(
		val path: String, val name: String, val visible: Boolean, val opacity: Float, val clipped: Boolean,
		val blend: Any, val passThrough: Boolean,
	)

	private data class Key(
		val width: Int, val height: Int, val groups: List<GroupKey>, val layers: List<LayerKey>,
		val generation: PipelineConfig, val baseline: PipelineConfig,
	)

	private var entry: Pair<Key, Entry>? = null

	@Synchronized
	fun getOrPut(geometry: SourceArt, generationConfig: PipelineConfig, baselineConfig: PipelineConfig, build: () -> Entry): Entry {
		val key = Key(geometry.widthPx, geometry.heightPx,
			geometry.groups.map { GroupKey(it.path, it.name, it.visible, it.opacity, it.clipped, it.blend, it.passThrough) },
			geometry.layers.map { layer ->
				LayerKey(layer.javaClass, layer.id.raw, layer.name, layer.groupPath, layer.kind, layer.visible, layer.idIsStable,
					layer.order, layer.bounds, layer.opacity, layer.clipped, layer.blend, layer.channelMask,
					layer.raster.width, layer.raster.height, digest(layer.raster.rgba))
			},
			withoutGeometryPoints(generationConfig), withoutGeometryPoints(baselineConfig))
		entry?.let { (cached, value) -> if (cached == key) return value }
		return build().also { entry = key to it }
	}

	companion object {
		private val digests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())

		/** Saved generation rasters keep their identity across rebuilds, so each is hashed once. */
		private fun digest(rgba: ByteArray): String = digests.getOrPut(rgba) {
			java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rgba))
		}

		private fun withoutGeometryPoints(config: PipelineConfig): PipelineConfig {
			val journal = config.rigEdits.authoringJournal
			if (journal.none { it.isCanvasGeometry() }) return config
			return config.copy(rigEdits = config.rigEdits.copy(authoringJournal = journal.map { command ->
				if (command.isCanvasGeometry()) JsonObject(command - "points") else command
			}))
		}

		private fun JsonObject.isCanvasGeometry() = this["op"]?.jsonPrimitive?.contentOrNull == "canvas_geometry"
	}
}
