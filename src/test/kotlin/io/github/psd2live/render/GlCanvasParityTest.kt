package io.github.psd2live.render

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.core.StandardParameters
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.ComponentPalette
import io.github.psd2live.ui.RigCanvasSupport
import io.github.psd2live.ui.SkiaRigPainter
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The GPU canvas renderer against the Skia painter it replaces: the same scenes, pixel by pixel.
 *
 * Needs an OpenGL 3.3 context; without a display it is skipped (CI runs it under xvfb-run with Mesa).
 * On a mismatch both pictures and their difference are written to build/tools/gl-parity/.
 */
class GlCanvasParityTest {
	private var host: GlHost? = null
	private var renderer: GlCanvasRenderer? = null

	@BeforeTest fun start() {
		host = GlHost.start()
		assumeTrue(host != null, "No OpenGL 3.3 context: ${GlHost.failure}")
		renderer = host!!.submit { GlCanvasRenderer() }.get()
	}

	@AfterTest fun stop() {
		renderer?.let { r -> host?.submit { r.close() }?.get() }
		host?.close()
	}

	@Test fun samplesMatchTheSkiaPainter() {
		for (sample in listOf("tml", "ds")) {
			val model = PSD2LivePipeline().buildPreview(Path.of("examples/$sample/psd-input/$sample.psd"))
			val width = 960
			val height = 720
			val poses = listOf(
				"rest" to emptyMap(),
				"turned" to mapOf(StandardParameters.ANGLE_X to 25f, StandardParameters.ANGLE_Y to -12f,
					StandardParameters.BODY_X to 6f, StandardParameters.MOUTH_OPEN to 1f, StandardParameters.EYE_L_OPEN to 0.2f),
			)
			val layers = model.rig.layerIdByDrawableId.values.toList()
			// The largest mesh drawn at rest, so the wash covers something in every view.
			val hovered = ArtworkDrawList.build(model, RigCanvasSupport.evaluate(model), ArtworkOptions())
				.maxByOrNull { draw -> model.rig.puppet.drawables.first { it.id == draw.drawableId }.mesh?.indices?.size ?: 0 }
				?.let { model.rig.layerIdByDrawableId[it.drawableId.raw] }
			val options = listOf(
				"plain" to ArtworkOptions(),
				"dimmed" to ArtworkOptions(dimUnselected = true, highlightedLayerIds = setOfNotNull(layers.firstOrNull())),
				"hover" to ArtworkOptions(tintLayerIds = setOfNotNull(hovered), tintColor = hovered?.let { ComponentPalette.strong(it).rgb } ?: 0),
			)
			for ((zoomName, zoom) in listOf("fit" to 1.0, "zoom3" to 3.0)) {
				val viewport = viewport(model, width, height, zoom)
				for ((poseName, pose) in poses) {
					val geometry = RigCanvasSupport.evaluate(model, pose)
					val rendered = HashMap<String, ByteArray>()
					for ((optionName, option) in options) {
						val draws = ArtworkDrawList.build(model, geometry, option)
						val scene = CanvasScene(width, height, viewport, model, geometry, draws)
						val gpu = renderer!!.let { r -> host!!.submit { r.render("parity", scene) }.get() }
						val cpu = skia(model, scene)
						compare("$sample-$zoomName-$poseName-$optionName", cpu, gpu, width, height)
						rendered[optionName] = requireNotNull(gpu.readPixels())
						if (optionName == "plain") {
							val masked = draws.count { it.maskIds.isNotEmpty() }
							println("PARITY $sample-$zoomName-$poseName: ${draws.size} meshes drawn, $masked masked")
							if (zoomName == "fit" && poseName == "rest") assertTrue(masked > 0, "$sample: the sample should exercise masks")
						}
					}
					for (variant in listOf("dimmed", "hover")) {
						val a = rendered.getValue("plain")
						val b = rendered.getValue(variant)
						assertTrue(a.indices.count { a[it] != b[it] } > 1000, "$sample-$zoomName-$poseName: $variant changes nothing")
					}
				}
			}
		}
	}

	@Test fun overlayLinesAndPointsLandWhereTheCameraPutsThem() {
		val model = PSD2LivePipeline().buildPreview(Path.of("examples/ds/psd-input/ds.psd"))
		val geometry = RigCanvasSupport.evaluate(model)
		// World (0, 0) at screen (10, 50); world y runs up, so world y = -20 is screen row 70.
		val viewport = CanvasViewport(1.0, 10.0, 50.0, 100f, 100f)
		// A concave L: a long arm across screen x 70..110, y 80..90 and a short one down x 70..90 to y 98, so the
		// notch at x 90..110, y 90..98 must stay empty.
		val ell = floatArrayOf(60f, -30f, 100f, -30f, 100f, -40f, 80f, -40f, 80f, -48f, 60f, -48f)
		val overlay = OverlayScene(listOf(
			LineBatch(0xFFFF0000.toInt(), 3f, floatArrayOf(0f, 0f, 100f, 0f)),
			PointBatch(0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 6f, 2f, floatArrayOf(50f, -20f)),
			FillBatch(0xFFFFFF00.toInt(), listOf(ell)),
		))
		val scene = CanvasScene(120, 100, viewport, model, geometry, emptyList(), overlay)
		val pixels = requireNotNull(renderer!!.let { r -> host!!.submit { r.render("overlay", scene) }.get() }.readPixels())
		fun rgba(x: Int, y: Int) = (0..3).map { pixels[(y * 120 + x) * 4 + it].toInt() and 0xff }
		assertTrue(rgba(60, 50) == listOf(255, 0, 0, 255), "line centre ${rgba(60, 50)}")
		assertTrue(rgba(60, 53)[3] == 0, "a 3 px line stays within its width: ${rgba(60, 53)}")
		assertTrue(rgba(60, 70) == listOf(0, 255, 0, 255), "point centre ${rgba(60, 70)}")
		assertTrue(rgba(65, 70).let { it[2] > 200 && it[3] > 240 }, "point ring ${rgba(65, 70)}")
		assertTrue(rgba(60, 78)[3] == 0, "nothing past the point's radius: ${rgba(60, 78)}")
		assertTrue(rgba(75, 94) == listOf(255, 255, 0, 255), "inside the fill ${rgba(75, 94)}")
		assertTrue(rgba(100, 94) == listOf(0, 0, 0, 0), "the concave notch stays empty: ${rgba(100, 94)}")
		assertTrue(rgba(95, 82) == listOf(255, 255, 0, 255), "the long arm is filled: ${rgba(95, 82)}")
	}

	/** The GPU rig guides cover what the Java2D guides they replace paint, and nothing far from it. */
	@Test fun rigGuidesCoverWhatJava2DPaints() {
		val model = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val puppet = model.rig.puppet
		val width = 960
		val height = 720
		val viewport = viewport(model, width, height, 1.0)
		val warpIds = puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Warp>().map { it.id.raw }.toSet()
		val rotationIds = puppet.deformers.filterIsInstance<org.umamo.runtime.model.Deformer.Rotation>().map { it.id.raw }.toSet()
		val selected = warpIds.first()
		val points = io.github.psd2live.ui.RigInformationOverlay.warpPoints(puppet, emptyMap(), warpIds)
		val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
		image.createGraphics().apply {
			setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
			io.github.psd2live.ui.RigInformationOverlay.paintRotations(this, puppet, emptyMap(), viewport, rotationIds,
				labels = false, selectedDeformerId = selected, dimUnselected = true)
			io.github.psd2live.ui.RigInformationOverlay.paint(this, puppet, emptyMap(), viewport, warpIds, labels = false,
				selectedDeformerId = selected, dimUnselected = true, pointsById = points)
			dispose()
		}
		val guides = RigGuides(viewport)
		guides.rotations(io.github.psd2live.ui.RigInformationOverlay.rotationNeedles(puppet, emptyMap(), viewport, rotationIds, selected, null, true))
		guides.warps(io.github.psd2live.ui.RigInformationOverlay.warpLayers(puppet, points, warpIds, selected, null, true),
			io.github.psd2live.ui.RigCanvasSupport.deformerCorners(io.github.psd2live.ui.RigCanvasSupport.deformerOutlines(puppet, points), viewport))
		val scene = CanvasScene(width, height, viewport, model, RigCanvasSupport.evaluate(model), emptyList(), OverlayScene(guides.items))
		val gpu = requireNotNull(renderer!!.let { r -> host!!.submit { r.render("guides", scene) }.get() }.readPixels())
		fun javaPainted(x: Int, y: Int) = x in 0 until width && y in 0 until height && (image.getRGB(x, y) ushr 24) > 40
		fun gpuPainted(x: Int, y: Int) = x in 0 until width && y in 0 until height && (gpu[(y * width + x) * 4 + 3].toInt() and 0xff) > 40
		fun near(x: Int, y: Int, painted: (Int, Int) -> Boolean) = (-1..1).any { dy -> (-1..1).any { dx -> painted(x + dx, y + dy) } }
		var java = 0; var javaCovered = 0; var gpuCount = 0; var gpuCovered = 0
		for (y in 0 until height) for (x in 0 until width) {
			if (javaPainted(x, y)) { java++; if (near(x, y, ::gpuPainted)) javaCovered++ }
			if (gpuPainted(x, y)) { gpuCount++; if (near(x, y, ::javaPainted)) gpuCovered++ }
		}
		println("GUIDES java2d $java px, ${javaCovered * 100 / maxOf(1, java)}% covered by GPU; gpu $gpuCount px, ${gpuCovered * 100 / maxOf(1, gpuCount)}% near Java2D")
		assertTrue(java > 2000, "the Java2D guides drew something")
		assertTrue(javaCovered >= java * 0.97, "GPU misses Java2D guide pixels: $javaCovered of $java")
		assertTrue(gpuCovered >= gpuCount * 0.97, "GPU paints away from the Java2D guides: $gpuCovered of $gpuCount")
	}

	/** Deform paths, curves with halos, points, corner diamonds and hardness rings, as the Java2D guide pass paints them. */
	@Test fun deformPathGuidesCoverWhatJava2DPaints() {
		val base = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val face = base.rig.puppet.drawables.first { it.name.equals("face", ignoreCase = true) }
		val indices = requireNotNull(face.mesh).indices
		val step = indices.size / 3 / 6
		val third = 1f / 3f
		val points = (0 until 5).map { k ->
			val t = k * step * 3
			org.umamo.runtime.model.DeformPathPoint(indices[t], indices[t + 1], indices[t + 2], third, third, 1f - 2 * third, corner = k == 2)
		}
		val path = org.umamo.runtime.model.DeformPath("p", face.id, points, width = 40f, hardness = 60f, closed = true)
		val puppet = base.rig.puppet.copy(deformPaths = listOf(path))
		val model = base.copy(rig = base.rig.copy(puppet = puppet))
		val width = 960
		val height = 720
		val viewport = viewport(model, width, height, 3.0)
		val geometry = RigCanvasSupport.evaluate(model)
		val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
		image.createGraphics().apply {
			io.github.psd2live.ui.RigInformationOverlay.paintDeformPaths(this, puppet, geometry, viewport, setOf("p"),
				showHardness = true, selectedPathIds = setOf("p"))
			dispose()
		}
		val guides = RigGuides(viewport)
		guides.paths(io.github.psd2live.ui.RigInformationOverlay.deformPathLooks(puppet, geometry, viewport, setOf("p"),
			showHardness = true, selectedPathIds = setOf("p")))
		val scene = CanvasScene(width, height, viewport, model, geometry, emptyList(), OverlayScene(guides.items))
		val gpu = requireNotNull(renderer!!.let { r -> host!!.submit { r.render("paths", scene) }.get() }.readPixels())
		fun javaPainted(x: Int, y: Int) = x in 0 until width && y in 0 until height && (image.getRGB(x, y) ushr 24) > 40
		fun gpuPainted(x: Int, y: Int) = x in 0 until width && y in 0 until height && (gpu[(y * width + x) * 4 + 3].toInt() and 0xff) > 40
		fun near(x: Int, y: Int, painted: (Int, Int) -> Boolean) = (-1..1).any { dy -> (-1..1).any { dx -> painted(x + dx, y + dy) } }
		var java = 0; var javaCovered = 0; var gpuCount = 0; var gpuCovered = 0
		for (y in 0 until height) for (x in 0 until width) {
			if (javaPainted(x, y)) { java++; if (near(x, y, ::gpuPainted)) javaCovered++ }
			if (gpuPainted(x, y)) { gpuCount++; if (near(x, y, ::javaPainted)) gpuCovered++ }
		}
		println("PATHS java2d $java px, ${javaCovered * 100 / maxOf(1, java)}% covered by GPU; gpu $gpuCount px, ${gpuCovered * 100 / maxOf(1, gpuCount)}% near Java2D")
		assertTrue(java > 500, "the Java2D path guide drew something")
		assertTrue(javaCovered >= java * 0.97, "GPU misses Java2D path pixels: $javaCovered of $java")
		assertTrue(gpuCovered >= gpuCount * 0.97, "GPU paints away from the Java2D path: $gpuCovered of $gpuCount")
		// The halo is translucent: where curve pieces meet it must not darken, which the stencil-once stroke ensures.
		val haloAlphas = (0 until width * height).map { gpu[it * 4 + 3].toInt() and 0xff }.filter { it in 1..254 }
		println("PATHS translucent alphas: ${haloAlphas.groupingBy { it / 32 }.eachCount().toSortedMap()}")
	}

	private fun viewport(model: RigPreviewModel, width: Int, height: Int, zoom: Double): CanvasViewport {
		val w = model.analysis.source.widthPx.toDouble()
		val h = model.analysis.source.heightPx.toDouble()
		val scale = minOf((width - 68) / w, (height - 68) / h) * zoom
		// World y runs up from the document's top edge (negative downwards); zoomed views look at the head.
		val offsetX = width / 2.0 - w * scale / 2
		val offsetY = if (zoom == 1.0) (height - h * scale) / 2 else height / 2.0 - h * 0.25 * scale
		return CanvasViewport(scale, offsetX, offsetY, w.toFloat(), h.toFloat())
	}

	private fun skia(model: RigPreviewModel, scene: CanvasScene): ByteArray =
		Surface.makeRasterN32Premul(scene.width, scene.height).use { surface ->
			SkiaRigPainter(model.atlas).use { painter -> painter.paint(surface.canvas, model, scene.geometry, scene.viewport, scene.draws) }
			surface.makeImageSnapshot().use { image -> rgba(image, scene.width, scene.height) }
		}

	private fun rgba(image: Image, width: Int, height: Int): ByteArray {
		val bitmap = Bitmap().apply { allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)) }
		image.readPixels(bitmap, 0, 0)
		return requireNotNull(bitmap.readPixels())
	}

	/**
	 * Rasterizers may disagree on a pixel that a triangle edge crosses exactly and on texel rounding, so this
	 * allows a few wrong pixels and small channel differences, never a missing or misplaced part.
	 */
	private fun compare(name: String, cpu: ByteArray, gpuBitmap: Bitmap, width: Int, height: Int) {
		val gpu = requireNotNull(gpuBitmap.readPixels())
		var painted = 0
		var off = 0
		var total = 0L
		for (i in 0 until width * height) {
			var worst = 0
			for (c in 0..3) {
				val d = abs((cpu[i * 4 + c].toInt() and 0xff) - (gpu[i * 4 + c].toInt() and 0xff))
				total += d
				if (d > worst) worst = d
			}
			if ((cpu[i * 4 + 3].toInt() and 0xff) != 0 || (gpu[i * 4 + 3].toInt() and 0xff) != 0) painted++
			if (worst > 8) off++
		}
		val offShare = off.toDouble() / maxOf(1, painted)
		val mean = total.toDouble() / (width * height * 4)
		println("PARITY $name: painted $painted, off $off (${"%.3f".format(offShare * 100)}%), mean ${"%.3f".format(mean)}")
		if (painted < 1000 || offShare > 0.01 || mean > 1.0) {
			val out = File("build/tools/gl-parity").apply { mkdirs() }
			fun write(file: String, bytes: ByteArray) = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), bytes, width * 4)
				.use { File(out, file).writeBytes(requireNotNull(it.encodeToData(EncodedImageFormat.PNG)).bytes) }
			write("$name-cpu.png", cpu)
			write("$name-gpu.png", gpu)
			write("$name-diff.png", ByteArray(cpu.size) { i ->
				if (i % 4 == 3) -1 else minOf(255, abs((cpu[i].toInt() and 0xff) - (gpu[i].toInt() and 0xff)) * 4).toByte()
			})
		}
		assertTrue(painted >= 1000, "$name: nothing drawn")
		assertTrue(offShare <= 0.01 && mean <= 1.0, "$name: GPU and Skia differ on ${"%.2f".format(offShare * 100)}% of painted pixels, mean $mean")
	}

}
