package io.github.psd2live.ui.state

import io.github.psd2live.application.WorkspacePhysicsPresetLibrary
import io.github.psd2live.application.WorkspacePhysicsPresetEntry
import io.github.psd2live.core.PhysicsPresets

/** Compose observes the same process-owned library that external agents edit. */
object PhysicsPresetStore {
	private val library get() = WorkspacePhysicsPresetLibrary.shared
	val state get() = library.state
	val presets get() = library.snapshot().entries.map { it.preset }

	/** The built-in presets of [kind], then the user's. */
	fun all(kind: PhysicsPresets.Kind): List<PhysicsPresets.Preset> =
		PhysicsPresets.builtins(kind) + presets.filter { it.kind == kind }

	/** Saves [preset], replacing the user's preset of the same kind and name. */
	fun save(expectedState: String, preset: PhysicsPresets.Preset): WorkspacePhysicsPresetEntry = library.save(expectedState, preset)

	fun rename(expectedState: String, id: String, name: String): WorkspacePhysicsPresetEntry = library.rename(expectedState, id, name)

	fun delete(expectedState: String, id: String) { library.delete(expectedState, id) }
}
