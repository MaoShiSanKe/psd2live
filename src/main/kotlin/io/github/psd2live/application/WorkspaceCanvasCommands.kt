package io.github.psd2live.application

import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel
import java.util.UUID

/** Materialize canvas requests against the model belonging to this candidate document. */
internal object WorkspaceCanvasCommands {
    fun create(model: PuppetModel, mode: String, request: JsonObject): JsonObject {
        val op = when (mode) {
            "warp" -> "canvas_create_warp"
            "rotation" -> "canvas_create_rotation"
            "glue" -> "canvas_create_glue"
            "topology" -> "canvas_topology"
            else -> throw IllegalArgumentException("Unknown canvas mode: $mode")
        }
        val id = request["id"]?.jsonPrimitive?.content
            ?: "Agent${mode.replaceFirstChar(Char::uppercase)}_${UUID.randomUUID().toString().take(8)}"
        return buildJsonObject {
            put("op", op); put("id", id)
            request.forEach { (key, value) -> if (key !in setOf("mode", "state", "project_id", "request_id", "id")) put(key, value) }
            if (mode == "glue") {
                fun mesh(key: String): String {
                    val raw = request.getValue(key).jsonPrimitive.content.trim()
                    require(raw.isNotEmpty()) { "Glue requires two different meshes" }
                    return model.drawables.firstOrNull { it.id.raw == raw && it.mesh != null }?.id?.raw
                        ?: throw IllegalArgumentException("Mesh not found: $raw. Glue requires two different art-mesh ids from inspect.")
                }
                val meshA = mesh("mesh_a"); val meshB = mesh("mesh_b")
                require(meshA != meshB) { "Glue requires two different meshes" }
                put("mesh_a", meshA); put("mesh_b", meshB)
                if (request["replace"]?.jsonPrimitive?.boolean == true) {
                    model.glues.firstOrNull { glue ->
                        (glue.meshA.raw == meshA && glue.meshB.raw == meshB) ||
                            (glue.meshA.raw == meshB && glue.meshB.raw == meshA)
                    }?.id?.let { put("id", it) }
                }
            }
        }
    }
}
