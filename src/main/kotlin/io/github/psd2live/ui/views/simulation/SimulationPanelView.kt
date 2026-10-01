package io.github.psd2live.ui.views.simulation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.sim.GlueRole
import io.github.psd2live.core.sim.RigSimEdit
import io.github.psd2live.core.sim.SimBake
import io.github.psd2live.core.sim.SimColliderRef
import io.github.psd2live.core.sim.SimGenerator
import io.github.psd2live.core.sim.SimKind
import io.github.psd2live.core.sim.SimMaterial
import io.github.psd2live.core.sim.glueKey
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.IconAdd
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconCollapseAll
import io.github.psd2live.ui.components.IconExpandAll
import io.github.psd2live.ui.components.IconEye
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.views.PanelSectionRow
import io.github.psd2live.ui.views.PanelToolButton
import io.github.psd2live.ui.views.shownToolLabels
import io.github.psd2live.ui.views.SimSectionIcon
import io.github.psd2live.ui.views.SimSectionIconView
import io.github.psd2live.ui.views.VertexGroupKindIcon
import io.github.psd2live.ui.views.physics.FieldLabel
import io.github.psd2live.ui.views.physics.Hint
import io.github.psd2live.ui.views.physics.PhysicsRowDivider
import io.github.psd2live.ui.views.physics.PhysicsSection
import io.github.psd2live.ui.views.physics.RowBadge
import io.github.psd2live.ui.views.physics.StatusDot
import io.github.psd2live.ui.views.vertexGroupKindColor
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeFeature
import org.umamo.runtime.model.VertexGroupKind
import java.util.Locale

/** The panel's foldable sections: the body list and the selected body's editor sections. */
private val SECTIONS = setOf("bodies", "bake", "material", "inputs", "colliders", "glue", "groups")

/** Whether a simulation exports, and as its current setup. */
private enum class BakeState { BAKED, STALE, UNBAKED, DISABLED }

/**
 * Simulation, laid out like the physics panel beside it: a toolbar with the bake status of every body and
 * Bake all, the bodies in a list, and the selected body's sections with its bake first. Edits commit one
 * history node each, baked again in the same node when the body bakes on its own; sliders commit on release.
 * The preview plays either the export (the bake, as Cubism plays it) or the reference simulation, which
 * never exports.
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
	// Which sections are open survives switching bodies; the toolbar opens or closes them all.
	var open by remember { mutableStateOf(SECTIONS - "groups") }
	val listOpen = "bodies" in open
	// Staleness hashes the targets' keyforms: once per rig and edit, not per frame.
	val bakeStates = remember(puppet, sims) {
		sims.associate { sim ->
			sim.id to when {
				!sim.enabled -> BakeState.DISABLED
				sim.bake == null -> BakeState.UNBAKED
				puppet != null && SimBake.stale(puppet, sim) -> BakeState.STALE
				else -> BakeState.BAKED
			}
		}
	}

	Column(modifier.fillMaxSize().background(colors.panelBackground)) {
		SimulationToolbar(viewModel, state, sims, selected, bakeStates, { open = it }) { selectedId = it }
		Divider(color = colors.divider)
		if (puppet == null) {
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text(tr("sim.noModel"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textMuted, modifier = Modifier.padding(12.dp))
			}
			return@Column
		}
		val scroll = rememberScrollState()
		Box(Modifier.fillMaxSize()) {
			Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 6.dp)) {
				PanelSectionRow(tr("sim.bodies"), listOpen, { open = if (listOpen) open - "bodies" else open + "bodies" }, count = sims.size)
				PhysicsRowDivider()
				if (listOpen) {
					if (sims.isEmpty()) {
						Text(tr("sim.empty"), style = typography.caption.copy(fontSize = 10.5.sp), color = colors.textMuted,
							modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp))
						PhysicsRowDivider()
					}
					for (sim in sims) {
						SimulationRow(viewModel, state, sim, bakeStates[sim.id] ?: BakeState.UNBAKED, sim.id == selected?.id) { selectedId = sim.id }
						PhysicsRowDivider()
					}
				}
				StatusLine(status)
				if (selected != null) SimulationEditor(viewModel, state, puppet, selected, bakeStates[selected.id] ?: BakeState.UNBAKED, open) { open = it }
				Spacer(Modifier.height(8.dp))
			}
			VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
		}
	}
}

@Composable
private fun BakeState.color(): Color {
	val colors = LocalToolColors.current
	return when (this) {
		BakeState.BAKED -> colors.success
		BakeState.STALE -> colors.warning
		BakeState.UNBAKED -> colors.error
		BakeState.DISABLED -> colors.textDisabled
	}
}

private fun BakeState.label() = tr("sim.state.${name.lowercase()}")

/** New body, Bake all with how many bodies export as set up, and the preview choice, in one row. */
@Composable
private fun SimulationToolbar(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	sims: List<RigSimEdit>,
	selected: RigSimEdit?,
	states: Map<String, BakeState>,
	onOpenSections: (Set<String>) -> Unit,
	onCreated: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	var newMenuOpen by remember { mutableStateOf(false) }
	val ready = state.previewModel != null
	val baking by viewModel.simulationBaking.collectAsState()
	val outdated = states.values.any { it == BakeState.UNBAKED || it == BakeState.STALE }
	val live = selected != null && state.simulationPreviewId == selected.id
	Column(
		Modifier.fillMaxWidth().background(colors.panelElevated).padding(horizontal = 4.dp, vertical = 3.dp),
		verticalArrangement = Arrangement.spacedBy(3.dp),
	) {
		BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
			val labels = listOf(tr("sim.new"), tr("sim.bakeAll"), tr("sim.previewExport"), tr("sim.previewReference"))
			val labelsShown = shownToolLabels(labels, if (live) 7 else 6, maxWidth)
			Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
				Box {
					PanelToolButton(labels[0], showLabel = labelsShown > 0, onClick = { newMenuOpen = true }, enabled = ready && !state.canvasEditBusy,
						tooltip = tr("sim.newTip")) {
						IconAdd(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
					}
					TreeContextMenu(expanded = newMenuOpen, onDismissRequest = { newMenuOpen = false }, minWidth = 140.dp) {
						for (kind in SimKind.entries) CompactMenuItem(tr("sim.kind.${kind.jsonName}"), {
							newMenuOpen = false
							viewModel.createSimulationFromSelection(kind)?.let(onCreated)
						})
					}
				}
				PanelToolButton(labels[1], showLabel = labelsShown > 1, onClick = viewModel::bakeAllSimulations,
					enabled = sims.any { it.enabled } && baking == null && !state.canvasEditBusy,
					tooltip = tr(if (outdated) "sim.bakeAllTip" else "sim.rebakeAllTip")) {
					SimSectionIconView(SimSectionIcon.BAKE, if (outdated) colors.warning else colors.textPrimary, size = 11.dp)
				}
				Spacer(Modifier.weight(1f))
				PanelToolButton(labels[2], showLabel = labelsShown > 2, onClick = { viewModel.setSimulationPreview(null) },
					enabled = selected != null, active = !live, tooltip = tr("sim.previewExportTip")) {
					IconEye(visible = true, modifier = Modifier.size(12.dp), tint = if (!live) colors.accent else colors.textMuted)
				}
				PanelToolButton(labels[3], showLabel = labelsShown > 3, onClick = { viewModel.setSimulationPreview(selected?.id) },
					enabled = selected != null, active = live, tooltip = tr("sim.previewReferenceTip")) {
					IconPlay(modifier = Modifier.size(12.dp), tint = if (live) colors.accent else colors.textMuted)
				}
				if (live) CompactIconButton(onClick = viewModel::restartSimulationPreview, tooltip = tr("sim.restart"), size = 22.dp) {
					IconReset(modifier = Modifier.size(11.dp), tint = colors.textPrimary)
				}
				CompactIconButton(onClick = { onOpenSections(SECTIONS) }, size = 22.dp, tooltip = tr("canvas.hierarchy.expandAll")) {
					IconExpandAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
				}
				CompactIconButton(onClick = { onOpenSections(emptySet()) }, size = 22.dp, tooltip = tr("canvas.hierarchy.collapseAll")) {
					IconCollapseAll(modifier = Modifier.size(11.dp), tint = colors.textMuted)
				}
			}
		}
		Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
			BakeAllStatus(viewModel, sims, states, Modifier.weight(1f))
		}
	}
}

/**
 * How many bodies export as set up; task progress is displayed in the status bar.
 */
@Composable
private fun RowScope.BakeAllStatus(
	viewModel: PSD2LiveViewModel,
	sims: List<RigSimEdit>,
	states: Map<String, BakeState>,
	modifier: Modifier,
) {
	val colors = LocalToolColors.current
	val caption = LocalToolTypography.current.caption.copy(fontSize = 10.sp)
	val baking by viewModel.simulationBaking.collectAsState()
	val enabled = sims.count { it.enabled }
	val outdated = states.values.count { it == BakeState.UNBAKED || it == BakeState.STALE }
	if (baking != null) return
	Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		if (enabled == 0) return@Row
		Dot(if (outdated > 0) colors.warning else colors.success)
		Text(if (outdated > 0) tr("sim.outdated", outdated, enabled) else tr("sim.allBaked", enabled, enabled),
			style = caption, color = if (outdated > 0) colors.warning else colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape))

/** One body, styled like a physics group row; right-click for bake, clear and delete. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SimulationRow(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	sim: RigSimEdit,
	bakeState: BakeState,
	selected: Boolean,
	onSelect: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interaction = remember { MutableInteractionSource() }
	val hovered by interaction.collectIsHoveredAsState()
	var menuOpen by remember { mutableStateOf(false) }
	var menuOffset by remember { mutableStateOf(Offset.Zero) }
	val live = state.simulationPreviewId == sim.id
	Box {
		Row(
			Modifier
				.fillMaxWidth()
				.height(24.dp)
				.background(
					when {
						selected -> colors.selection.copy(alpha = 0.35f)
						hovered -> colors.controlHover.copy(alpha = 0.35f)
						else -> Color.Transparent
					},
				)
				.drawWithContent {
					drawContent()
					if (selected) drawRect(colors.accent, size = Size(2.dp.toPx(), size.height))
				}
				.hoverable(interaction)
				.onPointerEvent(PointerEventType.Press) { event ->
					if (event.button == PointerButton.Secondary) {
						menuOffset = event.changes.firstOrNull()?.position ?: Offset.Zero
						menuOpen = true
						onSelect()
						event.changes.firstOrNull()?.consume()
					}
				}
				.clickable(interactionSource = interaction, indication = null, onClick = onSelect)
				.padding(start = 6.dp, end = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			CompactCheckbox(sim.enabled, { viewModel.putSimulation(sim.copy(enabled = it)) }, enabled = !state.canvasEditBusy)
			Text(
				sim.name,
				style = typography.body.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
				color = when {
					!sim.enabled -> colors.textMuted
					selected -> colors.accent
					else -> colors.textPrimary
				},
				maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
			)
			// Baked is the usual state: only a dot. What needs attention is named.
			when {
				live -> StatusDot(colors.warning, tr("sim.previewReference"))
				bakeState == BakeState.BAKED -> Dot(bakeState.color())
				else -> StatusDot(bakeState.color(), bakeState.label())
			}
			RowBadge(tr("sim.kind.${sim.kind.jsonName}"))
		}
		TreeContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, clickOffset = menuOffset, minWidth = 160.dp) {
			SimulationMenuItems(viewModel, state, sim) { menuOpen = false }
		}
	}
}

@Composable
private fun SimulationMenuItems(viewModel: PSD2LiveViewModel, state: PSD2LiveState, sim: RigSimEdit, dismiss: () -> Unit) {
	val baking by viewModel.simulationBaking.collectAsState()
	val idle = baking == null && !state.canvasEditBusy
	CompactMenuItem(tr(if (sim.bake == null) "sim.bakeAction" else "sim.rebake"), { dismiss(); viewModel.bakeSimulation(sim.id) }, enabled = idle && sim.enabled)
	CompactMenuItem(tr("sim.clearBake"), { dismiss(); viewModel.clearSimulationBake(sim.id) }, enabled = idle && sim.bake != null)
	CompactMenuDivider()
	CompactMenuItem(tr("sim.delete"), { dismiss(); viewModel.deleteSimulation(sim.id) }, enabled = !state.canvasEditBusy, danger = true)
}

@Composable
private fun StatusLine(status: PSD2LiveViewModel.SimulationStatus) {
	val colors = LocalToolColors.current
	val style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp)
	val modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
	when (status) {
		PSD2LiveViewModel.SimulationStatus.Idle -> {}
		PSD2LiveViewModel.SimulationStatus.Preparing -> Text(tr("sim.preparing"), style = style, color = colors.textMuted, modifier = modifier)
		is PSD2LiveViewModel.SimulationStatus.Running -> if (status.notes.isNotEmpty()) Column(modifier) { status.notes.forEach { Hint(it) } }
		is PSD2LiveViewModel.SimulationStatus.Failed -> Text(status.message, style = style, color = colors.error, modifier = modifier)
	}
}

@Composable
private fun SimulationEditor(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	puppet: PuppetModel,
	sim: RigSimEdit,
	bakeState: BakeState,
	open: Set<String>,
	onOpen: (Set<String>) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val caption = typography.caption.copy(fontSize = 9.5.sp)
	fun commit(next: RigSimEdit) { if (next != sim) viewModel.putSimulation(next) }
	fun section(key: String) = key in open
	fun toggle(key: String) = onOpen(if (key in open) open - key else open + key)

	Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(sim.name, style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = colors.textPrimary,
				maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
			CompactDropdown(SimKind.entries, sim.kind, { commit(sim.copy(kind = it, material = SimMaterial.preset(it))) },
				Modifier.width(88.dp), itemLabel = { tr("sim.kind.${it.jsonName}") }, height = 22.dp)
		}
	}
	PhysicsRowDivider()

	@Composable fun icon(icon: SimSectionIcon): @Composable () -> Unit = { SimSectionIconView(icon, colors.accent) }
	PhysicsSection(tr("sim.bake"), section("bake"), { toggle("bake") }, icon = icon(SimSectionIcon.BAKE)) {
		BakeEditor(viewModel, state, puppet, sim, bakeState, ::commit)
	}

	val preset = SimMaterial.preset(sim.kind)
	val resetMaterial: @Composable RowScope.() -> Unit = {
		CompactIconButton(onClick = { commit(sim.copy(material = preset)) }, tooltip = tr("sim.materialReset"), size = 18.dp) {
			IconReset(modifier = Modifier.size(11.dp), tint = colors.textMuted)
		}
	}
	PhysicsSection(tr("sim.material"), section("material"), { toggle("material") }, icon = icon(SimSectionIcon.MATERIAL), trailing = resetMaterial.takeIf { sim.material != preset }) {
		MaterialEditor(sim.material) { commit(sim.copy(material = it)) }
	}

	PhysicsSection(tr("sim.inputs"), section("inputs"), { toggle("inputs") }, count = sim.inputs.size, icon = icon(SimSectionIcon.INPUTS)) {
		if (sim.inputs.isEmpty()) Text(tr("sim.inputsDefault"), style = caption, color = colors.textMuted)
		for (input in sim.inputs) RemovableRow(input.parameter) { commit(sim.copy(inputs = sim.inputs - input)) }
		val available = puppet.parameters.map { it.id.raw }.filter { id -> sim.inputs.none { it.parameter == id } }
		if (available.isNotEmpty()) CompactDropdown(listOf<String?>(null) + available, null, { id ->
			if (id != null) commit(sim.copy(inputs = sim.inputs + PhysicsInput(id, type = PhysicsSourceType.ANGLE)))
		}, Modifier.fillMaxWidth(), itemLabel = { it ?: tr("sim.addInput") }, height = 22.dp)
	}

	PhysicsSection(tr("sim.colliders"), section("colliders"), { toggle("colliders") }, count = sim.colliders.size, icon = icon(SimSectionIcon.COLLIDERS)) {
		for (collider in sim.colliders) {
			val groups = puppet.vertexGroups.filter { it.drawableId.raw == collider.drawableId && it.kind == VertexGroupKind.COLLIDER }.map { it.name }
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(collider.drawableId, style = caption, color = colors.textPrimary,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				CompactDropdown(listOf<String?>(null) + groups, collider.group, { group ->
					commit(sim.copy(colliders = sim.colliders.map { if (it === collider) it.copy(group = group) else it }))
				}, Modifier.width(96.dp), itemLabel = { it ?: tr("sim.wholeMesh") }, height = 22.dp)
				RemoveButton { commit(sim.copy(colliders = sim.colliders - collider)) }
			}
		}
		val candidates = selectedMeshes(state).filter { it !in sim.targets && sim.colliders.none { c -> c.drawableId == it } }
		CompactButton(if (candidates.isEmpty()) tr("sim.collidersHint") else tr("sim.addColliders", candidates.size), {
			commit(sim.copy(colliders = sim.colliders + candidates.map { id ->
				SimColliderRef(id, puppet.vertexGroups.firstOrNull { it.drawableId.raw == id && it.kind == VertexGroupKind.COLLIDER }?.name)
			}))
		}, Modifier.fillMaxWidth(), enabled = candidates.isNotEmpty(), height = 22.dp)
	}

	val glues = puppet.glues.filter { it.meshA.raw in sim.targets || it.meshB.raw in sim.targets }
	PhysicsSection(tr("sim.glue"), section("glue"), { toggle("glue") }, count = glues.size, icon = icon(SimSectionIcon.GLUE)) {
		if (glues.isEmpty()) Text(tr("sim.noGlue"), style = caption, color = colors.textMuted)
		for (glue in glues) {
			val key = glueKey(glue)
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(key.replace("|", " ↔ "), style = caption, color = colors.textPrimary,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
				CompactDropdown(GlueRole.entries, sim.glueRoles[key] ?: GlueRole.IGNORE, { role ->
					commit(sim.copy(glueRoles = if (role == GlueRole.IGNORE) sim.glueRoles - key else sim.glueRoles + (key to role)))
				}, Modifier.width(96.dp), itemLabel = { tr("sim.glueRole.${it.jsonName}") }, height = 22.dp)
			}
		}
	}

	PhysicsSection(tr("sim.groups"), section("groups"), { toggle("groups") }, icon = icon(SimSectionIcon.GROUPS)) {
		GroupsEditor(viewModel, puppet, sim)
	}
}

@Composable
private fun RemoveButton(onRemove: () -> Unit) =
	CompactIconButton(onClick = onRemove, tooltip = tr("sim.remove"), size = 18.dp) {
		IconClose(modifier = Modifier.size(9.dp), tint = LocalToolColors.current.textMuted)
	}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp), color = LocalToolColors.current.textPrimary,
			maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
		RemoveButton(onRemove)
	}
}

/**
 * The bake: how well it matches the simulation, Bake / Clear, then the settings used most (auto bake, modes,
 * keys, exaggeration) with the rest folded under Advanced. Only a baked simulation exports.
 */
@Composable
private fun BakeEditor(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	puppet: PuppetModel,
	sim: RigSimEdit,
	bakeState: BakeState,
	commit: (RigSimEdit) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val caption = typography.caption.copy(fontSize = 9.5.sp)
	val baking by viewModel.simulationBaking.collectAsState()
	var advanced by remember { mutableStateOf(false) }
	val bake = sim.bake
	val running = baking?.takeIf { it.id == sim.id }

	when {
		running != null -> Unit
		bakeState == BakeState.DISABLED -> Text(tr("sim.disabledHint"), style = caption, color = colors.textMuted)
		bake == null -> Text(tr("sim.notBaked"), style = caption, color = colors.textMuted)
		else -> {
			if (bakeState == BakeState.STALE) Text(tr("sim.bakeStale"), style = caption, color = colors.warning)
			Text(tr("sim.bakedQuality", String.format(Locale.US, "%.2f", bake.fit), String.format(Locale.US, "%.1f", bake.maxErrorPx)),
				style = caption, color = if (bake.fit < 0.8f) colors.warning else colors.textMuted)
			if (bake.clipped > 0f || bake.jerk > 1.5f) Text(tr("sim.bakedMotion", (bake.peak * 100f).toInt(),
				String.format(Locale.US, "%.1f", bake.clipped * 100f), String.format(Locale.US, "%.2f", bake.jerk)), style = caption, color = colors.warning)
			remember(puppet, sim) { SimGenerator.issues(puppet, sim) }.forEach { Hint(it) }
		}
	}
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		CompactButton(tr(if (bake == null) "sim.bakeAction" else "sim.rebake"), { viewModel.bakeSimulation(sim.id) }, Modifier.weight(1f),
			enabled = baking == null && sim.enabled && !state.canvasEditBusy,
			isPrimary = bakeState == BakeState.UNBAKED || bakeState == BakeState.STALE, height = 22.dp)
		CompactButton(tr("sim.clearBake"), { viewModel.clearSimulationBake(sim.id) }, Modifier.weight(1f),
			enabled = bake != null && baking == null && !state.canvasEditBusy, height = 22.dp)
	}

	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.autoBake"), tooltip = tr("sim.autoBakeTip"))
		CompactCheckbox(sim.autoBake, { commit(sim.copy(autoBake = it)) })
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.modes"), tooltip = tr("sim.modesTip"))
		CompactDropdown((1..RigSimEdit.MAX_MODES).toList(), sim.modes, { commit(sim.copy(modes = it)) }, Modifier.weight(1f),
			itemLabel = { "$it" }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.keys"), tooltip = tr("sim.keysTip"))
		CompactDropdown(RigSimEdit.KEY_COUNTS.filter { it % 2 == 1 }, sim.keys, { commit(sim.copy(keys = it)) }, Modifier.weight(1f),
			itemLabel = { "$it" }, height = 22.dp)
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.exaggeration"), tooltip = tr("sim.exaggerationTip"))
		CompactDropdown((listOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f) + sim.exaggeration).distinct().sorted(), sim.exaggeration,
			{ commit(sim.copy(exaggeration = it)) }, Modifier.weight(1f), itemLabel = { "×" + String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') }, height = 22.dp)
	}

	PanelSectionRow(tr("sim.advanced"), advanced, { advanced = !advanced }, icon = null)
	if (!advanced) return
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.blendShapes"), tooltip = tr("sim.blendShapesTip"))
		CompactDropdown(listOf(null, true, false), sim.blendShapes, { commit(sim.copy(blendShapes = it)) }, Modifier.weight(1f),
			itemLabel = { tr(when (it) { null -> "sim.blendShapesAuto"; true -> "sim.blendShapesOn"; false -> "sim.blendShapesOff" }) }, height = 22.dp)
	}
	if (sim.blendShapes == true && !puppet.runtimeTarget.supports(RuntimeFeature.MeshWarpBlendShapes))
		Text(tr("sim.blendShapesUnavailable"), style = caption, color = colors.textMuted)
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(tr("sim.staticInputs"), tooltip = tr("sim.staticInputsTip"))
		CompactCheckbox(sim.staticInputs == null, { auto -> commit(sim.copy(staticInputs = if (auto) null else emptyList())) }, label = tr("sim.staticAuto"))
	}
	val statics = sim.staticInputs
	if (statics != null) {
		for (parameter in statics) RemovableRow(parameter) { commit(sim.copy(staticInputs = statics - parameter)) }
		val available = puppet.parameters.map { it.id.raw }.filter { it !in statics && bake?.parameters?.contains(it) != true }
		if (statics.size < RigSimEdit.MAX_STATIC_INPUTS && available.isNotEmpty()) CompactDropdown(listOf<String?>(null) + available, null, { id ->
			if (id != null) commit(sim.copy(staticInputs = statics + id))
		}, Modifier.fillMaxWidth(), itemLabel = { it ?: tr("sim.addStaticInput") }, height = 22.dp)
	}
	if (bake != null) {
		for (mode in bake.modes) Text(tr("sim.bakedMode", mode.axis.parameter, String.format(Locale.US, "%.1f", mode.amplitude)), style = caption, color = colors.textMuted)
		if (bake.statics.isNotEmpty()) Text(tr("sim.bakedStatics", bake.statics.joinToString { it.parameter }), style = caption, color = colors.textMuted)
	}
}

@Composable
private fun MaterialEditor(material: SimMaterial, onCommit: (SimMaterial) -> Unit) {
	// Sliders move a local draft and commit once on release, so a drag is one history node.
	var draft by remember(material) { mutableStateOf(material) }
	DraftSlider(tr("sim.stretch"), tr("sim.stretchTip"), draft.stretch, 0f..1f, { draft = draft.copy(stretch = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.bend"), tr("sim.bendTip"), draft.bend, 0f..1f, { draft = draft.copy(bend = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.goal"), tr("sim.goalTip"), draft.goal, 0f..1f, { draft = draft.copy(goal = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.damping"), tr("sim.dampingTip"), draft.damping, 0f..8f, { draft = draft.copy(damping = it) }) { onCommit(draft) }
	DraftSlider(tr("sim.mass"), tr("sim.massTip"), draft.mass, 0.2f..4f, { draft = draft.copy(mass = it) }) { onCommit(draft) }
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

/** The kinds a simulation reads per vertex, in the order they are usually painted. */
private val SIM_GROUP_KINDS = listOf(VertexGroupKind.PIN, VertexGroupKind.COLLIDE, VertexGroupKind.STIFFNESS, VertexGroupKind.GOAL,
	VertexGroupKind.MASS, VertexGroupKind.DAMPING)

/** Whether the targets have each kind's group, and Paint to open the weight brush on it. */
@Composable
private fun GroupsEditor(viewModel: PSD2LiveViewModel, puppet: PuppetModel, sim: RigSimEdit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	for (kind in SIM_GROUP_KINDS) {
		val painted = puppet.vertexGroups.any { it.drawableId.raw in sim.targets && it.kind == kind }
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			VertexGroupKindIcon(kind, vertexGroupKindColor(kind), size = 12.dp)
			FieldLabel(tr("sim.group.${kind.jsonName}"), width = 52, tooltip = tr("sim.groupTip.${kind.jsonName}"))
			Text(tr(if (painted) "sim.painted" else "sim.noGroup"), style = typography.caption.copy(fontSize = 9.5.sp),
				color = if (painted) colors.textPrimary else colors.textMuted, modifier = Modifier.weight(1f))
			CompactButton(tr("sim.paint"), { viewModel.beginVertexGroupPaint(sim.targets.first(), kind) }, height = 22.dp)
		}
	}
}

/** The meshes of the selected layers. */
private fun selectedMeshes(state: PSD2LiveState): List<String> {
	val model = state.previewModel ?: return emptyList()
	val layers = state.selectedLayerIds.ifEmpty { setOfNotNull(state.selectedLayerId) }
	return model.rig.layerIdByDrawableId.filter { (drawable, layer) ->
		layer in layers && model.rig.puppet.drawables.any { it.id.raw == drawable && it.mesh != null }
	}.keys.sorted()
}
