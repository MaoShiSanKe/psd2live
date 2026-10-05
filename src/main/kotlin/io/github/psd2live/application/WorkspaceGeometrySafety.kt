package io.github.psd2live.application

import io.github.psd2live.project.WorkspaceDocument
import kotlinx.serialization.json.*

/** Limit the gate to the authoring families covered by the geometry safety contract. */
internal object WorkspaceGeometrySafety {
    val operations = setOf("rig_deform", "keyform_apply", "rig_edit_structure", "object_edit_appearance",
        "canvas_warp", "canvas_rotation", "canvas_glue", "canvas_topology", "canvas_deform_stroke", "path_deform")
    private val journalOperations = setOf("set", "copy", "delete", "structure", "warp", "canvas_geometry",
        "canvas_topology", "canvas_create_warp", "canvas_create_rotation", "canvas_create_glue", "canvas_glue_edit")

    fun changed(before: WorkspaceDocument, after: WorkspaceDocument): Boolean {
        val old = before.rigEdits.authoringJournal
        val next = after.rigEdits.authoringJournal
        // Existing records belong to history; only a newly authored suffix triggers this policy.
        return next.size > old.size && next.take(old.size) == old &&
            next.drop(old.size).any { it.jsonObject["op"]?.jsonPrimitive?.content in journalOperations }
    }
}
