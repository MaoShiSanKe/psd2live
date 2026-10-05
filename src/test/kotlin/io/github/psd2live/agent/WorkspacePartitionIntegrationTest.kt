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

class WorkspacePartitionIntegrationTest {
    @TempDir lateinit var temporary: Path
    private suspend fun fixture(action: suspend (PSD2LiveViewModel, DesktopWorkspace, WorkspaceOperations, String) -> Unit) {
        val art = temporary.resolve("art.png"); val image = BufferedImage(96, 48, BufferedImage.TYPE_INT_ARGB)
        for (y in 8..39) for (x in (6..34) + (60..88)) image.setRGB(x, y, 0xff7799bb.toInt())
        ImageIO.write(image, "png", art.toFile())
        PSD2LiveViewModel().use { vm ->
            vm.setStateForTest(vm.state.value.copy(atlasSize = 256, exportMoc3 = false, generatePhysics = false))
            DesktopWorkspace(vm, temporary.resolve("store")).use { workspace ->
                vm.attachWorkspace(workspace)
                val created = workspace.createArtwork(buildJsonObject {
                    put("width", 96); put("height", 48); putJsonArray("layers") { add(buildJsonObject {
                        put("path", art.toString()); put("name", "Synthetic islands"); put("role", "objects")
                    }) }
                })
                WorkspaceOperations(workspace).use { action(vm, workspace, it, created.affectedLayerIds.single()) }
            }
        }
    }

    @Test fun actualGuiAndPublicPartitionsShareHistoryThenReopenAndExportTheSameAuthoredModel() = runBlocking<Unit> {
        fixture { vm, workspace, operations, original ->
            val agent = WorkspaceOperationContext(MutationAuthor.AGENT); var sequence = 0
            suspend fun call(id: String, fields: JsonObject): JsonObject {
                val before = workspace.snapshot(); val definition = operations.registry.definition(id)
                val request = if (definition.kind == WorkspaceOperationKind.QUERY) fields else JsonObject(fields + buildJsonObject {
                    put("project_id", before.projectId); put("state", before.state); put("request_id", "partition-${sequence++}")
                })
                val response = operations.registry.invoke(id, request, agent).data
                if (!definition.jobBacked) return response
                val terminal = operations.registry.invoke("job_wait", buildJsonObject { put("id", response.getValue("id")) }, agent).data
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content, terminal.toString())
                val result = terminal.getValue("result").jsonObject; validateOperationSchema(result, definition.jobResultSchema!!)
                assertEquals(response.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id"))
                return result
            }
            val originalMesh = workspace.currentPuppet()!!.drawables.single().id.raw
            workspace.createParameter(WorkspaceCreateParameterRequest("PartitionSourceAxis", workspace.snapshot().state, "Source shape"))
            workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:$originalMesh"); putJsonObject("key") { put("PartitionSourceAxis", 1) }
                putJsonObject("channels") { put("opacity", 0.6) }
            }) }, MutationAuthor.USER)
            val originalVertices = workspace.currentPuppet()!!.drawables.single().mesh!!.positions
            workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                put("op", "path_put"); put("id", "PartitionSourcePath"); put("target", "mesh:$originalMesh")
                putJsonArray("points") {
                    add(buildJsonArray { add(originalVertices[0]); add(originalVertices[1]) })
                    add(buildJsonArray { add(originalVertices[originalVertices.size - 2]); add(originalVertices.last()) })
                }
            }) }, MutationAuthor.USER)
            val root = workspace.snapshot(); val rootHistorySize = workspace.history().nodes.size
            val query = call("source_get_components", buildJsonObject { put("layer_id", original) })
            assertEquals(2, query.getValue("count").jsonPrimitive.int)
            assertEquals(root.state, workspace.snapshot().state)
            vm.requestMeshSplit(original)
            withTimeout(10000) { while (vm.pendingMeshSplit == null) kotlinx.coroutines.delay(20) }
            vm.confirmMeshSplit(listOf("Left island", "Right island"), listOf(Side.LEFT, Side.RIGHT))
            withTimeout(10000) { vm.state.first { it.analysis!!.layers.map { layer -> layer.source.name }.toSet() == setOf("Left island", "Right island") } }
            assertNull(vm.state.value.errorMessage)
            val guiNode = workspace.history().nodes.last(); assertEquals("user", guiNode.actor)
            assertEquals(rootHistorySize + 1, workspace.history().nodes.size)
            val guiLayers = workspace.snapshot().layers.filterNot { it.deleted }.map { it.id }
            val guiPuppet = workspace.currentPuppet()!!
            workspace.checkoutHistory(root.historyHeadNodeId!!, MutationAuthor.USER)
            val result = call("source_split_components", buildJsonObject {
                put("layer_id", original); put("names", JsonArray(listOf("Left island", "Right island").map(::JsonPrimitive)))
                put("sides", JsonArray(listOf("left", "right").map(::JsonPrimitive)))
                put("piece_ids", JsonArray(guiLayers.map(::JsonPrimitive)))
            })
            assertEquals(guiLayers.toSet(), result.getValue("layers").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertPartitionDeformers(guiPuppet.deformers, workspace.currentPuppet()!!.deformers)
            assertEquals(guiPuppet.drawables.map { it.id }, workspace.currentPuppet()!!.drawables.map { it.id })
            assertTrue(workspace.currentPuppet()!!.deformPaths.any { it.id.endsWith("/PartitionSourcePath") })
            guiPuppet.drawables.forEach { mesh -> assertContentEquals(mesh.mesh!!.positions,
                workspace.currentPuppet()!!.drawables.single { it.id == mesh.id }.mesh!!.positions) }
            val first = result.getValue("layers").jsonArray.first().jsonPrimitive.content
            call("source_split_polygon", buildJsonObject {
                put("layer_id", first); put("names", JsonArray(listOf("Top", "Bottom").map(::JsonPrimitive)))
                put("piece_ids", JsonArray(listOf("partition-top", "partition-bottom").map(::JsonPrimitive)))
                putJsonArray("polygon") { listOf(0 to 0, 96 to 0, 96 to 24, 0 to 24).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
            })
            val beforeMesh = workspace.snapshot()
            val oldTopology = workspace.currentPuppet()!!.drawables.single { it.id.raw == vm.state.value.rigEdits.splitDrawableIds.getValue("partition-top") }.mesh!!
            vm.setPartMeshSettings("partition-top", MeshSettings(outerMargin = 5f, interiorDensity = 6f))
            withTimeout(10000) { while (workspace.snapshot().state == beforeMesh.state) kotlinx.coroutines.delay(20) }
            assertNull(vm.state.value.errorMessage)
            assertEquals("user", workspace.history().nodes.last().actor)
            val guiRemesh = workspace.currentPuppet()!!
            assertFalse(oldTopology.positions.contentEquals(guiRemesh.drawables.single { it.id.raw == vm.state.value.rigEdits.splitDrawableIds.getValue("partition-top") }.mesh!!.positions))
            workspace.checkoutHistory(beforeMesh.historyHeadNodeId!!, MutationAuthor.USER)
            call("layer_mesh_update", buildJsonObject { put("layer_id", "partition-top"); putJsonObject("changes") {
                put("outerMargin", 5); put("interiorDensity", 6); put("maxEdgeDistance", 6)
            } })
            guiRemesh.drawables.forEach { drawable -> assertContentEquals(drawable.mesh!!.positions,
                workspace.currentPuppet()!!.drawables.single { it.id == drawable.id }.mesh!!.positions) }
            assertEquals(guiRemesh.deformPaths.map { it.id }, workspace.currentPuppet()!!.deformPaths.map { it.id })
            call("settings_update", buildJsonObject { putJsonObject("changes") { put("meshOuterMargin", 4); put("meshInteriorDensity", 6); put("alphaThreshold", 12) } })
            call("source_split_polygon", buildJsonObject {
                put("layer_id", guiLayers[1]); put("names", JsonArray(listOf("Right top", "Right bottom").map(::JsonPrimitive)))
                put("piece_ids", JsonArray(listOf("partition-right-top", "partition-right-bottom").map(::JsonPrimitive)))
                putJsonArray("polygon") { listOf(0 to 0, 96 to 0, 96 to 24, 0 to 24).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
            })
            val mesh = workspace.currentPuppet()!!.drawables.first().id.raw
            workspace.createParameter(WorkspaceCreateParameterRequest("PartitionAxis", workspace.snapshot().state, "Partition axis"))
            workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:$mesh"); putJsonObject("key") { put("PartitionAxis", 1) }; putJsonObject("channels") { put("opacity", 0.4) }
            }) }, MutationAuthor.USER)
            val before = workspace.currentPuppet()!!
            val frame = WorkspaceModelViewRequest(parameters = mapOf("PartitionAxis" to 1f),
                frame = WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 48f)), background = WorkspaceViewBackground.TRANSPARENT,
                output = WorkspaceViewOutputSpec(256))
            val png = workspace.renderModel(frame).png
            val rendered = ImageIO.read(png.inputStream())
            assertTrue((0 until rendered.height).sumOf { y -> (0 until rendered.width).count { x -> rendered.getRGB(x, y) ushr 24 != 0 } } > 1000,
                "The partition round trip must verify visible art, rather than two empty images")
            val archive = temporary.resolve("partition.psd2live")
            call("project_save_as", buildJsonObject { put("path", archive.toString()) })
            val nodes = workspace.history().nodes
            call("project_open", buildJsonObject { put("path", archive.toString()) })
            assertEquals(nodes, workspace.history().nodes); assertContentEquals(png, workspace.renderModel(frame).png)
            val files = call("project_export_model", buildJsonObject { put("output_directory", temporary.resolve("export").toString()) })
                .getValue("files").jsonArray.map { Path.of(it.jsonObject.getValue("path").jsonPrimitive.content) }
            val exported = Cmo3ModelImport.read(Files.readAllBytes(files.single { it.toString().endsWith(".cmo3") })).puppet
            val evaluator = CpuDeformationEvaluator()
            for (value in listOf(-1f, 0f, 1f)) {
                val pose = mapOf(ParameterId("PartitionAxis") to value); val expected = evaluator.evaluate(before, pose)
                for (puppet in listOf(workspace.currentPuppet()!!, exported)) {
                    val actual = evaluator.evaluate(puppet, pose)
                    expected.worldPositions.forEach { (id, vertices) ->
                        vertices.indices.forEach { assertEquals(vertices[it], actual.worldPositions.getValue(id)[it], 0.001f) }
                        assertEquals(expected.opacity.getValue(id), actual.opacity.getValue(id), 0.00001f)
                    }
                }
            }
            val visuals = Path.of("build/partition-remesh-visual").toAbsolutePath(); Files.createDirectories(visuals)
            Files.write(visuals.resolve("before-reopen.png"), png); Files.write(visuals.resolve("after-reopen.png"), workspace.renderModel(frame).png)
        }
    }

    @Test fun guiPartitionOfferKeepsItsOriginalStateWhenAuxiliaryDataChanges() = runBlocking<Unit> {
        fixture { vm, workspace, _, layer ->
            vm.requestMeshSplit(layer)
            withTimeout(10000) { while (vm.pendingMeshSplit == null) kotlinx.coroutines.delay(20) }
            val offered = vm.pendingMeshSplit!!; val history = workspace.history()
            vm.saveParameterSnapshot("Concurrent pose")
            assertNotEquals(offered.expected!!.state, workspace.snapshot().state)
            val changed = workspace.snapshot().state
            vm.confirmMeshSplit(listOf("First", "Second"), listOf(Side.NONE, Side.NONE))
            withTimeout(10000) { vm.state.first { it.errorMessage != null } }
            assertEquals(changed, workspace.snapshot().state); assertEquals(history, workspace.history())
            assertFalse(workspace.snapshot().layers.single().deleted)
        }
    }

    @Test fun publicBatchExposesNewLayerHandlesAndRejectsLaterMembersWithoutPublishingSourcePixels() = runBlocking<Unit> {
        fixture { _, workspace, operations, layer ->
            workspace.authorRig(workspace.snapshot().state, buildJsonArray { add(buildJsonObject {
                put("op", "structure"); putJsonArray("edits") { add(buildJsonObject {
                    put("kind", "mesh"); put("id", workspace.currentPuppet()!!.drawables.single().id.raw); put("action", "rename"); put("name", "Authored islands")
                }) }
            }) }, MutationAuthor.USER)
            val before = workspace.snapshot(); val history = workspace.history(); val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
            val edits = buildJsonArray {
                add(buildJsonObject { put("operation", "source_split_components"); putJsonObject("request") {
                    put("layer_id", layer); put("names", JsonArray(listOf("First", "Second").map(::JsonPrimitive)))
                    put("piece_ids", JsonArray(listOf("batch-first", "batch-second").map(::JsonPrimitive)))
                } })
                add(buildJsonObject { put("operation", "layer_mesh_update"); putJsonObject("request") {
                    put("layer_id", "batch-first"); putJsonObject("changes") { put("outerMargin", 5) }
                } })
            }
            suspend fun execute(id: String, members: JsonArray): JsonObject {
                val job = operations.registry.invoke("workspace_apply_edits", buildJsonObject {
                    put("project_id", before.projectId); put("state", before.state); put("request_id", id); put("edits", members)
                }, agent).data
                return operations.registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data
            }
            val invalid = execute("bad-partition-batch", JsonArray(edits + buildJsonObject {
                put("operation", "layer_mesh_update"); putJsonObject("request") { put("layer_id", "missing"); put("reset", true) }
            }))
            assertEquals("failed", invalid.getValue("status").jsonPrimitive.content)
            assertEquals(2, invalid.getValue("error").jsonObject.getValue("edit_index").jsonPrimitive.int)
            assertEquals(before.state, workspace.snapshot().state); assertEquals(history, workspace.history())
            val result = execute("partition-batch", edits)
            assertEquals("completed", result.getValue("status").jsonPrimitive.content)
            assertEquals(history.nodes.size + 1, workspace.history().nodes.size)
            assertTrue(result.getValue("result").jsonObject.getValue("changed").jsonArray.map { it.jsonPrimitive.content }
                .containsAll(listOf("layer:batch-first", "layer:batch-second")))
            assertEquals(5f, workspace.layerMeshSettings("batch-first").getValue("outerMargin").jsonPrimitive.float)
        }
    }
}
