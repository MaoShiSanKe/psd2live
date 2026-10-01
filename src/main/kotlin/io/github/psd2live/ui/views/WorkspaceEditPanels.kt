package io.github.psd2live.ui.views

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.SwingSessionPanel
import io.github.psd2live.ui.state.AppSettings
import io.github.psd2live.ui.state.CanvasMode
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.theme.LocalToolColors
import java.awt.MouseInfo
import java.awt.Point

/** Keep an unfinished placement reachable even when another canvas receives focus. */
internal fun workspacePlacementOwner(activeId: String, pendingIds: List<String>): String? =
    activeId.takeIf { it in pendingIds } ?: pendingIds.firstOrNull()

@Composable
internal fun WorkspaceEditPanels(
    state: PSD2LiveState,
    viewModel: PSD2LiveViewModel,
    modifier: Modifier = Modifier,
) {
    val pendingIds = state.activeWorkspace.canvases.filter { canvas ->
        val editor = viewModel.canvasEditorFor(canvas.id)
        canvas.mode == CanvasMode.EDIT && editor.placement != null && editor.skeletonDraft == null
    }.map { it.id }
    val ownerId = workspacePlacementOwner(state.activeCanvas.id, pendingIds)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ownerId != null) {
            val editor = viewModel.canvasEditorFor(ownerId)
            val placement = editor.placement
            if (placement != null) key(ownerId) {
                DetachableEditPanel(state) { titleModifier, windowActions ->
                    PlacementSettingsPanel(
                        editor, placement, false, state.keymap,
                        focus = { viewModel.requestCanvasFocus(ownerId) },
                        titleModifier = titleModifier, windowActions = windowActions,
                    )
                }
            }
        }
        viewModel.swingSession?.let { session ->
            val editor = viewModel.canvasEditor
            val poseKey = editor.state.parameterValues.filterKeys { it.raw !in session.draft.parameterIds }
            LaunchedEffect(session, poseKey) { viewModel.refreshSwingGizmo() }
            key(session) {
                DetachableEditPanel(state) { titleModifier, windowActions ->
                    SwingSessionPanel(
                        viewModel = viewModel,
                        session = session,
                        targetLabel = { id ->
                            state.previewModel?.rig?.puppet?.let { model ->
                                model.deformers.firstOrNull { it.id.raw == id }?.name
                                    ?: model.drawables.firstOrNull { it.id.raw == id }?.name
                            } ?: id
                        },
                        selectionTargets = editor::swingTargets,
                        focus = { viewModel.requestCanvasFocus(state.activeCanvas.id) },
                        titleModifier = titleModifier, windowActions = windowActions,
                    )
                }
            }
        }
    }
}

/** The panel's own header holds the window controls, inside its clipped rounded surface. */
@Composable
private fun DetachableEditPanel(
    state: PSD2LiveState,
    content: @Composable (Modifier, @Composable () -> Unit) -> Unit,
) {
    var detached by remember { mutableStateOf(false) }
    val windowState = rememberWindowState(size = DpSize.Unspecified)
    val title = tr("editor.workspacePanel.title")
    val body: @Composable (java.awt.Window?) -> Unit = { floatingWindow ->
        val colors = LocalToolColors.current
        val titleModifier = if (floatingWindow == null) Modifier else Modifier.pointerInput(floatingWindow) {
            var grabOffset = Point()
            detectDragGestures(
                onDragStart = {
                    MouseInfo.getPointerInfo()?.location?.let { pointer ->
                        grabOffset = Point(pointer.x - floatingWindow.x, pointer.y - floatingWindow.y)
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    MouseInfo.getPointerInfo()?.location?.let { pointer ->
                        floatingWindow.setLocation(pointer.x - grabOffset.x, pointer.y - grabOffset.y)
                    }
                },
            )
        }
        Box(
            Modifier.heightIn(max = 640.dp).clip(RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState()),
        ) {
            content(titleModifier) {
                CompactIconButton(
                    onClick = { detached = floatingWindow == null }, size = 18.dp,
                    tooltip = tr(if (floatingWindow != null) "dock.return" else "dock.detach"),
                ) {
                    Text(if (floatingWindow != null) "↙" else "↗", color = colors.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
    if (detached) {
        Window(
            onCloseRequest = { detached = false }, state = windowState,
            title = title, undecorated = true, transparent = true, resizable = false,
        ) {
            CompactToolTheme(colors = state.toolColors, uiScale = AppSettings.uiScale, fontScale = AppSettings.fontScale) {
                body(window)
            }
        }
    } else {
        body(null)
    }
}
