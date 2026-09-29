package io.github.psd2live.agent

import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class SkeletonMotionWorkspaceTest {
    @TempDir lateinit var temp: Path

    @Test fun skeletonAndMotionSurviveHistoryAndExport() = runBlocking {
        val png = temp.resolve("body.png")
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..28) for (x in 7..24) image.setRGB(x, y, 0xff995588.toInt())
        ImageIO.write(image, "png", png.toFile())
        PSD2LiveViewModel().use { viewModel ->
            ViewModelAgentWorkspace(viewModel, temp.resolve("store")).use { workspace ->
                viewModel.attachAgentWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 32); put("height", 32)
                    putJsonArray("layers") { add(buildJsonObject {
                        put("path", png.toString()); put("name", "body"); put("role", "topwear")
                    }) }
                })
                val armature = workspace.editSkeleton(created.historyNodeId, buildJsonObject { put("mode", "auto") })
                assertTrue(armature.applied)
                assertTrue(workspace.skeletonSpec()!!.enabled)
                assertTrue(workspace.skeletonSpec()!!.bones.isNotEmpty())
                val motion = workspace.editMotion(armature.historyNodeId, buildJsonObject {
                    put("mode", "put")
                    put("clip", buildJsonObject {
                        put("id", "turn"); put("name", "TurnCustom"); put("duration", 2)
                        putJsonArray("curves") { add(buildJsonObject {
                            put("parameter", "ParamAngleX")
                            putJsonArray("keys") {
                                add(buildJsonObject { put("time", 0); put("value", 0) })
                                add(buildJsonObject { put("time", 1); put("value", 15) })
                                add(buildJsonObject { put("time", 2); put("value", 0) })
                            }
                        }) }
                    })
                })
                assertTrue(motion.applied)
                assertEquals("turn", workspace.motionClips().single().id)
                val files = workspace.exportModel(motion.historyNodeId, temp.resolve("export").toString())
                    .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                assertTrue(files.any { it.fileName.toString().contains("turn", ignoreCase = true) && it.fileName.toString().endsWith("motion3.json") })
                assertTrue(files.all(Files::isRegularFile))

                workspace.checkoutHistory(armature.historyNodeId, MutationAuthor.AGENT)
                assertTrue(workspace.motionClips().isEmpty())
                workspace.checkoutHistory(motion.historyNodeId, MutationAuthor.AGENT)
                assertEquals("turn", workspace.motionClips().single().id)
                assertTrue(workspace.skeletonSpec()!!.enabled)
            }
        }
    }
}
