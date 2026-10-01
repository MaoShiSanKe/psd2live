package io.github.psd2live.core

import org.umamo.edit.withParametersSyncedFromTree

import io.github.psd2live.agent.AgentWorkspaceDocument
import io.github.psd2live.agent.AgentWorkspaceStore
import io.github.psd2live.agent.ViewModelAgentWorkspace
import io.github.psd2live.agent.MutationAuthor
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.github.psd2live.history.WorkspaceHistoryTree
import org.junit.jupiter.api.io.TempDir
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3.Cmo3Conversion
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.runtime.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class Cmo3ModelImportTest {
    @TempDir lateinit var temp: Path

    private fun model(vararg names: String): PuppetModel {
        val drawables = names.mapIndexed { index, name ->
            Drawable(DrawableId(name), name, null, BlendMode.Normal, emptyList(),
                DrawableMesh(floatArrayOf(2f + index, 2f, 12f + index, 2f, 2f + index, 12f),
                    floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)), null)
        }
        return PuppetModel(listOf(Parameter(ParameterId("ParamCustom"), "Custom", -1f, 1f, 0f)),
            emptyList(), emptyList(), drawables, drawables.map { OrgChild.Drawable(it.id) }, null,
            canvasWidth = 32f, canvasHeight = 32f)
    }

    private fun bytes(model: PuppetModel, color: Int = 0xffcc3355.toInt(), physics: List<RigPhysicsEdit> = emptyList()): ByteArray {
        val rgba = ByteArray(8 * 8 * 4)
        for (i in rgba.indices step 4) {
            rgba[i] = (color ushr 16).toByte(); rgba[i + 1] = (color ushr 8).toByte()
            rgba[i + 2] = color.toByte(); rgba[i + 3] = (color ushr 24).toByte()
        }
        val converted = Cmo3Conversion.freshCmo3(model,
            listOf(Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(8, 8, rgba)), 8, 8)),
            model.drawables.associate { it.id.raw to 0 }, "test", 0L, 0x42)
        if (physics.isNotEmpty()) Cmo3PhysicsInjector.inject(converted.model.root as CModelSource, physics, 30)
        return Cmo3.write(converted.model)
    }

    private fun preview(bytes: ByteArray, current: RigPreviewModel? = null, mode: Cmo3ImportMode = Cmo3ImportMode.NEW): RigPreviewModel {
        val (source, config) = Cmo3ModelImport.prepare(bytes, mode, current, current?.config ?: PipelineConfig())
        return PSD2LivePipeline().buildPreview(source, config)
    }

    @Test fun newImportContainsOnlyFileContentAndDoesNotCreatePresetsOnRebuildOrExport() {
        val old = preview(bytes(model("old", "shared")))
        val fresh = preview(bytes(model("shared")), old)
        val rebuilt = PSD2LivePipeline().rebuildPreview(fresh, fresh.config.copy(meshSpacing = 60))
        for (p in listOf(fresh, rebuilt)) {
            assertEquals(listOf("shared"), p.rig.puppet.drawables.map { it.id.raw })
            assertEquals(listOf("ParamCustom"), p.rig.puppet.parameters.map { it.id.raw })
            assertTrue(p.rig.puppet.deformers.isEmpty())
            assertFalse(p.config.rigEdits.skeleton!!.enabled)
            assertTrue(PhysicsCatalog.groups(p.analysis, p.config, setOf("ParamCustom")).isEmpty())
        }
        val result = PSD2LivePipeline().run(fresh.analysis.source, "import.cmo3", temp, fresh.config)
        assertTrue(result.exportedFiles.none { it.path.toString().endsWith(".motion3.json") || it.path.toString().endsWith(".physics3.json") })
        val reopened = Cmo3Import.fromModelSource(Cmo3.read(Files.readAllBytes(temp.resolve("import.cmo3"))).root as CModelSource)
        assertEquals(listOf("ParamCustom"), reopened.parameters.map { it.id.raw })
        assertEquals(listOf("shared"), reopened.drawables.map { it.id.raw })
    }

    @Test fun replacementPreservesAbsentObjectsAndUsesIncomingParametersMeshesAndTextures() {
        val old = preview(bytes(model("old", "shared")))
        val incoming = model("shared", "added").copy(parameters = listOf(Parameter(ParameterId("ParamNew"), "New", 0f, 1f, 0f)))
        val replaced = preview(bytes(incoming, 0xff2255cc.toInt()), old, Cmo3ImportMode.REPLACE)
        assertEquals(setOf("old", "shared", "added"), replaced.rig.puppet.drawables.map { it.id.raw }.toSet())
        assertEquals(setOf("ParamCustom", "ParamNew"), replaced.rig.puppet.parameters.map { it.id.raw }.toSet())
        assertEquals(setOf("old", "shared", "added"), replaced.analysis.source.layers.map { it.id.raw }.toSet())
        fun pixel(p: RigPreviewModel, id: String) = p.atlas.pages[p.rig.pageByDrawableId.getValue(id)].image.getRGB(0, 0)
        assertEquals(pixel(old, "old"), pixel(replaced, "old"))
        assertNotEquals(pixel(old, "shared"), pixel(replaced, "shared"))
    }

    @Test fun replacementRetainsChildrenAbsentFromImportedFolderAndMovesImportedChildrenOnce() {
        val parent = PartId("Folder")
        val old = model("old", "shared").copy(parts = listOf(Part(parent, "Old folder",
            listOf(OrgChild.Drawable(DrawableId("old")), OrgChild.Drawable(DrawableId("shared"))))),
            rootChildren = listOf(OrgChild.Part(parent)))
        val incoming = model("shared").copy(parts = listOf(Part(parent, "New folder", emptyList())),
            rootChildren = listOf(OrgChild.Drawable(DrawableId("shared")), OrgChild.Part(parent)))
        val merged = Cmo3ModelImport.merge(old, incoming)
        assertEquals("New folder", merged.parts.single().name)
        assertEquals(listOf(OrgChild.Drawable(DrawableId("old"))), merged.parts.single().children)
        assertEquals(listOf(OrgChild.Drawable(DrawableId("shared")), OrgChild.Part(parent)), merged.rootChildren)
    }

    @Test fun physicsIsImportedWithItsRateAndSurvivesHistoryPersistence() {
        val model = model("mesh").copy(parameters = model().parameters + Parameter(ParameterId("ParamOut"), "Output", -1f, 1f, 0f))
        val rule = RigPhysicsEdit("CustomPhysics", "Custom physics", listOf(PhysicsInput("ParamCustom")),
            listOf(PhysicsOutput("ParamOut", 1, 0.5f)), listOf(PhysicsSegment(5f)))
        val imported = preview(bytes(model, physics = listOf(rule)))
        assertEquals(listOf(rule), imported.config.rigEdits.physicsEdits)
        assertEquals(30, imported.config.rigEdits.physicsFps)
        val document = AgentWorkspaceDocument(imported.analysis.source, emptyMap(), emptySet(), emptyMap(), emptyMap(), imported.config.rigEdits)
        val history = WorkspaceHistoryTree(document, "revision", "hash")
        val store = AgentWorkspaceStore(temp)
        store.persistHistory("project", history.state())
        val restored = store.loadHistory("project")!!.head().snapshot
        assertEquals(document.rigEdits, restored.rigEdits)
        val rebuilt = PSD2LivePipeline().buildPreview(restored.source, imported.config.copy(rigEdits = restored.rigEdits))
        assertEquals(listOf("mesh"), rebuilt.rig.puppet.drawables.map { it.id.raw })
        assertTrue(rebuilt.hasRuntimePhysics)
    }

    @Test fun emptyCmo3ImportsWithoutSynthesizingArtworkOrParameters() {
        val converted = Cmo3Conversion.freshCmo3(model().copy(parameters = emptyList()), emptyList(), emptyMap(), "empty", 0L, 0x42)
        val empty = preview(Cmo3.write(converted.model))
        assertTrue(empty.analysis.layers.isEmpty())
        assertTrue(empty.rig.puppet.parameters.isEmpty())
        assertTrue(empty.rig.puppet.drawables.isEmpty())
    }

    @Test fun bundledCmo3ModelsImportTheirExactObjectInventory() {
        for (name in listOf("ds", "tml")) {
            val input = Files.readAllBytes(Path.of("examples/$name/moc3-cmo3-output/$name.cmo3"))
            val expected = Cmo3ModelImport.read(input).puppet
            val imported = preview(input)
            assertEquals(expected.withParametersSyncedFromTree().parameters, imported.rig.puppet.parameters)
            assertEquals(expected.parts.map { it.id }, imported.rig.puppet.parts.map { it.id })
            assertEquals(expected.deformers.map { it.id }, imported.rig.puppet.deformers.map { it.id })
            assertEquals(expected.drawables.map { it.id }, imported.rig.puppet.drawables.map { it.id })
            assertTrue(imported.atlas.pages.isNotEmpty())
        }
    }

    @Test fun editorImportCanBeUndoneAndSavedProjectReopensWithoutPresets() = runBlocking {
        val input = temp.resolve("first.cmo3")
        val replacement = temp.resolve("second.cmo3")
        val archive = temp.resolve("project.psd2live")
        Files.write(input, bytes(model("first")))
        Files.write(replacement, bytes(model("second")))
        suspend fun waitFor(predicate: () -> Boolean) = withTimeout(30_000) {
            while (!predicate()) delay(20)
        }
        PSD2LiveViewModel().use { vm ->
            ViewModelAgentWorkspace(vm, temp.resolve("workspace")).use { workspace ->
                vm.attachAgentWorkspace(workspace)
                vm.importCmo3(input, Cmo3ImportMode.NEW)
                waitFor { vm.state.value.previewModel != null && !vm.state.value.isAnalyzing }
                assertNull(vm.state.value.errorMessage)
                assertTrue(vm.physicsGroups().isEmpty())
                val first = workspace.snapshot().historyHeadNodeId!!
                val originalPreview = vm.state.value.previewModel
                vm.importCmo3(replacement, Cmo3ImportMode.REPLACE)
                waitFor { vm.state.value.previewModel !== originalPreview && !vm.state.value.isAnalyzing }
                assertNull(vm.state.value.errorMessage)
                val second = workspace.snapshot().historyHeadNodeId!!
                assertNotEquals(first, second)
                workspace.checkoutHistory(first, MutationAuthor.USER)
                assertEquals(listOf("first"), vm.state.value.previewModel!!.rig.puppet.drawables.map { it.id.raw })
                workspace.checkoutHistory(second, MutationAuthor.USER)
                assertEquals(setOf("first", "second"), vm.state.value.previewModel!!.rig.puppet.drawables.map { it.id.raw }.toSet())
                vm.saveProjectNow(archive)
                assertFalse(vm.state.value.projectDirty)
            }
        }
        PSD2LiveViewModel().use { vm ->
            ViewModelAgentWorkspace(vm, temp.resolve("reopened-workspace")).use { workspace ->
                vm.attachAgentWorkspace(workspace)
                vm.openProject(archive)
                waitFor { vm.state.value.previewModel != null }
                assertNull(vm.state.value.errorMessage)
                assertEquals(setOf("first", "second"), vm.state.value.previewModel!!.rig.puppet.drawables.map { it.id.raw }.toSet())
                assertEquals(listOf("ParamCustom"), vm.state.value.previewModel!!.rig.puppet.parameters.map { it.id.raw })
                assertTrue(vm.physicsGroups().isEmpty())
                assertTrue(vm.state.value.loadedInputPath!!.endsWith("original.cmo3"))
            }
        }
    }
}
