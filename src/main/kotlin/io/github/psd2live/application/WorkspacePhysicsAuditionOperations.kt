package io.github.psd2live.application

import kotlinx.serialization.json.*

internal object WorkspacePhysicsAuditionSchemas {
    private val s = WorkspaceResultSchema
    private val values = s.dictionary(s.number())
    val result = s.obj(s.identity + mapOf("workspace_id" to s.handle(), "session_id" to s.handle(), "group_id" to s.handle(),
        "status" to s.choices("running", "stopped"), "stale" to s.boolean(), "serial" to s.integer(0), "elapsed" to s.number(0),
        "drag" to s.vector(2), "x" to s.array(s.number()), "y" to s.array(s.number()),
        "outputs" to s.dictionary(s.number()), "peaks" to s.dictionary(s.number(0))))
    private fun session(mode: String, extra: Map<String, JsonObject> = emptyMap()) =
        s.obj(mapOf("mode" to s.constant(mode), "state" to s.handle(), "session_id" to s.handle()) + extra)
    val control = s.union(listOf(
        s.obj(mapOf("mode" to s.constant("start"), "state" to s.handle(), "group_id" to s.handle(), "values" to values),
            setOf("mode", "state", "group_id")),
        session("target", mapOf("x" to s.number(-1, 1), "y" to s.number(-1, 1))),
        session("release"), session("reset"), session("reset_peaks"), session("stop"),
    ))
    val step = s.obj(mapOf("state" to s.handle(), "session_id" to s.handle(), "dt" to s.number(0.000001, 0.1),
        "steps" to s.integer(1, 240), "values" to values), setOf("state", "session_id", "dt"))
}

/** The panel's pendulum audition and its observed peaks, which physics_fit observed_peaks takes. */
internal fun registerPhysicsAuditionOperations(registry: WorkspaceOperationRegistry, port: WorkspacePhysicsAuditionPort) {
    val s = WorkspaceResultSchema
    registry.register(WorkspaceOperationDefinition("physics_audition",
        "Audition one physics group's pendulum as the physics panel does, without changing the document, history or saved poses. " +
            "start opens a session on group_id (driven by the workspace's authored pose, optionally overlaid by values) and stops this workspace's previous one; " +
            "target drags the pendulum root toward x,y in -1..1 (the panel's pointer), release lets it go, reset brings it to rest, reset_peaks restarts the peak record, stop ends it. " +
            "A document edit retunes the session's group and keeps it swinging; a reopened project or a deleted group makes it stale. " +
            "Each output's peak is its reach toward the parameter's end since the last reset (1 = the end): pass them to physics_fit observed_peaks.",
        WorkspacePhysicsAuditionSchemas.control, WorkspaceOperationKind.SESSION, workspaceBound = true,
        resultSchema = WorkspacePhysicsAuditionSchemas.result)) { request, _ ->
        WorkspaceOperationOutput(port.controlPhysicsAudition(request))
    }
    registry.register(WorkspaceOperationDefinition("physics_audition_step",
        "Advance a running physics audition by dt seconds (at most 0.1) for 1..240 steps, under the current drag and optional transient values. " +
            "The steps are solved on a copy, so a rejected request leaves the pendulum where it was. Returns the pendulum vertices, outputs and peaks.",
        WorkspacePhysicsAuditionSchemas.step, WorkspaceOperationKind.SESSION, workspaceBound = true,
        resultSchema = WorkspacePhysicsAuditionSchemas.result)) { request, _ ->
        WorkspaceOperationOutput(port.stepPhysicsAudition(request))
    }
    registry.register(WorkspaceOperationDefinition("physics_audition_get",
        "Read a physics audition's latest pendulum vertices, outputs, peaks, drag, elapsed time and stale status without advancing it.",
        s.obj(mapOf("session_id" to s.handle())), WorkspaceOperationKind.QUERY,
        resultSchema = WorkspacePhysicsAuditionSchemas.result)) { request, _ ->
        WorkspaceOperationOutput(port.physicsAudition(request.getValue("session_id").jsonPrimitive.content))
    }
}
