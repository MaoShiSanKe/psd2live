package io.github.psd2live.agent

import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissingMeshReplayIntegrationTest {
    @TempDir lateinit var temp: Path

    @Test fun deletingEditedLayerDoesNotBreakPreviewAndCheckoutRestoresEdit() = runBlocking {
        val png = temp.resolve("mouth.png")
        val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB)
        for (y in 2..21) for (x in 2..21) image.setRGB(x, y, 0xffff3366.toInt())
        ImageIO.write(image, "png", png.toFile())

        PSD2LiveViewModel().use { viewModel ->
            ViewModelAgentWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachAgentWorkspace(workspace)
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
                    put("expected_history_head_node_id", created.historyNodeId)
                    putJsonArray("edits") { add(buildJsonObject {
                        put("action", "rename"); put("kind", "mesh")
                        put("id", meshId); put("name", "Edited mouth")
                    }) }
                })
                assertEquals("Edited mouth", viewModel.state.value.previewModel!!.rig.puppet.drawables
                    .single { it.id.raw == meshId }.name)

                val deleted = workspace.softDeleteLayer(layerId, edited.historyNodeId, null)
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
