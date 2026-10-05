package io.github.psd2live.project

import kotlinx.serialization.json.*

/** Absolute canvas-pixel placement; successive changes always sample the original imported pixels. */
data class WorkspaceImageBounds(val layerId: String, val left: Float, val top: Float, val width: Float, val height: Float, val name: String? = null) {
    companion object {
        internal fun parse(request: JsonObject) = WorkspaceImageBounds(request.getValue("layer_id").jsonPrimitive.content,
            request.getValue("left").jsonPrimitive.float, request.getValue("top").jsonPrimitive.float,
            request.getValue("width").jsonPrimitive.float, request.getValue("height").jsonPrimitive.float, request["name"]?.jsonPrimitive?.content)
    }
}
