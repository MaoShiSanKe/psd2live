package io.github.psd2live.ui

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CControllerCurve
import org.umamo.format.cmo3.model.gen.CControllerExtension
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.*
import org.umamo.render.eval.DeformedGeometry
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformPathMetrics
import org.umamo.runtime.model.*
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeformPathRadiusTest {
    private fun newlyCreatedPuppet(): PuppetModel {
        val source = puppet()
        val span = 50f / 0.06f
        val warp = (source.deformers.single() as Deformer.Warp).copy(
            geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
                WarpLatticeForm(floatArrayOf(0f, 0f, span, 0f, 0f, span, span, span))))),
        )
        val drawable = source.drawables.single().let { d ->
            d.copy(mesh = DrawableMesh(floatArrayOf(0.2f, 0.2f, 0.7f, 0.2f, 0.2f, 0.7f), d.mesh!!.uvs, d.mesh.indices),
                geometryGrid = KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(FloatArray(6))))))
        }
        return source.copy(deformers = listOf(warp), drawables = listOf(drawable),
            deformPaths = source.deformPaths.map { it.copy(width = 0.06f) })
    }

    @Test fun newlyCreatedLocalPathShows50PixelsBeforeAndAfterExportNormalization() {
        val live = newlyCreatedPuppet()
        val normalized = restMeshesToCanvasSpace(live)
        for (model in listOf(live, normalized)) {
            assertEquals(50f, DeformPathMetrics.canvasWidth(model, model.drawables.single(), model.deformPaths.single()), 0.001f)
            val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                RigInformationOverlay.paintDeformPaths(graphics, model, CpuDeformationEvaluator().evaluate(model, emptyMap()),
                    CanvasViewport(1.0, 0.0, 0.0, 400f, 400f), setOf("*"), showWidth = true,
                    selectedPathId = model.deformPaths.single().id)
            } finally { graphics.dispose() }
            val cx = (50f / 0.06f) * 0.2f
            val cy = cx
            val boundary = buildList {
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val color = Color(image.getRGB(x, y), true)
                    if (color.alpha > 50 && color.red > 180 && color.green < 120 && color.blue < 120 && hypot(x - cx.toDouble(), y - cy.toDouble()) < 80) add(hypot(x - cx.toDouble(), y - cy.toDouble()))
                }
            }
            assertTrue(boundary.size > 100, "newly created path radius must be visible")
            assertTrue(boundary.all { kotlin.math.abs(it - 50) < 3 }, "newly created radius must be 50 canvas pixels")
        }
        val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(2, 2, ByteArray(16) { 0xFF.toByte() })), 2, 2)
        val result = Cmo3Conversion.freshCmo3(normalized, listOf(page), mapOf("mesh" to 0), "new path", 0L, 0x42)
        val file = Cmo3.read(Cmo3.write(result.model))
        assertEquals(50f, nativeWidth(file), 0.001f)
        val imported = Cmo3Import.fromModelSource(file.root as CModelSource)
        assertEquals(50f, DeformPathMetrics.canvasWidth(imported, imported.drawables.single(), imported.deformPaths.single()), 0.001f)
    }

    private fun puppet(): PuppetModel {
        val canvas = floatArrayOf(40f, 50f, 140f, 50f, 40f, 100f)
        val local = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val warp = Deformer.Warp(
            DeformerId("warp"), "Warp", null, null, 1, 1, true,
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
                WarpLatticeForm(floatArrayOf(40f, 50f, 140f, 50f, 40f, 100f, 140f, 100f))))),
        )
        val drawable = Drawable(
            DrawableId("mesh"), "Mesh", warp.id, BlendMode.Normal, emptyList(),
            DrawableMesh(canvas, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
            KeyformGrid(emptyList(), listOf(KeyformCell(intArrayOf(),
                MeshDeltaForm(FloatArray(canvas.size) { local[it] - canvas[it] })))),
        )
        val path = DeformPath(
            "a1111111-1111-4111-8111-111111111111", drawable.id,
            listOf(DeformPathPoint(0, 1, 2, 1f, 0f, 0f), DeformPathPoint(0, 1, 2, 0f, 1f, 0f)),
            width = 0.2f, hardness = 0.6f,
        )
        return PuppetModel(emptyList(), emptyList(), listOf(warp), listOf(drawable),
            listOf(OrgChild.Drawable(drawable.id)), null, canvasWidth = 200f, canvasHeight = 150f,
            deformPaths = listOf(path))
    }

    private fun nativeWidth(model: org.umamo.format.cmo3.Cmo3Model): Float {
        val source = Cmo3GraphIndex(model.root as CModelSource).drawableSources.single()
        val controller = Cmo3Import.elementsOf(source._extensions).filterIsInstance<CControllerExtension>().single()
        return Cmo3Import.elementsOf(controller.controlCurves).filterIsInstance<CControllerCurve>().single().lineWidth
    }

    @Test fun inspectorRadiusMatchesSerializedCmo3AndEditedRoundTrip() {
        val puppet = puppet()
        val drawable = puppet.drawables.single()
        val path = puppet.deformPaths.single()
        val expected = path.width * ((100.0 + 50.0 + hypot(100.0, 50.0)) / (2.0 + kotlin.math.sqrt(2.0))).toFloat()
        assertEquals(expected, DeformPathMetrics.canvasWidth(puppet, drawable, path), 1e-4f)
        val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(2, 2, ByteArray(16) { 0xFF.toByte() })), 2, 2)
        val result = Cmo3Conversion.freshCmo3(puppet, listOf(page), mapOf("mesh" to 0), "radius", 0L, 0x42)
        val file = Cmo3.read(Cmo3.write(result.model))
        assertEquals(expected, nativeWidth(file), 1e-4f)
        val imported = Cmo3Import.fromModelSource(file.root as CModelSource)
        assertEquals(path.width, imported.deformPaths.single().width, 1e-5f)
        // This is the same canvas-to-local conversion used by the inspector's edit callback.
        val edited = imported.copy(deformPaths = imported.deformPaths.map {
            it.copy(width = DeformPathMetrics.localWidth(imported, imported.drawables.single(), 32f))
        })
        Cmo3Export.apply(edited, file)
        val rewritten = Cmo3.read(Cmo3.write(file))
        assertEquals(32f, nativeWidth(rewritten), 1e-4f)
        val reimported = Cmo3Import.fromModelSource(rewritten.root as CModelSource)
        assertEquals(32f, DeformPathMetrics.canvasWidth(reimported, reimported.drawables.single(), reimported.deformPaths.single()), 1e-4f)
        // Changing the parent scale must update native width even when the path is unchanged.
        val rescaled = reimported.copy(deformers = reimported.deformers.map { deformer ->
            val warp = deformer as Deformer.Warp
            warp.copy(geometryGrid = warp.geometryGrid!!.let { grid ->
                KeyformGrid(grid.axes, grid.cells.map { cell ->
                    KeyformCell(cell.coordinate, WarpLatticeForm(FloatArray(cell.form.controlPoints.size) { i ->
                        cell.form.controlPoints[i] * 2f
                    }))
                })
            })
        })
        val rescaledWidth = DeformPathMetrics.canvasWidth(rescaled, rescaled.drawables.single(), rescaled.deformPaths.single())
        assertEquals(64f, rescaledWidth, 0.001f)
        Cmo3Export.apply(rescaled, rewritten)
        assertEquals(rescaledWidth, nativeWidth(Cmo3.read(Cmo3.write(rewritten))), 1e-4f)
    }

    @Test fun previewRadiusUsesExportDistanceAcrossZoomAndPoses() {
        val model = puppet()
        val drawable = model.drawables.single()
        val radius = DeformPathMetrics.canvasWidth(model, drawable, model.deformPaths.single())
        for (zoom in listOf(1.0, 2.0)) for (geometry in listOf(null,
            DeformedGeometry(mapOf(drawable.id to floatArrayOf(40f, 50f, 240f, 50f, 40f, 75f)), emptyMap(), emptyMap()))) {
            val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                RigInformationOverlay.paintDeformPaths(graphics, model, geometry,
                    CanvasViewport(zoom, 0.0, 250.0, 200f, 150f), setOf("*"), showWidth = true,
                    selectedPathId = model.deformPaths.single().id)
            } finally { graphics.dispose() }
            val cx = 40 * zoom
            val cy = 250 - 50 * zoom
            val distances = buildList {
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val color = Color(image.getRGB(x, y), true)
                    val distance = hypot(x - cx, y - cy)
                    if (color.alpha > 50 && color.red > 180 && color.green < 120 && color.blue < 120 && distance < 50 * zoom) add(distance)
                }
            }
            assertTrue(distances.size > 20, "radius boundary must be visible")
            assertTrue(distances.all { kotlin.math.abs(it - radius * zoom) < 3 },
                "preview boundary must match CMO3 canvas radius at zoom=$zoom: expected=${radius * zoom}, actual=${distances.min()}..${distances.max()}")
        }
    }
}
