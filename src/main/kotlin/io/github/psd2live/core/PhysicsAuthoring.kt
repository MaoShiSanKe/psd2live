package io.github.psd2live.core

import kotlinx.serialization.json.*

/**
 * The overlay changes behind the physics panel and the `physics` tools; the panel and MCP call the same
 * functions, so a group edited in one reads back identically in the other.
 */
object PhysicsAuthoring {
    /**
     * [overlay] with [edit] as the user's version of its group. Editing a generated group back to its
     * generated values drops the replacement. Another enabled user group may not already drive an output.
     */
    fun put(overlay: RigEditOverlay, edit: RigPhysicsEdit, generated: RigPhysicsEdit? = null): RigEditOverlay {
        val others = overlay.physicsEdits.filter { it.id != edit.id && it.id !in overlay.disabledPhysicsIds }
        edit.outputParameters.firstOrNull { p -> others.any { p in it.outputParameters } }?.let { p ->
            throw IllegalArgumentException("Output $p is already driven by ${others.first { p in it.outputParameters }.id}")
        }
        val index = overlay.physicsEdits.indexOfFirst { it.id == edit.id }
        val next = when {
            edit == generated -> overlay.physicsEdits.filterNot { it.id == edit.id }
            index < 0 -> overlay.physicsEdits + edit
            else -> overlay.physicsEdits.toMutableList().also { it[index] = edit }
        }
        return overlay.copy(physicsEdits = next)
    }

    /** Turns a non-preset group on or off; the presets are switched by their own settings. */
    fun setEnabled(overlay: RigEditOverlay, id: String, enabled: Boolean): RigEditOverlay {
        require(id !in PhysicsGenerator.presetIds) { "Presets are switched by their settings" }
        return overlay.copy(disabledPhysicsIds = if (enabled) overlay.disabledPhysicsIds - id else overlay.disabledPhysicsIds + id)
    }

    /** Deletes a user group, or returns a replaced generated group to its generated values. */
    fun remove(overlay: RigEditOverlay, id: String, generated: Boolean): RigEditOverlay {
        require(overlay.physicsEdits.any { it.id == id }) { "No user physics group $id" }
        return overlay.copy(physicsEdits = overlay.physicsEdits.filterNot { it.id == id },
            disabledPhysicsIds = if (generated) overlay.disabledPhysicsIds else overlay.disabledPhysicsIds - id)
    }

    /** Drops every edit of groups that no longer exist. */
    internal fun forget(overlay: RigEditOverlay, ids: Set<String>): RigEditOverlay =
        if (ids.isEmpty()) overlay
        else overlay.copy(physicsEdits = overlay.physicsEdits.filterNot { it.id in ids }, disabledPhysicsIds = overlay.disabledPhysicsIds - ids)

    /** A fresh `PhysicsCustom<N>` that no group uses. */
    fun freshId(groups: List<PhysicsGroup>, overlay: RigEditOverlay): String {
        val used = groups.mapTo(HashSet()) { it.id } + overlay.physicsEdits.map { it.id }
        return generateSequence(1) { it + 1 }.map { "PhysicsCustom$it" }.first { it !in used }
    }

    /** A new hanging pendulum: head and body sway in, one hair-like segment, no output yet. */
    fun template(id: String, name: String, available: Set<String>, segments: Int = 1) = RigPhysicsEdit(
        id, name, PhysicsGenerator.headAndBodyInputs(available), emptyList(),
        List(segments) { PhysicsSegment(10f / segments, 0.9f, 0.9f, 1.2f) }, PhysicsNormalization(angleMin = -10f, angleMax = 10f),
    )

    /** Parameters that already have a writer other than [except]; a group may not take them as outputs. */
    fun drivenParameters(groups: List<PhysicsGroup>, except: String? = null): Map<String, String> =
        groups.filter { it.id != except && it.active }.flatMap { g -> g.setting.outputParameters.map { it to g.id } }.toMap()

    /** Everything a `physics_put` changes, resolved against the current [groups]. */
    data class Request(val edit: RigPhysicsEdit?, val generated: RigPhysicsEdit?, val enabled: Boolean?)

    private val controlKeys = setOf("id", "enabled", "state", "expected_history_head_node_id", "task_id", "mode")

    /**
     * Reads `physics_put` arguments laid over the group with that ID (or a new template), so an agent
     * sends only what changes. `enabled` alone switches a group without replacing it.
     */
    fun request(groups: List<PhysicsGroup>, arguments: JsonObject, available: Set<String>): Request {
        val id = requireNotNull(arguments["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }) { "id is required" }
        val group = groups.firstOrNull { it.id == id }
        val enabled = arguments["enabled"]?.jsonPrimitive?.booleanOrNull
        val fields = JsonObject(arguments.filterKeys { it !in controlKeys })
        if (fields.isEmpty()) {
            require(enabled != null) { "Give the fields to change, or enabled" }
            requireNotNull(group) { "Physics group not found: $id" }
            return Request(null, group.generated, enabled)
        }
        val base = group?.setting ?: template(id, id, available)
        val edit = base.patched(fields)
        edit.parameters.firstOrNull { it !in available }?.let { throw IllegalArgumentException("Parameter $it does not exist; create it first") }
        PhysicsGenerator.issueOf(edit, available)?.let { throw IllegalArgumentException(it.message) }
        return Request(edit, group?.generated, enabled)
    }
}
