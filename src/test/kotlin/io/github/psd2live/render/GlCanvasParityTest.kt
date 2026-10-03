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
