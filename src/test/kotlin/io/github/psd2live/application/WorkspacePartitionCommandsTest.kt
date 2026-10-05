package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import org.umamo.runtime.model.*
import io.github.psd2live.core.sim.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspacePartitionCommandsTest {
    private val builder = WorkspacePreviewBuilder()
    private val agent = WorkspaceOperationContext(MutationAuthor.AGENT)
    private suspend fun fixture(twoIslandLayers: Boolean = false,
                                rebuild: suspend (WorkspaceDocument) -> RigPreviewModel = { builder.build(it) }): WorkspaceRuntime<RigPreviewModel> {
        val rgba = ByteArray(96 * 48 * 4)
        for (y in 8..39) for (x in (6..34) + (60..88)) {
            val offset = (y * 96 + x) * 4
            rgba[offset] = 90; rgba[offset + 1] = 120; rgba[offset + 2] = 150.toByte(); rgba[offset + 3] = 255.toByte()
        }
        val source = WorkspaceSourceLayer(LayerId("islands"), "Islands", "", SourceLayerKind.Raster, true, 2,
            LayerBounds(0, 0, 96, 48), 1f, false, LayerBlend.Normal, ChannelMask.ALL, LayerRaster(96, 48, rgba), null, null, false)
        val other = if (twoIslandLayers) source.copy(id = LayerId("other"), name = "Other", order = 1) else
            source.copy(id = LayerId("other"), name = "Other", order = 1,
                bounds = LayerBounds(0, 0, 8, 8), raster = LayerRaster(8, 8, ByteArray(256) { if (it % 4 == 3) 255.toByte() else 80 }))
        val document = WorkspaceDocument(WorkspaceSourceArt(96, 48, listOf(source, other), emptyList()), emptyMap(), emptySet(),
            mapOf("islands" to LayerClassificationOverride(tag = SemanticTag.OBJECTS), "other" to LayerClassificationOverride(tag = SemanticTag.OBJECTS)),
            emptyMap(), RigEditOverlay.Empty,
            WorkspaceSettingsCodec.encode(PipelineConfig(atlasSize = 256, exportMoc3 = false, generatePhysics = false)))
        return WorkspaceRuntime(rebuild).also { it.install(it.state.value.state, "partition", document, builder.build(document)) }
    }

    private fun operation(id: String) = WorkspaceDocumentOperation(id, buildJsonObject {
        put("layer_id", "islands"); put("names", JsonArray(listOf("First", "Second").map(::JsonPrimitive)))
        put("piece_ids", JsonArray(listOf("first", "second").map(::JsonPrimitive)))
        if (id == "source_split_components") put("sides", JsonArray(listOf("left", "right").map(::JsonPrimitive)))
        else putJsonArray("polygon") { listOf(0 to 0, 48 to 0, 48 to 48, 0 to 48).forEach { (x, y) -> add(buildJsonArray { add(x); add(y) }) } }
    })

    private class Host(val runtime: WorkspaceRuntime<RigPreviewModel>, val after: suspend () -> Unit = {}) : WorkspaceBackendStub() {
        private val commands = WorkspacePartitionCommands(runtime)
        override fun captureQueries(): WorkspaceQueries = WorkspaceReadSession(runtime.read())
        override fun snapshot() = captureQueries().snapshot()
        override suspend fun splitArtwork(arguments: JsonObject, author: MutationAuthor): WorkspaceMutationResult =
            submit(arguments.getValue("state").jsonPrimitive.content, listOf(WorkspaceDocumentOperation("source_split_polygon", JsonObject(arguments - "state"))))
        override suspend fun splitMeshComponents(state: String, splits: List<JsonObject>): WorkspaceMutationResult =
            submit(state, splits.map { WorkspaceDocumentOperation("source_split_components", it) })
        private suspend fun submit(state: String, edits: List<WorkspaceDocumentOperation>): WorkspaceMutationResult {
            val result = commands.execute(runtime.capture().projectId, state, edits, "Partition", MutationAuthor.AGENT)
            after(); return result.mutation
        }
    }

    private fun input(runtime: WorkspaceRuntime<RigPreviewModel>, edit: WorkspaceDocumentOperation) = JsonObject(edit.request + buildJsonObject {
        val before = runtime.capture(); put("project_id", before.projectId); put("state", before.state); put("request_id", "partition")
    })
    private suspend fun WorkspaceOperations.wait(job: JsonObject) = registry.invoke("job_wait", buildJsonObject { put("id", job.getValue("id")) }, agent).data

    @Test fun authoredGeometryChannelsWeightsAndLateSimulationMigrateIntoBothPartitionKinds() = runBlocking<Unit> {
        for (operation in WorkspacePartitionEdits.supported) for (blend in listOf(false, true)) {
            val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
            val sourceId = DrawableId(root.model.rig.layerIdByDrawableId.entries.single { it.value == "islands" }.key)
            val count = root.model.rig.puppet.drawables.single { it.id == sourceId }.mesh!!.vertexCount
            val deltas = FloatArray(count * 2) { kotlin.math.sin(it.toDouble()).toFloat() * 0.1f }
            val group = VertexGroup("Pins", sourceId, VertexGroupKind.PIN, FloatArray(count) { it.toFloat() / count })
            val authored = commands.execute(root.projectId, root.state, "Author partition target", listOf(
                WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "PartitionAxis"); put("name", "Shape"); put("min", -1); put("max", 1) }),
                WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "PartitionParent"); put("name", "Parent"); put("rows", 2); put("columns", 2)
                    put("meshes", JsonArray(listOf(JsonPrimitive(sourceId.raw)))) }),
                WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                    add(buildJsonObject { put("op", "set"); put("target", "mesh:${sourceId.raw}"); putJsonObject("key") { put("PartitionAxis", 1) }
                        putJsonObject("geometry") { put("positionDeltas", JsonArray(deltas.map(::JsonPrimitive))) }
                        putJsonObject("channels") { put("opacity", 0.4); put("multiplyColor", buildJsonArray { add(0.5); add(0.8); add(0.3) }) }
                    })
                    add(VertexGroupJournal.encode(group))
                } })), MutationAuthor.USER).capture
            val mode = SimBakedAxis("PartitionMode", floatArrayOf(-SimGenerator.MODE_RANGE, 0f, SimGenerator.MODE_RANGE), mapOf(sourceId.raw to
                listOf(FloatArray(count * 2) { -deltas[it] }, FloatArray(count * 2), deltas)))
            val sim = RigSimEdit("PartitionBody", "Body", SimKind.CLOTH, listOf(sourceId.raw), blendShapes = blend, autoBake = false,
                bake = SimBakeResult("synthetic", mapOf(sourceId.raw to count), emptyList(), listOf(SimBakedMode(mode, 1f, 1f))))
            val before = commands.executeCandidate(authored.projectId, authored.state, "Materialized motion", MutationAuthor.USER,
                mutation = { document, _ -> document.copy(rigEdits = document.rigEdits.copy(simEdits = listOf(sim))) }).capture
            val original = before.model.rig.puppet.drawables.single { it.id == sourceId }
            val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state,
                listOf(operation(operation)), "Bound partition", MutationAuthor.USER).commit.capture
            assertTrue(result.model.rig.puppet.drawables.none { it.id == sourceId })
            val journal = SourcePartitionJournal.commands(result.document.rigEdits).single()
            val records = SourcePartitionJournal.pieces(journal)
            val body = result.document.rigEdits.simEdits.single()
            assertEquals(records.map { it.getValue("id").jsonPrimitive.content }, body.targets)
            assertFalse(sourceId.raw in body.bake!!.vertexCounts)
            val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
            val replayed = builder.build(result.document)
            for (record in records) {
                val id = DrawableId(record.getValue("id").jsonPrimitive.content)
                val actual = result.model.rig.puppet.drawables.single { it.id == id }
                assertTrue(actual.isVisible)
                assertEquals(actual.mesh!!.vertexCount, body.bake.vertexCounts[id.raw])
                fun interpolate(values: FloatArray, stride: Int): FloatArray = record.getValue("sources").jsonArray.flatMap { row ->
                    val s = row.jsonArray
                    (0 until stride).map { axis -> if (s.size == 1) values[s[0].jsonPrimitive.int * stride + axis] else
                        (0..2).sumOf { k -> values[s[k].jsonPrimitive.int * stride + axis].toDouble() * s[k + 3].jsonPrimitive.float }.toFloat() }
                }.toFloatArray()
                assertEquals(original.geometryGrid!!.axes.map { it.parameterId }, actual.geometryGrid!!.axes.map { it.parameterId })
                original.geometryGrid.cells.zip(actual.geometryGrid.cells).forEach { (old, next) ->
                    val expected = interpolate(old.form.positionDeltas, 2)
                    assertEquals(expected.size, next.form.positionDeltas.size)
                    expected.indices.forEach { assertEquals(expected[it], next.form.positionDeltas[it], 0.00001f) }
                }
                assertEquals(original.blendShapes.size, actual.blendShapes.size)
                original.blendShapes.zip(actual.blendShapes).forEach { (old, next) -> old.forms.zip(next.forms).forEach { (a, b) ->
                    if (a == null) assertNull(b) else {
                        val expected = interpolate(a.positionDeltas, 2); assertNotNull(b)
                        expected.indices.forEach { assertEquals(expected[it], b.positionDeltas[it], 0.00001f) }
                    }
                } }
                val weights = result.model.rig.puppet.vertexGroups.single { it.drawableId == id && it.name == "Pins" }.weights
                val expectedWeights = interpolate(before.model.rig.puppet.vertexGroups.single { it.drawableId == sourceId && it.name == "Pins" }.weights, 1)
                weights.indices.forEach { assertEquals(expectedWeights[it], weights[it], 0.00001f) }
                for (value in listOf(-1f, 0f, 1f)) {
                    val pose = mapOf(ParameterId("PartitionAxis") to value, ParameterId("PartitionMode") to value * 20f)
                    val a = evaluator.evaluate(result.model.rig.puppet, pose); val b = evaluator.evaluate(replayed.rig.puppet, pose)
                    assertContentEquals(a.worldPositions.getValue(id), b.worldPositions.getValue(id))
                    assertEquals(a.opacity.getValue(id), b.opacity.getValue(id))
                }
            }
            runtime.checkout(result.projectId, result.state, before.historyHead)
            assertEquals(listOf(sourceId.raw), runtime.capture().document.rigEdits.simEdits.single().targets)
            runtime.checkout(result.projectId, runtime.capture().state, result.historyHead)
            assertEquals(result.document, runtime.capture().document)
            val partition = runtime.capture()
            val meshEdit = WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
                put("layer_id", "first"); putJsonObject("changes") { put("outerMargin", 7); put("interiorDensity", 6) }
            })
            val remeshed = commands.execute(partition.projectId, partition.state, "Partition mesh settings", listOf(meshEdit), MutationAuthor.USER).capture
            assertTrue(MeshGenerationBaseline.present(remeshed.document.rigEdits))
            val firstId = DrawableId(remeshed.document.rigEdits.splitDrawableIds.getValue("first"))
            val replacement = remeshed.document.rigEdits.authoringJournal.last { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP }
            assertEquals(firstId.raw, replacement.getValue("id").jsonPrimitive.content)
            val oldMesh = partition.model.rig.puppet.drawables.single { it.id == firstId }.mesh!!
            val newMesh = remeshed.model.rig.puppet.drawables.single { it.id == firstId }.mesh!!
            assertFalse(oldMesh.positions.contentEquals(newMesh.positions))
            assertEquals(newMesh.vertexCount, remeshed.document.rigEdits.simEdits.single().bake!!.vertexCounts[firstId.raw])
            val expected = RasterMeshJournal.replay(partition.model.rig.puppet, replacement)
            val regeneratedReplay = builder.build(remeshed.document).rig.puppet
            for (value in listOf(-1f, 0f, 1f)) {
                val pose = mapOf(ParameterId("PartitionAxis") to value, ParameterId("PartitionMode") to value * 20f)
                val a = evaluator.evaluate(expected, pose)
                for (puppet in listOf(remeshed.model.rig.puppet, regeneratedReplay)) {
                    val b = evaluator.evaluate(puppet, pose)
                    a.worldPositions.forEach { (id, vertices) -> vertices.indices.forEach { assertEquals(vertices[it], b.worldPositions.getValue(id)[it], 0.00001f) } }
                    assertEquals(a.opacity, b.opacity)
                }
            }
            val histories = runtime.history()
            assertFalse(commands.execute(remeshed.projectId, remeshed.state, "Same settings", listOf(meshEdit), MutationAuthor.USER).applied)
            assertEquals(histories, runtime.history())
            runtime.checkout(remeshed.projectId, remeshed.state, partition.historyHead)
            assertEquals(partition.document, runtime.capture().document)
            runtime.checkout(remeshed.projectId, runtime.capture().state, remeshed.historyHead)
            assertContentEquals(newMesh.positions, runtime.capture().model.rig.puppet.drawables.single { it.id == firstId }.mesh!!.positions)
        }
    }

    @Test fun globalMeshChangesResetAndHiddenPiecesRetainOrderedPartitionBaselines() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val sourceId = root.model.rig.layerIdByDrawableId.entries.single { it.value == "islands" }.key
        val authored = commands.executeJournal(root.projectId, root.state, "Path", buildJsonArray { add(buildJsonObject {
            put("op", "path_put"); put("id", "OriginalPath"); put("target", "mesh:$sourceId")
            val points = root.model.rig.puppet.drawables.single { it.id.raw == sourceId }.mesh!!.positions
            putJsonArray("points") { add(buildJsonArray { add(points[0]); add(points[1]) }); add(buildJsonArray { add(points[2]); add(points[3]) }) }
        }) }, MutationAuthor.USER).capture
        val partition = WorkspacePartitionCommands(runtime).execute(authored.projectId, authored.state,
            listOf(operation("source_split_components")), "Partition", MutationAuthor.USER).commit.capture
        val firstId = partition.document.rigEdits.splitDrawableIds.getValue("first")
        val perLayer = WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject { put("layer_id", "first"); putJsonObject("changes") { put("outerMargin", 7) } })
        val global = WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("meshOuterMargin", 4); put("meshInteriorDensity", 6); put("alphaThreshold", 12) } })
        val reset = WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject { put("layer_id", "first"); put("reset", true) })
        val delete = WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", "second") })
        val result = commands.execute(partition.projectId, partition.state, "Ordered regeneration", listOf(perLayer, delete, global, reset), MutationAuthor.AGENT).capture
        assertFalse("first" in result.document.meshOverrides)
        assertEquals(4f, result.model.config.meshOuterMargin)
        assertEquals(partition.document.rigEdits.authoringJournal, result.document.rigEdits.authoringJournal.take(partition.document.rigEdits.authoringJournal.size))
        assertTrue(result.document.rigEdits.authoringJournal.count { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP } >= 4)
        assertEquals(1, result.model.rig.puppet.deformPaths.count { it.drawableId.raw == firstId })
        val restored = commands.execute(result.projectId, result.state, "Restore", listOf(WorkspaceDocumentOperation("layer_restore", buildJsonObject { put("layer_ids", buildJsonArray { add("second") }) })), MutationAuthor.USER).capture
        val replayed = builder.build(restored.document)
        assertEquals(partition.model.rig.puppet.drawables.map { it.id }, restored.model.rig.puppet.drawables.map { it.id })
        restored.model.rig.puppet.drawables.forEach { mesh -> assertContentEquals(mesh.mesh!!.positions, replayed.rig.puppet.drawables.single { it.id == mesh.id }.mesh!!.positions) }
        assertEquals(partition.document, runtime.history().selections.first { it.node.id == partition.historyHead }.snapshot)
    }

    @Test fun legacyPartitionsAcquireAMeshBaselineOnlyInTheNewCandidateAndCancellationDoesNotPublish() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val id = root.model.rig.layerIdByDrawableId.entries.single { it.value == "islands" }.key
        val authored = commands.executeJournal(root.projectId, root.state, "Rename", buildJsonArray { add(buildJsonObject {
            put("op", "structure"); putJsonArray("edits") { add(buildJsonObject { put("kind", "mesh"); put("id", id); put("action", "rename"); put("name", "Authored") }) }
        }) }, MutationAuthor.USER).capture
        val partition = WorkspacePartitionCommands(runtime).execute(authored.projectId, authored.state,
            listOf(operation("source_split_components")), "Partition", MutationAuthor.USER).commit.capture
        val legacy = partition.document.copy(rigEdits = partition.document.rigEdits.copy(authoringJournal =
            partition.document.rigEdits.authoringJournal.filterNot { it["op"]?.jsonPrimitive?.content == MeshGenerationBaseline.OP }))
        assertFalse(MeshGenerationBaseline.present(legacy.rigEdits))
        val model = builder.build(legacy)
        val changed = legacy.copy(meshOverrides = mapOf("first" to MeshSettings(outerMargin = 7f)))
        val normalized = builder.normalizeMeshEdits(changed, model)
        assertTrue(MeshGenerationBaseline.present(normalized.rigEdits))
        assertFalse(MeshGenerationBaseline.present(legacy.rigEdits))
        val rebuilt = builder.build(normalized)
        assertEquals(model.rig.puppet.drawables.map { it.id }, rebuilt.rig.puppet.drawables.map { it.id })
        val history = runtime.history()
        assertFailsWith<CancellationException> { PSD2LivePipeline().rebuildPreview(model, changed.config(), ProgressListener { _, _ -> throw CancellationException("Stop") }) }
        assertEquals(partition, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun componentGlueRetainsEveryWeldAndItsSequentialEvaluation() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
        val source = root.model.rig.puppet.drawables.single { root.model.rig.layerIdByDrawableId[it.id.raw] == "islands" }
        val other = root.model.rig.puppet.drawables.single { root.model.rig.layerIdByDrawableId[it.id.raw] == "other" }
        val welded = commands.execute(root.projectId, root.state, "Weld", listOf(WorkspaceDocumentOperation("canvas_glue", buildJsonObject {
            put("id", "IslandWeld"); put("mesh_a", source.id.raw); put("mesh_b", other.id.raw); put("distance", 256)
        })), MutationAuthor.USER).capture
        val before = commands.executeCandidate(welded.projectId, welded.state, "Glue role", MutationAuthor.USER,
            mutation = { document, model -> document.copy(rigEdits = document.rigEdits.copy(simEdits = listOf(
                RigSimEdit("OtherBody", "Other", SimKind.CLOTH, listOf(other.id.raw), autoBake = false,
                    glueRoles = mapOf(glueKey(model.rig.puppet.glues.single()) to GlueRole.PIN)))) ) }).capture
        val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state,
            listOf(operation("source_split_components")), "Bound components", MutationAuthor.USER).commit.capture
        val original = before.model.rig.puppet.glues.single()
        val glues = result.model.rig.puppet.glues
        assertEquals(original.pairs.size, glues.sumOf { it.pairs.size })
        assertEquals("IslandWeld", glues.first().id)
        assertEquals(glues.map { it.id }.distinct().size, glues.size)
        assertTrue(glues.all { it.meshA != source.id && it.meshB != source.id })
        assertEquals(glues.map(::glueKey).toSet(), result.document.rigEdits.simEdits.single().glueRoles.keys)
        assertTrue(result.document.rigEdits.simEdits.single().glueRoles.values.all { it == GlueRole.PIN })
        val evaluation = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(result.model.rig.puppet, emptyMap())
        val old = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(before.model.rig.puppet, emptyMap())
        assertContentEquals(old.worldPositions.getValue(other.id), evaluation.worldPositions.getValue(other.id))
        val record = SourcePartitionJournal.commands(result.document.rigEdits).single()
        SourcePartitionJournal.pieces(record).forEach { piece ->
            val id = DrawableId(piece.getValue("id").jsonPrimitive.content)
            piece.getValue("sources").jsonArray.forEachIndexed { index, row ->
                val vertex = row.jsonArray.single().jsonPrimitive.int
                for (axis in 0..1) assertEquals(old.worldPositions.getValue(source.id)[vertex * 2 + axis],
                    evaluation.worldPositions.getValue(id)[index * 2 + axis], 0.00001f)
            }
        }
        val owners = record.getValue("owners").jsonArray.map { it.jsonPrimitive.int }
        val first = owners.indices.filter { owners[it] == 0 }; val second = owners.indices.filter { owners[it] == 1 }
        val sequence = listOf(first[0], second[0], first[1], second[1])
        val baseline = result.model.baseRig.puppet.let { model -> model.copy(
            drawables = model.drawables.map { if (it.id == source.id) it.copy(isVisible = true) else it },
            glues = listOf(Glue(source.id, other.id, sequence.map { GluePair(it, 0, 0.4f, 0.6f) }, intensity = 0.7f, id = "SequentialWeld"))) }
        val partitioned = SourcePartitionJournal.apply(baseline, record)
        assertEquals(4, partitioned.glues.size)
        val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
        val expected = evaluator.evaluate(baseline, emptyMap()); val actual = evaluator.evaluate(partitioned, emptyMap())
        assertContentEquals(expected.worldPositions.getValue(other.id), actual.worldPositions.getValue(other.id))
        SourcePartitionJournal.pieces(record).forEach { piece ->
            val id = DrawableId(piece.getValue("id").jsonPrimitive.content)
            piece.getValue("sources").jsonArray.forEachIndexed { index, row ->
                val vertex = row.jsonArray.single().jsonPrimitive.int
                for (axis in 0..1) assertEquals(expected.worldPositions.getValue(source.id)[vertex * 2 + axis],
                    actual.worldPositions.getValue(id)[index * 2 + axis], 0.00001f)
            }
        }
        val remeshed = commands.execute(result.projectId, result.state, "Regenerate welded partition", listOf(
            WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject { put("layer_id", "first"); putJsonObject("changes") { put("outerMargin", 7); put("interiorDensity", 6) } })), MutationAuthor.USER).capture
        val migration = remeshed.document.rigEdits.authoringJournal.last { it["op"]?.jsonPrimitive?.content == RasterMeshJournal.OP }
        val migrated = evaluator.evaluate(RasterMeshJournal.replay(result.model.rig.puppet, migration), emptyMap())
        for (puppet in listOf(remeshed.model.rig.puppet, builder.build(remeshed.document).rig.puppet)) {
            val evaluated = evaluator.evaluate(puppet, emptyMap())
            migrated.worldPositions.forEach { (id, vertices) -> assertContentEquals(vertices, evaluated.worldPositions.getValue(id)) }
            puppet.glues.forEach { glue ->
                val a = puppet.drawables.single { it.id == glue.meshA }.mesh!!.vertexCount
                val b = puppet.drawables.single { it.id == glue.meshB }.mesh!!.vertexCount
                assertTrue(glue.pairs.all { it.indexA in 0 until a && it.indexB in 0 until b })
            }
        }
        assertEquals(result.document.rigEdits.simEdits.single().glueRoles, remeshed.document.rigEdits.simEdits.single().glueRoles)
    }

    @Test fun bothPartitionsPreservePixelsOtherBindingsAndStableIdentitiesAcrossReplayAndHistory() = runBlocking<Unit> {
        for (id in WorkspacePartitionEdits.supported) {
            val runtime = fixture(); val before = runtime.capture(); val source = before.document.source.layers.first()
            val oldPixels = source.raster.rgba.copyOf()
            val session = WorkspaceReadSession(runtime.read())
            val query = session.sourceMeshComponents("islands")
            validateOperationSchema(query, WorkspaceAuthoringResultSchemas.forOperation("source_get_components")!!)
            assertEquals(2, query.getValue("count").jsonPrimitive.int)
            val commands = WorkspacePartitionCommands(runtime)
            val result = commands.execute(before.projectId, before.state, listOf(operation(id)), "Partition", MutationAuthor.USER)
            assertEquals(listOf("first", "second"), result.mutation.affectedLayerIds)
            assertEquals(2, runtime.history().selections.size)
            val pieces = result.commit.capture.document.source.layers.filter { it.id.raw in result.mutation.affectedLayerIds }
            for (y in 0 until 48) for (x in 0 until 96) {
                val visible = pieces.filter { x in it.bounds.left until it.bounds.left + it.bounds.width && y in it.bounds.top until it.bounds.top + it.bounds.height }
                    .map { it to ((y - it.bounds.top) * it.raster.width + x - it.bounds.left) * 4 }.filter { (layer, offset) -> layer.raster.rgba[offset + 3] != 0.toByte() }
                val offset = (y * 96 + x) * 4
                assertEquals(if (oldPixels[offset + 3] == 0.toByte()) 0 else 1, visible.size)
                visible.forEach { (layer, pixel) -> for (channel in 0..3) assertEquals(oldPixels[offset + channel], layer.raster.rgba[pixel + channel]) }
            }
            assertContentEquals(oldPixels, source.raster.rgba)
            val puppet = result.commit.capture.model.rig.puppet
            assertPartitionDeformers(before.model.baseRig.puppet.deformers, result.commit.capture.model.baseRig.puppet.deformers)
            val other = before.model.rig.layerIdByDrawableId.entries.single { it.value == "other" }.key
            assertContentEquals(before.model.rig.puppet.drawables.single { it.id.raw == other }.mesh!!.positions,
                puppet.drawables.single { it.id.raw == other }.mesh!!.positions)
            val replayed = builder.build(result.commit.capture.document).rig.puppet
            assertEquals(puppet.drawables.map { it.id }, replayed.drawables.map { it.id })
            puppet.drawables.forEach { mesh -> assertContentEquals(mesh.mesh!!.positions, replayed.drawables.single { it.id == mesh.id }.mesh!!.positions) }
            assertEquals(query, session.sourceMeshComponents("islands"))
            assertFalse(WorkspaceReadSession(runtime.read()).sourceMeshComponents("islands").getValue("can_split").jsonPrimitive.boolean)
            runtime.checkout(before.projectId, result.mutation.state!!, before.historyHead)
            assertContentEquals(oldPixels, runtime.capture().document.source.layers.first().raster.rgba)
            runtime.checkout(before.projectId, runtime.capture().state, result.mutation.historyNodeId)
            assertEquals(puppet.drawables.map { it.id }, runtime.capture().model.rig.puppet.drawables.map { it.id })
            assertEquals(before.document, runtime.history().selections.first().snapshot)
        }
    }

    @Test fun batchMembersReferenceNewLayersAndLateFailurePublishesNoPartitionPrefix() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val split = operation("source_split_components")
        val preview = WorkspacePartitionEdits.apply(split, before.document, before.model)
        val mesh = preview.rigEdits.splitDrawableIds.getValue("first")
        val edits = listOf(split,
            WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject { put("layer_id", "first"); putJsonObject("changes") { put("outerMargin", 3) } }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "PartitionAxis"); put("name", "Partition axis") }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                put("op", "set"); put("target", "mesh:$mesh"); putJsonObject("key") { put("PartitionAxis", 1) }; putJsonObject("channels") { put("opacity", 0.4) }
            }) } }))
        val commands = WorkspaceDocumentCommands(runtime)
        val failure = assertFailsWith<WorkspaceBatchEditException> {
            commands.execute(before.projectId, before.state, "Bad partition", edits + WorkspaceDocumentOperation("layer_mesh_update", buildJsonObject {
                put("layer_id", "missing"); put("reset", true)
            }), MutationAuthor.AGENT)
        }
        assertEquals(4, failure.index); assertEquals(before, runtime.capture()); assertEquals(1, runtime.history().selections.size)
        val result = commands.execute(before.projectId, before.state, "Partition and edit", edits, MutationAuthor.AGENT)
        assertEquals(2, runtime.history().selections.size)
        val mutation = WorkspaceDocumentCommands.mutationResult(before, result, "Partition and edit", edits)
        assertTrue(mutation.affectedObjectIds.containsAll(listOf("layer:first", "layer:second", "mesh:$mesh")))
        assertEquals(3f, result.capture.document.meshOverrides.getValue("first").outerMargin)
        val pose = org.umamo.render.eval.CpuDeformationEvaluator().evaluate(result.capture.model.rig.puppet,
            mapOf(org.umamo.runtime.model.ParameterId("PartitionAxis") to 1f))
        assertEquals(0.4f, pose.opacity.getValue(org.umamo.runtime.model.DrawableId(mesh)), 0.00001f)
    }

    @Test fun severalGuiPartitionDecisionsUsePrecedingCandidatesAndCommitOneUserNode() = runBlocking<Unit> {
        val runtime = fixture(twoIslandLayers = true); val before = runtime.capture()
        val first = operation("source_split_components")
        val second = first.copy(request = JsonObject(first.request + buildJsonObject {
            put("layer_id", "other"); put("piece_ids", JsonArray(listOf("third", "fourth").map(::JsonPrimitive)))
        }))
        val result = WorkspacePartitionCommands(runtime).execute(before.projectId, before.state, listOf(first, second), "GUI partitions", MutationAuthor.USER)
        assertEquals(2, runtime.history().selections.size)
        assertEquals("user", runtime.history().selections.last().node.actor)
        assertEquals(listOf("first", "second", "third", "fourth"), result.mutation.affectedLayerIds)
        assertEquals(setOf("islands", "other"), result.commit.capture.document.deletedLayerIds)
        assertEquals(4, result.commit.capture.model.rig.puppet.drawables.size)
        assertPartitionDeformers(before.model.baseRig.puppet.deformers, result.commit.capture.model.baseRig.puppet.deformers)
        val rebuilt = builder.build(result.commit.capture.document)
        assertEquals(result.commit.capture.model.rig.puppet.drawables.map { it.id }, rebuilt.rig.puppet.drawables.map { it.id })
    }

    @Test fun staleMalformedIdsAuthoredLayersAndProjectionRejectionKeepOriginalDocument() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val commands = WorkspacePartitionCommands(runtime)
        assertFailsWith<WorkspaceConflict> { commands.execute(before.projectId, "stale", listOf(WorkspaceDocumentOperation("source_split_components", buildJsonObject {})), "Stale", MutationAuthor.USER) }
        val edit = operation("source_split_components")
        for (fields in listOf(buildJsonObject { put("piece_ids", JsonArray(listOf("other", "second").map(::JsonPrimitive))) },
            buildJsonObject { put("names", JsonArray(listOf("One", "Two", "Three").map(::JsonPrimitive))) })) {
            assertFailsWith<IllegalArgumentException> { commands.execute(before.projectId, before.state,
                listOf(edit.copy(request = JsonObject(edit.request + fields))), "Invalid", MutationAuthor.USER) }
        }
        assertFailsWith<IllegalStateException> { commands.execute(before.projectId, before.state, listOf(edit), "Reject", MutationAuthor.USER) { _, _, _ -> error("Projection rejected") } }
        assertEquals(before, runtime.capture()); assertEquals(1, runtime.history().selections.size)
        val target = "mesh:" + before.model.rig.layerIdByDrawableId.entries.first { it.value == "islands" }.key
        val changed = WorkspaceDocumentCommands(runtime).executeJournal(before.projectId, before.state, "Authored island", buildJsonArray { add(buildJsonObject {
            put("op", "structure"); putJsonArray("edits") { add(buildJsonObject { put("kind", "mesh"); put("id", target.removePrefix("mesh:")); put("action", "rename"); put("name", "Authored") }) }
        }) }, MutationAuthor.USER).capture
        assertTrue(WorkspaceReadSession(runtime.read()).sourceMeshComponents("islands").getValue("can_split").jsonPrimitive.boolean)
        val partition = commands.execute(changed.projectId, changed.state, listOf(edit), "Authored split", MutationAuthor.USER).commit.capture
        assertEquals(setOf("First", "Second"), partition.model.rig.puppet.drawables.filter { partition.model.rig.layerIdByDrawableId[it.id.raw] in setOf("first", "second") }.map { it.name }.toSet())
        assertEquals(partition.model.rig.puppet.drawables.map { it.id }, builder.build(partition.document).rig.puppet.drawables.map { it.id })
    }

    @Test fun partitionsInheritOverridesAndRetainUnrelatedAuthoringAndGenerationInputs() = runBlocking<Unit> {
        for (id in WorkspacePartitionEdits.supported) {
            val runtime = fixture(); val root = runtime.capture(); val commands = WorkspaceDocumentCommands(runtime)
            val other = root.model.rig.layerIdByDrawableId.entries.single { it.value == "other" }.key
            val configured = commands.executeCandidate(root.projectId, root.state, "Partition configuration", MutationAuthor.USER,
                mutation = { document, _ -> document.copy(
                    generationSource = document.source, meshSource = document.source,
                    meshOverrides = mapOf("islands" to MeshSettings(outerMargin = 3f)),
                    layerVisibility = mapOf("islands" to false),
                    layerOverrides = document.layerOverrides + ("islands" to LayerClassificationOverride(tag = SemanticTag.OBJECTS, side = Side.LEFT)),
                    settings = JsonObject(document.settings + ("drawOrderOverrides" to buildJsonObject { put("islands", 12) }))) }).capture
            val authored = commands.execute(configured.projectId, configured.state, "Other authoring", listOf(
                WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "OtherAxis"); put("name", "Other axis") }),
                WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") { add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:$other"); putJsonObject("key") { put("OtherAxis", 1) }; putJsonObject("channels") { put("opacity", 0.5) }
                }) } })), MutationAuthor.USER).capture
            val result = WorkspacePartitionCommands(runtime).execute(authored.projectId, authored.state, listOf(operation(id)), "Split with other authoring", MutationAuthor.USER).commit.capture
            for (piece in listOf("first", "second")) {
                assertEquals(authored.document.meshOverrides.getValue("islands"), result.document.meshOverrides.getValue(piece))
                assertEquals(false, result.document.layerVisibility.getValue(piece))
                assertEquals(12f, result.document.settings.getValue("drawOrderOverrides").jsonObject.getValue(piece).jsonPrimitive.float)
                assertEquals(if (id == "source_split_components" && piece == "second") Side.RIGHT else Side.LEFT,
                    result.document.layerOverrides.getValue(piece).side)
            }
            assertEquals(authored.document.generationSource, result.document.generationSource)
            assertEquals(authored.document.meshSource, result.document.meshSource)
            val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
            for (value in listOf(-1f, 0f, 1f)) {
                val pose = mapOf(org.umamo.runtime.model.ParameterId("OtherAxis") to value)
                val before = evaluator.evaluate(authored.model.rig.puppet, pose)
                val after = evaluator.evaluate(result.model.rig.puppet, pose)
                assertContentEquals(before.worldPositions.getValue(org.umamo.runtime.model.DrawableId(other)), after.worldPositions.getValue(org.umamo.runtime.model.DrawableId(other)))
                assertEquals(before.opacity.getValue(org.umamo.runtime.model.DrawableId(other)), after.opacity.getValue(org.umamo.runtime.model.DrawableId(other)))
            }
        }
    }

    @Test fun pixelAndIslandPreparationRespondToOriginalCoroutineCancellation() = runBlocking<Unit> {
        for (id in WorkspacePartitionEdits.supported) {
            val runtime = fixture(); val before = runtime.capture(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
            var checkpoints = 0
            val observer = object : WorkspaceRasterWork {
                override fun progress(fraction: Float, message: String) {}
                override fun checkpoint() { if (++checkpoints == 20) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) } }
            }
            val pending = async(Dispatchers.Default) { WorkspacePartitionCommands(runtime, observer).execute(before.projectId, before.state,
                listOf(operation(id)), "Cancelled partition", MutationAuthor.USER) }
            try {
                withTimeout(10000) { entered.await() }; pending.cancel(); release.countDown()
                assertFailsWith<CancellationException> { pending.await() }; pending.join()
                assertEquals(before, runtime.capture()); assertEquals(1, runtime.history().selections.size)
            } finally { release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun publicPartitionJobsCancelOrRejectForeignCommitDuringActualCandidateRebuild() = runBlocking<Unit> {
        for (id in WorkspacePartitionEdits.supported) for (cancel in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var block = false
            val runtime = fixture { document -> if (block) { entered.complete(Unit); release.await() }; builder.build(document) }
            val before = runtime.capture(); block = true
            WorkspaceOperations(Host(runtime)).use { operations ->
                val request = input(runtime, operation(id)); val job = operations.registry.invoke(id, request, agent).data
                withTimeout(10000) { entered.await() }
                val expected = if (cancel) {
                    operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent); before
                } else runtime.updateAuxiliary(before.projectId, before.state, buildJsonObject { put("foreign", true) })
                release.complete(Unit); val terminal = operations.wait(job)
                assertEquals(if (cancel) "cancelled" else "failed", terminal.getValue("status").jsonPrimitive.content)
                if (!cancel) assertEquals("state_conflict", terminal.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                assertEquals(expected, runtime.capture()); assertEquals(1, runtime.history().selections.size)
                assertEquals(job.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id"))
            }
        }
    }

    @Test fun jobsKeepStablePieceHandlesAfterLateCancellationOrRefreshFailureAndRetry() = runBlocking<Unit> {
        for (id in WorkspacePartitionEdits.supported) for (cancel in listOf(true, false)) {
            val runtime = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            WorkspaceOperations(Host(runtime) {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if (!cancel) throw java.io.IOException("Refresh failed")
            }).use { operations ->
                val definition = operations.registry.definition(id); assertTrue(definition.jobBacked && definition.batchable)
                val request = input(runtime, operation(id)); val job = operations.registry.invoke(id, request, agent).data
                withTimeout(10000) { entered.await() }
                if (cancel) operations.registry.invoke("job_cancel", buildJsonObject { put("id", job.getValue("id")); put("request_id", "cancel") }, agent)
                release.complete(Unit); val terminal = operations.wait(job)
                assertEquals("completed", terminal.getValue("status").jsonPrimitive.content)
                val result = terminal.getValue("result").jsonObject; validateOperationSchema(result, definition.jobResultSchema!!)
                assertEquals(runtime.capture().state, result.getValue("state").jsonPrimitive.content)
                assertEquals(listOf("first", "second"), result.getValue("layers").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(job.getValue("id"), operations.registry.invoke(id, request, agent).data.getValue("id")); assertEquals(terminal, operations.wait(job))
            }
        }
    }
}

internal fun assertPartitionDeformers(before: List<org.umamo.runtime.model.Deformer>, after: List<org.umamo.runtime.model.Deformer>) {
    assertEquals(before.map { it.id }, after.map { it.id })
    before.zip(after).forEach { (first, second) ->
        when (first) {
            is org.umamo.runtime.model.Deformer.Warp -> {
                val actual = assertIs<org.umamo.runtime.model.Deformer.Warp>(second)
                assertEquals(first, actual.copy(geometryGrid = first.geometryGrid))
                assertEquals(first.geometryGrid!!.axes.map { it.parameterId }, actual.geometryGrid!!.axes.map { it.parameterId })
                first.geometryGrid.axes.zip(actual.geometryGrid.axes).forEach { (expectedAxis, axis) -> assertContentEquals(expectedAxis.keys, axis.keys) }
                assertEquals(first.geometryGrid.cells.size, actual.geometryGrid.cells.size)
                first.geometryGrid.cells.zip(actual.geometryGrid.cells).forEach { (expectedCell, cell) ->
                    assertContentEquals(expectedCell.coordinate, cell.coordinate); assertContentEquals(expectedCell.form.controlPoints, cell.form.controlPoints)
                }
            }
            is org.umamo.runtime.model.Deformer.Rotation -> {
                val actual = assertIs<org.umamo.runtime.model.Deformer.Rotation>(second)
                assertEquals(first, actual.copy(geometryGrid = first.geometryGrid))
                assertEquals(first.geometryGrid!!.axes.map { it.parameterId }, actual.geometryGrid!!.axes.map { it.parameterId })
                first.geometryGrid.axes.zip(actual.geometryGrid.axes).forEach { (expectedAxis, axis) -> assertContentEquals(expectedAxis.keys, axis.keys) }
                assertEquals(first.geometryGrid.cells.size, actual.geometryGrid.cells.size)
                first.geometryGrid.cells.zip(actual.geometryGrid.cells).forEach { (expectedCell, cell) ->
                    assertContentEquals(expectedCell.coordinate, cell.coordinate)
                    assertEquals(expectedCell.form.originX, cell.form.originX); assertEquals(expectedCell.form.originY, cell.form.originY)
                    assertEquals(expectedCell.form.angle, cell.form.angle); assertEquals(expectedCell.form.scale, cell.form.scale)
                }
            }
        }
    }
}
