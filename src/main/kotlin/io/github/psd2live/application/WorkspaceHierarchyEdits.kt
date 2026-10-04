package io.github.psd2live.application

import kotlinx.serialization.json.*
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.PuppetModel

/**
 * The hierarchy drag as one ordered structure journal edit. Old v1 parentOverrides stay in the document
 * and keep applying while the rig is built, before any journal edit replays; new drags never write them.
 */
object WorkspaceHierarchyEdits {
    /** Null when [childId] already hangs under [parentId]. The journal candidate validates cycles and parents. */
    fun reparent(puppet: PuppetModel, childId: String, parentId: String?): JsonObject? {
        val deformer = puppet.deformers.firstOrNull { it.id.raw == childId }
        val drawable = puppet.drawables.firstOrNull { it.id.raw == childId }
        val current = deformer?.let { it.parent?.raw } ?: drawable?.parentDeformerId?.raw
        if (deformer == null && drawable == null) throw IllegalArgumentException("Object not found: $childId")
        if (current == parentId) return null
        return buildJsonObject {
            // A mesh binds to another deformer; a deformer moves within the deformer tree.
            put("action", if (deformer != null) "move" else "bind")
            put("kind", when (deformer) { null -> "mesh"; is Deformer.Warp -> "warp"; else -> "rotation" })
            put("id", childId); put("parent_id", parentId); put("space", "local")
        }
    }

    fun journal(edit: JsonObject): JsonArray = buildJsonArray {
        add(buildJsonObject { put("op", "structure"); putJsonArray("edits") { add(edit) } })
    }
}
