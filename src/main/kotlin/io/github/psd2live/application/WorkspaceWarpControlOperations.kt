package io.github.psd2live.application

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal object WorkspaceWarpControlSchemas {
    private val s = WorkspaceResultSchema
    fun request(id: String, includeState: Boolean = true): JsonObject {
        val fields = linkedMapOf("target" to s.handle())
        val required = mutableSetOf("target")
        if (includeState) { fields["state"] = s.handle(); required += "state" }
        if (id != "warp_set_topology") {
            fields["coordinate"] = s.dictionary(s.number()); fields["pose"] = s.dictionary(s.number())
        }
        when (id) {
            "warp_set_topology", "warp_bezier_divisions" -> {
                val maximum = if (id == "warp_set_topology") 32 else 16
                fields["rows"] = s.integer(1, maximum); fields["columns"] = s.integer(1, maximum)
                required += setOf("rows", "columns")
            }
            "warp_bezier_anchor", "warp_bezier_handle" -> {
                fields["row"] = s.integer(0, 16); fields["column"] = s.integer(0, 16)
                fields["x"] = s.number(); fields["y"] = s.number()
                required += setOf("row", "column", "x", "y")
                if (id == "warp_bezier_handle") {
                    fields["direction"] = s.choices("left", "right", "top", "bottom"); fields["smooth"] = s.boolean(); required += "direction"
                }
            }
            "warp_bezier_reset" -> Unit
            else -> error("Unknown Warp control operation")
        }
        if (id in setOf("warp_bezier_anchor", "warp_bezier_handle", "warp_bezier_reset")) fields["preserve_children"] = s.boolean()
        return s.obj(fields, required)
    }
    fun result(id: String): JsonObject {
        val fields = WorkspaceAuthoringResultSchemas.compactFields + ("target" to s.handle()) + if (id == "warp_set_topology") mapOf(
            "max_surface_error" to s.number(0), "error_space" to s.constant("parent_local"), "probe_divisions" to s.integer(64, 64)) else emptyMap()
        return s.obj(fields, s.identity.keys + "target" + if (id == "warp_set_topology") setOf("max_surface_error", "error_space", "probe_divisions") else emptySet())
    }
}

internal fun registerWarpControlOperations(registry: WorkspaceOperationRegistry, workspace: WorkspaceBackend, jobs: WorkspaceJobs) {
    val s = WorkspaceResultSchema
    val control = s.union(listOf("anchor", "handle").map { kind ->
        s.obj(mapOf("kind" to s.constant(kind), "row" to s.integer(0, 16), "column" to s.integer(0, 16), "x" to s.number(), "y" to s.number()) +
            if(kind == "handle") mapOf("direction" to s.choices("left", "right", "top", "bottom")) else emptyMap())
    })
    registry.register(WorkspaceOperationDefinition("warp_get_controls",
        "Read Bezier divisions and editor controls from one committed capture. summary omits controls; points pages anchors then handles up to 256. Positions are parent-local; persisted=false means controls were reconstructed from the sampled lattice. CMO3 stores sampled geometry and division metadata, without these editor handles.",
        s.obj(mapOf("target" to s.handle(), "coordinate" to s.dictionary(s.number()), "detail" to s.choices("summary", "points"),
            "offset" to s.integer(0), "limit" to s.integer(1, 256)), setOf("target")), WorkspaceOperationKind.QUERY,
        resultSchema = s.obj(mapOf("state" to s.handle(), "revisionId" to s.handle(), "target" to s.handle(),
            "persisted" to s.boolean(), "representation" to s.constant("journal_controls_with_sampled_residual"),
            "rows" to s.integer(1, 16), "columns" to s.integer(1, 16), "coordinateSpace" to s.constant("parent_local_x_right_y_down"),
            "anchorCount" to s.integer(4), "handleCount" to s.integer(8), "cmo3_has_handles" to s.constant(false),
            "controls" to s.array(control, 0, 256), "nextOffset" to s.integer(0)),
            setOf("state", "revisionId", "target", "persisted", "representation", "rows", "columns", "coordinateSpace", "anchorCount", "handleCount", "cmo3_has_handles")))) { request, _ ->
        val target = request.getValue("target").jsonPrimitive.content
        require(target.startsWith("warp:") && target.substringAfter(':').isNotBlank()) { "Use a warp:id handle" }
        val queries = workspace.captureQueries()
        val inspection = queries.inspectRigGeometry(JsonObject((request - "target") + buildJsonObject {
            putJsonObject("target") { put("kind", "warp"); put("id", target.substringAfter(':')) }
        }))
        val controls = inspection.getValue("editorBezier").jsonObject
        WorkspaceOperationOutput(JsonObject((controls - "available") + buildJsonObject {
            put("state", queries.snapshot().state); put("revisionId", inspection.getValue("revisionId")); put("target", target)
            put("cmo3_has_handles", false)
            controls["controls"]?.jsonArray?.let { entries -> put("controls", JsonArray(entries.map { entry ->
                val kind = entry.jsonObject.getValue("kind").jsonPrimitive.content
                val point = entry.jsonObject.getValue("value").jsonArray
                buildJsonObject {
                    put("kind", kind); put("row", point[0]); put("column", point[1])
                    if(kind == "handle") { put("direction", point[2]); put("x", point[3]); put("y", point[4]) }
                    else { put("x", point[2]); put("y", point[3]) }
                }
            })) }
        }))
    }
    for (id in WorkspaceWarpControlEdits.supported) registry.register(WorkspaceOperationDefinition(id,
        when (id) {
            "warp_set_topology" -> "Resample all ordinary and additive Warp forms to rows/columns in 1..32 using its runtime interpolation. Preserves channels, blend limits, identity and parent. Coarsening can approximate the surface; returns maximum parent-local component error on a 64-division probe grid."
            "warp_bezier_divisions" -> "Persist Bezier edit divisions in 1..16 without changing geometry. Coordinates and handles are parent-local. Omitted coordinate edits the reference lattice; supplied coordinate identifies the exact ordinary or additive key."
            "warp_bezier_anchor" -> "Move one Bezier anchor to absolute parent-local x/y and translate its handles. Persists editable controls and sampled lattice in one history edit."
            "warp_bezier_handle" -> "Move a Bezier tangent to absolute parent-local x/y. smooth=true preserves opposite-handle length and aligns its direction. Invalid boundary handles are rejected."
            else -> "Refit smooth Bezier controls from the displayed Warp lattice and apply their sampled surface. Explicitly resets residual detail."
        } + " Returns a job handle; use job_wait for the committed result.", WorkspaceWarpControlSchemas.request(id),
        WorkspaceOperationKind.DOCUMENT, batchable = true, jobBacked = true,
        resultSchema = requireNotNull(WorkspaceJobResultSchemas.operationOutput(id)), jobResultSchema = WorkspaceWarpControlSchemas.result(id))) { request, context ->
        startWorkspaceOperationJob(workspace, jobs, id) {
            withContext(WorkspaceWarpControlJobExecution(requireNotNull(currentCoroutineContext()[WorkspaceJobCompletion]))) {
                WorkspaceOperationOutput(workspace.editWarpControls(id, request.getValue("state").jsonPrimitive.content, JsonObject(request - "state"), context.author))
            }
        }
    }
}
