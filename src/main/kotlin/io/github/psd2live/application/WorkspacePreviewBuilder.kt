package io.github.psd2live.application

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.core.RigGenerationMigration
import io.github.psd2live.core.VertexGroupJournal
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.render.restMeshesToCanvasSpace
import io.github.psd2live.project.WorkspaceDocument
import io.github.psd2live.project.config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Rebuild and ordered replay are application work; adapters only project the resulting model. */
internal class WorkspacePreviewBuilder {
    private val pipeline = PSD2LivePipeline()

    private suspend fun progress(start: Float, end: Float): ProgressListener {
        val context = currentCoroutineContext()
        return ProgressListener { message, fraction ->
            context.ensureActive()
            if (context[WorkspaceGenerationJobExecution] != null || context[WorkspacePartitionJobExecution] != null || context[WorkspaceWarpJobExecution] != null || context[WorkspaceWarpControlJobExecution] != null || context[WorkspaceLayerJobExecution] != null)
                context[WorkspaceJobContext]?.progress(start + (end - start) * fraction.toFloat().coerceIn(0f, 1f), message)
        }
    }

    /** Persist the generation baseline and ordered topology migration together with the settings. */
    suspend fun normalizeMeshEdits(document: WorkspaceDocument, current: RigPreviewModel): WorkspaceDocument {
        val progress = progress(0.3f, 0.6f)
        return runInterruptible(Dispatchers.Default) {
            val decoded = document.config()
            val config = if ("drawOrderOverrides" in document.settings) decoded
                else decoded.copy(drawOrderOverrides = current.config.drawOrderOverrides)
            if (current.config.rigEdits.skeleton != config.rigEdits.skeleton &&
                (current.rig.puppet.vertexGroups.isNotEmpty() || config.rigEdits.authoringJournal.any {
                    it["op"]?.jsonPrimitive?.contentOrNull in setOf(VertexGroupJournal.PUT, VertexGroupJournal.DELETE)
                })) {
                // Skeleton refinement changes generated vertex indices before the journal replays.
                // Replay topology first, then carry the final painted groups onto the rebuilt mesh.
                val journal = config.rigEdits.authoringJournal.filterNot {
                    it["op"]?.jsonPrimitive?.contentOrNull in setOf(VertexGroupJournal.PUT, VertexGroupJournal.DELETE)
                }
                val overlay = config.rigEdits.copy(authoringJournal = journal)
                val rebuilt = pipeline.buildPreview(document.source, config.copy(rigEdits = overlay), progress)
                // Bone binding can change a mesh's parent space. Resample in a common neutral canvas
                // space so the weights stay on the artwork instead of moving with that local frame.
                val previous = restMeshesToCanvasSpace(current.rig.puppet)
                val replacement = restMeshesToCanvasSpace(rebuilt.rig.puppet)
                val groups = previous.vertexGroups.map { group ->
                    val oldMesh = requireNotNull(previous.drawables.single { it.id == group.drawableId }.mesh)
                    val newMesh = requireNotNull(replacement.drawables.single { it.id == group.drawableId }.mesh)
                    VertexGroupJournal.resample(group, oldMesh, newMesh)
                }
                return@runInterruptible document.copy(rigEdits = overlay.copy(authoringJournal = journal + groups.map(VertexGroupJournal::encode)))
            }
            if (RigGenerationMigration.changed(current, config)) {
                val prepared = RigGenerationMigration.prepare(pipeline, current, config, document.source, progress)
                document.copy(rigEdits = prepared.rigEdits, generationSource = prepared.generationSource, meshSource = prepared.meshSource)
            }
            // Source replacement without a generation transition uses its specific source command.
            else if ((current.analysis.source !== document.source && current.analysis.source != document.source) ||
                current.config.layerOverrides != config.layerOverrides) document
            else {
                val changed = pipeline.meshSettingsChangedDrawableIds(current, config)
                if (changed.isEmpty()) document else {
                    val prepared = pipeline.rebuildPreview(current, config, progress).config
                    document.copy(rigEdits = prepared.rigEdits, generationSource = prepared.generationSource,
                        meshSource = prepared.meshSource)
                }
            }
        }
    }

    suspend fun build(document: WorkspaceDocument, current: RigPreviewModel? = null,
                      legacyDrawOrders: Map<String, Float> = current?.config?.drawOrderOverrides.orEmpty()): RigPreviewModel {
        val progress = progress(0.6f, 0.85f)
        return runInterruptible(Dispatchers.Default) {
            val decoded = document.config()
            // Early v1 projects stored draw orders only in their saved presentation. Keep historical
            // documents and node IDs immutable; new documents carry this generation input explicitly.
            val config = if ("drawOrderOverrides" in document.settings) decoded
                else decoded.copy(drawOrderOverrides = legacyDrawOrders)
            when {
                current != null && pipeline.canFastUpdateRig(current, document.source, config) -> pipeline.updateRigEdits(current, config)
                current != null && (current.analysis.source === document.source || current.analysis.source == document.source) &&
                    current.config.copy(parentOverrides = config.parentOverrides, rigEdits = config.rigEdits,
                        drawOrderOverrides = config.drawOrderOverrides, hairSimulationFront = config.hairSimulationFront,
                        hairSimulationBack = config.hairSimulationBack) == config -> pipeline.rebuildPreview(current, config, progress)
                else -> pipeline.buildPreview(document.source, config, progress)
            }
        }
    }
}
