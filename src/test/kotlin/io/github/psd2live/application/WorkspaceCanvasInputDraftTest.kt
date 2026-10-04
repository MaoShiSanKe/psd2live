package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.umamo.edit.MeshRefinementOps
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import kotlin.test.*

class WorkspaceCanvasInputDraftTest {
    private val builder = WorkspacePreviewBuilder()
    private val scope = CanvasDraftScope("draft", 1, "workspace", "canvas")

    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        fun layer(id: String, order: Int) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster, true, order,
            LayerBounds(8, 8, 48, 48), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(48, 48, ByteArray(48 * 48 * 4) { if (it % 4 == 3) -1 else (80 + order * 30).toByte() }), null, null, false)
        val source = WorkspaceSourceArt(64, 64, listOf(layer("a", 1), layer("b", 2)), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshOnly = true, generateDeformers = false, generatePhysics = false, exportMoc3 = false)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), source.layers.associate { it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS) },
            emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "draft", document, builder.build(document))
        return runtime
    }

    private fun knifeDraft(capture: WorkspaceCapture<RigPreviewModel>): WorkspaceCanvasInputDraft<MeshRefinementOps.KnifeAnchor, FloatArray> {
        val drawable = capture.model.rig.puppet.drawables.first()
        val positions = drawable.mesh!!.positions.copyOf()
        val first = (0 until positions.size / 2).minBy { positions[it * 2] }
        return WorkspaceCanvasInputDraft(capture.state, scope, capture.model, emptyMap(), drawable.id.raw, positions,
            listOf(MeshRefinementOps.KnifeAnchor.AtVertex(first)))
    }

    private fun farthestAnchor(draft: WorkspaceCanvasInputDraft<MeshRefinementOps.KnifeAnchor, FloatArray>) =
        MeshRefinementOps.KnifeAnchor.AtVertex((0 until draft.frame.size / 2).maxBy { draft.frame[it * 2] })

    private fun knife(draft: WorkspaceCanvasInputDraft<MeshRefinementOps.KnifeAnchor, FloatArray>) = JsonArray(listOf(buildJsonObject {
        put("op", "canvas_topology"); put("id", draft.targetId); put("action", "knife")
        put("vertices", JsonArray(emptyList())); put("anchors", CanvasTopology.encodeAnchors(draft.inputs))
    }))

    private suspend fun write(runtime: WorkspaceRuntime<RigPreviewModel>, submit: CanvasDraftSubmit.Write): String? = try {
        WorkspaceDocumentCommands(runtime).executeJournal(runtime.capture().projectId, submit.state, "Knife", submit.edits, MutationAuthor.USER)
        null
    } catch (conflict: WorkspaceConflict) { conflict.message }

    @Test
    fun laterInputsAndConfirmKeepTheFirstInputsCaptureAcrossAnExternalEdit() = runBlocking {
        val runtime = fixture()
        val start = runtime.capture()
        val draft = knifeDraft(start)
        val frame = draft.frame.copyOf()
        val external = WorkspaceDocumentCommands(runtime).execute(start.projectId, start.state, "External", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", 0); put("max", 1) })
        ), MutationAuthor.AGENT).capture
        assertNotEquals(start.state, external.state)
        assertTrue(draft.append(farthestAnchor(draft)))
        assertEquals(start.state, draft.state, "A later click must not adopt the newer state")
        assertSame(start.model, draft.model)
        assertContentEquals(frame, draft.frame)
        assertEquals(2, draft.inputs.size)

        val history = runtime.history()
        val submit = assertIs<CanvasDraftSubmit.Write>(draft.submit(scope, knife(draft)))
        assertEquals(start.state, submit.state, "Confirm writes against the start capture, never a fresh one")
        assertNotNull(submit.preview.drawables.first().mesh)
        assertFalse(draft.open)
        assertFalse(draft.append(farthestAnchor(draft)), "No input lands while the write is in flight")

        val failure = write(runtime, submit)
        assertNotNull(failure)
        assertFalse(draft.settle(failure))
        assertEquals(external, runtime.capture()); assertEquals(history, runtime.history())
        assertTrue(draft.open, "A conflict leaves the draft for the user to inspect and cancel")
        assertEquals(failure, draft.failure)
        assertEquals(2, draft.inputs.size)
        assertEquals(start.state, draft.state)

        draft.cancel()
        assertTrue(draft.closed); assertTrue(draft.inputs.isEmpty())
        assertEquals(external, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test
    fun aDraftFromAnotherCanvasOrLoadIsRejectedWithoutWriting() = runBlocking {
        val runtime = fixture()
        val draft = knifeDraft(runtime.capture())
        draft.append(farthestAnchor(draft))
        for (other in listOf(scope.copy(canvasId = "other"), scope.copy(openGeneration = 2), scope.copy(projectId = "reopened"))) {
            val rejected = assertIs<CanvasDraftSubmit.Rejected>(draft.submit(other, knife(draft)))
            assertEquals(rejected.failure, draft.failure)
            assertTrue(draft.open); assertEquals(2, draft.inputs.size)
        }
        assertFailsWith<IllegalStateException> { draft.settle(null) }
        assertTrue(draft.dropLast()); assertNull(draft.failure); assertEquals(1, draft.inputs.size)
    }

    @Test
    fun aSuccessfulConfirmCommitsOneNodeThenClosesTheDraft() = runBlocking {
        val runtime = fixture()
        val start = runtime.capture()
        val draft = knifeDraft(start)
        draft.append(farthestAnchor(draft))
        val mesh = start.model.rig.puppet.drawables.first { it.id.raw == draft.targetId }.mesh!!
        assertNotNull(CanvasTopology.build(mesh, "knife", emptySet(), draft.inputs), "Synthetic cut must apply")
        val history = runtime.history().selections.size

        val submit = assertIs<CanvasDraftSubmit.Write>(draft.submit(scope, knife(draft)))
        assertNull(write(runtime, submit))
        assertTrue(draft.settle(null))
        assertTrue(draft.closed); assertTrue(draft.inputs.isEmpty()); assertNull(draft.failure)

        val after = runtime.capture()
        assertEquals(history + 1, runtime.history().selections.size, "One confirm, one history node")
        assertEquals(start.historyHead, runtime.history().selections.last().node.parentId)
        val cut = after.model.rig.puppet.drawables.first { it.id.raw == draft.targetId }.mesh!!
        assertFalse(cut.indices.contentEquals(mesh.indices), "The cut reached the committed mesh")
        assertContentEquals(submit.preview.drawables.first { it.id.raw == draft.targetId }.mesh!!.indices, cut.indices)
        draft.cancel()
        assertEquals(after, runtime.capture())
    }
}
