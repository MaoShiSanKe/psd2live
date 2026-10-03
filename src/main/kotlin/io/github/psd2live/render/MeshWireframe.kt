package io.github.psd2live.render

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.ui.ComponentPalette
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.Drawable
import java.util.WeakHashMap

/** One mesh of the wireframe guide: selected meshes draw bright with their points, dimmed ones faint. */
internal class WireItem(val drawable: Drawable, val layerId: String, val selected: Boolean, val dimmed: Boolean)

/** The mesh wireframe guide as GPU line and point batches, in the style the Java2D guide pass drew it. */
internal object MeshWireframe {
	private val HALO = argb(150, 12, 13, 16)
	private val POINT = argb(255, 255, 255, 255)

	/** Each index array's unique edges as vertex pairs, kept while the array is alive. */
	private val edgeCache = WeakHashMap<IntArray, IntArray>()

	fun strokeWidth(item: WireItem): Float = if (item.selected) 2.2f else if (item.dimmed) 0.65f else 1.1f

	fun wireColor(item: WireItem): java.awt.Color {
		val strong = ComponentPalette.strong(item.layerId)
		return when {
			item.selected -> strong.brighter()
			item.dimmed -> java.awt.Color(strong.red, strong.green, strong.blue, 65)
			else -> strong
		}
	}

	/**
	 * [items] in order, each mesh its own halo and wire batch so a later mesh's wire lies over an earlier one's,
	 * and the selected meshes' points on top of every wire.
	 */
	fun overlay(geometry: DeformedGeometry, items: List<WireItem>, showTexture: Boolean): OverlayScene {
		if (items.isEmpty()) return OverlayScene.EMPTY
		val lines = ArrayList<OverlayItem>(items.size * 2)
		val points = ArrayList<OverlayItem>()
		for (item in items) {
			val mesh = item.drawable.mesh ?: continue
			val world = geometry.worldPositions[item.drawable.id] ?: continue
			val edges = uniqueEdges(mesh.indices)
			val vertexCount = world.size / 2
			val segments = FloatArray(edges.size * 2)
			var n = 0
			for (e in edges.indices step 2) {
				val a = edges[e]
				val b = edges[e + 1]
				if (a >= vertexCount || b >= vertexCount) continue
				segments[n++] = world[a * 2]; segments[n++] = world[a * 2 + 1]
				segments[n++] = world[b * 2]; segments[n++] = world[b * 2 + 1]
			}
			val drawn = if (n == segments.size) segments else segments.copyOf(n)
			val width = strokeWidth(item)
			// Opaque artwork needs a dark halo under every wire so the mesh stays readable.
			if (showTexture && !item.dimmed) lines += LineBatch(HALO, width + 1.6f, drawn)
			lines += LineBatch(wireColor(item).rgb, width, drawn)
			if (item.selected) points += PointBatch(POINT, POINT, 2.5f, 0f, world.copyOf(minOf(world.size, mesh.vertexCount * 2)))
		}
		return OverlayScene(lines + points)
	}

	fun uniqueEdges(indices: IntArray): IntArray = edgeCache.getOrPut(indices) {
		val seen = HashSet<Long>(indices.size * 2)
		val out = ArrayList<Int>(indices.size * 2)
		for (t in 0 until indices.size / 3 * 3 step 3) {
			for (k in 0..2) {
				val a = indices[t + k]
				val b = indices[t + (k + 1) % 3]
				val key = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
				if (seen.add(key)) { out += a; out += b }
			}
		}
		out.toIntArray()
	}

	private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
}
