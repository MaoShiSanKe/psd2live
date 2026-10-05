package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

/** Synthetic artwork only; native checks run when the developer's optional Windows runtime is present. */
@EnabledOnOs(OS.WINDOWS)
class WorkspaceMotionObservationIntegrationTest {
    @TempDir lateinit var temporary: Path
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private fun requireNative() {
        assumeTrue(Files.isRegularFile(Path.of("src/main/resources/cubism/windows-x86_64/live2d_renderer.dll")) ||
            Files.isRegularFile(Path.of("cubism/windows-x86_64/live2d_renderer.dll")) ||
            listOf(System.getProperty("psd2live.cubism.path"), System.getenv("CUBISM_SDK_PATH"), System.getenv("LIVE2D_SDK_PATH")).any { !it.isNullOrBlank() },
            "Optional Cubism runtime is not installed")
    }
    private fun business() = buildJsonObject {
        putJsonArray("rect") { add(-64); add(-32); add(256); add(256) }; put("target_long_edge", 512)
        putJsonArray("frames") {
            add(buildJsonObject { put("time", 0); putJsonObject("parameters") { put("Drive", -30) } })
            add(buildJsonObject { put("time", 0.2); putJsonObject("parameters") { put("Drive", 30) } })
            add(buildJsonObject { put("time", 0.4); putJsonObject("parameters") { put("Drive", -30) } })
        }
        putJsonArray("samples") { add(0); add(0.1); add(0.3) }; put("fps", 60)
    }

    @Test fun desktopMotionJobUsesExportedNativeMotionAndReopenKeepsTheRenderedSheet() = runBlocking<Unit> {
        requireNative()
        val image = BufferedImage(12, 72, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, 0xff506e8c.toInt())
        val png = temporary.resolve("strip.png"); ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(meshOnly = true, meshSpacing = 8, atlasSize = 256, generatePhysics = false, exportMoc3 = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                workspace.createArtwork(buildJsonObject {
                    put("width", 64); put("height", 96)
                    putJsonArray("layers") { add(buildJsonObject { put("path", png.toString()); put("name", "Strip"); put("role", "objects") }) }
                })
                WorkspaceOperations(workspace).use { operations ->
                    val root = workspace.snapshot()
                    val setup = operations.registry.invoke("workspace_apply_edits", buildJsonObject {
                        put("request_id", "drive"); put("project_id", root.projectId); put("state", root.state)
                        put("edits", JsonArray(simulationDrivingEdits(workspace.currentPuppet()!!).map {
                            buildJsonObject { put("operation", it.operation); put("request", it.request) }
                        }))
                    }, agent).data
                    assertEquals("completed", operations.registry.invoke("job_wait", buildJsonObject { put("id", setup.getValue("id")) }, agent).data.getValue("status").jsonPrimitive.content)
                    suspend fun sample(id: String): ByteArray {
                        val captured = workspace.snapshot(); val history = workspace.history()
                        val input = JsonObject(business() + buildJsonObject { put("request_id", id); put("project_id", captured.projectId); put("state", captured.state) })
                        val job = operations.registry.invoke("view_sample_motion", input, agent).data
                        val completed = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent)
                        assertEquals("completed", completed.data.getValue("status").jsonPrimitive.content, completed.data.toString())
                        val result = completed.data.getValue("result").jsonObject
                        validateOperationSchema(result, operations.registry.definition("view_sample_motion").jobResultSchema!!)
                        assertEquals(captured.state, result.getValue("state").jsonPrimitive.content)
                        val range = result.getValue("ranges").jsonObject.getValue("Drive").jsonArray.map { it.jsonPrimitive.float }
                        assertTrue(range[1] - range[0] > 15, "Native motion did not evaluate the authored drive: $range")
                        assertEquals(3, result.getValue("tiles").jsonArray.size)
                        assertEquals(captured.state, workspace.snapshot().state); assertEquals(history, workspace.history())
                        assertEquals(job.getValue("id"), operations.registry.invoke("view_sample_motion", input, agent).data.getValue("id"))
                        val recovered = operations.registry.invoke("job_get", buildJsonObject { put("id", job.getValue("id")) }, agent)
                        assertContentEquals(completed.images.single(), recovered.images.single())
                        return completed.images.single()
                    }
                    val before = sample("before-reopen")
                    val controller = ProjectController(vm); val archive = temporary.resolve("motion.psd2live")
                    controller.save(workspace, archive); controller.open(workspace, archive)
                    val after = sample("after-reopen")
                    assertContentEquals(before, after)
                    val visual = Files.createDirectories(Path.of("build/motion-observation-visual"))
                    Files.write(visual.resolve("before-reopen.png"), before); Files.write(visual.resolve("after-reopen.png"), after)
                }
            }
        }
    }

    @Test fun nativeFrameCancellationStopsTheLoopCleansItsFilesAndLeavesTheSessionUsable() = runBlocking {
        requireNative()
        val builder = WorkspacePreviewBuilder(); val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Drive", simulationDrivingEdits(root), MutationAuthor.USER)
        val preview = runtime.capture().model
        val bundle = observationMotion(preview.runtimeBundle, business().getValue("frames").jsonArray,
            preview.rig.puppet.parameters.associate { it.id.raw to it.default })
        val parameters = preview.rig.puppet.parameters.map { it.id }
        fun directories() = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
            paths.filter { it.fileName.toString().startsWith("psd2live-motion-sample-") }.toList().toSet()
        }
        val before = directories(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val frames = AtomicInteger(); val queuedCalls = AtomicInteger()
        CubismSdkPreviewSession({}, {}).use { session ->
            val sampling = async(Dispatchers.Default) {
                session.sampleMotionAwait(bundle, parameters, "AgentObservation", 25, 60, { fraction ->
                    if (fraction > 0) frames.incrementAndGet()
                    if (fraction >= 0.25f && !entered.isCompleted) { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
                }, { false })
            }
            try {
                withTimeout(10000) { entered.await() }
                val staged = directories() - before; assertEquals(1, staged.size)
                val queued = session.sampleMotion(bundle, parameters, "AgentObservation", 25, 60, { queuedCalls.incrementAndGet() })
                assertTrue(queued.cancel(false))
                sampling.cancelAndJoin(); release.countDown()
                val recovered = session.sampleMotionAwait(bundle, parameters, "AgentObservation", 25, 60, {}, { false })
                assertEquals(25, recovered.size); assertTrue(recovered.all { it.keys == parameters.toSet() })
                assertEquals(7, frames.get()); assertEquals(0, queuedCalls.get())
                assertTrue(staged.none(Files::exists), "Cancelled sampling left temporary assets")
                assertEquals(before, directories())
            } finally { release.countDown(); sampling.cancel() }
        }
    }
}
