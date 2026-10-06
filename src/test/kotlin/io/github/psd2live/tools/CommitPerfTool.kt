package io.github.psd2live.tools

import io.github.psd2live.application.WorkspaceDocumentCommands
import io.github.psd2live.application.WorkspacePreviewBuilder
import io.github.psd2live.application.WorkspaceRuntime
import io.github.psd2live.application.WorkspaceSourceImporter
import io.github.psd2live.core.GeometrySafetyEvaluator
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.RigPreviewModel
import io.github.psd2live.project.MutationAuthor
import io.github.psd2live.project.WorkspaceRevisions
import io.github.psd2live.project.config
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test

/**
 * Wall time of one authored commit through the application command boundary, split by phase.
 *
 * PSD2LIVE_TOOLS=1 ./gradlew test --tests '*CommitPerfTool'
 * PSD2LIVE_SAMPLE picks the PSD (tml by default). Writes build/tools/commit-perf/report.txt.
 */
class CommitPerfTool {
	private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000.0

	@Test fun profile() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("commit-perf")
		val builder = WorkspacePreviewBuilder()
		lateinit var runtime: WorkspaceRuntime<RigPreviewModel>
		runtime = WorkspaceRuntime({ document -> builder.build(document, runtime.capture().model) })
		val commands = WorkspaceDocumentCommands(runtime)
		val report = StringBuilder()
		runBlocking {
			val start = System.nanoTime()
			WorkspaceSourceImporter(runtime).importPsd(sample.path.toAbsolutePath(), null, runtime.state.value.state, initialConfig = PipelineConfig())
			report.appendLine("import: %.0f ms".format(ms(start)))
			val mesh = runtime.capture().model.rig.puppet.drawables.filter { it.mesh != null }.maxBy { it.mesh!!.positions.size }
			report.appendLine("drawables=${runtime.capture().model.rig.puppet.drawables.size} target=${mesh.id.raw} vertices=${mesh.mesh!!.positions.size / 2}")
			repeat(8) { round ->
				val before = runtime.capture()
				val current = before.model.rig.puppet.drawables.single { it.id == mesh.id }.mesh!!.positions.copyOf()
				current[0] += 0.5f; current[1] += 0.25f
				val edit = buildJsonObject {
					put("op", "canvas_geometry"); put("kind", "mesh"); put("id", mesh.id.raw)
					put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap())); put("preserve_image", true)
					put("points", JsonArray(current.map(::JsonPrimitive)))
				}
				var t = System.nanoTime()
				commands.executeJournal(before.projectId, before.state, "Stroke $round", JsonArray(listOf(edit)), MutationAuthor.USER)
				report.appendLine("commit $round: %.0f ms".format(ms(t)))
				val after = runtime.capture()
				t = System.nanoTime(); repeat(5) { WorkspaceRevisions.of(after.document) }
				report.appendLine("  revision x1: %.1f ms".format(ms(t) / 5))
				t = System.nanoTime(); after.document.config()
				report.appendLine("  config decode: %.1f ms".format(ms(t)))
				t = System.nanoTime(); builder.normalizeMeshEdits(after.document, before.model)
				report.appendLine("  normalizeMeshEdits: %.0f ms".format(ms(t)))
				t = System.nanoTime(); builder.build(after.document, before.model)
				report.appendLine("  build(fast?): %.0f ms".format(ms(t)))
				t = System.nanoTime(); GeometrySafetyEvaluator.evaluate(before.model.rig.puppet, after.model.rig.puppet, blockFoldovers = false)
				report.appendLine("  geometry safety: %.0f ms".format(ms(t)))
				report.appendLine("  journal size=${after.document.rigEdits.authoringJournal.size} chars=${after.document.rigEdits.toString().length}")
			}
		}
		out.resolve("report.txt").writeText(report.toString())
		println(report)
	}

	/** The editor's own path: view model, desktop adapter, projection and persistence, with EDT stalls. */
	@Test fun desktop() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val out = output("commit-perf")
		val viewModel = io.github.psd2live.ui.state.PSD2LiveViewModel()
		val workspace = io.github.psd2live.ui.state.DesktopWorkspace(viewModel, out.resolve("workspace").toPath())
		viewModel.attachWorkspace(workspace)
		fun waitFor(seconds: Int, what: String, condition: () -> Boolean) {
			val end = System.nanoTime() + seconds * 1_000_000_000L
			while (!condition()) { require(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(2) }
		}
		// The longest the Swing thread went without running a 2 ms tick.
		val maxGap = java.util.concurrent.atomic.AtomicLong()
		var last = System.nanoTime()
		javax.swing.SwingUtilities.invokeAndWait {
			javax.swing.Timer(2) {
				val now = System.nanoTime(); maxGap.accumulateAndGet(now - last, ::maxOf); last = now
			}.start()
		}
		val report = StringBuilder()
		viewModel.openRecentFile(sample.path.toAbsolutePath().toString())
		waitFor(600, "model") { viewModel.state.value.previewModel != null && !viewModel.state.value.isBusy && viewModel.currentWorkspaceState() != null }
		javax.swing.SwingUtilities.invokeAndWait { viewModel.dismissStartScreen() }
		Thread.sleep(1000)
		val mesh = viewModel.state.value.previewModel!!.rig.puppet.drawables.filter { it.mesh != null }.maxBy { it.mesh!!.positions.size }
		report.appendLine("target=${mesh.id.raw} vertices=${mesh.mesh!!.positions.size / 2}")
		repeat(10) { round ->
			val state = viewModel.currentWorkspaceState()!!
			val current = viewModel.state.value.previewModel!!.rig.puppet.drawables.single { it.id == mesh.id }.mesh!!.positions.copyOf()
			current[0] += 0.5f; current[1] += 0.25f
			val edit = buildJsonObject {
				put("op", "canvas_geometry"); put("kind", "mesh"); put("id", mesh.id.raw)
				put("key", JsonObject(emptyMap())); put("pose", JsonObject(emptyMap())); put("preserve_image", true)
				put("points", JsonArray(current.map(::JsonPrimitive)))
			}
			val done = java.util.concurrent.CountDownLatch(1)
			var failure: String? = null
			val t = System.nanoTime()
			maxGap.set(0)
			javax.swing.SwingUtilities.invokeLater { viewModel.saveAuthoringEdits(state, JsonArray(listOf(edit))) { failure = it; done.countDown() } }
			done.await()
			val committed = ms(t)
			waitFor(30, "idle") { !viewModel.state.value.workspaceEditBusy }
			report.appendLine("geometry $round: commit %.0f ms, idle %.0f ms, max EDT gap %.0f ms %s".format(committed, ms(t), maxGap.get() / 1e6, failure ?: ""))
			Thread.sleep(300)
		}
		val layer = viewModel.state.value.previewModel!!.rig.layerIdByDrawableId.getValue(mesh.id.raw)
		repeat(6) { round ->
			val session = viewModel.beginPaintSession(layer)!!
			session.beginStroke()
			session.segment(10f, 10f, 60f + round, 40f, io.github.psd2live.core.RasterPaintEngine.Tip(6f), 0xff3366aa.toInt(), 1f, false)
			session.recordStroke("Stroke")
			val done = java.util.concurrent.CountDownLatch(1)
			val t = System.nanoTime()
			maxGap.set(0)
			javax.swing.SwingUtilities.invokeLater { viewModel.savePaintSession(session, false, false, "Stroke") { done.countDown() } }
			done.await(60, java.util.concurrent.TimeUnit.SECONDS)
			val committed = ms(t)
			waitFor(30, "idle") { !viewModel.state.value.workspaceEditBusy }
			report.appendLine("paint $round: commit %.0f ms, idle %.0f ms, max EDT gap %.0f ms %s".format(committed, ms(t), maxGap.get() / 1e6, viewModel.state.value.errorMessage ?: ""))
			Thread.sleep(300)
		}
		out.resolve("desktop.txt").writeText(report.toString())
		println(report)
		workspace.close()
	}
}
