package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.ParameterId
import java.nio.file.Path
import kotlin.test.*

class WorkspaceReadSessionTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val config = PipelineConfig(atlasSize = 256, meshOnly = true, generateDeformers = false, generatePhysics = false)
    private suspend fun seed(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val (png, _) = writeSourceImportFixture(temporary)
        return WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state,
            initialConfig = config).capture
    }
    private fun runtime() = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
    private val context = WorkspaceOperationContext(MutationAuthor.AGENT)

    @Test fun detachedQueriesKeepModelDocumentHistoryAndAuthoredPoseFromOneVersion() = runBlocking<Unit> {
        val runtime = runtime()
        val seed = seed(runtime)
        val parameter = ParameterId("ReadPose")
        val commands = WorkspaceDocumentCommands(runtime)
        val created = commands.execute(seed.projectId, seed.state, "Create pose parameter", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject {
                put("parameter_id", parameter.raw); put("name", "Before"); put("min", -1); put("max", 1); put("default", 0)
            })), MutationAuthor.USER).capture
        val posed = runtime.updateAuxiliary(created.projectId, created.state, buildJsonObject {
            putJsonObject("posesByWorkspace") { put("canvas", PreviewSessions.encode(WorkspacePose(mapOf(parameter to 0.5f), setOf(parameter)))) }
        })
        val read = WorkspaceReadSession(runtime.read(), WorkspaceQueryPresentation(workspaceId = "canvas"))
        val oldSnapshot = read.snapshot()
        val oldHistory = read.history()
        val mesh = read.listRigObjects().first { it.kind == "mesh" }
        val oldObject = read.getObject(mesh)
        val layerId = oldSnapshot.layers.first { !it.deleted }.id
        val oldMesh = read.layerMeshSettings(layerId)
        val changed = commands.execute(posed.projectId, posed.state, "Edit several domains", listOf(
            WorkspaceDocumentOperation("parameter_update", buildJsonObject { put("parameter_id", parameter.raw); put("name", "After") }),
            WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("meshOuterMargin", 5) } }),
            WorkspaceDocumentOperation("rig_edit_structure", buildJsonObject {
                putJsonArray("edits") { add(buildJsonObject { put("action", "rename"); put("kind", "mesh"); put("id", mesh.id); put("name", "After mesh") }) }
            }),
            WorkspaceDocumentOperation("physics_config", buildJsonObject { put("fps", 120) }),
        ), MutationAuthor.AGENT).capture
        assertNotEquals(posed.state, changed.state)
        assertEquals(oldSnapshot, read.snapshot())
        assertEquals(oldHistory, read.history())
        assertEquals(oldObject, read.getObject(mesh))
        assertEquals(oldMesh, read.layerMeshSettings(layerId))
        assertEquals(posed.document.settings, read.projectSettings())
        assertEquals(60, read.physicsFps())
        assertEquals(0.5f, read.previewSession().getValue("values").jsonObject.getValue(parameter.raw).jsonPrimitive.float)
        assertEquals(listOf(parameter.raw), read.previewSession().getValue("locked").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0.5f, read.snapshot().parameters.single { it.id == parameter.raw }.current)
        val latest = WorkspaceReadSession(runtime.read())
        assertEquals("After", latest.snapshot().parameters.single { it.id == parameter.raw }.name)
        assertEquals("After mesh", latest.getObject(mesh).name)
        assertEquals(120, latest.physicsFps())
        assertEquals(oldHistory.nodes.size + 1, latest.history().nodes.size)
        assertEquals(changed, runtime.capture(), "Every query is read-only")
    }

    @Test fun deletedLayerDetailsComeFromTheDocumentWithoutAHostTombstoneCache() = runBlocking<Unit> {
        val runtime = runtime(); val seed = seed(runtime)
        val original = seed.document.source.layers.single()
        val retained = object : org.umamo.format.art.SourceLayer by original {
            override val id = org.umamo.format.art.LayerId("retained")
        }
        val document = seed.document.copy(source = WorkspaceSourceArt(seed.document.source.widthPx, seed.document.source.heightPx,
            listOf(original, retained), seed.document.source.groups))
        val installed = runtime.install(seed.state, seed.projectId, document, builder.build(document), discardUnsaved = true)
        val before = WorkspaceReadSession(runtime.read()).snapshot().layers.single { it.id == original.id.raw }
        runtime.execute(installed.projectId, installed.state, "Delete source", MutationAuthor.USER, listOf(
            WorkspaceDocumentEdit { current, _ -> current.copy(deletedLayerIds = setOf(original.id.raw)) }))
        val read = WorkspaceReadSession(runtime.read())
        val deleted = read.snapshot().layers.single { it.id == original.id.raw }
        assertEquals(before.copy(deleted = true, visible = false), deleted)
        assertEquals(document.source.layers.size, read.snapshot().layers.size)
        assertFalse(original.id.raw in read.currentPuppet()!!.drawables.map { it.id.raw })
    }

    @Test fun deletedLegacyComponentsKeepTheirIdsAndDetailsWithoutHostCaches() = runBlocking<Unit> {
        val runtime = runtime(); val seed = seed(runtime)
        val width = 80; val height = 32
        val rgba = ByteArray(width * height * 4)
        for (y in 4..27) for (x in 4..75) if (x <= 23 || x >= 56) {
            val at = (y * width + x) * 4
            rgba[at] = 90; rgba[at + 1] = 110; rgba[at + 2] = 120; rgba[at + 3] = -1
        }
        val source = WorkspaceSourceLayer(org.umamo.format.art.LayerId("paired"), "Objects", "", org.umamo.format.art.SourceLayerKind.Raster,
            true, 0, org.umamo.format.art.LayerBounds(0, 0, width, height), 1f, false, org.umamo.format.art.LayerBlend.Normal,
            org.umamo.format.art.ChannelMask.ALL, org.umamo.format.art.LayerRaster(width, height, rgba), null, null, false)
        val document = seed.document.copy(source = WorkspaceSourceArt(width, height, listOf(source), emptyList()),
            layerOverrides = mapOf("paired:l" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)),
            settings = WorkspaceSettingsCodec.encode(config.copy(meshSpacing = 6)))
        val installed = runtime.install(seed.state, seed.projectId, document, builder.build(document), discardUnsaved = true)
        val layers = WorkspaceReadSession(runtime.read()).snapshot().layers
        assertEquals(setOf("paired:l", "paired:r"), layers.map { it.id }.toSet())
        val before = layers.single { it.id == "paired:l" }
        runtime.execute(installed.projectId, installed.state, "Delete component", MutationAuthor.USER, listOf(
            WorkspaceDocumentEdit { current, _ -> current.copy(deletedLayerIds = setOf(before.id)) }))
        val read = WorkspaceReadSession(runtime.read())
        assertEquals(before.copy(visible = false, deleted = true), read.snapshot().layers.single { it.id == before.id })
        assertEquals(setOf("paired:l", "paired:r"), read.snapshot().layers.map { it.id }.toSet())
        val discovered = read.inspect(buildJsonObject { put("scope", "layers") }).getValue("items").jsonArray
        assertEquals("paired:r", discovered.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertNotNull(discovered.single().jsonObject["mesh"])
    }

    @Test fun unloadedAndReopenedCapturesRemainDetached() = runBlocking<Unit> {
        val runtime = runtime()
        val empty = WorkspaceReadSession(runtime.read())
        val unloaded = empty.snapshot()
        val seed = seed(runtime)
        assertEquals(unloaded, empty.snapshot())
        assertFalse(empty.snapshot().loaded)
        assertNull(empty.currentPuppet())
        val loaded = WorkspaceReadSession(runtime.read())
        runtime.install(seed.state, seed.projectId, seed.document, seed.model, runtime.history(seed.state), discardUnsaved = true)
        assertEquals(seed.state, loaded.snapshot().state)
        assertNotEquals(seed.state, runtime.capture().state)
        assertEquals(seed.historyHead, loaded.history().headNodeId)
        assertEquals(seed.document.settings, loaded.projectSettings())
    }

    @Test fun publicCompositeQueriesCaptureOnceEvenWhenTheHostChangesDuringCapture() = runBlocking<Unit> {
        val runtime = runtime()
        val seed = seed(runtime)
        var captures = 0
        val host = object : WorkspaceBackendStub() {
            override fun snapshot() = WorkspaceReadSession(runtime.read()).snapshot()
            override fun captureQueries(): WorkspaceQueries {
                captures++
                val query = WorkspaceReadSession(runtime.read())
                val before = runtime.capture()
                runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("capture", captures) })
                return query
            }
            // All live business queries inherited from the fixture fail if a composite bypasses its capture.
        }
        WorkspaceOperations(host).use { operations ->
            val before = runtime.capture()
            val layers = operations.registry.invoke("workspace_inspect", buildJsonObject { put("scope", "layers") }, context).data
            assertEquals(1, captures)
            assertEquals(before.state, layers.getValue("state").jsonPrimitive.content)
            assertEquals(seed.model.analysis.layers.size, layers.getValue("items").jsonArray.size)
            val skeletonState = runtime.capture().state
            val skeleton = operations.registry.invoke("skeleton_get", buildJsonObject {}, context).data
            assertEquals(2, captures)
            assertEquals(skeletonState, skeleton.getValue("state").jsonPrimitive.content)
            val motionState = runtime.capture().state
            val motion = operations.registry.invoke("motion_list", buildJsonObject {}, context).data
            assertEquals(3, captures)
            assertEquals(motionState, motion.getValue("state").jsonPrimitive.content)
            val target = WorkspaceReadSession(runtime.read()).listRigObjects().first { it.kind == "mesh" }
            val objectState = runtime.capture().state
            val obj = operations.registry.invoke("workspace_inspect", buildJsonObject { put("target", "mesh:${target.id}") }, context).data
            assertEquals(4, captures)
            assertEquals(objectState, obj.getValue("state").jsonPrimitive.content)
            assertEquals(1, runtime.history().selections.size)
        }
    }

    @Test fun pagesBeyondIntegerRangeReturnEmptyWithoutAWrappedNextCursor() = runBlocking<Unit> {
        val runtime = runtime(); seed(runtime)
        val read = WorkspaceReadSession(runtime.read())
        val page = read.inspect(buildJsonObject { put("scope", "objects"); put("offset", Int.MAX_VALUE); put("limit", 64) })
        assertTrue(page.getValue("items").jsonArray.isEmpty())
        assertTrue(page.getValue("total").jsonPrimitive.int > 0)
        assertFalse("next" in page)
        val before = runtime.read()
        read.inspect(buildJsonObject { put("scope", "physics") })
        assertEquals(before, runtime.read())
    }
}
