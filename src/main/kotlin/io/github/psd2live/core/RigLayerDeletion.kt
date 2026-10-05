package io.github.psd2live.core

import kotlinx.serialization.json.*
import org.umamo.edit.withDrawablesDeleted
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

/** Replay authored objects and bindings before filtering a layer from the active model. */
internal object RigLayerDeletion {
    const val OP = "layer_membership"

    private fun replaysBeforeFiltering(config: PipelineConfig) = config.rigEdits.authoringJournal.any {
        it["op"]?.jsonPrimitive?.contentOrNull in setOf(RasterMeshCreation.OP, DepthSplit.OP, SourcePartitionJournal.OP, OP)
    }

    fun deferred(config: PipelineConfig) = config.deletedLayerIds.isNotEmpty() &&
        replaysBeforeFiltering(config)

    /** Upgrade only the new candidate; historical documents keep their original generation rules. */
    fun preserve(current: RigPreviewModel, config: PipelineConfig): RigEditOverlay {
        if (replaysBeforeFiltering(config)) return config.rigEdits
        val journal = config.rigEdits.authoringJournal + buildJsonObject { put("op", OP) }
        if (config.rigEdits.importedCmo3 != null) return config.rigEdits.copy(authoringJournal = journal)
        val baseline = config.rigEdits.splitBaselineLayerIds.ifEmpty {
            current.analysis.source.layers.filterNot { it.id.raw in config.deletedLayerIds }.mapTo(LinkedHashSet()) { it.id.raw }
        }
        val existing = config.rigEdits.splitDrawableIds +
            current.baseRig.layerIdByDrawableId.entries.associate { it.value to it.key } +
            current.rig.layerIdByDrawableId.entries.associate { it.value to it.key }
        val fullConfig = config.copy(deletedLayerIds = emptySet(), rigEdits = config.rigEdits.copy(
            splitBaselineLayerIds = baseline, authoringJournal = journal))
        val all = RigGenerationSource.prepare(RigGenerationSource.analyze(current.analysis.source, fullConfig), fullConfig).geometry
        val ids = RigBuilder.assignSplitDrawableIds(all, existing.mapValues { DrawableId(it.value) })
        return config.rigEdits.copy(splitBaselineLayerIds = baseline, splitDrawableIds = ids.mapValues { it.value.raw },
            authoringJournal = journal)
    }

    /** A configuration marker, never an object deletion inside the ordered replay. */
    fun replay(model: PuppetModel, command: JsonObject): PuppetModel {
        require(command.keys == setOf("op")) { "Invalid layer membership marker" }
        return model
    }

    fun generationConfig(config: PipelineConfig) = if (deferred(config)) config.copy(deletedLayerIds = emptySet()) else config

    fun generationAnalysis(input: PipelineAnalysis, config: PipelineConfig): PipelineAnalysis {
        if (!deferred(config)) return input
        val full = generationConfig(config)
        return RigGenerationSource.prepare(RigGenerationSource.analyze(input.source, full), full).textures
    }

    private fun deleted(id: String, analysis: PipelineAnalysis, config: PipelineConfig): Boolean {
        if (id in config.deletedLayerIds) return true
        val sourceIds = analysis.source.layers.map { it.id.raw }
        if (id in sourceIds) return false
        val owner = sourceIds.filter { id.startsWith("$it:") }.maxByOrNull { it.length }
        return owner in config.deletedLayerIds
    }

    fun analysis(input: PipelineAnalysis, config: PipelineConfig): PipelineAnalysis =
        if (!deferred(config)) input else input.copy(layers = input.layers.filterNot { deleted(it.source.id.raw, input, config) })

    fun rig(input: BuiltRig, analysis: PipelineAnalysis, config: PipelineConfig): BuiltRig {
        if (!deferred(config)) return input
        val removed = input.puppet.drawables.filter { drawable ->
            input.layerIdByDrawableId[drawable.id.raw]?.let { deleted(it, analysis, config) } == true
        }.mapTo(HashSet()) { it.id }
        val ids = removed.mapTo(HashSet()) { it.raw }
        return input.copy(puppet = input.puppet.withDrawablesDeleted(removed).let { model -> model.copy(
            deformPaths = model.deformPaths.filterNot { it.drawableId in removed },
            vertexGroups = model.vertexGroups.filterNot { it.drawableId in removed },
        ) }, layerIdByDrawableId = input.layerIdByDrawableId - ids,
            sourceBoundsByDrawableId = input.sourceBoundsByDrawableId - ids, pageByDrawableId = input.pageByDrawableId - ids)
    }

    fun preview(input: RigPreviewModel, config: PipelineConfig): RigPreviewModel {
        val active = analysis(input.analysis, config)
        val rig = rig(input.rig, input.analysis, config)
        val bundle = PSD2LivePipeline().buildRuntimeBundle("psd2live-preview", active, input.atlas, rig, config).first
        return input.copy(analysis = active, rig = rig, config = config, runtimeBundle = bundle)
    }
}
