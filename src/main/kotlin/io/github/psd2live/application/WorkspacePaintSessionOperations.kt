package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import io.github.psd2live.project.WorkspaceWorkflowResult

internal object WorkspacePaintSessionSchemas {
    private val s = WorkspaceResultSchema
    private val color = s.array(s.integer(0, 255), 4, 4)
    private val common = mapOf("color" to color, "opacity" to s.number(0, 1))
    private fun branch(mode: String, fields: Map<String, JsonObject>, required: Set<String>) =
        s.obj(common + fields + ("mode" to s.constant(mode)), required + "mode")
    val gesture = s.union(listOf(
        branch("brush", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512),
            "hardness" to s.number(0, 1)), setOf("points")),
        branch("pencil", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512)), setOf("points")),
        branch("eraser", mapOf("points" to s.array(s.vector(2), 1, 512), "radius" to s.number(0.5, 512),
            "hardness" to s.number(0, 1)), setOf("points")),
        branch("bucket", mapOf("point" to s.vector(2), "tolerance" to s.integer(0, 255)), setOf("point")),
        branch("shape", mapOf("from" to s.vector(2), "to" to s.vector(2), "shape" to s.choices("line", "rectangle", "ellipse"),
            "stroke_width" to s.number(1, 512), "filled" to s.boolean()), setOf("from", "to", "shape")),
        s.obj(mapOf("mode" to s.constant("clear"))),
    ))
    private fun control(action: String, fields: Map<String, JsonObject> = emptyMap(), required: Set<String> = fields.keys) =
        s.obj(fields + ("action" to s.constant(action)), required + "action")
    val edit = s.union(listOf(control("gesture", mapOf("gesture" to gesture, "name" to s.handle()), setOf("gesture")),
        control("undo"), control("redo"), control("jump", mapOf("index" to s.integer(0))), control("abandon"),
        control("cancel"), control("inspect"), control("sample", mapOf("x" to s.integer(0), "y" to s.integer(0))),
        control("render", mapOf("max_edge" to s.integer(128, 2048)), emptySet())))
    val snapshot = s.obj(mapOf("session_id" to s.handle(), "session_state" to s.handle(), "project_id" to s.handle(),
        "workspace_state" to s.handle(), "layer_id" to s.handle(), "layer_name" to s.string(),
        "width" to s.integer(1), "height" to s.integer(1), "index" to s.integer(0), "dirty" to s.boolean(),
        "active_stroke" to s.boolean(), "closed" to s.boolean(),
        "strokes" to s.array(s.obj(mapOf("id" to s.handle(), "name" to s.string())), 1, Int.MAX_VALUE)))
    val result = s.union(listOf(s.obj(mapOf("kind" to s.constant("state"), "session" to snapshot)),
        s.obj(mapOf("kind" to s.constant("sample"), "session" to snapshot, "rgba" to color)),
        s.obj(mapOf("kind" to s.constant("render"), "session" to snapshot, "width" to s.integer(1),
            "height" to s.integer(1)))))
    val commit = s.obj(s.identity + mapOf("applied" to s.constant(false), "changed" to s.array(s.handle(), 1, Int.MAX_VALUE)), s.identity.keys)
}

/** Render/sample observe the isolated candidate, including its in-progress live stroke. */
internal fun WorkspacePaintSession.control(request: JsonObject, checkpoint: () -> Unit = {
    if (Thread.currentThread().isInterrupted) throw kotlinx.coroutines.CancellationException("Paint session gesture cancelled")
}): WorkspaceWorkflowResult {
    val edit = request.getValue("edit").jsonObject
    validateOperationSchema(edit, WorkspacePaintSessionSchemas.edit)
    val expected = request.getValue("session_state").jsonPrimitive.content
    val action = edit.getValue("action").jsonPrimitive.content
    return atomic(expected) {
        when (action) {
            "gesture" -> gesture(edit.getValue("gesture").jsonObject, edit["name"]?.jsonPrimitive?.content
                ?: edit.getValue("gesture").jsonObject.getValue("mode").jsonPrimitive.content, expected, checkpoint)
            "undo" -> undo(expected)
            "redo" -> redo(expected)
            "jump" -> jump(edit.getValue("index").jsonPrimitive.int, expected)
            "abandon" -> abandonStroke(expected)
            "cancel" -> cancel(expected)
            "inspect", "sample", "render" -> require(sessionState == expected) { "Paint session state changed" }
        }
        var images = emptyList<ByteArray>()
        val metadata = buildJsonObject {
            put("kind", if (action in setOf("sample", "render")) action else "state"); put("session", snapshot())
            if (action == "sample") {
                val pixel = sample(edit.getValue("x").jsonPrimitive.int, edit.getValue("y").jsonPrimitive.int)
                put("rgba", JsonArray(listOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255, pixel ushr 24 and 255).map(::JsonPrimitive)))
            }
            if (action == "render") {
                val source = image(); val edge = edit["max_edge"]?.jsonPrimitive?.int ?: 512
                val scale = minOf(1.0, edge.toDouble() / maxOf(source.width, source.height))
                val output = BufferedImage(maxOf(1, (source.width * scale).toInt()), maxOf(1, (source.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
                output.createGraphics().also { graphics ->
                    try {
                        graphics.composite = java.awt.AlphaComposite.Src
                        graphics.drawImage(source, 0, 0, output.width, output.height, null)
                    } finally { graphics.dispose() }
                }
                val bytes = ByteArrayOutputStream().also { ImageIO.write(output, "png", it) }.toByteArray()
                put("width", output.width); put("height", output.height); images = listOf(bytes)
            }
        }
        WorkspaceWorkflowResult(metadata, images)
    }
}

internal fun registerPaintSessionOperations(registry: WorkspaceOperationRegistry, port: WorkspacePaintPort,
    statePort: WorkspaceStatePort, jobs: WorkspaceJobs) {
    val s = WorkspaceResultSchema
    val ids = mapOf("session_id" to s.handle(), "session_state" to s.handle())
    registry.register(WorkspaceOperationDefinition("paint_session_list",
        "List the current private paint sessions, including the GUI candidate and its stroke revision. Use a listed session_id to inspect or continue exactly that candidate.",
        s.obj(emptyMap()), WorkspaceOperationKind.QUERY, resultSchema = s.obj(mapOf("sessions" to s.array(WorkspacePaintSessionSchemas.snapshot))))) { _, _ ->
        WorkspaceOperationOutput(buildJsonObject { put("sessions", JsonArray(port.listPaintSessions())) })
    }
    registry.register(WorkspaceOperationDefinition("paint_session_begin",
        "Begin an isolated multi-stroke paint candidate from a committed layer, capturing its workspace state. Canvas X points right and Y down; edits, sampling and undo never alter project history until commit.",
        s.obj(mapOf("state" to s.handle(), "layer_id" to s.handle())), WorkspaceOperationKind.SESSION,
        resultSchema = WorkspacePaintSessionSchemas.result)) { request, _ ->
        WorkspaceOperationOutput(buildJsonObject { put("kind", "state"); put("session",
            port.beginPaintSession(request.getValue("state").jsonPrimitive.content, request.getValue("layer_id").jsonPrimitive.content).snapshot()) })
    }
    registry.register(WorkspaceOperationDefinition("paint_session_control",
        "Edit, undo, redo or jump within the private paint candidate; cancel discards the entire session, abandon discards only the active live stroke. Sample and render read candidate pixels. Pass the latest session_state to prevent concurrent stroke loss. Mutations reject a changed committed workspace; inspection and cancellation remain available.",
        s.obj(ids + ("edit" to WorkspacePaintSessionSchemas.edit)), WorkspaceOperationKind.SESSION,
        resultSchema = WorkspacePaintSessionSchemas.result)) { request, _ ->
        val result = port.controlPaintSession(request)
        WorkspaceOperationOutput(result.metadata, result.images)
    }
    registry.register(WorkspaceOperationDefinition("paint_session_commit",
        "Commit all candidate strokes once as one raster/mesh history node. Defaults to retaining mesh and authored bindings; rebuild_mesh migrates existing rig bindings. Both the captured workspace and session revision must match. A successful session cannot publish twice.",
        s.obj(mapOf("state" to s.handle()) + ids + mapOf("rebuild_mesh" to s.boolean(), "preserve_source_raster" to s.boolean()),
            setOf("state", "session_id", "session_state")), WorkspaceOperationKind.DOCUMENT,
        jobBacked = true, resultSchema = WorkspaceJobResultSchemas.snapshot("paint_session_commit"), jobResultSchema = WorkspacePaintSessionSchemas.commit)) { request, context ->
        startWorkspaceOperationJob(statePort, jobs, "paint_session_commit") {
            val completion = requireNotNull(currentCoroutineContext()[WorkspaceJobCompletion])
            withContext(WorkspacePaintJobExecution(completion)) {
                WorkspaceOperationOutput(port.commitPaintSession(request.getValue("state").jsonPrimitive.content,
                    request.getValue("session_id").jsonPrimitive.content, request.getValue("session_state").jsonPrimitive.content,
                    request["rebuild_mesh"]?.jsonPrimitive?.boolean ?: false, request["preserve_source_raster"]?.jsonPrimitive?.boolean ?: false,
                    context.author).compact())
            }
        }
    }
}
