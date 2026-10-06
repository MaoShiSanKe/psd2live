package io.github.psd2live.agent

import org.junit.jupiter.api.Tag
import io.github.psd2live.ui.state.DesktopWorkspace

import io.github.psd2live.project.MutationAuthor

import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

@Tag("slow")
class LayerClassificationIntegrationTest {
    @TempDir lateinit var temp: Path

    @Test fun classificationRebuildsRigAndCanBeRestored() = runBlocking {
        val png = temp.resolve("art.png")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xffff3366.toInt())
        ImageIO.write(image, "png", png.toFile())

        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 16); put("height", 16)
                    putJsonArray("layers") { add(buildJsonObject {
                        put("path", png.toString()); put("name", "decoration"); put("role", "objects")
                    }) }
                })
                val id = created.affectedLayerIds.single()
                val painted = workspace.paintSource(buildJsonObject {
                    put("state", created.state!!); put("layer_id", id); put("mode", "brush")
                    putJsonArray("points") { add(buildJsonArray { add(0); add(0) }) }
                    putJsonArray("color") { listOf(0, 255, 0, 255).forEach { add(it) } }
                    put("radius", 1.0)
                })
                assertTrue(painted.applied)
                assertEquals(0f, workspace.snapshot().layers.single().opaqueBounds.left)
                val paintedPixel = viewModel.state.value.analysis!!.source.layers.single().raster.rgba
                assertEquals(0, paintedPixel[0].toInt() and 255)
                assertEquals(255, paintedPixel[1].toInt() and 255)
                assertTrue((paintedPixel[3].toInt() and 255) > 0)
                workspace.checkoutHistory(created.historyNodeId, MutationAuthor.AGENT)
                assertEquals(2f, workspace.snapshot().layers.single().opaqueBounds.left)
                val connection = java.lang.reflect.Proxy.newProxyInstance(
                    ClientConnection::class.java.classLoader, arrayOf(ClientConnection::class.java),
                ) { _, method, _ -> if (method.name == "getSessionId") "test" else error("Unexpected MCP client call") } as ClientConnection
                val server = createAgentMcpServer(workspace)
                val layerTool = server.tools.getValue("layer_classify")
                val classified = layerTool.handler.invoke(connection,
                    CallToolRequest(CallToolRequestParams("layer_classify", buildJsonObject { putJsonObject("request") {
                        put("project_id", workspace.snapshot().projectId); put("request_id", "classify"); put("state", workspace.snapshot().state); put("layer_id", id)
                        put("type", "toggle"); put("parameter", "ParamDecoration")
                    } })))
                assertFalse(classified.isError == true)
                val classifiedJob = classified.structuredContent!!.getValue("data").jsonObject.getValue("id")
                val classification = server.tools.getValue("job_wait").handler.invoke(connection,
                    CallToolRequest(CallToolRequestParams("job_wait", buildJsonObject { putJsonObject("request") { put("id", classifiedJob) } })))
                    .structuredContent!!.getValue("data").jsonObject
                assertEquals("completed", classification.getValue("status").jsonPrimitive.content)
                val classifiedHead = classification.getValue("result").jsonObject.getValue("state").jsonPrimitive.content
                assertEquals("toggle", workspace.snapshot().layers.single().classificationType)
                assertEquals("ParamDecoration", workspace.snapshot().layers.single().parameterBinding)
                assertTrue(workspace.snapshot().parameters.any { it.id == "ParamDecoration" })
                val rigBeforePaint = workspace.currentPuppet()!!
                val paintTool = server.tools.getValue("source_paint_pencil")
                val paintRequest = CallToolRequest(CallToolRequestParams("source_paint_pencil", buildJsonObject { putJsonObject("request") {
                    put("project_id", workspace.snapshot().projectId); put("request_id", "paint-bound-layer"); put("state", classifiedHead)
                    put("layer_id", id); put("radius", 1); put("rebuild_mesh", false)
                    put("points", buildJsonArray { add(buildJsonArray { add(4); add(4) }) })
                    put("color", buildJsonArray { add(20); add(100); add(230); add(255) })
                } }))
                val boundPaint = paintTool.handler.invoke(connection, paintRequest)
                assertFalse(boundPaint.isError == true)
                val paintJob = boundPaint.structuredContent!!.getValue("data").jsonObject.getValue("id")
                val paintCompleted = server.tools.getValue("job_wait").handler.invoke(connection,
                    CallToolRequest(CallToolRequestParams("job_wait", buildJsonObject { putJsonObject("request") { put("id", paintJob) } })))
                val paintTerminal = paintCompleted.structuredContent!!.getValue("data").jsonObject
                assertEquals("completed", paintTerminal.getValue("status").jsonPrimitive.content)
                val paintedHead = paintTerminal.getValue("result").jsonObject.getValue("state").jsonPrimitive.content
                assertNotEquals(classifiedHead, paintedHead)
                val rigAfterPaint = workspace.currentPuppet()!!
                assertEquals(rigBeforePaint.parameters, rigAfterPaint.parameters)
                rigBeforePaint.drawables.forEach { before ->
                    val after = rigAfterPaint.drawables.single { it.id == before.id }
                    assertContentEquals(before.mesh!!.positions, after.mesh!!.positions)
                }
                val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
                for (value in listOf(0f, 0.5f, 1f)) {
                    val pose = mapOf(org.umamo.runtime.model.ParameterId("ParamDecoration") to value)
                    val before = evaluator.evaluate(rigBeforePaint, pose)
                    val after = evaluator.evaluate(rigAfterPaint, pose)
                    before.worldPositions.forEach { (drawable, vertices) -> assertContentEquals(vertices, after.worldPositions.getValue(drawable)) }
                    assertEquals(before.opacity, after.opacity)
                }
                val historyCount = workspace.history().nodes.size
                assertEquals(boundPaint.structuredContent, paintTool.handler.invoke(connection, paintRequest).structuredContent)
                assertEquals(historyCount, workspace.history().nodes.size)
                val session = workspace.setPreviewSession(buildJsonObject {
                    put("state", paintedHead); put("mode", "set")
                    putJsonObject("values") { put("ParamDecoration", 1.0) }
                    putJsonObject("locks") { put("ParamDecoration", true) }
                })
                assertEquals(1f, session.getValue("values").jsonObject.getValue("ParamDecoration").jsonPrimitive.float)
                assertTrue(session.getValue("locked").jsonArray.any { it.jsonPrimitive.content == "ParamDecoration" })

                val restored = workspace.checkoutHistory(created.historyNodeId, MutationAuthor.AGENT)
                assertEquals("preset", workspace.snapshot().layers.single().classificationType)
                assertFalse(workspace.snapshot().parameters.any { it.id == "ParamDecoration" })
                assertNotEquals(classifiedHead, restored.historyNodeId)

                val configured = workspace.updateProjectSettings(restored.state!!, buildJsonObject {
                    put("headStrength", 2.0); put("atlasSize", 512)
                })
                assertEquals(2.0f, workspace.projectSettings().getValue("headStrength").jsonPrimitive.float)
                // Body motion values merge one by one over the rest, and out-of-range ones are refused.
                val tuned = workspace.updateProjectSettings(configured.state!!, buildJsonObject {
                    putJsonObject("rigTuning") { put("armSwingDegrees", 6.0); put("turnDegrees", 8.0) }
                })
                assertEquals(io.github.psd2live.core.RigTuning(armSwingDegrees = 6f, turnDegrees = 8f), viewModel.state.value.rigTuning)
                val retuned = workspace.updateProjectSettings(tuned.state!!, buildJsonObject {
                    putJsonObject("rigTuning") { put("sink", 5.0) }
                })
                assertEquals(io.github.psd2live.core.RigTuning(armSwingDegrees = 6f, turnDegrees = 8f, sink = 5f), viewModel.state.value.rigTuning)
                assertEquals(6f, workspace.projectSettings().getValue("rigTuning").jsonObject.getValue("armSwingDegrees").jsonPrimitive.float)
                assertTrue(runCatching { workspace.updateProjectSettings(retuned.state!!, buildJsonObject {
                    putJsonObject("rigTuning") { put("turnDegrees", 90.0) }
                }) }.isFailure)
                assertTrue(runCatching { workspace.updateProjectSettings(retuned.state!!, buildJsonObject {
                    putJsonObject("rigTuning") { put("noSuchValue", 1.0) }
                }) }.isFailure)
                val meshed = workspace.setLayerMeshSettings(retuned.state!!, id, buildJsonObject {
                    put("outerMargin", 3.0); put("edgeMode", "DOUBLE")
                }, reset = false)
                assertEquals(3f, viewModel.state.value.meshOverrides.getValue(id).outerMargin)
                val output = workspace.exportModel(meshed.state!!, temp.resolve("export").toString())
                assertTrue(output.getValue("files").jsonArray.isNotEmpty())
                assertTrue(output.getValue("files").jsonArray.all { file ->
                    Files.isRegularFile(Path.of(file.jsonObject.getValue("path").jsonPrimitive.content))
                })
                val psd = temp.resolve("roundtrip.psd")
                val psdResult = workspace.exportPsd(meshed.state!!, psd.toString(), 1, true)
                assertEquals(Files.size(psd).toInt(), psdResult.getValue("bytes").jsonPrimitive.int)
                PSD2LiveViewModel().use { importedViewModel ->
                    DesktopWorkspace(importedViewModel, temp.resolve("import-store")).use { importedWorkspace ->
                        importedViewModel.attachWorkspace(importedWorkspace)
                        val imported = importedWorkspace.importPsd(psd.toString())
                        assertTrue(imported.affectedLayerIds.isNotEmpty())
                        assertEquals("roundtrip.psd", importedWorkspace.snapshot().inputName)
                        val split = importedWorkspace.splitArtwork(buildJsonObject {
                            put("state", imported.state!!); put("layer_id", imported.affectedLayerIds.first())
                            putJsonArray("polygon") {
                                listOf(0 to 0, 8 to 0, 8 to 16, 0 to 16).forEach { (x, y) ->
                                    add(buildJsonArray { add(x); add(y) })
                                }
                            }
                            putJsonArray("names") { add("left"); add("right") }
                        }, MutationAuthor.USER)
                        assertEquals(2, split.affectedLayerIds.size)
                        assertEquals("user", importedWorkspace.history().nodes.first { it.id == split.historyNodeId }.actor)
                        val clear = importedWorkspace.paintSource(buildJsonObject {
                            put("state", split.state!!)
                            put("layer_id", split.affectedLayerIds.first())
                            put("mode", "clear")
                        })
                        assertTrue(clear.applied)
                        assertFalse(importedWorkspace.snapshot().layers.first { it.id == split.affectedLayerIds.first() }.deleted)
                        assertEquals(listOf(0, 0, 0, 0), importedWorkspace.sampleSourceColor(split.affectedLayerIds.first(), 4, 4))
                        importedWorkspace.checkoutHistory(split.historyNodeId, MutationAuthor.USER)
                        assertFalse(importedWorkspace.snapshot().layers.first { it.id == split.affectedLayerIds.first() }.deleted)
                    }
                }
            }
        }
    }
}
