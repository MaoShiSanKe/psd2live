package io.github.psd2live.application

import io.github.psd2live.core.*
import io.github.psd2live.project.MutationAuthor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class WorkspacePhysicsPresetLibraryTest {
    private class Memory(initial: List<WorkspacePhysicsPresetEntry> = emptyList()) : WorkspacePhysicsPresetStorage {
        var entries = initial
        var writes = 0
        var failure: Exception? = null
        override fun load() = entries
        override fun write(entries: List<WorkspacePhysicsPresetEntry>) {
            failure?.let { throw it }
            this.entries = entries; writes++
        }
    }
    private fun preset(name: String = "Input") = PhysicsPresets.Preset(PhysicsPresets.Kind.INPUT, name, listOf(PhysicsInput("Drive")))

    @Test fun stableIdsOverwriteRenameCollisionsAndNoOpsPreserveTheSharedLibraryState() {
        val storage = Memory(); val library = WorkspacePhysicsPresetLibrary(storage)
        val firstState = library.snapshot().state
        val first = library.save(firstState, preset())
        val savedState = library.snapshot().state
        assertNotEquals(firstState, savedState)
        assertEquals(first.id, library.save(savedState, preset()).id)
        assertEquals(savedState, library.snapshot().state); assertEquals(1, storage.writes)
        val second = library.save(savedState, preset("Second"))
        val renamed = library.rename(library.snapshot().state, first.id, "Second")
        assertEquals(first.id, renamed.id)
        assertEquals(listOf(renamed), library.snapshot().entries)
        assertFalse(library.delete(library.snapshot().state, second.id))
        val beforeDelete = library.snapshot().state
        assertTrue(library.delete(beforeDelete, renamed.id))
        assertTrue(library.snapshot().entries.isEmpty())
        // Returning to the original content must not validate an old token (ABA).
        assertNotEquals(firstState, library.snapshot().state)
        assertFailsWith<WorkspaceConflict> { library.save(firstState, preset("Stale")) }
        val restored = WorkspacePhysicsPresetLibrary(storage)
        assertEquals(library.snapshot().entries, restored.snapshot().entries)
        assertNotEquals(library.snapshot().state, restored.snapshot().state)
    }

    @Test fun persistenceFailureAndRejectedBuiltinsLeaveMemoryAndNotificationsUnchanged() {
        val storage = Memory(); val library = WorkspacePhysicsPresetLibrary(storage)
        val before = library.snapshot()
        storage.failure = java.io.IOException("Disk failed")
        assertFailsWith<java.io.IOException> { library.save(before.state, preset()) }
        assertEquals(before, library.state.value); assertEquals(0, storage.writes)
        storage.failure = null
        assertFailsWith<IllegalArgumentException> { library.save(before.state, preset().copy(builtin = true)) }
        assertEquals(before, library.snapshot())
        val mutable = mutableListOf(PhysicsInput("Drive"))
        val captured = library.save(before.state, preset().copy(inputs = mutable))
        mutable.clear()
        assertEquals(listOf("Drive"), captured.preset.inputs.map { it.parameter })
        assertEquals(captured, library.snapshot().entries.single())
    }

    @Test fun legacyPreferenceEntriesKeepDataAndIdsAcrossChunkedPersistenceAndReload() {
        val node = Preferences.userRoot().node("io.github.psd2live.tests/physics-presets/${UUID.randomUUID()}")
        try {
            val original = preset()
            node.put("0", original.toJson().toString())
            val storage = PreferencesPhysicsPresetStorage(node)
            val library = WorkspacePhysicsPresetLibrary(storage)
            val legacy = library.snapshot().entries.single()
            assertEquals(original, legacy.preset)
            assertEquals(original.toJson().toString(), node.get("0", ""))
            val large = preset("P".repeat(Preferences.MAX_VALUE_LENGTH + 64))
            library.save(library.snapshot().state, large)
            val restored = WorkspacePhysicsPresetLibrary(storage)
            assertEquals(library.snapshot().entries, restored.snapshot().entries)
            assertEquals(legacy.id, restored.snapshot().entries.first().id)
            assertTrue(node.keys().any { ".part" in it })
        } finally { node.removeNode() }
    }

    @Test fun publicPresetOperationsUseStrictContractsDeduplicationAndLibraryCasWithoutAProject() = runBlocking<Unit> {
        val storage = Memory(); val library = WorkspacePhysicsPresetLibrary(storage)
        WorkspaceOperations(object : WorkspaceBackendStub() {}, library).use { operations ->
            val context = WorkspaceOperationContext(MutationAuthor.AGENT)
            suspend fun call(id: String, fields: JsonObject) = operations.registry.invoke(id, fields, context).data
            val listed = call("physics_preset_list", buildJsonObject { put("kind", "input") })
            assertTrue(listed.getValue("items").jsonArray.isNotEmpty())
            val request = buildJsonObject {
                put("request_id", "save-preset"); put("library_state", listed.getValue("library_state")); put("preset", preset().toJson())
            }
            val saved = call("physics_preset_put", request)
            assertEquals(saved, call("physics_preset_put", request)); assertEquals(1, storage.writes)
            val entryId = saved.getValue("entry").jsonObject.getValue("id")
            val renamed = call("physics_preset_rename", buildJsonObject {
                put("request_id", "rename-preset"); put("library_state", saved.getValue("library_state")); put("id", entryId); put("name", "Renamed")
            })
            assertEquals(entryId, renamed.getValue("entry").jsonObject.getValue("id"))
            assertFailsWith<WorkspaceConflict> { call("physics_preset_put", JsonObject(request + ("request_id" to JsonPrimitive("stale-preset")))) }
            assertFailsWith<WorkspaceValidationException> { call("physics_preset_put", JsonObject(request + ("unknown" to JsonPrimitive(true)))) }
            assertFailsWith<WorkspaceRequestReuse> { call("physics_preset_put", JsonObject(request + ("preset" to preset("Other").toJson()))) }
            assertFailsWith<IllegalArgumentException> { call("physics_preset_delete", buildJsonObject {
                put("request_id", "delete-builtin"); put("library_state", renamed.getValue("library_state")); put("id", "builtin:input:0")
            }) }
            val deleted = call("physics_preset_delete", buildJsonObject {
                put("request_id", "delete-preset"); put("library_state", renamed.getValue("library_state")); put("id", entryId)
            })
            assertTrue(deleted.getValue("applied").jsonPrimitive.boolean)
            assertEquals(4, operations.registry.definitions().count { it.id.startsWith("physics_preset_") })
            assertEquals(3, storage.writes)
        }
    }
}
