package io.github.psd2live.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.psd2live.core.PhysicsPresets
import java.util.prefs.Preferences

/**
 * The user's physics presets, shared by every project like Cubism Editor's: kept in the app's preferences,
 * one entry per preset so no single value outgrows the preferences' limit.
 */
object PhysicsPresetStore {
	private const val NODE = "io.github.psd2live.settings/physics-presets"

	private val node by lazy { runCatching { Preferences.userRoot().node(NODE) }.getOrNull() }

	var presets: List<PhysicsPresets.Preset> by mutableStateOf(load())
		private set

	/** The built-in presets of [kind], then the user's. */
	fun all(kind: PhysicsPresets.Kind): List<PhysicsPresets.Preset> =
		PhysicsPresets.builtins(kind) + presets.filter { it.kind == kind }

	/** Saves [preset], replacing the user's preset of the same kind and name. */
	fun save(preset: PhysicsPresets.Preset) {
		val index = presets.indexOfFirst { it.kind == preset.kind && it.name == preset.name }
		update(if (index < 0) presets + preset else presets.toMutableList().also { it[index] = preset })
	}

	fun rename(preset: PhysicsPresets.Preset, name: String): PhysicsPresets.Preset {
		val renamed = preset.copy(name = name.trim())
		update(presets.filterNot { it.kind == preset.kind && it.name == renamed.name && it != preset }.map { if (it == preset) renamed else it })
		return renamed
	}

	fun delete(preset: PhysicsPresets.Preset) = update(presets - preset)

	private fun update(next: List<PhysicsPresets.Preset>) {
		presets = next.filterNot { it.builtin }
		runCatching {
			val node = node ?: return
			node.clear()
			presets.forEachIndexed { i, p -> node.put("$i", p.toJson().toString()) }
			node.flush()
		}
	}

	private fun load(): List<PhysicsPresets.Preset> = runCatching {
		val node = node ?: return emptyList()
		node.keys().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.mapNotNull { key ->
			PhysicsPresets.listFromJson("[${node.get(key, "")}]").firstOrNull()
		}
	}.getOrDefault(emptyList())
}
