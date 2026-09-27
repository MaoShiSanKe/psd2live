package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.IconChevron
import io.github.psd2live.ui.components.IconFolder
import io.github.psd2live.ui.components.IconPhysics
import io.github.psd2live.ui.components.IconPlay
import io.github.psd2live.ui.components.IconSkeleton
import io.github.psd2live.ui.state.RecentFile
import io.github.psd2live.ui.state.RecentFileKind
import io.github.psd2live.ui.state.WorkspacePreset
import io.github.psd2live.ui.state.recentFilesFrom
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.tutorial.TutorialId
import java.awt.Cursor
import java.nio.file.Files
import java.nio.file.Path

@Composable
internal fun EmptyCanvasStart(
	recentPaths: List<String>,
	enabled: Boolean,
	openProjectShortcut: String?,
	openPsdShortcut: String?,
	onOpenTutorialCatalog: (() -> Unit)?,
	onStartTutorial: ((TutorialId) -> Unit)?,
	onOpenProject: (() -> Unit)?,
	onOpenPsd: (() -> Unit)?,
	onOpenRecent: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val recent = remember(recentPaths) {
		recentFilesFrom(recentPaths).filter { runCatching { Files.isRegularFile(Path.of(it.path)) }.getOrDefault(false) }
	}

	BoxWithConstraints(
		modifier.fillMaxSize().background(colors.windowBackground).padding(horizontal = 18.dp, vertical = 14.dp),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.widthIn(max = 860.dp)
				.fillMaxWidth()
				.heightIn(max = maxHeight)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
				Text(
					text = tr("canvas.start.welcome"),
					style = typography.title.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
					color = colors.textPrimary,
				)
				Text(
					text = tr("canvas.start.welcome.desc"),
					style = typography.caption.copy(fontSize = 10.5.sp),
					color = colors.textMuted,
				)
			}

			val startContent: @Composable () -> Unit = {
				StartSection(
					recent = recent,
					enabled = enabled,
					openProjectShortcut = openProjectShortcut,
					openPsdShortcut = openPsdShortcut,
					onOpenTutorialCatalog = onOpenTutorialCatalog,
					onOpenProject = onOpenProject,
					onOpenPsd = onOpenPsd,
					onOpenRecent = onOpenRecent,
				)
			}
			val updatesContent: @Composable () -> Unit = {
				UpdatesSection(enabled = enabled, onStartTutorial = onStartTutorial)
			}
			Row(
				Modifier.fillMaxWidth().drawBehind {
					drawLine(colors.divider, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
				},
				horizontalArrangement = Arrangement.spacedBy(18.dp),
			) {
				Column(Modifier.weight(1f)) { startContent() }
				Column(Modifier.weight(1f)) { updatesContent() }
			}

			Text(
				text = tr("canvas.start.dropHint"),
				style = typography.caption.copy(fontSize = 11.sp),
				color = colors.textDisabled,
				modifier = Modifier.padding(top = 4.dp),
			)
		}
	}
}

@Composable
private fun StartSection(
	recent: List<RecentFile>,
	enabled: Boolean,
	openProjectShortcut: String?,
	openPsdShortcut: String?,
	onOpenTutorialCatalog: (() -> Unit)?,
	onOpenProject: (() -> Unit)?,
	onOpenPsd: (() -> Unit)?,
	onOpenRecent: (String) -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		SectionTitle(tr("canvas.start.section.start"))
		onOpenTutorialCatalog?.let {
			StartActionRow(tr("canvas.start.quickStart"), tr("canvas.start.quickStart.desc"), icon = { tint -> IconRoute(tint = tint) }, enabled = enabled, onClick = it)
		}
		onOpenProject?.let {
			StartActionRow(tr("canvas.start.openProject"), tr("canvas.start.openProject.desc"), openProjectShortcut, icon = { tint -> IconFolder(tint = tint) }, enabled = enabled, onClick = it)
		}
		onOpenPsd?.let {
			StartActionRow(tr("canvas.start.importPsd"), tr("canvas.start.importPsd.desc"), openPsdShortcut, icon = { tint -> IconDocument(tint = tint) }, enabled = enabled, onClick = it)
		}
		SectionTitle(tr("canvas.start.recent"), Modifier.padding(top = 6.dp))
		if (recent.isEmpty()) {
			Text(tr("canvas.start.recent.empty"), style = typography.caption.copy(fontSize = 11.sp), color = colors.textDisabled, modifier = Modifier.padding(4.dp))
		} else {
			recent.take(4).forEach { file ->
				RecentFileRow(file, enabled) { onOpenRecent(file.path) }
			}
		}
	}
}

@Composable
private fun UpdatesSection(
	enabled: Boolean,
	onStartTutorial: ((TutorialId) -> Unit)?,
) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		SectionTitle(tr("canvas.start.section.updates"))
		Text(
			tr("canvas.start.updates.desc"),
			style = LocalToolTypography.current.caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
			color = LocalToolColors.current.textMuted,
			modifier = Modifier.padding(bottom = 1.dp),
		)
		listOf(
			TutorialId.WORKSPACE to "workspace",
			TutorialId.SKELETON to "skeleton",
			TutorialId.ANIMATION to "animation",
			TutorialId.PHYSICS to "physics",
		).forEach { (tutorial, key) ->
			FeatureUpdateRow(
				title = tr("canvas.start.update.$key.title"),
				description = tr("canvas.start.update.$key.desc"),
				tutorial = tutorial,
				enabled = enabled && onStartTutorial != null,
				onClick = { onStartTutorial?.invoke(tutorial) },
			)
		}
	}
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	Text(text, style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = colors.textPrimary, modifier = modifier)
}

@Composable
private fun FeatureUpdateRow(
	title: String,
	description: String,
	tutorial: TutorialId,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val shape = RoundedCornerShape(3.dp)
	Row(
		Modifier.fillMaxWidth()
			.background(if (hovered && enabled) colors.controlHover else colors.windowBackground.copy(alpha = 0f), shape)
			.hoverable(interactionSource)
			.clickable(enabled = enabled, interactionSource = interactionSource, indication = null, onClick = onClick)
			.pointerHoverIcon(if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)) else PointerIcon.Default)
			.padding(horizontal = 6.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		val iconTint = if (enabled) colors.accent else colors.textDisabled
		when (tutorial) {
			TutorialId.WORKSPACE -> WorkspacePresetIcon(WorkspacePreset.EDIT, iconTint, Modifier.size(15.dp))
			TutorialId.SKELETON -> IconSkeleton(modifier = Modifier.size(15.dp), tint = iconTint)
			TutorialId.ANIMATION -> IconPlay(modifier = Modifier.size(14.dp), tint = iconTint)
			TutorialId.PHYSICS -> IconPhysics(active = true, modifier = Modifier.size(15.dp), tint = iconTint)
			else -> IconRoute(tint = iconTint)
		}
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(title, style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = if (enabled) colors.textPrimary else colors.textDisabled)
			Text(description, style = typography.caption.copy(fontSize = 10.sp, lineHeight = 13.sp), color = colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
		IconChevron(expanded = false, modifier = Modifier.size(12.dp), tint = if (enabled) colors.accent else colors.textDisabled)
	}
}

@Composable
private fun IconRoute(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color,
) {
	Canvas(modifier) {
		val stroke = Stroke(1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val start = Offset(size.width * .20f, size.height * .72f)
		val branch = Offset(size.width * .50f, size.height * .34f)
		val upper = Offset(size.width * .82f, size.height * .18f)
		val lower = Offset(size.width * .82f, size.height * .76f)
		drawLine(tint, start, branch, stroke.width, stroke.cap)
		drawLine(tint, branch, upper, stroke.width, stroke.cap)
		drawLine(tint, branch, lower, stroke.width, stroke.cap)
		listOf(start, upper, lower).forEach { drawCircle(tint, size.width * .10f, it, style = stroke) }
		drawCircle(tint, size.width * .09f, branch)
	}
}

@Composable
private fun IconDocument(
	modifier: Modifier = Modifier.size(14.dp),
	tint: Color,
) {
	Canvas(modifier) {
		val w = size.width
		val h = size.height
		val stroke = Stroke(1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
		val page = ComposePath().apply {
			moveTo(w * .20f, h * .10f)
			lineTo(w * .62f, h * .10f)
			lineTo(w * .82f, h * .30f)
			lineTo(w * .82f, h * .90f)
			lineTo(w * .20f, h * .90f)
			close()
		}
		drawPath(page, tint, style = stroke)
		drawLine(tint, Offset(w * .62f, h * .10f), Offset(w * .62f, h * .31f), stroke.width, stroke.cap)
		drawLine(tint, Offset(w * .62f, h * .31f), Offset(w * .82f, h * .31f), stroke.width, stroke.cap)
		drawLine(tint.copy(alpha = .75f), Offset(w * .33f, h * .53f), Offset(w * .69f, h * .53f), stroke.width, stroke.cap)
		drawLine(tint.copy(alpha = .75f), Offset(w * .33f, h * .69f), Offset(w * .64f, h * .69f), stroke.width, stroke.cap)
	}
}

@Composable
private fun StartActionRow(
	title: String,
	subtitle: String,
	shortcut: String? = null,
	icon: @Composable (Color) -> Unit,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val shape = RoundedCornerShape(3.dp)
	val background = if (hovered && enabled) colors.controlHover else colors.windowBackground.copy(alpha = 0f)
	val titleColor = when {
		!enabled -> colors.textDisabled
		else -> colors.accent
	}
	val subColor = when {
		!enabled -> colors.textDisabled
		else -> colors.textMuted
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(background, shape)
			.hoverable(interactionSource)
			.clickable(
				enabled = enabled,
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.pointerHoverIcon(
				if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
				else PointerIcon.Default,
			)
			.padding(horizontal = 6.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		icon(titleColor)
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				text = title,
				style = typography.body.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
				color = titleColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = subtitle,
				style = typography.caption.copy(fontSize = 10.sp),
				color = subColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (!shortcut.isNullOrBlank()) {
			Text(
				text = shortcut,
				style = typography.monoSmall.copy(fontSize = 10.sp),
				color = subColor,
				maxLines = 1,
			)
		}
	}
}

@Composable
private fun RecentFileRow(
	file: RecentFile,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val colors = LocalToolColors.current
	val typography = LocalToolTypography.current
	val interactionSource = remember { MutableInteractionSource() }
	val hovered by interactionSource.collectIsHoveredAsState()
	val shape = RoundedCornerShape(3.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(if (hovered && enabled) colors.controlHover else colors.windowBackground.copy(alpha = 0f), shape)
			.hoverable(interactionSource)
			.clickable(
				enabled = enabled,
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.pointerHoverIcon(
				if (enabled) PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
				else PointerIcon.Default,
			)
			.padding(horizontal = 6.dp, vertical = 5.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp),
	) {
		when (file.kind) {
			RecentFileKind.PROJECT -> IconFolder(modifier = Modifier.size(14.dp), tint = colors.textMuted)
			RecentFileKind.PSD -> IconDocument(modifier = Modifier.size(14.dp), tint = colors.textMuted)
		}
		Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(
				text = file.name,
				style = typography.body.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
				color = if (enabled) colors.textPrimary else colors.textDisabled,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (file.directory.isNotEmpty()) {
				Text(
					text = file.directory,
					style = typography.caption.copy(fontSize = 10.sp),
					color = colors.textMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		Text(
			text = tr(
				when (file.kind) {
					RecentFileKind.PROJECT -> "canvas.start.kind.project"
					RecentFileKind.PSD -> "canvas.start.kind.psd"
				},
			),
			style = typography.caption.copy(fontSize = 10.sp),
			color = colors.textMuted,
		)
	}
}
