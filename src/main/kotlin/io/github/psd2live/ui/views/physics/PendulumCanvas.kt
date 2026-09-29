package io.github.psd2live.ui.views.physics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.core.PhysicsEngine
import io.github.psd2live.core.PhysicsDrag
import io.github.psd2live.core.PhysicsSourceType
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactIconButton
import io.github.psd2live.ui.components.IconReset
import io.github.psd2live.ui.state.PSD2LiveState
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.previewPanelState
import io.github.psd2live.ui.state.FramePacer
import io.github.psd2live.ui.state.frameIntervalNanos
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.theme.LocalToolTypography
import java.awt.Cursor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** What a press on the pendulum grabs. */
internal sealed interface PendulumHandle {
	/** A vertex of the rest pose: drag it up or down for its segment's length. */
	data class Length(val segment: Int) : PendulumHandle
	/** An output's label at the side: drop it level with another vertex to read that segment. */
	data class Label(val output: Int) : PendulumHandle
	/** The edge of the selected output's range fan: drag it to widen or narrow the range. */
	data class Range(val output: Int) : PendulumHandle
}

/** What the pendulum canvas keeps between frames. */
internal class PendulumRuntime {
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
	/**
	 * Per output index, how far its swing has reached toward the parameter's end since the last reset
	 * (1 = exactly), refreshed a few times a second: Cubism Editor's maximum output.
	 */
	var peaks by mutableStateOf<Map<Int, Float>>(emptyMap())
	private var peaksAt = 0L

	fun resetPeaks() {
		engine?.strands?.forEach { it.resetPeaks() }
		peaks = emptyMap()
	}

	/** Reads the strand's peaks into [peaks], at most every 100 ms. */
	fun publishPeaks(now: Long, ranges: Map<String, PhysicsEngine.Range>) {
		if (now - peaksAt < 100_000_000L) return
		peaksAt = now
		val strand = engine?.strands?.firstOrNull() ?: return
		val next = strand.setting.outputs.indices.mapNotNull { k ->
			ranges[strand.setting.outputs[k].parameter]?.let { k to strand.peakFraction(k, it) }
		}.toMap()
		if (next != peaks) peaks = next
	}

	val setting: RigPhysicsEdit? get() = engine?.strands?.firstOrNull()?.setting
}

/** Where the pendulum hangs in a canvas of [width] x [height]: pixels per unit and the plumb line. */
internal fun pendulumScale(setting: RigPhysicsEdit, width: Float, height: Float): Float {
	val total = setting.totalLength.coerceAtLeast(0.01f)
	// Leave room for the root's travel at a full-range sideways input, and for the swing itself.
	val travel = setting.normalization.positionMax * setting.inputs.filter { it.type == PhysicsSourceType.X }.sumOf { it.weight.toDouble() }.toFloat() / 100f
	val byHeight = (height - PENDULUM_TOP - PENDULUM_BOTTOM) / total
	val byWidth = (width * PENDULUM_AREA / 2f - 10f) / (abs(travel) + total * 0.45f)
	return minOf(byHeight, byWidth).coerceAtLeast(0.5f)
}

internal const val PENDULUM_TOP = 22f

internal const val PENDULUM_BOTTOM = 24f

/** The share of the canvas width the pendulum swings in; the output labels take the rest. */
internal const val PENDULUM_AREA = 0.6f

internal const val MIN_ARC = 0.035f

internal const val MAX_ARC = 2.9f

/**
 * The selected pendulum. Solid is the live strand under the model's inputs; dashed is its rest pose,
 * hanging from the live root, with a ring per vertex. Dragging a ring sets that segment's length; each
 * output's label at the side shows its live value and can be dropped level with another vertex; a selected
 * output shows the fan of angles over which its parameter runs end to end, with a handle on its edge.
 * Dragging anywhere else pulls the model the way Cubism's viewer does, and letting go eases it back. The
 * view is mirrored so the root follows the pointer.
 */
@Composable
internal fun PendulumEditor(
	viewModel: PSD2LiveViewModel,
	state: PSD2LiveState,
	runtime: PendulumRuntime,
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
	val fps = state.rigEdits.physicsFps
	// A retuned pendulum keeps swinging from where it is.
	remember(setting, ranges, fps) {
		PhysicsEngine(listOf(setting), ranges, fps.toFloat()).also { it.carryOver(runtime.engine); runtime.engine = it }
	}
	// The inputs are the pose the preview shows, frame for frame; the edit pose while it holds still.
	val previewState = state.previewPanelState()
	val staticValues = previewState.parameterValues
	val inputs by rememberUpdatedState {
		val live = if (previewState.activeWorkspace.pose?.authoringPose == true) emptyMap() else viewModel.livePose.value
		val base = (staticValues + live).mapKeys { it.key.raw }
		val s = runtime.setting
		if (s == null) base else runtime.drag.apply(base, s.inputs.map { it.parameter }, ranges)
	}
	val editBy by rememberUpdatedState(edit)
	val rangesNow by rememberUpdatedState(ranges)
	val selectSegment by rememberUpdatedState(onSelectSegment)
	val selectOutput by rememberUpdatedState(onSelectOutput)
	val segmentSelected by rememberUpdatedState(selectedSegment)
	val outputSelected by rememberUpdatedState(selectedOutput)
	var tick by remember { mutableLongStateOf(0L) }

	LaunchedEffect(fps) {
		var last = 0L
		val pacer = FramePacer(frameIntervalNanos(fps))
		while (true) withFrameNanos { now ->
			if (!pacer.due(now)) return@withFrameNanos
			val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
			last = now
			if (dt > 0f) {
				runtime.drag.update(dt)
				runtime.engine?.let { runtime.outputs = it.step(inputs(), dt) }
				runtime.publishPeaks(now, rangesNow)
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
internal fun nearestVertex(rest: List<Offset>, y: Float): Int? =
	(1 until rest.size).minByOrNull { abs(rest[it].y - y) }

internal fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f

internal fun round2(v: Float) = kotlin.math.round(v * 100f) / 100f
