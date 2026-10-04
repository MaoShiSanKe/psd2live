package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.cmo3.Cmo3
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceMeshUnitsMigrationTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private suspend fun fixture(imported: Boolean): WorkspaceRuntime<RigPreviewModel> {
        val width = 128; val height = 96
        val rgba = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x - width / 2f) / 58f; val dy = (y - height / 2f) / 42f
            if (dx * dx + dy * dy < 1) { val i = (y * width + x) * 4; rgba[i] = 80; rgba[i + 1] = 120; rgba[i + 2] = 100; rgba[i + 3] = -1 }
        }
        val layer = WorkspaceSourceLayer(LayerId("art"), "Artwork", "", SourceLayerKind.Raster, true, 0,
            LayerBounds(12, 16, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(width, height, rgba), null, null, false)
        val config = PipelineConfig(meshOnly = true, generateDeformers = false, generatePhysics = false, atlasSize = 256,
            exportMoc3 = false, meshUnits = MeshUnits.PIXELS, meshMaxEdgeDistance = 8f, meshInteriorDensity = 8f,
            layerOverrides = mapOf("art" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)))
        val document = WorkspaceDocument(WorkspaceSourceArt(4096, 160, listOf(layer), emptyList()), emptyMap(), emptySet(),
            config.layerOverrides, emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "units", document, builder.build(document))
        val before = runtime.capture(); val mesh = before.model.rig.puppet.drawables.single()
        WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Authored units", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "Parent"); put("name", "Parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(mesh.id.raw) } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for (value in listOf(-1f, 1f)) add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${mesh.id.raw}"); putJsonObject("key") { put("Shape", value) }
                    putJsonObject("geometry") { put("positionDeltas", JsonArray(List(mesh.mesh!!.vertexCount * 2) { JsonPrimitive(value * if(it % 2 == 0) 0.04f else -0.02f) })) }
                    putJsonObject("channels") { put("opacity", 0.7f + value * 0.2f) }
                })
            } })
        ), MutationAuthor.USER)
        if (imported) {
            val capture = runtime.capture()
            val converted = Cmo3Conversion.freshCmo3(restMeshesToCanvasSpace(capture.model.rig.puppet),
                capture.model.atlas.pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) },
                capture.model.rig.pageByDrawableId, "Unit fixture", 0L, 0x53)
            val (source, importedConfig) = Cmo3ModelImport.prepare(Cmo3.write(converted.model), Cmo3ImportMode.NEW, null, capture.model.config)
            val next = WorkspaceDocument(source, importedConfig.layerVisibility, emptySet(), importedConfig.layerOverrides,
                importedConfig.parentOverrides, importedConfig.rigEdits, WorkspaceSettingsCodec.encode(importedConfig.copy(meshUnits = MeshUnits.PIXELS)))
            runtime.install(capture.state, "imported-units", next, builder.build(next), discardUnsaved = true)
        }
        return runtime
    }
    private fun sameModel(a: PuppetModel, b: PuppetModel) {
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(-1f, 0f, 0.5f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to value)
            val first = evaluator.evaluate(a, pose); val second = evaluator.evaluate(b, pose)
            assertEquals(first.worldPositions.keys, second.worldPositions.keys)
            first.worldPositions.forEach { (id, points) ->
                val actual = second.worldPositions.getValue(id); assertEquals(points.size, actual.size)
                points.indices.forEach { assertEquals(points[it], actual[it], 0.001f) }
                assertEquals(first.opacity.getValue(id), second.opacity.getValue(id), 0.0001f)
            }
        }
    }
    private fun assertMotion(before: PuppetModel, after: PuppetModel) {
        val evaluator = CpuDeformationEvaluator(); val id = before.drawables.single().id
        val a0 = evaluator.evaluate(before, emptyMap()).worldPositions.getValue(id)
        val b0 = evaluator.evaluate(after, emptyMap()).worldPositions.getValue(id)
        for (value in listOf(-1f, 0.5f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to value)
            val a = evaluator.evaluate(before, pose); val b = evaluator.evaluate(after, pose)
            val expected = a.worldPositions.getValue(id)
            val actual = b.worldPositions.getValue(id)
            actual.indices.forEach { assertEquals(expected[it % 2] - a0[it % 2], actual[it] - b0[it], 0.001f) }
            assertEquals(a.opacity.getValue(id), b.opacity.getValue(id), 0.0001f)
        }
    }

    @Test fun unitsAndSimultaneousGenerationTransitionRetainOrdinaryImportedAuthoringArchiveAndExport() = runBlocking<Unit> {
        for (imported in listOf(false, true)) for (semantic in listOf(false, true)) {
            val runtime = fixture(imported); val before = runtime.capture(); val old = before.model.rig.puppet.drawables.single()
            assertEquals(2f, MeshResolution.unitScale(MeshUnits.DOCUMENT, before.document.source.widthPx, before.document.source.heightPx))
            val operation = WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") {
                put("meshUnits", "DOCUMENT"); if(semantic) put("mouthOutlineEnabled", false)
            } })
            val changed = WorkspaceGenerationCommands(runtime).execute(before.projectId, before.state, operation, "Unit scale", MutationAuthor.USER).commit.capture
            val next = changed.model.rig.puppet.drawables.single()
            assertFalse(old.mesh!!.positions.contentEquals(next.mesh!!.positions))
            assertEquals(old.id, next.id); assertEquals(old.parentDeformerId, next.parentDeformerId)
            assertTrue(changed.document.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP })
            assertEquals(MeshUnits.PIXELS, MeshGenerationBaseline.restore(changed.document.config()).meshUnits)
            assertEquals(MeshUnits.DOCUMENT, changed.document.config().meshUnits)
            assertMotion(before.model.rig.puppet, changed.model.rig.puppet)
            sameModel(changed.model.rig.puppet, builder.build(changed.document).rig.puppet)
            val history = runtime.history()
            val repeated = WorkspaceGenerationCommands(runtime).execute(changed.projectId, changed.state, operation, "Same units", MutationAuthor.USER)
            assertFalse(repeated.commit.applied); assertEquals(history, runtime.history())
            val file = temporary.resolve("units-$imported-$semantic.psd2live")
            ProjectRepository().save(ProjectSaveCapture(changed.projectId, history, JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("store-$imported-$semantic"))), file)
            val reopened = ProjectRepository().open(file).use { builder.build(it.history.head().snapshot) }
            sameModel(changed.model.rig.puppet, reopened.rig.puppet)
            val exported = PSD2LivePipeline().run(changed.document.source, "units", temporary.resolve("export-$imported-$semantic"), changed.document.config()).exportedFiles
            val cmo = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            sameModel(changed.model.rig.puppet, cmo)
            runtime.checkout(changed.projectId, changed.state, before.historyHead)
            sameModel(before.model.rig.puppet, runtime.capture().model.rig.puppet)
        }
    }

    @Test fun legacyBaselineKeepsPixelsAndNewBaselineKeepsDocumentUnits() {
        val original = PipelineConfig(meshUnits = MeshUnits.PIXELS)
        val marker = MeshGenerationBaseline.preserve(RigEditOverlay.Empty, original).authoringJournal.single()
        val legacy = JsonObject(marker + ("settings" to JsonObject(marker.getValue("settings").jsonObject - "meshUnits")))
        val old = RigEditOverlay.Empty.copy(authoringJournal = listOf(legacy))
        assertEquals(MeshUnits.PIXELS, MeshGenerationBaseline.restore(original.copy(meshUnits = MeshUnits.DOCUMENT, rigEdits = old)).meshUnits)
        assertEquals(listOf(legacy), old.authoringJournal)
        val modern = MeshGenerationBaseline.preserve(RigEditOverlay.Empty, original.copy(meshUnits = MeshUnits.DOCUMENT))
        assertEquals(MeshUnits.DOCUMENT, MeshGenerationBaseline.restore(original.copy(rigEdits = modern)).meshUnits)
    }
}
