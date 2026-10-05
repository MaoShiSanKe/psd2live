package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

class WorkspacePreviewBuilderTest {
    @Test fun legacyPresentationDrawOrdersReplayWithoutRewritingHistoricalDocuments() = runBlocking {
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster,
            true, 0, LayerBounds(0, 0, 8, 8), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(8, 8, ByteArray(8 * 8 * 4) { if (it % 4 == 3) 255.toByte() else 80 }), null, null, false)
        val source = WorkspaceSourceArt(16, 16, listOf(layer), emptyList())
        val config = PipelineConfig(atlasSize = 256, exportMotions = false)
        val savedSettings = WorkspaceSettingsCodec.encode(config)
        val legacy = WorkspaceDocument(source, emptyMap(), emptySet(), mapOf("art" to
            LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS)), emptyMap(),
            RigEditOverlay.Empty, JsonObject(savedSettings - "drawOrderOverrides"))
        val revision = WorkspaceRevisions.of(legacy)
        val builder = WorkspacePreviewBuilder()
        val restored = builder.build(legacy, legacyDrawOrders = mapOf("art" to 123f))
        assertEquals(123f, restored.rig.puppet.drawables.single().drawOrder)
        val replayed = builder.build(legacy, restored)
        assertEquals(123f, replayed.rig.puppet.drawables.single().drawOrder)
        assertEquals(revision, WorkspaceRevisions.of(legacy))
        assertFalse("drawOrderOverrides" in legacy.settings)
        val explicit = legacy.copy(settings = JsonObject(savedSettings + ("drawOrderOverrides" to buildJsonObject { put("art", 456) })))
        val updated = builder.build(explicit, restored)
        assertEquals(456f, updated.rig.puppet.drawables.single().drawOrder)
    }
}
