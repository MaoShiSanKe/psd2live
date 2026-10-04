package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.umamo.runtime.model.ParameterId
import kotlin.math.ceil
import kotlin.math.floor

/** The host supplies only native evaluation; request validation, sampling and rendering live here. */
internal fun interface WorkspaceMotionSampler {
    suspend fun sample(bundle: CubismRuntimeBundle, parameters: List<ParameterId>, frames: Int, fps: Int,
                       progress: (Float) -> Unit, cancelled: () -> Boolean): List<Map<ParameterId, Float>>
}

internal class WorkspaceObservationSession(
    private val read: WorkspaceReadCapture<RigPreviewModel>,
    private val builder: WorkspacePreviewBuilder,
    private val sampler: WorkspaceMotionSampler,
    private val remember: (WorkspaceRenderedView) -> WorkspaceRenderedView,
    visibleLayerIds: Set<String>? = null,
    private val renderer: WorkspaceRenderSession = WorkspaceRenderSession(read, remember, visibleLayerIds),
) : WorkspaceObservation, WorkspaceImageRenderer by renderer {
    private val visibleLayerIds = visibleLayerIds?.toSet()
    override fun snapshot() = WorkspaceReadSession(read).snapshot()

    override suspend fun observeAuthoring(arguments: JsonObject): WorkspaceWorkflowResult {
        val captured = requireNotNull(read.runtime.capture) { "No workspace is loaded" }
        val context = currentCoroutineContext()
        val job = context[WorkspaceJobContext]
        fun progress(value: Float, message: String) { context.ensureActive(); job?.progress(value, message) }
        val cancelled = { context.ensureActive(); false }
        progress(0.05f, "Preparing observation")
        val kind = arguments.getValue("kind").jsonPrimitive.content
        val rect = arguments.getValue("rect").jsonArray.map { it.jsonPrimitive.float }
        require(rect.size == 4 && rect.all(Float::isFinite) && rect[2] > 0 && rect[3] > 0) { "rect is [left,top,width,height] in canvas pixels" }
        val frame = WorkspaceViewFrame.CanvasRect(Bounds(rect[0], rect[1], rect[0] + rect[2], rect[1] + rect[3]))
        val output = WorkspaceViewOutputSpec(arguments["target_long_edge"]?.jsonPrimitive?.int ?: 1536)
        val views = mutableListOf<WorkspaceRenderedView>()
        suspend fun render(preview: RigPreviewModel, revision: String, values: Map<String, Float>, layers: Set<String>) =
            renderer.renderModel(preview, revision, WorkspaceModelViewRequest(parameters = values, includeLayerIds = layers, frame = frame,
                output = output.copy(targetLongEdge = maxOf(128, output.targetLongEdge / 2))))
        if (kind == "history") {
            val states = arguments.getValue("states").jsonArray.map { it.jsonPrimitive.content }
            val poses = arguments.getValue("poses").jsonArray.map { it.jsonObject.mapValues { entry -> entry.value.jsonPrimitive.float } }
            require(states.size in 1..2 && poses.size in 1..4)
            val history = requireNotNull(read.history)
            val prepared = states.map { id ->
                val selection = history.selections.singleOrNull { it.node.id == id } ?: error("History node not found: $id")
                selection to builder.build(selection.snapshot, captured.model)
            }
            for (pose in poses) for ((selection, preview) in prepared) {
                views += render(preview, selection.node.revisionId, pose, selection.snapshot.renderVisibleLayers(preview))
            }
            val sheet = renderPoseSheet(views, output, states.size, compareVersions = true)
            return sheet.copy(metadata = JsonObject(sheet.metadata + ("states" to JsonArray(states.map(::JsonPrimitive)))))
        }
        require(kind == "motion")
        val preview = captured.model
        val frames = arguments.getValue("frames").jsonArray
        val fps = arguments["fps"]?.jsonPrimitive?.int ?: 60
        require(fps in 15..120)
        val duration = frames.last().jsonObject.getValue("time").jsonPrimitive.float
        val times = arguments.getValue("samples").jsonArray.map { it.jsonPrimitive.float }
        require(times.size in 1..9 && times.all { it.isFinite() && it in 0f..duration }) { "Samples must be inside the motion duration" }
        val defaults = preview.rig.puppet.parameters.associate { it.id.raw to it.default }
        for (sample in frames) for ((id, value) in sample.jsonObject.getValue("parameters").jsonObject) {
            val parameter = preview.rig.puppet.parameters.singleOrNull { it.id.raw == id } ?: error("Unknown motion input $id")
            require(value.jsonPrimitive.float in parameter.min..parameter.max) { "Motion input outside $id range" }
        }
        val bundle = observationMotion(preview.runtimeBundle, frames, defaults)
        progress(0.1f, "Evaluating exported motion")
        val count = ceil(duration * fps).toInt() + 1
        val parameters = preview.rig.puppet.parameters.map { it.id }
        val samples = sampler.sample(bundle, parameters, count, fps, { progress(0.1f + 0.6f * it, "Evaluating exported motion") }, cancelled)
        val parameterIds = parameters.toSet()
        check(samples.size == count && samples.all { sample -> sample.keys == parameterIds && sample.values.all(Float::isFinite) }) {
            "Motion evaluator returned incomplete or invalid samples"
        }
        val layers = visibleLayerIds ?: captured.document.renderVisibleLayers(preview)
        times.forEachIndexed { index, time ->
            views += render(preview, captured.revision, samples[(time * fps).toInt().coerceIn(samples.indices)].mapKeys { it.key.raw }, layers)
            progress(0.7f + 0.25f * (index + 1) / times.size, "Rendering sampled poses")
        }
        val sheet = renderPoseSheet(views, output)
        progress(0.99f, "Observation ready")
        return sheet.copy(metadata = JsonObject(sheet.metadata + buildJsonObject {
            put("physicsSimulated", true); put("backend", "Cubism exported model parameters; CPU image compositor")
            put("fps", fps); put("sampleTimes", JsonArray(times.map { JsonPrimitive(floor(it * fps) / fps) }))
            putJsonObject("ranges") {
                for (parameter in preview.rig.puppet.parameters) {
                    val values = samples.map { it.getValue(parameter.id) }
                    if (values.max() - values.min() > 1e-5f) put(parameter.id.raw, JsonArray(listOf(values.min(), values.max()).map(::JsonPrimitive)))
                }
            }
        }))
    }
}
