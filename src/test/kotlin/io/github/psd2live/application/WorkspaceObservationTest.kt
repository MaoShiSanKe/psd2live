package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import org.umamo.format.art.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceObservationTest {
    @Test fun sourceAndRigViewsRenderWithoutGuiAndRetainTheirCaptureMetadata() {
        val raster = LayerRaster(16, 16, ByteArray(16 * 16 * 4) { index ->
            when (index % 4) { 0 -> 230.toByte(); 1 -> 70; 2 -> 35; else -> 255.toByte() }
        })
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster,
            true, 0, LayerBounds(4, 4, 16, 16), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            raster, null, null, false)
        val source = WorkspaceSourceArt(32, 32, listOf(layer), emptyList())
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), mapOf("art" to
            LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS)),
            emptyMap(), RigEditOverlay.Empty)
        val revision = WorkspaceRevisions.of(document)
        val preview = PSD2LivePipeline().buildPreview(source, PipelineConfig(atlasSize = 256,
            layerOverrides = document.layerOverrides))
        val output = WorkspaceViewOutputSpec(targetLongEdge = 128)
        val sourceView = WorkspaceViewRenderer.isolatedLayer(layer, 32, 32, revision,
            WorkspaceViewBackground.TRANSPARENT, output)
        val rigView = WorkspaceViewRenderer.modelComposite(preview, revision, emptyMap(), setOf("art"),
            emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)),
            WorkspaceViewBackground.TRANSPARENT, output)
        for (view in listOf(sourceView, rigView)) {
            val metadata = view.toJson()
            validateOperationSchema(metadata, WorkspaceObservationResultSchemas.view)
            val invalidSpatial = JsonObject(metadata.getValue("spatial").jsonObject +
                ("pixelToCanvas" to JsonArray(listOf(JsonPrimitive(1)))))
            assertFailsWith<WorkspaceValidationException> {
                validateOperationSchema(JsonObject(metadata + ("spatial" to invalidSpatial)), WorkspaceObservationResultSchemas.view)
            }
            assertEquals(revision, view.revisionId)
            val image = ImageIO.read(ByteArrayInputStream(view.png))
            assertNotNull(image)
            assertEquals(128, maxOf(image.width, image.height))
            val painted = image.getRGB(image.width / 2, image.height / 2)
            assertTrue((painted ushr 24) > 240)
            assertTrue(((painted ushr 16) and 255) > 200)
        }
        assertEquals(revision, WorkspaceRevisions.of(document))
        assertContentEquals(raster.rgba, source.layers.single().raster.rgba)
    }

    @Test fun poseAndVersionSheetsValidateTheirOwnMetadataAndRejectConflictingRevisionFields() {
        val image = java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val bytes = java.io.ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val rect = Bounds(0f, 0f, 32f, 32f)
        val spatial = WorkspaceViewSpatialMetadata(pixelWidth = 128, pixelHeight = 128, canvasWidth = 32f, canvasHeight = 32f,
            requestedViewRect = rect, viewRect = rect, canvasUnitsPerPixelX = 0.25f, canvasUnitsPerPixelY = 0.25f)
        val view = WorkspaceRenderedView("view", "revision", "model", emptyList(), bytes, 128, 128, 128, 128,
            rect, 1f, "hash", spatial, appliedParameters = mapOf("angle" to 0f))
        val output = WorkspaceViewOutputSpec(targetLongEdge = 512)
        val poses = renderPoseSheet(listOf(view, view.copy(viewId = "other", appliedParameters = mapOf("angle" to 1f))), output)
        val poseSchema = WorkspaceObservationResultSchemas.forOperation("view_render_poses")!!
        validateOperationSchema(poses.metadata, poseSchema)
        val versions = renderPoseSheet(listOf(view, view.copy(viewId = "previous", revisionId = "older")), output,
            compareVersions = true)
        val comparison = JsonObject(versions.metadata + ("states" to JsonArray(listOf(JsonPrimitive("head"), JsonPrimitive("older-head")))))
        val historySchema = WorkspaceObservationResultSchemas.forOperation("view_compare_history")!!
        validateOperationSchema(comparison, historySchema)
        assertFailsWith<WorkspaceValidationException> {
            validateOperationSchema(JsonObject(comparison + ("revisionId" to JsonPrimitive("one-revision"))), historySchema)
        }
        assertFailsWith<WorkspaceValidationException> { validateOperationSchema(comparison, poseSchema) }
        val coverage = JsonObject(view.toJson().let { buildJsonObject { put("view", it); put("coverage", measureCoverage(image, 128)) } })
        validateOperationSchema(coverage, WorkspaceObservationResultSchemas.forOperation("view_check_coverage")!!)
    }
}
