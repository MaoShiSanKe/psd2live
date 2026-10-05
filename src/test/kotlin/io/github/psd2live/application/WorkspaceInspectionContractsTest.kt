package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.runtime.model.VertexGroupKind
import java.nio.file.Path
import kotlin.test.*

class WorkspaceInspectionContractsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val schema = requireNotNull(WorkspaceInspectionResultSchemas.forOperation("workspace_inspect"))
    private suspend fun seed(runtime: WorkspaceRuntime<RigPreviewModel>): WorkspaceCapture<RigPreviewModel> {
        val (png, _) = writeSourceImportFixture(temporary)
        return WorkspaceSourceImporter(runtime).createArtwork(sourceImportArguments(png), null, runtime.state.value.state,
            initialConfig = PipelineConfig(atlasSize = 256, meshOnly = true, generateDeformers = false, generatePhysics = false)).capture
    }
    private fun runtime() = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })

    @Test fun everyScopeReturnsAnUnambiguousTypedCaptureIncludingEmptyAndOutOfRangePages() = runBlocking<Unit> {
        val runtime = runtime(); seed(runtime)
        val read = WorkspaceReadSession(runtime.read())
        val before = runtime.read()
        for (scope in listOf("project", "physics", "swings", "simulations", "settings", "preview", "objects", "layers", "parameters", "paths", "vertex_groups")) {
            val result = read.inspect(buildJsonObject { put("scope", scope) })
            assertEquals(scope, result.getValue("scope").jsonPrimitive.content)
            validateOperationSchema(result, schema)
            if ("items" in result) {
                val empty = read.inspect(buildJsonObject { put("scope", scope); put("offset", Int.MAX_VALUE) })
                assertTrue(empty.getValue("items").jsonArray.isEmpty())
                validateOperationSchema(empty, schema)
                val wrongScope = JsonObject(result + ("scope" to JsonPrimitive("project")))
                assertFailsWith<WorkspaceValidationException> { validateOperationSchema(wrongScope, schema) }
            }
        }
        val mesh = read.listRigObjects().first { it.kind == "mesh" }
        val detail = read.inspect(buildJsonObject { put("target", "mesh:${mesh.id}") })
        assertEquals("object", detail.getValue("scope").jsonPrimitive.content)
        validateOperationSchema(detail, schema)
        assertEquals(before, runtime.read())
        val unloaded = WorkspaceReadSession(runtime().read()).inspect(buildJsonObject {})
        validateOperationSchema(unloaded, schema)
        assertFalse(unloaded.getValue("loaded").jsonPrimitive.boolean)
        assertEquals(JsonNull, unloaded.getValue("project_id"))
    }

    @Test fun sparseV1SettingsExposeDefaultsAndAuthoritativeMeshOverridesWithoutChangingTheSavedDocument() = runBlocking<Unit> {
        val runtime = runtime(); val seed = seed(runtime)
        val layer = seed.document.source.layers.single().id.raw
        val raw = buildJsonObject { put("atlasSize", 256); put("meshOnly", true); put("meshInnerMargin", 7); put("legacy_extension", "retained") }
        val mesh = MeshSettings(outerMargin = 3f, edgeMode = MeshEdgeMode.TRIPLE)
        val document = seed.document.copy(settings = raw, meshOverrides = mapOf(layer to mesh))
        runtime.install(seed.state, seed.projectId, document, builder.build(document), discardUnsaved = true)
        val read = WorkspaceReadSession(runtime.read())
        val result = read.inspect(buildJsonObject { put("scope", "settings") })
        validateOperationSchema(result, schema)
        val settings = result.getValue("settings").jsonObject
        assertEquals(8f, settings.getValue("meshEdgeWidth").jsonPrimitive.float)
        assertEquals("TRIPLE", settings.getValue("meshOverrides").jsonObject.getValue(layer).jsonObject.getValue("edgeMode").jsonPrimitive.content)
        assertTrue("rigTuning" in settings)
        assertFalse("legacy_extension" in settings)
        assertEquals(raw, runtime.capture().document.settings)
        assertEquals(mesh, runtime.capture().document.meshOverrides.getValue(layer))
    }

    @Test fun nonemptyPhysicsSwingAndSimulationScopesPreserveOptionalFieldsAndDiagnosis() = runBlocking<Unit> {
        val runtime = runtime(); val seed = seed(runtime)
        val target = seed.model.rig.puppet.drawables.first().id.raw
        val group = RigPhysicsEdit("custom", "Custom", listOf(PhysicsInput("missing", reflect = true)),
            listOf(PhysicsOutput("output", weight = 50f, type = PhysicsSourceType.X, reflect = true)), listOf(PhysicsSegment()),
            PhysicsNormalization(positionMin = -20f))
        val swing = RigSwingEdit("swing", "Swing", listOf("absent-warp"), listOf(SwingMotion(SwingKind.LATERAL, listOf("lateral"),
            SwingShape(flip = true), null)), tilt = 12f, offsetAlong = 0.1f, offsetAcross = -0.1f, baked = true)
        val sim = RigSimEdit("sim", "Simulation", SimKind.CLOTH, listOf(target), groups = mapOf(VertexGroupKind.PIN to "root"),
            autoBake = false, enabled = false, modes = 1, keys = 3, vertical = false, staticInputs = emptyList(), blendShapes = false,
            outputs = mapOf("baked" to SimOutput(id = "renamed", range = 10f)), outputNames = mapOf("renamed" to "Renamed"))
        val document = seed.document.copy(rigEdits = seed.document.rigEdits.copy(physicsEdits = listOf(group), swingEdits = listOf(swing), simEdits = listOf(sim)))
        runtime.install(seed.state, seed.projectId, document, builder.build(document), discardUnsaved = true)
        val read = WorkspaceReadSession(runtime.read())
        val physics = read.inspect(buildJsonObject { put("scope", "physics") })
        validateOperationSchema(physics, schema)
        assertTrue(physics.getValue("groups").jsonArray.single().jsonObject.getValue("issue").jsonPrimitive.content.contains("missing"))
        for (scope in listOf("swings", "simulations")) {
            val result = read.inspect(buildJsonObject { put("scope", scope) })
            validateOperationSchema(result, schema)
            assertEquals(1, result.getValue(scope).jsonArray.size)
        }
    }
}
