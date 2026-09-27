package io.github.psd2live.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsGenerator
import io.github.psd2live.core.PhysicsGroup
import io.github.psd2live.core.PhysicsInput
import io.github.psd2live.core.PhysicsIssue
import io.github.psd2live.core.PhysicsNormalization
import io.github.psd2live.core.PhysicsOrigin
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.PhysicsDrag
import io.github.psd2live.core.PhysicsResponse
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactMenuSection
import io.github.psd2live.ui.components.CompactNumberSpinner
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.CompactSlider
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.components.CompactToggleChip
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconClose
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import org.umamo.runtime.model.Parameter
import java.awt.Cursor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val PHYSICS_FIELD = "physics-field"

/**
 * Physics: every pendulum the model exports, generated or the user's, in one list. The selected group is
 * edited on its pendulum - drag a vertex to set a segment's length, drag elsewhere to shake it - with
 * sliders for what has no place on it and the raw numbers folded under Advanced.
 */
@Composable
internal fun PhysicsPanelView(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val previewState = state.previewPanelState()
	val groups = viewModel.physicsGroups(state)
	var selectedId by remember { mutableStateOf<String?>(null) }
	val selected = groups.firstOrNull { it.id == selectedId } ?: groups.firstOrNull()
	val parameters = state.previewModel?.rig?.puppet?.parameters.orEmpty()

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(colors.panelBackground)
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 8.dp, vertical = 6.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		PhysicsHeaderCard(viewModel, previewState, groups)

		CompactSectionHeader(
			title = tr("physics.groups"),
			trailing = {
				NewPhysicsButton(viewModel, selected) { id -> selectedId = id }
			},
		)
		if (state.previewModel == null) {
			Text(tr("physics.noModel"), style = typography.caption, color = colors.textMuted, modifier = Modifier.padding(4.dp))
			return@Column
		}
		PhysicsGroupList(groups, selected?.id, state.generatePhysics && !state.meshOnly,
			onSelect = { selectedId = it }, onEnabled = viewModel::setPhysicsGroupEnabled)

		if (selected != null) {
			PhysicsGroupEditor(viewModel, state, selected, groups, parameters) { id -> selectedId = id }
		}
		Spacer(Modifier.height(8.dp))
	}
}

/** The global switch, how many groups export, and the preview's play/tracking so inputs can be tried. */
@Composable
private fun PhysicsHeaderCard(viewModel: PSD2LiveViewModel, state: PSD2LiveState, groups: List<PhysicsGroup>) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val on = state.generatePhysics && !state.meshOnly
	val playing = state.animationEnabled && !state.meshOnly
	Column(
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.windowBackground)
			.border(BorderStroke(1.dp, if (on) colors.accent.copy(alpha = 0.45f) else colors.divider), RoundedCornerShape(4.dp))
			.padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			CompactCheckbox(state.generatePhysics, viewModel::setGeneratePhysics, label = tr("physics.enableSimulation"),
				enabled = !state.meshOnly, modifier = Modifier.weight(1f))
			Text(
				tr("physics.activeCount", groups.count { it.active }, groups.size),
				style = typography.caption.copy(fontSize = 9.5.sp),
				color = if (on) colors.accent else colors.textMuted,
			)
		}
		if (state.meshOnly) Text(tr("physics.meshOnly"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.warning)
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			CompactButton(
				text = tr(if (playing) "animation.pause" else "animation.play"),
				onClick = { viewModel.setAnimationEnabled(!state.animationEnabled) },
				leadingIcon = {
					if (playing) IconPause(modifier = Modifier.size(10.dp), tint = colors.textPrimary)
					else IconPlay(modifier = Modifier.size(10.dp), tint = colors.accent)
				},
				height = 22.dp,
			)
			CompactCheckbox(state.mouseTrackingEnabled, viewModel::setMouseTrackingEnabled, label = tr("animation.mouseTracking"))
		}
	}
}

@Composable
private fun NewPhysicsButton(viewModel: PSD2LiveViewModel, selected: PhysicsGroup?, onCreated: (String) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		CompactButton(text = "+ ${tr("physics.new")}", onClick = { open = true }, height = 18.dp)
		TreeContextMenu(expanded = open, onDismissRequest = { open = false }) {
			CompactMenuItem(text = tr("physics.newBlank"), onClick = {
				open = false
				viewModel.createPhysicsGroup()?.let(onCreated)
			})
			CompactMenuItem(text = tr("physics.duplicate"), enabled = selected != null, onClick = {
				open = false
				selected?.let { viewModel.createPhysicsGroup(it.setting)?.let(onCreated) }
			})
			CompactMenuDivider()
			val targets = viewModel.canvasEditor.swingTargets()
			CompactMenuItem(text = tr("physics.newSwing"), enabled = targets.isNotEmpty(), onClick = {
				open = false
				viewModel.beginSwing(targets)
			})
		}
	}
}

@Composable
private fun PhysicsGroupList(
	groups: List<PhysicsGroup>,
	selectedId: String?,
	globalOn: Boolean,
	onSelect: (String) -> Unit,
	onEnabled: (String, Boolean) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	if (groups.isEmpty()) {
		Text(tr("physics.empty"), style = typography.caption.copy(fontSize = 10.sp), color = colors.textMuted, modifier = Modifier.padding(4.dp))
		return
	}
	Column(
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.windowBackground)
			.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)).padding(vertical = 2.dp),
	) {
		for (group in groups) {
			val isSelected = group.id == selectedId
			Row(
				modifier = Modifier.fillMaxWidth().height(24.dp)
					.background(if (isSelected) colors.selection.copy(alpha = 0.55f) else Color.Transparent)
					.clickable { onSelect(group.id) }
					.padding(horizontal = 6.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				CompactCheckbox(group.enabled, { onEnabled(group.id, it) })
				Text(
					group.setting.name,
					style = typography.body.copy(fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
					color = if (group.active && globalOn) colors.textPrimary else colors.textMuted,
					maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
				)
				when {
					group.issue != null -> StatusDot(colors.warning, tr("physics.issue.short"))
					group.shadowedBy != null -> StatusDot(colors.textDisabled, tr("physics.replaced.short"))
					group.overridden -> StatusDot(colors.accent, tr("physics.modified"))
				}
				OriginBadge(group.origin)
			}
		}
	}
}

@Composable
private fun StatusDot(color: Color, label: String) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
		Box(Modifier.size(6.dp).clip(CircleShape).background(color))
		Text(label, style = LocalToolTypography.current.caption.copy(fontSize = 9.sp), color = color, maxLines = 1)
	}
}

@Composable
private fun OriginBadge(origin: PhysicsOrigin) {
	val colors = LocalToolColors.current
	Box(
		Modifier.clip(RoundedCornerShape(3.dp)).background(colors.controlBackground).padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(tr("physics.origin.${origin.name.lowercase()}"), style = LocalToolTypography.current.caption.copy(fontSize = 9.sp), color = colors.textMuted)
	}
}

private fun issueText(issue: PhysicsIssue): String = when (issue.code) {
	PhysicsIssue.Code.MISSING_PARAMETER -> tr("physics.issue.missing", issue.parameter.orEmpty())
	PhysicsIssue.Code.NO_INPUT -> tr("physics.issue.noInput")
	PhysicsIssue.Code.NO_OUTPUT -> tr("physics.issue.noOutput")
	PhysicsIssue.Code.FEEDBACK -> tr("physics.issue.feedback", issue.parameter.orEmpty())
	PhysicsIssue.Code.OUTPUT_TAKEN -> tr("physics.issue.taken", issue.parameter.orEmpty(), issue.group.orEmpty())
}

/** The selected group: its pendulum, dynamics, inputs, outputs and the raw numbers. */
@Composable
private fun PhysicsGroupEditor(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	group: PhysicsGroup,
	groups: List<PhysicsGroup>,
	parameters: List<Parameter>,
	onSelect: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val setting = group.setting
	// null edits every segment at once.
	var segment by remember(group.id) { mutableStateOf<Int?>(null) }
	val shownSegment = segment?.takeIf { it < setting.segments.size }
	// The output whose range fan the pendulum shows.
	var fanOutput by remember(group.id) { mutableStateOf<Int?>(null) }
	val shownOutput = fanOutput?.takeIf { it < setting.outputs.size }
	var advanced by remember { mutableStateOf(false) }
	val byId = remember(parameters) { parameters.associateBy { it.id.raw } }
	val ranges = remember(parameters) { PhysicsEngine.ranges(parameters) }
	// Lambdas remembered on what they read, not local function references: those are cached without their
	// captures, which left gestures editing a stale copy of the group.
	val label: (String) -> String = remember(byId) { { id -> byId[id]?.name?.takeIf { it.isNotBlank() && it != id } ?: id } }
	// Every edit applies to the group as it is now, so a gesture that outlives a recomposition sees earlier edits.
	val edit: ((RigPhysicsEdit) -> RigPhysicsEdit) -> Unit = remember(viewModel, group.id) {
		{ change ->
			val current = viewModel.physicsGroups().firstOrNull { it.id == group.id }?.setting
			val next = current?.let { runCatching { change(it) }.getOrNull() }
			if (next != null && next != current) viewModel.putPhysicsGroup(next)
		}
	}
	val driven = remember(groups, group.id) { io.github.psd2live.core.PhysicsAuthoring.drivenParameters(groups, group.id) }

	Column(
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.windowBackground)
			.border(BorderStroke(1.dp, colors.divider), RoundedCornerShape(4.dp)).padding(8.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		GroupTitle(viewModel, state, group, onSelect) { name -> edit { it.copy(name = name) } }

		PendulumEditor(
			viewModel = viewModel,
			state = state,
			setting = setting,
			ranges = ranges,
			selectedSegment = shownSegment,
			selectedOutput = shownOutput,
			label = label,
			onSelectSegment = { segment = it },
			onSelectOutput = { fanOutput = it },
			edit = edit,
		)

		SegmentBar(
			count = setting.segments.size,
			selected = shownSegment,
			onSelect = { segment = it },
			onAdd = { edit { it.withSegmentCount(it.segments.size + 1) }; segment = setting.segments.size },
			onRemove = {
				val index = shownSegment ?: (setting.segments.size - 1)
				edit { it.withoutSegment(index) }
				segment = null
			},
		)
		DynamicsSliders(viewModel, setting, shownSegment, edit)
		ResponseGraph(setting, ranges, label)

		CompactSectionHeader(tr("physics.inputs"), trailing = {
			AddParameterButton(tr("physics.addInput"), parameters, exclude = setting.parameters.toSet(), driven = emptyMap(), label = label,
				presets = listOf(tr("physics.inputs.headBody") to {
					edit { it.copy(inputs = PhysicsGenerator.headAndBodyInputs(byId.keys).filter { i -> i.parameter !in it.outputParameters }) }
				})) { id ->
				edit { it.copy(inputs = it.inputs + PhysicsInput(id, 50f, if (id.endsWith("AngleZ")) PhysicsSourceType.ANGLE else PhysicsSourceType.X)) }
			}
		})
		if (setting.inputs.isEmpty()) Hint(tr("physics.inputs.empty"))
		setting.inputs.forEachIndexed { index, input ->
			InputRow(viewModel, input, parameters, setting, label,
				onChange = { next -> edit { it.copy(inputs = it.inputs.mapIndexed { i, x -> if (i == index) next else x }) } },
				onRemove = { edit { it.copy(inputs = it.inputs.filterIndexed { i, _ -> i != index }) } })
		}

		CompactSectionHeader(tr("physics.outputs"), trailing = {
			AddParameterButton(tr("physics.addOutput"), parameters, exclude = setting.parameters.toSet(), driven = driven, label = label) { id ->
				edit { it.copy(outputs = it.outputs + PhysicsOutput(id, shownSegment?.plus(1) ?: it.segments.size, 1f)) }
			}
		})
		if (setting.outputs.isEmpty()) Hint(tr("physics.outputs.empty"))
		setting.outputs.forEachIndexed { index, output ->
			OutputRow(viewModel, index, output, index == shownOutput, { fanOutput = if (shownOutput == index) null else index },
				parameters, setting, driven, label,
				onChange = { next -> edit { it.copy(outputs = it.outputs.mapIndexed { i, x -> if (i == index) next else x }) } },
				onRemove = { edit { it.copy(outputs = it.outputs.filterIndexed { i, _ -> i != index }) } })
		}

		Row(
			modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			IconChevron(expanded = advanced, tint = colors.textMuted, modifier = Modifier.size(10.dp))
			Text(tr("physics.advanced"), style = typography.caption.copy(fontWeight = FontWeight.SemiBold), color = colors.textMuted)
		}
		if (advanced) AdvancedSection(viewModel, setting, edit)
	}
}

/** Name (renamable), where the group comes from, and why it does or does not export. */
@Composable
private fun GroupTitle(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	group: PhysicsGroup,
	onSelect: (String) -> Unit,
	onRename: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	var renaming by remember(group.id) { mutableStateOf(false) }
	var draft by remember(group.id, group.setting.name) { mutableStateOf(group.setting.name) }
	var menu by remember { mutableStateOf(false) }
	val swing = if (group.origin == PhysicsOrigin.SWING) PhysicsGenerator.swingOf(group.id, state.rigEdits.swingEdits) else null
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		if (renaming) {
			CompactTextField(draft, { draft = it }, modifier = Modifier.weight(1f), height = 22.dp, selectAllOnFocus = true,
				onCommit = { if (draft.isNotBlank()) onRename(draft.trim()); renaming = false },
				onFocusLost = { if (renaming && draft.isNotBlank()) onRename(draft.trim()); renaming = false })
		} else {
			Text(
				group.setting.name,
				style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
				color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f).clickable { renaming = true },
			)
		}
		OriginBadge(group.origin)
		Box {
			CompactIconButton(onClick = { menu = true }, size = 20.dp, tooltip = tr("physics.more")) {
				Text("⋯", style = typography.body.copy(fontSize = 12.sp), color = colors.textMuted)
			}
			TreeContextMenu(expanded = menu, onDismissRequest = { menu = false }) {
				CompactMenuItem(tr("physics.rename"), { menu = false; renaming = true })
				CompactMenuItem(tr("physics.duplicate"), { menu = false; viewModel.createPhysicsGroup(group.setting)?.let(onSelect) })
				if (swing != null) CompactMenuItem(tr("physics.openSwing"), { menu = false; viewModel.beginSwing(swing.targets) })
				CompactMenuDivider()
				CompactMenuItem(tr("physics.resetDefaults"), { menu = false; viewModel.removePhysicsGroup(group.id) }, enabled = group.overridden)
				CompactMenuItem(tr("physics.delete"), { menu = false; viewModel.removePhysicsGroup(group.id) },
					enabled = group.origin == PhysicsOrigin.CUSTOM, danger = true)
			}
		}
	}
	val origin = when (group.origin) {
		PhysicsOrigin.PRESET -> tr("physics.originNote.preset")
		PhysicsOrigin.SKELETON -> tr("physics.originNote.skeleton")
		PhysicsOrigin.SWING -> tr("physics.originNote.swing", swing?.name ?: "")
		PhysicsOrigin.CUSTOM -> tr("physics.originNote.custom")
	}
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(origin, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
		if (group.overridden) {
			Text(
				tr("physics.resetDefaults"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.accent,
				modifier = Modifier.clickable { viewModel.removePhysicsGroup(group.id) }.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
			)
		}
	}
	group.issue?.let { Text(issueText(it), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.warning) }
	group.shadowedBy?.let { Text(tr("physics.replacedBy", it), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted) }
	if (!group.enabled) Text(tr("physics.disabledNote"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
}

/** Output colors, shared by the pendulum, the response curve and the output rows. */
private val OUTPUT_COLORS = listOf(Color(0xFF5C9CF5), Color(0xFFE5A04B), Color(0xFF5CC08A), Color(0xFFD27AD6), Color(0xFFE06C75), Color(0xFF56C2C9))

private fun outputColor(index: Int) = OUTPUT_COLORS[index % OUTPUT_COLORS.size]

/** Half an output parameter's range: the most it moves either way from its default. */
private fun halfRange(range: PhysicsEngine.Range?) =
	range?.let { maxOf(abs(it.max - it.default), abs(it.min - it.default)) }?.coerceAtLeast(1e-6f) ?: 1f

/** What a press on the pendulum grabs. */
private sealed interface PendulumHandle {
	/** A vertex of the rest pose: drag it up or down for its segment's length. */
	data class Length(val segment: Int) : PendulumHandle
	/** An output's label at the side: drop it level with another vertex to read that segment. */
	data class Label(val output: Int) : PendulumHandle
	/** The edge of the selected output's range fan: drag it to widen or narrow the range. */
	data class Range(val output: Int) : PendulumHandle
}

/** What the pendulum canvas keeps between frames. */
private class PendulumRuntime {
	var engine: PhysicsEngine? = null
	val drag = PhysicsDrag()
	var outputs: Map<String, Float> = emptyMap()
	/** Where each handle was last drawn, for hit testing, in drawing order (last on top). */
	var handles: List<Pair<PendulumHandle, Offset>> = emptyList()
	/** Rest vertices as last drawn, root first. */
	var rest: List<Offset> = emptyList()
	/** Each output's pivot and the direction its angle is measured from, as last drawn. */
	var pivots: Map<Int, Pair<Offset, Offset>> = emptyMap()
	var active by mutableStateOf<PendulumHandle?>(null)
	var hovered by mutableStateOf<PendulumHandle?>(null)
	/** The scale kept while anything is dragged, so the pendulum does not rescale under the pointer. */
	var frozenScale = 0f
	var pointer = Offset.Zero
	/** The vertex a dragged output label would read if dropped now. */
	var dropVertex by mutableStateOf<Int?>(null)

	val setting: RigPhysicsEdit? get() = engine?.strands?.firstOrNull()?.setting
}

/** Where the pendulum hangs in a canvas of [width] x [height]: pixels per unit and the plumb line. */
private fun pendulumScale(setting: RigPhysicsEdit, width: Float, height: Float): Float {
	val total = setting.totalLength.coerceAtLeast(0.01f)
	// Leave room for the root's travel at a full-range sideways input, and for the swing itself.
	val travel = setting.normalization.positionMax * setting.inputs.filter { it.type == PhysicsSourceType.X }.sumOf { it.weight.toDouble() }.toFloat() / 100f
	val byHeight = (height - PENDULUM_TOP - PENDULUM_BOTTOM) / total
	val byWidth = (width * PENDULUM_AREA / 2f - 10f) / (abs(travel) + total * 0.45f)
	return minOf(byHeight, byWidth).coerceAtLeast(0.5f)
}

private const val PENDULUM_TOP = 22f
private const val PENDULUM_BOTTOM = 24f
/** The share of the canvas width the pendulum swings in; the output labels take the rest. */
private const val PENDULUM_AREA = 0.6f
private const val MIN_ARC = 0.035f
private const val MAX_ARC = 2.9f

/**
 * The selected pendulum. Solid is the live strand under the model's inputs; dashed is its rest pose,
 * hanging from the live root, with a ring per vertex. Dragging a ring sets that segment's length; each
 * output's label at the side shows its live value and can be dropped level with another vertex; a selected
 * output shows the fan of angles over which its parameter runs end to end, with a handle on its edge.
 * Dragging anywhere else pulls the model the way Cubism's viewer does, and letting go eases it back. The
 * view is mirrored so the root follows the pointer.
 */
@Composable
private fun PendulumEditor(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	setting: RigPhysicsEdit,
	ranges: Map<String, PhysicsEngine.Range>,
	selectedSegment: Int?,
	selectedOutput: Int?,
	label: (String) -> String,
	onSelectSegment: (Int?) -> Unit,
	onSelectOutput: (Int?) -> Unit,
	edit: ((RigPhysicsEdit) -> RigPhysicsEdit) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val measurer = rememberTextMeasurer(cacheSize = 32)
	val runtime = remember { PendulumRuntime() }
	// A retuned pendulum keeps swinging from where it is.
	remember(setting, ranges) {
		PhysicsEngine(listOf(setting), ranges).also { it.carryOver(runtime.engine); runtime.engine = it }
	}
	val previewState = state.previewPanelState()
	val live = previewState.animationEnabled && state.previewLive && !state.meshOnly
	val staticValues = previewState.previewParameterValues.ifEmpty { previewState.parameterValues }
	val inputs by rememberUpdatedState {
		val base = (if (live) viewModel.currentLiveParameters else staticValues).mapKeys { it.key.raw }
		val s = runtime.setting
		if (s == null) base else runtime.drag.apply(base, s.inputs.map { it.parameter }, ranges)
	}
	val editBy by rememberUpdatedState(edit)
	val selectSegment by rememberUpdatedState(onSelectSegment)
	val selectOutput by rememberUpdatedState(onSelectOutput)
	val segmentSelected by rememberUpdatedState(selectedSegment)
	val outputSelected by rememberUpdatedState(selectedOutput)
	var tick by remember { mutableLongStateOf(0L) }

	LaunchedEffect(Unit) {
		var last = 0L
		while (true) withFrameNanos { now ->
			val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
			last = now
			if (dt > 0f) {
				runtime.drag.update(dt)
				runtime.engine?.let { runtime.outputs = it.step(inputs(), dt) }
			}
			tick = now
		}
	}

	fun hit(p: Offset, radius: Float): PendulumHandle? =
		runtime.handles.lastOrNull { (handle, at) -> handle !is PendulumHandle.Label && (at - p).getDistance() < radius }?.first
			?: runtime.handles.lastOrNull { (handle, at) -> handle is PendulumHandle.Label && p.x >= at.x - 8f && abs(p.y - at.y) < 9f }?.first

	Box(
		modifier = Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(3.dp)).background(colors.inputBackground)
			.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp)),
	) {
		val cursor = when (runtime.active ?: runtime.hovered) {
			is PendulumHandle.Length -> Cursor.N_RESIZE_CURSOR
			is PendulumHandle.Label -> Cursor.MOVE_CURSOR
			is PendulumHandle.Range -> Cursor.CROSSHAIR_CURSOR
			null -> Cursor.HAND_CURSOR
		}
		Canvas(
			modifier = Modifier.fillMaxSize()
				.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
				.pointerInput(Unit) {
					awaitEachGesture {
						val down = awaitFirstDown()
						val start = runtime.setting ?: return@awaitEachGesture
						val handle = hit(down.position, 10.dp.toPx())
						val slop = viewConfiguration.touchSlop
						val w = size.width.toFloat()
						val h = size.height.toFloat()
						runtime.frozenScale = pendulumScale(start, w, h)
						runtime.pointer = down.position
						var moved = false
						try {
							do {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { it.id == down.id } ?: break
								if (!change.pressed) break
								val p = change.position
								runtime.pointer = p
								if (!moved && (p - down.position).getDistance() > slop) {
									moved = true
									// A drag edits the handle it began on; a drag from empty space pulls the model.
									if (handle != null) { runtime.active = handle; viewModel.beginEditorGesture() }
								}
								if (moved) when (handle) {
									// Only sideways: the pull turns the head and body, as a horizontal drag in Cubism's viewer.
									null -> runtime.drag.target((p.x - down.position.x) / (w * 0.35f), 0f)
									is PendulumHandle.Length -> {
										val above = runtime.rest.getOrNull(handle.segment) ?: break
										val length = ((p.y - above.y) / runtime.frozenScale).coerceIn(0.2f, 200f)
										editBy { s -> s.copy(segments = s.segments.mapIndexed { i, g -> if (i == handle.segment) g.copy(length = round1(length)) else g }) }
									}
									is PendulumHandle.Range -> {
										val o = start.outputs.getOrNull(handle.output) ?: break
										val (pivot, along) = runtime.pivots[handle.output] ?: break
										val v = p - pivot
										val angle = abs(atan2(along.x * v.y - along.y * v.x, along.x * v.x + along.y * v.y)).coerceIn(MIN_ARC, MAX_ARC)
										val scale = halfRange(ranges[o.parameter]) / angle
										editBy { s -> s.copy(outputs = s.outputs.mapIndexed { i, x -> if (i == handle.output) x.copy(scale = round2(scale)) else x }) }
									}
									is PendulumHandle.Label -> runtime.dropVertex = nearestVertex(runtime.rest, p.y)
								}
								change.consume()
							} while (true)
						} finally {
							when {
								!moved -> when (handle) {
									// A click selects: a vertex (again for all), an output (again for none), or nothing.
									is PendulumHandle.Length -> selectSegment(if (segmentSelected == handle.segment) null else handle.segment)
									is PendulumHandle.Label -> selectOutput(if (outputSelected == handle.output) null else handle.output)
									is PendulumHandle.Range -> Unit
									null -> selectOutput(null)
								}
								handle is PendulumHandle.Label -> {
									// Dropped: the output reads the vertex it was level with from now on.
									val vertex = runtime.dropVertex
									if (vertex != null) editBy { s -> s.copy(outputs = s.outputs.mapIndexed { i, x -> if (i == handle.output) x.copy(vertex = vertex) else x }) }
								}
							}
							if (moved && handle != null) viewModel.endEditorGesture()
							runtime.active = null
							runtime.dropVertex = null
							runtime.drag.release()
						}
					}
				}
				.pointerInput(Unit) {
					awaitPointerEventScope {
						while (true) {
							val event = awaitPointerEvent()
							if (event.type == PointerEventType.Exit) { runtime.hovered = null; continue }
							if (event.type != PointerEventType.Move || runtime.active != null) continue
							runtime.hovered = hit(event.changes.first().position, 10.dp.toPx())
						}
					}
				},
		) {
			if (tick < 0L) return@Canvas // Reading the frame tick redraws only the canvas.
			val engine = runtime.engine ?: return@Canvas
			val strand = engine.strands.firstOrNull() ?: return@Canvas
			val s = strand.setting
			val k = if (runtime.active != null && runtime.frozenScale > 0f) runtime.frozenScale else pendulumScale(s, size.width, size.height)
			val cx = size.width * PENDULUM_AREA / 2f
			val handles = mutableListOf<Pair<PendulumHandle, Offset>>()
			val focus = runtime.active ?: runtime.hovered

			// Mirrored, so a pull to the right moves the root to the right; the root stays on the top line.
			fun live(i: Int) = Offset(cx - strand.x[i] * k, PENDULUM_TOP + (strand.y[i] - strand.y[0]) * k)
			val root = live(0)
			drawLine(colors.border, Offset(cx, 0f), Offset(cx, size.height), 1f)
			drawLine(colors.border.copy(alpha = 0.6f), Offset(0f, PENDULUM_TOP), Offset(size.width * PENDULUM_AREA, PENDULUM_TOP), 1f)

			// Rest pose, hanging from the live root.
			var y = root.y
			val rest = listOf(root) + s.segments.map { g -> y += g.length * k; Offset(root.x, y) }
			runtime.rest = rest
			drawLine(colors.textMuted.copy(alpha = 0.5f), rest.first(), rest.last(), 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
			selectedSegment?.let { i -> if (i + 1 < rest.size) drawLine(colors.accent.copy(alpha = 0.5f), rest[i], rest[i + 1], 3f) }

			// The selected output's fan: its segment swinging across it runs the parameter end to end.
			val pivots = HashMap<Int, Pair<Offset, Offset>>()
			s.outputs.forEachIndexed { j, o ->
				if (o.vertex !in 1 until strand.size) return@forEachIndexed
				val pivot = live(o.vertex - 1)
				val along = if (o.vertex >= 2) (pivot - live(o.vertex - 2)).let { it / it.getDistance().coerceAtLeast(1e-3f) } else Offset(0f, 1f)
				pivots[j] = pivot to along
				if (j != selectedOutput || o.type != PhysicsSourceType.ANGLE) return@forEachIndexed
				val angle = (halfRange(ranges[o.parameter]) / abs(o.scale).coerceAtLeast(1e-3f)).coerceIn(MIN_ARC, MAX_ARC)
				val radius = s.segments[o.vertex - 1].length * k * 0.85f
				val base = Math.toDegrees(atan2(along.y, along.x).toDouble()).toFloat()
				val degrees = Math.toDegrees(angle.toDouble()).toFloat()
				val color = outputColor(j)
				drawArc(color.copy(alpha = 0.16f), base - degrees, degrees * 2f, true,
					topLeft = pivot - Offset(radius, radius), size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f))
				for (side in listOf(-1f, 1f)) {
					val a = atan2(along.y, along.x) + side * angle
					drawLine(color.copy(alpha = 0.8f), pivot, pivot + Offset(cos(a), sin(a)) * radius, 1.2f)
				}
				val edge = pivot + Offset(cos(atan2(along.y, along.x) - angle), sin(atan2(along.y, along.x) - angle)) * radius
				drawCircle(color, if (focus == PendulumHandle.Range(j)) 6f else 4.5f, edge)
				handles += PendulumHandle.Range(j) to edge
			}
			runtime.pivots = pivots

			// Live strand.
			for (i in 1 until strand.size) drawLine(colors.textPrimary, live(i - 1), live(i), 2f)
			for (i in 1 until strand.size) drawCircle(colors.accent, 4.5f, live(i))
			drawCircle(colors.textPrimary, 4f, root)

			// Rings on the rest pose; drawn after the strand so they stay grabbable over it.
			rest.drop(1).forEachIndexed { i, at ->
				val handle = PendulumHandle.Length(i)
				val on = focus == handle || selectedSegment == i
				drawCircle(if (on) colors.accent else colors.textMuted, if (focus == handle) 7f else 5.5f, at, style = Stroke(if (on) 2f else 1.3f))
				handles += handle to at
			}

			// Output labels at the side, level with the vertex each reads, tied to its live position.
			val labelLeft = size.width * PENDULUM_AREA + 6f
			val labelWidth = (size.width - labelLeft - 6f).coerceAtLeast(20f)
			val stacked = HashMap<Int, Int>()
			s.outputs.forEachIndexed { j, o ->
				if (o.vertex >= strand.size) return@forEachIndexed
				val handle = PendulumHandle.Label(j)
				val dragging = runtime.active == handle
				val row = stacked.merge(o.vertex, 1, Int::plus)!! - 1
				val anchorY = if (dragging) runtime.pointer.y else rest[o.vertex].y + row * 26f
				val color = outputColor(j)
				val chosen = j == selectedOutput
				if (!dragging) drawLine(color.copy(alpha = 0.5f), live(o.vertex), Offset(labelLeft, anchorY), 1f)
				val text = measurer.measure(label(o.parameter), TextStyle(fontSize = 9.5.sp, color = colors.textPrimary),
					overflow = TextOverflow.Ellipsis, maxLines = 1,
					constraints = androidx.compose.ui.unit.Constraints(maxWidth = (labelWidth - 14f).toInt().coerceAtLeast(1)))
				val boxTop = anchorY - 11f
				drawRoundRect(if (chosen || focus == handle) color.copy(alpha = 0.22f) else colors.windowBackground.copy(alpha = 0.85f),
					Offset(labelLeft, boxTop), androidx.compose.ui.geometry.Size(labelWidth, 22f), androidx.compose.ui.geometry.CornerRadius(3f))
				drawCircle(color, 3.5f, Offset(labelLeft + 6f, boxTop + 6f))
				drawText(text, topLeft = Offset(labelLeft + 12f, boxTop))
				// The live value as a bar across the label: the middle is the default, the edges the ends.
				val range = ranges[o.parameter]
				val v = runtime.outputs[o.parameter] ?: range?.default ?: 0f
				val f = ((v - (range?.default ?: 0f)) / halfRange(range)).coerceIn(-1f, 1f)
				val mid = labelLeft + labelWidth / 2f
				drawLine(colors.border, Offset(labelLeft + 4f, boxTop + 18f), Offset(labelLeft + labelWidth - 4f, boxTop + 18f), 2f)
				drawLine(color, Offset(mid, boxTop + 18f), Offset(mid + f * (labelWidth / 2f - 4f), boxTop + 18f), 2f)
				if (!dragging) handles += handle to Offset(labelLeft, anchorY)
			}
			runtime.dropVertex?.let { v -> rest.getOrNull(v)?.let { drawCircle(colors.accent, 10f, it, style = Stroke(1.6f)) } }
			runtime.handles = handles

			// What the focused handle does, and its value.
			val caption = when (val f = focus) {
				is PendulumHandle.Length -> s.segments.getOrNull(f.segment)?.let {
					tr("physics.segmentLength", f.segment + 1, String.format(Locale.US, "%.1f", it.length))
				}
				is PendulumHandle.Range -> s.outputs.getOrNull(f.output)?.let { o ->
					val degrees = Math.toDegrees((halfRange(ranges[o.parameter]) / abs(o.scale).coerceAtLeast(1e-3f)).toDouble())
					tr("physics.scaleHandle", label(o.parameter), String.format(Locale.US, "%.0f", degrees), String.format(Locale.US, "%.2f", o.scale))
				}
				is PendulumHandle.Label -> tr("physics.badgeHandle")
				null -> null
			}
			if (caption != null) drawText(measurer.measure(caption, TextStyle(fontSize = 10.sp, color = colors.accent)), topLeft = Offset(6f, 3f))
		}
		Text(
			tr("physics.canvasHint"), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted,
			modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 4.dp),
		)
		CompactIconButton(
			onClick = { runtime.engine?.reset(); runtime.drag.reset() }, size = 18.dp, tooltip = tr("physics.resetSimulation"),
			modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
		) { IconReset(modifier = Modifier.size(10.dp), tint = colors.textMuted) }
	}
}

/** The vertex (1..) of [rest] nearest the height [y]. */
private fun nearestVertex(rest: List<Offset>, y: Float): Int? =
	(1 until rest.size).minByOrNull { abs(rest[it].y - y) }

private fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f
private fun round2(v: Float) = kotlin.math.round(v * 100f) / 100f

/**
 * How the group answers a standard pull (fully right for a second, then let go), one curve per output as a
 * fraction of its range, redrawn as the group changes. The faint curve is the pull itself.
 */
@Composable
private fun ResponseGraph(setting: RigPhysicsEdit, ranges: Map<String, PhysicsEngine.Range>, label: (String) -> String) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val trace = remember(setting, ranges) { PhysicsResponse.trace(setting, ranges) }
	Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Text(tr("physics.response"), style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted)
		Canvas(
			Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(3.dp)).background(colors.inputBackground)
				.border(BorderStroke(1.dp, colors.border), RoundedCornerShape(3.dp)),
		) {
			val w = size.width
			val h = size.height
			val end = trace.times.last().coerceAtLeast(0.01f)
			fun x(t: Float) = t / end * w
			fun y(v: Float) = h / 2f - v.coerceIn(-1.1f, 1.1f) * (h / 2f - 4f)
			for (t in 1..(end * 2).toInt()) drawLine(colors.border.copy(alpha = 0.5f), Offset(x(t / 2f), 0f), Offset(x(t / 2f), h), 0.8f)
			drawLine(colors.border, Offset(0f, y(0f)), Offset(w, y(0f)), 1f)
			for (v in listOf(-1f, 1f)) drawLine(colors.border.copy(alpha = 0.6f), Offset(0f, y(v)), Offset(w, y(v)), 0.8f,
				pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
			drawLine(colors.textMuted.copy(alpha = 0.6f), Offset(x(trace.hold), 0f), Offset(x(trace.hold), h), 1f)
			fun curve(values: FloatArray, color: Color, width: Float) {
				val path = androidx.compose.ui.graphics.Path()
				values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(trace.times[i]), y(v)) else path.lineTo(x(trace.times[i]), y(v)) }
				drawPath(path, color, style = Stroke(width))
			}
			curve(trace.drag, colors.textMuted.copy(alpha = 0.45f), 1f)
			setting.outputs.forEachIndexed { j, o -> trace.outputs[o.parameter]?.let { curve(it, outputColor(j), 1.6f) } }
		}
		setting.outputs.forEachIndexed { j, o ->
			if (o.parameter !in trace.outputs) return@forEachIndexed
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				Box(Modifier.size(6.dp).clip(CircleShape).background(outputColor(j)))
				Text(
					tr("physics.responseLine", label(o.parameter), String.format(Locale.US, "%.0f", trace.peak(o.parameter) * 100f),
						String.format(Locale.US, "%.1f", (trace.settleTime(o.parameter) - trace.hold).coerceAtLeast(0f))),
					style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

/** Which segment the sliders edit (all or one), and adding or removing segments. */
@Composable
private fun SegmentBar(count: Int, selected: Int?, onSelect: (Int?) -> Unit, onAdd: () -> Unit, onRemove: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FieldLabel(tr("physics.segments"), tooltip = tr("physics.segments.tip"))
		CompactToggleChip(tr("physics.segments.all"), selected == null, { onSelect(null) }, showCheckWhenSelected = false, height = 20.dp)
		if (count <= 6) {
			for (i in 0 until count) {
				CompactToggleChip("${i + 1}", selected == i, { onSelect(i) }, showCheckWhenSelected = false, height = 20.dp)
			}
		} else {
			CompactDropdown((0 until count).toList(), selected ?: 0, { onSelect(it) }, itemLabel = { "${it + 1}" },
				modifier = Modifier.width(48.dp), height = 20.dp)
		}
		Spacer(Modifier.weight(1f))
		CompactIconButton(onClick = onAdd, size = 20.dp, enabled = count < RigPhysicsEdit.MAX_SEGMENTS, tooltip = tr("physics.addSegment")) {
			Text("+", style = LocalToolTypography.current.body, color = LocalToolColors.current.textPrimary)
		}
		CompactIconButton(onClick = onRemove, size = 20.dp, enabled = count > 1, tooltip = tr("physics.removeSegment")) {
			Text("−", style = LocalToolTypography.current.body, color = LocalToolColors.current.textPrimary)
		}
	}
}

/** Length, shakiness, reaction and settling of the chosen segment, or of every segment at once. */
@Composable
private fun DynamicsSliders(
	viewModel: PSD2LiveViewModel,
	setting: RigPhysicsEdit,
	segment: Int?,
	edit: ((RigPhysicsEdit) -> RigPhysicsEdit) -> Unit,
) {
	val shown = segment?.let { setting.segments[it] } ?: setting.segments.first()
	fun set(change: (PhysicsSegment) -> PhysicsSegment) = edit { s ->
		s.copy(segments = s.segments.mapIndexed { i, g -> if (segment == null || segment == i) change(g) else g })
	}
	if (segment == null) {
		SliderRow(viewModel, tr("physics.totalLength"), setting.totalLength, 0.5f..60f, "%.1f") { v -> edit { it.withTotalLength(v) } }
	} else {
		SliderRow(viewModel, tr("physics.length"), shown.length, 0.2f..30f, "%.1f") { v -> set { it.copy(length = v) } }
	}
	SliderRow(viewModel, tr("physics.shakiness"), shown.mobility, 0f..1f) { v -> set { it.copy(mobility = v) } }
	SliderRow(viewModel, tr("physics.reactionSpeed"), shown.delay, 0.05f..3f) { v -> set { it.copy(delay = v) } }
	SliderRow(viewModel, tr("physics.convergenceSpeed"), shown.acceleration, 0f..5f) { v -> set { it.copy(acceleration = v) } }
}

@Composable
private fun SliderRow(
	viewModel: PSD2LiveViewModel,
	title: String,
	value: Float,
	range: ClosedFloatingPointRange<Float>,
	format: String = "%.2f",
	onChange: (Float) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		FieldLabel(title)
		CompactSlider(
			value, { onChange((it * 100f).toInt() / 100f) },
			onValueChangeStarted = viewModel::beginEditorGesture,
			onValueChangeFinished = viewModel::endEditorGesture,
			modifier = Modifier.weight(1f), valueRange = range,
		)
		Text(String.format(Locale.US, format, value), style = typography.monoSmall, color = colors.textPrimary, modifier = Modifier.width(34.dp))
	}
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun FieldLabel(text: String, width: Int = 64, tooltip: String? = null) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val label = @Composable {
		Text(
			text, style = typography.caption.copy(fontSize = 9.5.sp), color = colors.textMuted,
			modifier = Modifier.width(width.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
		)
	}
	if (tooltip == null) return label()
	TooltipArea(
		tooltip = {
			Surface(color = colors.panelElevated, shape = RoundedCornerShape(3.dp), border = BorderStroke(1.dp, colors.border), elevation = 4.dp) {
				Text(tooltip, style = typography.caption.copy(fontSize = 10.sp), color = colors.textPrimary,
					modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 6.dp, vertical = 3.dp))
			}
		},
		delayMillis = 300,
	) { label() }
}

@Composable
private fun Hint(text: String) {
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp), color = LocalToolColors.current.warning)
}

/** "+ Add" with the parameters a group can take; [driven] ones are shown but belong to another group. */
@Composable
private fun AddParameterButton(
	text: String,
	parameters: List<Parameter>,
	exclude: Set<String>,
	driven: Map<String, String>,
	label: (String) -> String,
	presets: List<Pair<String, () -> Unit>> = emptyList(),
	onAdd: (String) -> Unit,
) {
	var open by remember { mutableStateOf(false) }
	Box {
		CompactButton(text = "+ $text", onClick = { open = true }, height = 18.dp)
		TreeContextMenu(expanded = open, onDismissRequest = { open = false }, maxWidth = 320.dp) {
			for ((name, apply) in presets) CompactMenuItem(name, { open = false; apply() })
			if (presets.isNotEmpty()) CompactMenuDivider()
			ParameterMenuItems(parameters.filter { it.id.raw !in exclude }, driven, label) { open = false; onAdd(it) }
		}
	}
}

/** The model's standard inputs first, then the rest in panel order, in a scrolling list. */
@Composable
private fun ColumnScope.ParameterMenuItems(parameters: List<Parameter>, driven: Map<String, String>, label: (String) -> String, onPick: (String) -> Unit) {
	val common = listOf("ParamAngleX", "ParamAngleY", "ParamAngleZ", "ParamBodyAngleX", "ParamBodyAngleY", "ParamBodyAngleZ")
	val sorted = parameters.sortedBy { p -> common.indexOf(p.id.raw).let { if (it < 0) Int.MAX_VALUE else it } }
	Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
		if (sorted.isEmpty()) CompactMenuSection(tr("physics.noParameters"))
		for (p in sorted) {
			val owner = driven[p.id.raw]
			CompactMenuItem(label(p.id.raw), { onPick(p.id.raw) }, enabled = owner == null,
				trailingText = owner?.let { tr("physics.drivenBy", it) } ?: p.id.raw.takeIf { it != label(it) })
		}
	}
}

@Composable
private fun ParameterPicker(
	value: String,
	parameters: List<Parameter>,
	exclude: Set<String>,
	driven: Map<String, String>,
	label: (String) -> String,
	modifier: Modifier = Modifier,
	onPick: (String) -> Unit,
) {
	val items = (listOf(value) + parameters.map { it.id.raw }.filter { it !in exclude }).distinct()
	CompactDropdown(items, value, onPick, modifier = modifier, height = 22.dp, itemLabel = label,
		itemEnabled = { it == value || it !in driven })
}

@Composable
private fun InputRow(
	viewModel: PSD2LiveViewModel,
	input: PhysicsInput,
	parameters: List<Parameter>,
	setting: RigPhysicsEdit,
	label: (String) -> String,
	onChange: (PhysicsInput) -> Unit,
	onRemove: () -> Unit,
) {
	val colors = LocalToolColors.current
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			ParameterPicker(input.parameter, parameters, setting.parameters.toSet(), emptyMap(), label, Modifier.weight(1f)) { onChange(input.copy(parameter = it)) }
			TypeChips(input.type) { onChange(input.copy(type = it)) }
			CompactToggleChip("⇄", input.reflect, { onChange(input.copy(reflect = !input.reflect)) }, showCheckWhenSelected = false, height = 20.dp)
			CompactIconButton(onClick = onRemove, size = 20.dp, tooltip = tr("physics.remove")) {
				IconClose(tint = colors.textMuted, modifier = Modifier.size(9.dp))
			}
		}
		SliderRow(viewModel, tr("physics.weight"), input.weight, 0f..100f, "%.0f%%") { onChange(input.copy(weight = it.coerceIn(0f, 100f))) }
	}
}

@Composable
private fun OutputRow(
	viewModel: PSD2LiveViewModel,
	index: Int,
	output: PhysicsOutput,
	selected: Boolean,
	onSelect: () -> Unit,
	parameters: List<Parameter>,
	setting: RigPhysicsEdit,
	driven: Map<String, String>,
	label: (String) -> String,
	onChange: (PhysicsOutput) -> Unit,
	onRemove: () -> Unit,
) {
	val colors = LocalToolColors.current
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			// The dot shows this output's range on the pendulum.
			Box(
				Modifier.size(14.dp).clip(CircleShape).background(if (selected) outputColor(index).copy(alpha = 0.3f) else Color.Transparent)
					.clickable(onClick = onSelect).pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
				contentAlignment = Alignment.Center,
			) { Box(Modifier.size(7.dp).clip(CircleShape).background(outputColor(index))) }
			ParameterPicker(output.parameter, parameters, setting.parameters.toSet(), driven, label, Modifier.weight(1f)) { onChange(output.copy(parameter = it)) }
			CompactToggleChip("⇄", output.reflect, { onChange(output.copy(reflect = !output.reflect)) }, showCheckWhenSelected = false, height = 20.dp)
			CompactIconButton(onClick = onRemove, size = 20.dp, tooltip = tr("physics.remove")) {
				IconClose(tint = colors.textMuted, modifier = Modifier.size(9.dp))
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
			FieldLabel(tr("physics.vertex"), tooltip = tr("physics.vertex.tip"))
			val count = setting.segments.size
			if (count <= 6) {
				for (v in 1..count) CompactToggleChip("$v", output.vertex == v, { onChange(output.copy(vertex = v)) }, showCheckWhenSelected = false, height = 20.dp)
			} else {
				CompactDropdown((1..count).toList(), output.vertex, { onChange(output.copy(vertex = it)) }, modifier = Modifier.width(48.dp), height = 20.dp)
			}
		}
		SliderRow(viewModel, tr("physics.outputScale"), output.scale, 0f..5f) { onChange(output.copy(scale = it)) }
		if (output.type == PhysicsSourceType.X) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(tr("physics.outputX"), style = LocalToolTypography.current.caption.copy(fontSize = 9.5.sp), color = colors.warning, modifier = Modifier.weight(1f))
				CompactButton(tr("physics.outputToAngle"), { onChange(output.copy(type = PhysicsSourceType.ANGLE)) }, height = 18.dp)
			}
		}
	}
}

@Composable
private fun TypeChips(type: PhysicsSourceType, onChange: (PhysicsSourceType) -> Unit) {
	for (t in PhysicsSourceType.entries) {
		CompactToggleChip(tr("physics.type.${t.name.lowercase()}"), type == t, { onChange(t) }, showCheckWhenSelected = false, height = 20.dp)
	}
}

/** The numbers the canvas and sliders cover, for exact values and the rarely changed ones. */
@Composable
private fun AdvancedSection(viewModel: PSD2LiveViewModel, setting: RigPhysicsEdit, edit: ((RigPhysicsEdit) -> RigPhysicsEdit) -> Unit) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	@Composable
	fun Number(value: Float, min: Double, max: Double, step: Double, decimals: Int, modifier: Modifier, onChange: (Float) -> Unit) =
		NumberField(viewModel, value, min, max, step, decimals, modifier, onChange)
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("ID: ${setting.id}", style = typography.monoSmall, color = colors.textMuted)

		SubTitle(tr("physics.segmentTable"))
		Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel("#", 16)
			for (key in listOf("physics.col.length", "physics.col.mobility", "physics.col.delay", "physics.col.acceleration")) {
				Text(tr(key), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, modifier = Modifier.weight(1f), maxLines = 1)
			}
		}
		setting.segments.forEachIndexed { i, s ->
			fun set(change: (PhysicsSegment) -> PhysicsSegment) = edit { e -> e.copy(segments = e.segments.mapIndexed { j, g -> if (j == i) change(g) else g }) }
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
				FieldLabel("${i + 1}", 16)
				Number(s.length, 0.01, 500.0, 0.5, 1, Modifier.weight(1f)) { v -> set { it.copy(length = v) } }
				Number(s.mobility, 0.0, 1.0, 0.05, 2, Modifier.weight(1f)) { v -> set { it.copy(mobility = v) } }
				Number(s.delay, 0.01, 10.0, 0.05, 2, Modifier.weight(1f)) { v -> set { it.copy(delay = v) } }
				Number(s.acceleration, 0.0, 20.0, 0.1, 2, Modifier.weight(1f)) { v -> set { it.copy(acceleration = v) } }
			}
		}

		if (setting.outputs.isNotEmpty()) {
			SubTitle(tr("physics.outputDetails"))
			setting.outputs.forEachIndexed { i, o ->
				fun set(change: (PhysicsOutput) -> PhysicsOutput) = edit { e -> e.copy(outputs = e.outputs.mapIndexed { j, x -> if (j == i) change(x) else x }) }
				Text(o.parameter, style = typography.monoSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					FieldLabel(tr("physics.outputScale"))
					Number(o.scale, -100.0, 100.0, 0.1, 3, Modifier.weight(1f)) { v -> set { it.copy(scale = v) } }
					FieldLabel(tr("physics.weight"), 40)
					Number(o.weight, 0.0, 100.0, 5.0, 0, Modifier.weight(1f)) { v -> set { it.copy(weight = v) } }
				}
			}
		}

		SubTitle(tr("physics.inputNormalization"))
		val n = setting.normalization
		fun norm(change: (PhysicsNormalization) -> PhysicsNormalization) = edit { e -> e.copy(normalization = change(e.normalization)) }
		Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel("", 48)
			for (key in listOf("physics.min", "physics.center", "physics.max")) {
				Text(tr(key), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted, modifier = Modifier.weight(1f))
			}
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel(tr("physics.positionX"), 48)
			Number(n.positionMin, -1000.0, 1000.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(positionMin = v) } }
			Number(n.positionDefault, -1000.0, 1000.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(positionDefault = v) } }
			Number(n.positionMax, -1000.0, 1000.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(positionMax = v) } }
		}
		Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
			FieldLabel(tr("physics.angle"), 48)
			Number(n.angleMin, -360.0, 360.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(angleMin = v) } }
			Number(n.angleDefault, -360.0, 360.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(angleDefault = v) } }
			Number(n.angleMax, -360.0, 360.0, 1.0, 1, Modifier.weight(1f)) { v -> norm { it.copy(angleMax = v) } }
		}
		Text(tr("physics.normalizationHint"), style = typography.caption.copy(fontSize = 9.sp), color = colors.textMuted)
	}
}

@Composable
private fun SubTitle(text: String) {
	Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
		color = LocalToolColors.current.textPrimary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun NumberField(
	viewModel: PSD2LiveViewModel,
	value: Float,
	min: Double,
	max: Double,
	step: Double,
	decimals: Int,
	modifier: Modifier,
	onChange: (Float) -> Unit,
) {
	CompactNumberSpinner(value.toDouble(), { onChange(it.toFloat()) }, modifier = modifier, min = min, max = max, step = step, decimals = decimals,
		height = 20.dp, onEditStart = { viewModel.beginEditorField(PHYSICS_FIELD) }, onEditEnd = { viewModel.endEditorField(PHYSICS_FIELD) })
}
