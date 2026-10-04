package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class WorkspaceHierarchyEditsTest {
    @TempDir lateinit var temporary: Path
    private val builder = WorkspacePreviewBuilder()

    /** One mesh under warp "child", itself under warp "parent"; the layer also carries an old v1 override. */
    private suspend fun fixture(): WorkspaceRuntime<RigPreviewModel> {
        val runtime = WorkspaceRuntime<RigPreviewModel>({ builder.build(it) })
        val root = simulationFixture(runtime)
        val layer = root.document.source.layers.single().id.raw
        val legacy = root.document.copy(parentOverrides = mapOf(layer to null))
        val installed = runtime.install(root.state, root.projectId, legacy, builder.build(legacy), discardUnsaved = true)
        val mesh = installed.model.rig.puppet.drawables.single().id.raw
        WorkspaceDocumentCommands(runtime).execute(installed.projectId, installed.state, "Warps", listOf(
            WorkspaceDocumentOperation("canvas_warp", buildJsonObject {
                put("id", "parent"); put("name", "Parent"); put("rows", 2); put("columns", 2); put("meshes", buildJsonArray { add(mesh) })
            }),
            WorkspaceDocumentOperation("rig_create_warp", buildJsonObject {
                put("id", "child"); put("name", "Child"); put("targets", buildJsonArray { add("mesh:$mesh") }); put("fit_local", true)
            })), MutationAuthor.USER)
        return runtime
    }

    private suspend fun reparent(runtime: WorkspaceRuntime<RigPreviewModel>, child: String, parent: String?): WorkspaceCommit<RigPreviewModel>? {
        val before = runtime.capture()
        val edit = WorkspaceHierarchyEdits.reparent(before.model.rig.puppet, child, parent) ?: return null
        return WorkspaceDocumentCommands(runtime).executeJournal(before.projectId, before.state, "Reparent",
            WorkspaceHierarchyEdits.journal(edit), MutationAuthor.USER)
    }

    @Test fun dragsBecomeStructureJournalEditsAfterOldParentOverrides() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture()
        val mesh = before.model.rig.puppet.drawables.single().id.raw
        val overrides = before.document.parentOverrides
        assertEquals("child", before.model.rig.puppet.drawables.single().parentDeformerId?.raw)
        assertEquals(buildJsonObject { put("action", "bind"); put("kind", "mesh"); put("id", mesh); put("parent_id", "parent"); put("space", "local") },
            WorkspaceHierarchyEdits.reparent(before.model.rig.puppet, mesh, "parent"))
        val bound = assertNotNull(reparent(runtime, mesh, "parent")).capture
        assertEquals("parent", bound.model.rig.puppet.drawables.single().parentDeformerId?.raw)
        val moved = assertNotNull(reparent(runtime, "child", null)).capture
        assertNull(moved.model.rig.puppet.deformers.single { it.id.raw == "child" }.parent)
        assertEquals("move", moved.document.rigEdits.authoringJournal.last().getValue("edits").jsonArray.single()
            .jsonObject.getValue("action").jsonPrimitive.content)
        // The v1 overrides are neither rewritten nor reordered; they still apply first on every rebuild.
        assertEquals(overrides, moved.document.parentOverrides)
        assertEquals(overrides.keys.toList(), moved.document.parentOverrides.keys.toList())
        val rebuilt = builder.build(moved.document).rig.puppet
        assertEquals(moved.model.rig.puppet.drawables.map { it.id to it.parentDeformerId }, rebuilt.drawables.map { it.id to it.parentDeformerId })
        assertEquals(moved.model.rig.puppet.deformers.map { it.id to it.parent }, rebuilt.deformers.map { it.id to it.parent })
        assertEquals(3, runtime.history().selections.size - 1)
        val store = WorkspaceStore(temporary); store.persistHistory(moved.projectId, runtime.history())
        val reopened = assertNotNull(WorkspaceStore(temporary).loadHistory(moved.projectId)).head().snapshot
        assertEquals(moved.revision, WorkspaceRevisions.of(reopened)); assertEquals(overrides, reopened.parentOverrides)
        assertEquals("parent", builder.build(reopened).rig.puppet.drawables.single().parentDeformerId?.raw)
    }

    @Test fun unchangedParentsAddNoHistoryAndInvalidDragsPublishNothing() = runBlocking<Unit> {
        val runtime = fixture(); val before = runtime.capture(); val history = runtime.history()
        val mesh = before.model.rig.puppet.drawables.single().id.raw
        assertNull(reparent(runtime, mesh, "child")); assertNull(reparent(runtime, "parent", null))
        assertFailsWith<IllegalArgumentException> { WorkspaceHierarchyEdits.reparent(before.model.rig.puppet, "missing", null) }
        // A cycle and a missing parent are rejected by the journal candidate before the CAS.
        assertFails { reparent(runtime, "parent", "child") }
        assertFails { reparent(runtime, mesh, "missing") }
        assertEquals(before, runtime.capture()); assertEquals(history, runtime.history())
    }
}
