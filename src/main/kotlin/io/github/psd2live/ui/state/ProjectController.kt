package io.github.psd2live.ui.state

import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.project.ProjectRepository
import io.github.psd2live.project.ProjectSaveCapture
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import io.github.psd2live.application.WorkspaceJobCompletion
import io.github.psd2live.application.WorkspaceOperationOutput
import io.github.psd2live.application.lifecycleResult
import kotlinx.coroutines.currentCoroutineContext

/** Desktop lifecycle projection; all archive reading/writing is UI-independent. */
internal class ProjectController(private val viewModel: PSD2LiveViewModel) {
    private val repository = ProjectRepository()
    private val saves = Mutex()

    suspend fun save(workspace: DesktopWorkspace, path: Path, actor: String = "user"): String =
        saveCapture(workspace, path, actor).history.headNodeId

    suspend fun saveCapture(workspace: DesktopWorkspace, path: Path, actor: String = "user"): DesktopWorkspace.ProjectCapture {
        var capturedState = viewModel.state.value
        val committed = java.util.concurrent.atomic.AtomicBoolean()
        val completion = currentCoroutineContext()[WorkspaceJobCompletion]
        viewModel.projectSaveStarted()
        try {
            val capture = workspace.captureProject("Save project", actor)
            capturedState = capture.uiState
            return saves.withLock {
                workspace.flushProjectPersistence()
                val state = capture.uiState
                val originalPath = state.loadedInputPath ?: state.inputPath
                repository.save(ProjectSaveCapture(capture.projectId, capture.history,
                    WorkspaceStateCodec.encode(state), originalPath.takeIf(String::isNotBlank)?.let(Path::of),
                    capture.store, capture.spatial, capture.tasks, capture.auxiliary), path) {
                    committed.set(true)
                    completion?.committed(WorkspaceOperationOutput(workspace.savedResult(capture).lifecycleResult()))
                    workspace.projectSaved(capture)
                    viewModel.projectSaveFinished(path, capture.history.headNodeId, state)
                }
                capture
            }
        } catch (failure: Exception) {
            if (!committed.get()) viewModel.projectSaveFailed(failure, capturedState)
            throw failure
        }
    }

    suspend fun open(workspace: DesktopWorkspace, path: Path, discardUnsaved: Boolean = true) = saves.withLock {
        val expected = workspace.captureProjectOpen(discardUnsaved)
        val completion = currentCoroutineContext()[WorkspaceJobCompletion]
        repository.open(path).use { opened ->
            val state = WorkspaceStateCodec.decode(opened.presentation)
            val installed = workspace.installProject(opened.projectId, opened.file, opened.source, state,
                opened.history, opened.store, expected, discardUnsaved, opened.presentation)
            workspace.rememberProjectDirectory(opened.transferDirectory())
            completion?.committed(WorkspaceOperationOutput(workspace.openedResult(installed).lifecycleResult()))
            viewModel.refreshWorkspaceRenderer(installed.model)
            installed
        }
    }
}
