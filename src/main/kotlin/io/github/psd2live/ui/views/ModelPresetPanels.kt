package io.github.psd2live.ui.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.sim.ModelPresets
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.state.CanvasCreationPreset
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull

/** A collapsible group title of the model presets, with a one-line summary while collapsed. */
@Composable
private fun PresetGroupHeader(title: String, summary: String, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(vertical = 1.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		IconChevron(expanded = expanded && enabled, modifier = Modifier.size(9.dp), tint = if (enabled) colors.textMuted else colors.textDisabled)
		Spacer(Modifier.width(4.dp))
		Text(
			text = title,
			style = typography.body.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
			color = if (enabled) colors.textPrimary else colors.textDisabled,
		)
		if (!expanded || !enabled) {
			Spacer(Modifier.width(6.dp))
			Text(
				text = "($summary)",
				style = typography.caption.copy(fontSize = 9.5.sp),
				color = colors.textMuted,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/** One labelled line of a preset group: the label column, then [content]. */
@Composable
private fun PresetRow(label: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = label,
			style = typography.body.copy(fontSize = 10.5.sp),
			color = colors.textPrimary,
			modifier = Modifier.width(60.dp),
			textAlign = TextAlign.Right,
		)
		Spacer(Modifier.width(5.dp))
		content()
	}
}

/**
 * Physics and simulation presets: hair simulation in place of the legacy sway, clothing simulated where
 * the art shows it hanging loose, recomputed pin weights, and the eye jelly pendulum.
 */
@Composable
internal fun SimulationPresetsGroup(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val baking by viewModel.simulationBaking.collectAsState()
	val status by viewModel.simulationStatus.collectAsState()
	val report by viewModel.modelPresetReport.collectAsState()
	var selectedOnly by remember { mutableStateOf(false) }
	val busy = state.isAnalyzing || state.isGenerating || state.canvasEditBusy || baking != null
	val ready = state.previewModel != null && !state.meshOnly && !busy
	val hasSelection = state.selectedLayerIds.isNotEmpty() || state.selectedLayerId != null
	val canApply = ready && (!selectedOnly || hasSelection)
	val present = PhysicsGenerator.Presets.present(state.analysis)
	val hasClothing = state.analysis?.layers.orEmpty().any { it.semantic.tag in ModelPresets.CLOTHING_TAGS && it.opaquePixels > 0 }
	val sims = state.rigEdits.simEdits.associateBy { it.id }
	val clothingSims = ModelPresets.CLOTHING_SIMS.filterValues { it in sims }

	fun simStatus(id: String): String? = sims[id]?.let { tr(if (it.bake != null) "presets.status.baked" else "presets.status.unbaked") }

	val summary = listOfNotNull(
		tr(if (state.hairSimulationFront) "presets.summary.frontSim" else "presets.summary.frontClassic").takeIf { present.frontHair },
		tr(if (state.hairSimulationBack) "presets.summary.backSim" else "presets.summary.backClassic").takeIf { present.backHair },
		tr("presets.summary.clothing").takeIf { clothingSims.isNotEmpty() },
	).joinToString(" · ").ifEmpty { tr("presets.summary.none") }
	PresetGroupHeader(tr("settings.group.simulation"), summary, state.simulationPresetsExpanded, !state.meshOnly) {
		viewModel.setSimulationPresetsExpanded(!state.simulationPresetsExpanded)
	}
	if (!state.simulationPresetsExpanded || state.meshOnly) return

	Column(
		modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		CompactCheckbox(
			checked = selectedOnly,
			onCheckedChange = { selectedOnly = it },
			label = tr("presets.selectedOnly"),
			enabled = !busy,
		)

		for (front in listOf(true, false)) {
			val simulated = if (front) state.hairSimulationFront else state.hairSimulationBack
			val exists = if (front) present.frontHair else present.backHair
			val preset = if (front) ModelPresets.Preset.FRONT_HAIR else ModelPresets.Preset.BACK_HAIR
			PresetRow(tr(if (front) "presets.frontHair" else "presets.backHair")) {
				if (simulated) {
					Text(
						text = simStatus(if (front) ModelPresets.FRONT_HAIR_SIM else ModelPresets.BACK_HAIR_SIM) ?: tr("presets.status.missing"),
						style = typography.caption.copy(fontSize = 10.sp),
						color = colors.textMuted,
						modifier = Modifier.weight(1f),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					CompactButton(tr("presets.reapply"), onClick = { viewModel.applyModelPreset(preset, selectedOnly) },
						enabled = canApply && exists, height = 20.dp)
					Spacer(Modifier.width(4.dp))
					CompactButton(tr("presets.restoreClassic"), onClick = { viewModel.restoreClassicHair(front) },
						enabled = ready, height = 20.dp)
				} else {
					// The legacy sway stays switchable until the hair is simulated.
					CompactCheckbox(
						checked = if (front) state.physicsFrontHair else state.physicsBackHair,
						onCheckedChange = { if (front) viewModel.setPhysicsFrontHair(it) else viewModel.setPhysicsBackHair(it) },
						label = tr("presets.classicSway"),
						enabled = !busy && exists,
						modifier = Modifier.weight(1f),
					)
					CompactButton(tr("presets.simulateHair"), onClick = { viewModel.applyModelPreset(preset, selectedOnly) },
						enabled = canApply && exists, isPrimary = true, height = 20.dp)
				}
			}
		}

		PresetRow(tr("presets.clothing")) {
			Text(
				text = clothingSims.map { (wear, id) -> "${tr("presets.garment.${wear.jsonName}")}: ${simStatus(id)}" }
					.joinToString(" · ").ifEmpty { tr(if (hasClothing) "presets.status.notApplied" else "presets.status.noClothing") },
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
				modifier = Modifier.weight(1f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			CompactButton(tr("presets.detectClothing"), onClick = { viewModel.applyModelPreset(ModelPresets.Preset.CLOTHING, selectedOnly) },
				enabled = canApply && hasClothing, isPrimary = clothingSims.isEmpty(), height = 20.dp)
		}
		GarmentReport(state, report)

		PresetRow(tr("presets.weights")) {
			Text(
				text = tr("presets.weightsHint"),
				style = typography.caption.copy(fontSize = 10.sp),
				color = colors.textMuted,
				modifier = Modifier.weight(1f),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			CompactButton(tr("presets.autoWeights"), onClick = { viewModel.applyModelPreset(ModelPresets.Preset.AUTO_WEIGHTS, selectedOnly) },
				enabled = canApply && (selectedOnly || state.rigEdits.simEdits.isNotEmpty()), height = 20.dp)
		}

		CompactCheckbox(
			checked = state.physicsEyeJelly,
			onCheckedChange = viewModel::setPhysicsEyeJelly,
			label = tr("export.physics.eyeJelly"),
			enabled = !busy && present.eyeJelly,
		)

		baking?.let { progress ->
			Row(verticalAlignment = Alignment.CenterVertically) {
				LinearProgressIndicator(progress.overall, Modifier.weight(1f), color = colors.accent, backgroundColor = colors.panelElevated)
				Spacer(Modifier.width(6.dp))
				CompactButton(tr("sim.cancelBake"), onClick = viewModel::cancelSimulationBake, height = 20.dp)
			}
		}
		(status as? PSD2LiveViewModel.SimulationStatus.Failed)?.let {
			Text(it.message, style = typography.caption.copy(fontSize = 10.sp), color = colors.warning)
		}
	}
}

/** What the last clothing preset read from each garment: its kind, how loose it hangs, or that it is worn tight. */
@Composable
private fun GarmentReport(state: PSD2LiveState, report: JsonObject?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val garments = report?.get("garments") as? JsonObject ?: return
	val names = state.previewModel?.rig?.puppet?.drawables?.associate { it.id.raw to it.name }.orEmpty()
	for ((mesh, value) in garments) {
		val profile = value as? JsonObject ?: continue
		val garment = (profile["garment"] as? JsonPrimitive)?.content ?: continue
		val simulated = (profile["simulated"] as? JsonPrimitive)?.booleanOrNull ?: true
		val loose = (profile["loose"] as? JsonPrimitive)?.floatOrNull ?: 0f
		val detail = listOfNotNull(
			(profile["decided_by"] as? JsonPrimitive)?.content?.let { tr("presets.decidedBy.$it") },
			if (simulated) tr("presets.fit.loose", kotlin.math.round(loose * 100f).toInt()) else tr("presets.fit.tight"),
		).joinToString(" · ")
		Text(
			text = tr("presets.garmentRead", names[mesh] ?: mesh, tr("presets.garment.$garment"), detail),
			style = typography.caption.copy(fontSize = 9.5.sp),
			color = colors.textMuted,
			modifier = Modifier.padding(start = 65.dp),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

private val warpAddToChoices = listOf("PARENT_OF_SELECTED", "CHILD_OF_SELECTED_DEFORMER")

/** Defaults new warps take on the canvas, and shortcuts that start placing a warp or rotation deformer. */
@Composable
internal fun CanvasCreationPresetsGroup(state: PSD2LiveState, viewModel: PSD2LiveViewModel) {
	val preset = state.canvasCreation
	val builtIn = preset.builtIn
	val ready = state.previewModel != null && !state.isAnalyzing && !state.isGenerating
	PresetGroupHeader(
		tr("settings.group.canvasCreation"),
		"${tr(builtIn?.let { "presets.canvas.$it" } ?: "presets.canvas.custom")} · ${preset.warpRows}×${preset.warpCols}",
		state.canvasCreationExpanded,
		true,
	) { viewModel.setCanvasCreationExpanded(!state.canvasCreationExpanded) }
	if (!state.canvasCreationExpanded) return

	Column(
		modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		PresetRow(tr("presets.canvas.preset")) {
			CompactDropdown(
				items = CanvasCreationPreset.BUILT_IN.keys.toList() + "custom",
				selectedItem = builtIn ?: "custom",
				onItemSelected = { key ->
					CanvasCreationPreset.BUILT_IN[key]?.let { viewModel.setCanvasCreationPreset(it.copy(warpAddTo = preset.warpAddTo)) }
				},
				itemLabel = { tr("presets.canvas.$it") },
				modifier = Modifier.weight(1f),
				height = 20.dp,
			)
		}
		PresetRow(tr("presets.canvas.grid")) {
			CompactNumberSpinner(
				value = preset.warpRows.toDouble(),
				onValueChange = { viewModel.setCanvasCreationPreset(preset.copy(warpRows = it.toInt().coerceIn(1, 32))) },
				min = 1.0, max = 32.0, step = 1.0, decimals = 0,
				modifier = Modifier.weight(1f), height = 20.dp,
			)
			Text(" × ", fontSize = 10.sp, color = LocalToolColors.current.textMuted)
			CompactNumberSpinner(
				value = preset.warpCols.toDouble(),
				onValueChange = { viewModel.setCanvasCreationPreset(preset.copy(warpCols = it.toInt().coerceIn(1, 32))) },
				min = 1.0, max = 32.0, step = 1.0, decimals = 0,
				modifier = Modifier.weight(1f), height = 20.dp,
			)
		}
		PresetRow(tr("presets.canvas.bezier")) {
			CompactNumberSpinner(
				value = preset.bezierRows.toDouble(),
				onValueChange = { viewModel.setCanvasCreationPreset(preset.copy(bezierRows = it.toInt().coerceIn(1, 16))) },
				min = 1.0, max = 16.0, step = 1.0, decimals = 0,
				modifier = Modifier.weight(1f), height = 20.dp,
			)
			Text(" × ", fontSize = 10.sp, color = LocalToolColors.current.textMuted)
			CompactNumberSpinner(
				value = preset.bezierCols.toDouble(),
				onValueChange = { viewModel.setCanvasCreationPreset(preset.copy(bezierCols = it.toInt().coerceIn(1, 16))) },
				min = 1.0, max = 16.0, step = 1.0, decimals = 0,
				modifier = Modifier.weight(1f), height = 20.dp,
			)
		}
		PresetRow(tr("editor.warpAddTo")) {
			CompactDropdown(
				items = warpAddToChoices,
				selectedItem = preset.warpAddTo.takeIf { it in warpAddToChoices } ?: warpAddToChoices[0],
				onItemSelected = { viewModel.setCanvasCreationPreset(preset.copy(warpAddTo = it)) },
				itemLabel = { if (it == "PARENT_OF_SELECTED") tr("editor.warpAddTo.parentOfSelected") else tr("editor.warpAddTo.childOfDeformer") },
				modifier = Modifier.weight(1f),
				height = 20.dp,
			)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 65.dp)) {
			CompactButton(tr("editor.createWarp"), onClick = { viewModel.beginCanvasCreation(false) }, enabled = ready,
				modifier = Modifier.weight(1f), height = 20.dp)
			CompactButton(tr("editor.createRotation"), onClick = { viewModel.beginCanvasCreation(true) }, enabled = ready,
				modifier = Modifier.weight(1f), height = 20.dp)
		}
	}
}
