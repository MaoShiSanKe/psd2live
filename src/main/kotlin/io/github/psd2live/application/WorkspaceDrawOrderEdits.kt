package io.github.psd2live.application

import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*

/** The ruler, inspector and public requests resolve the same explicit layer-order override. */
internal object WorkspaceDrawOrderEdits {
    const val OP = "layer_draw_order"

    fun request(target: String, order: Float?) = buildJsonObject {
        put("target", target); put("order", order?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun apply(document: WorkspaceDocument, model: RigPreviewModel, request: JsonObject): WorkspaceDocument {
        validateOperationSchema(request, WorkspaceDrawOrderSchemas.request)
        val raw = request.getValue("target").jsonPrimitive.content.removePrefix("mesh:").removePrefix("layer:")
        val drawable = model.rig.puppet.drawables.singleOrNull { it.id.raw == raw }
            ?: model.rig.puppet.drawables.singleOrNull { model.rig.layerIdByDrawableId[it.id.raw] == raw }
            ?: throw IllegalArgumentException("Draw-order target not found: $raw")
        val layer = model.rig.layerIdByDrawableId[drawable.id.raw] ?: drawable.id.raw
        val before = document.settings["drawOrderOverrides"]?.jsonObject?.mapValues { it.value.jsonPrimitive.float }
            ?: model.config.drawOrderOverrides
        val value = request.getValue("order")
        val after = if (value == JsonNull) before - layer - drawable.id.raw else {
            val order = value.jsonPrimitive.float
            require(order.isFinite() && order in 0f..1000f) { "Draw order must be within 0..1000" }
            before - drawable.id.raw + (layer to order)
        }
        if (before == after) return document
        return document.copy(settings = JsonObject(document.settings +
            ("drawOrderOverrides" to JsonObject(after.toSortedMap().mapValues { JsonPrimitive(it.value) }))))
    }
}

internal object WorkspaceDrawOrderSchemas {
    val request = WorkspaceResultSchema.obj(mapOf(
        "target" to WorkspaceResultSchema.handle(),
        "order" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null")))); put("minimum", 0); put("maximum", 1000) },
    ))
    val result = WorkspaceResultSchema.obj(WorkspaceResultSchema.identity + mapOf(
        "applied" to WorkspaceResultSchema.constant(false), "changed" to WorkspaceResultSchema.array(WorkspaceResultSchema.handle(), 1, Int.MAX_VALUE)),
        WorkspaceResultSchema.identity.keys)
}

internal fun registerDrawOrderOperations(registry: WorkspaceOperationRegistry, port: WorkspaceDocumentPort) {
    val business = WorkspaceDrawOrderSchemas.request
    val schema = JsonObject(business + mapOf(
        "properties" to JsonObject(business.getValue("properties").jsonObject + ("state" to WorkspaceResultSchema.handle())),
        "required" to JsonArray(business.getValue("required").jsonArray + JsonPrimitive("state"))))
    registry.register(WorkspaceOperationDefinition(WorkspaceDrawOrderEdits.OP,
        "Set the ruler's explicit drawing order for a current layer or mesh target (0..1000). This order wins over animated order until reset. Use order=null to remove the layer and mesh overrides and restore the underlying generated or authored order, including its animation. Ordinary, imported and partitioned artwork use the same rule; the underlying ordered edits remain intact.",
        schema, WorkspaceOperationKind.DOCUMENT, batchable = true, resultSchema = WorkspaceDrawOrderSchemas.result)) { request, context ->
        val edit = WorkspaceDocumentOperation(WorkspaceDrawOrderEdits.OP, JsonObject(request - "state"))
        WorkspaceOperationOutput(port.applyDocumentEdits(request.getValue("state").jsonPrimitive.content,
            "Updated drawing order", listOf(edit), context.author).compact())
    }
}
