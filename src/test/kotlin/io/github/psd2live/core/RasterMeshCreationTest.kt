package io.github.psd2live.core

import io.github.psd2live.project.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import org.umamo.render.eval.CpuDeformationEvaluator
import kotlin.test.*

class RasterMeshCreationTest {
    private fun preview(): RigPreviewModel {
        fun layer(id: String, name: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(
            LayerId(id), name, "", SourceLayerKind.Raster, true, order, bounds, 1f, false,
            LayerBlend.Normal, ChannelMask.ALL, LayerRaster(bounds.width, bounds.height,
                ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 90 }), null, null, false)
        return PSD2LivePipeline().buildPreview(WorkspaceSourceArt(96, 80, listOf(
            layer("face", "Face", 0, LayerBounds(8, 8, 72, 60)),
            layer("mouth", "Mouth open", 1, LayerBounds(32, 46, 24, 12))), emptyList()),
            PipelineConfig(atlasSize = 256, meshSpacing = 8, exportMoc3 = false, generatePhysics = false,
                rigEdits = RigEditOverlay(skeleton = SkeletonSpec.Disabled),
                layerOverrides = mapOf("face" to LayerClassificationOverride(tag = SemanticTag.FACE),
                    "mouth" to LayerClassificationOverride(tag = SemanticTag.MOUTH_OPEN))))
    }

    private fun without(model: PuppetModel, id: DrawableId) = model.copy(
        drawables = model.drawables.filterNot { it.id == id },
        parts = model.parts.map { it.copy(children = it.children.filterNot { child -> child == OrgChild.Drawable(id) }) },
        rootChildren = model.rootChildren.filterNot { it == OrgChild.Drawable(id) },
        deformPaths = model.deformPaths.filterNot { it.drawableId == id },
    ).withDerivedRenderRoot()

    @Test fun creationRestoresGeneratedKeyformsAndCanvasUvsAfterAtlasRepacking() {
        val preview = preview()
        val target = preview.rig.puppet.drawables.single { preview.rig.layerIdByDrawableId[it.id.raw] == MouthLipLayer.idFor("mouth", 0) }
        val command = RasterMeshCreation.encode(preview.rig, target.id)
        val original = without(preview.rig.puppet, target.id)
        val tile = original.atlas.tiles.single { it.id == target.atlasTileId }
        val reference = tile.source!!
        val sourceLayer = original.sources.single { it.id == reference.sourceId }.layers.single { it.key == reference.layerKey }
        val resized = sourceLayer.copy(left = sourceLayer.left - 3, top = sourceLayer.top - 4,
            width = sourceLayer.width + 12, height = sourceLayer.height + 8)
        val placement = AtlasPlacement(0, 180f, 24f, 1.5f, 0.5f, 90f)
        val repacked = original.copy(atlas = original.atlas.copy(pages = listOf(AtlasPage(512, 768)), tiles = original.atlas.tiles.map {
            if (it.id == tile.id) it.copy(placement = placement) else it
        }), sources = original.sources.map { source -> source.copy(layers = source.layers.map { if (it.key == sourceLayer.key) resized else it }) })
        val evaluator = CpuDeformationEvaluator()
        for (pages in listOf(true, false)) {
            val input = repacked.copy(atlas = repacked.atlas.copy(storedUvsAddressPages = pages))
            val (restored, records) = RigAuthoringJournal.compile(input, buildJsonArray { add(command) })
            assertEquals(listOf(command), records)
            val mesh = restored.drawables.single { it.id == target.id }.mesh!!
            assertContentEquals(target.mesh!!.positions, mesh.positions)
            val canvas = command.getValue("canvas_uvs").jsonArray.map { it.jsonPrimitive.float }
            val addresses = RasterMeshJournal.TextureCoordinates(restored, restored.drawables.single { it.id == target.id }).toCanvas(mesh.uvs)
            canvas.indices.forEach { assertEquals(canvas[it], addresses[it], 0.0001f) }
            for (open in listOf(0f, 0.5f, 1f)) {
                val pose = mapOf(StandardParameters.MOUTH_OPEN to open)
                val expected = evaluator.evaluate(preview.rig.puppet, pose)
                val actual = evaluator.evaluate(restored, pose)
                assertContentEquals(expected.worldPositions.getValue(target.id), actual.worldPositions.getValue(target.id))
                assertEquals(expected.opacity.getValue(target.id), actual.opacity.getValue(target.id))
            }
        }
    }

    @Test fun invalidCreationDataAndIdentityCollisionsRejectWithoutChangingTheInput() {
        val preview = preview()
        val target = preview.rig.puppet.drawables.first { it.geometryGrid != null }
        val command = RasterMeshCreation.encode(preview.rig, target.id)
        val input = without(preview.rig.puppet, target.id)
        val positions = target.mesh!!.positions.copyOf()
        val geometry = command.getValue("geometry").jsonObject
        val malformed = listOf(
            "id" to JsonPrimitive(input.drawables.first().id.raw),
            "parent" to JsonPrimitive("missing"), "source_id" to JsonPrimitive("missing"),
            "source" to JsonPrimitive("missing"), "layer_id" to JsonPrimitive(""),
            "source_bounds" to buildJsonArray { add(0); add(0); add(Int.MAX_VALUE); add(Int.MAX_VALUE) },
            "source_bounds" to buildJsonArray { add(0); add(0); add(-1); add(1) },
            "triangles" to buildJsonArray { add(0); add(1); add(Int.MAX_VALUE) },
            "canvas_uvs" to buildJsonArray { add(0) },
            "part" to JsonPrimitive("missing"),
            "geometry" to JsonObject(geometry + ("cells" to buildJsonArray {
                add(buildJsonArray { add(buildJsonArray { add(Int.MAX_VALUE) }); add(buildJsonArray { add(0) }) })
            })),
        )
        for ((field, value) in malformed) {
            assertFailsWith<IllegalArgumentException>(field) { RigAuthoringJournal.compile(input,
                buildJsonArray { add(JsonObject(command + (field to value))) }) }
            assertTrue(input.drawables.none { it.id == target.id })
            assertContentEquals(positions, target.mesh.positions)
        }
        assertFailsWith<IllegalArgumentException> { RasterMeshCreation.replay(preview.rig.puppet, command) }
    }
}
