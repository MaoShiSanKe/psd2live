package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.SourceArt
import org.umamo.format.psd.PsdReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** New source projects use one isolated read/rebuild/install boundary in both adapters. */
internal class WorkspaceSourceImporter(
    private val runtime: WorkspaceRuntime<RigPreviewModel>,
    private val build: suspend (WorkspaceDocument) -> RigPreviewModel = { WorkspacePreviewBuilder().build(it) },
    private val readPsd: suspend (Path) -> SourceArt = { path -> runInterruptible(Dispatchers.IO) {
        val bytes = Files.readAllBytes(path)
        require(PsdReader.matches(bytes)) { "Invalid PSD file" }
        PsdReader.read(bytes)
    } },
    private val readArtwork: suspend (JsonObject) -> Pair<WorkspaceSourceArt, Map<String, LayerClassificationOverride>> = {
        runInterruptible(Dispatchers.IO) { sourceArtwork(it) }
    },
    private val newProjectId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun importPsd(path: Path, projectId: String?, state: String, discardUnsaved: Boolean = false,
                         initialConfig: PipelineConfig = PipelineConfig(),
                         project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> {
        return install(projectId, state, discardUnsaved, initialConfig, "Imported PSD ${path.fileName}",
            prepare = {
                require(path.isAbsolute && Files.isRegularFile(path) && path.fileName.toString().endsWith(".psd", true)) { "Provide an absolute local PSD path" }
                readPsd(path) to emptyMap()
            }, project = project)
    }

    suspend fun createArtwork(arguments: JsonObject, projectId: String?, state: String, discardUnsaved: Boolean = false,
                              initialConfig: PipelineConfig = PipelineConfig(),
                              project: (WorkspaceDocument, RigPreviewModel, String) -> Unit = { _, _, _ -> }): WorkspaceCommit<RigPreviewModel> =
        install(projectId, state, discardUnsaved, initialConfig, "Created source artwork", { readArtwork(arguments) }, project)

    private suspend fun install(projectId: String?, state: String, discardUnsaved: Boolean, initialConfig: PipelineConfig,
                                summary: String, prepare: suspend () -> Pair<SourceArt, Map<String, LayerClassificationOverride>>,
                                project: (WorkspaceDocument, RigPreviewModel, String) -> Unit): WorkspaceCommit<RigPreviewModel> {
        val before = runtime.state.value
        currentCoroutineContext().ensureActive()
        // Capture before source I/O. Another edit or reload cannot be hidden by taking a newer token.
        WorkspaceExecution(projectId, state, MutationAuthor.AGENT).check(before.capture?.projectId, before.state)
        if (before.capture?.dirty == true && !discardUnsaved) throw WorkspaceUnsavedChanges()
        suspend fun progress(value: Float, message: String) {
            currentCoroutineContext().ensureActive()
            currentCoroutineContext()[WorkspaceJobContext]?.progress(value, message)
        }
        progress(0.1f, "Reading source artwork")
        val (source, overrides) = prepare()
        progress(0.4f, "Preparing new source document")
        val config = initialConfig.copy(layerVisibility = emptyMap(), deletedLayerIds = emptySet(),
            layerOverrides = overrides, parentOverrides = emptyMap(), drawOrderOverrides = emptyMap(), meshOverrides = emptyMap(),
            rigEdits = RigEditOverlay.Empty, generationSource = null, meshSource = null, hairSimulationFront = false, hairSimulationBack = false)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), overrides, emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(config))
        progress(0.6f, "Rebuilding new source project")
        val preview = build(document)
        val prepared = document.copy(rigEdits = preview.config.rigEdits, settings = WorkspaceSettingsCodec.encode(preview.config))
        progress(0.9f, "Installing new source project")
        val id = newProjectId()
        val installed = runtime.install(state, id, prepared, preview,
            auxiliary = JsonObject(WorkspaceAuxiliaryCodec.encode(WorkspaceAuxiliaryData()) +
                ("assetCatalog" to WorkspaceAssetCatalog().encode())), discardUnsaved = discardUnsaved, dirty = true) {
            project(prepared, preview, id)
        }
        val result = WorkspaceCommit(installed, true)
        currentCoroutineContext()[WorkspaceJobCompletion]?.committed(WorkspaceOperationOutput(result.mutation(summary).sourceResult()))
        return result
    }
}
