package io.github.psd2live.application

import io.github.psd2live.core.*
import kotlinx.serialization.json.*

internal object WorkspaceSkeletonDraftSchemas {
    private val s = WorkspaceResultSchema
    private val bones = s.array(s.handle(), 1, SkeletonDraftEdits.MAX_BONES)
    private fun intent(kind: String, fields: Map<String, JsonObject> = emptyMap(), required: Set<String> = fields.keys) =
        s.obj(fields + ("kind" to s.constant(kind)), required + "kind")
    private val transferFields = mapOf("source_id" to s.handle(), "target_id" to s.handle(),
        "mode" to s.choices(*SkeletonWeightTransferMode.entries.map { it.name }.toTypedArray()), "tolerance" to s.number(0, 4096),
        "mirror" to s.boolean(), "mapping" to s.dictionary(s.handle()))
    val transfer = s.obj(transferFields, setOf("source_id", "target_id", "mode"))
    val intents = s.union(listOf(
        intent("transform", mapOf("bone_ids" to bones, "dx" to s.number(), "dy" to s.number(), "degrees" to s.number(-3600, 3600),
            "scale" to buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0); put("maximum", 1000) },
            "descendants" to s.boolean(), "symmetric" to s.boolean()), setOf("bone_ids")),
        intent("move_joint", mapOf("bone_id" to s.handle(), "end" to s.choices("head", "tail"), "point" to s.vector(2),
            "symmetric" to s.boolean()), setOf("bone_id", "end", "point")),
        intent("duplicate", mapOf("bone_ids" to bones, "descendants" to s.boolean(), "transfer_bindings" to s.boolean(),
            "mirror" to s.boolean(), "suffix" to s.string()), setOf("bone_ids")),
        intent("subdivide", mapOf("bone_id" to s.handle(), "segments" to s.integer(2, 16))),
        intent("dissolve", mapOf("bone_id" to s.handle())),
        intent("chain", mapOf("role" to s.choices(BoneRole.TAIL.name, BoneRole.WING.name), "enabled" to s.boolean())),
        intent("create_bone", mapOf("head" to s.vector(2), "tail" to s.vector(2), "parent_id" to s.handle()), setOf("head", "tail")),
        intent("remove_bone", mapOf("bone_id" to s.handle())),
        intent("rename", mapOf("bone_id" to s.handle(), "name" to s.handle())),
        intent("parent", mapOf("bone_id" to s.handle(), "parent_id" to s.handle(), "connect" to s.boolean()), setOf("bone_id")),
        intent("blend_width", mapOf("bone_id" to s.handle(), "width" to s.number(0)), setOf("bone_id")),
        intent("limits", mapOf("bone_id" to s.handle(), "min" to s.number(-180, 0), "max" to s.number(0, 180))),
        intent("ik", mapOf("bone_id" to s.handle(), "settings" to s.obj(mapOf("chainLength" to s.integer(1, 32),
            "iterations" to s.integer(1, 256), "tolerancePx" to s.number(0.001, 10), "bendDirection" to s.integer(-1, 1)), emptySet()))),
        intent("bind", mapOf("drawable_ids" to JsonObject(s.array(s.handle(), 1, 256) + ("uniqueItems" to JsonPrimitive(true))),
            "bone_id" to s.handle()), setOf("drawable_ids")),
        intent("symmetry_axis", mapOf("x" to s.number())),
        intent("enabled", mapOf("enabled" to s.boolean())),
        intent("paint_weights", mapOf("drawable_id" to s.handle(), "bone_id" to s.handle(), "points" to s.array(s.vector(2), 1, 512),
            "radius" to buildJsonObject { put("type", "number"); put("exclusiveMinimum", 0); put("maximum", 4096) },
            "strength" to s.number(0, 1), "mode" to s.choices(*SkeletonWeightBrushMode.entries.map { it.name }.toTypedArray()),
            "replace_value" to s.number(0, 1)), setOf("drawable_id", "bone_id", "points", "radius", "strength", "mode")),
        intent("cleanup_weights", mapOf("drawable_id" to s.handle(), "influences" to s.integer(1, 2), "cutoff" to s.number(0, 1)), setOf("drawable_id")),
        intent("clear_weights", mapOf("drawable_id" to s.handle())),
        intent("transfer_weights", transferFields, setOf("source_id", "target_id", "mode")),
        intent("revert", mapOf("revision" to s.integer(0))),
    ))
    val session = s.obj(s.identity + mapOf("workspace_id" to s.handle(), "session_id" to s.handle(), "session_state" to s.handle(),
        "revision" to s.integer(0), "status" to s.choices("active", "cancelled", "committed", "superseded"), "stale" to s.boolean(),
        "draft" to WorkspaceAnimationResultSchemas.skeleton, "selected" to s.array(s.handle())),
        s.identity.keys + setOf("workspace_id", "session_id", "session_state", "revision", "status", "stale", "draft"))
    val list = s.obj(mapOf("sessions" to s.array(session)))
    val commit = s.obj(WorkspaceAuthoringResultSchemas.compactFields + ("session" to session), s.identity.keys + "session")
    val preview = s.obj(mapOf("session_id" to s.handle(), "session_state" to s.handle(), "mapping" to s.dictionary(s.handle()),
        "available" to s.boolean(), "matched" to s.integer(0), "unmatched" to s.integer(0)), setOf("session_id", "session_state", "mapping", "available"))

    fun transfer(input: JsonObject) = SkeletonDraftIntent.TransferWeights(input.text("source_id"), input.text("target_id"),
        SkeletonWeightTransferMode.valueOf(input.text("mode")), input["tolerance"]?.jsonPrimitive?.float ?: 10f,
        input["mirror"]?.jsonPrimitive?.boolean ?: false, input["mapping"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content })

    /** Public requests decode to the same typed intents the canvas editor builds. */
    fun parse(input: JsonObject, revision: (Long) -> SkeletonSpec?): SkeletonDraftIntent {
        fun bool(key: String) = input[key]?.jsonPrimitive?.boolean ?: false
        fun number(key: String) = input.getValue(key).jsonPrimitive.float
        fun point(key: String) = input.getValue(key).jsonArray.let { it[0].jsonPrimitive.float to it[1].jsonPrimitive.float }
        fun ids(key: String) = input.getValue(key).jsonArray.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        return when (val kind = input.text("kind")) {
            "transform" -> SkeletonDraftIntent.Transform(ids("bone_ids"), input["dx"]?.jsonPrimitive?.float ?: 0f,
                input["dy"]?.jsonPrimitive?.float ?: 0f, input["degrees"]?.jsonPrimitive?.float ?: 0f,
                input["scale"]?.jsonPrimitive?.float ?: 1f, bool("descendants"), bool("symmetric"))
            "move_joint" -> point("point").let { (x, y) ->
                SkeletonDraftIntent.MoveJoint(input.text("bone_id"), BoneEnd.valueOf(input.text("end").uppercase()), x, y, bool("symmetric"))
            }
            "duplicate" -> SkeletonDraftIntent.Duplicate(ids("bone_ids"), bool("descendants"), bool("transfer_bindings"), bool("mirror"),
                input["suffix"]?.jsonPrimitive?.content)
            "subdivide" -> SkeletonDraftIntent.Subdivide(input.text("bone_id"), input.getValue("segments").jsonPrimitive.int)
            "dissolve" -> SkeletonDraftIntent.Dissolve(input.text("bone_id"))
            "chain" -> SkeletonDraftIntent.OptionalChain(BoneRole.valueOf(input.text("role")), bool("enabled"))
            "create_bone" -> point("head").let { (hx, hy) -> point("tail").let { (tx, ty) ->
                SkeletonDraftIntent.CreateBone(hx, hy, tx, ty, input["parent_id"]?.jsonPrimitive?.content)
            } }
            "remove_bone" -> SkeletonDraftIntent.RemoveBone(input.text("bone_id"))
            "rename" -> SkeletonDraftIntent.Rename(input.text("bone_id"), input.text("name"))
            "parent" -> SkeletonDraftIntent.Parent(input.text("bone_id"), input["parent_id"]?.jsonPrimitive?.content, bool("connect"))
            "blend_width" -> SkeletonDraftIntent.BlendWidth(input.text("bone_id"), input["width"]?.jsonPrimitive?.float)
            "limits" -> SkeletonDraftIntent.Limits(input.text("bone_id"), number("min"), number("max"))
            "ik" -> SkeletonDraftIntent.Ik(input.text("bone_id"), SkeletonIkSettings.fromJson(input.getValue("settings").jsonObject))
            "bind" -> SkeletonDraftIntent.Bind(ids("drawable_ids"), input["bone_id"]?.jsonPrimitive?.content)
            "symmetry_axis" -> SkeletonDraftIntent.SymmetryAxis(number("x"))
            "enabled" -> SkeletonDraftIntent.Enabled(bool("enabled"))
            "paint_weights" -> SkeletonDraftIntent.PaintWeights(input.text("drawable_id"), input.text("bone_id"),
                input.getValue("points").jsonArray.map { p -> p.jsonArray.let { it[0].jsonPrimitive.float to it[1].jsonPrimitive.float } },
                number("radius"), number("strength"), SkeletonWeightBrushMode.valueOf(input.text("mode")),
                input["replace_value"]?.jsonPrimitive?.float ?: 1f)
            "cleanup_weights" -> SkeletonDraftIntent.CleanupWeights(input.text("drawable_id"), input["influences"]?.jsonPrimitive?.int ?: 2,
                input["cutoff"]?.jsonPrimitive?.float ?: 0.001f)
            "clear_weights" -> SkeletonDraftIntent.ClearWeights(input.text("drawable_id"))
            "transfer_weights" -> transfer(input)
            "revert" -> input.getValue("revision").jsonPrimitive.long.let { target ->
                SkeletonDraftIntent.Restore(requireNotNull(revision(target)) { "Skeleton draft revision not retained: $target" })
            }
            else -> throw IllegalArgumentException("Unknown skeleton draft edit: $kind")
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}

internal fun registerSkeletonDraftOperations(registry: WorkspaceOperationRegistry, port: WorkspaceSkeletonDraftPort) {
    val s = WorkspaceResultSchema
    val schemas = WorkspaceSkeletonDraftSchemas
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    fun session(draft: WorkspaceSkeletonDraft) = WorkspaceOperationOutput(draft.toJson())
    registry.register(WorkspaceOperationDefinition("skeleton_draft_open",
        "Open the workspace's skeleton edit draft from the committed armature, as the Skeleton Edit tool does. Resets the workspace pose to rest as the draft's own pose commit; the returned state is the draft's lineage, which edit and commit must carry. Opening again supersedes the previous draft of the workspace.",
        s.obj(mapOf("state" to s.handle())), WorkspaceOperationKind.SESSION, resultSchema = schemas.session)) { request, _ ->
        session(port.openSkeletonDraft(request.text("state")))
    }
    registry.register(WorkspaceOperationDefinition("skeleton_draft_list",
        "List skeleton drafts, including the one the canvas editor has open, with their lineage state, revision and whether the workspace has moved past them (stale).",
        s.obj(emptyMap()), WorkspaceOperationKind.QUERY, resultSchema = schemas.list)) { _, _ ->
        WorkspaceOperationOutput(buildJsonObject { put("sessions", JsonArray(port.skeletonDrafts().map { it.toJson() })) })
    }
    registry.register(WorkspaceOperationDefinition("skeleton_draft_get",
        "Read one skeleton draft: its private armature, revision, lineage state and status. Cancelled, committed and superseded drafts remain readable.",
        s.obj(mapOf("session_id" to s.handle())), WorkspaceOperationKind.QUERY, resultSchema = schemas.session)) { request, _ ->
        session(port.skeletonDraft(request.text("session_id")))
    }
    registry.register(WorkspaceOperationDefinition("skeleton_draft_edit",
        "Apply 1..128 edits to a private skeleton draft in order, all or none: batch transform, joint moves, copy or mirror (symmetric partners and opposite-side meshes), subdivide, dissolve, optional tail/wing chains, bone creation and removal, renaming, parenting, blend width, limits, IK, bindings, symmetry axis, enabled, manual weight painting (one stroke per edit, canvas pixels), cleanup, clearing and weight transfer, or revert to an earlier revision. Pass the draft's lineage state and latest session_state. Nothing reaches the workspace until skeleton_draft_commit.",
        s.obj(mapOf("state" to s.handle(), "session_id" to s.handle(), "session_state" to s.handle(),
            "edits" to s.array(schemas.intents, 1, 128))), WorkspaceOperationKind.SESSION, resultSchema = schemas.session)) { request, _ ->
        val id = request.text("session_id")
        val intents = request.getValue("edits").jsonArray.map { edit -> schemas.parse(edit.jsonObject) { port.skeletonDraftRevision(id, it) } }
        session(port.editSkeletonDraft(id, request.text("state"), request.text("session_state"), intents))
    }
    registry.register(WorkspaceOperationDefinition("skeleton_draft_preview_transfer",
        "Preview a manual weight transfer between two meshes of a skeleton draft: the inferred source-to-target bone mapping after overrides, and how many target vertices would receive a weight. available=false when the meshes cannot exchange weights in that mode.",
        s.obj(mapOf("session_id" to s.handle(), "transfer" to schemas.transfer)), WorkspaceOperationKind.QUERY, resultSchema = schemas.preview)) { request, _ ->
        val id = request.text("session_id")
        val draft = port.skeletonDraft(id)
        val (mapping, transfer) = port.previewSkeletonWeightTransfer(id, schemas.transfer(request.getValue("transfer").jsonObject))
        WorkspaceOperationOutput(buildJsonObject {
            put("session_id", id); put("session_state", draft.sessionState)
            putJsonObject("mapping") { mapping.forEach { (from, to) -> put(from, to) } }
            put("available", transfer != null)
            transfer?.let { put("matched", it.matched); put("unmatched", it.unmatched) }
        })
    }
    registry.register(WorkspaceOperationDefinition("skeleton_draft_commit",
        "Commit a skeleton draft as one history edit, checked against the draft's own lineage state. A pose or document change after opening, a reopened project or a newer draft makes it fail without writing; an unchanged armature adds no history node.",
        s.obj(mapOf("state" to s.handle(), "session_id" to s.handle(), "session_state" to s.handle())), WorkspaceOperationKind.DOCUMENT,
        resultSchema = schemas.commit)) { request, context ->
        val result = port.commitSkeletonDraft(request.text("session_id"), request.text("state"), request.text("session_state"), context.author)
        WorkspaceOperationOutput(JsonObject(result.mutation.compact() + ("session" to result.session.toJson())))
    }
    registry.register(WorkspaceOperationDefinition("skeleton_draft_cancel",
        "Discard a skeleton draft. The rest pose its opening committed stays; the armature is unchanged.",
        s.obj(mapOf("state" to s.handle(), "session_id" to s.handle())), WorkspaceOperationKind.SESSION, resultSchema = schemas.session)) { request, _ ->
        session(port.cancelSkeletonDraft(request.text("session_id")))
    }
}
