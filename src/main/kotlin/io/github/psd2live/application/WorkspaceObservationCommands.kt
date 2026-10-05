package io.github.psd2live.application


import io.github.psd2live.core.CubismJson
import io.github.psd2live.core.CubismRuntimeAsset
import io.github.psd2live.core.CubismRuntimeBundle
import kotlinx.serialization.json.*

internal fun registerObservationCommands(catalog: WorkspaceCommands, workspace: WorkspaceRenderPort) {
    val number = buildJsonObject { put("type", "number") }
    val time = buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 10) }
    val pose = buildJsonObject { put("type", "object"); put("additionalProperties", number) }
    fun array(items: JsonObject, min: Int, max: Int) = buildJsonObject { put("type", "array"); put("items", items); put("minItems", min); put("maxItems", max) }
    for (kind in listOf("history", "motion")) {
        val schema = WorkspaceCommandSchema(properties = buildJsonObject {
            put("rect", array(number, 4, 4))
            putJsonObject("target_long_edge") { put("type", "integer"); put("minimum", 128); put("maximum", 4096) }
            if (kind == "history") {
                put("states", array(buildJsonObject { put("type", "string") }, 1, 2))
                put("poses", array(pose, 1, 4))
            } else {
                put("frames", array(buildJsonObject { put("type", "object"); put("additionalProperties", false)
                    putJsonObject("properties") { put("time", time); put("parameters", pose) }
                    putJsonArray("required") { add(JsonPrimitive("time")); add(JsonPrimitive("parameters")) }
                }, 2, 32))
                put("samples", array(time, 1, 9))
                putJsonObject("fps") { put("type", "integer"); put("minimum", 15); put("maximum", 120) }
            }
        }, required = listOf("rect") + if (kind == "history") listOf("states", "poses") else listOf("frames", "samples"))
        val description = if (kind == "history") "Compare captured history nodes with common poses and a fixed canvas rectangle. Returns one labeled PNG and per-tile revision IDs."
            else "Evaluate the exported Cubism motion and physics, then render sampled poses with a fixed canvas rectangle. Frames must increase from 0 up to 10 seconds; omitted parameter values carry forward. Sample times must lie inside the motion duration and are quantized to fps. Returns a labeled PNG and parameter ranges; requires the optional native Cubism runtime."
        catalog.register(if (kind == "history") "view_compare_history" else "view_sample_motion", description, schema,
            hints = WorkspaceCommandHints(readOnlyHint = true, destructiveHint = false, openWorldHint = false)) { request ->
            val result = workspace.captureObservation().observeAuthoring(JsonObject(request.arguments + ("kind" to JsonPrimitive(kind))))
            WorkspaceOperationOutput(result.metadata, result.images)
        }
    }
}

/** Drive the exported native model with a motion, so inputs arrive before physics evaluation. */
internal fun observationMotion(bundle: CubismRuntimeBundle, frames: JsonArray, defaults: Map<String, Float>): CubismRuntimeBundle {
    require(frames.size in 2..32)
    val times = frames.map { it.jsonObject.getValue("time").jsonPrimitive.float }
    require(times.first() == 0f && times.all { it.isFinite() && it in 0f..10f } && times.zipWithNext().all { it.first < it.second }) { "Motion times must increase from 0, up to 10 seconds" }
    val poses = frames.map { it.jsonObject.getValue("parameters").jsonObject.mapValues { it.value.jsonPrimitive.float } }
    val ids = poses.flatMap { it.keys }.distinct()
    require(ids.isNotEmpty() && ids.all { it in defaults }) { "Unknown or empty motion parameter set" }
    val values = mutableMapOf<String, Float>()
    val expanded = poses.map { pose -> values.putAll(pose); defaults + values }
    require(expanded.all { it.values.all(Float::isFinite) })
    val motion = buildJsonObject {
        put("Version", 3)
        putJsonObject("Meta") {
            put("Duration", times.last()); put("Fps", 60); put("Loop", false); put("AreBeziersRestricted", true)
            put("CurveCount", ids.size); put("TotalSegmentCount", ids.size * (times.size - 1))
            put("TotalPointCount", ids.size * times.size); put("UserDataCount", 0); put("TotalUserDataSize", 0)
        }
        putJsonArray("Curves") {
            ids.forEach { id -> add(buildJsonObject {
                put("Target", "Parameter"); put("Id", id); put("FadeInTime", 0); put("FadeOutTime", 0)
                putJsonArray("Segments") {
                    add(JsonPrimitive(0)); add(JsonPrimitive(expanded.first().getValue(id)))
                    for (index in 1..times.lastIndex) { add(JsonPrimitive(0)); add(JsonPrimitive(times[index])); add(JsonPrimitive(expanded[index].getValue(id))) }
                }
            }) }
        }
    }
    val path = "agent-observation.motion3.json"
    val manifestAsset = bundle.assets.single { it.path == bundle.manifestPath }
    val manifest = Json.parseToJsonElement(manifestAsset.bytes.decodeToString()).jsonObject
    val refs = manifest.getValue("FileReferences").jsonObject
    val updated = JsonObject(manifest + ("FileReferences" to JsonObject(refs + ("Motions" to buildJsonObject {
        putJsonArray("AgentObservation") { add(buildJsonObject { put("File", path); put("FadeInTime", 0); put("FadeOutTime", 0) }) }
    }))))
    val directory = bundle.manifestPath.substringBeforeLast('/', "")
    val motionPath = if (directory.isEmpty()) path else "$directory/$path"
    return bundle.copy(assets = bundle.assets.filterNot { it.path in setOf(bundle.manifestPath, motionPath) } +
        CubismRuntimeAsset(bundle.manifestPath, CubismJson.normalize(updated.toString()).encodeToByteArray()) +
        CubismRuntimeAsset(motionPath, CubismJson.normalize(motion.toString()).encodeToByteArray()))
}
