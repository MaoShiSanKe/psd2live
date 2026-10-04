package io.github.psd2live.application

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

class WorkspaceSourcePaintingTest {
    private fun document(): WorkspaceDocument {
        val raster = LayerRaster(2, 2, ByteArray(16) { if (it % 4 == 3) 255.toByte() else 40 })
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster, true,
            0, LayerBounds(2, 2, 2, 2), 1f, false, LayerBlend.Normal, ChannelMask.ALL, raster, null, null, true)
        return WorkspaceDocument(WorkspaceSourceArt(8, 8, listOf(layer), emptyList()),
            emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty)
    }

    @Test fun pencilCanExpandACroppedLayerWithoutMutatingTheOriginalRaster() {
        val before = document()
        val pixels = before.source.layers.single().raster.rgba.copyOf()
        val changed = before.paintSource(buildJsonObject {
            put("layer_id", "art"); put("mode", "pencil"); put("radius", 1)
            putJsonArray("points") { add(buildJsonArray { add(6.5); add(6.5) }) }
            putJsonArray("color") { add(10); add(200); add(30); add(255) }
        })
        assertEquals(listOf(10, 200, 30, 255), changed.sampleSourceColor("art", 6, 6))
        assertEquals(listOf(40, 40, 40, 255), changed.sampleSourceColor("art", 2, 2))
        assertEquals(listOf(0, 0, 0, 0), before.sampleSourceColor("art", 6, 6))
        assertContentEquals(pixels, before.source.layers.single().raster.rgba)
        val alpha = changed.source.layers.single().raster.rgba.filterIndexed { index, _ -> index % 4 == 3 }
        assertTrue(alpha.all { it == 0.toByte() || it == 255.toByte() }, "Pencil must not introduce partially covered edge pixels")
    }

    @Test fun eyedropperUsesCanvasPlacementAndRejectsDeletedLayersWithoutEditing() {
        val before = document()
        val revision = WorkspaceRevisions.of(before)
        assertEquals(listOf(40, 40, 40, 255), before.sampleSourceColor("art", 3, 3))
        assertEquals(listOf(0, 0, 0, 0), before.sampleSourceColor("art", 0, 0))
        assertFailsWith<IllegalArgumentException> { before.sampleSourceColor("art", 8, 0) }
        assertFailsWith<IllegalArgumentException> { before.copy(deletedLayerIds = setOf("art")).sampleSourceColor("art", 2, 2) }
        assertEquals(revision, WorkspaceRevisions.of(before))
    }
}
