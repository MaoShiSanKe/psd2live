package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.PuppetModel

/** A public synthetic hanging strip with an explicit driving axis, independent of GUI and disk input. */
internal suspend fun simulationFixture(runtime: WorkspaceRuntime<RigPreviewModel>, hair: Boolean = false): WorkspaceCapture<RigPreviewModel> {
    val width = 12; val height = 72
    val rgba = ByteArray(width * height * 4)
    for (i in rgba.indices step 4) { rgba[i] = 80; rgba[i + 1] = 110; rgba[i + 2] = 140.toByte(); rgba[i + 3] = -1 }
    val layer = WorkspaceSourceLayer(LayerId("strip"), "Strand", "", SourceLayerKind.Raster, true, 0,
        LayerBounds(0, 0, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(width, height, rgba), null, null, false)
    val config = PipelineConfig(atlasSize = 256, meshOnly = true, meshSpacing = 8, generatePhysics = false, exportMoc3 = false,
        layerOverrides = mapOf("strip" to LayerClassificationOverride(type = LayerType.PRESET, tag = if (hair) SemanticTag.BACK_HAIR else SemanticTag.OBJECTS)))
    val document = WorkspaceDocument(WorkspaceSourceArt(64, 96, listOf(layer), emptyList()), emptyMap(), emptySet(), config.layerOverrides,
        emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
    val builder = WorkspacePreviewBuilder()
    return runtime.install(runtime.state.value.state, "simulation-project", document, builder.build(document))
}

internal fun simulationDrivingEdits(capture: WorkspaceCapture<RigPreviewModel>) = simulationDrivingEdits(capture.model.rig.puppet)

internal fun simulationDrivingEdits(puppet: PuppetModel): List<WorkspaceDocumentOperation> {
    val drawable = puppet.drawables.first()
    val positions = requireNotNull(drawable.mesh).positions
    val xs = positions.indices.filter { it % 2 == 0 }.map { positions[it] }
    val ys = positions.indices.filter { it % 2 == 1 }.map { positions[it] }
    val root = listOf(xs.min() - 0.01f, ys.min() - 0.01f, xs.max() + 0.01f, ys.min() + (ys.max() - ys.min()) * 0.15f)
    return listOf(WorkspaceDocumentOperation("parameter_create", buildJsonObject {
        put("parameter_id", "Drive"); put("name", "Drive"); put("min", -30); put("max", 30); put("default", 0)
    }), WorkspaceDocumentOperation("rig_deform", buildJsonObject {
        putJsonArray("changes") {
            for (value in listOf(-30, 0, 30)) add(buildJsonObject {
                put("target", "mesh:${drawable.id.raw}"); putJsonObject("key") { put("Drive", value) }
                putJsonArray("operations") { add(buildJsonObject {
                    put("type", "translate"); put("delta", JsonArray(listOf(JsonPrimitive(value / 12f), JsonPrimitive(0))))
                }) }
            })
        }
    }), WorkspaceDocumentOperation("vertex_group_update", buildJsonObject {
        put("target", "mesh:${drawable.id.raw}"); put("name", "root"); put("kind", "pin"); put("rule", "region"); put("value", 1)
        put("rect", JsonArray(root.map(::JsonPrimitive)))
    }))
}

internal fun simulationPut(capture: WorkspaceCapture<RigPreviewModel>, id: String = "sway", autoBake: Boolean = false) =
    simulationPut(capture.model.rig.puppet, id, autoBake)

internal fun simulationPut(puppet: PuppetModel, id: String = "sway", autoBake: Boolean = false) =
    WorkspaceDocumentOperation("simulation_put", buildJsonObject {
        put("id", id); put("name", "Sway"); put("kind", "hair"); put("auto_bake", autoBake); put("modes", 1); put("keys", 3)
        putJsonArray("targets") { add(puppet.drawables.first().id.raw) }
        putJsonObject("groups") { put("pin", "root") }
        putJsonArray("inputs") { add(buildJsonObject { put("parameter", "Drive"); put("type", "x") }) }
    })
