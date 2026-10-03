package io.github.psd2live.render

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The editing canvases' GPU renderer, shared by every canvas of the application.
 *
 * Each canvas hands it scenes through [submit] and watches [frames]. Scenes go into a one-slot mailbox per
 * canvas: the GL thread always draws the newest and drops any it never got to, so a fast drag cannot queue up
 * frames behind it. A canvas with no new scene costs nothing.
 *
 * The context starts in the background on first use; until [status] is [Status.Ready] the canvas paints in
 * software, and it goes on doing so for good when the context cannot be had or a frame fails.
 */
internal object CanvasRenderService {
	sealed interface Status {
		data object Starting : Status
		data class Ready(val description: String) : Status
		data class Unavailable(val reason: String) : Status
	}

	private val _status = MutableStateFlow<Status>(Status.Starting)
	val status: StateFlow<Status> = _status.asStateFlow()

	private val started = AtomicBoolean(false)
	@Volatile private var host: GlHost? = null
	@Volatile private var renderer: GlCanvasRenderer? = null
	private val pending = ConcurrentHashMap<String, CanvasScene>()
	private val frameFlows = ConcurrentHashMap<String, MutableStateFlow<RenderedFrame?>>()
	private val drainScheduled = AtomicBoolean(false)
	/**
	 * Each canvas's recent frame bitmaps, oldest first. A bitmap's pixels are native memory the garbage collector
	 * does not see, so a drag would pile up hundreds of megabytes before it ran; each is closed once
	 * [RETAINED_FRAMES] newer ones have replaced it, by which time no drawn frame can still read it.
	 */
	private val retained = ConcurrentHashMap<String, ArrayDeque<org.jetbrains.skia.Bitmap>>()
	private const val RETAINED_FRAMES = 3

	/** Starts the GL context once, off the calling thread. */
	fun ensureStarted() {
		if (!started.compareAndSet(false, true)) return
		Thread({
			val started = runCatching { GlHost.start() }.getOrNull()
			if (started == null) {
				_status.value = Status.Unavailable(GlHost.failure ?: "No OpenGL context")
				return@Thread
			}
			val created = runCatching { started.submit { GlCanvasRenderer() }.get() }
			created.onSuccess {
				host = started
				renderer = it
				_status.value = Status.Ready(started.description)
			}.onFailure { failure ->
				started.close()
				_status.value = Status.Unavailable(failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName)
			}
		}, "psd2live-canvas-gl-start").apply { isDaemon = true }.start()
	}

	fun frames(viewId: String): StateFlow<RenderedFrame?> = frameFlows.getOrPut(viewId) { MutableStateFlow(null) }

	/** Queues [scene] for [viewId], replacing one not yet drawn. */
	fun submit(viewId: String, scene: CanvasScene) {
		if (status.value !is Status.Ready) return
		pending[viewId] = scene
		scheduleDrain()
	}

	/** Forgets [viewId] and frees its GPU objects. */
	fun release(viewId: String) {
		pending.remove(viewId)
		frameFlows.remove(viewId)
		// The last frames may still be on screen for a moment; the collector frees these few as it would anyway.
		retained.remove(viewId)
		val r = renderer ?: return
		host?.execute { runCatching { r.release(viewId) } }
	}

	private fun scheduleDrain() {
		val h = host ?: return
		if (!drainScheduled.compareAndSet(false, true)) return
		if (!h.execute(::drain)) drainScheduled.set(false)
	}

	private fun drain() {
		val r = renderer
		try {
			while (r != null && status.value is Status.Ready) {
				val viewId = pending.keys.firstOrNull() ?: break
				val scene = pending.remove(viewId) ?: continue
				val bitmap = r.render(viewId, scene)
				frameFlows.getOrPut(viewId) { MutableStateFlow(null) }.value =
					RenderedFrame(bitmap, scene.width, scene.height, scene.viewport, scene)
				val recent = retained.getOrPut(viewId) { ArrayDeque() }
				synchronized(recent) {
					recent.addLast(bitmap)
					while (recent.size > RETAINED_FRAMES) recent.removeFirst().close()
				}
			}
		} catch (failure: Throwable) {
			// A frame that cannot be drawn says nothing good about the next; the canvas goes back to software.
			System.err.println("Canvas GPU rendering failed, falling back to software: $failure")
			_status.value = Status.Unavailable(failure.message ?: failure.javaClass.simpleName)
			pending.clear()
		} finally {
			drainScheduled.set(false)
			if (pending.isNotEmpty() && status.value is Status.Ready) scheduleDrain()
		}
	}
}
