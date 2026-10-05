package io.github.psd2live.application

import kotlinx.serialization.json.*

internal object WorkspaceHistoryPhysicsResultSchemas {
    private val s = WorkspaceResultSchema
    private val nodeFields = mapOf("id" to s.handle(), "parentId" to s.handle(), "revisionId" to s.handle(),
        "summary" to s.string(), "actor" to s.handle(), "taskId" to s.string(), "createdAt" to s.string(), "isHead" to s.boolean())
    private val history = s.obj(mapOf("headNodeId" to s.string(), "nodes" to s.array(s.obj(nodeFields, nodeFields.keys - setOf("parentId", "taskId")))))
    private val phaseFields = listOf("start", "peak", "peak_time", "final", "settle_time").associateWith { s.number() } + ("settled" to s.boolean())
    // A zero-length hold or release has no phase metrics.
    private val phase = s.union(listOf(s.obj(emptyMap()), s.obj(phaseFields)))
    private val reportFields = mapOf("fps" to s.integer(0, 240), "hold" to s.number(0, 20), "duration" to s.number(0.1, 20), "note" to s.string(),
        "outputs" to s.array(s.obj(mapOf("parameter" to s.handle(), "group" to s.handle(),
            "while_held" to phase, "after_release" to phase, "samples" to s.array(s.vector(2), 2, 60)))))
    private val physics = s.obj(reportFields, reportFields.keys - "note")
    fun forOperation(id: String): JsonObject? = when (id) {
        "history_list" -> history
        "physics_simulate" -> physics
        else -> null
    }
}
