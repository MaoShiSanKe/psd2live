package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsPresets
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PhysicsPresetStore
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography

/**
 * A preset line as in Cubism Editor: pick a preset and apply it to the group, or keep the group's current
 * inputs (or pendulums) as a preset of your own - save as new, overwrite, rename, delete. Built-in presets
 * can be applied and saved over as a new name, not changed.
 */
@Composable
internal fun PhysicsPresetBar(kind: PhysicsPresets.Kind, setting: RigPhysicsEdit, onApply: (PhysicsPresets.Preset) -> Unit) {
	val colors = LocalToolColors.current
	val presets = PhysicsPresetStore.all(kind)
	var chosenName by remember(kind) { mutableStateOf<Pair<Boolean, String>?>(null) }
	val chosen = presets.firstOrNull { chosenName == (it.builtin to it.name) } ?: presets.first()
	// Naming: null, or whether the typed name saves a new preset (true) or renames the chosen one (false).
	var naming by remember(kind) { mutableStateOf<Boolean?>(null) }
	var draft by remember(kind) { mutableStateOf("") }
	var menu by remember { mutableStateOf(false) }

	fun commit() {
		val name = draft.trim()
		if (name.isNotEmpty()) {
			val saved = if (naming == true) PhysicsPresets.capture(kind, name, setting).also(PhysicsPresetStore::save)
			else PhysicsPresetStore.rename(chosen, name)
			chosenName = false to saved.name
		}
		naming = null
	}

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("physics.preset"))
		if (naming != null) {
			CompactTextField(draft, { draft = it }, modifier = Modifier.weight(1f), height = 20.dp, selectAllOnFocus = true,
				placeholder = tr("physics.preset.namePlaceholder"), onCommit = ::commit, onFocusLost = { if (naming != null) commit() })
		} else {
			CompactDropdown(presets, chosen, { chosenName = it.builtin to it.name }, modifier = Modifier.weight(1f), height = 20.dp,
				itemLabel = { if (it.builtin) "${it.name} · ${tr("physics.preset.builtin")}" else it.name })
			CompactButton(tr("physics.preset.apply"), { onApply(chosen) }, height = 20.dp)
		}
		Box {
			CompactIconButton(onClick = { menu = true }, size = 20.dp, tooltip = tr("physics.more")) {
				Text("⋯", style = LocalToolTypography.current.body.copy(fontSize = 12.sp), color = colors.textMuted)
			}
			TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
				CompactMenuItem(tr("physics.preset.saveAs"), { menu = false; draft = ""; naming = true })
				CompactMenuItem(tr("physics.preset.overwrite"), {
					menu = false
					PhysicsPresetStore.save(PhysicsPresets.capture(kind, chosen.name, setting))
				}, enabled = !chosen.builtin)
				CompactMenuItem(tr("physics.rename"), { menu = false; draft = chosen.name; naming = false }, enabled = !chosen.builtin)
				CompactMenuDivider()
				CompactMenuItem(tr("physics.delete"), { menu = false; PhysicsPresetStore.delete(chosen); chosenName = null },
					enabled = !chosen.builtin, danger = true)
			}
		}
	}
}
