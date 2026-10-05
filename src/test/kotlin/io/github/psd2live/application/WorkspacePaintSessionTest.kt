package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspacePaintSessionTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun fixture(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val layer = WorkspaceSourceLayer(LayerId("art"), "Art", "", SourceLayerKind.Raster, true, 1,
            LayerBounds(12, 8, 32, 32), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(32, 32, ByteArray(32 * 32 * 4) { if (it % 4 == 3) 255.toByte() else 100 }), null, null, false)
        val document = WorkspaceDocument(WorkspaceSourceArt(64, 48, listOf(layer), emptyList()), emptyMap(), emptySet(),
            mapOf("art" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)), emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, meshOnly = true, generatePhysics = false, exportMoc3 = false)))
        return WorkspaceRuntime(rebuild).also { it.install(it.state.value.state, "paint", document, builder.build(document)) }
    }
    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>,
        val after: suspend () -> Unit = {}, val afterControl: suspend (JsonObject) -> Unit = {}) : WorkspaceBackendStub() {
        val sessions = WorkspacePaintSessions(runtime)
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override fun beginPaintSession(state: String, layerId: String) = sessions.begin(state, layerId)
        override fun listPaintSessions() = sessions.list()
        override suspend fun controlPaintSession(request: JsonObject): WorkspaceWorkflowResult {
            val result = runInterruptible(Dispatchers.Default) { sessions.get(request.getValue("session_id").jsonPrimitive.content).control(request) }
            afterControl(request); return result
        }
        override suspend fun commitPaintSession(state: String, sessionId: String, sessionState: String,
            rebuildMesh: Boolean, preserveSourceRaster: Boolean, author: MutationAuthor): WorkspaceMutationResult {
            assertEquals(state, runtime.capture().state)
            val result = sessions.commit(sessionId, sessionState, rebuildMesh, preserveSourceRaster, author)
            after(); return result.mutation
        }
    }
    private fun context(runtime: WorkspaceRuntime<RigPreviewModel>, fields: JsonObject, id: String) = JsonObject(fields + buildJsonObject {
        val captured = runtime.capture(); put("project_id", captured.projectId); put("state", captured.state); put("request_id", id)
    })
    private fun control(runtime: WorkspaceRuntime<RigPreviewModel>, session: WorkspacePaintSession, action: String,
        id: String = java.util.UUID.randomUUID().toString(), fields: JsonObject = JsonObject(emptyMap())) =
        context(runtime, buildJsonObject {
            put("session_id", session.id); put("session_state", session.sessionState)
            put("edit", JsonObject(fields + ("action" to JsonPrimitive(action))))
        }, id)
    private fun gesture(mode: String = "brush", red: Int = 220) = buildJsonObject {
        put("mode", mode)
        if (mode != "clear") put("color", JsonArray(listOf(red, 30, 80, 255).map(::JsonPrimitive)))
        when (mode) {
            "brush", "pencil", "eraser" -> {
                put("radius", 4); put("opacity", 0.5)
                putJsonArray("points") { for (x in listOf(4, 9, 4)) add(buildJsonArray { add(x); add(5) }) }
            }
            "shape" -> {
                put("shape", "rectangle"); put("filled", true)
                put("from", buildJsonArray { add(50); add(30) }); put("to", buildJsonArray { add(60); add(42) })
            }
        }
    }
    private suspend fun WorkspaceOperations.call(id: String, request: JsonObject) = registry.invoke(id, request, agent)
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = call("job_wait", buildJsonObject { put("id", job.getValue("id")) }).data
    private suspend fun WorkspaceOperations.cancel(job: JsonObject) =
        call("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "stop") })
    private fun pixels(session: WorkspacePaintSession) = session.image().let { it.getRGB(0, 0, it.width, it.height, null, 0, it.width) }

    @Test fun guiAndPublicControlsSharePrivatePixelsStrokeBranchAndCandidateObservations() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history(); val host = Host(runtime)
        WorkspaceOperations(host).use { operations ->
            val opened = operations.call("paint_session_begin", context(runtime, buildJsonObject { put("layer_id", "art") }, "begin")).data
            val session = host.sessions.get(opened.getValue("session").jsonObject.getValue("session_id").jsonPrimitive.content)
            val gui = io.github.psd2live.ui.PaintSession(session)
            assertEquals(session.id, operations.call("paint_session_list", JsonObject(emptyMap())).data
                .getValue("sessions").jsonArray.single().jsonObject.getValue("session_id").jsonPrimitive.content)
            val initial = pixels(session)
            val revision = session.sessionState
            assertFailsWith<WorkspaceValidationException> {
                operations.call("paint_session_control", control(runtime, session, "gesture", fields = buildJsonObject {
                    put("gesture", JsonObject(gesture() + ("radius" to JsonPrimitive(-1))))
                }))
            }
            assertEquals(revision, session.sessionState); assertContentEquals(initial, pixels(session))
            session.beginStroke()
            val tip = RasterPaintEngine.Tip(4f, 1f)
            session.segment(4f, 5f, 9f, 5f, tip, 0xffdc1e50.toInt(), 0.5f, false)
            session.segment(9f, 5f, 4f, 5f, tip, 0xffdc1e50.toInt(), 0.5f, false)
            val live = pixels(session)
            assertFailsWith<WorkspaceBusy> { session.undo() }
            val sample = operations.call("paint_session_control", control(runtime, session, "sample",
                fields = buildJsonObject { put("x", 4); put("y", 5) })).data
            assertEquals(128, sample.getValue("rgba").jsonArray[3].jsonPrimitive.int)
            operations.call("paint_session_control", control(runtime, session, "abandon"))
            assertContentEquals(initial, pixels(session)); assertEquals(0, gui.strokeCount)
            operations.call("paint_session_control", control(runtime, session, "gesture", fields = buildJsonObject { put("gesture", gesture()) }))
            assertContentEquals(live, pixels(session)); assertEquals(1, gui.strokeCount); assertTrue(gui.isDirty)
            val stale = control(runtime, session, "undo", "stale")
            gui.undo(); assertContentEquals(initial, pixels(session))
            assertFailsWith<WorkspaceConflict> { operations.call("paint_session_control", stale) }
            operations.call("paint_session_control", control(runtime, session, "redo")); assertContentEquals(live, pixels(session))
            operations.call("paint_session_control", control(runtime, session, "gesture", fields = buildJsonObject { put("gesture", gesture("shape")) }))
            val both = pixels(session)
            operations.call("paint_session_control", control(runtime, session, "jump", fields = buildJsonObject { put("index", 0) }))
            assertContentEquals(initial, pixels(session))
            gui.jumpToStroke(2); assertContentEquals(both, pixels(session))
            gui.undo()
            session.gesture(gesture("pencil", 100))
            assertFalse(session.canRedo()); assertEquals(2, session.index)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            val render = operations.call("paint_session_control", control(runtime, session, "render"))
            assertFalse("png_base64" in render.data); assertEquals(1, render.images.size)
            val image = ImageIO.read(render.images.single().inputStream())
            assertEquals(session.sample(4, 5), image.getRGB(4, 5))
            assertTrue(gui.previewTiles.isNotEmpty())
            gui.discard(); assertContentEquals(initial, pixels(session)); assertTrue(session.finished)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        }
    }

    @Test fun oneCommitPersistsEveryStrokeOnceAcrossHistoryArchiveAndExports() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val host = Host(runtime)
        WorkspaceOperations(host).use { operations ->
            val session = host.beginPaintSession(before.state, "art")
            session.gesture(gesture()); session.gesture(gesture("shape"))
            val expected = pixels(session)
            val request = context(runtime, buildJsonObject {
                put("session_id", session.id); put("session_state", session.sessionState); put("rebuild_mesh", true)
            }, "commit")
            val started = operations.call("paint_session_commit", request).data
            val terminal = operations.wait(started)
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
            val after = runtime.capture()
            assertEquals(2, runtime.history().selections.size); assertTrue(session.finished)
            assertEquals(after.state, terminal.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
            assertEquals(started.getValue("id"), operations.call("paint_session_commit", request).data.getValue("id"))
            assertEquals(2, runtime.history().selections.size)
            val actual = after.document.sourceLayerImage("art")
            assertContentEquals(expected, actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width))
            val archive = temporary.resolve("paint.psd2live")
            ProjectRepository().save(ProjectSaveCapture(after.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("store"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            val files = PSD2LivePipeline().run(after.document.source, "paint", temporary.resolve("export"),
                after.document.config().copy(exportMoc3 = true)).exportedFiles
            val cmo = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            val moc = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".moc3") }.path)), null)
            val evaluated = CpuDeformationEvaluator().evaluate(after.model.rig.puppet, emptyMap())
            for (model in listOf(reopened.rig.puppet, cmo, moc)) {
                val restored = CpuDeformationEvaluator().evaluate(model, emptyMap())
                assertEquals(evaluated.worldPositions.keys, restored.worldPositions.keys)
                evaluated.worldPositions.forEach { (id, xy) -> xy.indices.forEach { assertEquals(xy[it], restored.worldPositions.getValue(id)[it], 0.001f) } }
            }
            fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "paint", emptyMap(),
                model.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 64f, 48f)),
                WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
            val png = render(after.model); assertContentEquals(png, render(reopened))
            val visuals = Path.of("build/paint-session-visual"); Files.createDirectories(visuals)
            Files.write(visuals.resolve("committed.png"), png); Files.write(visuals.resolve("reopened.png"), render(reopened))
            runtime.checkout(after.projectId, after.state, before.historyHead)
            assertEquals(before.document, runtime.capture().document)
            val empty = host.beginPaintSession(runtime.capture().state, "art")
            val noop = operations.call("paint_session_commit", context(runtime, buildJsonObject {
                put("session_id", empty.id); put("session_state", empty.sessionState)
            }, "noop")).data
            val noopResult = operations.wait(noop).getValue("result").jsonObject
            assertEquals(false, noopResult.getValue("applied").jsonPrimitive.boolean)
            assertEquals(2, runtime.history().selections.size); assertTrue(empty.finished)
        }
    }

    @Test fun failedGestureStaleWorkspaceAndCancelledCandidateKeepPixelsAndHistoryPrivate() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); var block = false
        val runtime = fixture { document -> if (block) { entered.complete(Unit); awaitCancellation() }; builder.build(document) }
        val before = runtime.capture(); val history = runtime.history(); val host = Host(runtime)
        val session = host.beginPaintSession(before.state, "art"); val initial = pixels(session)
        assertFailsWith<CancellationException> {
            session.gesture(gesture(), checkpoint = {
                if (session.sample(4, 5) != initial[5 * session.width + 4]) throw CancellationException("Stopped after raster write")
            })
        }
        assertContentEquals(initial, pixels(session)); assertEquals(0, session.index)
        session.gesture(gesture("shape")); val draft = pixels(session)
        assertFailsWith<IllegalStateException> {
            host.sessions.commit(session.id, session.sessionState, false, false, MutationAuthor.USER) { _, _, _ -> error("Projection rejected") }
        }
        assertEquals(before, runtime.capture()); assertFalse(session.finished)
        WorkspaceOperations(host).use { operations ->
            block = true
            val started = operations.call("paint_session_commit", context(runtime, buildJsonObject {
                put("session_id", session.id); put("session_state", session.sessionState)
            }, "cancel")).data
            withTimeout(5000) { entered.await() }; operations.cancel(started)
            assertEquals("cancelled", operations.wait(started).getValue("status").jsonPrimitive.content)
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history()); assertContentEquals(draft, pixels(session))
            block = false
            val foreign = runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("changed", true) })
            assertFailsWith<WorkspaceConflict> { session.undo() }
            assertFailsWith<WorkspaceConflict> { host.sessions.commit(session.id, session.sessionState, false, false, MutationAuthor.USER) }
            session.cancel(); assertEquals(foreign, runtime.capture())
        }
    }

    @Test fun disconnectedRenderRetryRetainsImagesAndLateCommitFailuresRetainSuccess() = runBlocking<Unit> {
        val runtime = fixture(); val rendered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val host = Host(runtime, afterControl = { request ->
            if (request.getValue("edit").jsonObject["action"] == JsonPrimitive("render")) { rendered.complete(Unit); release.await() }
        })
        WorkspaceOperations(host).use { operations ->
            val session = host.beginPaintSession(runtime.capture().state, "art"); session.gesture(gesture())
            val request = control(runtime, session, "render", "render")
            val waiter = async { operations.call("paint_session_control", request) }
            withTimeout(5000) { rendered.await() }; waiter.cancelAndJoin()
            val atRender = pixels(session); session.gesture(gesture("shape")); release.complete(Unit)
            val retry = operations.call("paint_session_control", request)
            val image = ImageIO.read(retry.images.single().inputStream())
            assertContentEquals(atRender, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
            assertContentEquals(retry.images.single(), operations.call("paint_session_control", request).images.single())
        }
        for (cancel in listOf(false, true)) {
            val candidate = fixture(); val committed = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
            val late = Host(candidate, after = {
                withContext(NonCancellable) { committed.complete(Unit); resume.await() }
                if (!cancel) throw IOException("Renderer refresh failed after CAS")
            })
            WorkspaceOperations(late).use { operations ->
                val session = late.beginPaintSession(candidate.capture().state, "art"); session.gesture(gesture())
                val request = context(candidate, buildJsonObject { put("session_id", session.id); put("session_state", session.sessionState) }, "once")
                val job = operations.call("paint_session_commit", request).data
                withTimeout(5000) { committed.await() }; if (cancel) operations.cancel(job); resume.complete(Unit)
                val terminal = operations.wait(job)
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
                assertEquals(candidate.capture().state, terminal.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
                assertEquals(2, candidate.history().selections.size); assertTrue(session.finished)
                assertEquals(job.getValue("id"), operations.call("paint_session_commit", request).data.getValue("id"))
            }
        }
    }
}
