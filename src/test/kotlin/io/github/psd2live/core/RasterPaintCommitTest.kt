package io.github.psd2live.core

import io.github.psd2live.application.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

/** Synthetic artwork exercises the preparation shared by desktop and application adapters. */
class RasterPaintCommitTest {
    @TempDir lateinit var temp: Path
    private fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(
        LayerId(id), name, "", SourceLayerKind.Raster, true, order, bounds, 1f, false,
        LayerBlend.Normal, ChannelMask.ALL,
        LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) {
            if (it % 4 == 3) -1 else 90
        }), null, null, false,
    )

    private fun base(pipeline: PSD2LivePipeline) = pipeline.buildPreview(
        WorkspaceSourceArt(96, 80, listOf(
            layer("art0", "Artwork 0", 0, LayerBounds(8, 8, 32, 24)),
            layer("art1", "Artwork 1", 1, LayerBounds(42, 36, 24, 24)),
        ), emptyList()),
        PipelineConfig(atlasSize = 256, meshSpacing = 8, meshOnly = true, exportMoc3 = false),
    )

    private fun drawable(preview: RigPreviewModel, id: String) = preview.rig.puppet.drawables.single {
        preview.rig.layerIdByDrawableId[it.id.raw] == id
    }

    @Test fun rebuiltPartitionInputSurvivesKeepMeshPaintingAndLaterMeshSettings() = runBlocking<Unit> {
        val preview = base(PSD2LivePipeline())
        val original = preview.analysis.source
        val document = WorkspaceDocument(original, emptyMap(), emptySet(), emptyMap(), emptyMap(),
            preview.config.rigEdits, WorkspaceSettingsCodec.encode(preview.config),
            generationSource = original, meshSource = original)
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "partition-paint", document, preview)
        val commands = WorkspaceDocumentCommands(runtime)
        val root = runtime.capture()
        val source = drawable(preview, "art0")
        val authored = commands.executeJournal(root.projectId, root.state, "Authored partition source", buildJsonArray {
            add(buildJsonObject { put("op", "structure"); putJsonArray("edits") {
                add(buildJsonObject { put("kind", "mesh"); put("id", source.id.raw); put("action", "rename"); put("name", "Source") })
            } })
        }, MutationAuthor.USER).capture
        val partition = WorkspacePartitionCommands(runtime).execute(root.projectId, authored.state, listOf(
            WorkspaceDocumentOperation("source_split_polygon", buildJsonObject {
                put("layer_id", "art0"); putJsonArray("names") { add("First"); add("Second") }
                putJsonArray("piece_ids") { add("first"); add("second") }
                putJsonArray("polygon") { listOf(0 to 0, 24 to 0, 24 to 80, 0 to 80).forEach { (x, y) ->
                    add(buildJsonArray { add(x); add(y) })
                } }
            })), "Partition", MutationAuthor.USER).commit.capture
        assertTrue(partition.document.meshSource!!.layers.none { it.id.raw == "first" })
        val generation = partition.document.generationSource
        val rebuilt = commands.execute(root.projectId, partition.state, "Rebuild new piece", listOf(
            WorkspaceDocumentOperation("source_paint_shape", buildJsonObject {
                put("layer_id", "first"); put("rebuild_mesh", true); put("shape", "rectangle"); put("filled", true)
                putJsonArray("from") { add(14); add(14) }; putJsonArray("to") { add(40); add(36) }
                putJsonArray("color") { add(20); add(110); add(210); add(255) }
            })), MutationAuthor.USER).capture
        val frozen = assertNotNull(rebuilt.document.meshSource)
        val frozenPiece = frozen.layers.single { it.id.raw == "first" }
        assertEquals(rebuilt.document.source.layers.single { it.id.raw == "first" }.bounds, frozenPiece.bounds)
        assertContentEquals(rebuilt.document.source.layers.single { it.id.raw == "first" }.raster.rgba, frozenPiece.raster.rgba)
        assertEquals(generation, rebuilt.document.generationSource)
        val settings = WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
            put("layer_id", "first"); putJsonObject("changes") { put("outerMargin", 4); put("interiorDensity", 6) }
        })
        val expected = commands.execute(root.projectId, rebuilt.state, "Settings from rebuilt input", listOf(settings), MutationAuthor.USER).capture
        runtime.checkout(root.projectId, expected.state, rebuilt.historyHead)
        val repaint = commands.execute(root.projectId, runtime.capture().state, "Keep mesh and expand pixels", listOf(
            WorkspaceDocumentOperation("source_paint_pencil", buildJsonObject {
                put("layer_id", "first"); put("radius", 3)
                putJsonArray("points") { add(buildJsonArray { add(70); add(68) }) }
                putJsonArray("color") { add(30); add(170); add(70); add(255) }
            })), MutationAuthor.USER).capture
        assertSame(frozen, repaint.document.meshSource)
        assertTrue(repaint.document.source.layers.single { it.id.raw == "first" }.bounds != frozenPiece.bounds)
        assertContentEquals(drawable(rebuilt.model, "first").mesh!!.positions, drawable(repaint.model, "first").mesh!!.positions)
        val actual = commands.execute(root.projectId, repaint.state, "Settings after keep mesh paint", listOf(settings), MutationAuthor.USER).capture
        assertContentEquals(drawable(expected.model, "first").mesh!!.positions, drawable(actual.model, "first").mesh!!.positions)
        assertContentEquals(drawable(expected.model, "first").mesh!!.indices, drawable(actual.model, "first").mesh!!.indices)
        assertContentEquals(drawable(actual.model, "first").mesh!!.positions, drawable(builder.build(actual.document), "first").mesh!!.positions)
        assertEquals(generation, actual.document.generationSource)
    }

    private fun image(width: Int = 96, height: Int = 80, bounds: LayerBounds? = null) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { result ->
            if (bounds != null) result.createGraphics().let { graphics ->
                try {
                    graphics.color = Color(30, 120, 220, 255)
                    graphics.fillRect(bounds.left, bounds.top, bounds.width, bounds.height)
                } finally { graphics.dispose() }
            }
        }

    private suspend fun authored(pipeline: PSD2LivePipeline, glue: Boolean): RigPreviewModel {
        val preview = base(pipeline)
        val document = WorkspaceDocument(preview.analysis.source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
            preview.config.rigEdits, WorkspaceSettingsCodec.encode(preview.config))
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ doc -> builder.build(doc) })
        runtime.install(runtime.state.value.state, "paint", document, preview)
        val before = runtime.capture()
        val first = drawable(preview, "art0")
        val points = first.mesh!!.positions
        fun edit(id: String, body: JsonObject) = WorkspaceDocumentOperation(id, body)
        val edits = buildList {
            add(edit("parameter_create", buildJsonObject { put("parameter_id", "PaintAxis"); put("name", "Paint axis") }))
            add(edit("parameter_create", buildJsonObject { put("parameter_id", "PaintBlend"); put("name", "Paint blend"); put("kind", "blend_shape") }))
            add(edit("canvas_warp", buildJsonObject {
                put("id", "paintWarp"); put("name", "Paint warp"); putJsonArray("meshes") { add(first.id.raw) }
                put("rows", 2); put("columns", 2)
            }))
            add(edit("keyform_apply", buildJsonObject { putJsonArray("changes") {
                add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${first.id.raw}"); putJsonObject("key") { put("PaintAxis", 1) }
                    putJsonObject("geometry") { put("positionDeltas", JsonArray(List(points.size) { JsonPrimitive(0.01f) })) }
                    putJsonObject("channels") { put("opacity", 0.4) }
                })
            } }))
            add(edit("keyform_apply", buildJsonObject { putJsonArray("changes") {
                add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${first.id.raw}"); putJsonObject("key") { put("PaintBlend", 1) }
                    putJsonObject("geometry") { put("positionDeltas", JsonArray(List(points.size) { JsonPrimitive(0.02f) })) }
                    putJsonObject("channels") { put("opacity", 0.8) }
                })
            } }))
            add(edit("path_put", buildJsonObject {
                put("id", "paintPath"); put("target", "mesh:${first.id.raw}"); putJsonArray("points") {
                    add(buildJsonArray { add(points[0]); add(points[1]) })
                    add(buildJsonArray { add(points[points.size - 2]); add(points.last()) })
                }
            }))
            if (glue) add(edit("canvas_glue", buildJsonObject {
                put("id", "paintGlue"); put("mesh_a", first.id.raw); put("mesh_b", drawable(preview, "art1").id.raw)
                put("distance", 128)
            }))
        }
        val commands = WorkspaceDocumentCommands(runtime)
        val rigged = commands.execute(before.projectId, before.state, "Prepare paint rig", edits, MutationAuthor.USER).capture
        val group = VertexGroup("paintPin", first.id, VertexGroupKind.PIN, FloatArray(first.mesh.vertexCount) { 0.65f })
        return commands.executeJournal(rigged.projectId, rigged.state, "Pin weights",
            buildJsonArray { add(VertexGroupJournal.encode(group)) }, MutationAuthor.USER).capture.model
    }

    private fun slice(preview: RigPreviewModel, layerId: String): AtlasSlice {
        val placement = preview.atlas.placementByLayerId.getValue(layerId)
        val page = preview.atlas.pages[placement.page].image
        val bounds = preview.analysis.source.layers.single { it.id.raw == layerId }.bounds
        return AtlasSlice(placement, page.width, page.height, Bounds(bounds.left.toFloat(), bounds.top.toFloat(),
            (bounds.left + bounds.width).toFloat(), (bounds.top + bounds.height).toFloat()))
    }

    @Test fun recropWithoutRebuildPreservesAuthoredRigAndAtlasCanvasAddresses() = runBlocking<Unit> {
        val pipeline = PSD2LivePipeline()
        val before = authored(pipeline, glue = true)
        val oldPixels = before.analysis.source.layers.associate { it.id.raw to it.raster.rgba.copyOf() }
        val newBounds = LayerBounds(14, 12, 12, 8)
        val after = RasterPaintCommit.prepare(pipeline, before, drawable(before, "art0").id.raw, image(bounds = newBounds), false)
        assertEquals(newBounds, after.analysis.source.layers.single { it.id.raw == "art0" }.bounds)
        assertEquals(before.rig.puppet.deformers, after.rig.puppet.deformers)
        assertEquals(before.rig.puppet.parameters, after.rig.puppet.parameters)
        assertEquals(before.rig.puppet.glues, after.rig.puppet.glues)
        assertEquals(before.rig.puppet.deformPaths, after.rig.puppet.deformPaths)
        assertEquals(before.rig.puppet.vertexGroups, after.rig.puppet.vertexGroups)
        assertSame(before.config.rigEdits, after.config.rigEdits)
        for (id in listOf("art0", "art1")) {
            val prior = drawable(before, id); val next = drawable(after, id)
            assertContentEquals(prior.mesh!!.positions, next.mesh!!.positions)
            assertContentEquals(prior.mesh.indices, next.mesh.indices)
            assertSame(prior.geometryGrid, next.geometryGrid)
            assertEquals(prior.parentDeformerId, next.parentDeformerId)
            assertEquals(before.rig.sourceBoundsByDrawableId[prior.id.raw], after.rig.sourceBoundsByDrawableId[next.id.raw])
            val from = slice(before, id); val to = slice(after, id)
            for (index in prior.mesh.uvs.indices step 2) {
                assertEquals(from.canvasX(prior.mesh.uvs[index]), to.canvasX(next.mesh.uvs[index]), 0.0001f)
                assertEquals(from.canvasY(prior.mesh.uvs[index + 1]), to.canvasY(next.mesh.uvs[index + 1]), 0.0001f)
            }
            assertContentEquals(oldPixels.getValue(id), before.analysis.source.layers.single { it.id.raw == id }.raster.rgba)
        }
        assertContentEquals(oldPixels.getValue("art1"), after.analysis.source.layers.single { it.id.raw == "art1" }.raster.rgba)
    }

    @Test fun rebuildRebindsPathsAndResamplesGroupsWhilePreservingParentFrames() = runBlocking<Unit> {
        val pipeline = PSD2LivePipeline()
        val before = authored(pipeline, glue = true)
        val prior = drawable(before, "art0")
        val newBounds = LayerBounds(4, 4, 72, 64)
        val after = RasterPaintCommit.prepare(pipeline, before, "art0", image(bounds = newBounds), true)
        val next = drawable(after, "art0")
        assertFalse(prior.mesh!!.positions.contentEquals(next.mesh!!.positions))
        assertEquals(before.rig.puppet.deformers, after.rig.puppet.deformers)
        assertEquals(prior.parentDeformerId, next.parentDeformerId)
        assertContentEquals(drawable(before, "art1").mesh!!.positions, drawable(after, "art1").mesh!!.positions)
        val path = after.rig.puppet.deformPaths.single()
        assertEquals("paintPath", path.id)
        val oldPathPoints = DeformPathTools.positions(before.rig.puppet.deformPaths.single(), prior.mesh.positions)
        val newPathPoints = DeformPathTools.positions(path, next.mesh.positions)
        oldPathPoints.zip(newPathPoints).forEach { (old, new) ->
            assertEquals(old.first, new.first, 0.0001f); assertEquals(old.second, new.second, 0.0001f)
        }
        val group = after.rig.puppet.vertexGroups.single()
        assertEquals(next.mesh.vertexCount, group.weights.size)
        group.weights.forEach { assertEquals(0.65f, it, 0.0001f) }
        assertEquals(prior.geometryGrid!!.axes, next.geometryGrid!!.axes)
        assertTrue(prior.geometryGrid.cells.any { it.form.positionDeltas.any { delta -> delta != 0f } })
        assertEquals(prior.geometryGrid.cells.size, next.geometryGrid.cells.size)
        prior.geometryGrid.cells.zip(next.geometryGrid.cells).forEach { (oldCell, cell) ->
            assertContentEquals(oldCell.coordinate, cell.coordinate)
            assertEquals(next.mesh.positions.size, cell.form.positionDeltas.size)
            cell.form.positionDeltas.forEach { assertEquals(oldCell.form.positionDeltas.first(), it, 0.0001f) }
        }
        assertEquals(prior.blendShapes.single().keys.toList(), next.blendShapes.single().keys.toList())
        next.blendShapes.single().forms.filterNotNull().forEach { form ->
            assertEquals(next.mesh.positions.size, form.positionDeltas.size)
            form.positionDeltas.forEach { assertEquals(0.02f, it, 0.0001f) }
        }
        val glue = after.rig.puppet.glues.single()
        assertTrue(glue.pairs.isNotEmpty())
        assertTrue(glue.pairs.all { it.indexA in 0 until next.mesh.vertexCount && it.indexB in 0 until drawable(after, "art1").mesh!!.vertexCount })
        assertEquals(before.rig.puppet.glues.single().intensity, glue.intensity)
        assertSame(before.rig.puppet.glues.single().channelGrids, glue.channelGrids)
        val journal = after.config.rigEdits.authoringJournal
        assertEquals(1, journal.count { it["op"]?.jsonPrimitive?.content == "path_put" })
        assertEquals(next.mesh.vertexCount, journal.single { it["op"]?.jsonPrimitive?.content == VertexGroupJournal.PUT }
            .getValue("weights").jsonArray.size)
    }

    @Test fun hierarchyOnlyRebuildKeepsSourcePixelsAndRejectsInvalidInputsWithoutMutation() {
        val pipeline = PSD2LivePipeline()
        val before = base(pipeline)
        val source = before.analysis.source.layers.single { it.id.raw == "art0" }
        val pixels = source.raster.rgba.copyOf()
        val after = RasterPaintCommit.prepare(pipeline, before, "art0", image(), true, preserveSourceRaster = true)
        val saved = after.analysis.source.layers.single { it.id.raw == "art0" }
        assertEquals(source.bounds, saved.bounds)
        assertContentEquals(pixels, saved.raster.rgba)
        assertFailsWith<IllegalArgumentException> { RasterPaintCommit.prepare(pipeline, before, "art0", image(1, 1), false) }
        assertFailsWith<IllegalArgumentException> { RasterPaintCommit.prepare(pipeline, before, "missing", image(), false) }
        assertContentEquals(pixels, source.raster.rgba)
        assertSame(before.rig.puppet, before.baseRig.puppet)
    }

    @Test fun depthFrontEraseKeepsBoundsTopologyAndDirectionalGlueEvenWhenRebuildRequested() {
        val pipeline = PSD2LivePipeline()
        val original = pipeline.buildPreview(WorkspaceSourceArt(128, 160, listOf(
            layer("face", "face", 1, LayerBounds(28, 10, 72, 70)),
            layer("neck", "neck", 2, LayerBounds(50, 70, 28, 30)),
            layer("collar", "neckwear", 3, LayerBounds(30, 86, 68, 30)),
        ), emptyList()), PipelineConfig(atlasSize = 256, meshSpacing = 12, exportMoc3 = false))
        val split = DepthSplit.build(pipeline, original, original.config, drawable(original, "collar").id.raw,
            drawable(original, "neck").id.raw)
        val before = split.preview
        val after = RasterPaintCommit.prepare(pipeline, before, split.frontLayerId, image(128, 160), true)
        val frontBefore = drawable(before, split.frontLayerId); val frontAfter = drawable(after, split.frontLayerId)
        assertContentEquals(frontBefore.mesh!!.positions, frontAfter.mesh!!.positions)
        assertContentEquals(frontBefore.mesh.indices, frontAfter.mesh.indices)
        assertSame(frontBefore.geometryGrid, frontAfter.geometryGrid)
        assertEquals(before.rig.puppet.glues, after.rig.puppet.glues)
        val oldLayer = before.analysis.source.layers.single { it.id.raw == split.frontLayerId }
        val newLayer = after.analysis.source.layers.single { it.id.raw == split.frontLayerId }
        assertEquals(oldLayer.bounds, newLayer.bounds)
        assertTrue(newLayer.raster.rgba.indices.filter { it % 4 == 3 }.all { newLayer.raster.rgba[it] == 0.toByte() })
        assertTrue(oldLayer.raster.rgba.indices.filter { it % 4 == 3 }.all { oldLayer.raster.rgba[it] == (-1).toByte() })
        assertContentEquals(before.analysis.source.layers.single { it.id.raw == "collar" }.raster.rgba,
            after.analysis.source.layers.single { it.id.raw == "collar" }.raster.rgba)
        val replayed = pipeline.buildPreview(after.analysis.source, after.config)
        assertContentEquals(frontAfter.mesh.positions, drawable(replayed, split.frontLayerId).mesh!!.positions)
        val expectedGlue = after.rig.puppet.glues.single()
        val actualGlue = replayed.rig.puppet.glues.single()
        assertEquals(expectedGlue.id, actualGlue.id)
        assertEquals(expectedGlue.meshA, actualGlue.meshA)
        assertEquals(expectedGlue.meshB, actualGlue.meshB)
        assertEquals(expectedGlue.intensity, actualGlue.intensity)
        assertEquals(expectedGlue.pairs.size, actualGlue.pairs.size)
        expectedGlue.pairs.zip(actualGlue.pairs).forEach { (old, new) ->
            assertEquals(old.indexA, new.indexA); assertEquals(old.indexB, new.indexB)
            assertEquals(old.weightA, new.weightA); assertEquals(old.weightB, new.weightB)
        }
    }

    private fun withTriangleCenter(mesh: DrawableMesh): DrawableMesh {
        val first = mesh.indices.take(3)
        val center = FloatArray(2) { axis -> first.sumOf { mesh.positions[it * 2 + axis].toDouble() }.toFloat() / 3 }
        val uv = FloatArray(2) { axis -> first.sumOf { mesh.uvs[it * 2 + axis].toDouble() }.toFloat() / 3 }
        val n = mesh.vertexCount
        return DrawableMesh(mesh.positions + center, mesh.uvs + uv,
            intArrayOf(first[0], first[1], n, first[1], first[2], n, first[2], first[0], n) + mesh.indices.drop(3))
    }

    @Test fun materializedMigrationSurvivesHistoryReplayAndCmo3ExportWithAllPoses() = runBlocking<Unit> {
        val pipeline = PSD2LivePipeline()
        val before = authored(pipeline, glue = true)
        val target = drawable(before, "art0")
        val command = RasterMeshJournal.encode(before.rig.puppet, target.id, withTriangleCenter(target.mesh!!))
        val (_, compiled) = RigAuthoringJournal.compile(before.rig.puppet, buildJsonArray { add(command) })
        assertEquals(listOf(command), compiled)
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val initial = WorkspaceDocument(before.analysis.source, before.config.layerVisibility, before.config.deletedLayerIds,
            before.config.layerOverrides, before.config.parentOverrides, before.config.rigEdits,
            WorkspaceSettingsCodec.encode(before.config), before.config.meshOverrides)
        runtime.install(runtime.state.value.state, "mesh-project", initial, before)
        val root = runtime.capture()
        val committed = WorkspaceDocumentCommands(runtime).executeJournal(root.projectId, root.state, "Rebuild mesh",
            buildJsonArray { add(command) }, MutationAuthor.USER)
        assertTrue(committed.applied)
        assertEquals(2, runtime.history().selections.size)
        val after = committed.capture.model
        assertEquals(target.mesh.vertexCount + 1, drawable(after, "art0").mesh!!.vertexCount)
        val document = committed.capture.document
        val store = WorkspaceStore(temp)
        store.persistHistory("mesh-project", runtime.history())
        val restored = assertNotNull(store.loadHistory("mesh-project")).head().snapshot
        assertEquals(document.rigEdits.authoringJournal, restored.rigEdits.authoringJournal)
        val reopened = WorkspacePreviewBuilder().build(restored)
        assertContentEquals(drawable(after, "art0").mesh!!.positions, drawable(reopened, "art0").mesh!!.positions)
        assertContentEquals(drawable(after, "art0").mesh!!.uvs, drawable(reopened, "art0").mesh!!.uvs)
        assertEquals(after.rig.puppet.vertexGroups, reopened.rig.puppet.vertexGroups)
        val undone = runtime.checkout(root.projectId, committed.capture.state, root.historyHead)
        assertEquals(target.mesh.vertexCount, drawable(undone.model, "art0").mesh!!.vertexCount)
        val redone = runtime.checkout(root.projectId, undone.state, committed.capture.historyHead)
        assertContentEquals(drawable(after, "art0").mesh!!.positions, drawable(redone.model, "art0").mesh!!.positions)
        val noOp = WorkspaceDocumentCommands(runtime).executeJournal(redone.projectId, redone.state, "Same mesh",
            buildJsonArray { add(RasterMeshJournal.encode(redone.model.rig.puppet, target.id, drawable(redone.model, "art0").mesh!!)) }, MutationAuthor.USER)
        assertFalse(noOp.applied)
        assertEquals(2, runtime.history().selections.size)
        val converted = Cmo3Conversion.freshCmo3(after.rig.puppet, after.atlas.pages.map { page ->
            Cmo3Conversion.AtlasPage(page.png, page.image.width, page.image.height)
        }, after.rig.pageByDrawableId, "mesh-migration", 0L, 0x42,
            tileRasters = { PuppetSourceAtlas.rastersByTile(after.analysis)[it] })
        val exported = Cmo3Import.fromModelSource(Cmo3.read(Cmo3.write(converted.model)).root as CModelSource)
        val evaluator = CpuDeformationEvaluator()
        for (axis in listOf(-1f, 0f, 1f)) for (blend in listOf(0f, 1f)) {
            val pose = mapOf(ParameterId("PaintAxis") to axis, ParameterId("PaintBlend") to blend)
            val expected = evaluator.evaluate(after.rig.puppet, pose)
            for (model in listOf(reopened.rig.puppet, exported)) {
                val actual = evaluator.evaluate(model, pose)
                after.rig.puppet.drawables.forEach { drawable ->
                    val a = expected.worldPositions.getValue(drawable.id); val b = actual.worldPositions.getValue(drawable.id)
                    assertEquals(a.size, b.size)
                    a.indices.forEach { assertEquals(a[it], b[it], 0.001f, "pose $axis/$blend vertex $it") }
                }
            }
        }
        val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 80f))
        fun render(preview: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(preview, "mesh-revision",
            mapOf("PaintAxis" to 1f, "PaintBlend" to 1f), setOf("art0", "art1"), emptySet(), frame,
            WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(512)).png
        val beforeReopen = render(after); val afterReopen = render(reopened)
        assertContentEquals(beforeReopen, afterReopen)
        val directory = Path.of("build/raster-migration-visual").toAbsolutePath()
        Files.createDirectories(directory)
        Files.write(directory.resolve("before-reopen.png"), beforeReopen)
        Files.write(directory.resolve("after-reopen.png"), afterReopen)
        val regenerated = WorkspaceDocumentCommands(runtime).execute(noOp.capture.projectId, noOp.capture.state, "Regenerate mesh",
            listOf(WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("meshSpacing", 16) } })),
            MutationAuthor.USER).capture
        assertTrue(MeshGenerationBaseline.present(regenerated.document.rigEdits))
        assertEquals(noOp.capture.document.rigEdits.authoringJournal,
            regenerated.document.rigEdits.authoringJournal.take(noOp.capture.document.rigEdits.authoringJournal.size))
        assertTrue(regenerated.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP } >
            noOp.capture.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP })
        val regeneratedReplay = builder.build(regenerated.document)
        assertContentEquals(drawable(regenerated.model, "art0").mesh!!.positions, drawable(regeneratedReplay, "art0").mesh!!.positions)
        assertEquals(regenerated.model.rig.puppet.vertexGroups, regeneratedReplay.rig.puppet.vertexGroups)
    }

    @Test fun repaintingUnderASavedGenerationSourceReusesGeometryButNotTextures() {
        val initial = base(PSD2LivePipeline())
        val config = initial.config.copy(meshOnly = false, generatePhysics = false, mouthOutlineEnabled = false)
        val art = initial.analysis.source as WorkspaceSourceArt
        fun painted(red: Int) = art.copy(layers = art.layers.map { layer ->
            if (layer.id.raw != "art0") layer else (layer as WorkspaceSourceLayer).copy(raster = LayerRaster(layer.raster.width, layer.raster.height,
                ByteArray(layer.raster.rgba.size) { if (it % 4 == 3) -1 else if (it % 4 == 0) red.toByte() else 40 }))
        })
        val saved = config.copy(generationSource = art)
        val shared = PSD2LivePipeline()
        val first = shared.buildPreview(painted(200), saved)
        val second = shared.buildPreview(painted(30), saved)
        val cold = PSD2LivePipeline().buildPreview(painted(30), saved)
        assertEquals(cold.rig.puppet.deformers.map { it.id }, second.rig.puppet.deformers.map { it.id })
        cold.rig.puppet.drawables.forEach { expected ->
            val actual = second.rig.puppet.drawables.single { it.id == expected.id }
            assertContentEquals(expected.mesh?.positions, actual.mesh?.positions)
            assertContentEquals(expected.mesh?.uvs, actual.mesh?.uvs)
        }
        val placement = second.atlas.placementByLayerId.getValue("art0")
        fun red(model: RigPreviewModel) = (model.atlas.pages[placement.page].image.getRGB(placement.x + 4, placement.y + 4) ushr 16) and 255
        assertEquals(200, red(first)); assertEquals(30, red(second))
        assertContentEquals(cold.atlas.pages[placement.page].png, second.atlas.pages[placement.page].png)
        // A changed generation input is never served from the previous geometry.
        val moved = saved.copy(generationSource = art.copy(layers = art.layers.map { layer ->
            if (layer.id.raw != "art1") layer else (layer as WorkspaceSourceLayer).copy(bounds = LayerBounds(50, 40, 24, 24))
        }))
        val regenerated = shared.buildPreview(painted(30), moved)
        val expected = PSD2LivePipeline().buildPreview(painted(30), moved)
        fun art1(model: RigPreviewModel) = model.rig.puppet.drawables.single { model.rig.layerIdByDrawableId[it.id.raw] == "art1" }.mesh!!.positions
        assertFalse(art1(second).contentEquals(art1(expected)))
        assertContentEquals(art1(expected), art1(regenerated))
    }

    @Test fun savedGenerationSourcePreservesGeneratedFramesAndPadsErasedTextureCoverage() {
        val pipeline = PSD2LivePipeline()
        val initial = base(pipeline)
        val config = initial.config.copy(meshOnly = false, generatePhysics = false, mouthOutlineEnabled = false)
        val before = pipeline.buildPreview(initial.analysis.source, config)
        val art = before.analysis.source as WorkspaceSourceArt
        val cropped = layer("art0", "Artwork 0", 0, LayerBounds(14, 12, 12, 8))
        val source = art.copy(layers = art.layers.map { if (it.id.raw == "art0") cropped else it })
        val saved = config.copy(generationSource = art)
        val after = PSD2LivePipeline().buildPreview(source, saved)
        assertTrue(before.rig.puppet.deformers.isNotEmpty())
        assertEquals(before.rig.faceCenterX, after.rig.faceCenterX)
        assertEquals(before.rig.faceRadiusX, after.rig.faceRadiusX)
        assertEquals(before.rig.sourceBoundsByDrawableId, after.rig.sourceBoundsByDrawableId)
        val textureLayer = after.analysis.layers.single { it.source.id.raw == "art0" }.source
        assertEquals(art.layers.first().bounds, textureLayer.bounds)
        val placement = after.atlas.placementByLayerId.getValue("art0")
        val page = after.atlas.pages[placement.page].image
        assertEquals(0, page.getRGB(placement.x, placement.y) ushr 24)
        assertEquals(255, page.getRGB(placement.x + 8, placement.y + 6) ushr 24)
        val evaluator = CpuDeformationEvaluator()
        for (angle in listOf(-30f, 0f, 30f)) {
            val pose = mapOf(StandardParameters.ANGLE_X to angle, StandardParameters.ANGLE_Y to angle)
            val previous = evaluator.evaluate(before.rig.puppet, pose).worldPositions
            evaluator.evaluate(after.rig.puppet, pose).worldPositions.forEach { (id, vertices) ->
                val old = previous.getValue(id)
                assertEquals(old.size, vertices.size)
                old.indices.forEach { assertEquals(old[it], vertices[it], 0.00001f) }
            }
        }
        val erased = source.copy(layers = source.layers.map { original ->
            (WorkspaceSourceLayer.copyOf(original, original.order) as WorkspaceSourceLayer).copy(
                raster = LayerRaster(original.raster.width, original.raster.height, ByteArray(original.raster.rgba.size)))
        })
        val transparent = pipeline.buildPreview(erased, saved)
        assertEquals(before.rig.puppet.drawables.size, transparent.rig.puppet.drawables.size)
        assertTrue(transparent.analysis.layers.all { it.source.raster.rgba.filterIndexed { index, _ -> index % 4 == 3 }.all { it == 0.toByte() } })
        assertFailsWith<IllegalArgumentException> {
            pipeline.buildPreview(source.copy(widthPx = 97), saved)
        }
    }

    @Test fun paintedSourceAndMaterializedMeshMigrationSurviveHistoryStorageAndPipelineExport() = runBlocking<Unit> {
        val pipeline = PSD2LivePipeline()
        val before = authored(pipeline, glue = true)
        val target = drawable(before, "art0")
        val prepared = RasterPaintCommit.prepare(pipeline, before, target.id.raw,
            image(bounds = LayerBounds(4, 4, 72, 64)), rebuildMesh = true)
        val migration = RasterMeshJournal.encode(before.rig.puppet, target.id, drawable(prepared, "art0").mesh!!,
            textureModel = prepared.rig.puppet)
        val builder = WorkspacePreviewBuilder()
        val initial = WorkspaceDocument(before.analysis.source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
            before.config.rigEdits, WorkspaceSettingsCodec.encode(before.config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "generation", initial, before)
        val root = runtime.capture()
        val commit = WorkspaceDocumentCommands(runtime).executeCandidate(root.projectId, root.state, "Paint and rebuild", MutationAuthor.USER,
            mutation = { document, model -> WorkspaceDocumentEdits.journal(document.copy(source = prepared.analysis.source,
                generationSource = document.source), model, buildJsonArray { add(migration) }) })
        assertTrue(commit.applied)
        assertEquals(2, runtime.history().selections.size)
        val after = commit.capture.model
        assertContentEquals(drawable(prepared, "art0").mesh!!.positions, drawable(after, "art0").mesh!!.positions)
        val store = WorkspaceStore(temp.resolve("store"))
        store.persistHistory("generation", runtime.history())
        val restored = store.loadHistory("generation")!!
        assertEquals(root.historyHead, restored.state().selections.first().node.id)
        assertEquals(root.revision, restored.state().selections.first().node.revisionId)
        assertNull(restored.state().selections.first().snapshot.generationSource)
        val document = restored.head().snapshot
        assertEquals(commit.capture.revision, WorkspaceRevisions.of(document))
        assertNotNull(document.generationSource)
        assertContentEquals(before.analysis.source.layers.first().raster.rgba, document.generationSource.layers.first().raster.rgba)
        val repository = ProjectRepository()
        val project = temp.resolve("painted.psd2live")
        repository.save(ProjectSaveCapture(root.projectId, runtime.history(), JsonObject(emptyMap()), null, store), project)
        val reopenedProject = repository.open(project).use { opened ->
            assertEquals(runtime.history().selections.map { it.node }, opened.history.state().selections.map { it.node })
            val saved = opened.history.head().snapshot
            assertEquals(commit.capture.revision, WorkspaceRevisions.of(saved))
            assertNotNull(saved.generationSource)
            assertContentEquals(document.generationSource.layers.first().raster.rgba, saved.generationSource.layers.first().raster.rgba)
            val original = org.umamo.format.psd.PsdReader.read(Files.readAllBytes(opened.source))
            assertContentEquals(before.analysis.source.layers.first().raster.rgba,
                original.layers.single { it.name == "Artwork 0" }.raster.rgba)
            builder.build(saved)
        }
        val blobs = Files.walk(temp.resolve("store")).use { files ->
            files.filter { it.parent.fileName.toString() == "blobs" && it.toString().endsWith(".png") }.count()
        }
        assertEquals(3L, blobs)
        val reopened = builder.build(document)
        val exported = PSD2LivePipeline().run(document.source, "paint", temp.resolve("export"), document.config())
        val cmo = exported.exportedFiles.single { it.path.toString().endsWith(".cmo3") }
        val imported = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(cmo.path)).root as CModelSource)
        val evaluator = CpuDeformationEvaluator()
        for (axis in listOf(-1f, 0f, 1f)) for (blend in listOf(0f, 1f)) {
            val pose = mapOf(ParameterId("PaintAxis") to axis, ParameterId("PaintBlend") to blend)
            val expected = evaluator.evaluate(after.rig.puppet, pose)
            for (puppet in listOf(reopened.rig.puppet, reopenedProject.rig.puppet, exported.previewModel.rig.puppet, imported)) {
                val actual = evaluator.evaluate(puppet, pose)
                expected.worldPositions.forEach { (id, prior) ->
                    val vertices = actual.worldPositions.getValue(id)
                    assertEquals(prior.size, vertices.size)
                    prior.indices.forEach { assertEquals(prior[it], vertices[it], 0.001f) }
                    assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                }
            }
        }
        assertEquals(after.rig.puppet.deformPaths, reopened.rig.puppet.deformPaths)
        assertEquals(after.rig.puppet.vertexGroups, reopened.rig.puppet.vertexGroups)
        val undone = runtime.checkout(root.projectId, commit.capture.state, root.historyHead)
        assertNull(undone.document.generationSource)
        val redone = runtime.checkout(root.projectId, undone.state, commit.capture.historyHead)
        assertEquals(commit.capture.revision, redone.revision)
        val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 80f))
        fun render(preview: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(preview, "generation-revision",
            mapOf("PaintAxis" to 1f, "PaintBlend" to 1f), setOf("art0", "art1"), emptySet(), frame,
            WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(512)).png
        assertContentEquals(render(after), render(reopened))
        assertContentEquals(render(after), render(reopenedProject))
        val directory = Path.of("build/generation-source-visual").toAbsolutePath()
        Files.createDirectories(directory)
        Files.write(directory.resolve("before-reopen.png"), render(after))
        Files.write(directory.resolve("after-reopen.png"), render(reopened))
        val withDifferentGeneration = document.copy(generationSource = prepared.analysis.source)
        assertNotEquals(WorkspaceRevisions.of(document), WorkspaceRevisions.of(withDifferentGeneration))
    }

    @Test fun publicRasterGesturesAndCapturedGuiPixelsShareOneCandidateAndPreserveAllBindings() = runBlocking<Unit> {
        val before = authored(PSD2LivePipeline(), glue = true)
        val initial = WorkspaceDocument(before.analysis.source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
            before.config.rigEdits, WorkspaceSettingsCodec.encode(before.config))
        val brush = buildJsonObject {
            put("layer_id", "art0"); put("mode", "brush"); put("radius", 4)
            put("points", buildJsonArray { add(buildJsonArray { add(20); add(20) }) })
            put("color", buildJsonArray { add(220); add(70); add(40); add(255) })
        }
        val publicCandidate = WorkspaceDocumentEdits.paint(initial, before, brush)
        val rasterCandidate = WorkspaceRasterEdits.prepare(initial, before,
            WorkspacePaintRaster.capture("art0", initial.paintSourceImage(brush), false))
        assertEquals(WorkspaceRevisions.of(publicCandidate), WorkspaceRevisions.of(rasterCandidate))
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "shared-paint", initial, before)
        val root = runtime.capture()
        val commands = WorkspaceDocumentCommands(runtime)
        val painted = commands.execute(root.projectId, root.state, "Brush", listOf(
            WorkspaceDocumentOperation("source_paint_brush", JsonObject(brush - "mode"))), MutationAuthor.AGENT)
        assertTrue(painted.applied)
        assertEquals(2, runtime.history().selections.size)
        assertEquals(before.config.rigEdits, painted.capture.document.rigEdits)
        assertEquals(before.rig.puppet.vertexGroups, painted.capture.model.rig.puppet.vertexGroups)
        assertContentEquals(drawable(before, "art0").mesh!!.positions, drawable(painted.capture.model, "art0").mesh!!.positions)
        val shape = buildJsonObject {
            put("layer_id", "art0"); put("rebuild_mesh", true); put("filled", true); put("shape", "rectangle")
            put("from", buildJsonArray { add(4); add(4) }); put("to", buildJsonArray { add(76); add(68) })
            put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
        }
        val rebuilt = commands.execute(root.projectId, painted.capture.state, "Rebuild painted mesh", listOf(
            WorkspaceDocumentOperation("source_paint_shape", shape)), MutationAuthor.AGENT)
        assertTrue(rebuilt.applied)
        assertEquals(3, runtime.history().selections.size)
        assertNotNull(rebuilt.capture.document.meshSource)
        assertTrue(rebuilt.capture.document.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP })
        assertNotEquals(drawable(before, "art0").mesh!!.vertexCount, drawable(rebuilt.capture.model, "art0").mesh!!.vertexCount)
        val store = WorkspaceStore(temp.resolve("shared-store"))
        val repository = ProjectRepository()
        repository.save(ProjectSaveCapture(root.projectId, runtime.history(), JsonObject(emptyMap()), null, store), temp.resolve("shared.psd2live"))
        val reopened = repository.open(temp.resolve("shared.psd2live")).use { opened ->
            assertEquals(runtime.history().selections.map { it.node }, opened.history.state().selections.map { it.node })
            assertEquals(rebuilt.capture.revision, WorkspaceRevisions.of(opened.history.head().snapshot))
            builder.build(opened.history.head().snapshot)
        }
        val result = PSD2LivePipeline().run(rebuilt.capture.document.source, "shared", temp.resolve("shared-export"), rebuilt.capture.document.config())
        val imported = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(result.exportedFiles.single {
            it.path.toString().endsWith(".cmo3") }.path)).root as CModelSource)
        val evaluator = CpuDeformationEvaluator()
        for (axis in listOf(-1f, 0f, 1f)) for (blend in listOf(0f, 1f)) {
            val pose = mapOf(ParameterId("PaintAxis") to axis, ParameterId("PaintBlend") to blend)
            val expected = evaluator.evaluate(rebuilt.capture.model.rig.puppet, pose)
            for (model in listOf(reopened.rig.puppet, imported)) {
                val actual = evaluator.evaluate(model, pose)
                expected.worldPositions.forEach { (id, vertices) ->
                    assertEquals(vertices.size, actual.worldPositions.getValue(id).size)
                    vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                    assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                }
            }
        }
        val noOp = commands.execute(root.projectId, rebuilt.capture.state, "Same shape", listOf(
            WorkspaceDocumentOperation("source_paint_shape", JsonObject(shape + ("rebuild_mesh" to JsonPrimitive(false))))), MutationAuthor.USER)
        assertFalse(noOp.applied)
        assertEquals(3, runtime.history().selections.size)
    }

    @Test fun paintedMeshCandidatesRollBackOnLaterMemberFailureCancellationAndStateConflict() = runBlocking<Unit> {
        val before = authored(PSD2LivePipeline(), glue = true)
        val document = WorkspaceDocument(before.analysis.source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
            before.config.rigEdits, WorkspaceSettingsCodec.encode(before.config))
        val operation = WorkspaceDocumentOperation("source_paint_shape", buildJsonObject {
            put("layer_id", "art0"); put("rebuild_mesh", true); put("filled", true); put("shape", "rectangle")
            put("from", buildJsonArray { add(4); add(4) }); put("to", buildJsonArray { add(76); add(68) })
            put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
        })
        val builder = WorkspacePreviewBuilder()
        for (scenario in listOf("member", "cancel", "conflict")) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val runtime = WorkspaceRuntime<RigPreviewModel>({ candidate ->
                if (scenario != "member") { started.complete(Unit); release.await() }
                builder.build(candidate)
            })
            runtime.install(runtime.state.value.state, "rollback-$scenario", document, before)
            val root = runtime.capture()
            val commands = WorkspaceDocumentCommands(runtime)
            if (scenario == "member") {
                val error = assertFailsWith<WorkspaceBatchEditException> {
                    commands.execute(root.projectId, root.state, "Failed painting", listOf(operation,
                        WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
                            put("id", "missingWarp"); put("rows", 2); put("columns", 2)
                            putJsonArray("meshes") { add("missing") }
                        })), MutationAuthor.AGENT)
                }
                assertEquals(1, error.index)
            } else {
                val pending = async { runCatching { commands.execute(root.projectId, root.state, "Pending painting", listOf(operation), MutationAuthor.AGENT) } }
                withTimeout(5000) { started.await() }
                if (scenario == "cancel") pending.cancelAndJoin()
                else {
                    runtime.updateAuxiliary(root.projectId, root.state, buildJsonObject { put("foreign", true) })
                    release.complete(Unit)
                    assertIs<WorkspaceConflict>(pending.await().exceptionOrNull())
                }
            }
            assertSame(document, runtime.capture().document)
            assertSame(before, runtime.capture().model)
            assertEquals(1, runtime.history().selections.size)
        }
    }

    @Test fun explicitMouthRebuildRefreshesSavedRibbonPixelsEvenWhenNoVertexChanges() = runBlocking<Unit> {
        val source = WorkspaceSourceArt(96, 80, listOf(
            layer("face", "Face", 0, LayerBounds(8, 8, 72, 60)),
            layer("mouth", "Mouth open", 1, LayerBounds(32, 46, 24, 12))), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshSpacing = 8, generatePhysics = false,
            rigEdits = RigEditOverlay(skeleton = SkeletonSpec.Disabled),
            layerOverrides = mapOf("face" to LayerClassificationOverride(tag = SemanticTag.FACE),
                "mouth" to LayerClassificationOverride(tag = SemanticTag.MOUTH_OPEN)))
        val pipeline = PSD2LivePipeline()
        val before = pipeline.buildPreview(source, config)
        val initial = WorkspaceDocument(source, emptyMap(), emptySet(), config.layerOverrides, emptyMap(),
            config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val painted = WorkspaceDocumentEdits.paint(initial, before, buildJsonObject {
            put("layer_id", "mouth"); put("mode", "bucket"); put("point", buildJsonArray { add(40); add(50) })
            put("color", buildJsonArray { add(220); add(50); add(70); add(255) })
        })
        val builder = WorkspacePreviewBuilder()
        val kept = builder.build(painted)
        val lip = before.analysis.layers.first { it.source is MouthLipLayer }.source.id.raw
        assertContentEquals(before.analysis.layers.single { it.source.id.raw == lip }.source.raster.rgba,
            kept.analysis.layers.single { it.source.id.raw == lip }.source.raster.rgba)
        val request = WorkspacePaintRaster.capture("mouth", painted.sourceLayerImage("mouth"), rebuildMesh = true)
        val refreshed = WorkspaceRasterEdits.prepare(painted, kept, request)
        assertNotEquals(WorkspaceRevisions.of(painted), WorkspaceRevisions.of(refreshed))
        val after = builder.build(refreshed)
        assertContentEquals(drawable(kept, lip).mesh!!.positions, drawable(after, lip).mesh!!.positions)
        assertFalse(kept.analysis.layers.single { it.source.id.raw == lip }.source.raster.rgba.contentEquals(
            after.analysis.layers.single { it.source.id.raw == lip }.source.raster.rgba))
        assertSame(refreshed, WorkspaceRasterEdits.prepare(refreshed, after, request))
    }

    @Test fun paintingMouthRebuildsExistingRibbonsWithoutLosingTheirAuthoredFormsOrSavedContours() = runBlocking<Unit> {
        val source = WorkspaceSourceArt(96, 80, listOf(
            layer("face", "Face", 0, LayerBounds(8, 8, 72, 60)),
            layer("mouth", "Mouth open", 1, LayerBounds(32, 46, 24, 12))), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshSpacing = 8, exportMoc3 = false,
            generatePhysics = false, rigEdits = RigEditOverlay(skeleton = SkeletonSpec.Disabled),
            layerOverrides = mapOf("face" to LayerClassificationOverride(tag = SemanticTag.FACE),
                "mouth" to LayerClassificationOverride(tag = SemanticTag.MOUTH_OPEN)))
        val pipeline = PSD2LivePipeline()
        val preview = pipeline.buildPreview(source, config)
        val lipIds = preview.analysis.layers.filter { it.source is MouthLipLayer }.map { it.source.id.raw }
        assertEquals(2, lipIds.size)
        val lip = drawable(preview, lipIds.first())
        val initial = WorkspaceDocument(source, emptyMap(), emptySet(), config.layerOverrides, emptyMap(),
            config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "mouth-paint", initial, preview)
        val root = runtime.capture()
        val commands = WorkspaceDocumentCommands(runtime)
        val authored = commands.execute(root.projectId, root.state, "Ribbon form", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "RibbonAxis"); put("name", "Ribbon axis") }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:${lip.id.raw}"); putJsonObject("key") { put("RibbonAxis", 1) }
                putJsonObject("geometry") { put("positionDeltas", JsonArray(List(lip.mesh!!.positions.size) { JsonPrimitive(0.01f) })) }
                putJsonObject("channels") { put("opacity", 0.35) }
            }) } })
        ), MutationAuthor.USER)
        val shape = buildJsonObject {
            put("layer_id", "mouth"); put("rebuild_mesh", true); put("shape", "ellipse"); put("filled", true)
            put("from", buildJsonArray { add(28); add(44) }); put("to", buildJsonArray { add(62); add(64) })
            put("color", buildJsonArray { add(170); add(40); add(70); add(255) })
        }
        val rebuilt = commands.execute(root.projectId, authored.capture.state, "Mouth contour", listOf(
            WorkspaceDocumentOperation("source_paint_shape", shape)), MutationAuthor.AGENT)
        val meshSource = assertNotNull(rebuilt.capture.document.meshSource)
        assertContentEquals(rebuilt.capture.document.source.layers.last().raster.rgba, meshSource.layers.last().raster.rgba)
        val lipAfter = drawable(rebuilt.capture.model, lipIds.first())
        assertNotNull(lipAfter.geometryGrid)
        val evaluator = CpuDeformationEvaluator()
        assertEquals(0.35f, evaluator.evaluate(rebuilt.capture.model.rig.puppet,
            mapOf(ParameterId("RibbonAxis") to 1f)).opacity.getValue(lip.id), 0.00001f)
        val later = commands.execute(root.projectId, rebuilt.capture.state, "Keep mouth mesh", listOf(
            WorkspaceDocumentOperation("source_paint_pencil", buildJsonObject {
                put("layer_id", "mouth"); put("radius", 2)
                put("points", buildJsonArray { add(buildJsonArray { add(70); add(66) }) })
                put("color", buildJsonArray { add(20); add(80); add(150); add(255) })
            })), MutationAuthor.USER)
        assertSame(meshSource, later.capture.document.meshSource)
        lipIds.forEach { id ->
            assertContentEquals(drawable(rebuilt.capture.model, id).mesh!!.positions, drawable(later.capture.model, id).mesh!!.positions)
            val beforeRaster = rebuilt.capture.model.analysis.layers.single { it.source.id.raw == id }.source.raster.rgba
            assertContentEquals(beforeRaster, later.capture.model.analysis.layers.single { it.source.id.raw == id }.source.raster.rgba)
        }
        val store = WorkspaceStore(temp.resolve("mouth-store"))
        val repository = ProjectRepository()
        repository.save(ProjectSaveCapture(root.projectId, runtime.history(), JsonObject(emptyMap()), null, store), temp.resolve("mouth.psd2live"))
        val reopened = repository.open(temp.resolve("mouth.psd2live")).use { opened ->
            assertEquals(later.capture.revision, WorkspaceRevisions.of(opened.history.head().snapshot))
            builder.build(opened.history.head().snapshot)
        }
        val exported = pipeline.run(later.capture.document.source, "mouth", temp.resolve("mouth-export"), later.capture.document.config())
        val imported = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(exported.exportedFiles.single {
            it.path.toString().endsWith(".cmo3") }.path)).root as CModelSource)
        for (axis in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("RibbonAxis") to axis, StandardParameters.MOUTH_OPEN to 1f)
            val expected = evaluator.evaluate(later.capture.model.rig.puppet, pose)
            for (model in listOf(reopened.rig.puppet, imported)) {
                val actual = evaluator.evaluate(model, pose)
                expected.worldPositions.forEach { (id, vertices) ->
                    val saved = actual.worldPositions.getValue(id)
                    assertEquals(vertices.size, saved.size)
                    vertices.indices.forEach { assertEquals(vertices[it], saved[it], 0.001f) }
                    assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                }
            }
        }
        val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 80f))
        fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "mouth-revision",
            mapOf("RibbonAxis" to 1f, StandardParameters.MOUTH_OPEN.raw to 1f),
            setOf("face", "mouth") + lipIds, emptySet(), frame, WorkspaceViewBackground.TRANSPARENT,
            WorkspaceViewOutputSpec(512)).png
        assertContentEquals(render(later.capture.model), render(reopened))
        val directory = Path.of("build/shared-raster-visual").toAbsolutePath()
        Files.createDirectories(directory)
        Files.write(directory.resolve("mouth-before-reopen.png"), render(later.capture.model))
        Files.write(directory.resolve("mouth-after-reopen.png"), render(reopened))
    }

    @Test fun firstPaintCreatesDurableMeshWithStableIdentityAndPreservesExistingBindings() = runBlocking<Unit> {
        val pipeline = PSD2LivePipeline()
        val original = base(pipeline)
        val blank = original.analysis.source.layers[1].let { source ->
            (source as WorkspaceSourceLayer).copy(bounds = LayerBounds(42, 36, 1, 1), raster = LayerRaster(1, 1, ByteArray(4)))
        }
        val source = WorkspaceSourceArt(96, 80, listOf(original.analysis.source.layers[0], blank), emptyList())
        val preview = pipeline.buildPreview(source, original.config)
        assertEquals(1, preview.rig.puppet.drawables.size)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), preview.config.rigEdits,
            WorkspaceSettingsCodec.encode(preview.config))
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "creation", document, preview)
        val commands = WorkspaceDocumentCommands(runtime)
        val root = runtime.capture()
        val existing = drawable(preview, "art0")
        val authored = commands.execute(root.projectId, root.state, "Existing forms", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "CreationAxis"); put("name", "Creation axis") }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:${existing.id.raw}"); putJsonObject("key") { put("CreationAxis", 1) }
                putJsonObject("geometry") { put("positionDeltas", JsonArray(List(existing.mesh!!.positions.size) { JsonPrimitive(0.015f) })) }
                putJsonObject("channels") { put("opacity", 0.6) }
            }) } }),
        ), MutationAuthor.USER).capture
        fun shape(rebuild: Boolean = false) = WorkspaceDocumentOperation("source_paint_shape", buildJsonObject {
            put("layer_id", "art1"); put("filled", true); put("shape", "rectangle"); put("rebuild_mesh", rebuild)
            put("from", buildJsonArray { add(42); add(36) }); put("to", buildJsonArray { add(66); add(60) })
            put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
        })
        val painted = commands.execute(root.projectId, authored.state, "Create painted mesh", listOf(shape()), MutationAuthor.AGENT)
        assertTrue(painted.applied)
        assertEquals(3, runtime.history().selections.size)
        val created = drawable(painted.capture.model, "art1")
        assertNotNull(created.mesh)
        assertEquals(1, painted.capture.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
        val evaluator = CpuDeformationEvaluator()
        for (axis in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("CreationAxis") to axis)
            val before = evaluator.evaluate(authored.model.rig.puppet, pose)
            val after = evaluator.evaluate(painted.capture.model.rig.puppet, pose)
            assertContentEquals(before.worldPositions.getValue(existing.id), after.worldPositions.getValue(existing.id))
            assertEquals(before.opacity.getValue(existing.id), after.opacity.getValue(existing.id))
        }
        val noOp = commands.execute(root.projectId, painted.capture.state, "Same pixels", listOf(shape()), MutationAuthor.USER)
        assertFalse(noOp.applied)
        val keyed = commands.execute(root.projectId, noOp.capture.state, "New mesh form", listOf(
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:${created.id.raw}"); putJsonObject("key") { put("CreationAxis", 1) }
                putJsonObject("geometry") { put("positionDeltas", JsonArray(List(created.mesh.positions.size) { JsonPrimitive(0.02f) })) }
                putJsonObject("channels") { put("opacity", 0.4) }
            }) } }),
            WorkspaceDocumentOperation("path_put", buildJsonObject {
                put("id", "creationPath"); put("target", "mesh:${created.id.raw}"); putJsonArray("points") {
                    add(buildJsonArray { add(created.mesh.positions[0]); add(created.mesh.positions[1]) })
                    add(buildJsonArray { add(created.mesh.positions[2]); add(created.mesh.positions[3]) })
                }
            }),
        ), MutationAuthor.USER).capture
        val pin = VertexGroup("creationPin", created.id, VertexGroupKind.PIN, FloatArray(created.mesh.vertexCount) { 0.65f })
        val bound = commands.executeJournal(root.projectId, keyed.state, "Created pin",
            buildJsonArray { add(VertexGroupJournal.encode(pin)) }, MutationAuthor.USER).capture
        val expanded = commands.execute(root.projectId, bound.state, "Expand created mesh", listOf(
            WorkspaceDocumentOperation("source_paint_shape", JsonObject(shape(true).request +
                ("to" to buildJsonArray { add(84); add(70) })))), MutationAuthor.USER).capture
        assertTrue(expanded.document.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP })
        val oldCreation = expanded.document.rigEdits.authoringJournal.single { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
        val settings = commands.execute(root.projectId, expanded.state, "Created mesh settings", listOf(
            WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
                put("layer_id", "art1"); putJsonObject("changes") { put("interiorDensity", 6); put("outerMargin", 4) }
            })), MutationAuthor.USER).capture
        val newCreation = settings.document.rigEdits.authoringJournal.single { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP }
        assertEquals(oldCreation, newCreation)
        assertEquals(expanded.document.rigEdits.authoringJournal,
            settings.document.rigEdits.authoringJournal.take(expanded.document.rigEdits.authoringJournal.size))
        assertTrue(settings.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP } >
            expanded.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP })
        val regenerated = drawable(settings.model, "art1")
        assertEquals(created.id, regenerated.id)
        assertTrue(settings.model.rig.puppet.deformPaths.any { it.id == "creationPath" && it.drawableId == created.id })
        val regeneratedPin = settings.model.rig.puppet.vertexGroups.single { it.name == "creationPin" }
        assertEquals(regenerated.mesh!!.vertexCount, regeneratedPin.weights.size)
        regeneratedPin.weights.forEach { assertEquals(0.65f, it, 0.00001f) }
        assertEquals(0.4f, evaluator.evaluate(settings.model.rig.puppet, mapOf(ParameterId("CreationAxis") to 1f)).opacity.getValue(created.id), 0.00001f)
        val cleared = commands.execute(root.projectId, settings.state, "Clear born mesh", listOf(
            WorkspaceDocumentOperation("source_paint_clear", buildJsonObject { put("layer_id", "art1") })), MutationAuthor.USER).capture
        assertEquals(created.id, drawable(cleared.model, "art1").id)
        assertContentEquals(regenerated.mesh.positions, drawable(cleared.model, "art1").mesh!!.positions)
        assertEquals(listOf(0, 0, 0, 0), cleared.document.sampleSourceColor("art1", 50, 50))
        val repainted = commands.execute(root.projectId, cleared.state, "Repaint born mesh", listOf(shape()), MutationAuthor.USER).capture
        assertEquals(created.id, drawable(repainted.model, "art1").id)
        val store = WorkspaceStore(temp.resolve("creation-store"))
        val repository = ProjectRepository()
        val archive = temp.resolve("creation.psd2live")
        repository.save(ProjectSaveCapture(root.projectId, runtime.history(), JsonObject(emptyMap()), null, store), archive)
        val reopened = repository.open(archive).use { opened ->
            assertEquals(runtime.history().selections.map { it.node }, opened.history.state().selections.map { it.node })
            assertEquals(repainted.revision, WorkspaceRevisions.of(opened.history.head().snapshot))
            assertEquals(1, builder.build(opened.history.selections().single { it.node.parentId == null }.snapshot).rig.puppet.drawables.size)
            builder.build(opened.history.head().snapshot)
        }
        val result = pipeline.run(repainted.document.source, "creation", temp.resolve("creation-export"), repainted.document.config())
        val imported = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(result.exportedFiles.single {
            it.path.toString().endsWith(".cmo3") }.path)).root as CModelSource)
        for (axis in listOf(-1f, 0f, 1f)) {
            val pose = mapOf(ParameterId("CreationAxis") to axis)
            val expected = evaluator.evaluate(repainted.model.rig.puppet, pose)
            for (model in listOf(reopened.rig.puppet, imported)) {
                val actual = evaluator.evaluate(model, pose)
                expected.worldPositions.forEach { (id, vertices) ->
                    assertEquals(vertices.size, actual.worldPositions.getValue(id).size)
                    vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                    assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                }
            }
        }
        val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 80f))
        fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "creation", mapOf("CreationAxis" to 1f),
            setOf("art0", "art1"), emptySet(), frame, WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(512)).png
        assertContentEquals(render(repainted.model), render(reopened))
        val directory = Path.of("build/raster-creation-visual").toAbsolutePath()
        Files.createDirectories(directory)
        Files.write(directory.resolve("before-reopen.png"), render(repainted.model))
        Files.write(directory.resolve("after-reopen.png"), render(reopened))
    }

    @Test fun meshCreationCandidatesRollBackOnLaterMemberFailureCancellationAndStateConflict() = runBlocking<Unit> {
        val pipeline = PSD2LivePipeline()
        val original = base(pipeline)
        val blank = (original.analysis.source.layers[1] as WorkspaceSourceLayer).copy(
            bounds = LayerBounds(42, 36, 1, 1), raster = LayerRaster(1, 1, ByteArray(4)))
        val source = WorkspaceSourceArt(96, 80, listOf(original.analysis.source.layers[0], blank), emptyList())
        val before = pipeline.buildPreview(source, original.config)
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(),
            before.config.rigEdits, WorkspaceSettingsCodec.encode(before.config))
        val operation = WorkspaceDocumentOperation("source_paint_shape", buildJsonObject {
            put("layer_id", "art1"); put("filled", true); put("shape", "rectangle")
            put("from", buildJsonArray { add(42); add(36) }); put("to", buildJsonArray { add(66); add(60) })
            put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
        })
        val builder = WorkspacePreviewBuilder()
        for (scenario in listOf("member", "cancel", "conflict")) {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = WorkspaceRuntime<RigPreviewModel>({ candidate ->
                assertTrue(candidate.rigEdits.authoringJournal.any { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
                if (scenario != "member") { started.complete(Unit); release.await() }
                builder.build(candidate)
            })
            runtime.install(runtime.state.value.state, "creation-$scenario", document, before)
            val root = runtime.capture()
            val commands = WorkspaceDocumentCommands(runtime)
            if (scenario == "member") {
                val failure = assertFailsWith<WorkspaceBatchEditException> {
                    commands.execute(root.projectId, root.state, "Failed creation", listOf(operation,
                        WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
                            put("id", "missingWarp"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add("missing") }
                        })), MutationAuthor.AGENT)
                }
                assertEquals(1, failure.index)
            } else {
                val pending = async { runCatching { commands.execute(root.projectId, root.state, "Pending creation", listOf(operation), MutationAuthor.AGENT) } }
                withTimeout(5000) { started.await() }
                if (scenario == "cancel") pending.cancelAndJoin() else {
                    runtime.updateAuxiliary(root.projectId, root.state, buildJsonObject { put("foreign", true) })
                    release.complete(Unit)
                    assertIs<WorkspaceConflict>(pending.await().exceptionOrNull())
                }
            }
            assertSame(document, runtime.capture().document)
            assertSame(before, runtime.capture().model)
            assertEquals(1, runtime.history().selections.size)
        }
    }

    @Test fun firstMouthPaintCreatesRibbonsWithGeneratedFormsAndClearRetainsTheirTextureCoverage() = runBlocking<Unit> {
        val mouth = layer("mouth", "Mouth open", 1, LayerBounds(32, 46, 1, 1)).copy(raster = LayerRaster(1, 1, ByteArray(4)))
        val source = WorkspaceSourceArt(96, 80, listOf(layer("face", "Face", 0, LayerBounds(8, 8, 72, 60)), mouth), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshSpacing = 8, exportMoc3 = false,
            generatePhysics = false, rigEdits = RigEditOverlay(skeleton = SkeletonSpec.Disabled),
            layerOverrides = mapOf("face" to LayerClassificationOverride(tag = SemanticTag.FACE),
                "mouth" to LayerClassificationOverride(tag = SemanticTag.MOUTH_OPEN)))
        val builder = WorkspacePreviewBuilder()
        val initial = WorkspaceDocument(source, emptyMap(), emptySet(), config.layerOverrides, emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val preview = builder.build(initial)
        assertEquals(1, preview.rig.puppet.drawables.size)
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "mouth-creation", initial, preview)
        val commands = WorkspaceDocumentCommands(runtime)
        val root = runtime.capture()
        val shape = WorkspaceDocumentOperation("source_paint_shape", buildJsonObject {
            put("layer_id", "mouth"); put("shape", "ellipse"); put("filled", true)
            put("from", buildJsonArray { add(28); add(44) }); put("to", buildJsonArray { add(62); add(64) })
            put("color", buildJsonArray { add(170); add(40); add(70); add(255) })
        })
        val painted = commands.execute(root.projectId, root.state, "Create mouth", listOf(shape), MutationAuthor.AGENT).capture
        val lipIds = painted.model.analysis.layers.filter { it.source is MouthLipLayer }.map { it.source.id.raw }
        assertEquals(2, lipIds.size)
        assertEquals(3, painted.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshCreation.OP })
        assertEquals(4, painted.model.rig.puppet.drawables.size)
        lipIds.forEach { assertNotNull(drawable(painted.model, it).geometryGrid) }
        val evaluator = CpuDeformationEvaluator()
        val face = drawable(preview, "face")
        for (open in listOf(0f, 0.5f, 1f)) {
            val pose = mapOf(StandardParameters.MOUTH_OPEN to open)
            assertContentEquals(evaluator.evaluate(preview.rig.puppet, pose).worldPositions.getValue(face.id),
                evaluator.evaluate(painted.model.rig.puppet, pose).worldPositions.getValue(face.id))
        }
        val clear = commands.execute(root.projectId, painted.state, "Clear mouth", listOf(
            WorkspaceDocumentOperation("source_paint_clear", buildJsonObject { put("layer_id", "mouth"); put("rebuild_mesh", true) })), MutationAuthor.USER).capture
        for (layerId in listOf("mouth") + lipIds) {
            assertEquals(drawable(painted.model, layerId).id, drawable(clear.model, layerId).id)
            assertContentEquals(drawable(painted.model, layerId).mesh!!.positions, drawable(clear.model, layerId).mesh!!.positions)
            val raster = clear.model.analysis.layers.single { it.source.id.raw == layerId }.source.raster
            assertTrue((0 until raster.width * raster.height).all { raster.rgba[it * 4 + 3] == 0.toByte() })
        }
        val repainted = commands.execute(root.projectId, clear.state, "Restore mouth pixels", listOf(shape), MutationAuthor.USER).capture
        val deleted = commands.execute(root.projectId, repainted.state, "Delete born mouth", listOf(
            WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", "mouth") })), MutationAuthor.USER).capture
        assertEquals(listOf("face"), deleted.model.rig.layerIdByDrawableId.values.toList())
        assertEquals(listOf("face"), deleted.model.analysis.layers.map { it.source.id.raw })
        val restored = commands.execute(root.projectId, deleted.state, "Restore born mouth", listOf(
            WorkspaceDocumentOperation("layer_restore", buildJsonObject {})), MutationAuthor.USER).capture
        assertEquals(repainted.revision, restored.revision)
        for (layerId in listOf("mouth") + lipIds) {
            assertEquals(drawable(repainted.model, layerId).id, drawable(restored.model, layerId).id)
            assertContentEquals(drawable(repainted.model, layerId).mesh!!.positions, drawable(restored.model, layerId).mesh!!.positions)
        }
        val store = WorkspaceStore(temp.resolve("mouth-creation-store"))
        val repository = ProjectRepository()
        val archive = temp.resolve("mouth-creation.psd2live")
        repository.save(ProjectSaveCapture(root.projectId, runtime.history(), JsonObject(emptyMap()), null, store), archive)
        val reopened = repository.open(archive).use { builder.build(it.history.head().snapshot) }
        val exported = PSD2LivePipeline().run(repainted.document.source, "mouth-creation", temp.resolve("mouth-creation-export"), repainted.document.config())
        val imported = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(exported.exportedFiles.single {
            it.path.toString().endsWith(".cmo3") }.path)).root as CModelSource)
        for (open in listOf(0f, 0.5f, 1f)) {
            val pose = mapOf(StandardParameters.MOUTH_OPEN to open)
            val expected = evaluator.evaluate(repainted.model.rig.puppet, pose)
            for (model in listOf(reopened.rig.puppet, imported)) {
                val actual = evaluator.evaluate(model, pose)
                expected.worldPositions.forEach { (id, vertices) ->
                    assertEquals(vertices.size, actual.worldPositions.getValue(id).size)
                    vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                    assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                }
            }
        }
        val frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 80f))
        fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "mouth-creation", mapOf(StandardParameters.MOUTH_OPEN.raw to 1f),
            setOf("face", "mouth") + lipIds, emptySet(), frame, WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(512)).png
        assertContentEquals(render(repainted.model), render(reopened))
        val directory = Path.of("build/raster-creation-visual").toAbsolutePath()
        Files.createDirectories(directory)
        Files.write(directory.resolve("mouth-before-reopen.png"), render(repainted.model))
        Files.write(directory.resolve("mouth-after-reopen.png"), render(reopened))
    }

    @Test fun clearingTheOnlyLayerKeepsItsMeshAndBindingsAndCanBePaintedAgain() = runBlocking<Unit> {
        val preview = base(PSD2LivePipeline())
        val source = (preview.analysis.source as WorkspaceSourceArt).copy(layers = preview.analysis.source.layers.take(1))
        val config = preview.config
        val initialModel = PSD2LivePipeline().buildPreview(source, config)
        val initial = WorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), config.rigEdits, WorkspaceSettingsCodec.encode(config))
        val builder = WorkspacePreviewBuilder()
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "clear", initial, initialModel)
        val root = runtime.capture()
        val clear = WorkspaceDocumentOperation("source_paint_clear", buildJsonObject { put("layer_id", "art0") })
        val commands = WorkspaceDocumentCommands(runtime)
        val cleared = commands.execute(root.projectId, root.state, "Clear", listOf(clear), MutationAuthor.USER)
        assertTrue(cleared.applied)
        assertTrue(cleared.capture.document.deletedLayerIds.isEmpty())
        assertContentEquals(drawable(initialModel, "art0").mesh!!.positions, drawable(cleared.capture.model, "art0").mesh!!.positions)
        assertEquals(listOf(0, 0, 0, 0), cleared.capture.document.sampleSourceColor("art0", 20, 20))
        val again = commands.execute(root.projectId, cleared.capture.state, "Clear again", listOf(clear), MutationAuthor.USER)
        assertFalse(again.applied)
        val painted = commands.execute(root.projectId, again.capture.state, "Repaint", listOf(WorkspaceDocumentOperation("source_paint_pencil", buildJsonObject {
            put("layer_id", "art0"); put("radius", 2)
            put("points", buildJsonArray { add(buildJsonArray { add(20); add(20) }) })
            put("color", buildJsonArray { add(220); add(70); add(40); add(255) })
        })), MutationAuthor.USER)
        assertTrue(painted.applied)
        assertEquals(listOf(220, 70, 40, 255), painted.capture.document.sampleSourceColor("art0", 20, 20))
        assertContentEquals(drawable(initialModel, "art0").mesh!!.positions, drawable(painted.capture.model, "art0").mesh!!.positions)
    }

    @Test fun journalTextureCoordinatesSurviveRecropRotatedScaledPackingAndTileUvs() = runBlocking<Unit> {
        val before = authored(PSD2LivePipeline(), glue = false)
        val model = before.rig.puppet
        val target = drawable(before, "art0")
        val command = RasterMeshJournal.encode(model, target.id, withTriangleCenter(target.mesh!!))
        val tile = model.atlas.tiles.single { it.id == target.atlasTileId }
        val reference = tile.source!!
        val layer = model.sources.single { it.id == reference.sourceId }.layers.single { it.key == reference.layerKey }
        val resized = layer.copy(left = layer.left + 5, top = layer.top - 3, width = layer.width * 2, height = layer.height * 2)
        val packing = org.umamo.runtime.model.AtlasPlacement(0, 180f, 24f, 1.5f, 0.5f, 90f)
        val repacked = model.copy(atlas = model.atlas.copy(pages = listOf(AtlasPage(512, 768)), tiles = model.atlas.tiles.map {
            if (it.id == tile.id) it.copy(placement = packing) else it
        }), sources = model.sources.map { source -> source.copy(layers = source.layers.map { if (it.key == layer.key) resized else it }) })
        val canvas = command.getValue("texture_canvas").jsonArray.map { it.jsonPrimitive.float }
        for (pages in listOf(true, false)) {
            val replayed = RasterMeshJournal.replay(repacked.copy(atlas = repacked.atlas.copy(storedUvsAddressPages = pages)), command)
            val mesh = replayed.drawables.single { it.id == target.id }.mesh!!
            for (index in mesh.uvs.indices step 2) {
                val x = (canvas[index] - resized.left) * tile.width / resized.width
                val y = (canvas[index + 1] - resized.top) * tile.height / resized.height
                val pixel = atlasPixelOf(packing, x, y)
                assertEquals(if (pages) pixel[0] / 512 else x / tile.width, mesh.uvs[index], 0.000001f)
                assertEquals(if (pages) pixel[1] / 768 else y / tile.height, mesh.uvs[index + 1], 0.000001f)
            }
        }
    }

    @Test fun identityMigrationCreatesNoJournalAndMalformedOrStaleMigrationCannotMutateRig() = runBlocking<Unit> {
        val before = authored(PSD2LivePipeline(), glue = true)
        val model = before.rig.puppet
        val target = drawable(before, "art0")
        val identity = RasterMeshJournal.encode(model, target.id, target.mesh!!)
        val (same, journal) = RigAuthoringJournal.compile(model, buildJsonArray { add(identity) })
        assertSame(model, same)
        assertTrue(journal.isEmpty())
        val duplicate = DrawableMesh(target.mesh.positions + target.mesh.positions.take(2),
            target.mesh.uvs + target.mesh.uvs.take(2), target.mesh.indices)
        val uvOnly = DrawableMesh(duplicate.positions, FloatArray(duplicate.uvs.size) { duplicate.uvs[it] + 0.001f }, duplicate.indices)
        val duplicatePlan = RasterMeshJournal.prepare(duplicate, uvOnly)
        assertContentEquals(IntArray(duplicate.vertexCount) { it }, duplicatePlan.glueMap)
        assertEquals(List(duplicate.vertexCount) { org.umamo.edit.VertexSource.FromOld(it) }, duplicatePlan.sources)
        val valid = RasterMeshJournal.encode(model, target.id, withTriangleCenter(target.mesh))
        val invalid = listOf(
            "before_mesh" to JsonPrimitive("stale"), "parent" to JsonPrimitive("different"),
            "triangles" to buildJsonArray { add(0); add(1); add(Int.MAX_VALUE) },
            "texture_canvas" to buildJsonArray { add(0) }, "sources" to buildJsonArray { add(buildJsonArray { add(-1) }) },
            "glue_map" to JsonArray(emptyList()),
            "previous_parent_points" to buildJsonArray { add(0) },
        )
        val positions = target.mesh.positions.copyOf()
        invalid.forEach { (field, value) ->
            assertFailsWith<IllegalArgumentException>(field) { RasterMeshJournal.replay(model, JsonObject(valid + (field to value))) }
            assertContentEquals(positions, target.mesh.positions)
        }
        val changedGeometry = model.copy(drawables = model.drawables.map { drawable ->
            if (drawable.id != target.id) drawable else drawable.copy(mesh = DrawableMesh(
                target.mesh.positions.copyOf().also { it[0] += 1 }, target.mesh.uvs, target.mesh.indices))
        })
        assertFailsWith<IllegalArgumentException> { RasterMeshJournal.replay(changedGeometry, valid) }
    }

    @Test fun collapsedGlueEndpointsRetainSequentialDirectionalPullsAndNeverLeaveDanglingIndices() = runBlocking<Unit> {
        val before = authored(PSD2LivePipeline(), glue = true)
        val target = drawable(before, "art0")
        val mesh = target.mesh!!
        val vertices = mesh.indices.take(3)
        val replacement = DrawableMesh(vertices.flatMap { listOf(mesh.positions[it * 2], mesh.positions[it * 2 + 1]) }.toFloatArray(),
            vertices.flatMap { listOf(mesh.uvs[it * 2], mesh.uvs[it * 2 + 1]) }.toFloatArray(), intArrayOf(0, 1, 2))
        val old = before.rig.puppet.glues.single()
        val directional = old.copy(pairs = old.pairs + old.pairs.first().let { GluePair(it.indexA, it.indexB, 0.9f, 0.1f) })
        val model = before.rig.puppet.copy(glues = listOf(directional))
        val plan = RasterMeshJournal.prepare(mesh, replacement)
        val after = RasterMeshJournal.apply(model, target.id, plan)
        val pairs = after.glues.single().pairs
        assertTrue(pairs.isNotEmpty())
        assertEquals(directional.pairs.size, pairs.size)
        assertTrue(pairs.map { it.indexA to it.indexB }.distinct().size < pairs.size)
        assertTrue(pairs.all { it.indexA in 0..2 && it.indexB in 0 until drawable(before, "art1").mesh!!.vertexCount })
        directional.pairs.zip(pairs).forEach { (oldPair, newPair) ->
            assertEquals(plan.glueMap[oldPair.indexA], newPair.indexA); assertEquals(oldPair.indexB, newPair.indexB)
            assertEquals(oldPair.weightA, newPair.weightA); assertEquals(oldPair.weightB, newPair.weightB)
        }
        val evaluator = CpuDeformationEvaluator()
        val preWeld = evaluator.evaluate(after.copy(glues = emptyList()), emptyMap()).worldPositions
        val expectedA = preWeld.getValue(old.meshA).copyOf(); val expectedB = preWeld.getValue(old.meshB).copyOf()
        for (pair in directional.pairs) {
            val a = plan.glueMap[pair.indexA] * 2; val b = pair.indexB * 2
            for (axis in 0..1) {
                val previousA = expectedA[a + axis]; val previousB = expectedB[b + axis]
                expectedA[a + axis] = previousA + (previousB - previousA) * pair.weightA * directional.intensity
                expectedB[b + axis] = previousB + (previousA - previousB) * pair.weightB * directional.intensity
            }
        }
        val evaluated = evaluator.evaluate(after, emptyMap()).worldPositions
        assertContentEquals(expectedA, evaluated.getValue(old.meshA)); assertContentEquals(expectedB, evaluated.getValue(old.meshB))
        assertContentEquals(mesh.positions, target.mesh.positions)
        assertSame(directional.channelGrids, after.glues.single().channelGrids)
    }
}
