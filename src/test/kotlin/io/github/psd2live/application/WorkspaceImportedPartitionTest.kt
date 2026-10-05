package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.moc3.Moc3
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkspaceImportedPartitionTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val pipeline = PSD2LivePipeline()

    private suspend fun fixture(imported: Boolean): WorkspaceRuntime<RigPreviewModel> {
        val pixels = ByteArray(80 * 48 * 4)
        for (y in 4..43) for (x in (4..33) + (46..75)) for (channel in 0..3)
            pixels[(y * 80 + x) * 4 + channel] = if (channel == 3) -1 else 100
        val art = WorkspaceSourceLayer(LayerId("art"), "Art", "", SourceLayerKind.Raster, true, 2,
            LayerBounds(0, 0, 80, 48), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(80, 48, pixels), null, null, false)
        val other = art.copy(id = LayerId("other"), name = "Other", order = 1,
            bounds = LayerBounds(20, 4, 50, 40), raster = LayerRaster(50, 40,
                ByteArray(50 * 40 * 4) { if (it % 4 == 3) -1 else 80 }))
        val source = WorkspaceSourceArt(80, 48, listOf(art, other), emptyList())
        val config = PipelineConfig(atlasSize = 256, exportMoc3 = false, generatePhysics = false,
            layerOverrides = source.layers.associate { it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS) })
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), config.layerOverrides, emptyMap(),
            RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "partition", document, builder.build(document))
        val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val a = root.model.rig.puppet.drawables.single { it.name == "Art" }
        val b = root.model.rig.puppet.drawables.single { it.name == "Other" }
        var authored = commands.execute(root.projectId, root.state, "Authored seam", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "Parent"); put("name", "Parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(a.id.raw) } }),
            WorkspaceDocumentOperation("canvas_glue", buildJsonObject { put("id", "Seam"); put("mesh_a", a.id.raw); put("mesh_b", b.id.raw); put("distance", 100) }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for (value in listOf(-1, 1)) {
                    add(buildJsonObject { put("op", "set"); put("target", "mesh:${a.id.raw}"); putJsonObject("key") { put("Shape", value) }
                        putJsonObject("geometry") { put("positionDeltas", JsonArray(List(a.mesh!!.positions.size) { JsonPrimitive(kotlin.math.sin(it.toDouble()).toFloat() * value * 0.03f) })) }
                        putJsonObject("channels") { put("opacity", if (value < 0) 0.4 else 0.8) } })
                    add(buildJsonObject { put("op", "set"); put("target", "glue:Seam"); putJsonObject("key") { put("Shape", value) }
                        putJsonObject("channels") { put("glueIntensity", if (value < 0) 0.2 else 0.7) } })
                }
            } })
        ), MutationAuthor.USER).capture
        if (imported) {
            val converted = Cmo3Conversion.freshCmo3(restMeshesToCanvasSpace(authored.model.rig.puppet),
                authored.model.atlas.pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) },
                authored.model.rig.pageByDrawableId, "Synthetic partition", 0L, 0x42)
            val (incoming, settings) = Cmo3ModelImport.prepare(Cmo3.write(converted.model), Cmo3ImportMode.NEW, null, authored.model.config)
            val importedDocument = WorkspaceDocument(incoming, settings.layerVisibility, emptySet(), settings.layerOverrides,
                settings.parentOverrides, settings.rigEdits, WorkspaceSettingsCodec.encode(settings))
            runtime.install(authored.state, "imported", importedDocument, builder.build(importedDocument), discardUnsaved = true)
        }
        return runtime
    }

    private fun split(model: RigPreviewModel, kind: String) = WorkspaceDocumentOperation(kind, buildJsonObject {
        put("layer_id", model.rig.layerIdByDrawableId.getValue(model.rig.puppet.drawables.single { it.name == "Art" }.id.raw))
        putJsonArray("piece_ids") { add("one"); add("two") }; putJsonArray("names") { add("One"); add("Two") }
        if (kind == "source_split_polygon") putJsonArray("polygon") {
            listOf(-2 to -2, 21 to -2, 21 to 50, -2 to 50).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) }
        }
    })

    private fun assertSamePoses(expected: PuppetModel, actual: PuppetModel, tolerance: Float = 0.001f) {
        val evaluator = CpuDeformationEvaluator()
        for (shape in listOf(-1f, -0.35f, 0f, 0.45f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to shape)
            val a = evaluator.evaluate(expected, pose); val b = evaluator.evaluate(actual, pose)
            assertEquals(a.worldPositions.keys, b.worldPositions.keys)
            a.worldPositions.forEach { (id, xy) -> xy.indices.forEach { assertEquals(xy[it], b.worldPositions.getValue(id)[it], tolerance) } }
            a.opacity.forEach { (id, value) -> assertEquals(value, b.opacity.getValue(id), tolerance) }
        }
    }

    @Test fun polygonGlueAndImportedComponentsPreserveLiveWeldsAcrossReplayArchiveAndBothExports() = runBlocking<Unit> {
        for (imported in listOf(false, true)) for (kind in WorkspacePartitionEdits.supported) {
            val runtime = fixture(imported); val before = runtime.capture()
            val original = before.model.rig.puppet.drawables.single { it.name == "Art" }
            val other = before.model.rig.puppet.drawables.single { it.name == "Other" }
            val split = split(before.model, kind)
            val history = runtime.history()
            assertFailsWith<IllegalArgumentException> {
                WorkspaceDocumentCommands(runtime).executeJournal(before.projectId, before.state, "Misspelled channel", buildJsonArray {
                    add(buildJsonObject { put("op", "delete"); put("target", "glue:Seam"); put("parameter", "Shape"); put("channel", "glueIntensitty") })
                }, MutationAuthor.AGENT)
            }
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            val failed = assertFailsWith<WorkspaceBatchEditException> {
                WorkspaceDocumentCommands(runtime).execute(before.projectId, before.state, "Late failure", listOf(split,
                    WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", "missing") })), MutationAuthor.AGENT)
            }
            assertEquals(1, failed.index); assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            var checkpoints = 0
            assertFailsWith<CancellationException> {
                WorkspacePartitionEdits.apply(split, before.document, before.model, object : WorkspaceRasterWork {
                    override fun progress(fraction: Float, message: String) = Unit
                    override fun checkpoint() { if (++checkpoints > 5) throw CancellationException("Cancel the split") }
                })
            }
            assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
            val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state, listOf(split), "Partition", MutationAuthor.AGENT).commit.capture
            val command = SourcePartitionJournal.commands(result.document.rigEdits).single()
            val evaluator = CpuDeformationEvaluator()
            for (shape in listOf(-1f, -0.35f, 0f, 0.45f, 1f)) {
                val pose = mapOf(ParameterId("Shape") to shape)
                val old = evaluator.evaluate(before.model.rig.puppet, pose); val actual = evaluator.evaluate(result.model.rig.puppet, pose)
                assertContentEquals(old.worldPositions.getValue(other.id), actual.worldPositions.getValue(other.id))
                for (piece in SourcePartitionJournal.pieces(command)) {
                    val id = DrawableId(piece.getValue("id").jsonPrimitive.content)
                    assertEquals(old.opacity.getValue(original.id), actual.opacity.getValue(id), 0.0001f)
                    piece.getValue("sources").jsonArray.forEachIndexed { vertex, row ->
                        val source = row.jsonArray; val oldXY = old.worldPositions.getValue(original.id)
                        for (axis in 0..1) {
                            val expected = if (source.size == 1) oldXY[source[0].jsonPrimitive.int * 2 + axis] else
                                (0..2).sumOf { oldXY[source[it].jsonPrimitive.int * 2 + axis].toDouble() * source[it + 3].jsonPrimitive.float }.toFloat()
                            assertEquals(expected, actual.worldPositions.getValue(id)[vertex * 2 + axis], 0.0002f)
                        }
                    }
                }
            }
            assertSamePoses(result.model.rig.puppet, builder.build(result.document).rig.puppet)
            if (imported) assertEquals(other.atlasTileId, result.model.rig.puppet.drawables.single { it.id == other.id }.atlasTileId)
            val archive = temporary.resolve("$imported-$kind.psd2live")
            ProjectRepository().save(ProjectSaveCapture(result.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("store-$imported-$kind"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            assertSamePoses(result.model.rig.puppet, reopened.rig.puppet)
            fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "partition", mapOf("Shape" to 0.45f),
                model.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 80f, 48f)),
                WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
            val png = render(result.model); assertContentEquals(png, render(reopened))
            val image = javax.imageio.ImageIO.read(png.inputStream())
            assertTrue((0 until image.height).sumOf { y -> (0 until image.width).count { x -> image.getRGB(x, y) ushr 24 != 0 } } > 500)
            val visuals = Path.of("build/imported-partition-visual/$imported-$kind")
            Files.createDirectories(visuals); Files.write(visuals.resolve("before-reopen.png"), png)
            Files.write(visuals.resolve("after-reopen.png"), render(reopened))
            val exported = pipeline.run(result.document.source, "partition", temporary.resolve("export-$imported-$kind"),
                result.document.config().copy(exportMoc3 = true)).exportedFiles
            val cmo3 = Cmo3ModelImport.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            val moc3 = Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(exported.single { it.path.toString().endsWith(".moc3") }.path)), null)
            assertSamePoses(result.model.rig.puppet, cmo3)
            assertSamePoses(result.model.rig.puppet, moc3)
            runtime.checkout(result.projectId, result.state, before.historyHead)
            assertEquals(before.document, runtime.capture().document)
        }
    }

    @Test fun importedDepthCopiesTheAuthoredSourceAndPreservesUnrelatedAtlasIdentity() = runBlocking<Unit> {
        val runtime = fixture(true); val before = runtime.capture()
        val source = before.model.rig.puppet.drawables.single { it.name == "Art" }
        val middle = before.model.rig.puppet.drawables.single { it.name == "Other" }
        val operation = WorkspaceDocumentOperation("source_split_depth", buildJsonObject {
            put("source_id", source.id.raw); putJsonArray("middle_ids") { add(middle.id.raw) }
            put("front_layer_id", "front"); put("front_mesh_id", "Front"); put("glue_id", "DepthWeld")
            putJsonArray("names") { add("Rear"); add("Front") }
        })
        val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state, listOf(operation), "Depth", MutationAuthor.USER).commit.capture
        val evaluator = CpuDeformationEvaluator()
        for (shape in listOf(-1f, 0f, 0.45f, 1f)) {
            val pose = mapOf(ParameterId("Shape") to shape)
            val old = evaluator.evaluate(before.model.rig.puppet, pose); val actual = evaluator.evaluate(result.model.rig.puppet, pose)
            assertContentEquals(old.worldPositions.getValue(source.id), actual.worldPositions.getValue(source.id))
            assertContentEquals(actual.worldPositions.getValue(source.id), actual.worldPositions.getValue(DrawableId("Front")))
        }
        assertEquals(middle.atlasTileId, result.model.rig.puppet.drawables.single { it.id == middle.id }.atlasTileId)
        assertSamePoses(result.model.rig.puppet, builder.build(result.document).rig.puppet)
        val archive = temporary.resolve("depth.psd2live")
        ProjectRepository().save(ProjectSaveCapture(result.projectId, runtime.history(), JsonObject(emptyMap()), null,
            WorkspaceStore(temporary.resolve("depth-store"))), archive)
        val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
        assertSamePoses(result.model.rig.puppet, reopened.rig.puppet)
        val files = pipeline.run(result.document.source, "depth", temporary.resolve("depth-export"),
            result.document.config().copy(exportMoc3 = true)).exportedFiles
        assertSamePoses(result.model.rig.puppet,
            Cmo3ModelImport.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".cmo3") }.path)).puppet)
        assertSamePoses(result.model.rig.puppet,
            Moc3Import.fromMocDocument(Moc3.read(Files.readAllBytes(files.single { it.path.toString().endsWith(".moc3") }.path)), null))
        fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "depth", mapOf("Shape" to 0.45f),
            model.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 80f, 48f)),
            WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
        val png = render(result.model); assertContentEquals(png, render(reopened))
        val visuals = Path.of("build/imported-partition-visual/depth")
        Files.createDirectories(visuals); Files.write(visuals.resolve("before-reopen.png"), png)
        Files.write(visuals.resolve("after-reopen.png"), render(reopened))
        runtime.checkout(result.projectId, result.state, before.historyHead)
        assertEquals(before.document, runtime.capture().document)
    }
}
