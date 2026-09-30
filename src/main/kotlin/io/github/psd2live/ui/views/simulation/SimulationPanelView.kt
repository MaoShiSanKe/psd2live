package io.github.psd2live.ui.views.simulation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.sim.GlueRole
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimColliderRef
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.core.sim.SimMaterial
import io.github.psd2live.core.sim.glueKey
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.PanelSectionRow
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.Hint
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.VertexGroupKind
import java.util.Locale

/**
 * Simulation: cloth and hair bodies simulated in 2D on their meshes. Edits commit one history node each,
 * baked again in the same node when the body bakes on its own; sliders commit on release. The preview plays
 * either the export (the bake, as Cubism plays it) or the reference simulation, which never exports.
 */
@Composable
internal fun SimulationPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val sims = state.rigEdits.simEdits
	var selectedId by remember { mutableStateOf<String?>(null) }
	val selected = sims.firstOrNull { it.id == selectedId } ?: sims.firstOrNull()
	val status by viewModel.simulationStatus.collectAsState()
	val puppet = state.previewModel?.rig?.puppet

	Column(modifier.fillMaxSize().background(colors.panelBackground)) {
		Row(
			Modifier.fillMaxWidth().background(colors.panelElevated).padding(horizontal = 4.dp, vertical = 3.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(3.dp),
		) {
			CompactButton(tr("sim.newCloth"), { viewModel.createSimulationFromSelection(SimKind.CLOTH)?.let { selectedId = it } },
				enabled = puppet != null && !state.canvasEditBusy, height = 22.dp)
			CompactButton(tr("sim.newHair"), { viewModel.createSimulationFromSelection(SimKind.HAIR)?.let { selectedId = it } },
				enabled = puppet != null && !state.canvasEditBusy, height = 22.dp)
			Spacer(Modifier.weight(1f))
			val live = selected != null && state.simulationPreviewId == selected.id
			CompactToggleChip(tr("sim.previewExport"), !live, { viewModel.setSimulationPreview(null) },
				enabled = selected != null, tooltip = tr("sim.previewExportTip"))
			CompactToggleChip(tr("sim.previewReference"), live, { viewModel.setSimulationPreview(selected?.id) },
				enabled = selected != null, tooltip = tr("sim.previewReferenceTip"))
			if (live) CompactIconButton(onClick = viewModel::restartSimulationPreview, tooltip = tr("sim.restart"), size = 20.dp) {
				IconReset(modifier = Modifier.size(12.dp), tint = colors.textMuted)
			}
		}
		Divider(color = colors.divider)
		if (puppet == null) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(tr("sim.noModel"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted, modifier = Modifier.padding(12.dp))
			}
			return@Column
		}
		val scroll = rememberScrollState()
		Box(Modifier.fillMaxSize()) {
			Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 6.dp, end = 10.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
				if (sims.isEmpty()) Text(tr("sim.empty"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted)
				for (sim in sims) SimulationRow(sim, sim.id == selected?.id, state.simulationPreviewId == sim.id, { selectedId = sim.id }) {
					viewModel.deleteSimulation(sim.id)
				}
				StatusLine(status, selected?.id)
				if (selected != null) {
					Divider(color = colors.divider, modifier = Modifier.padding(vertical = 3.dp))
					SimulationEditor(viewModel, state, puppet, selected)
				}
				Spacer(Modifier.height(8.dp))
			}
			VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
		}
	}
}

@Composable
private fun SimulationRow(sim: RigSimEdit, selected: Boolean, live: Boolean, onSelect: () -> Unit, onDelete: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(
		Modifier.fillMaxWidth().background(if (selected) colors.selection else colors.panelBackground).clickable(onClick = onSelect).padding(horizontal = 4.dp, vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(sim.name, style = typography.caption.copy(fontSize = 11.sp), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		Text(tr("sim.kind.${sim.kind.jsonName}") + " · " + tr("sim.meshCount", sim.targets.size) + if (live) " · " + tr("sim.previewReference") else "",
			style = typography.caption.copy(fontSize = 9.5.sp), color = if (live) colors.warning else colors.textMuted)
		// Only a baked simulation exports; the dot says which ones do.
		Box(Modifier.size(6.dp).background(if (sim.bake != null) colors.accent else colors.warning, androidx.compose.foundation.shape.CircleShape))
		CompactIconButton(onClick = onDelete, tooltip = tr("sim.delete"), size = 16.dp) { IconClose(modifier = Modifier.size(9.dp), tint = colors.textMuted) }
	}
}

@Composable
private fun StatusLine(status: PSD2LiveViewModel.SimulationStatus, selectedId: String?) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val style = typography.caption.copy(fontSize = 9.5.sp)
	when (status) {
		PSD2LiveViewModel.SimulationStatus.Idle -> {}
		PSD2LiveViewModel.SimulationStatus.Preparing -> Text(tr("sim.preparing"), style = style, color = colors.textMuted)
		is PSD2LiveViewModel.SimulationStatus.Running -> status.notes.forEach { Hint(it) }
		is PSD2LiveViewModel.SimulationStatus.Failed -> Text(status.message, style = style, color = colors.error)
		is PSD2LiveViewModel.SimulationStatus.Report -> if (status.id == selectedId) {
			val r = status.report
			fun value(key: String) = r[key]?.toString().orEmpty()
			Text(tr("sim.report", value("pinned"), value("particles"), value("rest_drift_px"), value("max_stretch_percent"), value("calibration_residual_px")),
				style = style, color = colors.textPrimary)
			(r["phases"] as? kotlinx.serialization.json.JsonArray)?.forEach { phase ->
				val o = phase as kotlinx.serialization.json.JsonObject
				Text(tr("sim.reportPhase", o["input"].toString().trim('"'), o["peak_px"].toString(), o["after_release_px"].toString()), style = style, color = colors.textMuted)
			}
			(r["notes"] as? kotlinx.serialization.json.JsonArray)?.forEach { Hint(it.toString().trim('"')) }
		}
	}
}

@Composable
private fun SimulationEditor(viewModel: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, sim: RigSimEdit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	fun commit(next: RigSimEdit) { if (next != sim) viewModel.putSimulation(next) }
	var open by remember { mutableStateOf(setOf("material", "groups", "glue", "colliders", "inputs")) }

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.kind"))
		CompactDropdown(SimKind.entries, sim.kind, { commit(sim.copy(kind = it, material = SimMaterial.preset(it))) },
			Modifier.weight(1f), itemLabel = { tr("sim.kind.${it.jsonName}") }, height = 22.dp)
		CompactCheckbox(sim.enabled, { commit(sim.copy(enabled = it)) }, label = tr("sim.enabled"))
	}
	Text(tr("sim.targets", sim.targets.joinToString()), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted,
		maxLines = 2, overflow = TextOverflow.Ellipsis)

	PanelSectionRow(tr("sim.material"), "material" in open, { open = open.toggle("material") })
	if ("material" in open) MaterialEditor(sim.material) { commit(sim.copy(material = it)) }

	PanelSectionRow(tr("sim.groups"), "groups" in open, { open = open.toggle("groups") })
	if ("groups" in open) GroupsEditor(puppet, sim, ::commit)

	val glues = puppet.glues.filter { it.meshA.raw in sim.targets || it.meshB.raw in sim.targets }
	PanelSectionRow(tr("sim.glue"), "glue" in open, { open = open.toggle("glue") }, count = glues.size)
	if ("glue" in open) {
		if (glues.isEmpty()) Text(tr("sim.noGlue"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
		for (glue in glues) {
			val key = glueKey(glue)
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(key.replace("|", " ↔ "), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textPrimary,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				CompactDropdown(GlueRole.entries, sim.glueRoles[key] ?: GlueRole.IGNORE, { role ->
					commit(sim.copy(glueRoles = if (role == GlueRole.IGNORE) sim.glueRoles - key else sim.glueRoles + (key to role)))
				}, Modifier.width(96.dp), itemLabel = { tr("sim.glueRole.${it.jsonName}") }, height = 22.dp)
			}
		}
		Text(tr("sim.glueHint"), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
	}

	PanelSectionRow(tr("sim.colliders"), "colliders" in open, { open = open.toggle("colliders") }, count = sim.colliders.size)
	if ("colliders" in open) {
		for (collider in sim.colliders) {
			val groups = puppet.vertexGroups.filter { it.drawableId.raw == collider.drawableId && it.kind == VertexGroupKind.COLLIDER }.map { it.name }
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(collider.drawableId, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textPrimary,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				CompactDropdown(listOf<String?>(null) + groups, collider.group, { group ->
					commit(sim.copy(colliders = sim.colliders.map { if (it === collider) it.copy(group = group) else it }))
				}, Modifier.width(96.dp), itemLabel = { it ?: tr("sim.wholeMesh") }, height = 22.dp)
				CompactIconButton(onClick = { commit(sim.copy(colliders = sim.colliders - collider)) }, tooltip = tr("sim.remove"), size = 16.dp) {
					IconClose(modifier = Modifier.size(9.dp), tint = colors.textMuted)
				}
			}
		}
		val candidates = selectedMeshes(state).filter { it !in sim.targets && sim.colliders.none { c -> c.drawableId == it } }
		CompactButton(tr("sim.addColliders"), {
			commit(sim.copy(colliders = sim.colliders + candidates.map { id ->
				SimColliderRef(id, puppet.vertexGroups.firstOrNull { it.drawableId.raw == id && it.kind == VertexGroupKind.COLLIDER }?.name)
			}))
		}, enabled = candidates.isNotEmpty(), height = 22.dp)
	}

	PanelSectionRow(tr("sim.inputs"), "inputs" in open, { open = open.toggle("inputs") }, count = sim.inputs.size)
	if ("inputs" in open) {
		for (input in sim.inputs) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(input.parameter, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textPrimary, modifier = Modifier.weight(1f))
			CompactIconButton(onClick = { commit(sim.copy(inputs = sim.inputs - input)) }, tooltip = tr("sim.remove"), size = 16.dp) {
				IconClose(modifier = Modifier.size(9.dp), tint = colors.textMuted)
			}
		}
		val available = puppet.parameters.map { it.id.raw }.filter { id -> sim.inputs.none { it.parameter == id } }
		if (available.isNotEmpty()) CompactDropdown(listOf<String?>(null) + available, null, { id ->
			if (id != null) commit(sim.copy(inputs = sim.inputs + PhysicsInput(id, type = PhysicsSourceType.ANGLE)))
		}, Modifier.fillMaxWidth(), itemLabel = { it ?: tr("sim.addInput") }, height = 22.dp)
		Text(tr("sim.inputsHint"), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
	}

	PanelSectionRow(tr("sim.bake"), "bake" !in open, { open = open.toggle("bake") })
	if ("bake" !in open) BakeEditor(viewModel, state, puppet, sim, ::commit)

	Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
		CompactButton(tr("sim.test"), { viewModel.reportSimulation(sim.id) }, height = 22.dp)
		CompactButton(tr("sim.paintWeights"), { viewModel.beginVertexGroupPaint(sim.targets.first(), VertexGroupKind.PIN) }, height = 22.dp)
	}
}

/**
 * The bake: how many modes and keys, which parameters are baked as exact poses, how the modes are written,
 * and the bake's state - missing, stale, or how well it matches the simulation. Only a baked simulation exports.
 */
@Composable
private fun BakeEditor(viewModel: PSD2LiveViewModel, state: PSD2LiveState, puppet: PuppetModel, sim: RigSimEdit, commit: (RigSimEdit) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val caption = typography.caption.copy(fontSize = 9.5.sp)
	val baking by viewModel.simulationBaking.collectAsState()

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.modes"), tooltip = tr("sim.modesTip"))
		CompactDropdown((1..RigSimEdit.MAX_MODES).toList(), sim.modes, { commit(sim.copy(modes = it)) }, Modifier.weight(1f),
			itemLabel = { "$it" }, height = 22.dp)
		FieldLabel(tr("sim.keys"), tooltip = tr("sim.keysTip"))
		CompactDropdown(RigSimEdit.KEY_COUNTS.filter { it % 2 == 1 }, sim.keys, { commit(sim.copy(keys = it)) }, Modifier.weight(1f),
			itemLabel = { "$it" }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.exaggeration"), tooltip = tr("sim.exaggerationTip"))
		CompactDropdown((listOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f) + sim.exaggeration).distinct().sorted(), sim.exaggeration,
			{ commit(sim.copy(exaggeration = it)) }, Modifier.weight(1f), itemLabel = { "×" + String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.blendShapes"), tooltip = tr("sim.blendShapesTip"))
		CompactDropdown(listOf(null, true, false), sim.blendShapes, { commit(sim.copy(blendShapes = it)) }, Modifier.weight(1f),
			itemLabel = { tr(when (it) { null -> "sim.blendShapesAuto"; true -> "sim.blendShapesOn"; false -> "sim.blendShapesOff" }) }, height = 22.dp)
	}
	if (sim.blendShapes == true && !puppet.runtimeTarget.supports(org.umamo.runtime.model.RuntimeFeature.MeshWarpBlendShapes))
		Text(tr("sim.blendShapesUnavailable"), style = caption, color = colors.textMuted)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.autoBake"), tooltip = tr("sim.autoBakeTip"))
		Spacer(Modifier.weight(1f))
		CompactCheckbox(sim.autoBake, { commit(sim.copy(autoBake = it)) })
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.staticInputs"), tooltip = tr("sim.staticInputsTip"))
		Spacer(Modifier.weight(1f))
		CompactCheckbox(sim.staticInputs == null, { auto -> commit(sim.copy(staticInputs = if (auto) null else emptyList())) }, label = tr("sim.staticAuto"))
	}
	val statics = sim.staticInputs
	if (statics == null) {
		Text(if (sim.colliders.isEmpty()) tr("sim.staticAutoNone") else tr("sim.staticAutoHint"), style = caption, color = colors.textMuted)
	} else {
		for (parameter in statics) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(parameter, style = caption, color = colors.textPrimary, modifier = Modifier.weight(1f))
			CompactIconButton(onClick = { commit(sim.copy(staticInputs = statics - parameter)) }, tooltip = tr("sim.remove"), size = 16.dp) {
				IconClose(modifier = Modifier.size(9.dp), tint = colors.textMuted)
			}
		}
		val available = puppet.parameters.map { it.id.raw }.filter { it !in statics && sim.bake?.parameters?.contains(it) != true }
		if (statics.size < RigSimEdit.MAX_STATIC_INPUTS && available.isNotEmpty()) CompactDropdown(listOf<String?>(null) + available, null, { id ->
			if (id != null) commit(sim.copy(staticInputs = statics + id))
		}, Modifier.fillMaxWidth(), itemLabel = { it ?: tr("sim.addStaticInput") }, height = 22.dp)
	}

	val bake = sim.bake
	val running = baking?.takeIf { it.id == sim.id }
	when {
		running != null -> Text(tr("sim.baking", (running.progress * 100f).toInt()), style = caption, color = colors.accent)
		bake == null -> Text(tr("sim.notBaked"), style = caption, color = colors.warning)
		else -> {
			// Both hash or rebuild the targets' keyforms: once per rig and edit, not per frame.
			val stale = remember(puppet, sim) { io.github.psd2live.core.sim.SimBake.stale(puppet, sim) }
			val issues = remember(puppet, sim) { io.github.psd2live.core.sim.SimGenerator.issues(puppet, sim) }
			if (stale) Text(tr("sim.bakeStale"), style = caption, color = colors.warning)
			for (mode in bake.modes) Text(tr("sim.bakedMode", mode.axis.parameter, String.format(Locale.US, "%.1f", mode.amplitude)),
				style = caption, color = colors.textPrimary)
			if (bake.physics != null) Text(tr("sim.bakedFit", bake.physics.id, String.format(Locale.US, "%.2f", bake.fit)), style = caption,
				color = if (bake.fit < 0.8f) colors.warning else colors.textPrimary)
			if (bake.modes.isNotEmpty()) Text(tr(if (io.github.psd2live.core.sim.SimGenerator.usesBlendShapes(puppet, sim)) "sim.bakedAsBlend" else "sim.bakedAsGrid"),
				style = caption, color = colors.textMuted)
			if (bake.statics.isNotEmpty()) Text(tr("sim.bakedStatics", bake.statics.joinToString { it.parameter }), style = caption, color = colors.textPrimary)
			Text(tr("sim.bakedError", String.format(Locale.US, "%.1f", bake.maxErrorPx)), style = caption, color = colors.textMuted)
			if (bake.modes.isNotEmpty()) Text(tr("sim.bakedMotion", (bake.peak * 100f).toInt(), String.format(Locale.US, "%.1f", bake.clipped * 100f),
				String.format(Locale.US, "%.2f", bake.jerk)), style = caption,
				color = if (bake.clipped > 0f || bake.jerk > 1.5f) colors.warning else colors.textMuted)
			issues.forEach { Hint(it) }
		}
	}
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		if (running != null) {
			CompactButton(tr("sim.cancelBake"), viewModel::cancelSimulationBake, height = 22.dp)
		} else {
			CompactButton(tr(if (bake == null) "sim.bakeAction" else "sim.rebake"), { viewModel.bakeSimulation(sim.id) },
				enabled = baking == null && !state.canvasEditBusy, height = 22.dp)
			CompactButton(tr("sim.clearBake"), { viewModel.clearSimulationBake(sim.id) }, enabled = bake != null && !state.canvasEditBusy, height = 22.dp)
		}
	}
	Text(tr("sim.bakeHint"), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
}

@Composable
private fun MaterialEditor(material: SimMaterial, onCommit: (SimMaterial) -> Unit) {
	// Sliders move a local draft and commit once on release, so a drag is one history node.
	var draft by remember(material) { mutableStateOf(material) }
	DraftSlider(tr("sim.mass"), tr("sim.massTip"), draft.mass, 0.2f..4f, { draft = draft.copy(mass = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.stretch"), tr("sim.stretchTip"), draft.stretch, 0f..1f, { draft = draft.copy(stretch = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.bend"), tr("sim.bendTip"), draft.bend, 0f..1f, { draft = draft.copy(bend = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.damping"), tr("sim.dampingTip"), draft.damping, 0f..8f, { draft = draft.copy(damping = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.goal"), tr("sim.goalTip"), draft.goal, 0f..1f, { draft = draft.copy(goal = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.slack"), tr("sim.slackTip"), draft.slack, 0f..0.3f, { draft = draft.copy(slack = it) }) { onCommit(draft) }
}

@Composable
private fun DraftSlider(title: String, tooltip: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(title, tooltip = tooltip)
		CompactSlider(value, { onChange((it * 100f).toInt() / 100f) }, onValueChangeFinished = onFinished, modifier = Modifier.weight(1f), valueRange = range)
		Text(String.format(Locale.US, "%.2f", value), style = typography.monoSmall, color = colors.textPrimary, modifier = Modifier.width(34.dp))
	}
}

/** Which vertex group each kind reads; the choices are the targets' groups of that kind. */
@Composable
private fun GroupsEditor(puppet: PuppetModel, sim: RigSimEdit, commit: (RigSimEdit) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val kinds = listOf(VertexGroupKind.PIN, VertexGroupKind.COLLIDE, VertexGroupKind.STIFFNESS, VertexGroupKind.MASS,
		VertexGroupKind.GOAL, VertexGroupKind.DAMPING, VertexGroupKind.WIND)
	for (kind in kinds) {
		val names = puppet.vertexGroups.filter { it.drawableId.raw in sim.targets && it.kind == kind }.map { it.name }.distinct()
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			FieldLabel(tr("sim.group.${kind.jsonName}"), tooltip = tr("sim.groupTip.${kind.jsonName}"))
			if (names.isEmpty()) Text(tr("sim.noGroup"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
			else CompactDropdown(listOf<String?>(null) + names, sim.groups[kind], { name ->
				commit(sim.copy(groups = if (name == null) sim.groups - kind else sim.groups + (kind to name)))
			}, Modifier.weight(1f), itemLabel = { it ?: tr("sim.firstGroup") }, height = 22.dp)
		}
	}
}

private fun Set<String>.toggle(key: String) = if (key in this) this - key else this + key

/** The meshes of the selected layers. */
private fun selectedMeshes(state: PSD2LiveState): List<String> {
	val model = state.previewModel ?: return emptyList()
	val layers = state.selectedLayerIds.ifEmpty { setOfNotNull(state.selectedLayerId) }
	return model.rig.layerIdByDrawableId.filter { (drawable, layer) ->
		layer in layers && model.rig.puppet.drawables.any { it.id.raw == drawable && it.mesh != null }
	}.keys.sorted()
}
