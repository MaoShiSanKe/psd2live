package io.github.psd2live.application

import io.github.psd2live.core.PhysicsPresets
import io.github.psd2live.core.RigPhysicsEdit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.UUID
import java.util.Collections
import java.util.prefs.Preferences

data class WorkspacePhysicsPresetEntry(val id: String, val preset: PhysicsPresets.Preset)
data class WorkspacePhysicsPresetSnapshot(val state: String, val entries: List<WorkspacePhysicsPresetEntry>)

/** Existing preferences are read without rewriting them; a new write preserves stable entry IDs. */
internal interface WorkspacePhysicsPresetStorage {
    fun load(): List<WorkspacePhysicsPresetEntry>
    fun write(entries: List<WorkspacePhysicsPresetEntry>)
}

internal class PreferencesPhysicsPresetStorage(private val node: Preferences) : WorkspacePhysicsPresetStorage {
    override fun load() = node.keys().filter { it.toIntOrNull() != null }.sortedBy { it.toInt() }.mapNotNull { key ->
        runCatching {
            val stored = node.get(key, "")
            val payload = if (stored.startsWith("chunks:")) {
                val count = stored.removePrefix("chunks:").toInt()
                require(count in 1..1024) { "Invalid physics preset chunk inventory" }
                (0 until count).joinToString("") { index -> requireNotNull(node.get("$key.part$index", null)) }
            } else stored
            val fields = Json.parseToJsonElement(payload).jsonObject
            val preset = PhysicsPresets.Preset.fromJson(fields)
            WorkspacePhysicsPresetEntry(fields["library_id"]?.jsonPrimitive?.content
                ?: UUID.nameUUIDFromBytes("${preset.kind}:${preset.name}".toByteArray(Charsets.UTF_8)).toString(), preset)
        }.getOrNull()
    }
    override fun write(entries: List<WorkspacePhysicsPresetEntry>) {
        // Validate all values before touching the preference node.
        val values = entries.map { JsonObject(it.preset.toJson() + ("library_id" to JsonPrimitive(it.id))).toString() }
        val previous = node.keys().associateWith { node.get(it, "") }
        try {
            node.clear()
            values.forEachIndexed { index, value ->
                if (value.length <= Preferences.MAX_VALUE_LENGTH) node.put(index.toString(), value)
                else {
                    val chunks = value.chunked(Preferences.MAX_VALUE_LENGTH)
                    chunks.forEachIndexed { part, chunk -> node.put("$index.part$part", chunk) }
                    node.put(index.toString(), "chunks:${chunks.size}")
                }
            }
            node.flush()
        } catch (failure: Exception) {
            runCatching { node.clear(); previous.forEach { (key, value) -> node.put(key, value) }; node.flush() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}

/** Process-owned reusable physics presets; UI and MCP share persistence, notifications and CAS. */
class WorkspacePhysicsPresetLibrary internal constructor(private val storage: WorkspacePhysicsPresetStorage) {
    private val lock = Any()
    private val instance = UUID.randomUUID().toString()
    private var version = 0L
    private val changes = MutableStateFlow(snapshotOf(storage.load().mapNotNull { entry -> runCatching {
        require(entry.id.isNotBlank() && !entry.id.startsWith("builtin:") && !entry.preset.builtin)
        entry.copy(preset = freeze(entry.preset))
    }.getOrNull() }.distinctBy { it.preset.kind to it.preset.name }.distinctBy { it.id }))
    private val notifications = changes.asStateFlow()
    val state: StateFlow<WorkspacePhysicsPresetSnapshot> get() = notifications
    fun snapshot(): WorkspacePhysicsPresetSnapshot = synchronized(lock) { changes.value }
    internal fun <T> captureCommit(action: () -> T): Pair<T, WorkspacePhysicsPresetSnapshot> = synchronized(lock) { action() to changes.value }

    fun save(expectedState: String, preset: PhysicsPresets.Preset): WorkspacePhysicsPresetEntry = synchronized(lock) {
        require(!preset.builtin) { "Built-in presets cannot be overwritten" }
        mutate(expectedState) { entries ->
            val existing = entries.firstOrNull { it.preset.kind == preset.kind && it.preset.name == preset.name }
            val entry = WorkspacePhysicsPresetEntry(existing?.id ?: UUID.randomUUID().toString(), freeze(preset))
            (if (existing == null) entries + entry else entries.map { if (it.id == existing.id) entry else it }) to entry
        }
    }

    fun rename(expectedState: String, id: String, name: String): WorkspacePhysicsPresetEntry = synchronized(lock) {
        mutate(expectedState) { entries ->
            val current = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Physics preset not found: $id")
            val next = current.copy(preset = current.preset.copy(name = name.trim()))
            entries.filterNot { it.id != id && it.preset.kind == next.preset.kind && it.preset.name == next.preset.name }
                .map { if (it.id == id) next else it } to next
        }
    }

    fun delete(expectedState: String, id: String): Boolean = synchronized(lock) {
        mutate(expectedState) { entries -> (entries.filterNot { it.id == id }) to entries.any { it.id == id } }
    }

    private fun <T> mutate(expectedState: String, transform: (List<WorkspacePhysicsPresetEntry>) -> Pair<List<WorkspacePhysicsPresetEntry>, T>): T {
        val before = changes.value
        if (expectedState != before.state) throw WorkspaceConflict(expectedState, before.state)
        val (next, result) = transform(before.entries)
        if (next != before.entries) {
            storage.write(next)
            version++
            changes.value = snapshotOf(next)
        }
        return result
    }

    private fun freeze(preset: PhysicsPresets.Preset): PhysicsPresets.Preset {
        require(preset.inputs.size <= RigPhysicsEdit.MAX_LINKS) { "Too many physics preset inputs" }
        val frozen = PhysicsPresets.Preset.fromJson(preset.toJson())
        return frozen.copy(inputs = Collections.unmodifiableList(frozen.inputs), segments = Collections.unmodifiableList(frozen.segments))
    }
    private fun snapshotOf(entries: List<WorkspacePhysicsPresetEntry>): WorkspacePhysicsPresetSnapshot {
        val frozen = entries.map { it.copy(preset = freeze(it.preset)) }
        val payload = JsonArray(frozen.map { JsonObject(it.preset.toJson() + ("id" to JsonPrimitive(it.id))) }).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return WorkspacePhysicsPresetSnapshot("physics-presets:$instance:$version:$hash", Collections.unmodifiableList(frozen))
    }

    companion object {
        val shared: WorkspacePhysicsPresetLibrary by lazy {
            WorkspacePhysicsPresetLibrary(PreferencesPhysicsPresetStorage(Preferences.userRoot().node("io.github.psd2live.settings/physics-presets")))
        }
    }
}
