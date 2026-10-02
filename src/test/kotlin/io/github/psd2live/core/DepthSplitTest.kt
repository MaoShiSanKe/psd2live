package io.github.psd2live.core

import io.github.psd2live.agent.AgentWorkspaceDocument
import io.github.psd2live.agent.AgentWorkspaceStore
import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.moc3.Moc3
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.*
import java.nio.file.Path
import kotlin.test.*

class DepthSplitTest {
    @TempDir lateinit var temp: Path

    private fun layer(id: String, name: String, order: Int, bounds: LayerBounds): WorkspaceSourceLayer {
        val rgba = ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 90 }
        return WorkspaceSourceLayer(LayerId(id), name, "", SourceLayerKind.Raster, true, order,
            bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
            LayerRaster(bounds.width, bounds.height, rgba), null, null, false)
    }

    private fun original(pipeline: PSD2LivePipeline): RigPreviewModel = pipeline.buildPreview(
        WorkspaceSourceArt(128, 160, listOf(
            layer("face", "face", 1, LayerBounds(28, 10, 72, 70)),
            layer("neck", "neck", 2, LayerBounds(50, 70, 28, 30)),
            layer("collar", "neckwear", 3, LayerBounds(30, 86, 68, 30)),
        ), emptyList()), PipelineConfig(atlasSize = 256, meshSpacing = 12))

    private fun mesh(preview: RigPreviewModel, layerId: String): Drawable = preview.rig.puppet.drawables.single {
        preview.rig.layerIdByDrawableId[it.id.raw] == layerId
    }

    private fun split(pipeline: PSD2LivePipeline, original: RigPreviewModel): DepthSplit.Result =
        DepthSplit.build(pipeline, original, original.config, mesh(original, "collar").id.raw, mesh(original, "neck").id.raw)

    private fun samePositions(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], 0.0001f, "component $it") }
    }

    @Test fun multipleMiddleMeshesPreserveRelativeOrderAtTheLimitsAndAfterReplay() {
        val pipeline = PSD2LivePipeline()
        val generated = original(pipeline)
        val config = generated.config.copy(drawOrderOverrides = mapOf("face" to 0f, "neck" to 1000f))
        val before = pipeline.rebuildPreview(generated, config)
        val result = DepthSplit.build(pipeline, before, config, mesh(before, "collar").id.raw,
            listOf(mesh(before, "neck").id.raw, mesh(before, "face").id.raw))
        val after = result.preview
        val reopened = pipeline.buildPreview(after.analysis.source, after.config)
        for (preview in listOf(after, reopened)) {
            val back = mesh(preview, "collar")
            val front = mesh(preview, result.frontLayerId)
            val face = mesh(preview, "face")
            val neck = mesh(preview, "neck")
            assertTrue(back.drawOrder < face.drawOrder && face.drawOrder < neck.drawOrder && neck.drawOrder < front.drawOrder)
            assertTrue(back.drawOrder >= 0f && front.drawOrder <= 1000f)
            assertEquals(4, preview.rig.puppet.drawables.size)
            assertEquals(back.id, preview.rig.puppet.glues.single().meshA)
            assertEquals(front.id, preview.rig.puppet.glues.single().meshB)
            for (id in listOf("face", "neck")) {
                assertContentEquals(mesh(before, id).mesh!!.positions, mesh(preview, id).mesh!!.positions)
            }
        }
    }

    @Test fun copiesIndependentArtAndPreservesAllPosesWithDirectionalGlue() {
        val pipeline = PSD2LivePipeline()
        val before = original(pipeline)
        val result = split(pipeline, before)
        val after = result.preview
        val back = mesh(after, "collar")
        val front = mesh(after, result.frontLayerId)
        val neck = mesh(after, "neck")
        assertEquals(before.rig.puppet.drawables.size + 1, after.rig.puppet.drawables.size)
        assertTrue(back.drawOrder < neck.drawOrder && neck.drawOrder < front.drawOrder)
        assertEquals(back.parentDeformerId, front.parentDeformerId)
        assertSame(back.geometryGrid, front.geometryGrid)
        assertNotEquals(back.atlasTileId, front.atlasTileId)
        assertNotSame(back.mesh!!.positions, front.mesh!!.positions)
        assertContentEquals(back.mesh.indices, front.mesh.indices)
        val oldRaster = before.analysis.layers.single { it.source.id.raw == "collar" }.source.raster.rgba
        val copiedRaster = after.analysis.layers.single { it.source.id.raw == result.frontLayerId }.source.raster.rgba
        assertNotSame(oldRaster, copiedRaster)
        assertContentEquals(oldRaster, copiedRaster)
        val glue = after.rig.puppet.glues.single { it.meshB == front.id }
        assertEquals(back.mesh.vertexCount, glue.pairs.size)
        assertTrue(glue.pairs.all { it.indexA == it.indexB && it.weightA == 0f && it.weightB == 1f })
        val runtime = Moc3Import.fromMocDocument(Moc3.read(after.runtimeBundle.assets.first { it.path.endsWith(".moc3") }.bytes), null)
        assertEquals(glue.pairs.size, runtime.glues.single().pairs.size)
        assertTrue(runtime.glues.single().pairs.all { it.weightA == 0f && it.weightB == 1f })
        val eval = CpuDeformationEvaluator()
        for (x in listOf(-30f, 0f, 30f)) for (y in listOf(-20f, 0f, 20f)) {
            val pose = mapOf(StandardParameters.ANGLE_X to x, StandardParameters.ANGLE_Y to y,
                StandardParameters.BODY_X to x / 3, StandardParameters.BREATH to 0.6f)
            val prior = eval.evaluate(before.rig.puppet, pose)
            val next = eval.evaluate(after.rig.puppet, pose)
            before.rig.puppet.drawables.forEach { samePositions(prior.worldPositions.getValue(it.id), next.worldPositions.getValue(it.id)) }
            samePositions(next.worldPositions.getValue(back.id), next.worldPositions.getValue(front.id))
            val exportedPose = eval.evaluate(runtime, pose)
            samePositions(exportedPose.worldPositions.getValue(back.id), exportedPose.worldPositions.getValue(front.id))
        }
    }

    @Test fun persistedHistoryReplaysAuthoredMeshAndIndependentTexture() {
        val pipeline = PSD2LivePipeline()
        val generated = original(pipeline)
        val source = mesh(generated, "collar")
        val points = source.mesh!!.positions.copyOf().also { it[0] += 0.025f }
        val command = buildJsonObject {
            put("op", "canvas_geometry"); put("id", source.id.raw); put("kind", "mesh")
            put("key", JsonObject(emptyMap())); put("preserve_image", true)
            put("points", JsonArray(points.map(::JsonPrimitive)))
        }
        val config = generated.config.copy(rigEdits = generated.config.rigEdits.copy(authoringJournal = listOf(command)))
        val edited = pipeline.rebuildPreview(generated, config)
        val result = split(pipeline, edited)
        val after = result.preview
        val document = AgentWorkspaceDocument(after.analysis.source, after.config.layerVisibility,
            after.config.deletedLayerIds, after.config.layerOverrides, after.config.parentOverrides, after.config.rigEdits)
        val tree = WorkspaceHistoryTree(document, "depth-revision", "depth-snapshot")
        val store = AgentWorkspaceStore(temp)
        store.persistHistory("depth", tree.state())
        val restored = assertNotNull(store.loadHistory("depth")).head().snapshot
        val reopened = pipeline.buildPreview(restored.source, after.config.copy(rigEdits = restored.rigEdits))
        val back = mesh(reopened, "collar")
        val front = mesh(reopened, result.frontLayerId)
        assertContentEquals(mesh(edited, "collar").mesh!!.positions, back.mesh!!.positions)
        assertContentEquals(back.mesh.positions, front.mesh!!.positions)
        assertEquals(after.rig.puppet.glues.single().pairs.size, reopened.rig.puppet.glues.single().pairs.size)
        val eval = CpuDeformationEvaluator()
        val pose = mapOf(StandardParameters.ANGLE_X to 24f, StandardParameters.ANGLE_Y to -15f)
        val expected = eval.evaluate(after.rig.puppet, pose)
        val actual = eval.evaluate(reopened.rig.puppet, pose)
        after.rig.puppet.drawables.forEach { samePositions(expected.worldPositions.getValue(it.id), actual.worldPositions.getValue(it.id)) }
    }

    @Test fun erasingFrontKeepsTextureRectangleRigAndGlueAfterReopen() {
        val pipeline = PSD2LivePipeline()
        val result = split(pipeline, original(pipeline))
        val split = result.preview
        val front = mesh(split, result.frontLayerId)
        val originalSource = split.analysis.layers.single { it.source.id.raw == "collar" }.source
        val frontSource = split.analysis.layers.single { it.source.id.raw == result.frontLayerId }.source
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(analysis = split.analysis, previewModel = split,
                rigEdits = split.config.rigEdits, parentOverrides = split.config.parentOverrides,
                layerOverrides = split.config.layerOverrides, drawOrderOverrides = split.config.drawOrderOverrides))
            val editor = vm.canvasEditor
            editor.selectLayer(result.frontLayerId)
            val session = assertNotNull(editor.startPaintSession(result.frontLayerId))
            editor.clearCurrentLayerPaint()
            assertTrue(session.isDirty)
            editor.promptCommitPaintSession()
            assertFalse(editor.showRebuildMeshDialog, "Depth-copy paint should apply without offering mesh reconstruction")
            val painted = assertNotNull(vm.state.value.previewModel)
            val paintedSource = painted.analysis.layers.single { it.source.id.raw == result.frontLayerId }.source
            assertEquals(frontSource.bounds, paintedSource.bounds)
            assertTrue(paintedSource.raster.rgba.indices.filter { it % 4 == 3 }.all { paintedSource.raster.rgba[it] == 0.toByte() })
            assertContentEquals(originalSource.raster.rgba, painted.analysis.layers.single { it.source.id.raw == "collar" }.source.raster.rgba)
            assertContentEquals(front.mesh!!.positions, mesh(painted, result.frontLayerId).mesh!!.positions)
            assertEquals(split.rig.puppet.glues.single().pairs.size, painted.rig.puppet.glues.single().pairs.size)
            val reopened = pipeline.buildPreview(painted.analysis.source, painted.config)
            val loadedFront = mesh(reopened, result.frontLayerId)
            assertContentEquals(front.mesh.indices, loadedFront.mesh!!.indices)
            assertEquals(frontSource.bounds.width, reopened.rig.puppet.atlas.tiles.single { it.id == loadedFront.atlasTileId }.width)
            assertEquals(split.rig.puppet.glues.single().pairs.size, reopened.rig.puppet.glues.single().pairs.size)
        }
    }

    @Test fun deletingTheFrontLayerDoesNotRecreateItOnReplay() {
        val pipeline = PSD2LivePipeline()
        val result = split(pipeline, original(pipeline))
        val deleted = pipeline.buildPreview(result.preview.analysis.source,
            result.preview.config.copy(deletedLayerIds = setOf(result.frontLayerId)))
        assertTrue(deleted.rig.layerIdByDrawableId.values.none { it == result.frontLayerId })
        assertTrue(deleted.rig.puppet.glues.isEmpty())
    }
}
