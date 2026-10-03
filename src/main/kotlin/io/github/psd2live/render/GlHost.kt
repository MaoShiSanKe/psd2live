package io.github.psd2live.render

import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One OpenGL 3.3 core context on a thread of its own, behind a hidden GLFW window that is never shown:
 * every draw goes to framebuffer objects and is read back.
 *
 * Every GL call of the canvas renderer runs on [thread] through [execute] or [submit]; nothing else touches
 * the context, so no call needs to know which thread it is on. [start] answers null when no such context
 * can be had (no display, an old driver, macOS, where GLFW insists on the main thread), and the canvas then
 * paints in software.
 */
class GlHost private constructor(private val executor: ExecutorService, val description: String) : AutoCloseable {
	@Volatile private var closed = false

	/** Queues [task] on the GL thread; false once the host is closed. */
	fun execute(task: () -> Unit): Boolean {
		if (closed) return false
		return try {
			executor.execute(task)
			true
		} catch (_: RejectedExecutionException) {
			false
		}
	}

	/** Runs [task] on the GL thread and completes with its result. */
	fun <T> submit(task: () -> T): CompletableFuture<T> {
		val future = CompletableFuture<T>()
		if (!execute { runCatching(task).fold(future::complete, future::completeExceptionally) }) {
			future.completeExceptionally(IllegalStateException("GL host closed"))
		}
		return future
	}

	override fun close() {
		if (closed) return
		closed = true
		runCatching {
			executor.execute {
				val window = GLFW.glfwGetCurrentContext()
				GL.setCapabilities(null)
				if (window != MemoryUtil.NULL) GLFW.glfwDestroyWindow(window)
				GLFW.glfwTerminate()
			}
		}
		executor.shutdown()
		runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
	}

	companion object {
		/** Why the last [start] failed, for the log; null after a success. */
		@Volatile var failure: String? = null
			private set

		/** A host with a current 3.3 core context, or null with [failure] saying why not. */
		fun start(): GlHost? {
			if (System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) {
				failure = "GLFW windows must be created on the main thread on macOS"
				return null
			}
			val executor = Executors.newSingleThreadExecutor { task ->
				Thread(task, "psd2live-canvas-gl").apply { isDaemon = true }
			}
			val started = CompletableFuture<String>()
			executor.execute {
				val errors = StringBuilder()
				val callback = GLFWErrorCallback.create { code, message ->
					errors.append("GLFW ").append(code).append(": ").append(GLFWErrorCallback.getDescription(message)).append('\n')
				}
				GLFW.glfwSetErrorCallback(callback)
				try {
					check(GLFW.glfwInit()) { "glfwInit failed. $errors" }
					GLFW.glfwDefaultWindowHints()
					GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
					GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE)
					GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
					GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
					GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
					GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE)
					val window = GLFW.glfwCreateWindow(16, 16, "psd2live-canvas", MemoryUtil.NULL, MemoryUtil.NULL)
					if (window == MemoryUtil.NULL) {
						GLFW.glfwTerminate()
						error("No OpenGL 3.3 core context. $errors")
					}
					GLFW.glfwMakeContextCurrent(window)
					val capabilities = GL.createCapabilities()
					if (!capabilities.OpenGL33) {
						GL.setCapabilities(null)
						GLFW.glfwDestroyWindow(window)
						GLFW.glfwTerminate()
						error("OpenGL 3.3 is not supported")
					}
					started.complete("${GL11.glGetString(GL11.GL_RENDERER)} | ${GL11.glGetString(GL11.GL_VERSION)}")
				} catch (failure: Throwable) {
					started.completeExceptionally(failure)
				}
			}
			return try {
				val description = started.get(10, TimeUnit.SECONDS)
				failure = null
				GlHost(executor, description)
			} catch (thrown: Throwable) {
				failure = (thrown.cause ?: thrown).message ?: thrown.javaClass.simpleName
				executor.shutdownNow()
				null
			}
		}
	}
}
