package io.github.psd2live.ui.state

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.test.*

class WorkspaceEditorDraftPortTest {
    private fun preview(): RigPreviewModel {
        val source = WorkspaceSourceArt(16, 16, listOf(WorkspaceSourceLayer(
            LayerId("art"), "Artwork", "", SourceLayerKind.Raster, true, 0, LayerBounds(0, 0, 16, 16),
            1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(16, 16, ByteArray(16 * 16 * 4) { -1 }),
            null, null, true)), emptyList())
        return PSD2LivePipeline().buildPreview(source, PipelineConfig(meshOnly = true, atlasSize = 256))
    }

    private class Host(val original: WorkspaceDocument) : WorkspaceBackendStub() {
        val completion = CompletableDeferred<WorkspaceMutationResult>()
        var token = "load:1:0"
        var submitted: Triple<String, WorkspaceDocument, MutationAuthor>? = null
        var saveContext: WorkspaceExecution? = null
        val paintStarted = CompletableDeferred<Unit>()
        var paintSubmission: Pair<String, WorkspacePaintRaster>? = null
        var paintContext: WorkspaceExecution? = null
        override fun snapshot() = WorkspaceProjectSnapshot("project", WorkspaceRevisions.of(original), "head", true,
            "Artwork", 16, 16, false, "Ready", null, emptyList(), emptyList(), state = token)
        override fun submitEditorDraft(projectId: String, state: String, document: WorkspaceDocument,
                                       summary: String, author: MutationAuthor): Deferred<WorkspaceMutationResult> {
            assertEquals("project", projectId)
            submitted = Triple(state, document, author)
            return completion
        }
        override suspend fun awaitEditorDrafts() { if (submitted != null) completion.await() }
        override suspend fun commitPaintRaster(state: String, request: WorkspacePaintRaster): WorkspaceMutationResult {
            paintSubmission = state to request
            paintContext = requireNotNull(currentCoroutineContext()[WorkspaceExecution])
            paintStarted.complete(Unit)
            return completion.await()
        }
        override suspend fun saveProjectAt(path: java.nio.file.Path?): WorkspaceMutationResult {
            saveContext = requireNotNull(currentCoroutineContext()[WorkspaceExecution])
            return WorkspaceMutationResult("saved", "revision", summary = "Saved", state = token, projectId = "project")
        }
    }

    private suspend fun fixture(action: suspend (PSD2LiveViewModel, Host) -> Unit) {
        PSD2LiveViewModel().use { vm ->
            val preview = preview()
            vm.setStateForTest(vm.state.value.copy(projectId = "project", analysis = preview.analysis,
                previewModel = preview, atlasSize = 256))
            val host = Host(WorkspaceStateCodec.document(vm.state.value))
            vm.attachWorkspace(host)
            action(vm, host)
        }
    }

    @Test fun fieldCompletionReturnsWhileItsApplicationCommitIsPendingAndUsesTheOpeningState() = runBlocking {
        fixture { vm, host ->
            vm.beginEditorField("atlas")
            vm.setAtlasSize(512)
            vm.setAtlasSize(1024)
            host.token = "load:1:foreign"
            vm.endEditorField("atlas")
            val submitted = assertNotNull(host.submitted)
            assertFalse(host.completion.isCompleted)
            assertEquals("load:1:0", submitted.first)
            assertEquals(1024, submitted.second.settings.getValue("atlasSize").jsonPrimitive.int)
            assertEquals(MutationAuthor.USER, submitted.third)
            assertTrue(vm.state.value.projectDirty)
            assertTrue(vm.state.value.editorDraftBusy)
            host.completion.completeExceptionally(WorkspaceConflict(submitted.first, host.token))
            withTimeout(5000) { vm.state.first { !it.editorDraftBusy } }
            assertNotNull(vm.state.value.errorMessage)
            assertEquals(1024, vm.state.value.atlasSize, "A rejected completion keeps the visible draft")
        }
    }

    @Test fun saveWaitsForCompletedFieldsBeforeCapturingItsTrustedExpectation() = runBlocking {
        fixture { vm, host ->
            vm.beginEditorField("atlas")
            vm.setAtlasSize(512)
            vm.endEditorField("atlas")
            val save = async(start = CoroutineStart.UNDISPATCHED) { vm.saveProjectNow() }
            assertNull(host.saveContext)
            assertFalse(save.isCompleted)
            host.token = "load:1:1"
            host.completion.complete(WorkspaceMutationResult("edited", "revision", summary = "Edited",
                state = host.token, projectId = "project"))
            assertEquals("saved", save.await())
            assertEquals("load:1:1", host.saveContext?.state)
            assertEquals(MutationAuthor.USER, host.saveContext?.author)
        }
    }

    @Test fun generationInputIsCapturedFromExplicitStateAndParticipatesInGuiProjectionValidation() {
        PSD2LiveViewModel().use { vm ->
            val preview = preview()
            val source = preview.analysis.source
            val state = vm.state.value.copy(projectId = "generation", analysis = preview.analysis,
                previewModel = preview.copy(config = preview.config.copy(generationSource = null)),
                generationSource = source, atlasSize = 256)
            vm.setStateForTest(state)
            assertSame(source, WorkspaceStateCodec.document(state).generationSource)
            assertSame(source, state.buildConfig().generationSource)
            fun project(expected: SourceArt?) = vm.applyAgentWorkspacePreview(preview,
                expectedSource = source, expectedLayerVisibility = state.layerVisibility,
                expectedDeletedLayerIds = state.deletedLayerIds, expectedLayerOverrides = state.layerOverrides,
                expectedParentOverrides = state.parentOverrides, expectedRigEdits = state.rigEdits,
                expectedGenerationSource = expected, layerVisibility = state.layerVisibility,
                deletedLayerIds = state.deletedLayerIds, layerOverrides = state.layerOverrides,
                parentOverrides = state.parentOverrides, rigEdits = state.rigEdits, status = "Projected",
                generationSource = null)
            assertFalse(project(null))
            assertSame(state, vm.state.value)
            assertTrue(project(source))
            assertNull(vm.state.value.generationSource)
        }
    }

    @Test fun paintSubmitsFrozenPixelsWithTheGestureOpeningStateAndResumesOnlyAfterCommit() = runBlocking {
        fixture { vm, host ->
            val expected = assertNotNull(vm.capturePaintExpectation())
            val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            image.setRGB(4, 5, 0xff223344.toInt())
            val request = WorkspacePaintRaster.capture("art", image, rebuildMesh = true)
            image.setRGB(4, 5, 0xffff0000.toInt())
            host.token = "load:1:foreign"
            val resumed = CompletableDeferred<Unit>()
            vm.savePaintRaster(request, expected, "Paint", onCommitted = { resumed.complete(Unit) })
            withTimeout(5000) { host.paintStarted.await() }
            assertTrue(vm.state.value.canvasEditBusy)
            assertFalse(resumed.isCompleted)
            assertEquals("load:1:0", host.paintSubmission?.first)
            assertEquals("project", host.paintContext?.projectId)
            assertEquals("load:1:0", host.paintContext?.state)
            assertEquals(MutationAuthor.USER, host.paintContext?.author)
            val offset = (5 * 16 + 4) * 4
            assertEquals(listOf(34, 51, 68, 255), request.raster.rgba.slice(offset..offset + 3).map { it.toInt() and 255 })
            host.completion.complete(WorkspaceMutationResult("painted", "revision", summary = "Painted",
                state = "load:1:1", projectId = "project"))
            withTimeout(5000) { resumed.await(); vm.state.first { !it.canvasEditBusy } }
            assertNull(vm.state.value.errorMessage)
        }
    }

    @Test fun rejectedPaintLeavesTheVisibleModelAndDoesNotResumeTheGesture() = runBlocking {
        fixture { vm, host ->
            val original = vm.state.value.previewModel
            val expected = assertNotNull(vm.capturePaintExpectation())
            val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            var resumed = false
            vm.savePaintRaster(WorkspacePaintRaster.capture("art", image, false), expected, "Clear") { resumed = true }
            withTimeout(5000) { host.paintStarted.await() }
            host.completion.completeExceptionally(WorkspaceConflict(expected.state, "load:2:0"))
            withTimeout(5000) { vm.state.first { !it.canvasEditBusy } }
            assertFalse(resumed)
            assertSame(original, vm.state.value.previewModel)
            assertNotNull(vm.state.value.errorMessage)
        }
    }
}
