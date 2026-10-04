package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceRenderSessionTest {
    private val builder = WorkspacePreviewBuilder()
    private val frame = WorkspaceViewFrame.CanvasRect(Bounds(-64f, -32f, 192f, 224f))
    private val output = WorkspaceViewOutputSpec(512)
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private val unusedSampler = WorkspaceMotionSampler { _, _, _, _, _, _ -> error("Static rendering must not invoke native evaluation") }
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Drive", simulationDrivingEdits(root), MutationAuthor.USER)
        return runtime
    }
    private fun observation(runtime: WorkspaceRuntime<RigPreviewModel>, remember: (WorkspaceRenderedView) -> WorkspaceRenderedView = { it }) =
        WorkspaceObservationSession(runtime.read(), builder, unusedSampler, remember)
    private suspend fun replaceProject(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val before = runtime.capture()
        val changed = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Rename drive", listOf(
            WorkspaceDocumentOperation("parameter_update", buildJsonObject { put("parameter_id", "Drive"); put("name", "Changed drive") })), MutationAuthor.USER).capture
        // Replacement artwork is prepared before authoring forms; the old project's edits are discarded.
        val document = changed.document.copy(rigEdits = RigEditOverlay.Empty).paintSource(buildJsonObject {
            put("mode", "pencil"); put("layer_id", "strip"); put("radius", 4)
            putJsonArray("points") { add(buildJsonArray { add(4); add(4) }) }
            putJsonArray("color") { add(255); add(0); add(0); add(255) }
        })
        return runtime.install(changed.state, "replacement-project", document, builder.build(document), discardUnsaved = true)
    }

    @Test fun detachedSourceContextAndModelViewsKeepTheirPixelsAndRevisionAfterRepaintAndProjectReplacement() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val session = observation(runtime)
        suspend fun views() = listOf(session.renderLayer("strip", WorkspaceViewBackground.TRANSPARENT, output),
            session.renderContext("strip", 0.65f, 1f, WorkspaceViewBackground.TRANSPARENT, output),
            session.renderModel(WorkspaceModelViewRequest(parameters = mapOf("Drive" to 30f), frame = frame, output = output)))
        val expected = views(); replaceProject(runtime)
        val replacement = runtime.capture(); val history = runtime.history()
        views().zip(expected).forEach { (actual, original) ->
            assertEquals(before.revision, actual.revisionId); assertEquals(original.spatial, actual.spatial)
            assertContentEquals(original.png, actual.png)
            validateOperationSchema(actual.toJson(), WorkspaceObservationResultSchemas.view)
        }
        assertFalse(expected.first().png.contentEquals(observation(runtime).renderLayer("strip", WorkspaceViewBackground.TRANSPARENT, output).png))
        assertEquals(replacement, runtime.capture()); assertEquals(history, runtime.history())
    }

    private fun request(operation: String) = buildJsonObject {
        put("target_long_edge", 512)
        if (operation in setOf("view_render_layer", "view_render_context")) put("layer_id", "strip")
        else {
            putJsonObject("viewport") { put("mode", "canvas_rect"); put("left", -64); put("top", -32); put("width", 256); put("height", 256) }
            putJsonObject("parameters") { put("Drive", 0) }
            if (operation == "view_render_poses") putJsonArray("poses") {
                for (value in listOf(-30, 0, 30)) add(buildJsonObject { put("Drive", value) })
            }
            if (operation == "view_check_coverage") putJsonArray("include_layer_ids") { add("strip") }
        }
    }
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val remember: (WorkspaceRenderedView) -> WorkspaceRenderedView) : WorkspaceBackendStub() {
        val captures = AtomicInteger()
        override fun snapshot(): WorkspaceProjectSnapshot = error("Static observation must not read the live snapshot")
        override fun captureObservation(): WorkspaceObservation {
            captures.incrementAndGet()
            return WorkspaceObservationSession(runtime.read(), WorkspacePreviewBuilder(), WorkspaceMotionSampler { _, _, _, _, _, _ -> error("No native sampling") }, remember)
        }
        override suspend fun renderLayer(layerId: String, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Live rendering is forbidden")
        override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("Live rendering is forbidden")
        override suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView = error("Live rendering is forbidden")
    }

    @Test fun allStaticPublicViewsCaptureOnceAndPoseSheetsSurviveEditsAndReloadBetweenTiles() = runBlocking<Unit> {
        for (operation in listOf("view_render_layer", "view_render_context", "view_render_model", "view_render_poses", "view_check_coverage")) {
            val runtime = fixture(); val before = runtime.capture()
            val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val calls = AtomicInteger()
            val revisions = mutableListOf<String>()
            val host = Host(runtime) { view ->
                revisions += view.revisionId
                if (calls.incrementAndGet() == 1) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
                view
            }
            WorkspaceOperations(host).use { operations ->
                val render = async(Dispatchers.Default) { operations.registry.invoke(operation, request(operation), agent) }
                try {
                    withTimeout(5000) { entered.await() }
                    replaceProject(runtime)
                    val replacement = runtime.capture(); val history = runtime.history()
                    release.countDown()
                    val result = render.await()
                    assertEquals(1, result.images.size)
                    assertEquals(if (operation == "view_render_poses") 3 else 1, calls.get())
                    assertTrue(revisions.all { it == before.revision }); assertEquals(1, host.captures.get())
                    val metadata = if (operation == "view_check_coverage") result.data.getValue("view").jsonObject else result.data
                    assertEquals(before.revision, metadata.getValue("revisionId").jsonPrimitive.content)
                    assertEquals(replacement, runtime.capture()); assertEquals(history, runtime.history())
                    if (operation == "view_render_poses") {
                        val tiles = metadata.getValue("tiles").jsonArray
                        assertEquals(listOf(-30f, 0f, 30f), tiles.map { it.jsonObject.getValue("parameters").jsonObject.getValue("Drive").jsonPrimitive.float })
                    }
                } finally { release.countDown(); render.cancel() }
            }
        }
    }

    @Test fun explicitLayerSelectionAndCapturedVisibilityRemainIndependentOfLaterInputChanges() = runBlocking<Unit> {
        val runtime = fixture(); val visible = mutableSetOf("strip")
        val session = WorkspaceObservationSession(runtime.read(), builder, unusedSampler, { it }, visible)
        visible.clear()
        val base = WorkspaceModelViewRequest(frame = frame, output = output)
        assertEquals(listOf("strip"), session.renderModel(base).includedLayerIds)
        val blank = session.renderModel(base.copy(includeLayerIds = emptySet()))
        val image = ImageIO.read(blank.png.inputStream())
        assertTrue(image.getRGB(0, 0, image.width, image.height, null, 0, image.width).all { it ushr 24 == 0 })
        assertFailsWith<IllegalArgumentException> { session.renderModel(base.copy(parameters = mapOf("Missing" to 1f))) }
        assertFailsWith<IllegalArgumentException> { session.renderModel(base.copy(includeLayerIds = setOf("Missing"))) }
        val before = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "New parameter", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Later"); put("name", "Later"); put("min", -1); put("max", 1) })), MutationAuthor.USER)
        assertFailsWith<IllegalArgumentException> { session.renderModel(base.copy(parameters = mapOf("Later" to 1f))) }
        assertTrue("Later" in observation(runtime).renderModel(base.copy(parameters = mapOf("Later" to 1f))).appliedParameters)
    }

    @Test fun contextAndDefaultModelSelectionHonorOpacityAndLegacySplitVisibilityInheritance() = runBlocking<Unit> {
        fun layer(id: String, x: Int, rgba: List<Int>, opacity: Float = 1f) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, 0,
            LayerBounds(x, 0, 16, 16), opacity, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(16, 16, ByteArray(16 * 16 * 4) { rgba[it % 4].toByte() }), null, null, false)
        val base = layer("base", 0, listOf(255, 204, 32, 255), 0.5f)
        val left = layer("split:l", 0, listOf(255, 0, 0, 255)); val right = layer("split:r", 16, listOf(0, 0, 255, 255))
        val source = WorkspaceSourceArt(32, 32, listOf(base, left, right), emptyList())
        val config = PipelineConfig(meshOnly = true, atlasSize = 256, layerOverrides = source.layers.associate {
            it.id.raw to LayerClassificationOverride(type = LayerType.PRESET, tag = SemanticTag.OBJECTS)
        })
        val document = WorkspaceDocument(source, mapOf("split" to false), emptySet(), config.layerOverrides, emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val capture = runtime.install(runtime.state.value.state, "layered-project", document, builder.build(document))
        val session = observation(runtime)
        val model = session.renderModel(WorkspaceModelViewRequest(frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)), output = output))
        assertEquals(listOf("base"), model.includedLayerIds)
        val expected = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = expected.createGraphics()
        try { graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
            graphics.drawImage(PreviewRenderer.rasterImage(16, 16, base.raster.rgba), 0, 0, null)
        } finally { graphics.dispose() }
        val classified = capture.model.analysis.layers.single { it.source.id.raw == "split:l" }
        val expectedView = WorkspaceViewRenderer.context(expected, classified.source, classified.bounds, capture.revision, 0.5f, 2f,
            WorkspaceViewBackground.TRANSPARENT, output)
        val actual = session.renderContext("split:l", 0.5f, 2f, WorkspaceViewBackground.TRANSPARENT, output)
        assertContentEquals(expectedView.png, actual.png)
        assertEquals(128, expected.getRGB(8, 8) ushr 24)
    }

    @Test fun unloadedSessionsFailWithoutCreatingResourcesAndLoadedCapturesSurviveClose() = runBlocking<Unit> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }); val remembered = AtomicInteger()
        val empty = observation(runtime) { remembered.incrementAndGet(); it }
        assertFalse(empty.snapshot().loaded)
        assertFailsWith<IllegalStateException> { empty.renderLayer("strip", WorkspaceViewBackground.TRANSPARENT, output) }
        assertFailsWith<IllegalStateException> { empty.renderContext("strip", 0.65f, 1f, WorkspaceViewBackground.TRANSPARENT, output) }
        assertFailsWith<IllegalStateException> { empty.renderModel(WorkspaceModelViewRequest(frame = frame, output = output)) }
        assertEquals(0, remembered.get())
        val before = simulationFixture(runtime); val loaded = observation(runtime) { remembered.incrementAndGet(); it }
        runtime.close(before.state, discardUnsaved = true)
        assertEquals(before.revision, loaded.renderLayer("strip", WorkspaceViewBackground.TRANSPARENT, output).revisionId)
        assertFalse(observation(runtime).snapshot().loaded); assertEquals(1, remembered.get())
    }
}
