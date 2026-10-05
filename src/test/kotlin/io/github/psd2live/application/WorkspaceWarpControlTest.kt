package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.warpApply
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceWarpControlTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val actor = WorkspaceOperationContext(MutationAuthor.AGENT)
    private fun topology(rows: Int, columns: Int = rows, id: String = "control") = WorkspaceDocumentOperation("warp_set_topology", buildJsonObject {
        put("target", "warp:$id"); put("rows", rows); put("columns", columns)
    })
    private fun divisions(rows: Int, columns: Int = rows) = WorkspaceDocumentOperation("warp_bezier_divisions", buildJsonObject {
        put("target", "warp:control"); put("rows", rows); put("columns", columns)
    })
    private fun create(mesh: String, id: String = "control") = WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
        put("id", id); put("name", "Control"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(mesh) }
    })
    private suspend fun fixture(rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime(rebuild); simulationFixture(runtime)
        val before = runtime.capture()
        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Warp", listOf(create(before.model.rig.puppet.drawables.single().id.raw)), MutationAuthor.USER)
        return runtime
    }
    private suspend fun apply(runtime: WorkspaceRuntime<RigPreviewModel>, vararg edits: WorkspaceDocumentOperation): WorkspaceCommit<RigPreviewModel> {
        val before = runtime.capture()
        return WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Controls", edits.toList(), MutationAuthor.USER)
    }
    private fun warp(model: PuppetModel) = model.deformers.single { it.id.raw == "control" } as Deformer.Warp
    private suspend fun author(runtime: WorkspaceRuntime<RigPreviewModel>) {
        apply(runtime, WorkspaceDocumentOperation("parameter_create", buildJsonObject {
            put("parameter_id", "Axis"); put("name", "Axis"); put("min", 0); put("max", 1); put("default", 0.35f)
        }), WorkspaceDocumentOperation("parameter_create", buildJsonObject {
            put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape"); put("min", 0); put("max", 1); put("default", 0.4f)
        }))
        val points = warp(runtime.capture().model.rig.puppet).geometryGrid!!.cells.single().form.controlPoints
        fun form(key: String, value: Float, amplitude: Float) = buildJsonObject {
            put("op", "set"); put("target", "warp:control"); putJsonObject("key") { put(key, value) }
            putJsonObject("geometry") { put("controlPoints", RigWarpTopology.floats(points.copyOf().also { it[8] += amplitude; it[9] -= amplitude * 0.7f })) }
            putJsonObject("channels") { put("opacity", 0.6f + value * 0.3f); put("multiplyColor", JsonArray(listOf(0.8f, 0.9f, 1f).map(::JsonPrimitive))) }
        }
        val before = runtime.capture()
        WorkspaceDocumentCommands(runtime).executeJournal(before.projectId, before.state, "Ordinary and blend", JsonArray(listOf(
            form("Axis", 0f, 1f), form("Axis", 1f, 3f), form("Blend", 1f, 4f))), MutationAuthor.USER)
    }
    private fun assertWorld(before: PuppetModel, after: PuppetModel, tolerance: Float = 0.002f) {
        val evaluator = CpuDeformationEvaluator()
        for (axis in listOf(0f, 0.35f, 0.5f, 1f)) for (blend in listOf(0f, 0.4f, 0.7f, 1f)) {
            val pose = mapOf(ParameterId("Axis") to axis, ParameterId("Blend") to blend).filterKeys { id -> before.parameters.any { it.id == id } }
            val a = evaluator.evaluate(before, pose); val b = evaluator.evaluate(after, pose)
            a.worldPositions.forEach { (id, points) ->
                val actual = b.worldPositions.getValue(id); assertEquals(points.size, actual.size)
                points.indices.forEach { assertEquals(points[it], actual[it], tolerance, "$id/$pose scalar $it") }
                assertEquals(a.opacity.getValue(id), b.opacity.getValue(id), 0.0001f)
            }
        }
    }
    private class Host(private val runtime: WorkspaceRuntime<RigPreviewModel>, private val refresh: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override suspend fun editWarpControls(operation: String, expectedState: String, request: JsonObject, author: MutationAuthor): JsonObject {
            val result = WorkspaceWarpControlCommands(runtime).execute(runtime.capture().projectId, expectedState, WorkspaceDocumentOperation(operation, request), author)
            refresh(); return result.output
        }
        override suspend fun applyDocumentEdits(state: String, summary: String, edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            val before = runtime.capture()
            return WorkspaceDocumentCommands.mutationResult(before, WorkspaceDocumentCommands(runtime).execute(before.projectId, state, summary, edits, author), summary, edits)
        }
    }
    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, request: JsonObject, id: String) = JsonObject(request + buildJsonObject {
        put("state", runtime.capture().state); put("project_id", runtime.capture().projectId); put("request_id", id)
    })
    private suspend fun WorkspaceOperations.call(id: String, request: JsonObject) = registry.invoke(id, request, actor).data
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = withTimeout(20000) { call("job_wait", buildJsonObject { put("id", job.getValue("id")) }) }

    @Test fun refinementRetainsOrdinaryBlendDefaultChannelsHistoryAndIdentity() = runBlocking<Unit> {
        val runtime = fixture(); author(runtime); val before = runtime.capture(); val old = warp(before.model.rig.puppet)
        val result = WorkspaceWarpControlCommands(runtime).execute(before.projectId, before.state, topology(4), MutationAuthor.USER)
        assertTrue(result.commit.applied); assertTrue(result.output.getValue("max_surface_error").jsonPrimitive.float < 0.0001f)
        val after = result.commit.capture; val changed = warp(after.model.rig.puppet)
        assertEquals(4, changed.rows); assertEquals(4, changed.columns); assertEquals(old.parent, changed.parent); assertEquals(old.partId, changed.partId)
        assertEquals(old.channelGrids.gridsByChannel.keys, changed.channelGrids.gridsByChannel.keys)
        for ((channel, grid) in old.channelGrids.gridsByChannel) {
            val actual = changed.channelGrids.gridsByChannel.getValue(channel)
            assertEquals(grid.axes.map { it.parameterId }, actual.axes.map { it.parameterId })
            grid.axes.indices.forEach { assertContentEquals(grid.axes[it].keys, actual.axes[it].keys) }
            assertEquals(grid.cells.map { it.form }, actual.cells.map { it.form })
        }
        assertEquals(old.blendShapes.map { it.parameterId }, changed.blendShapes.map { it.parameterId })
        assertEquals(old.blendShapes.map { it.limits }, changed.blendShapes.map { it.limits })
        assertWorld(before.model.rig.puppet, after.model.rig.puppet); assertWorld(after.model.rig.puppet, builder.build(after.document).rig.puppet)
        val history = runtime.history(); assertFalse(apply(runtime, topology(4)).applied); assertEquals(history, runtime.history())
        runtime.checkout(after.projectId, runtime.capture().state, before.historyHead)
        assertEquals(2, warp(runtime.capture().model.rig.puppet).rows)
        runtime.checkout(after.projectId, runtime.capture().state, after.historyHead)
        assertWorld(before.model.rig.puppet, runtime.capture().model.rig.puppet)
    }

    @Test fun triangleRefinementAndBlendLimitsUseActualSurfaceAndCoarseningReportsLoss() = runBlocking<Unit> {
        val runtime = fixture(); author(runtime); val source = runtime.capture().model.rig.puppet
        val original = warp(source)
        val limit = BlendWeightLimit(ParameterId("Axis"), listOf(BlendWeightLimitPoint(0f, 0.25f), BlendWeightLimitPoint(1f, 0.85f)))
        val triangular = source.copy(deformers = source.deformers.map { if (it.id == original.id) original.copy(isQuadTransform = false,
            blendShapes = original.blendShapes.map { binding -> binding.copy(limits = listOf(limit)) }) else it })
        val refined = RigWarpTopology.replay(triangular, RigWarpTopology.prepare(triangular, "control", 4, 4))
        assertWorld(triangular, refined)
        assertEquals(listOf(limit), warp(refined).blendShapes.single().limits)
        val loss = RigWarpTopology.prepare(source, "control", 1, 1)
        assertTrue(loss.getValue("max_surface_error").jsonPrimitive.float > 0.5f)
        val coarse = RigWarpTopology.replay(source, loss); val p = FloatArray(2); val q = FloatArray(2)
        warpApply(original.geometryGrid!!.cells.first().form.controlPoints, 2, 2, true, 0.5f, 0.5f, p, 0)
        warpApply(warp(coarse).geometryGrid!!.cells.first().form.controlPoints, 1, 1, true, 0.5f, 0.5f, q, 0)
        assertTrue(kotlin.math.abs(p[0] - q[0]) > 0.5f)
        assertFailsWith<CancellationException> { RigWarpTopology.prepare(source, "control", 8, 8) { throw CancellationException("Stopped") } }
    }

    @Test fun referenceEditAndBezierResetDoNotDoubleApplyNonzeroBlendDefaults() = runBlocking<Unit> {
        val runtime = fixture(); author(runtime)
        val before = runtime.capture(); val original = before.model.rig.puppet
        val geometry = RigGeometryTools.geometry(original, "warp", "control", emptyMap())
        val desired = FloatArray(geometry.points.size) { geometry.points[it] + if(it % 2 == 0) 2f else -3f }
        val record = buildJsonObject {
            put("op", "canvas_geometry"); put("kind", "warp"); put("id", "control")
            put("key", JsonObject(emptyMap())); put("points", RigWarpTopology.floats(desired))
        }
        val after = WorkspaceDocumentCommands(runtime).executeJournal(before.projectId, before.state, "Reference", JsonArray(listOf(record)), MutationAuthor.USER).capture
        for (axis in listOf(0f, 0.35f, 0.7f, 1f)) for (blend in listOf(0f, 0.4f, 1f)) {
            val pose = mapOf("Axis" to axis, "Blend" to blend)
            val a = RigGeometryTools.geometry(original, "warp", "control", pose).points
            val b = RigGeometryTools.geometry(after.model.rig.puppet, "warp", "control", pose).points
            a.indices.forEach { assertEquals(a[it] + if(it % 2 == 0) 2f else -3f, b[it], 0.0001f) }
        }
        assertWorld(after.model.rig.puppet, builder.build(after.document).rig.puppet)
        apply(runtime, topology(8), divisions(1))
        val resetBefore = runtime.capture()
        val local = RigGeometryTools.geometry(resetBefore.model.rig.puppet, "warp", "control", emptyMap())
        val expected = BezierDeformerState(1, 1).apply { initFromLattice(local.points, local.rows!!, local.columns!!) }.evaluateLattice(local.rows!!, local.columns!!)
        apply(runtime, WorkspaceDocumentOperation("warp_bezier_reset", buildJsonObject { put("target", "warp:control") }))
        val reset = runtime.capture()
        val actual = RigGeometryTools.geometry(reset.model.rig.puppet, "warp", "control", emptyMap()).points
        expected.indices.forEach { assertEquals(expected[it], actual[it], 0.0001f) }
        assertTrue(expected.indices.any { kotlin.math.abs(expected[it] - local.points[it]) > 0.1f })
        assertWorld(reset.model.rig.puppet, builder.build(reset.document).rig.puppet)
    }

    @Test fun publicControlsEqualGestureCandidateAndReconstructAfterOtherGeometryChanges() = runBlocking<Unit> {
        val runtime = fixture(); apply(runtime, topology(8), divisions(2, 3))
        WorkspaceOperations(Host(runtime)).use { operations ->
            val summary = operations.call("warp_get_controls", buildJsonObject { put("target", "warp:control") })
            assertEquals(2, summary.getValue("rows").jsonPrimitive.int); assertEquals(3, summary.getValue("columns").jsonPrimitive.int)
            assertTrue(summary.getValue("persisted").jsonPrimitive.boolean); assertFalse(summary.getValue("cmo3_has_handles").jsonPrimitive.boolean)
            assertFalse("controls" in summary)
            val before = runtime.capture()
            val controls = RigBezierJournal.read(before.model.rig.puppet, before.document.rigEdits, "control", emptyMap())
            val handle = controls.state.handles.getValue(Triple(0, 0, BezierHandleDir.RIGHT))
            val x = handle.x + 4f; val y = handle.y - 5f
            controls.state.moveHandle(0, 0, BezierHandleDir.RIGHT, x, y, true)
            val gesture = RigBezierJournal.materialize(before.model.rig.puppet, "control", emptyMap(), emptyMap(), controls)
            val edit = WorkspaceDocumentOperation("warp_bezier_handle", buildJsonObject {
                put("target", "warp:control"); put("row", 0); put("column", 0); put("direction", "right"); put("x", x); put("y", y); put("smooth", true)
            })
            val job = operations.call(edit.operation, input(runtime, edit.request, "handle")); val terminal = operations.wait(job)
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
            val after = runtime.capture()
            assertEquals(gesture, after.document.rigEdits.authoringJournal.last())
            assertWorld(RigAuthoringJournal.apply(before.model.rig.puppet, gesture), after.model.rig.puppet)
            assertEquals(job, operations.call(edit.operation, inputFrom(before, edit.request, "handle")))
            var offset = 0; val entries = mutableListOf<JsonElement>()
            do {
                val page = operations.call("warp_get_controls", buildJsonObject { put("target", "warp:control"); put("detail", "points"); put("offset", offset); put("limit", 3) })
                entries.addAll(page.getValue("controls").jsonArray)
                offset = page["nextOffset"]?.jsonPrimitive?.int ?: -1
            } while(offset >= 0)
            assertEquals(12 + 34, entries.size)
            val anchors = RigBezierJournal.read(after.model.rig.puppet, after.document.rigEdits, "control", emptyMap()).state.anchors
            val anchor = anchors.getValue(1 to 1)
            val moved = WorkspaceDocumentOperation("warp_bezier_anchor", buildJsonObject {
                put("target", "warp:control"); put("row", 1); put("column", 1); put("x", anchor.x + 1); put("y", anchor.y + 2)
            })
            apply(runtime, moved)
            assertTrue(RigBezierJournal.read(runtime.capture().model.rig.puppet, runtime.capture().document.rigEdits, "control", emptyMap()).persisted)
            apply(runtime, topology(6))
            val current = runtime.capture()
            assertEquals(2 to 3, RigBezierJournal.divisions(current.document.rigEdits, "control"))
            assertFalse(RigBezierJournal.read(current.model.rig.puppet, current.document.rigEdits, "control", emptyMap()).persisted)
            val reset = WorkspaceDocumentOperation("warp_bezier_reset", buildJsonObject { put("target", "warp:control") })
            apply(runtime, reset); val resetCapture = runtime.capture()
            assertTrue(RigBezierJournal.read(resetCapture.model.rig.puppet, resetCapture.document.rigEdits, "control", emptyMap()).persisted)
            assertWorld(resetCapture.model.rig.puppet, builder.build(resetCapture.document).rig.puppet)
        }
    }
    private fun inputFrom(capture: WorkspaceCapture<RigPreviewModel>, request: JsonObject, id: String) = JsonObject(request + buildJsonObject {
        put("state", capture.state); put("project_id", capture.projectId); put("request_id", id)
    })

    @Test fun orderedNewWarpBatchLateFailureConflictAndRejectedProjectionPublishNoPrefix() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val bad = WorkspaceDocumentOperation("warp_bezier_handle", buildJsonObject {
            put("target", "warp:control"); put("row", 0); put("column", 0); put("direction", "left"); put("x", 1); put("y", 1)
        })
        assertFailsWith<WorkspaceBatchEditException> { apply(runtime, topology(4), bad) }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        assertFailsWith<WorkspaceConflict> { WorkspaceWarpControlCommands(runtime).execute(before.projectId, "old-state", topology(4), MutationAuthor.USER) }
        assertFailsWith<IllegalStateException> { WorkspaceWarpControlCommands(runtime).execute(before.projectId, before.state, topology(4), MutationAuthor.USER) { _, _, _ -> error("Projection failed") } }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        val mesh = before.model.rig.puppet.drawables.single().id.raw
        val after = apply(runtime, create(mesh, "born"), topology(4, id = "born"))
        assertEquals(4, (after.capture.model.rig.puppet.deformers.single { it.id.raw == "born" } as Deformer.Warp).rows)
        assertEquals(history.selections.size + 1, runtime.history().selections.size)
    }

    @Test fun cancellationConcurrentCommitAndLateRefreshUseTheAuthoritativeCas() = runBlocking<Unit> {
        for (cancel in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = fixture { document ->
                if (document.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == RigWarpTopology.OP }) { entered.complete(Unit); release.await() }
                builder.build(document)
            }
            val before = runtime.capture(); val history = runtime.history()
            WorkspaceOperations(Host(runtime)).use { operations ->
                val job = operations.call("warp_set_topology", input(runtime, topology(4).request, "topology"))
                withTimeout(10000) { entered.await() }
                val expected = if(cancel) { operations.call("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }); before }
                    else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit); val terminal = operations.wait(job)
                assertEquals(if(cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                assertEquals(expected, runtime.capture()); assertEquals(history, runtime.history())
            }
        }
        val runtime = fixture()
        WorkspaceOperations(Host(runtime) { throw CancellationException("Late refresh") }).use { operations ->
            val terminal = operations.wait(operations.call("warp_set_topology", input(runtime, topology(4).request, "late")))
            assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
            assertEquals(runtime.capture().state, terminal.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
        }
    }

    @Test fun archiveBothExportsAndImportedDivisionsKeepSampledGeometryAndVisiblePixels() = runBlocking<Unit> {
        val runtime = fixture(); author(runtime); apply(runtime, topology(8), divisions(3, 4))
        val before = runtime.capture(); val key = mapOf("Axis" to 1f, "Blend" to 1f)
        val controls = RigBezierJournal.read(before.model.rig.puppet, before.document.rigEdits, "control", key)
        val anchor = controls.state.anchors.getValue(1 to 1)
        apply(runtime, WorkspaceDocumentOperation("warp_bezier_anchor", buildJsonObject {
            put("target", "warp:control"); putJsonObject("coordinate") { key.forEach { (id, value) -> put(id, value) } }
            put("row", 1); put("column", 1); put("x", anchor.x + 1f); put("y", anchor.y + 2f)
        }))
        val after = runtime.capture(); val archive = temporary.resolve("warp.psd2live")
        ProjectRepository().save(ProjectSaveCapture(after.projectId, runtime.history(), JsonObject(emptyMap()), null, WorkspaceStore(temporary.resolve("store"))), archive)
        val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
        assertWorld(after.model.rig.puppet, reopened.rig.puppet)
        assertTrue(RigBezierJournal.read(reopened.rig.puppet, reopened.config.rigEdits, "control", key).persisted)
        val files = PSD2LivePipeline().run(after.document.source, "warp", temporary.resolve("export"), after.document.config().copy(exportMoc3 = true)).exportedFiles
        val cmoBytes = Files.readAllBytes(files.single { it.path.toString().endsWith(".cmo3") }.path)
        val cmo = Cmo3ModelImport.read(cmoBytes).puppet
        val moc = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".moc3") }.path)), null)
        assertWorld(after.model.rig.puppet, cmo); assertWorld(after.model.rig.puppet, moc)
        val root = Cmo3.read(cmoBytes).root as CModelSource
        val exported = Cmo3Import.elementsOf((root.deformerSourceSet as CDeformerSourceSet)._sources).filterIsInstance<CWarpDeformerSource>().single { Cmo3Import.idStrOf(it.id) == "control" }
        val extension = Cmo3Import.elementsOf(exported._extensions).filterIsInstance<CWarpDeformerBezierExtension>().single()
        assertEquals(3, extension.bezierRow); assertEquals(4, extension.bezierCol)
        assertEquals(3 to 4, BezierWarp.importedDivisions(RigEditOverlay(importedCmo3 = java.util.Base64.getEncoder().encodeToString(cmoBytes)), "control"))
        fun png(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "controls", emptyMap(),
            model.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 64f, 96f)),
            WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
        val image = png(after.model); assertContentEquals(image, png(reopened))
        val decoded = javax.imageio.ImageIO.read(image.inputStream())
        assertTrue((0 until decoded.height).sumOf { y -> (0 until decoded.width).count { x -> decoded.getRGB(x, y) ushr 24 > 0 } } > 50)
        val output = Path.of("build/warp-control-visual"); Files.createDirectories(output)
        Files.write(output.resolve("before.png"), image); Files.write(output.resolve("reopened.png"), png(reopened))
    }
}
