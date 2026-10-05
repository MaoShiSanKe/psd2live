package io.github.psd2live.ui.state

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspaceSourceImportPortTest {
    @TempDir lateinit var temporary: Path
    @Test fun guiImageImportUsesTheSourcePortWithTheCapturedStateAndUserAuthor() = runBlocking<Unit> {
        val runtime = WorkspaceRuntime<io.github.psd2live.core.RigPreviewModel>({ WorkspacePreviewBuilder().build(it) })
        val capture = simulationFixture(runtime)
        val entered = CompletableDeferred<Triple<String, List<Path>, WorkspaceExecution>>()
        val release = CompletableDeferred<Unit>()
        val host = object : WorkspaceBackendStub() {
            override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
            override suspend fun importImages(state: String, paths: List<Path>, parentDeformerId: String?): WorkspaceMutationResult {
                assertEquals("parent", parentDeformerId)
                entered.complete(Triple(state, paths, requireNotNull(currentCoroutineContext()[WorkspaceExecution])))
                release.await()
                throw IllegalStateException("Injected image rejection")
            }
        }
        PSD2LiveViewModel().use { vm ->
            vm.attachWorkspace(host)
            val expected = host.snapshot()
            val image = temporary.resolve("does-not-need-ui-decoding.png").toFile()
            val pending = async { runCatching { vm.importImagesNow(listOf(image), "parent", expected) } }
            val (state, paths, execution) = withTimeout(5000) { entered.await() }
            assertEquals(capture.state, state); assertEquals(listOf(image.toPath()), paths)
            assertEquals(capture.projectId, execution.projectId); assertEquals(state, execution.state)
            assertEquals(MutationAuthor.USER, execution.author); assertFalse(pending.isCompleted)
            release.complete(Unit)
            assertEquals("Injected image rejection", pending.await().exceptionOrNull()!!.message)
        }
    }
    @Test fun guiAnalysisUsesTheSourcePortAndTrustedOpeningStateWithoutBlockingItsCaller() = runBlocking {
        val (_, psd) = writeSourceImportFixture(temporary)
        val entered = CompletableDeferred<Triple<String, Boolean, WorkspaceExecution>>()
        val release = CompletableDeferred<Unit>()
        val host = object : WorkspaceBackendStub() {
            override fun snapshot() = WorkspaceProjectSnapshot(null, "empty", null, false, null, null, null, false, "Ready", null,
                emptyList(), emptyList(), state = "empty:0")
            override suspend fun awaitEditorDrafts() = Unit
            override suspend fun importPsd(path: String, discardUnsaved: Boolean): WorkspaceMutationResult {
                entered.complete(Triple(path, discardUnsaved, requireNotNull(currentCoroutineContext()[WorkspaceExecution])))
                release.await()
                throw IllegalStateException("Injected source rejection")
            }
        }
        PSD2LiveViewModel().use { vm ->
            vm.attachWorkspace(host)
            vm.setInputPath(psd.toString())
            vm.analyze(discardUnsaved = true)
            val (path, discard, execution) = withTimeout(5000) { entered.await() }
            assertEquals(psd.toString(), path)
            assertTrue(discard)
            assertEquals(MutationAuthor.USER, execution.author)
            assertEquals("empty:0", execution.state)
            assertNull(execution.projectId)
            assertTrue(vm.state.value.isAnalyzing)
            assertFalse(release.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { vm.state.first { !it.isAnalyzing } }
            assertEquals("Injected source rejection", vm.state.value.errorMessage)
        }
    }
}
