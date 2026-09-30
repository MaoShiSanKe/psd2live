package io.github.psd2live.ui.views

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.modeLabel
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import io.github.psd2live.ui.theme.frostedGlass
import kotlinx.coroutines.delay

/**
 * One row of the canvas mode menu: the six editing modes, then Preview. Preview is the canvas's preview
 * mode rather than a hierarchy mode, but it is picked from the same list, the way Blender lists every
 * interaction mode of the object in one menu.
 */
internal enum class CanvasModeChoice(val mode: EditHierarchyMode?) {
    SELECT(EditHierarchyMode.SELECT),
    DEFORM(EditHierarchyMode.DEFORM),
    EDIT(EditHierarchyMode.EDIT),
    SIMULATE(EditHierarchyMode.SIMULATE),
    SKELETON(EditHierarchyMode.SKELETON),
    PAINT(EditHierarchyMode.PAINT),
    PREVIEW(null),
    ;

    val label: String get() = mode?.let(::modeLabel) ?: tr("editor.mode.preview")

    companion object {
        fun of(canvasMode: CanvasMode, mode: EditHierarchyMode): CanvasModeChoice =
            if (canvasMode == CanvasMode.PREVIEW) PREVIEW else entries.first { it.mode == mode }

        fun of(mode: EditHierarchyMode): CanvasModeChoice = entries.first { it.mode == mode }
    }
}

/** Takes a row of the mode menu on [this] canvas: Preview plays the model, any other row edits in that mode. */
internal fun io.github.psd2live.ui.CanvasEditor.chooseCanvasMode(choice: CanvasModeChoice) {
    val mode = choice.mode
    if (mode == null) {
        showCanvasMode(CanvasMode.PREVIEW)
        return
    }
    showCanvasMode(CanvasMode.EDIT)
    setHierarchyMode(mode)
}

@Composable
internal fun CanvasModeIcon(choice: CanvasModeChoice, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val mode = choice.mode
        if (mode != null) drawModeIcon(mode, color) else drawPreviewModeIcon(color)
    }
}

private val MenuItemHeight = 28.dp
private val MenuItemGap = 1.dp
private val MenuDividerHeight = 9.dp
private val MenuWidth = 212.dp

/** Where each row sits in the list: the rows are evenly spaced, with a divider before Preview. */
private fun menuRowTop(index: Int): Dp {
    val divider = if (CanvasModeChoice.entries[index] == CanvasModeChoice.PREVIEW) MenuDividerHeight else 0.dp
    return (MenuItemHeight + MenuItemGap) * index + divider
}

/**
 * The canvas mode menu, Blender's mode dropdown: the button shows the mode in force and opens a list of
 * every mode. The list scales in from the button, its rows slide in one after another, and the highlight
 * that marks the current mode glides to the row under the pointer's choice. 1-7 pick a row while it is
 * open, the arrows walk it, Enter takes the row in focus and Esc closes it.
 */
@Composable
internal fun CanvasModeMenu(
    current: CanvasModeChoice,
    onSelect: (CanvasModeChoice) -> Unit,
    modifier: Modifier = Modifier,
    waiting: CanvasModeChoice? = null,
    enabled: Boolean = true,
) {
    val colors = LocalToolColors.current
    var open by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            open -> colors.accent.copy(alpha = 0.3f)
            hovered -> colors.accent.copy(alpha = 0.26f)
            else -> colors.accent.copy(alpha = 0.18f)
        },
        animationSpec = tween(140),
    )
    val chevronTurn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
    )

    Box(modifier) {
        Row(
            modifier = Modifier
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .border(0.5.dp, colors.accent.copy(alpha = if (open) 0.75f else 0.5f), RoundedCornerShape(4.dp))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, enabled = enabled) { open = !open }
                .semantics { contentDescription = tr("editor.mode.menu") }
                .padding(start = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The icon and name roll over to the new mode rather than jumping, so a mode switched from a
            // shortcut or a panel is noticed on the canvas too.
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    val down = targetState.ordinal > initialState.ordinal
                    (slideInVertically(tween(200, easing = FastOutSlowInEasing)) { h -> if (down) h / 2 else -h / 2 } +
                        fadeIn(tween(160, delayMillis = 40))) togetherWith
                        (slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { h -> if (down) -h / 2 else h / 2 } +
                            fadeOut(tween(100)))
                },
            ) { choice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CanvasModeIcon(choice, colors.accent)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = choice.label,
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Canvas(Modifier.size(10.dp).rotate(chevronTurn)) {
                val w = size.width
                val h = size.height
                drawLine(colors.accent, androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.38f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.66f), 1.4f, StrokeCap.Round)
                drawLine(colors.accent, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.66f), androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.38f), 1.4f, StrokeCap.Round)
            }
        }
        CanvasModeMenuPopup(
            expanded = open,
            current = current,
            waiting = waiting,
            onDismiss = { open = false },
            onSelect = { choice ->
                open = false
                if (choice != current) onSelect(choice)
            },
        )
    }
}

@Composable
private fun CanvasModeMenuPopup(
    expanded: Boolean,
    current: CanvasModeChoice,
    waiting: CanvasModeChoice?,
    onDismiss: () -> Unit,
    onSelect: (CanvasModeChoice) -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!visibility.currentState && !visibility.targetState) return

    val colors = LocalToolColors.current
    val typography = LocalToolTypography.current
    val density = LocalDensity.current
    val offset = with(density) { IntOffset(0, 28.dp.roundToPx()) }

    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalToolColors provides colors,
            LocalToolTypography provides typography,
        ) {
            // Same entrance as the canvas context menus, so every canvas popup opens the same way.
            val transition = rememberTransition(visibility, "CanvasModeMenu")
            val alpha by transition.animateFloat(
                transitionSpec = {
                    if (false isTransitioningTo true) tween(120, easing = LinearOutSlowInEasing)
                    else tween(80, easing = FastOutLinearInEasing)
                },
                label = "alpha",
            ) { if (it) 1f else 0f }
            val scale by transition.animateFloat(
                transitionSpec = {
                    if (false isTransitioningTo true) spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
                    else tween(80, easing = FastOutLinearInEasing)
                },
                label = "scale",
            ) { if (it) 1f else 0.92f }
            val lift by transition.animateFloat(
                transitionSpec = {
                    if (false isTransitioningTo true) tween(160, easing = FastOutSlowInEasing)
                    else tween(80, easing = FastOutLinearInEasing)
                },
                label = "lift",
            ) { if (it) 0f else -6f }

            val choices = CanvasModeChoice.entries
            var focused by remember { mutableStateOf(current.ordinal) }
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            // The highlight glides between rows instead of jumping, following the keyboard or the pointer.
            val highlightTop by animateDpAsState(
                targetValue = menuRowTop(focused),
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            )

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                        translationY = lift * density.density
                        transformOrigin = TransformOrigin(0.12f, 0f)
                    }
                    .width(MenuWidth)
                    .frostedGlass(
                        shape = RoundedCornerShape(7.dp),
                        isHovered = true,
                        elevation = 12.dp,
                        baseColor = colors.panelElevated,
                        alpha = 0.9f,
                    )
                    .border(0.5.dp, colors.borderHover.copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val digit = when (event.key) {
                            Key.One, Key.NumPad1 -> 0
                            Key.Two, Key.NumPad2 -> 1
                            Key.Three, Key.NumPad3 -> 2
                            Key.Four, Key.NumPad4 -> 3
                            Key.Five, Key.NumPad5 -> 4
                            Key.Six, Key.NumPad6 -> 5
                            Key.Seven, Key.NumPad7 -> 6
                            else -> null
                        }
                        when {
                            digit != null -> { onSelect(choices[digit]); true }
                            event.key == Key.DirectionDown -> { focused = (focused + 1) % choices.size; true }
                            event.key == Key.DirectionUp -> { focused = (focused - 1 + choices.size) % choices.size; true }
                            event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar -> {
                                onSelect(choices[focused]); true
                            }
                            event.key == Key.Escape -> { onDismiss(); true }
                            else -> false
                        }
                    }
                    .padding(4.dp),
            ) {
                Box(
                    Modifier
                        .offset(y = highlightTop)
                        .fillMaxWidth()
                        .height(MenuItemHeight)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.controlHover.copy(alpha = 0.75f))
                )
                Column {
                    choices.forEachIndexed { index, choice ->
                        if (choice == CanvasModeChoice.PREVIEW) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(MenuDividerHeight)
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider.copy(alpha = 0.7f)))
                            }
                        }
                        ModeMenuRow(
                            choice = choice,
                            index = index,
                            selected = choice == current,
                            waiting = choice == waiting,
                            onHover = { focused = index },
                            onClick = { onSelect(choice) },
                        )
                        if (index < choices.lastIndex) Spacer(Modifier.height(MenuItemGap))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeMenuRow(
    choice: CanvasModeChoice,
    index: Int,
    selected: Boolean,
    waiting: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalToolColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }

    // Rows arrive one after another, a few milliseconds apart, sliding in from the button's side.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 18L)
        arrival.animateTo(1f, tween(190, easing = FastOutSlowInEasing))
    }
    val tint by animateColorAsState(
        targetValue = when {
            selected -> colors.accent
            waiting -> colors.warning
            hovered -> colors.textPrimary
            else -> colors.textMuted
        },
        animationSpec = tween(120),
    )
    val density = LocalDensity.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MenuItemHeight)
            .graphicsLayer {
                alpha = arrival.value
                translationX = (1f - arrival.value) * -8f * density.density
            }
            .clip(RoundedCornerShape(5.dp))
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .semantics { contentDescription = choice.label }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The current mode is marked by a bar at the row's edge; the moving highlight is the focus.
        val bar by animateFloatAsState(if (selected) 1f else 0f, tween(180, easing = FastOutSlowInEasing))
        Box(
            Modifier
                .width(2.dp)
                .height(14.dp * bar)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = bar))
        )
        Spacer(Modifier.width(5.dp))
        CanvasModeIcon(choice, tint, 15.dp)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = choice.label,
                color = tint,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
        if (waiting) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(colors.warning))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = "${index + 1}",
            color = colors.textMuted.copy(alpha = 0.7f),
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}
