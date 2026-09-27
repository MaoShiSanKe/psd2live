package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsGroup
import io.github.psd2live.core.PhysicsIssue
import io.github.psd2live.core.PhysicsOrigin
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactCheckbox
import io.github.psd2live.ui.components.CompactDropdown
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.CompactMenuDivider
import io.github.psd2live.ui.components.CompactMenuItem
import io.github.psd2live.ui.components.CompactSectionHeader
import io.github.psd2live.ui.components.IconPause
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.TreeContextMenu
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.utils.NativeFilePicker

/**
 * Physics: every pendulum the model exports, generated or the user's, in one list in evaluation order.
 * The selected group is edited on its pendulum - drag a vertex to set a pendulum's length, drag elsewhere
 * to shake it - with sliders for what has no place on it and the raw numbers folded under Advanced.
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
		PhysicsHeaderCard(viewModel, previewState, groups, state.rigEdits.physicsFps)

		CompactSectionHeader(
			title = tr("physics.groups"),
			trailing = {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
					// Cubism runs groups top to bottom; a later group reads an earlier one's outputs.
					val index = groups.indexOfFirst { it.id == selected?.id }
					OrderButton("↑", tr("physics.moveUp"), index > 0) { selected?.let { viewModel.movePhysicsGroup(it.id, -1) } }
					OrderButton("↓", tr("physics.moveDown"), index in 0 until groups.size - 1) { selected?.let { viewModel.movePhysicsGroup(it.id, 1) } }
					NewPhysicsButton(viewModel, selected) { id -> selectedId = id }
				}
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
internal fun PhysicsHeaderCard(viewModel: PSD2LiveViewModel, state: PSD2LiveState, groups: List<PhysicsGroup>, fps: Int) {
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
			CompactCheckbox(state.mouseTrackingEnabled, viewModel::setMouseTrackingEnabled, label = tr("animation.mouseTracking"),
				modifier = Modifier.weight(1f))
			FieldLabel(tr("physics.fps"), width = 64, tooltip = tr("physics.fps.tip"))
			CompactDropdown((FPS_CHOICES + fps).distinct().sorted(), fps, viewModel::setPhysicsFps, modifier = Modifier.width(56.dp), height = 20.dp)
		}
	}
}

/** Cubism Editor's physics rates. */
private val FPS_CHOICES = listOf(30, 60, 120)

@Composable
private fun OrderButton(text: String, tooltip: String, enabled: Boolean, onClick: () -> Unit) {
	CompactIconButton(onClick = onClick, size = 18.dp, enabled = enabled, tooltip = tooltip) {
		Text(text, style = LocalToolTypography.current.caption.copy(fontSize = 11.sp), color = LocalToolColors.current.textPrimary)
	}
}

@Composable
internal fun NewPhysicsButton(viewModel: PSD2LiveViewModel, selected: PhysicsGroup?, onCreated: (String) -> Unit) {
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
			CompactMenuItem(text = tr("physics.import"), onClick = {
				open = false
				NativeFilePicker.choosePhysicsFile()?.let { path -> viewModel.importPhysics(path)?.let(onCreated) }
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
internal fun PhysicsGroupList(
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
		groups.forEachIndexed { index, group ->
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
				Text("${index + 1}", style = typography.monoSmall, color = colors.textMuted, modifier = Modifier.width(14.dp))
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
internal fun StatusDot(color: Color, label: String) {
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
		Box(Modifier.size(6.dp).clip(CircleShape).background(color))
		Text(label, style = LocalToolTypography.current.caption.copy(fontSize = 9.sp), color = color, maxLines = 1)
	}
}

@Composable
internal fun OriginBadge(origin: PhysicsOrigin) {
	val colors = LocalToolColors.current
	Box(
		Modifier.clip(RoundedCornerShape(3.dp)).background(colors.controlBackground).padding(horizontal = 4.dp, vertical = 1.dp),
	) {
		Text(tr("physics.origin.${origin.name.lowercase()}"), style = LocalToolTypography.current.caption.copy(fontSize = 9.sp), color = colors.textMuted)
	}
}

internal fun issueText(issue: PhysicsIssue): String = when (issue.code) {
	PhysicsIssue.Code.MISSING_PARAMETER -> tr("physics.issue.missing", issue.parameter.orEmpty())
	PhysicsIssue.Code.NO_INPUT -> tr("physics.issue.noInput")
	PhysicsIssue.Code.NO_OUTPUT -> tr("physics.issue.noOutput")
	PhysicsIssue.Code.FEEDBACK -> tr("physics.issue.feedback", issue.parameter.orEmpty())
	PhysicsIssue.Code.OUTPUT_TAKEN -> tr("physics.issue.taken", issue.parameter.orEmpty(), issue.group.orEmpty())
}
