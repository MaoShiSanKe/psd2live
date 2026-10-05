package io.github.psd2live.application

import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.test.*

class WorkspaceAssetContractsTest {
    @TempDir lateinit var temporary: Path
    private val document: WorkspaceDocument get() {
        val rgba = ByteArray(16 * 16 * 4) { if (it % 4 == 3) 255.toByte() else 50 }
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster, true, 0,
            LayerBounds(4, 4, 16, 16), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(16, 16, rgba), null, null, false)
        return WorkspaceDocument(WorkspaceSourceArt(32, 32, listOf(layer), emptyList()), emptyMap(), emptySet(),
            emptyMap(), emptyMap(), RigEditOverlay.Empty)
    }
    private fun image(matte: Boolean): BufferedImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0..15) for (x in 0..15) image.setRGB(x, y,
            if (x in 3..12 && y in 3..12) 0xffff3366.toInt() else if (matte) 0xff11ccff.toInt() else 0)
    }
    private fun prepare(api: WorkspaceAssetWorkflow): JsonObject = api.prepare(buildJsonObject {
        put("layer_id", "art"); put("piece_id", "decoration"); put("background_color", "#11ccff"); put("source_parent_id", "parent")
        put("target_long_edge", 128)
        putJsonObject("target_anchors") {
            put("root", WorkspacePoint(4.0, 4.0).json()); put("tip", WorkspacePoint(20.0, 20.0).json())
        }
    }).metadata.also { validateOperationSchema(it, WorkspaceAssetResultSchemas.recordForOperation("asset_prepare_reference")!!) }
    private fun inspect(preview: WorkspaceAssetPreview): JsonObject = buildJsonObject {
        put("asset", preview.asset.toJson()); put("transparentPixels", preview.transparentPixels); put("translucentPixels", preview.translucentPixels)
        put("image_order", "processed, original (when retained)")
    }

    @Test fun legacyNativeAlphaAndMatteAssetsValidateActualPersistedDetailsAndRetainOriginalPixels() {
        val store = WorkspaceStore(temporary)
        val assets = WorkspacePngAssetStore()
        val api = WorkspaceAssetWorkflow("project", "revision", document, store, assets)
        val reference = prepare(api).getValue("id").jsonPrimitive.content
        val spatial = store.loadSpatial("project", reference)!!
        for ((index, matte) in listOf(false, false, true).withIndex()) {
            val original = png(image(matte))
            val imported = assets.import(WorkspacePngImportRequest(original, reference, referenceId = if (index == 0) null else reference,
                solidBackground = if (matte) "#11ccff" else null, requireTransparency = true,
                processing = if (matte) buildJsonObject { put("edge_width", 2) } else JsonObject(emptyMap())), spatial)
            validateOperationSchema(imported.toJson(), WorkspaceAssetResultSchemas.recordForOperation("asset_import_png")!!)
            if (index == 0) assertTrue(imported.details.isEmpty())
            else if (!matte) assertEquals("native_alpha", imported.details.getValue("diagnostics").jsonObject.getValue("mode").jsonPrimitive.content)
            else assertTrue("removed_pixels" in imported.details.getValue("diagnostics").jsonObject)
            store.persistAsset("project", assets.require(imported.id))
            val reloaded = WorkspaceStore(temporary).loadAsset("project", imported.id)!!
            assertContentEquals(original, reloaded.originalPng)
            assertEquals(imported.toJson(), reloaded.public.toJson())
            validateOperationSchema(inspect(api.inspect(imported.id)), WorkspaceAssetResultSchemas.recordForOperation("asset_inspect")!!)
            assertEquals(156, reloaded.preview().transparentPixels)
        }
    }

    @Test fun registrationCompositeAndReprocessingUseStoredOriginalsAndExactCanvasRecords() {
        val store = WorkspaceStore(temporary)
        val assets = WorkspacePngAssetStore()
        val api = WorkspaceAssetWorkflow("project", "revision", document, store, assets)
        val reference = prepare(api).getValue("id").jsonPrimitive.content
        val imported = assets.import(WorkspacePngImportRequest(png(image(true)), reference, referenceId = reference,
            solidBackground = "#11ccff", requireTransparency = true), store.loadSpatial("project", reference)!!)
        store.persistAsset("project", assets.require(imported.id))
        val before = imported.toJson()
        val registration = api.register(buildJsonObject { put("asset_id", imported.id); put("mode", "frame") }).metadata
        validateOperationSchema(registration, WorkspaceAssetResultSchemas.recordForOperation("asset_register")!!)
        val inspected = api.inspect(imported.id)
        validateOperationSchema(inspect(inspected), WorkspaceAssetResultSchemas.recordForOperation("asset_inspect")!!)
        assertEquals(reference, inspected.asset.details.getValue("reference").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(registration)), inspected.asset.details.getValue("registrations").jsonArray)
        assertFalse(inspected.asset.details.getValue("reference").jsonObject.keys.any { it.startsWith("_") })
        assertEquals(before, store.loadAsset("project", imported.id)!!.public.toJson())
        val restarted = WorkspaceAssetWorkflow("project", "new-revision", document, WorkspaceStore(temporary), WorkspacePngAssetStore())
        val composite = restarted.preview(buildJsonObject {
            putJsonArray("placements") { add(buildJsonObject { put("registration_id", registration.getValue("id")); put("insertion", "top") }) }
            put("replace_layer_ids", JsonArray(listOf(JsonPrimitive("art")))); put("target_long_edge", 128)
        })
        validateOperationSchema(composite.metadata, WorkspaceAssetResultSchemas.recordForOperation("asset_preview_composite")!!)
        assertEquals("new-revision", composite.metadata.getValue("revision_id").jsonPrimitive.content)
        assertEquals(1, composite.images.size)
        val reprocessed = restarted.reprocess(buildJsonObject {
            put("asset_id", imported.id); putJsonObject("processing") { put("edge_width", 0) }
        })
        validateOperationSchema(reprocessed.metadata, WorkspaceAssetResultSchemas.recordForOperation("asset_reprocess")!!)
        assertNotEquals(imported.id, reprocessed.metadata.getValue("asset_id").jsonPrimitive.content)
        assertEquals(before, store.loadAsset("project", imported.id)!!.public.toJson())
    }

    @Test fun assetContractsRejectUnknownProcessingAndMisstatedOrientationWithoutExpandingThePayload() {
        val store = WorkspaceStore(temporary)
        val assets = WorkspacePngAssetStore()
        val api = WorkspaceAssetWorkflow("project", "revision", document, store, assets)
        val reference = prepare(api).getValue("id").jsonPrimitive.content
        val asset = assets.import(WorkspacePngImportRequest(png(image(false)), reference, referenceId = reference), store.loadSpatial("project", reference)!!)
        val wrongDetails = JsonObject(asset.details + ("processing" to buildJsonObject { put("edge_witdh", 2) }))
        assertFailsWith<WorkspaceValidationException> {
            validateOperationSchema(JsonObject(asset.toJson() + ("details" to wrongDetails)), WorkspaceAssetResultSchemas.recordForOperation("asset_import_png")!!)
        }
        val registration = api.register(buildJsonObject { put("asset_id", asset.id); put("mode", "absolute")
            put("transform", WorkspacePlacementTransform(0.0, 0.0, 1.0, mirrorX = true).json()) }).metadata
        validateOperationSchema(registration, WorkspaceAssetResultSchemas.recordForOperation("asset_register")!!)
        assertEquals("explicit_reflection", registration.getValue("orientation").jsonPrimitive.content)
        assertFailsWith<WorkspaceValidationException> {
            validateOperationSchema(JsonObject(registration + ("orientation" to JsonPrimitive("inferred_mirror"))), WorkspaceAssetResultSchemas.recordForOperation("asset_register")!!)
        }
    }
}
