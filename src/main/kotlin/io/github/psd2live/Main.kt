package io.github.psd2live

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.github.psd2live.agent.AgentMcpService
import io.github.psd2live.project.WorkspaceStore
import io.github.psd2live.ui.state.DesktopWorkspace
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.core.ProgressListener
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.views.PSD2LiveApp
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JOptionPane
import kotlin.io.path.absolutePathString
import kotlin.system.exitProcess

import androidx.compose.ui.window.rememberWindowState

fun main(arguments: Array<String>) {
	System.setProperty("sun.java2d.uiScale.enabled", "true")
	configureLanguage(arguments)
	if (arguments.isEmpty()) {
		runGui()
		return
	}
	if (arguments.any { it == "--help" || it == "-h" }) {
		printUsage()
		return
	}
	val options = CliOptions.parse(arguments)
	val config = PipelineConfig(
		atlasSize = options.int("--atlas", 4096),
        textureUpscale = io.github.psd2live.core.TextureUpscaleConfig(
            scale = options.value("--upscale")?.toInt() ?: 1,
            python = options.value("--upscale-python") ?: "python",
            nunifDirectory = options.value("--nunif-dir") ?: "",
            modelDirectory = options.value("--upscale-model") ?: "",
            tileSize = options.value("--upscale-tile")?.toInt() ?: 256,
            noiseLevel = options.value("--upscale-noise")?.toInt() ?: 1,
            neuralAlpha = !options.flags.contains("--no-upscale-neural-alpha"),
        ),
		meshSpacing = options.int("--mesh-spacing", 64),
		headTurnStrength = options.float("--head-strength", 1f),
		bodyStrength = options.float("--body-strength", 1f),
		meshOnly = options.flags.contains("--mesh-only"),
		generateDeformers = !options.flags.contains("--no-deformers"),
		exportMotions = !options.flags.contains("--no-motions"),
		generatePhysics = !options.flags.contains("--no-physics"),
		exportCmo3 = !options.flags.contains("--no-cmo3"),
		exportMoc3 = !options.flags.contains("--no-moc3"),
		exportJson = !options.flags.contains("--no-json"),
	)
	require(config.exportCmo3 || config.exportMoc3 || config.exportJson) { tr("cli.exportFormatRequired") }
	val input = Path.of(options.required("--input"))
	val output = Path.of(options.value("--output") ?: input.toAbsolutePath().parent.resolve("psd2live-output").toString())
	println(tr("cli.start", input.toAbsolutePath(), output.toAbsolutePath()))
	val result = PSD2LivePipeline().run(input, output, config, ProgressListener { stage, fraction ->
		println("%3d%%  %s".format((fraction * 100).toInt(), stage))
	})
	println(tr("cli.complete", result.exportedFiles.size))
	result.exportedFiles.forEach { println("  ${it.path.absolutePathString()} (${it.bytes} bytes)") }
	result.warnings.forEach { System.err.println(tr("cli.warning", it)) }
}

private fun runGui() {
	val instanceLock = try {
		AppInstanceLock.acquire(WorkspaceStore.defaultRoot()) ?: run {
			// Held by a running editor; leave it alone rather than share its store and MCP port.
			System.err.println(tr("app.alreadyRunning"))
			runCatching { JOptionPane.showMessageDialog(null, tr("app.alreadyRunning"), tr("app.title"), JOptionPane.WARNING_MESSAGE) }
			exitProcess(1)
		}
	} catch (failure: IOException) {
		System.err.println("Instance lock unavailable, continuing without it: ${failure.message}")
		null
	}
	val viewModel = PSD2LiveViewModel()
	val agentWorkspace = DesktopWorkspace(viewModel)
	viewModel.attachWorkspace(agentWorkspace)
	var agentMcpService: AgentMcpService? = null
	val agentMcpStartup = runCatching {
		AgentMcpService(agentWorkspace)
			.also { agentMcpService = it }
			.start()
	}

	// Both close() calls are idempotent, so the hook (Ctrl+C, SIGTERM, logoff) and the normal exit can race.
	val shutdown = {
		runCatching { agentMcpService?.close() }
		runCatching { viewModel.close() }
		Unit
	}
	val watchdog = ExitWatchdog()
	val shutdownHook = Thread({
		watchdog.arm()
		shutdown()
	}, "psd2live-shutdown-hook")
	Runtime.getRuntime().addShutdownHook(shutdownHook)

	var status = 0
	try {
		// Exit from main rather than letting Compose call System.exit on the EDT, which would block
		// the EDT while the shutdown hooks run.
		application(exitProcessOnExit = false) {
			val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
			val closeApp: () -> Unit = {
				viewModel.withSavedChanges {
					watchdog.arm()
					exitApplication()
				}
			}
			val transparent by remember {
				derivedStateOf { viewModel.uiState.value.canvasBackground.windowTransparent }
			}
			// A window's transparency cannot change once it is shown, so switching the canvas
			// background to or from transparent replaces the window; windowState keeps its bounds.
			key(transparent) {
				Window(
					onCloseRequest = closeApp,
					title = tr("app.title"),
					icon = painterResource("icons/psd2live.png"),
					state = windowState,
					undecorated = true,
					transparent = transparent,
				) {
					PSD2LiveApp(
						viewModel = viewModel,
						window = window,
						windowState = windowState,
						onCloseRequest = closeApp,
						agentConnectionInfo = agentMcpStartup.getOrNull(),
						agentStartupError = agentMcpStartup.exceptionOrNull()?.message,
					)
				}
			}
		}
	} catch (failure: Throwable) {
		failure.printStackTrace()
		status = 1
	}
	watchdog.arm()
	shutdown()
	runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
	instanceLock?.close()
	// AWT, Skiko and pooled threads would otherwise keep the JVM alive.
	exitProcess(status)
}

/**
 * Halts the JVM when an exit stalls, since a hung hook or window would otherwise keep the process,
 * and with it the instance lock, alive until it is killed by hand. Built at startup, so arming it
 * loads nothing.
 */
private class ExitWatchdog {
	private val thread = Thread({
		try {
			Thread.sleep(EXIT_TIMEOUT_MILLIS)
		} catch (_: InterruptedException) {
		}
		System.err.println("PSD2Live did not exit within ${EXIT_TIMEOUT_MILLIS / 1000} s; halting.")
		Runtime.getRuntime().halt(1)
	}, "psd2live-exit-watchdog").apply { isDaemon = true }
	private val armed = AtomicBoolean(false)

	fun arm() {
		if (armed.compareAndSet(false, true)) thread.start()
	}

	private companion object {
		// Above the longest clean exit: MCP stop, the recovery-write flush and the Cubism shutdown.
		const val EXIT_TIMEOUT_MILLIS = 15_000L
	}
}

private fun configureLanguage(arguments: Array<String>) {
	val index = arguments.indexOf("--lang")
	if (index < 0) return
	require(index + 1 < arguments.size) { tr("cli.missingValue", "--lang") }
	val raw = arguments[index + 1]
	val language = AppLanguage.fromTag(raw) ?: error(tr("cli.invalidLanguage", raw))
	I18n.setLanguage(language)
}

private fun printUsage() {
    println(tr("cli.usage"))
    println("""
        Texture upscale (optional local nunif):
          --upscale <1|2|4>          Default 1 (off)
          --upscale-python <path>    Python executable with nunif dependencies
          --nunif-dir <path>         nunif source checkout
          --upscale-model <path>     Explicit Art weights directory
          --upscale-tile <64..512>   Input tile size; default 256, batch 1, no TTA
          --upscale-noise <-1..3>    Denoise/sharpen level: -1 (none), 0 (clean art/sharp), 1 (medium, default), 2 (high), 3 (max)
          --no-upscale-neural-alpha  Disable neural alpha (use bilinear fallback)
    """.trimIndent())
}

private data class CliOptions(val values: Map<String, String>, val flags: Set<String>) {
	fun value(name: String): String? = values[name]
	fun required(name: String): String = value(name) ?: error(tr("cli.missingRequired", name))
	fun int(name: String, default: Int): Int = value(name)?.toIntOrNull() ?: default
	fun float(name: String, default: Float): Float = value(name)?.toFloatOrNull() ?: default

	companion object {
		private val flagNames = setOf("--no-upscale-neural-alpha", "--upscale-neural-alpha", "--no-physics", "--no-cmo3", "--no-moc3", "--mesh-only", "--no-deformers", "--no-motions", "--no-json")
		private val valueNames = setOf("--upscale", "--upscale-noise", "--upscale-python", "--nunif-dir", "--upscale-model", "--upscale-tile", "--input", "--output", "--lang", "--atlas", "--mesh-spacing", "--head-strength", "--body-strength")
		fun parse(arguments: Array<String>): CliOptions {
			val values = linkedMapOf<String, String>()
			val flags = linkedSetOf<String>()
			var index = 0
			while (index < arguments.size) {
				val name = arguments[index]
				require(name in flagNames || name in valueNames) { tr("cli.unknownOption", name) }
				if (name in flagNames) {
					flags += name
					index++
				} else {
					require(index + 1 < arguments.size) { tr("cli.missingValue", name) }
					values[name] = arguments[index + 1]
					index += 2
				}
			}
			return CliOptions(values, flags)
		}
	}
}
