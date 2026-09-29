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

    @Test fun bakedMotionAndPoseSnapshotsSurviveHistoryAndExport() = runBlocking {
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
                val bone = workspace.skeletonSpec()!!.bones.first { !it.role.anchor }
                val parameter = bone.parameterId
                val target = minOf(4f, bone.maxAngle)
                val bakeKeys = buildJsonArray {
                    add(buildJsonObject { put("time", 0); put("values", buildJsonObject { put(parameter, 0) }); put("ease", "LINEAR") })
                    add(buildJsonObject { put("time", 1); put("values", buildJsonObject { put(parameter, target) }) })
                }
                val preview = workspace.bakeSkeletonMotion(buildJsonObject {
                    put("keys", bakeKeys); put("fps", 30); put("tolerance", 0.05)
                })
                assertEquals(31, preview.getValue("frames").jsonPrimitive.int)
                assertEquals(2, preview.getValue("keys").jsonPrimitive.int)
                assertTrue(preview.getValue("max_error").jsonPrimitive.float <= 0.05f)
                // A preview never writes.
                assertTrue(workspace.motionClips().isEmpty())

                val baked = workspace.editMotion(armature.historyNodeId, buildJsonObject {
                    put("mode", "bake"); put("id", "sway"); put("name", "SwayBaked")
                    put("keys", bakeKeys); put("fps", 30); put("tolerance", 0.05)
                })
                assertTrue(baked.applied)
                val clip = workspace.motionClips().single()
                assertEquals("sway", clip.id)
                assertEquals(1f, clip.duration)
                assertEquals(target / 2f, io.github.psd2live.core.MotionClips.sample(clip.curve(parameter)!!, 0.5f), 0.06f)

                val posed = workspace.editMotion(baked.historyNodeId, buildJsonObject {
                    put("mode", "pose_put")
                    put("pose", buildJsonObject {
                        put("id", "lean"); put("name", "Lean")
                        put("values", buildJsonObject { put(parameter, target) })
                    })
                })
                assertTrue(posed.applied)
                assertEquals(listOf("lean"), workspace.posePresets().map { it.id })
                val files = workspace.exportModel(posed.historyNodeId, temp.resolve("export").toString())
                    .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
                assertTrue(files.any { it.fileName.toString().contains("swayBaked", ignoreCase = true) && it.fileName.toString().endsWith("motion3.json") })

                workspace.checkoutHistory(armature.historyNodeId, MutationAuthor.AGENT)
                assertTrue(workspace.motionClips().isEmpty())
                assertTrue(workspace.posePresets().isEmpty())
                workspace.checkoutHistory(posed.historyNodeId, MutationAuthor.AGENT)
                assertEquals("sway", workspace.motionClips().single().id)
                assertEquals(listOf("lean"), workspace.posePresets().map { it.id })
            }
        }
    }

    /** Polls [condition] for up to [seconds]; the dialog's solve and history checkout run off the test thread. */
    private fun waitFor(seconds: Int = 20, condition: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting" }
            Thread.sleep(25)
        }
    }

    @Test fun bakeDialogTurnsCapturedPosesIntoOneUndoableClip() = runBlocking {
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
                workspace.editSkeleton(created.historyNodeId, buildJsonObject { put("mode", "auto") })
                val bones = workspace.skeletonSpec()!!.bones.filterNot { it.role.anchor }
                val bone = bones.first()
                val target = minOf(4f, bone.maxAngle)
                val bake = viewModel.skeletonBake

                viewModel.openSkeletonBake()
                assertTrue(bake.open)
                // A pose holds one value per bone that has a parameter of its own.
                assertEquals(bones.map { it.parameterId }.toSet(), viewModel.capturePoseValues().keys)
                viewModel.captureBakeRow(0f, mapOf(bone.parameterId to 0f))
                viewModel.captureBakeRow(1f, mapOf(bone.parameterId to target))
                waitFor { bake.preview != null && !bake.computing }
                val result = assertNotNull(bake.preview?.result, bake.preview?.error)
                assertEquals(2, result.keyCount)
                assertEquals(1f, result.end)

                val clipsBefore = viewModel.motionClips.size
                val historyBefore = viewModel.state.value.historySnapshot?.nodes?.size ?: 0
                viewModel.commitSkeletonBake()
                assertFalse(bake.open)
                val clip = viewModel.motionClips.single { it.builtin == null }
                assertEquals(clipsBefore + 1, viewModel.motionClips.size)
                assertEquals(target / 2f, io.github.psd2live.core.MotionClips.sample(clip.curve(bone.parameterId)!!, 0.5f), 0.06f)
                waitFor { (viewModel.state.value.historySnapshot?.nodes?.size ?: 0) == historyBefore + 1 }
                val head = viewModel.state.value.historySnapshot!!
                assertEquals("Baked skeleton motion", head.nodes.first { it.id == head.headNodeId }.summary)

                // Snapshots are project state too: saved once, restored by undo, applied by name.
                viewModel.savePosePreset("", mapOf(bone.parameterId to target))
                assertEquals(1, viewModel.posePresets.size)
                waitFor { (viewModel.state.value.historySnapshot?.nodes?.size ?: 0) == historyBefore + 2 }
                viewModel.undoHistory()
                waitFor { viewModel.posePresets.isEmpty() }
                assertEquals(clip, viewModel.motionClips.single { it.builtin == null })
                viewModel.undoHistory()
                waitFor { viewModel.motionClips.none { it.builtin == null } }
            }
        }
    }
}
