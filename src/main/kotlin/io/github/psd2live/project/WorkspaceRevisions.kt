package io.github.psd2live.project

import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

/** v1 revision identity, shared by GUI and application commands. Raster buffers are immutable. */
internal object WorkspaceRevisions {
    private val rasterDigests = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())
	fun of(document: WorkspaceDocument): String {
		val canonical = buildString {
			append("|settings:").append(document.settings)
            append("|rig:").append(document.rigEdits)
			document.source.groups.forEach { group ->
                append("|group:").append(group.path).append(':').append(group.name).append(':').append(group.visible)
                append(':').append(group.opacity).append(':').append(group.clipped).append(':').append(group.blend).append(':').append(group.passThrough)
            }
            append("|canvas:").append(document.source.widthPx).append('x').append(document.source.heightPx)
			document.source.layers.forEachIndexed { painterIndex, layer ->
				append("|layer:").append(painterIndex).append(':').append(layer.id.raw)
				append(':').append(layer.name).append(':').append(layer.groupPath).append(':').append(layer.kind)
				append(':').append(layer.visible).append(':').append(layer.order).append(':').append(layer.bounds)
				append(':').append(layer.opacity).append(':').append(layer.clipped).append(':').append(layer.blend).append(':').append(layer.channelMask)
				append(':').append(layer.raster.width).append('x').append(layer.raster.height)
				append(':').append(rasterDigest(layer.raster.rgba))
			}
			document.layerVisibility.toSortedMap().forEach { (key, value) -> append("|v:").append(key).append('=').append(value) }
			document.deletedLayerIds.sorted().forEach { append("|d:").append(it) }
			document.layerOverrides.toSortedMap().forEach { (key, value) -> append("|o:").append(key).append('=').append(value) }
			document.parentOverrides.toSortedMap().forEach { (key, value) -> append("|p:").append(key).append('=').append(value) }
			document.meshOverrides.toSortedMap().forEach { (key, value) -> append("|m:").append(key).append('=').append(value) }
			document.rigEdits.deletedParameterIds.sorted().forEach { append("|pd:").append(it) }
			document.rigEdits.parameterEdits.forEach { edit -> append("|pe:").append(edit) }
			// Omit the field entirely for v1 documents without a separate generation input.
			listOf("generation" to document.generationSource, "meshSource" to document.meshSource, "placementSource" to document.placementSource).forEach { (kind, source) ->
				if (source == null) return@forEach
				append("|$kind:").append(source.widthPx).append('x').append(source.heightPx)
				source.groups.forEach { group ->
					append("|group:").append(group.path).append(':').append(group.name).append(':').append(group.visible)
					append(':').append(group.opacity).append(':').append(group.clipped).append(':').append(group.blend).append(':').append(group.passThrough)
				}
				source.layers.forEachIndexed { index, layer ->
					append("|layer:").append(index).append(':').append(layer.id.raw)
					append(':').append(layer.name).append(':').append(layer.groupPath).append(':').append(layer.kind)
					append(':').append(layer.visible).append(':').append(layer.order).append(':').append(layer.bounds)
					append(':').append(layer.opacity).append(':').append(layer.clipped).append(':').append(layer.blend).append(':').append(layer.channelMask)
					append(':').append(layer.raster.width).append('x').append(layer.raster.height)
					append(':').append(rasterDigest(layer.raster.rgba))
				}
			}
		}
		return "revision-${sha256(canonical)}"
	}

    private fun rasterDigest(rgba: ByteArray): String = rasterDigests[rgba] ?: sha256(rgba).also { rasterDigests[rgba] = it }
    private fun sha256(value: String): String = sha256(value.encodeToByteArray())
    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
