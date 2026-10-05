package io.github.psd2live.agent

import io.github.psd2live.application.WorkspaceSkeletonMotionEdits

import io.github.psd2live.application.WorkspaceBackendStub
import io.github.psd2live.project.WorkspaceLayerSnapshot
import io.github.psd2live.project.WorkspaceModelViewRequest
import io.github.psd2live.project.WorkspaceProjectSnapshot
import io.github.psd2live.project.WorkspaceRenderedView
import io.github.psd2live.project.WorkspaceViewBackground
import io.github.psd2live.project.WorkspaceViewOutputSpec
import io.github.psd2live.project.WorkspaceMutationResult

import io.github.psd2live.core.Bounds
import io.github.psd2live.core.LayerClassificationOverride
import io.github.psd2live.core.LayerType
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSpec
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class AuthoringParityTest {
    private val connection = java.lang.reflect.Proxy.newProxyInstance(
        ClientConnection::class.java.classLoader,
        arrayOf(ClientConnection::class.java),
    ) { _, method, _ -> if (method.name == "getSessionId") "test" else error("Unexpected MCP client call") } as ClientConnection
    private class Workspace : WorkspaceBackendStub() {
        var classified: Pair<String, LayerClassificationOverride>? = null
        var armature: SkeletonSpec? = null
        var clips: List<MotionClip> = emptyList()
        override fun skeletonSpec() = armature
        override fun proposeSkeleton() = SkeletonSpec(bones = listOf(
            SkeletonBone("body", "Body", null, BoneRole.LOWER_BODY, headX = 0f, headY = 0f, tailX = 0f, tailY = 10f)))
        override suspend fun editSkeleton(state: String, request: JsonObject): WorkspaceMutationResult {
            assertEquals("load:1:0", state)
            armature = WorkspaceSkeletonMotionEdits.skeleton(armature, request, ::proposeSkeleton)
            return WorkspaceMutationResult("next", "revision-next", summary = "skeleton", state = "load:1:1", projectId = "test")
        }
        override fun motionClips() = clips
        override suspend fun editMotion(state: String, request: JsonObject): WorkspaceMutationResult {
            assertEquals("load:1:0", state)
            val ranges = mapOf("ParamArmLA" to (-90f..150f))
            clips = WorkspaceSkeletonMotionEdits.motion(clips, request, ranges, armature)
            return WorkspaceMutationResult("next", "revision-next", summary = "motion", state = "load:1:1", projectId = "test")
        }
        override fun snapshot() = WorkspaceProjectSnapshot(
            projectId = "test", revisionId = "revision", historyHeadNodeId = "head", loaded = true,
            inputName = "test", canvasWidth = 32, canvasHeight = 32, busy = false, status = "ready",
            selectedLayerId = null, parameters = emptyList(), state = "load:1:0",
            layers = listOf(WorkspaceLayerSnapshot(
                id = "hair", sourceName = "hair", rasterWidth = 16, rasterHeight = 16,
                groupPath = "", order = 0, semanticTag = "front_hair", side = "left",
                confidence = 1f, bounds = Bounds(0f, 0f, 16f, 16f),
                opaqueBounds = Bounds(0f, 0f, 16f, 16f), visible = true, deleted = false,
            )),
        )
        override suspend fun classifyLayer(layerId: String, fields: JsonObject, expectedState: String): WorkspaceMutationResult {
            assertEquals("load:1:0", expectedState)
            val classification = LayerClassificationOverride(
                type = fields["type"]?.jsonPrimitive?.content?.uppercase()?.let(LayerType::valueOf) ?: LayerType.PRESET,
                tag = fields["role"]?.jsonPrimitive?.content?.uppercase()?.let(SemanticTag::valueOf) ?: SemanticTag.FRONT_HAIR,
                side = fields["side"]?.jsonPrimitive?.content?.uppercase()?.let(io.github.psd2live.core.Side::valueOf) ?: io.github.psd2live.core.Side.LEFT,
                parameter = fields["parameter"]?.jsonPrimitive?.content ?: "", switchId = fields["switch_id"]?.jsonPrimitive?.int ?: 0)
            classified = layerId to classification
            return WorkspaceMutationResult("next", "revision-next", listOf(layerId), "classified", state = "load:1:1", projectId = "test")
        }
        override suspend fun renderLayer(layerId: String, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("unused")
        override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: WorkspaceViewBackground, output: WorkspaceViewOutputSpec): WorkspaceRenderedView = error("unused")
        override suspend fun renderModel(request: WorkspaceModelViewRequest): WorkspaceRenderedView = error("unused")
    }

    @Test fun classificationMergesOmittedFieldsAndRejectsInvalidRequests() = runBlocking {
        val workspace = Workspace()
        val server = createAgentMcpServer(workspace)
        assertTrue(server.tools.keys.all { Regex("[a-z][a-z0-9]*(?:_[a-z0-9]+)+").matches(it) })
        val layer = server.tools.getValue("layer_classify")
        val result = layer.handler.invoke(connection, CallToolRequest(CallToolRequestParams("layer_classify", buildJsonObject { putJsonObject("request") {
            put("project_id", "test"); put("request_id", java.util.UUID.randomUUID().toString()); put("state", "load:1:0"); put("layer_id", "hair"); put("type", "switch")
            put("parameter", "expression"); put("switch_id", 2)
        } })))
        assertFalse(result.isError == true)
        val job = result.structuredContent!!.getValue("data").jsonObject.getValue("id")
        val terminal = server.tools.getValue("job_wait").handler.invoke(connection, CallToolRequest(CallToolRequestParams("job_wait",
            buildJsonObject { putJsonObject("request") { put("id", job) } }))).structuredContent!!.getValue("data").jsonObject
        assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
        assertEquals("load:1:1", terminal.getValue("result").jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals("hair", workspace.classified?.first)
        assertEquals(LayerType.SWITCH, workspace.classified?.second?.type)
        assertEquals(SemanticTag.FRONT_HAIR, workspace.classified?.second?.tag)
        assertEquals("expression", workspace.classified?.second?.parameter)
        assertEquals(2, workspace.classified?.second?.switchId)

        workspace.classified = null
        val invalid = layer.handler.invoke(connection, CallToolRequest(CallToolRequestParams("layer_classify", buildJsonObject { putJsonObject("request") {
            put("project_id", "test"); put("request_id", java.util.UUID.randomUUID().toString()); put("state", "load:1:0"); put("layer_id", "hair"); put("switch_id", -1)
        } })))
        assertTrue(invalid.isError == true)
        assertNull(workspace.classified)
    }

    @Test fun publicSurfaceIncludesParityRoutes() {
        val server = createAgentMcpServer(Workspace())
        assertTrue(server.tools.keys.containsAll(listOf("layer_classify", "layer_mesh_update", "source_paint_pencil", "preview_set", "rig_edit_structure", "canvas_topology", "settings_update", "parameter_delete", "project_export_model", "project_export_psd", "swing_put", "skeleton_auto", "motion_set_key", "simulation_bake", "model_apply_preset", "vertex_group_update", "project_import_psd")))
        assertTrue(server.tools.keys.intersect(setOf("layer", "paint", "skeleton", "motion", "parameter", "asset", "revision")).isEmpty())
    }

    @Test fun skeletonAndMotionRoutesExposePersistentEdits() = runBlocking {
        val workspace = Workspace()
        val server = createAgentMcpServer(workspace)
        suspend fun call(name: String, request: JsonObject): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult {
            val tool = server.tools.getValue(name)
            val input = if (tool.tool.annotations?.readOnlyHint == true) request else JsonObject(request + mapOf(
                "project_id" to JsonPrimitive("test"), "request_id" to JsonPrimitive(java.util.UUID.randomUUID().toString())))
            return tool.handler.invoke(connection, CallToolRequest(CallToolRequestParams(name, buildJsonObject { put("request", input) })))
        }
        val created = call("skeleton_auto", buildJsonObject { put("state", "load:1:0") })
        assertFalse(created.isError == true)
        assertEquals("load:1:1", created.structuredContent?.get("data")?.jsonObject?.get("state")?.jsonPrimitive?.content)
        val read = call("skeleton_get", buildJsonObject {})
        assertEquals("body", read.structuredContent?.get("data")?.jsonObject?.get("spec")?.jsonObject?.get("bones")?.jsonArray?.single()?.jsonObject?.get("id")?.jsonPrimitive?.content)
        workspace.armature = workspace.armature!!.copy(
            bones = workspace.armature!!.bones.map { it.copy(connected = false, parameterOverride = "ParamBodyCopy", mirrorId = "partner") },
            symmetryAxisX = 16f,
            savedPoses = mapOf("Rest" to mapOf("ParamBodyCopy" to 0f)),
            ikTargets = mapOf("body" to io.github.psd2live.core.SkeletonIkTarget(0f, 10f)),
            manualWeights = mapOf("mesh" to io.github.psd2live.core.SkeletonWeightMap(
                listOf(0f, 0f), emptyList(), listOf(mapOf("body" to 1f)))),
        )
        val authored = workspace.armature!!
        val roundTrippedSkeleton = call("skeleton_put", buildJsonObject {
             put("state", "load:1:0"); put("spec", authored.toJson())
        })
        assertFalse(roundTrippedSkeleton.isError == true)
        assertEquals(authored, workspace.armature)
        val written = call("motion_put", buildJsonObject {
             put("state", "load:1:0")
            put("clip", buildJsonObject { put("id", "custom"); put("name", "Custom") })
        })
        assertFalse(written.isError == true)
        val clip = call("motion_get", buildJsonObject { put("id", "custom") })
        assertEquals("Custom", clip.structuredContent?.get("data")?.jsonObject?.get("clip")?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals(MotionClips.toJson(workspace.clips.single()), clip.structuredContent?.get("data")?.jsonObject?.get("clip"))
        val roundTrippedClip = call("motion_put", buildJsonObject {
             put("state", "load:1:0"); put("clip", clip.structuredContent!!.getValue("data").jsonObject.getValue("clip"))
        })
        assertFalse(roundTrippedClip.isError == true)
        val sampled = call("motion_sample", buildJsonObject { put("id", "custom"); put("time", 0.5) })
        assertFalse(sampled.isError == true)
        assertTrue(sampled.structuredContent?.get("data")?.jsonObject?.get("values")?.jsonObject?.isEmpty() == true)
    }
}
