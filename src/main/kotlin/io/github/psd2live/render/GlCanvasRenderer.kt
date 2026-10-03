package io.github.psd2live.render

import io.github.psd2live.ui.CanvasViewport
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33
import org.lwjgl.system.MemoryUtil
import org.umamo.runtime.model.DrawableId
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.util.IdentityHashMap

/**
 * Draws canvas scenes with OpenGL. Every method runs on the [GlHost] thread with its context current.
 *
 * The CPU deforms (the UI already has the geometry for picking), so the GPU only fills pixels: one VAO per mesh
 * with static UVs and indices and a dynamic position buffer, re-uploaded only when the scene hands over a
 * different array; the atlas pages as premultiplied textures; masks through the stencil buffer. The camera is a
 * uniform, so pan and zoom upload nothing.
 */
internal class GlCanvasRenderer : AutoCloseable {
	private class Program(val id: Int) {
		private val locations = HashMap<String, Int>()
		fun uniform(name: String): Int = locations.getOrPut(name) { GL20.glGetUniformLocation(id, name) }
	}

	private class MeshBuffers(val vao: Int, val positions: Int, val uvs: Int, val indices: Int) {
		var indexCount = 0
		var positionCapacity = 0
		var positionSource: FloatArray? = null
		var uvSource: FloatArray? = null
		var indexSource: IntArray? = null
	}

	/** One canvas's framebuffer and meshes. Meshes are per view: two canvases can show two poses at once. */
	private class View {
		var framebuffer = 0
		var color = 0
		var depthStencil = 0
		var capacityWidth = 0
		var capacityHeight = 0
		val meshes = HashMap<DrawableId, MeshBuffers>()
		var readback: ByteBuffer? = null
		/** Stencil reference of the last masked draw; cleared and restarted when it would overflow. */
		var stencilRef = 0
	}

	private val artwork = program(Shaders.ARTWORK_VERTEX, Shaders.ARTWORK_FRAGMENT)
	private val lines = program(Shaders.LINE_VERTEX, Shaders.LINE_FRAGMENT)
	private val points = program(Shaders.POINT_VERTEX, Shaders.POINT_FRAGMENT)
	private val textures = IdentityHashMap<BufferedImage, Int>()
	private val views = HashMap<String, View>()
	private val lineVao: Int
	private val lineInstances: Int
	private val pointVao: Int
	private val pointInstances: Int
	private val fillVao: Int = GL30.glGenVertexArrays()
	private val fillVertices: Int = GL15.glGenBuffers()
	private val worldUniform = FloatArray(4)

	init {
		fun quad(corners: FloatArray, instanceComponents: Int): Pair<Int, Int> {
			val vao = GL30.glGenVertexArrays()
			GL30.glBindVertexArray(vao)
			val cornerBuffer = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cornerBuffer)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, corners, GL15.GL_STATIC_DRAW)
			GL20.glEnableVertexAttribArray(0)
			GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
			val instances = GL15.glGenBuffers()
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instances)
			GL20.glEnableVertexAttribArray(1)
			GL20.glVertexAttribPointer(1, instanceComponents, GL11.GL_FLOAT, false, 0, 0L)
			GL33.glVertexAttribDivisor(1, 1)
			GL30.glBindVertexArray(0)
			return vao to instances
		}
		quad(floatArrayOf(0f, -1f, 1f, -1f, 0f, 1f, 1f, 1f), 4).let { (vao, buffer) -> lineVao = vao; lineInstances = buffer }
		quad(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), 2).let { (vao, buffer) -> pointVao = vao; pointInstances = buffer }
		GL30.glBindVertexArray(fillVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, fillVertices)
		GL20.glEnableVertexAttribArray(0)
		GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
		GL30.glBindVertexArray(0)
	}

	/**
	 * Draws [scene] for [viewId] and reads it back as a premultiplied RGBA bitmap, top row first. The bitmap is
	 * new each frame and owned by the caller.
	 */
	fun render(viewId: String, scene: CanvasScene): Bitmap {
		val width = scene.width.coerceAtLeast(1)
		val height = scene.height.coerceAtLeast(1)
		val view = views.getOrPut(viewId) { View() }
		ensureTarget(view, width, height)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, view.framebuffer)
		GL11.glViewport(0, 0, width, height)
		GL11.glDisable(GL11.GL_DEPTH_TEST)
		GL11.glDisable(GL11.GL_CULL_FACE)
		GL11.glDisable(GL11.GL_SCISSOR_TEST)
		GL11.glColorMask(true, true, true, true)
		GL11.glClearColor(0f, 0f, 0f, 0f)
		GL11.glClearStencil(0)
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_STENCIL_BUFFER_BIT)
		view.stencilRef = 0
		GL11.glEnable(GL11.GL_BLEND)
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
		worldTransform(scene.viewport, width, height)

		drawArtwork(view, scene)
		drawOverlay(scene.overlay, width, height)

		val bytes = width * height * 4
		val buffer = view.readback?.takeIf { it.capacity() >= bytes }
			?: MemoryUtil.memAlloc(bytes).also { next -> view.readback?.let(MemoryUtil::memFree); view.readback = next }
		buffer.clear()
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4)
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		val pixels = ByteArray(bytes)
		buffer.get(pixels, 0, bytes)
		return Bitmap().apply {
			allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
			installPixels(pixels)
			setImmutable()
		}
	}

	/** Frees what [viewId] holds on the GPU; the next render for it starts over. */
	fun release(viewId: String) {
		val view = views.remove(viewId) ?: return
		view.meshes.values.forEach(::deleteMesh)
		if (view.framebuffer != 0) GL30.glDeleteFramebuffers(view.framebuffer)
		if (view.color != 0) GL30.glDeleteRenderbuffers(view.color)
		if (view.depthStencil != 0) GL30.glDeleteRenderbuffers(view.depthStencil)
		view.readback?.let(MemoryUtil::memFree)
	}

	/** Drops atlas pages no longer in [live]; called when a model's atlas is replaced. */
	fun retainTextures(live: Collection<BufferedImage>) {
		val keep = java.util.Collections.newSetFromMap(IdentityHashMap<BufferedImage, Boolean>()).apply { addAll(live) }
		val iterator = textures.entries.iterator()
		while (iterator.hasNext()) {
			val (image, texture) = iterator.next()
			if (image !in keep) { GL11.glDeleteTextures(texture); iterator.remove() }
		}
	}

	override fun close() {
		views.keys.toList().forEach(::release)
		textures.values.forEach(GL11::glDeleteTextures)
		textures.clear()
		GL20.glDeleteProgram(artwork.id); GL20.glDeleteProgram(lines.id); GL20.glDeleteProgram(points.id)
		GL30.glDeleteVertexArrays(lineVao); GL30.glDeleteVertexArrays(pointVao); GL30.glDeleteVertexArrays(fillVao)
		GL15.glDeleteBuffers(lineInstances); GL15.glDeleteBuffers(pointInstances); GL15.glDeleteBuffers(fillVertices)
	}

	private fun worldTransform(viewport: CanvasViewport, width: Int, height: Int) {
		// screen = offset + world * scale (y: offsetY - world.y * scale), then screen -> clip with the top row at
		// clip -1, which is the framebuffer's first row and so the first row glReadPixels returns.
		worldUniform[0] = (2.0 * viewport.scale / width).toFloat()
		worldUniform[1] = (2.0 * viewport.offsetX / width - 1.0).toFloat()
		worldUniform[2] = (-2.0 * viewport.scale / height).toFloat()
		worldUniform[3] = (2.0 * viewport.offsetY / height - 1.0).toFloat()
	}

	private fun drawArtwork(view: View, scene: CanvasScene) {
		val model = scene.model
		val drawables = model.rig.puppet.drawables.associateBy { it.id }
		val pages = model.atlas.pages
		GL20.glUseProgram(artwork.id)
		GL20.glUniform4fv(artwork.uniform("u_world"), worldUniform)
		GL20.glUniform1i(artwork.uniform("u_texture"), 0)
		GL13.glActiveTexture(GL13.GL_TEXTURE0)
		val solid = artwork.uniform("u_solid")
		val opacity = artwork.uniform("u_opacity")
		val used = HashSet<DrawableId>()
		fun meshFor(id: DrawableId): MeshBuffers? {
			val drawable = drawables[id] ?: return null
			val mesh = drawable.mesh ?: return null
			val world = scene.geometry.worldPositions[id] ?: return null
			used += id
			return syncMesh(view, id, world, mesh.uvs, mesh.indices)
		}
		for (draw in scene.draws) {
			val buffers = meshFor(draw.drawableId) ?: continue
			if (buffers.indexCount == 0) continue
			val page = pages.getOrNull(draw.page) ?: continue
			val masked = draw.maskIds.isNotEmpty()
			if (masked) {
				if (view.stencilRef == 255) {
					GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
					view.stencilRef = 0
				}
				val ref = ++view.stencilRef
				// Mask triangles write the reference without colour; the part then draws where it matches.
				GL11.glEnable(GL11.GL_STENCIL_TEST)
				GL11.glColorMask(false, false, false, false)
				GL11.glStencilFunc(GL11.GL_ALWAYS, ref, 0xff)
				GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
				GL20.glUniform4f(solid, 0f, 0f, 0f, 1f)
				for (maskId in draw.maskIds) {
					val mask = meshFor(maskId) ?: continue
					drawMesh(mask)
				}
				GL11.glColorMask(true, true, true, true)
				GL11.glStencilFunc(GL11.GL_EQUAL, ref, 0xff)
				GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
			}
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture(page.image))
			GL20.glUniform4f(solid, 0f, 0f, 0f, -1f)
			GL20.glUniform1f(opacity, draw.opacity)
			drawMesh(buffers)
			if (draw.tintColor != 0) {
				val a = draw.tintAlpha.coerceIn(0f, 1f)
				val c = draw.tintColor
				GL20.glUniform4f(solid, (c ushr 16 and 0xff) / 255f * a, (c ushr 8 and 0xff) / 255f * a, (c and 0xff) / 255f * a, a)
				drawMesh(buffers)
			}
			if (masked) GL11.glDisable(GL11.GL_STENCIL_TEST)
		}
		GL30.glBindVertexArray(0)
		// Meshes that left the scene (deleted, hidden for good) give their buffers back.
		if (view.meshes.size > used.size) {
			val gone = view.meshes.keys.filter { it !in used }
			for (id in gone) view.meshes.remove(id)?.let(::deleteMesh)
		}
	}

	private fun drawMesh(buffers: MeshBuffers) {
		GL30.glBindVertexArray(buffers.vao)
		GL11.glDrawElements(GL11.GL_TRIANGLES, buffers.indexCount, GL11.GL_UNSIGNED_INT, 0L)
	}

	private fun drawOverlay(overlay: OverlayScene, width: Int, height: Int) {
		if (overlay.items.isEmpty()) return
		// The artwork's masks leave their references behind; the fills count from a clean stencil.
		GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT)
		for (item in overlay.items) when (item) {
			is LineBatch -> {
				if (item.segments.size < 4) continue
				GL20.glUseProgram(lines.id)
				GL20.glUniform4fv(lines.uniform("u_world"), worldUniform)
				GL20.glUniform2f(lines.uniform("u_viewport"), width.toFloat(), height.toFloat())
				GL30.glBindVertexArray(lineVao)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, lineInstances)
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, item.segments, GL15.GL_STREAM_DRAW)
				GL20.glUniform1f(lines.uniform("u_width"), item.width)
				setColor(lines.uniform("u_color"), item.argb)
				GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, item.segments.size / 4)
			}
			is PointBatch -> {
				if (item.centers.size < 2) continue
				GL20.glUseProgram(points.id)
				GL20.glUniform4fv(points.uniform("u_world"), worldUniform)
				GL20.glUniform2f(points.uniform("u_viewport"), width.toFloat(), height.toFloat())
				GL30.glBindVertexArray(pointVao)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, pointInstances)
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, item.centers, GL15.GL_STREAM_DRAW)
				GL20.glUniform1f(points.uniform("u_radius"), item.radius)
				GL20.glUniform1f(points.uniform("u_ring"), item.ring)
				setColor(points.uniform("u_fill"), item.fillArgb)
				setColor(points.uniform("u_stroke"), item.strokeArgb)
				GL31.glDrawArraysInstanced(GL11.GL_TRIANGLE_STRIP, 0, 4, item.centers.size / 2)
			}
			is FillBatch -> drawFill(item)
		}
		GL30.glBindVertexArray(0)
	}

	/**
	 * Stencil, then cover: each outline's triangle fan inverts the stencil, which leaves exactly the even-odd
	 * interior set, and one quad over the outlines' bounds paints it and clears the stencil behind itself.
	 */
	private fun drawFill(batch: FillBatch) {
		val contours = batch.contours.filter { it.size >= 6 }
		if (contours.isEmpty()) return
		val total = contours.sumOf { it.size }
		val vertices = FloatArray(total + 12)
		var n = 0
		var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
		for (contour in contours) {
			contour.copyInto(vertices, n)
			n += contour.size
			for (i in contour.indices step 2) {
				minX = minOf(minX, contour[i]); maxX = maxOf(maxX, contour[i])
				minY = minOf(minY, contour[i + 1]); maxY = maxOf(maxY, contour[i + 1])
			}
		}
		floatArrayOf(minX, minY, maxX, minY, minX, maxY, maxX, minY, maxX, maxY, minX, maxY).copyInto(vertices, n)
		GL20.glUseProgram(artwork.id)
		GL20.glUniform4fv(artwork.uniform("u_world"), worldUniform)
		GL30.glBindVertexArray(fillVao)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, fillVertices)
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW)
		GL11.glEnable(GL11.GL_STENCIL_TEST)
		GL11.glColorMask(false, false, false, false)
		GL11.glStencilFunc(GL11.GL_ALWAYS, 0, 0xff)
		GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INVERT)
		var first = 0
		for (contour in contours) {
			GL11.glDrawArrays(GL11.GL_TRIANGLE_FAN, first, contour.size / 2)
			first += contour.size / 2
		}
		GL11.glColorMask(true, true, true, true)
		GL11.glStencilFunc(GL11.GL_NOTEQUAL, 0, 0xff)
		GL11.glStencilOp(GL11.GL_ZERO, GL11.GL_ZERO, GL11.GL_ZERO)
		setColor(artwork.uniform("u_solid"), batch.argb)
		GL11.glDrawArrays(GL11.GL_TRIANGLES, first, 6)
		GL11.glDisable(GL11.GL_STENCIL_TEST)
	}

	/** Premultiplied from unpremultiplied ARGB. */
	private fun setColor(location: Int, argb: Int) {
		val a = (argb ushr 24 and 0xff) / 255f
		GL20.glUniform4f(location, (argb ushr 16 and 0xff) / 255f * a, (argb ushr 8 and 0xff) / 255f * a, (argb and 0xff) / 255f * a, a)
	}

	private fun syncMesh(view: View, id: DrawableId, world: FloatArray, uvs: FloatArray, indices: IntArray): MeshBuffers {
		val buffers = view.meshes.getOrPut(id) {
			val vao = GL30.glGenVertexArrays()
			MeshBuffers(vao, GL15.glGenBuffers(), GL15.glGenBuffers(), GL15.glGenBuffers()).also { created ->
				GL30.glBindVertexArray(vao)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, created.positions)
				GL20.glEnableVertexAttribArray(0)
				GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 0, 0L)
				GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, created.uvs)
				GL20.glEnableVertexAttribArray(1)
				GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 0, 0L)
				GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, created.indices)
				GL30.glBindVertexArray(0)
			}
		}
		// Identity, not contents: the editor's copy-on-write keeps an untouched mesh's arrays the same instances.
		if (buffers.positionSource !== world) {
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers.positions)
			if (world.size > buffers.positionCapacity) {
				GL15.glBufferData(GL15.GL_ARRAY_BUFFER, world, GL15.GL_DYNAMIC_DRAW)
				buffers.positionCapacity = world.size
			} else GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, world)
			buffers.positionSource = world
		}
		if (buffers.uvSource !== uvs) {
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffers.uvs)
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, uvs, GL15.GL_STATIC_DRAW)
			buffers.uvSource = uvs
		}
		if (buffers.indexSource !== indices) {
			// Indices past the vertex count would read outside the buffers; such a mesh draws nothing.
			val vertexCount = minOf(world.size, uvs.size) / 2
			val valid = indices.size % 3 == 0 && indices.all { it in 0 until vertexCount }
			GL30.glBindVertexArray(buffers.vao)
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, buffers.indices)
			GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, if (valid) indices else IntArray(0), GL15.GL_STATIC_DRAW)
			GL30.glBindVertexArray(0)
			buffers.indexCount = if (valid) indices.size else 0
			buffers.indexSource = indices
		}
		return buffers
	}

	private fun deleteMesh(buffers: MeshBuffers) {
		GL30.glDeleteVertexArrays(buffers.vao)
		GL15.glDeleteBuffers(buffers.positions)
		GL15.glDeleteBuffers(buffers.uvs)
		GL15.glDeleteBuffers(buffers.indices)
	}

	/** [image] as a premultiplied RGBA texture with linear filtering, uploaded once per page instance. */
	private fun texture(image: BufferedImage): Int = textures.getOrPut(image) {
		val width = image.width
		val height = image.height
		val raster = (image.raster.dataBuffer as? DataBufferInt)?.data?.takeIf { it.size == width * height }
		val premultipliedInput = raster != null && image.type == BufferedImage.TYPE_INT_ARGB_PRE
		val source = if (raster != null && (premultipliedInput || image.type == BufferedImage.TYPE_INT_ARGB)) raster
			else image.getRGB(0, 0, width, height, null, 0, width)
		val pixels = MemoryUtil.memAlloc(width * height * 4)
		try {
			for (i in 0 until width * height) {
				val c = source[i]
				val a = c ushr 24
				if (premultipliedInput || a == 255) {
					pixels.put((c ushr 16 and 0xff).toByte()).put((c ushr 8 and 0xff).toByte()).put((c and 0xff).toByte()).put(a.toByte())
				} else {
					// Rounded like Skia's own premultiply, so the two painters sample the same texels.
					pixels.put((((c ushr 16 and 0xff) * a + 127) / 255).toByte()).put((((c ushr 8 and 0xff) * a + 127) / 255).toByte())
						.put((((c and 0xff) * a + 127) / 255).toByte()).put(a.toByte())
				}
			}
			pixels.flip()
			val texture = GL11.glGenTextures()
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
			texture
		} finally {
			MemoryUtil.memFree(pixels)
		}
	}

	private fun ensureTarget(view: View, width: Int, height: Int) {
		if (view.framebuffer != 0 && width <= view.capacityWidth && height <= view.capacityHeight) return
		// Grow-only: a dock drag resizes every frame, and reallocating each time would stall on every step.
		val capacityWidth = maxOf(width, view.capacityWidth)
		val capacityHeight = maxOf(height, view.capacityHeight)
		if (view.framebuffer == 0) view.framebuffer = GL30.glGenFramebuffers()
		if (view.color != 0) GL30.glDeleteRenderbuffers(view.color)
		if (view.depthStencil != 0) GL30.glDeleteRenderbuffers(view.depthStencil)
		view.color = GL30.glGenRenderbuffers()
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, view.color)
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, capacityWidth, capacityHeight)
		view.depthStencil = GL30.glGenRenderbuffers()
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, view.depthStencil)
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, capacityWidth, capacityHeight)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, view.framebuffer)
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, view.color)
		GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, view.depthStencil)
		val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
		check(status == GL30.GL_FRAMEBUFFER_COMPLETE) { "Canvas framebuffer incomplete: 0x${Integer.toHexString(status)}" }
		view.capacityWidth = capacityWidth
		view.capacityHeight = capacityHeight
	}

	private fun program(vertex: String, fragment: String): Program {
		fun compile(type: Int, source: String): Int {
			val shader = GL20.glCreateShader(type)
			GL20.glShaderSource(shader, source)
			GL20.glCompileShader(shader)
			check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) {
				"Shader compile failed: ${GL20.glGetShaderInfoLog(shader)}"
			}
			return shader
		}
		val vs = compile(GL20.GL_VERTEX_SHADER, vertex)
		val fs = compile(GL20.GL_FRAGMENT_SHADER, fragment)
		val id = GL20.glCreateProgram()
		GL20.glAttachShader(id, vs)
		GL20.glAttachShader(id, fs)
		GL20.glLinkProgram(id)
		GL20.glDeleteShader(vs)
		GL20.glDeleteShader(fs)
		check(GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) { "Program link failed: ${GL20.glGetProgramInfoLog(id)}" }
		return Program(id)
	}

}
