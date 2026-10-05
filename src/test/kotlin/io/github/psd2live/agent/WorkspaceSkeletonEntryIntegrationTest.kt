package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceSkeletonEntryIntegrationTest {
    @TempDir lateinit var temporary: Path

    private suspend fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace, JsonObject) -> Unit) {
        val png = temporary.resolve("body.png")
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 8) for (x in 0 until 8) image.setRGB(x, y, 0xff7799bb.toInt())
        ImageIO.write(image, "png", png.toFile())
        val artwork = buildJsonObject {
            put("width", 8); put("height", 8)
            putJsonArray("layers") { add(buildJsonObject {
                put("path", png.toString()); put("name", "body"); put("role", "topwear")
            }) }
        }
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, atlasSize = 256, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { backend ->
                vm.attachWorkspace(backend)
                backend.createArtwork(artwork)
                assertTrue(backend.proposeSkeleton().bones.isNotEmpty())
                action(vm, backend, artwork)
            }
        }
    }

    private class PausedSkeleton(private val backend: DesktopWorkspace) : WorkspaceBackend by backend {
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        override suspend fun applyDocumentEdits(state: String, summary: String,
            edits: List<WorkspaceDocumentOperation>, author: MutationAuthor): WorkspaceMutationResult {
            if (edits.any { it.operation == "skeleton_put" }) {
                entered.complete(state)
                release.await()
                return try { backend.applyDocumentEdits(state, summary, edits, author) }
                finally { finished.complete(Unit) }
            }
            return backend.applyDocumentEdits(state, summary, edits, author)
        }
    }

    private suspend fun settled(vm: PSD2LiveViewModel, paused: PausedSkeleton) {
        withTimeout(10000) { paused.finished.await() }
        withTimeout(10000) { while (vm.state.value.workspaceEditBusy) delay(10) }
        // The entry continuation runs on the same dispatcher after the edit releases its busy flag.
        withContext(Dispatchers.Main) { }
    }

    @Test fun firstEditEntryWaitsForTheCommittedArmatureBeforeOpeningItsDraft() = runBlocking<Unit> {
        fixture { vm, backend, _ ->
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val paused = PausedSkeleton(backend)
            vm.attachWorkspace(paused)
            val before = backend.snapshot().state
            editor.beginSkeletonEdit()
            assertEquals(before, withTimeout(10000) { paused.entered.await() })
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertNull(editor.skeletonDraft)
            assertNull(backend.skeletonSpec())
            paused.release.complete(Unit)
            withTimeout(10000) { while (editor.skeletonDraft == null || vm.state.value.workspaceEditBusy) delay(10) }
            assertEquals(EditHierarchyMode.SKELETON, editor.hierarchyMode)
            assertEquals(CanvasTool.SKELETON_EDIT, editor.tool)
            assertTrue(editor.skeletonSelected)
            assertEquals(backend.skeletonSpec(), editor.skeletonDraft)
            assertEquals(2, backend.history().nodes.size)
            assertEquals("user", backend.history().nodes.last().actor)
        }
    }

    @Test fun externalEditRejectsTheCapturedEntryInsteadOfRecapturingItsState() = runBlocking<Unit> {
        fixture { vm, backend, _ ->
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val paused = PausedSkeleton(backend)
            vm.attachWorkspace(paused)
            editor.setHierarchyMode(EditHierarchyMode.SKELETON)
            val expected = withTimeout(10000) { paused.entered.await() }
            backend.updateProjectSettings(expected, buildJsonObject { put("texturePadding", 3) })
            val afterExternal = backend.snapshot().state
            val history = backend.history()
            paused.release.complete(Unit)
            withTimeout(10000) { while (editor.error == null || vm.state.value.workspaceEditBusy) delay(10) }
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertNull(editor.committedSkeleton)
            assertNull(editor.skeletonDraft)
            assertEquals(afterExternal, backend.snapshot().state)
            assertEquals(history, backend.history())
            assertTrue(editor.error!!.contains(expected))
        }
    }

    @Test fun choosingAnotherModeWhilePreparingDoesNotEnterSkeletonAfterCommit() = runBlocking<Unit> {
        fixture { vm, backend, _ ->
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val paused = PausedSkeleton(backend)
            vm.attachWorkspace(paused)
            editor.beginSkeletonEdit()
            withTimeout(10000) { paused.entered.await() }
            editor.setHierarchyMode(EditHierarchyMode.SELECT)
            paused.release.complete(Unit)
            settled(vm, paused)
            assertNotNull(backend.skeletonSpec())
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertNull(editor.skeletonDraft)
            assertFalse(editor.skeletonSelected)
        }
    }

    @Test fun choosingAnotherToolWhilePreparingDoesNotRearmTheSkeletonTool() = runBlocking<Unit> {
        fixture { vm, backend, _ ->
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val paused = PausedSkeleton(backend)
            vm.attachWorkspace(paused)
            editor.beginSkeletonEdit()
            withTimeout(10000) { paused.entered.await() }
            editor.activateTool(CanvasTool.SELECT)
            paused.release.complete(Unit)
            settled(vm, paused)
            assertNotNull(backend.skeletonSpec())
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertEquals(CanvasTool.SELECT, editor.tool)
            assertNull(editor.skeletonDraft)
            assertFalse(editor.skeletonSelected)
        }
    }

    @Test fun replacingTheProjectCannotContinueTheOldCanvasEntry() = runBlocking<Unit> {
        fixture { vm, backend, artwork ->
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val paused = PausedSkeleton(backend)
            vm.attachWorkspace(paused)
            editor.beginSkeletonEdit()
            withTimeout(10000) { paused.entered.await() }
            val firstProject = backend.snapshot().projectId
            backend.createArtwork(JsonObject(artwork + ("discard_unsaved" to JsonPrimitive(true))))
            val replacement = backend.snapshot()
            assertNotEquals(firstProject, replacement.projectId)
            paused.release.complete(Unit)
            settled(vm, paused)
            assertEquals(replacement.state, backend.snapshot().state)
            assertNull(backend.skeletonSpec())
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertNull(editor.skeletonDraft)
            assertNull(editor.error)
            assertEquals(EditHierarchyMode.SELECT, vm.canvasEditorFor(vm.state.value.activeCanvas.id).hierarchyMode)
        }
    }

    @Test fun restoringAnotherWorkspaceDoesNotEnterThePreviousWorkspaceCanvas() = runBlocking<Unit> {
        fixture { vm, backend, _ ->
            val original = vm.state.value.activeWorkspace.id
            val second = vm.addWorkspace()
            vm.setActiveWorkspace(original)
            val editor = vm.canvasEditorFor(vm.state.value.activeCanvas.id)
            val paused = PausedSkeleton(backend)
            vm.attachWorkspace(paused)
            editor.beginSkeletonEdit()
            withTimeout(10000) { paused.entered.await() }
            // A host restoration can replace presentation while an application edit is preparing.
            vm.setStateForTest(vm.state.value.copy(activeWorkspaceId = second))
            paused.release.complete(Unit)
            settled(vm, paused)
            assertEquals(second, vm.state.value.activeWorkspace.id)
            assertNotNull(backend.skeletonSpec())
            assertEquals(EditHierarchyMode.SELECT, editor.hierarchyMode)
            assertNull(editor.skeletonDraft)
            assertNull(editor.error)
            assertEquals(EditHierarchyMode.SELECT, vm.canvasEditorFor(vm.state.value.activeCanvas.id).hierarchyMode)
        }
    }
}
