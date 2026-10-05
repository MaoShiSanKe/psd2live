package io.github.psd2live.agent

import io.github.psd2live.ui.state.DesktopWorkspace

import io.github.psd2live.project.MutationAuthor

import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class MissingMeshReplayIntegrationTest {
    @TempDir lateinit var temp: Path

    @Test fun paintingTransparentArtworkThroughMcpReturnsNewMeshHandleAndRetryKeepsOneCommit() = runBlocking<Unit> {
        val opaque = temp.resolve("opaque.png")
        val transparent = temp.resolve("transparent.png")
        val image = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..21) for (x in 2..21) image.setRGB(x, y, 0xff8899aa.toInt())
        ImageIO.write(image, "png", opaque.toFile())
        ImageIO.write(BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB), "png", transparent.toFile())
        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temp.resolve("creation-store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 48); put("height", 48); putJsonArray("layers") {
                        for ((path, name) in listOf(opaque to "Visible artwork", transparent to "Blank artwork")) add(buildJsonObject {
                            put("path", path.toString()); put("name", name); put("role", "objects")
                        })
                    }
                })
                val layerId = created.affectedLayerIds.last()
                val before = workspace.currentPuppet()!!
                assertEquals(1, before.drawables.size)
                val connection = java.lang.reflect.Proxy.newProxyInstance(
                    ClientConnection::class.java.classLoader, arrayOf(ClientConnection::class.java),
                ) { _, method, _ -> if (method.name == "getSessionId") "creation-test" else error("Unexpected MCP client call") } as ClientConnection
                val server = createAgentMcpServer(workspace)
                val tool = server.tools.getValue("source_paint_shape")
                val request = CallToolRequest(CallToolRequestParams("source_paint_shape", buildJsonObject { putJsonObject("request") {
                    put("project_id", created.projectId); put("state", created.state!!); put("request_id", "first-raster")
                    put("layer_id", layerId); put("shape", "rectangle"); put("filled", true)
                    put("from", buildJsonArray { add(26); add(26) }); put("to", buildJsonArray { add(44); add(44) })
                    put("color", buildJsonArray { add(30); add(120); add(220); add(255) })
                } }))
                val result = tool.handler.invoke(connection, request)
                assertFalse(result.isError == true)
                val paintJob = assertNotNull(result.structuredContent)["data"]!!.jsonObject.getValue("id")
                val completed = server.tools.getValue("job_wait").handler.invoke(connection,
                    CallToolRequest(CallToolRequestParams("job_wait", buildJsonObject { putJsonObject("request") { put("id", paintJob) } })))
                val terminal = assertNotNull(completed.structuredContent)["data"]!!.jsonObject
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
                val data = terminal.getValue("result").jsonObject
                val after = workspace.currentPuppet()!!
                val mesh = after.drawables.single { born -> before.drawables.none { it.id == born.id } }
                assertTrue(data.getValue("changed").jsonArray.any { it.jsonPrimitive.content == "mesh:${mesh.id.raw}" })
                val historySize = workspace.history().nodes.size
                assertEquals(2, historySize)
                assertEquals(result.structuredContent, tool.handler.invoke(connection, request).structuredContent)
                assertEquals(historySize, workspace.history().nodes.size)
                workspace.checkoutHistory(created.historyNodeId, MutationAuthor.AGENT)
                assertEquals(1, workspace.currentPuppet()!!.drawables.size)
                workspace.checkoutHistory(data.getValue("history_node_id").jsonPrimitive.content, MutationAuthor.AGENT)
                assertEquals(mesh.id, workspace.currentPuppet()!!.drawables.single { it.id == mesh.id }.id)
            }
        }
    }

    @Test fun deletingEditedLayerDoesNotBreakPreviewAndCheckoutRestoresEdit() = runBlocking {
        val png = temp.resolve("mouth.png")
        val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..21) for (x in 2..21) image.setRGB(x, y, 0xffff3366.toInt())
        ImageIO.write(image, "png", png.toFile())

        PSD2LiveViewModel().use { viewModel ->
            DesktopWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 24); put("height", 24)
                    putJsonArray("layers") {
                        add(buildJsonObject {
                            put("path", png.toString()); put("name", "mouth"); put("role", "objects")
                        })
                        add(buildJsonObject {
                            put("path", png.toString()); put("name", "background"); put("role", "objects")
                        })
                    }
                })
                val layerId = created.affectedLayerIds.first()
                val meshId = viewModel.state.value.previewModel!!.rig.layerIdByDrawableId
                    .entries.first { it.value == layerId }.key
                val edited = workspace.editObjects(buildJsonObject {
                    put("state", created.state!!)
                    putJsonArray("edits") { add(buildJsonObject {
                        put("action", "rename"); put("kind", "mesh")
                        put("id", meshId); put("name", "Edited mouth")
                    }) }
                })
                assertEquals("Edited mouth", viewModel.state.value.previewModel!!.rig.puppet.drawables
                    .single { it.id.raw == meshId }.name)

                val deleted = workspace.softDeleteLayer(layerId, edited.state!!, null)
                assertTrue(viewModel.state.value.previewModel!!.rig.puppet.drawables.none { it.id.raw == meshId })
                assertTrue(workspace.snapshot().layers.single { it.id == layerId }.deleted)

                workspace.checkoutHistory(edited.historyNodeId, MutationAuthor.AGENT)
                assertEquals("Edited mouth", viewModel.state.value.previewModel!!.rig.puppet.drawables
                    .single { it.id.raw == meshId }.name)
                assertTrue(deleted.applied)
            }
        }
    }
}
