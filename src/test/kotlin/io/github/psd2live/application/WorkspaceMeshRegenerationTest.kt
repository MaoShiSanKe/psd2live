package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.core.sim.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
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

class WorkspaceMeshRegenerationTest {
    @TempDir lateinit var temporary: Path
    private val pipeline = PSD2LivePipeline()
    private val builder = WorkspacePreviewBuilder()
    private fun layer(id: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), id, "",
        SourceLayerKind.Raster, true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
        LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 110 }), null, null, false)
    private suspend fun base(): WorkspaceRuntime<RigPreviewModel> {
        val source = WorkspaceSourceArt(96, 80, listOf(layer("art", 2, LayerBounds(8, 8, 36, 44)),
            layer("other", 1, LayerBounds(54, 16, 28, 36))), emptyList())
        val config = PipelineConfig(atlasSize = 256, exportMoc3 = false, generatePhysics = false,
            layerOverrides = source.layers.associate { it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS) })
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), config.layerOverrides, emptyMap(),
            RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        return WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }).also {
            it.install(it.state.value.state, "mesh", document, builder.build(document))
        }
    }
    private fun target(model: RigPreviewModel, name: String = "art") = model.rig.puppet.drawables.single { it.name == name }
    private fun mesh(id: String, margin: Int = 7) = WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
        put("layer_id", id); putJsonObject("changes") { put("outerMargin", margin); put("interiorDensity", 6) }
    })
    private suspend fun authored(imported: Boolean, blendSimulation: Boolean): WorkspaceRuntime<RigPreviewModel> {
        val runtime = base(); val commands = WorkspaceDocumentCommands(runtime); val root = runtime.capture()
        val id = target(root.model).id
        val count = target(root.model).mesh!!.vertexCount
        var capture = commands.execute(root.projectId, root.state, "Author mesh", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape") }),
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "MeshParent"); put("name", "Parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(id.raw) } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for (axis in listOf("Shape", "Blend")) add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${id.raw}"); putJsonObject("key") { put(axis, 1) }
                    putJsonObject("geometry") { put("positionDeltas", JsonArray(List(count * 2) { JsonPrimitive(kotlin.math.sin(it.toDouble()).toFloat() * 0.03f) })) }
                    putJsonObject("channels") { put("opacity", if (axis == "Shape") 0.6 else 0.8) }
                })
            } }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "Weld"); put("mesh_a", id.raw); put("mesh_b", target(root.model, "other").id.raw); put("distance", 128) })
        ), MutationAuthor.USER).capture
        val points = target(capture.model).mesh!!.positions
        capture = commands.execute(capture.projectId, capture.state, "Path", listOf(WorkspaceDocumentOperation("path_put", buildJsonObject {
            put("id", "MeshPath"); put("target", "mesh:${id.raw}"); putJsonArray("points") {
                add(buildJsonArray { add(points[0]); add(points[1]) }); add(buildJsonArray { add(points[points.size - 2]); add(points.last()) })
            }
        })), MutationAuthor.USER).capture
        if (imported) {
            val converted = Cmo3Conversion.freshCmo3(restMeshesToCanvasSpace(capture.model.rig.puppet),
                capture.model.atlas.pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) },
                capture.model.rig.pageByDrawableId, "Synthetic mesh", 0L, 0x42)
            val (source, config) = Cmo3ModelImport.prepare(Cmo3.write(converted.model), Cmo3ImportMode.NEW, null, capture.model.config)
            val document = WorkspaceDocument(source, config.layerVisibility, emptySet(), config.layerOverrides, config.parentOverrides,
                config.rigEdits, WorkspaceSettingsCodec.encode(config.copy(exportMoc3 = false)))
            runtime.install(capture.state, "imported-mesh", document, builder.build(document), discardUnsaved = true)
            capture = runtime.capture()
        }
        val current = target(capture.model); val size = current.mesh!!.vertexCount
        val offsets = FloatArray(size * 2) { kotlin.math.cos(it.toDouble()).toFloat() * 0.01f }
        val sim = RigSimEdit("Body", "Body", SimKind.CLOTH, listOf(current.id.raw), autoBake = false,
            blendShapes = blendSimulation, bake = SimBakeResult("synthetic", mapOf(current.id.raw to size), emptyList(), listOf(
                SimBakedMode(SimBakedAxis("Mode", floatArrayOf(-30f, 0f, 30f), mapOf(current.id.raw to listOf(
                    FloatArray(offsets.size) { -offsets[it] }, FloatArray(offsets.size), offsets))), 1f, 1f))))
        val group = VertexGroup("Pins", current.id, VertexGroupKind.PIN, FloatArray(size) { it.toFloat() / size })
        commands.executeCandidate(capture.projectId, capture.state, "Weights and motion", MutationAuthor.USER,
            mutation = { doc, _ -> doc.copy(rigEdits = doc.rigEdits.copy(simEdits = listOf(sim),
                authoringJournal = doc.rigEdits.authoringJournal + VertexGroupJournal.encode(group))) })
        return runtime
    }

    private fun assertPoses(expected: PuppetModel, actual: PuppetModel, tolerance: Float = 0.0001f) {
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to value, ParameterId("Blend") to kotlin.math.abs(value), ParameterId("Mode") to value * 20)
            val a = evaluator.evaluate(expected, pose); val b = evaluator.evaluate(actual, pose)
            assertEquals(a.worldPositions.keys, b.worldPositions.keys)
            a.worldPositions.forEach { (id, vertices) ->
                assertEquals(vertices.size, b.worldPositions.getValue(id).size)
                vertices.indices.forEach { assertEquals(vertices[it], b.worldPositions.getValue(id)[it], tolerance) }
                assertEquals(a.opacity.getValue(id), b.opacity.getValue(id), tolerance)
            }
        }
    }

    @Test fun regularAndImportedMeshesRetainAuthoredFormsPathsWeightsGlueAndBothSimulationModes() = runBlocking<Unit> {
        for (imported in listOf(false, true)) for (blend in listOf(false, true)) {
            val runtime = authored(imported, blend); val before = runtime.capture(); val old = target(before.model)
            assertFalse(MeshGenerationBaseline.present(before.document.rigEdits))
            val layer = before.model.rig.layerIdByDrawableId.getValue(old.id.raw)
            if (imported) assertTrue(before.document.source.layers.single { it.id.raw == layer }.bounds.width > 20,
                "Imported parent-local positions must be converted into canvas artwork bounds")
            val operation = mesh(layer)
            val result = WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Regenerate", listOf(operation), MutationAuthor.AGENT).capture
            assertNotNull(result.document.generationSource)
            assertTrue(MeshGenerationBaseline.present(result.document.rigEdits))
            assertEquals(before.document.rigEdits.authoringJournal, result.document.rigEdits.authoringJournal.take(before.document.rigEdits.authoringJournal.size))
            val next = target(result.model)
            assertFalse(old.mesh!!.positions.contentEquals(next.mesh!!.positions))
            assertEquals(old.parentDeformerId, next.parentDeformerId)
            assertEquals(old.geometryGrid!!.axes.map { it.parameterId }, next.geometryGrid!!.axes.map { it.parameterId })
            old.geometryGrid.axes.zip(next.geometryGrid.axes).forEach { (a, b) -> assertContentEquals(a.keys, b.keys) }
            assertEquals(old.blendShapes.size, next.blendShapes.size)
            assertEquals(result.document.rigEdits.simEdits.single().bake!!.vertexCounts[next.id.raw], next.mesh.vertexCount)
            assertEquals("synthetic", result.document.rigEdits.simEdits.single().bake!!.fingerprint)
            assertEquals(next.mesh.vertexCount, result.model.rig.puppet.vertexGroups.single().weights.size)
            assertEquals(before.model.rig.puppet.deformPaths.map { it.id }, result.model.rig.puppet.deformPaths.map { it.id })
            val record = result.document.rigEdits.authoringJournal.last { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP }
            val working = if (!imported) before.model else Cmo3ModelImport.paintingPreview(pipeline, before.document.source,
                before.document.config().copy(generationSource = before.document.source))
            val expected = RasterMeshJournal.replay(working.rig.puppet, record)
            assertPoses(expected, result.model.rig.puppet)
            assertPoses(result.model.rig.puppet, builder.build(result.document).rig.puppet)
            if (imported) assertEquals(target(before.model, "other").atlasTileId, target(result.model, "other").atlasTileId)
            val history = runtime.history()
            assertFalse(WorkspaceDocumentCommands(runtime).execute(result.projectId, result.state, "Same mesh", listOf(operation), MutationAuthor.USER).applied)
            assertEquals(history, runtime.history())
            runtime.checkout(result.projectId, result.state, before.historyHead)
            assertEquals(before.document, runtime.capture().document)
            runtime.checkout(result.projectId, runtime.capture().state, result.historyHead)
            assertPoses(result.model.rig.puppet, runtime.capture().model.rig.puppet)
            val name = "${if (imported) "imported" else "regular"}-${if (blend) "blend" else "ordinary"}"
            val archive = temporary.resolve("$name.psd2live")
            ProjectRepository().save(ProjectSaveCapture(result.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("$name-store"))), archive)
            val reopened = ProjectRepository().open(archive).use { opened ->
                assertEquals(result.revision, WorkspaceRevisions.of(opened.history.head().snapshot))
                builder.build(opened.history.head().snapshot)
            }
            assertPoses(result.model.rig.puppet, reopened.rig.puppet)
            val files = pipeline.run(result.document.source, name, temporary.resolve(name), result.document.config()).exportedFiles
            val exported = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            assertPoses(result.model.rig.puppet, exported, 0.001f)
            fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "mesh", mapOf("Shape" to 1f),
                model.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 80f)),
                WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
            val png = render(result.model); assertContentEquals(png, render(reopened))
            val image = javax.imageio.ImageIO.read(png.inputStream())
            assertTrue((0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) ushr 24 != 0 } } > 1000)
            val visuals = Path.of("build/mesh-regeneration-visual/$name"); Files.createDirectories(visuals)
            Files.write(visuals.resolve("before-reopen.png"), png); Files.write(visuals.resolve("after-reopen.png"), render(reopened))
        }
    }

    @Test fun regenerationAfterAnUnboundPartitionPreservesLaterVertexEditsAndRejectsLateBatchFailures() = runBlocking<Unit> {
        val runtime = base(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val partition = WorkspacePartitionCommands(runtime).execute(root.projectId, root.state, listOf(
            WorkspaceDocumentOperation("source_split_polygon", buildJsonObject { put("layer_id", "art"); putJsonArray("names") { add("Top"); add("Bottom") }
                putJsonArray("piece_ids") { add("top"); add("bottom") }; putJsonArray("polygon") {
                    listOf(0 to 0, 96 to 0, 96 to 30, 0 to 30).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) }
                }
            })), "Partition", MutationAuthor.USER).commit.capture
        assertFalse(MeshGenerationBaseline.present(partition.document.rigEdits))
        val drawable = target(partition.model, "Top")
        val authored = commands.execute(partition.projectId, partition.state, "Vertex form", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:${drawable.id.raw}"); putJsonObject("key") { put("Shape", 1) }
                putJsonObject("geometry") { put("positionDeltas", JsonArray(List(drawable.mesh!!.positions.size) { JsonPrimitive(0.02f) })) }
            }) } })), MutationAuthor.USER).capture
        val history = runtime.history()
        val failure = assertFailsWith<WorkspaceBatchEditException> { commands.execute(authored.projectId, authored.state, "Bad mesh batch",
            listOf(mesh("top"), mesh("missing")), MutationAuthor.AGENT) }
        assertEquals(1, failure.index); assertEquals(authored, runtime.capture()); assertEquals(history, runtime.history())
        val result = commands.execute(authored.projectId, authored.state, "Mesh", listOf(mesh("top")), MutationAuthor.AGENT).capture
        val next = target(result.model, "Top")
        assertTrue(next.geometryGrid!!.cells.any { cell -> cell.form.positionDeltas.any { it != 0f } })
        assertPoses(result.model.rig.puppet, builder.build(result.document).rig.puppet)
        assertFailsWith<CancellationException> { pipeline.rebuildPreview(result.model,
            result.model.config.copy(meshOuterMargin = 4f), ProgressListener { _, _ -> throw CancellationException("Stop") }) }
        assertEquals(result, runtime.capture())
    }

    @Test fun globalRegenerationWhileDeletedMatchesVisibleRegenerationAndSubsequentReset() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val visible = authored(imported, false); val initial = visible.capture()
            val initialLayer = initial.model.rig.layerIdByDrawableId.getValue(target(initial.model).id.raw)
            val hidden = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) }).also {
                it.install(it.state.value.state, initial.projectId, initial.document, builder.build(initial.document))
            }
            val hide = WorkspaceDocumentCommands(hidden).execute(initial.projectId, hidden.capture().state, "Hide mesh", listOf(
                WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", initialLayer) })), MutationAuthor.USER).capture
            assertTrue(hide.model.rig.puppet.drawables.none { it.name == "art" })
            val changes = WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") {
                put("meshOuterMargin", 6); put("meshInteriorDensity", 6)
            } })
            val shown = WorkspaceDocumentCommands(visible).execute(initial.projectId, initial.state, "All meshes", listOf(changes), MutationAuthor.AGENT).capture
            val deleted = WorkspaceDocumentCommands(hidden).execute(initial.projectId, hide.state, "All hidden meshes", listOf(changes), MutationAuthor.AGENT).capture
            assertTrue(deleted.model.rig.puppet.drawables.none { it.name == "art" })
            val restored = WorkspaceDocumentCommands(hidden).execute(initial.projectId, deleted.state, "Restore", listOf(
                WorkspaceDocumentOperation("layer_restore", buildJsonObject { put("layer_id", initialLayer) })), MutationAuthor.USER).capture
            assertPoses(shown.model.rig.puppet, restored.model.rig.puppet)
            assertContentEquals(target(shown.model).mesh!!.positions, target(restored.model).mesh!!.positions)
            val layerId = shown.model.rig.layerIdByDrawableId.getValue(target(shown.model).id.raw)
            val edited = WorkspaceDocumentCommands(visible).execute(initial.projectId, shown.state, "Override", listOf(mesh(layerId, 9)), MutationAuthor.AGENT).capture
            val reset = WorkspaceDocumentCommands(visible).execute(initial.projectId, edited.state, "Reset", listOf(
                WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject { put("layer_id", layerId); put("reset", true) })), MutationAuthor.USER).capture
            assertContentEquals(target(shown.model).mesh!!.positions, target(reset.model).mesh!!.positions)
            val resetRecord = reset.document.rigEdits.authoringJournal.last { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP }
            val resetInput = if (!imported) edited.model else Cmo3ModelImport.paintingPreview(pipeline, edited.document.source, edited.document.config())
            // Each replacement interpolates the immediately preceding topology. A round trip through
            // a coarser mesh cannot recover discarded high-frequency offsets from an earlier mesh.
            assertPoses(RasterMeshJournal.replay(resetInput.rig.puppet, resetRecord), reset.model.rig.puppet)
            assertPoses(reset.model.rig.puppet, builder.build(reset.document).rig.puppet)
            assertEquals(1, reset.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == MeshGenerationBaseline.OP })
            if (imported) assertEquals(1, reset.document.rigEdits.authoringJournal.count { "previous_parent_points" in it && it["id"]?.jsonPrimitive?.content == target(reset.model).id.raw })
        }
    }

    @Test fun globalRegenerationRetainsDepthFrontIdentityAndDirectionalGlue() = runBlocking<Unit> {
        val runtime = authored(false, true); val before = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val split = commands.execute(before.projectId, before.state, "Depth", listOf(WorkspaceDocumentOperation("source_split_depth", buildJsonObject {
            put("source_id", target(before.model).id.raw); putJsonArray("middle_ids") { add(target(before.model, "other").id.raw) }
            put("front_layer_id", "front"); put("front_mesh_id", "Front"); put("glue_id", "DepthGlue")
            putJsonArray("names") { add("Rear"); add("Front") }
        })), MutationAuthor.USER).capture
        val changed = commands.execute(before.projectId, split.state, "Depth mesh settings", listOf(WorkspaceDocumentOperation("settings_update", buildJsonObject {
            putJsonObject("changes") { put("meshOuterMargin", 5); put("meshInteriorDensity", 6) }
        })), MutationAuthor.AGENT).capture
        val puppet = changed.model.rig.puppet; val back = puppet.drawables.single { it.id == target(before.model).id }; val front = puppet.drawables.single { it.id.raw == "Front" }
        assertEquals(back.parentDeformerId, front.parentDeformerId)
        val glue = puppet.glues.single { it.id == "DepthGlue" }
        assertTrue(glue.pairs.isNotEmpty()); assertTrue(glue.pairs.all { it.weightA == 0f && it.weightB == 1f })
        val evaluator = CpuDeformationEvaluator()
        for (value in listOf(-1f, 0f, 1f)) {
            val evaluated = evaluator.evaluate(puppet, mapOf(ParameterId("Shape") to value))
            assertContentEquals(evaluated.worldPositions.getValue(back.id), evaluated.worldPositions.getValue(front.id))
        }
        assertPoses(puppet, builder.build(changed.document).rig.puppet)
    }
}
