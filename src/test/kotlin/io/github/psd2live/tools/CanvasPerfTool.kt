package io.github.psd2live.tools

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.render.CanvasRenderService
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.views.PSD2LiveApp
import java.awt.Robot
import java.awt.event.InputEvent
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * Frame pacing of the real editor window on the edit canvas, GPU renderer against the software painter.
 *
 * PSD2LIVE_TOOLS=1 xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew test --tests '*CanvasPerfTool'
 * PSD2LIVE_SAMPLE picks the PSD (tml by default). Writes build/tools/canvas-perf/report.txt and a screenshot
 * after each phase. Under Xvfb the GPU is Mesa's llvmpipe, a CPU rasterizer, so absolute GPU numbers there
 * say little; the UI thread's share is what this measures.
 */
class CanvasPerfTool {
	private class Phase(val name: String, val seconds: Double, val input: (Robot, Double, Double, Double, Double) -> Unit)

	private var dragOrigin: androidx.compose.ui.geometry.Offset? = null
	private var pressNote = ""

	@Test fun profile() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("canvas-perf")
		val savedSoftware = AppSettings.softwareCanvas
		val viewModel = PSD2LiveViewModel()
		// As the app starts it: the history workspace is what makes the canvas editable.
		val workspace = io.github.psd2live.ui.state.DesktopWorkspace(viewModel, out.resolve("workspace").toPath())
		viewModel.attachWorkspace(workspace)
		val frames = ConcurrentLinkedQueue<Long>()
		val window = AtomicReference<java.awt.Window?>()
		Thread {
			application(exitProcessOnExit = false) {
				Window(onCloseRequest = ::exitApplication, state = rememberWindowState(size = DpSize(1600.dp, 1000.dp)), undecorated = true) {
					window.set(this.window)
					LaunchedEffect(Unit) { while (true) withFrameNanos { frames += it } }
					PSD2LiveApp(viewModel = viewModel, window = this.window)
				}
			}
		}.apply { isDaemon = true; start() }
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(100) }
		}
		val report = StringBuilder()
		try {
			waitFor(60, "window") { window.get()?.isShowing == true }
			viewModel.openRecentFile(sample.path.toAbsolutePath().toString())
			waitFor(600, "model") { viewModel.state.value.previewModel != null && !viewModel.state.value.isBusy }
			SwingUtilities.invokeAndWait {
				viewModel.dismissStartScreen()
				viewModel.setCanvasMode(viewModel.state.value.activeCanvas.id, CanvasMode.EDIT)
				// Every mesh wireframe (faded in object mode), every warp lattice and rotation: the heaviest guides the canvas draws.
				val state = viewModel.state.value
				viewModel.updateEditViewOptions(state.activeCanvas.id, state.activeWorkspace.id) { it.copy(showMesh = true, showWarp = true, showRotation = true) }
			}
			CanvasRenderService.ensureStarted()
			waitFor(30, "GPU renderer") { CanvasRenderService.status.value !is CanvasRenderService.Status.Starting }
			report.appendLine("GPU: ${CanvasRenderService.status.value}")
			Thread.sleep(2000)
			val bounds = window.get()!!.bounds
			val robot = Robot().apply { autoDelay = 0 }
			val cx = bounds.x + bounds.width * 0.45
			val cy = bounds.y + bounds.height * 0.5
			val radius = bounds.height * 0.15
			fun circle(r: Robot, t: Double, x: Double, y: Double) =
				r.mouseMove((x + cos(t * 3) * radius).toInt(), (y + sin(t * 3) * radius).toInt())
			val phases = listOf(
				Phase("idle", 3.0) { _, _, _, _, _ -> },
				Phase("hover", 4.0) { r, t, _, x, y -> circle(r, t, x, y) },
				Phase("zoom", 4.0) { r, t, dt, x, y ->
					r.mouseMove(x.toInt(), y.toInt())
					// In for half the phase, out for the other, a notch every 40 ms (a wheel step away from the user zooms in).
					if (((t * 25).toInt()) != (((t - dt) * 25).toInt())) r.mouseWheel(if (t < 2.0) 1 else -1)
				},
				Phase("pan", 4.0) { r, t, _, x, y ->
					if (t == 0.0) { r.mouseMove(x.toInt(), y.toInt()); r.mousePress(InputEvent.BUTTON2_DOWN_MASK) }
					circle(r, t, x, y)
				},
			)
			val canvasId = viewModel.state.value.activeCanvas.id
			val editor = viewModel.canvasEditorFor(canvasId)
			val rig = viewModel.state.value.previewModel!!.rig
			val face = rig.puppet.drawables.first { it.name.equals("face", ignoreCase = true) }.let { rig.layerIdByDrawableId.getValue(it.id.raw) }
			/** Drags every point of the face mesh in Deform mode in a circle, through the editor's own gesture. */
			val deformDrag = Phase("deform-drag", 4.0) { _, t, _, _, _ ->
				SwingUtilities.invokeAndWait {
					val viewport = editor.viewport ?: return@invokeAndWait
					val target = editor.target() ?: return@invokeAndWait
					val points = editor.screen(target.geometry.points, target, viewport)
					val cx = points.map { it.x }.average().toFloat()
					val cy = points.map { it.y }.average().toFloat()
					if (t == 0.0 || !editor.inGesture) {
						// A Deform press may first snap the pose to a key and press again itself; retry until it holds.
						val origin = dragOrigin.takeIf { t > 0.0 } ?: androidx.compose.ui.geometry.Offset(cx, cy)
						val handled = editor.press(origin, viewport, shift = false, alt = false)
						if (t == 0.0) pressNote = "press at $origin handled $handled, tool ${editor.tool}, editable ${editor.editable}, busy ${editor.busy}, " +
							"in gesture ${editor.inGesture}, snapping ${viewModel.isSnappingParameters}, error ${editor.error}, " +
							viewModel.state.value.let { "history ${it.historySnapshot != null}, canvasEditBusy ${it.canvasEditBusy}, generating ${it.isGenerating}, analyzing ${it.isAnalyzing}" }
						dragOrigin = origin
					} else {
						val origin = dragOrigin ?: return@invokeAndWait
						editor.move(origin + androidx.compose.ui.geometry.Offset((cos(t * 4) * 30).toFloat() - 30f, (sin(t * 4) * 30).toFloat()), viewport, shift = false)
					}
				}
			}
			// A snapshot with the head turned, to look at its ghost over the rest pose in each mode.
			SwingUtilities.invokeAndWait {
				viewModel.setParameterValue(io.github.psd2live.core.StandardParameters.ANGLE_X, 30f)
				viewModel.saveParameterSnapshot("turned")
				viewModel.setParameterValue(io.github.psd2live.core.StandardParameters.ANGLE_X, 0f)
			}
			/** A red brush stroke circling over the face, through the editor's own paint gesture. */
			val paintStroke = Phase("paint-stroke", 4.0) { _, t, _, _, _ ->
				SwingUtilities.invokeAndWait {
					val viewport = editor.viewport ?: return@invokeAndWait
					val origin = dragOrigin ?: return@invokeAndWait
					val pos = origin + androidx.compose.ui.geometry.Offset((cos(t * 5) * 40).toFloat(), (sin(t * 5) * 40).toFloat())
					if (t == 0.0) editor.press(pos, viewport, shift = false, alt = false) else editor.move(pos, viewport, shift = false)
				}
			}
			for (software in listOf(false, true)) {
				AppSettings.softwareCanvas = software
				Thread.sleep(1500)
				val mode = if (software) "software" else "gpu"
				val ghost = arrayOfNulls<Any>(1)
				SwingUtilities.invokeAndWait {
					viewModel.setCanvasView(1f, 0f, 0f, canvasId, CanvasMode.EDIT)
					ghost[0] = viewModel.previewParameterSnapshot(viewModel.state.value.parameterSnapshots.last().id)
				}
				Thread.sleep(1500)
				ImageIO.write(robot.createScreenCapture(bounds), "png", File(out, "$mode-snapshot-ghost.png"))
				SwingUtilities.invokeAndWait {
					(ghost[0] as? io.github.psd2live.ui.state.ParameterSnapshotPreview)?.let(viewModel::clearParameterSnapshotPreview)
				}
				Thread.sleep(500)
				for (phase in phases + deformDrag + paintStroke) {
					if (phase === paintStroke) {
						SwingUtilities.invokeAndWait {
							viewModel.setCanvasView(1f, 0f, 0f, canvasId, CanvasMode.EDIT)
							viewModel.selectLayer(face)
						}
						Thread.sleep(500)
						SwingUtilities.invokeAndWait {
							editor.setHierarchyMode(io.github.psd2live.ui.EditHierarchyMode.PAINT)
							editor.tool = io.github.psd2live.ui.CanvasTool.PAINT_BRUSH
							editor.paintColor = androidx.compose.ui.graphics.Color.Red
							editor.paintBrushSize = 24f
						}
						Thread.sleep(800)
						SwingUtilities.invokeAndWait {
							val viewport = editor.viewport
							val target = editor.target()
							if (viewport != null && target != null) {
								val points = editor.screen(target.geometry.points, target, viewport)
								dragOrigin = androidx.compose.ui.geometry.Offset(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
							}
						}
					}
					if (phase === deformDrag) {
						SwingUtilities.invokeAndWait {
							viewModel.setCanvasView(1f, 0f, 0f, canvasId, CanvasMode.EDIT)
							viewModel.selectLayer(face)
						}
						Thread.sleep(500)
						SwingUtilities.invokeAndWait { editor.setHierarchyMode(io.github.psd2live.ui.EditHierarchyMode.DEFORM) }
						Thread.sleep(500)
						SwingUtilities.invokeAndWait { editor.selectAll() }
						Thread.sleep(500)
						report.appendLine("deform drag: mode ${editor.hierarchyMode}, ${editor.vertices.size} points selected on ${editor.target()?.id}")
					}
					val pings = mutableListOf<Long>()
					val recording = jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile")).apply {
						enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(2)); start()
					}
					frames.clear()
					val start = System.nanoTime()
					var previous = 0.0
					var pingAt = start
					var first = true
					while (true) {
						// The first step is exactly 0, which is where phases press their buttons.
						val t = if (first) 0.0 else (System.nanoTime() - start) / 1e9
						first = false
						if (t > phase.seconds) break
						phase.input(robot, t, t - previous, cx, cy)
						previous = t
						if (System.nanoTime() >= pingAt) {
							val sent = System.nanoTime()
							SwingUtilities.invokeLater { synchronized(pings) { pings += System.nanoTime() - sent } }
							pingAt = sent + 5_000_000
						}
						Thread.sleep(8)
					}
					recording.stop()
					val jfr = File(out, "$mode-${phase.name}.jfr")
					recording.dump(jfr.toPath()); recording.close()
					if (phase.name == "pan") robot.mouseRelease(InputEvent.BUTTON2_DOWN_MASK)
					if (phase === deformDrag) {
						ImageIO.write(robot.createScreenCapture(bounds), "png", File(out, "$mode-deform-mid.png"))
						report.appendLine("deform drag: in gesture ${editor.inGesture}, previewing ${editor.preview != null}; $pressNote")
					}
					if (phase === paintStroke) {
						ImageIO.write(robot.createScreenCapture(bounds), "png", File(out, "$mode-paint-mid.png"))
						SwingUtilities.invokeAndWait {
							report.appendLine("paint stroke: session ${editor.paintSession != null}, gpu preview ${editor.paintSession?.gpuPreview}")
							editor.release()
							editor.discardPaintSession()
							editor.setHierarchyMode(io.github.psd2live.ui.EditHierarchyMode.SELECT)
						}
					}
					if (phase === deformDrag) SwingUtilities.invokeAndWait {
						editor.release()
						editor.cancel()
						editor.setHierarchyMode(io.github.psd2live.ui.EditHierarchyMode.SELECT)
					}
					Thread.sleep(300)
					ImageIO.write(robot.createScreenCapture(bounds), "png", File(out, "$mode-${phase.name}.png"))
					val stamps = frames.toList().sorted()
					val gaps = stamps.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
					val latency = synchronized(pings) { pings.map { it / 1e6 }.sorted() }
					fun List<Double>.pct(p: Double) = if (isEmpty()) 0.0 else this[((size - 1) * p).toInt()]
					report.appendLine("%-8s %-6s %5.1f fps | frame gap p50 %5.1f p95 %6.1f max %6.1f ms | UI latency p50 %5.1f p95 %6.1f max %6.1f ms".format(
						mode, phase.name, stamps.size / phase.seconds, gaps.pct(0.5), gaps.pct(0.95), gaps.lastOrNull() ?: 0.0,
						latency.pct(0.5), latency.pct(0.95), latency.lastOrNull() ?: 0.0))
					report.append(hotspots(jfr))
				}
			}
		} finally {
			AppSettings.softwareCanvas = savedSoftware
			File(out, "report.txt").writeText(report.toString())
			println(report)
			runCatching { SwingUtilities.invokeAndWait { window.get()?.dispose() } }
			workspace.close()
			viewModel.close()
		}
	}

	/** Where the UI thread spent the phase: busiest project frames (inclusive) and methods (self). */
	private fun hotspots(file: File): String {
		val self = HashMap<String, Int>()
		val inclusive = HashMap<String, Int>()
		var total = 0
		var all = 0
		jdk.jfr.consumer.RecordingFile(file.toPath()).use { events ->
			while (events.hasMoreEvents()) {
				val event = events.readEvent()
				if (event.eventType.name != "jdk.ExecutionSample") continue
				all++
				if (event.getThread("sampledThread")?.javaName?.startsWith("AWT-EventQueue") != true) continue
				total++
				val stack = event.stackTrace?.frames ?: continue
				stack.firstOrNull()?.let { f -> val k = "${f.method.type.name}.${f.method.name}"; self[k] = (self[k] ?: 0) + 1 }
				val seen = HashSet<String>()
				for (f in stack) {
					val type = f.method.type.name
					if (!type.startsWith("io.github.psd2live") && !type.startsWith("org.umamo")) continue
					val k = "${type.substringAfterLast('.')}.${f.method.name}"
					if (seen.add(k)) inclusive[k] = (inclusive[k] ?: 0) + 1
				}
			}
		}
		val text = StringBuilder("    UI thread samples $total of $all\n")
		inclusive.entries.sortedByDescending { it.value }.take(12).forEach { text.append("      ${it.value * 100 / maxOf(1, total)}% ${it.key}\n") }
		text.append("    self:\n")
		self.entries.sortedByDescending { it.value }.take(8).forEach { text.append("      ${it.value * 100 / maxOf(1, total)}% ${it.key}\n") }
		return text.toString()
	}
}
