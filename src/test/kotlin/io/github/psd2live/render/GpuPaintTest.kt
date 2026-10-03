package io.github.psd2live.render

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.PaintSession
import io.github.psd2live.ui.RigCanvasSupport
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpuPaintTest {
	@Test fun gpuPreviewHandsOverOnlyWhatChangedPremultiplied() {
		val image = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
		val session = PaintSession("layer", "layer", image, PaintSession.copyImage(image))
		session.gpuPreview = true
		// The first upload of a texture is the whole raster.
		val full = assertNotNull(session.takeGpuUpload(full = true))
		assertEquals(listOf(0, 0, 100, 80), listOf(full.x, full.y, full.width, full.height))
		assertNull(session.takeGpuUpload(full = false), "nothing changed since")

		val tilesBefore = session.previewTiles
		val version = session.gpuVersion
		session.edit(Rectangle(10, 20, 4, 3)) { it.setRGB(10, 20, 0x80FF0000.toInt()) }
		session.edit(Rectangle(30, 5, 2, 2)) { it.setRGB(31, 6, 0xFF00FF00.toInt()) }
		session.refreshPreview()
		assertTrue(session.gpuVersion > version, "a change bumps the version the canvas redraws on")
		assertTrue(session.previewTiles === tilesBefore, "no preview tiles are painted for a GPU canvas")

		val upload = assertNotNull(session.takeGpuUpload(full = false))
		assertEquals(listOf(10, 5, 22, 18), listOf(upload.x, upload.y, upload.width, upload.height), "the union of both edits")
		fun rgba(x: Int, y: Int) = (0..3).map { upload.rgba[((y - upload.y) * upload.width + (x - upload.x)) * 4 + it].toInt() and 0xff }
		assertEquals(listOf(128, 0, 0, 128), rgba(10, 20), "half-transparent red, premultiplied")
		assertEquals(listOf(0, 255, 0, 255), rgba(31, 6))
		assertNull(session.takeGpuUpload(full = false))
	}

	@Test fun aReplacedSceneKeepsItsPaintUploads() {
		val model = PSD2LivePipeline().buildPreview(Path.of("examples/ds/psd-input/ds.psd"))
		val geometry = RigCanvasSupport.evaluate(model)
		val viewport = CanvasViewport(1.0, 0.0, 0.0, 10f, 10f)
		val session = Any()
		fun upload(x: Int) = PaintUpload(x, 0, 1, 1, ByteArray(4))
		fun scene(paint: PaintScene?) = CanvasScene(10, 10, viewport, model, geometry, emptyList(), paint = paint)
		val first = scene(PaintScene(session, 10, 10, listOf(upload(1))))
		val second = scene(PaintScene(session, 10, 10, listOf(upload(2))))
		assertEquals(listOf(1, 2), second.after(first).paint!!.uploads.map { it.x }, "oldest first")
		// Another session's uploads belong to another texture.
		val other = scene(PaintScene(Any(), 10, 10, listOf(upload(3))))
		assertEquals(listOf(3), other.after(first).paint!!.uploads.map { it.x })
	}

	@Test fun paintTextureLandsOnTheDocumentAndTakesPartialUploads() {
		val host = GlHost.start()
		assumeTrue(host != null, "No OpenGL 3.3 context: ${GlHost.failure}")
		try {
			val renderer = host!!.submit { GlCanvasRenderer() }.get()
			val model = PSD2LivePipeline().buildPreview(Path.of("examples/ds/psd-input/ds.psd"))
			val geometry = RigCanvasSupport.evaluate(model)
			// Document pixel (x, y) lands on screen (10 + 2x, 20 + 2y).
			val viewport = CanvasViewport(2.0, 10.0, 20.0, 40f, 30f)
			val session = Any()
			fun solid(x: Int, y: Int, w: Int, h: Int, r: Int, g: Int, b: Int) =
				PaintUpload(x, y, w, h, ByteArray(w * h * 4) { i -> when (i % 4) { 0 -> r; 1 -> g; 2 -> b; else -> 255 }.toByte() })
			fun render(vararg uploads: PaintUpload): ByteArray {
				val scene = CanvasScene(120, 100, viewport, model, geometry, emptyList(), paint = PaintScene(session, 40, 30, uploads.toList()))
				return requireNotNull(host.submit { renderer.render("paint", scene) }.get().readPixels())
			}
			fun at(pixels: ByteArray, x: Int, y: Int) = (0..3).map { pixels[(y * 120 + x) * 4 + it].toInt() and 0xff }
			// The whole raster: transparent with a red block at document (5..14, 4..9).
			val clear = PaintUpload(0, 0, 40, 30, ByteArray(40 * 30 * 4))
			val first = render(clear, solid(5, 4, 10, 6, 255, 0, 0))
			assertEquals(listOf(255, 0, 0, 255), at(first, 10 + 2 * 8 + 1, 20 + 2 * 6 + 1), "inside the block")
			assertEquals(0, at(first, 10 + 2 * 20, 20 + 2 * 6)[3], "outside it, the document stays clear")
			assertEquals(0, at(first, 5, 5)[3], "nothing off the document")
			// A later frame uploads only what changed; the rest of the texture keeps the earlier stroke.
			val second = render(solid(20, 10, 4, 4, 0, 0, 255))
			assertEquals(listOf(0, 0, 255, 255), at(second, 10 + 2 * 21 + 1, 20 + 2 * 11 + 1))
			assertEquals(listOf(255, 0, 0, 255), at(second, 10 + 2 * 8 + 1, 20 + 2 * 6 + 1))
			host.submit { renderer.close() }.get()
		} finally {
			host?.close()
		}
	}
}
