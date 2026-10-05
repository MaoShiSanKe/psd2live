package io.github.psd2live.ui.state

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.*

class WorkspaceLifecyclePortTest {
    @Test fun guiLifecycleUsesTheApplicationPortAndTrustedUserExpectation() = runBlocking {
        val calls = mutableListOf<Triple<Path?, Boolean?, WorkspaceExecution>>()
        val workspace = object : WorkspaceBackendStub() {
            override suspend fun awaitEditorDrafts() = Unit
            var token = "load:1:0"
            override fun snapshot() = WorkspaceProjectSnapshot("project", "revision", "head", true,
                "Artwork", 16, 16, false, "Ready", null, emptyList(), emptyList(), state = token)
            override suspend fun saveProjectAt(path: Path?): WorkspaceMutationResult {
                calls += Triple(path, null, requireNotNull(currentCoroutineContext()[WorkspaceExecution]))
                token = "load:1:1"
                return WorkspaceMutationResult("head", "revision", summary = "Saved", state = token, projectId = "project")
            }
            override suspend fun openProjectAt(path: Path, discardUnsaved: Boolean): WorkspaceMutationResult {
                calls += Triple(path, discardUnsaved, requireNotNull(currentCoroutineContext()[WorkspaceExecution]))
                token = "load:2:0"
                return WorkspaceMutationResult("head", "revision", summary = "Opened", state = token, projectId = "project")
            }
        }
        PSD2LiveViewModel().use { vm ->
            vm.attachWorkspace(workspace)
            val path = Path.of("build/lifecycle-port.psd2live")
            assertEquals("head", vm.saveProjectNow(path))
            assertEquals("load:2:0", vm.openProjectNow(path).state)
            assertEquals(listOf(path.toAbsolutePath().normalize(), path.toAbsolutePath().normalize()), calls.map { it.first })
            assertEquals(listOf(null, false), calls.map { it.second })
            assertEquals(listOf("load:1:0", "load:1:1"), calls.map { it.third.state })
            assertTrue(calls.all { it.third.author == MutationAuthor.USER && it.third.projectId == "project" })
        }
    }
}
