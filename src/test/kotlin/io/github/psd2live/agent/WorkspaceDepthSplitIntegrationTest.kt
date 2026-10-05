package io.github.psd2live.agent

import io.github.psd2live.application.*
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class WorkspaceDepthSplitIntegrationTest {
    @TempDir lateinit var temporary: Path
    private suspend fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace, WorkspaceOperations, List<String>) -> Unit) {
        val art = temporary.resolve("art.png"); val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (y in 3..28) for (x in 3..28) image.setRGB(x, y, 0xff7799bb.toInt())
        ImageIO.write(image, "png", art.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject { put("width", 32); put("height", 32)
                    putJsonArray("layers") { for (name in listOf("Rear source", "Middle", "Other")) add(buildJsonObject {
                        put("path", art.toString()); put("name", name); put("role", "objects")
                    }) }
                })
                WorkspaceOperations(workspace).use { action(vm, workspace, it, created.affectedLayerIds) }
            }
        }
    }

    @Test fun guiAndPublicDepthSplitKeepAuthoredMotionThenPaintReopenAndExportSameSlices() = runBlocking<Unit> {
        fixture { vm, workspace, operations, layers ->
            val agent = WorkspaceOperationContext(MutationAuthor.AGENT); var sequence = 0
            suspend fun call(id: String, fields: JsonObject): JsonObject {
                val before = workspace.snapshot(); val definition = operations.registry.definition(id)
                val request = JsonObject(fields + buildJsonObject { put("project_id", before.projectId); put("state", before.state); put("request_id", "depth-${sequence++}") })
                val response = operations.registry.invoke(id, request, agent).data
                if (!definition.jobBacked) return response
                val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", response.getValue("id")) }, agent).data
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                val result = terminal.getValue("result").jsonObject; validateOperationSchema(result, definition.jobResultSchema!!)
                assertEquals(response.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id"))
                return result
            }
            val initial = vm.state.value.previewModel!!
            val source = initial.rig.puppet.drawables.single { initial.rig.layerIdByDrawableId[it.id.raw] == layers[0] }.id.raw
            val middle = initial.rig.puppet.drawables.single { initial.rig.layerIdByDrawableId[it.id.raw] == layers[1] }.id.raw
            workspace.createParameter(WorkspaceCreateParameterRequest("DepthAxis", workspace.snapshot().state, "Depth axis"))
            workspace.authorRig(workspace.snapshot().state, buildJsonArray {
                add(buildJsonObject { put("op", "canvas_geometry"); put("id", source); put("kind", "mesh"); put("preserve_image", true); put("key", JsonObject(emptyMap()))
                    put("points", JsonArray(workspace.currentPuppet()!!.drawables.single { it.id.raw == source }.mesh!!.positions.mapIndexed { index, point -> JsonPrimitive(point + if (index == 0) 0.025f else 0f) }))
                })
                for (value in listOf(-1, 1)) add(buildJsonObject { put("op", "set"); put("target", "mesh:$source"); putJsonObject("key") { put("DepthAxis", value) }; putJsonObject("channels") { put("opacity", if (value < 0) 0.4 else 0.8) } })
            }, MutationAuthor.USER)
            val root = workspace.snapshot(); val original = workspace.currentPuppet()!!
            vm.requestDepthSplit(source); assertNotNull(vm.pendingDepthSplit); vm.confirmDepthSplit(middle)
            withTimeout(10000) { vm.state.first { !it.workspaceEditBusy && it.previewModel!!.rig.puppet.drawables.size == 4 } }
            assertNull(vm.state.value.errorMessage)
            val gui = workspace.currentPuppet()!!; val frontId = vm.state.value.selectedLayerId!!
            val frontMesh = vm.state.value.previewModel!!.rig.layerIdByDrawableId.entries.single { it.value == frontId }.key
            val glueId = gui.glues.single().id!!
            assertEquals("user", workspace.history().nodes.last().actor)
            assertEquals(root.historyHeadNodeId, workspace.history().nodes.last().parentId)
            val frame = WorkspaceModelViewRequest(parameters = mapOf("DepthAxis" to 1f), frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 32f, 32f)),
                background = WorkspaceViewBackground.TRANSPARENT, output = WorkspaceViewOutputSpec(256))
            val guiPng = workspace.renderModel(frame).png
            workspace.checkoutHistory(root.historyHeadNodeId!!, MutationAuthor.USER)
            val result = call("source_split_depth", buildJsonObject {
                put("source_id", source); put("middle_ids", JsonArray(listOf(JsonPrimitive(middle))))
                put("front_layer_id", frontId); put("front_mesh_id", frontMesh); put("glue_id", glueId)
                put("names", JsonArray(listOf(gui.drawables.single { it.id.raw == source }.name, gui.drawables.single { it.id.raw == frontMesh }.name).map(::JsonPrimitive)))
            })
            assertEquals(listOf(frontId), result.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
            assertPartitionDeformers(gui.deformers, workspace.currentPuppet()!!.deformers)
            assertContentEquals(guiPng, workspace.renderModel(frame).png)
            call("source_paint_clear", buildJsonObject { put("layer_id", frontId); put("rebuild_mesh", true) })
            val before = workspace.currentPuppet()!!; val png = workspace.renderModel(frame).png
            assertContentEquals(original.drawables.single { it.id.raw == source }.mesh!!.positions, before.drawables.single { it.id.raw == source }.mesh!!.positions)
            val archive = temporary.resolve("depth.psd2live")
            call("project_save_as", buildJsonObject { put("path", archive.toString()) }); val nodes = workspace.history().nodes
            call("project_open", buildJsonObject { put("path", archive.toString()) })
            assertEquals(nodes, workspace.history().nodes); assertContentEquals(png, workspace.renderModel(frame).png)
            val exportedFiles = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("export").toString()) })
                .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
            val cmo3 = exportedFiles.single { it.toString().endsWith(".cmo3") }
            val exported = Cmo3ModelImport.read(Files.readAllBytes(cmo3)).puppet
            // CMO3 identifies glue by mesh pair and ordinal, rather than the workspace's ID.
            assertDepthGlues(before.glues, exported.glues, checkIds = false)
            val evaluator = CpuDeformationEvaluator()
            for (value in listOf(-1f, 0f, 1f)) {
                val pose = mapOf(ParameterId("DepthAxis") to value); val expected = evaluator.evaluate(before, pose)
                for (puppet in listOf(workspace.currentPuppet()!!, exported)) {
                    val actual = evaluator.evaluate(puppet, pose)
                    expected.worldPositions.forEach { (id, vertices) ->
                        vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                        assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                    }
                }
            }
            val visuals = Path.of("build/depth-split-command-visual").toAbsolutePath(); Files.createDirectories(visuals)
            Files.write(visuals.resolve("gui.png"), guiPng); Files.write(visuals.resolve("before-reopen.png"), png); Files.write(visuals.resolve("after-reopen.png"), workspace.renderModel(frame).png)
            call("project_import_cmo3", buildJsonObject { put("path", cmo3.toString()); put("mode", "new"); put("discard_unsaved", true) })
            val imported = workspace.snapshot(); val history = workspace.history()
            val job = operations.registry.invoke("source_split_depth", buildJsonObject {
                put("project_id", imported.projectId); put("state", imported.state); put("request_id", "invalid-imported-depth")
                put("source_id", "missing-imported-source"); put("middle_ids", JsonArray(listOf(JsonPrimitive(middle))))
            }, agent).data
            val failed = operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            assertEquals("invalid_argument", failed.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(imported.state, workspace.snapshot().state); assertEquals(history, workspace.history())

            // Imported slices use the same GUI candidate, public command and retained-model export.
            val importedBase = workspace.currentPuppet()!!
            vm.requestDepthSplit(source); assertNotNull(vm.pendingDepthSplit); vm.confirmDepthSplit(middle)
            withTimeout(10000) { vm.state.first { !it.workspaceEditBusy && it.previewModel!!.rig.puppet.drawables.size == importedBase.drawables.size + 1 } }
            assertNull(vm.state.value.errorMessage)
            val importedGui = workspace.currentPuppet()!!
            val importedFrontLayer = vm.state.value.selectedLayerId!!
            val importedFrontMesh = vm.state.value.previewModel!!.rig.layerIdByDrawableId.entries.single { it.value == importedFrontLayer }.key
            val importedGlue = importedGui.glues.single { glue -> importedBase.glues.none { it.id == glue.id } }.id!!
            val importedGuiPng = workspace.renderModel(frame).png
            assertEquals("user", workspace.history().nodes.last().actor)
            assertEquals(imported.historyHeadNodeId, workspace.history().nodes.last().parentId)
            workspace.checkoutHistory(imported.historyHeadNodeId!!, MutationAuthor.USER)
            val importedResult = call("source_split_depth", buildJsonObject {
                put("source_id", source); put("middle_ids", JsonArray(listOf(JsonPrimitive(middle))))
                put("front_layer_id", importedFrontLayer); put("front_mesh_id", importedFrontMesh); put("glue_id", importedGlue)
                put("names", JsonArray(listOf(importedGui.drawables.single { it.id.raw == source }.name,
                    importedGui.drawables.single { it.id.raw == importedFrontMesh }.name).map(::JsonPrimitive)))
            })
            assertEquals(listOf(importedFrontLayer), importedResult.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
            assertPartitionDeformers(importedGui.deformers, workspace.currentPuppet()!!.deformers)
            assertDepthGlues(importedGui.glues, workspace.currentPuppet()!!.glues)
            val importedPublicPng = workspace.renderModel(frame).png
            assertContentEquals(importedGuiPng, importedPublicPng)
            val importedFiles = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("imported-export").toString()) })
                .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
            val importedExport = Cmo3ModelImport.read(Files.readAllBytes(importedFiles.single { it.toString().endsWith(".cmo3") })).puppet
            assertDepthGlues(importedGui.glues, importedExport.glues, checkIds = false)
            for (value in listOf(-1f, 0f, 1f)) {
                val pose = mapOf(ParameterId("DepthAxis") to value); val expected = evaluator.evaluate(importedGui, pose)
                for (puppet in listOf(workspace.currentPuppet()!!, importedExport)) {
                    val actual = evaluator.evaluate(puppet, pose)
                    assertEquals(expected.worldPositions.keys, actual.worldPositions.keys)
                    expected.worldPositions.forEach { (id, vertices) ->
                        assertEquals(vertices.size, actual.worldPositions.getValue(id).size)
                        vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                        assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                        assertEquals(expected.drawOrder.getValue(id), actual.drawOrder.getValue(id), 0.00001f)
                    }
                }
            }
            Files.write(visuals.resolve("imported-gui.png"), importedGuiPng); Files.write(visuals.resolve("imported-public.png"), importedPublicPng)
        }
    }

    @Test fun depthDialogKeepsOriginalStateAndRejectsAuxiliaryChangesWithoutStartingPaint() = runBlocking<Unit> {
        fixture { vm, workspace, _, layers ->
            val before = vm.state.value.previewModel!!
            val meshes = layers.map { layer -> before.rig.layerIdByDrawableId.entries.single { it.value == layer }.key }
            vm.requestDepthSplit(meshes[0]); val offer = vm.pendingDepthSplit!!; val history = workspace.history()
            vm.saveParameterSnapshot("Concurrent pose"); val changed = workspace.snapshot().state
            assertNotEquals(offer.expected.state, changed); assertSame(before, vm.state.value.previewModel)
            vm.confirmDepthSplit(meshes[1])
            withTimeout(10000) { vm.state.first { it.errorMessage != null && !it.canvasEditBusy } }
            assertEquals(changed, workspace.snapshot().state); assertEquals(history, workspace.history()); assertEquals(3, workspace.currentPuppet()!!.drawables.size)
            assertNull(vm.canvasEditor.paintSession)
        }
    }

    @Test fun publicDepthBatchCanAuthorAndEraseNewFrontAndLateFailureRollsBackEveryMember() = runBlocking<Unit> {
        fixture { vm, workspace, operations, layers ->
            val before = workspace.snapshot(); val history = workspace.history(); val model = vm.state.value.previewModel!!
            val meshes = layers.map { layer -> model.rig.layerIdByDrawableId.entries.single { it.value == layer }.key }
            val edits = buildJsonArray {
                add(buildJsonObject { put("operation", "parameter_create"); putJsonObject("request") { put("parameter_id", "BatchDepthAxis"); put("name", "Axis") } })
                add(buildJsonObject { put("operation", "source_split_depth"); putJsonObject("request") {
                    put("source_id", meshes[0]); put("middle_ids", JsonArray(listOf(JsonPrimitive(meshes[1]))))
                    put("front_layer_id", "batch-depth-front"); put("front_mesh_id", "BatchDepthFront"); put("glue_id", "BatchDepthWeld")
                } })
                add(buildJsonObject { put("operation", "keyform_apply"); putJsonObject("request") { putJsonArray("changes") { add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:BatchDepthFront"); putJsonObject("key") { put("BatchDepthAxis", 1) }; putJsonObject("channels") { put("opacity", 0.4) }
                }) } } })
                add(buildJsonObject { put("operation", "source_paint_clear"); putJsonObject("request") { put("layer_id", "batch-depth-front"); put("rebuild_mesh", true) } })
            }
            val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
            suspend fun batch(id: String, members: JsonArray): JsonObject {
                val job = operations.registry.invoke("workspace_apply_edits", buildJsonObject {
                    put("request_id", id); put("project_id", before.projectId); put("state", before.state); put("edits", members)
                }, agent).data
                return operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
            }
            val failed = batch("bad-depth-batch", JsonArray(edits + buildJsonObject {
                put("operation", "layer_mesh_update"); putJsonObject("request") { put("layer_id", "missing"); put("reset", true) }
            }))
            assertEquals("failed", failed.getValue("status").jsonPrimitive.content)
            assertEquals(4, failed.getValue("error").jsonObject.getValue("edit_index").jsonPrimitive.int)
            assertEquals(before.state, workspace.snapshot().state); assertEquals(history, workspace.history())
            val completed = batch("depth-batch", edits)
            assertEquals("completed", completed.getValue("status").jsonPrimitive.content, completed.toString())
            val result = completed.getValue("result").jsonObject
            validateOperationSchema(result, operations.registry.definition("workspace_apply_edits").jobResultSchema!!)
            assertTrue(result.getValue("changed").jsonArray.map { it.jsonPrimitive.content }
                .containsAll(listOf("layer:batch-depth-front", "mesh:BatchDepthFront", "glue:BatchDepthWeld", "parameter:BatchDepthAxis")))
            assertEquals(history.nodes.size + 1, workspace.history().nodes.size)
            val front = vm.state.value.analysis!!.source.layers.single { it.id.raw == "batch-depth-front" }
            assertTrue(front.raster.rgba.indices.filter { it % 4 == 3 }.all { front.raster.rgba[it] == 0.toByte() })
            val pose = CpuDeformationEvaluator().evaluate(workspace.currentPuppet()!!, mapOf(ParameterId("BatchDepthAxis") to 1f))
            assertEquals(0.4f, pose.opacity.getValue(org.umamo.runtime.model.DrawableId("BatchDepthFront")), 0.00001f)
        }
    }
}
