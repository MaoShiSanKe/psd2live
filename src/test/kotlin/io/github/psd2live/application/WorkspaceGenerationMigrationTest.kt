package io.github.psd2live.application

import org.junit.jupiter.api.Tag
import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.art.*
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshForm
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.restMeshesToCanvasSpace
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@Tag("slow")
class WorkspaceGenerationMigrationTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()
    private val pipeline = PSD2LivePipeline()
    private fun layer(id: String, order: Int, bounds: LayerBounds) = WorkspaceSourceLayer(LayerId(id), id, "", SourceLayerKind.Raster,
        true, order, bounds, 1f, false, LayerBlend.Normal, ChannelMask.ALL,
        LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4) { if (it % 4 == 3) -1 else 115 }), null, null, false)

    private suspend fun fixture(imported: Boolean = false): WorkspaceRuntime<RigPreviewModel> {
        val source = WorkspaceSourceArt(96, 80, listOf(layer("art", 2, LayerBounds(8, 8, 36, 44)), layer("other", 1, LayerBounds(54, 16, 28, 36))), emptyList())
        val config = PipelineConfig(atlasSize = 256, meshOnly = true, generateDeformers = false, mouthOutlineEnabled = false,
            generatePhysics = false, exportMoc3 = false, layerOverrides = source.layers.associate { it.id.raw to LayerClassificationOverride(tag = SemanticTag.OBJECTS) })
        val document = WorkspaceDocument(source, emptyMap(), emptySet(), config.layerOverrides, emptyMap(), RigEditOverlay.Empty, WorkspaceSettingsCodec.encode(config))
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        runtime.install(runtime.state.value.state, "generation", document, builder.build(document))
        var root = runtime.capture()
        var drawable = art(root.model)
        val commands = WorkspaceDocumentCommands(runtime)
        root = commands.execute(root.projectId, root.state, "Author generation input", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Shape"); put("name", "Shape"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Blend"); put("name", "Blend"); put("kind", "blend_shape") }),
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject { put("id", "AuthoredParent"); put("name", "Authored parent"); put("rows", 2); put("columns", 2); putJsonArray("meshes") { add(drawable.id.raw) } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for (axis in listOf("Shape", "Blend")) add(buildJsonObject {
                    put("op", "set"); put("target", "mesh:${drawable.id.raw}"); putJsonObject("key") { put(axis, 1) }
                    putJsonObject("geometry") { put("positionDeltas", JsonArray(List(drawable.mesh!!.positions.size) { JsonPrimitive(kotlin.math.sin(it.toDouble()).toFloat() * 0.025f) })) }
                    putJsonObject("channels") { put("opacity", if (axis == "Shape") 0.6f else 0.8f) }
                })
            } })
        ), MutationAuthor.USER).capture
        root = commands.execute(root.projectId, root.state, "Author parent interpolation", listOf(
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Bend"); put("name", "Bend"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("parameter_create", buildJsonObject { put("parameter_id", "Turn"); put("name", "Turn"); put("min", -1); put("max", 1) }),
            WorkspaceDocumentOperation("canvas_rotation", buildJsonObject {
                put("id", "AuthoredRotation"); put("name", "Authored rotation"); put("add_to", "parent_of_deformer")
                put("deformer_id", "AuthoredParent"); put("preservePose", true); putJsonArray("origin") { add(24); add(32) }
            })
        ), MutationAuthor.USER).capture
        val warp = root.model.rig.puppet.deformers.single { it.id.raw == "AuthoredParent" } as Deformer.Warp
        val points = warp.geometryGrid!!.cells.first().form.controlPoints
        val rotation = root.model.rig.puppet.deformers.single { it.id.raw == "AuthoredRotation" } as Deformer.Rotation
        val pivot = rotation.geometryGrid!!.cells.first().form
        root = commands.execute(root.projectId, root.state, "Author warp rotation and blend shapes", listOf(
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                for (axis in listOf("Bend", "Blend")) add(buildJsonObject {
                    put("op", "set"); put("target", "warp:AuthoredParent"); putJsonObject("key") { put(axis, 1) }
                    putJsonObject("geometry") { put("controlPoints", JsonArray(points.mapIndexed { index, value ->
                        JsonPrimitive(value + if (index == 8) 2.5f else if (index == 9) -1.75f else 0f)
                    })) }
                })
                for (axis in listOf("Turn", "Blend")) add(buildJsonObject {
                    put("op", "set"); put("target", "rotation:AuthoredRotation"); putJsonObject("key") { put(axis, 1) }
                    putJsonObject("geometry") { put("originX", pivot.originX); put("originY", pivot.originY)
                        put("angle", if (axis == "Turn") 23f else 8f); put("scale", if (axis == "Turn") 1.1f else 0.95f) }
                })
            } })
        ), MutationAuthor.USER).capture
        if (imported) {
            val file = Cmo3Conversion.freshCmo3(restMeshesToCanvasSpace(root.model.rig.puppet),
                root.model.atlas.pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) }, root.model.rig.pageByDrawableId, "Synthetic generation", 0L, 0x42)
            val (input, importedConfig) = Cmo3ModelImport.prepare(Cmo3.write(file.model), Cmo3ImportMode.NEW, null, root.model.config)
            val importedDocument = WorkspaceDocument(input, importedConfig.layerVisibility, emptySet(), importedConfig.layerOverrides, importedConfig.parentOverrides,
                importedConfig.rigEdits, WorkspaceSettingsCodec.encode(importedConfig.copy(exportMoc3 = false)))
            runtime.install(root.state, "imported-generation", importedDocument, builder.build(importedDocument), discardUnsaved = true)
            root = runtime.capture()
        }
        drawable = art(root.model)
        val meshPoints = drawable.mesh!!.positions
        commands.execute(root.projectId, root.state, "Authored path and weights", listOf(
            WorkspaceDocumentOperation("path_put", buildJsonObject { put("id", "AuthorPath"); put("target", "mesh:${drawable.id.raw}"); putJsonArray("points") {
                add(buildJsonArray { add(meshPoints[0]); add(meshPoints[1]) }); add(buildJsonArray { add(meshPoints[meshPoints.size - 2]); add(meshPoints.last()) })
            } }),
            WorkspaceDocumentOperation("vertex_group_update", buildJsonObject { put("target", "mesh:${drawable.id.raw}"); put("name", "Pins"); put("kind", "pin"); put("rule", "fill"); put("value", 0.7f) })
        ), MutationAuthor.USER)
        return runtime
    }

    private fun art(model: RigPreviewModel) = model.rig.puppet.drawables.single { it.name == "art" }
    private fun classify(model: RigPreviewModel, type: String, parameter: String = "Door", switchId: Int = 0) =
        WorkspaceDocumentOperation("layer_classify", buildJsonObject {
            put("layer_id", model.rig.layerIdByDrawableId.getValue(art(model).id.raw)); put("type", type); put("parameter", parameter); put("switch_id", switchId)
        })
    private fun settings(meshOnly: Boolean) = WorkspaceDocumentOperation("settings_update", buildJsonObject {
        putJsonObject("changes") { put("meshOnly", meshOnly); put("generateDeformers", !meshOnly) }
    })
    private suspend fun execute(runtime: WorkspaceRuntime<RigPreviewModel>, vararg operations: WorkspaceDocumentOperation): WorkspaceCapture<RigPreviewModel> {
        val root = runtime.capture()
        return WorkspaceDocumentCommands(runtime).execute(root.projectId, root.state, "Generation migration", operations.toList(), MutationAuthor.USER).capture
    }
    private fun assertWorld(expected: PuppetModel, actual: PuppetModel, pose: Map<ParameterId, Float>, tolerance: Float = 0.001f) {
        val evaluator = CpuDeformationEvaluator()
        val before = evaluator.evaluate(expected, pose); val after = evaluator.evaluate(actual, pose)
        assertEquals(before.worldPositions.keys, after.worldPositions.keys)
        before.worldPositions.forEach { (id, points) ->
            val current = after.worldPositions.getValue(id)
            assertEquals(points.size, current.size)
            points.indices.forEach { assertEquals(points[it], current[it], tolerance, "${id.raw} component $it") }
            assertEquals(before.opacity.getValue(id), after.opacity.getValue(id), tolerance)
        }
    }

    @Test fun ordinaryAndImportedClassificationRetainsAuthorGeometryChannelsPathsWeightsAndStoredHistory() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val runtime = fixture(imported); val root = runtime.capture(); val old = art(root.model)
            val toggled = execute(runtime, classify(root.model, "toggle"))
            assertTrue(RigGenerationBaseline.present(toggled.document.rigEdits))
            assertEquals(old.parentDeformerId, art(toggled.model).parentDeformerId)
            assertEquals(root.document.rigEdits.authoringJournal, toggled.document.rigEdits.authoringJournal.take(root.document.rigEdits.authoringJournal.size))
            val on = mapOf(ParameterId("Shape") to 0.5f, ParameterId("Blend") to 0.5f,
                ParameterId("Bend") to 0.5f, ParameterId("Turn") to 0.5f, ParameterId("Door") to 1f)
            assertWorld(root.model.rig.puppet, toggled.model.rig.puppet, on)
            assertTrue(CpuDeformationEvaluator().evaluate(toggled.model.rig.puppet, on + (ParameterId("Door") to 0f)).opacity.getValue(old.id) < 0.01f)
            assertEquals(root.model.rig.puppet.deformPaths.map { it.id }, toggled.model.rig.puppet.deformPaths.map { it.id })
            assertContentEquals(root.model.rig.puppet.vertexGroups.single().weights, toggled.model.rig.puppet.vertexGroups.single().weights)
            val switched = execute(runtime, classify(toggled.model, "switch", switchId = 2))
            val parameter = switched.model.rig.puppet.parameters.single { it.id.raw == "Door" }
            assertEquals(2f, parameter.min); assertEquals(3f, parameter.max); assertEquals(2f, parameter.default)
            assertWorld(root.model.rig.puppet, switched.model.rig.puppet, on + (ParameterId("Door") to 2f))
            val count = runtime.history().selections.size
            assertFalse(WorkspaceDocumentCommands(runtime).execute(switched.projectId, switched.state, "Same classification", listOf(classify(switched.model, "switch", switchId = 2)), MutationAuthor.USER).applied)
            assertEquals(count, runtime.history().selections.size)
            runtime.checkout(switched.projectId, switched.state, root.historyHead)
            assertEquals(root.document, runtime.capture().document)
            runtime.checkout(switched.projectId, runtime.capture().state, switched.historyHead)
            val archive = temporary.resolve("${if (imported) "imported" else "ordinary"}.psd2live")
            ProjectRepository().save(ProjectSaveCapture(switched.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("${if (imported) "imported" else "ordinary"}-store"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            assertWorld(switched.model.rig.puppet, reopened.rig.puppet, on + (ParameterId("Door") to 2f))
            val output = pipeline.run(switched.document.source, "generation", temporary.resolve("output-$imported"), switched.document.config()).exportedFiles
            val exported = Cmo3ModelImport.read(Files.readAllBytes(output.single { it.path.toString().endsWith(".cmo3") }.path)).puppet
            assertWorld(switched.model.rig.puppet, exported, on + (ParameterId("Door") to 2f), 0.01f)
            fun render(model: RigPreviewModel) = WorkspaceViewRenderer.modelComposite(model, "generation", mapOf("Shape" to 1f, "Door" to 2f),
                model.rig.layerIdByDrawableId.values.toSet(), emptySet(), WorkspaceViewFrame.CanvasRect(Bounds(0f, 0f, 96f, 80f)),
                WorkspaceViewBackground.TRANSPARENT, WorkspaceViewOutputSpec(256)).png
            val directory = Path.of("build/generation-migration-visual/${if (imported) "imported" else "ordinary"}")
            Files.createDirectories(directory)
            val before = render(switched.model); val after = render(reopened)
            assertContentEquals(before, after)
            Files.write(directory.resolve("before-reopen.png"), before); Files.write(directory.resolve("after-reopen.png"), after)
        }
    }

    @Test fun modeRoundTripRetainsAuthorParentAndShapesAndLateFailureNeverPublishesThePrefix() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture(); val old = art(root.model)
        val full = execute(runtime, settings(false))
        assertEquals(old.parentDeformerId, art(full.model).parentDeformerId)
        assertTrue(full.model.rig.puppet.deformers.filterIsInstance<Deformer.Warp>().any { d ->
            d.geometryGrid?.axes.orEmpty().any { it.parameterId == StandardParameters.BODY_X }
        })
        assertTrue(art(full.model).geometryGrid!!.axes.none { it.parameterId == StandardParameters.BODY_X })
        val roundTrip = execute(runtime, settings(true))
        for (shape in listOf(0f, 0.5f, 1f)) for (body in listOf(-5f, 0f, 5f)) for (bend in listOf(0.25f, 0.75f))
            for (turn in listOf(0.25f, 0.75f)) assertWorld(root.model.rig.puppet, roundTrip.model.rig.puppet,
                mapOf(ParameterId("Shape") to shape, ParameterId("Blend") to 0.5f,
                    ParameterId("Bend") to bend, ParameterId("Turn") to turn, StandardParameters.BODY_X to body), 0.003f)
        assertWorld(roundTrip.model.rig.puppet, builder.build(roundTrip.document).rig.puppet, mapOf(ParameterId("Shape") to 1f))
        val history = runtime.history()
        val failure = assertFailsWith<WorkspaceBatchEditException> {
            execute(runtime, settings(false), WorkspaceDocumentOperation("layer_classify", buildJsonObject { put("layer_id", "missing"); put("type", "toggle") }))
        }
        assertEquals(1, failure.index); assertEquals(roundTrip, runtime.capture()); assertEquals(history, runtime.history())
    }

    @Test fun changedParameterDefaultsAndDeletedGeneratedAxesSurviveSeveralSemanticTransitions() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture()
        var current = execute(runtime, classify(root.model, "toggle"))
        current = execute(runtime, WorkspaceDocumentOperation("parameter_update", buildJsonObject {
            put("parameter_id", "Door"); put("min", -1); put("max", 4); put("default", 0.4f)
        }))
        current = execute(runtime, classify(current.model, "switch", switchId = 2))
        val desired = current.model.rig.puppet.parameters.single { it.id.raw == "Door" }
        assertEquals(-1f, desired.min); assertEquals(4f, desired.max); assertEquals(0.4f, desired.default)
        current = execute(runtime, settings(false))
        assertEquals(desired, current.model.rig.puppet.parameters.single { it.id.raw == "Door" })
        current = execute(runtime, settings(true))
        assertEquals(desired, current.model.rig.puppet.parameters.single { it.id.raw == "Door" })
        current = execute(runtime, WorkspaceDocumentOperation("parameter_delete", buildJsonObject { put("parameter_id", "Door") }))
        current = execute(runtime, settings(false))
        assertTrue(current.model.rig.puppet.parameters.none { it.id.raw == "Door" })
        current = execute(runtime, settings(true))
        assertTrue(current.model.rig.puppet.parameters.none { it.id.raw == "Door" })
        assertTrue(builder.build(current.document).rig.puppet.parameters.none { it.id.raw == "Door" })
    }

    @Test fun commonHeadBodyAndMouthAxesRemainFactoredAndProgressAndCancellationUseTheCommandContext() = runBlocking<Unit> {
        val runtime = fixture(); val root = runtime.capture()
        val completion = WorkspaceJobCompletion()
        val progress = mutableListOf<Float>()
        val operation = settings(false)
        val full = withContext(WorkspaceGenerationJobExecution("settings_update", completion) + WorkspaceJobContext { fraction, _ -> progress += fraction }) {
            WorkspaceGenerationCommands(runtime).execute(root.projectId, root.state, operation, "Enable automatic motion", MutationAuthor.USER).commit.capture
        }
        assertNotNull(completion.result)
        assertTrue(progress.zipWithNext().all { (before, after) -> before <= after }, "Generation progress regressed: $progress")
        var current = execute(runtime, WorkspaceDocumentOperation("layer_classify", buildJsonObject {
            put("layer_id", currentLayer(full.model)); put("type", "preset"); put("role", "mouth")
        }))
        val mesh = art(current.model)
        assertTrue(mesh.geometryGrid!!.axes.size <= 3, "Ancestor axes were flattened into the mesh")
        assertTrue(mesh.geometryGrid!!.cells.size < 1000, "Common automatic hierarchy expanded a whole-union grid")
        val pose = mapOf(ParameterId("Shape") to 0.5f, ParameterId("Blend") to 0.5f, ParameterId("Turn") to 0.5f, ParameterId("Bend") to 0.5f,
            StandardParameters.ANGLE_X to 15f, StandardParameters.ANGLE_Y to -15f, StandardParameters.ANGLE_Z to 15f,
            StandardParameters.BODY_X to 5f, StandardParameters.BODY_Y to -5f, StandardParameters.MOUTH_OPEN to 0.25f, StandardParameters.MOUTH_FORM to 0.5f)
        assertWorld(current.model.rig.puppet, builder.build(current.document).rig.puppet, pose, 0.003f)
        val before = current; val history = runtime.history(); val cancelled = Job(coroutineContext[Job])
        val cancellationCompletion = WorkspaceJobCompletion()
        assertFailsWith<CancellationException> {
            withContext(cancelled + WorkspaceGenerationJobExecution("settings_update", cancellationCompletion) + WorkspaceJobContext { _, message ->
                if (message == "Migrating generation bindings") cancelled.cancel()
            }) {
                WorkspaceGenerationCommands(runtime).execute(before.projectId, before.state, settings(true), "Cancelled migration", MutationAuthor.USER)
            }
        }
        assertNull(cancellationCompletion.result); assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
        assertFailsWith<WorkspaceConflict> {
            WorkspaceGenerationCommands(runtime).execute(before.projectId, root.state, settings(true), "Stale generation", MutationAuthor.USER)
        }
        assertEquals(before, runtime.capture())
    }

    private fun currentLayer(model: RigPreviewModel) = model.rig.layerIdByDrawableId.getValue(art(model).id.raw)

    @Test fun derivedLipsRetainAuthoringAcrossRemovalOwnerDeletionRestorationAndReopen() = runBlocking<Unit> {
        for (imported in listOf(false, true)) {
            val runtime = fixture(imported); val root = runtime.capture()
            var current = execute(runtime, WorkspaceDocumentOperation("layer_classify", buildJsonObject {
                put("layer_id", currentLayer(root.model)); put("type", "preset"); put("role", "mouth")
            }), WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") {
                put("meshOnly", false); put("generateDeformers", true); put("mouthOutlineEnabled", true)
            } }))
            val owner = currentLayer(current.model)
            val lipLayer = MouthLipLayer.idFor(owner, 0)
            val lipId = current.model.rig.layerIdByDrawableId.entries.single { it.value == lipLayer }.key
            val lip = current.model.rig.puppet.drawables.single { it.id.raw == lipId }
            current = execute(runtime,
                WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                    add(buildJsonObject {
                        put("op", "set"); put("target", "mesh:$lipId"); putJsonObject("key") { put("Blend", 1) }
                        putJsonObject("geometry") { put("positionDeltas", JsonArray(List(lip.mesh!!.positions.size) { JsonPrimitive(if (it % 2 == 0) 0.012f else -0.009f) })) }
                        putJsonObject("channels") { put("opacity", 0.7f) }
                    })
                } }),
                WorkspaceDocumentOperation("vertex_group_update", buildJsonObject { put("target", "mesh:$lipId"); put("name", "LipPins"); put("kind", "pin"); put("rule", "fill"); put("value", 0.65f) }),
                WorkspaceDocumentOperation("object_edit_appearance", buildJsonObject { putJsonArray("edits") {
                    add(buildJsonObject { put("action", "visibility"); put("kind", "mesh"); put("id", lipId); put("visible", false) })
                    add(buildJsonObject { put("action", "static"); put("kind", "mesh"); put("id", lipId); put("user_data", "Authored lip"); put("culling", true) })
                } })
            )
            val authored = current.model.rig.puppet.drawables.single { it.id.raw == lipId }
            current = execute(runtime, WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("mouthOutlineEnabled", false) } }))
            assertFalse(current.model.rig.puppet.drawables.single { it.id.raw == lipId }.isVisible)
            assertEquals(authored.blendShapes.map { it.parameterId }, current.model.rig.puppet.drawables.single { it.id.raw == lipId }.blendShapes.map { it.parameterId })
            current = execute(runtime, WorkspaceDocumentOperation("layer_soft_delete", buildJsonObject { put("layer_id", owner) }))
            assertTrue(current.model.rig.puppet.drawables.none { it.id.raw == lipId })
            current = execute(runtime, WorkspaceDocumentOperation("settings_update", buildJsonObject { putJsonObject("changes") { put("mouthOutlineEnabled", true); put("mouthThickness", 2.25f) } }))
            current = execute(runtime, WorkspaceDocumentOperation("layer_restore", buildJsonObject { putJsonArray("layer_ids") { add(owner) } }))
            val restored = current.model.rig.puppet.drawables.single { it.id.raw == lipId }
            assertEquals(authored.id, restored.id); assertFalse(restored.isVisible); assertEquals("Authored lip", restored.userData); assertTrue(restored.culling)
            assertEquals(authored.blendShapes.map { it.parameterId }, restored.blendShapes.map { it.parameterId })
            assertTrue(current.model.rig.puppet.vertexGroups.single { it.name == "LipPins" }.weights.all { kotlin.math.abs(it - 0.65f) < 0.001f })
            val replayed = builder.build(current.document)
            assertEquals(restored.id, replayed.rig.puppet.drawables.single { it.id.raw == lipId }.id)
            assertFalse(replayed.rig.puppet.drawables.single { it.id.raw == lipId }.isVisible)
            val pose = mapOf(ParameterId("Blend") to 0.5f, StandardParameters.MOUTH_OPEN to 0.25f, StandardParameters.MOUTH_FORM to 0.5f,
                StandardParameters.ANGLE_X to 15f, StandardParameters.BODY_X to 5f)
            assertWorld(current.model.rig.puppet, replayed.rig.puppet, pose, 0.003f)
            val archive = temporary.resolve("lips-$imported.psd2live")
            ProjectRepository().save(ProjectSaveCapture(current.projectId, runtime.history(), JsonObject(emptyMap()), null,
                WorkspaceStore(temporary.resolve("lips-store-$imported"))), archive)
            val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
            assertWorld(current.model.rig.puppet, reopened.rig.puppet, pose, 0.003f)
        }
    }

    @Test fun importedGuidesActivateOnlyRealArtworkAndKeepParentsMaterialChannelsAndEmptyObjects() = runBlocking<Unit> {
        val runtime = fixture(); val authored = runtime.capture()
        val file = Cmo3Conversion.freshCmo3(restMeshesToCanvasSpace(authored.model.rig.puppet),
            authored.model.atlas.pages.map { Cmo3Conversion.AtlasPage(it.png, it.image.width, it.image.height) },
            authored.model.rig.pageByDrawableId, "Synthetic guides", 0L, 0x42)
        val rootSource = file.model.root as CModelSource
        val sources = Cmo3Import.elementsOf((rootSource.drawableSourceSet as CDrawableSourceSet)._sources).filterIsInstance<CArtMeshSource>()
        sources.forEach { source ->
            source.positions = null
            Cmo3Import.elementsOf(source.keyforms).filterIsInstance<CArtMeshForm>().forEach { it.positions = null }
        }
        val (_, imported) = Cmo3ModelImport.prepare(Cmo3.write(file.model), Cmo3ImportMode.NEW, null, authored.model.config)
        val source = WorkspaceSourceArt(96, 80, authored.document.source.layers.map { layer ->
            if (layer.name != "other") layer else object : SourceLayer by layer {
                override val raster = LayerRaster(layer.raster.width, layer.raster.height, ByteArray(layer.raster.rgba.size))
            }
        }, emptyList())
        val config = imported.copy(rigEdits = imported.rigEdits.copy(importedLayerIds = authored.model.rig.layerIdByDrawableId),
            layerOverrides = authored.document.layerOverrides, generationSource = null, meshSource = null)
        val document = WorkspaceDocument(source, config.layerVisibility, emptySet(), config.layerOverrides, config.parentOverrides,
            config.rigEdits, WorkspaceSettingsCodec.encode(config))
        runtime.install(authored.state, "guides", document, builder.build(document), discardUnsaved = true)
        var current = runtime.capture(); val id = art(current.model).id
        assertNull(art(current.model).mesh)
        current = execute(runtime,
            WorkspaceDocumentOperation("object_edit_appearance", buildJsonObject { putJsonArray("edits") {
                add(buildJsonObject { put("action", "static"); put("kind", "mesh"); put("id", id.raw)
                    put("user_data", "Authored guide"); put("culling", true); put("opacity", 0.67f) })
            } }),
            WorkspaceDocumentOperation("keyform_apply", buildJsonObject { putJsonArray("changes") {
                add(buildJsonObject { put("op", "set"); put("target", "mesh:${id.raw}"); putJsonObject("key") { put("Shape", 1) }
                    putJsonObject("channels") { put("opacity", 0.4f) } })
            } })
        )
        val guide = art(current.model); val empty = current.model.rig.puppet.drawables.single { it.name == "other" }
        val transitioned = execute(runtime, classify(current.model, "toggle"))
        val activated = art(transitioned.model)
        assertNotNull(activated.mesh); assertEquals(guide.id, activated.id); assertEquals(guide.parentDeformerId, activated.parentDeformerId)
        assertEquals("Authored guide", activated.userData); assertTrue(activated.culling); assertEquals(guide.opacity, activated.opacity)
        assertEquals(guide.channelGrids.gridsByChannel.keys + FormChannel.OPACITY, activated.channelGrids.gridsByChannel.keys)
        val retained = transitioned.model.rig.puppet.drawables.single { it.name == "other" }
        assertNull(retained.mesh); assertEquals(empty.id, retained.id); assertEquals(empty.parentDeformerId, retained.parentDeformerId)
        assertEquals(empty.userData, retained.userData); assertEquals(empty.channelGrids, retained.channelGrids)
        val pose = mapOf(ParameterId("Shape") to 0.5f, ParameterId("Bend") to 0.5f, ParameterId("Turn") to 0.5f,
            ParameterId("Blend") to 0.5f, ParameterId("Door") to 1f)
        assertWorld(transitioned.model.rig.puppet, builder.build(transitioned.document).rig.puppet, pose, 0.003f)
        val archive = temporary.resolve("guides.psd2live")
        ProjectRepository().save(ProjectSaveCapture(transitioned.projectId, runtime.history(), JsonObject(emptyMap()), null,
            WorkspaceStore(temporary.resolve("guide-store"))), archive)
        val reopened = ProjectRepository().open(archive).use { builder.build(it.history.head().snapshot) }
        assertWorld(transitioned.model.rig.puppet, reopened.rig.puppet, pose, 0.003f)
        assertNull(reopened.rig.puppet.drawables.single { it.name == "other" }.mesh)
    }
}
