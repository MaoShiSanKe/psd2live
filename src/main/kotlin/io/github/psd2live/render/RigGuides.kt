package io.github.psd2live.render

import io.github.psd2live.core.CanvasViewport
import io.github.psd2live.core.RigCanvasSupport
import io.github.psd2live.core.RigInformationOverlay
import java.awt.Color
import java.awt.geom.Area
import java.awt.geom.PathIterator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The rig guides the GPU draws in place of the Java2D guide pass: warp lattices with their corner marks,
 * rotation needles and selection boxes, in the colours, widths and order [RigInformationOverlay] and
 * [RigCanvasSupport] paint them. Text stays with the Java2D pass.
 *
 * The shapes are laid out in screen pixels, as the Java2D painters lay them out, and handed over in world units
 * through [viewport], so a frame drawn for one camera lines up under the next until it is redrawn.
 */
internal class RigGuides(private val viewport: CanvasViewport) {
	val items = ArrayList<OverlayItem>()

	private fun wx(sx: Float) = ((sx - viewport.offsetX) / viewport.scale).toFloat()
	private fun wy(sy: Float) = ((viewport.offsetY - sy) / viewport.scale).toFloat()

	/** Every lattice with its points, then its corner mark, one warp after another as the Java2D pass paints. */
	fun warps(layers: List<RigInformationOverlay.WarpLayer>, corners: Map<String, Area>) {
		for (layer in layers) {
			val warp = layer.warp
			val p = layer.points
			val columns = warp.columns
			val segments = ArrayList<Float>()
			for (r in 0..warp.rows) for (c in 0..columns) {
				val i = r * (columns + 1) + c
				if (i * 2 + 1 >= p.size) continue
				if (c < columns && (i + 1) * 2 + 1 < p.size) segments += listOf(p[i * 2], p[i * 2 + 1], p[(i + 1) * 2], p[(i + 1) * 2 + 1])
				val below = i + columns + 1
				if (r < warp.rows && below * 2 + 1 < p.size) segments += listOf(p[i * 2], p[i * 2 + 1], p[below * 2], p[below * 2 + 1])
			}
			val color = layer.wireColor.rgb
			items += LineBatch(color, layer.strokeWidth, segments.toFloatArray())
			// Java2D's fillOval(x - r, y - r, 2r, 2r) is a disc of radius r.
			items += PointBatch(color, color, layer.pointRadius.toFloat(), 0f, p.copyOf(min(p.size, (warp.rows + 1) * (columns + 1) * 2)))
			corners[warp.id.raw]?.let { area ->
				val accent = layer.accent()
				items += FillBatch(Color(accent.red, accent.green, accent.blue, if (layer.isDimmed) 120 else 200).rgb, contours(area))
			}
		}
	}

	/** The needle [RigInformationOverlay] paints: a shadow, the tapered body with round caps, a white edge and the hub. */
	fun rotations(needles: List<RigInformationOverlay.RotationNeedle>) {
		for (needle in needles) {
			val px = needle.pivot.x; val py = needle.pivot.y
			val tx = needle.tip.x; val ty = needle.tip.y
			val dx = tx - px; val dy = ty - py
			val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-3f)
			val rootR = min(11f, max(8.5f, length * 0.10f))
			val tipR = min(4.5f, max(3.2f, rootR * 0.45f))
			val angle = atan2(dy.toDouble(), dx.toDouble())
			val perpX = (-sin(angle)).toFloat()
			val perpY = cos(angle).toFloat()
			fun body(offsetY: Float) = floatArrayOf(
				wx(tx + perpX * tipR), wy(ty + perpY * tipR + offsetY),
				wx(px + perpX * rootR), wy(py + perpY * rootR + offsetY),
				wx(px - perpX * rootR), wy(py - perpY * rootR + offsetY),
				wx(tx - perpX * tipR), wy(ty - perpY * tipR + offsetY),
			)
			val color = needle.color
			val dimmed = needle.dimmed
			val shadow = Color(0, 0, 0, if (dimmed) 20 else 64).rgb
			items += FillBatch(shadow, listOf(body(1.2f)))
			items += PointBatch(shadow, shadow, tipR, 0f, floatArrayOf(wx(tx), wy(ty + 1.2f)))
			items += PointBatch(shadow, shadow, rootR, 0f, floatArrayOf(wx(px), wy(py + 1.2f)))
			val fill = (if (dimmed) color else Color(color.red, color.green, color.blue, 224)).rgb
			items += FillBatch(fill, listOf(body(0f)))
			items += PointBatch(fill, fill, tipR, 0f, floatArrayOf(wx(tx), wy(ty)))
			items += PointBatch(fill, fill, rootR, 0f, floatArrayOf(wx(px), wy(py)))
			val edge = Color(255, 255, 255, if (dimmed) 40 else 200).rgb
			val outline = body(0f)
			items += LineBatch(edge, 1.1f, loop(outline))
			// The caps' outlines: a 1.1 px ring centred on each cap's edge, over a clear disc.
			items += PointBatch(0, edge, tipR + 0.55f, 1.1f, floatArrayOf(wx(tx), wy(ty)))
			items += PointBatch(0, edge, rootR + 0.55f, 1.1f, floatArrayOf(wx(px), wy(py)))
			val hub = if (dimmed) 2.2f else 4.2f
			val hubShadow = Color(0, 0, 0, if (dimmed) 30 else 90).rgb
			items += PointBatch(hubShadow, hubShadow, hub * 0.55f, 0f, floatArrayOf(wx(px), wy(py + 0.8f)))
			items += PointBatch(-1, -1, hub * 0.5f, 0f, floatArrayOf(wx(px), wy(py)))
			items += PointBatch(color.rgb, color.rgb, hub * 0.28f, 0f, floatArrayOf(wx(px), wy(py)))
		}
	}

	/** [RigCanvasSupport.paintSelectionBounds]'s solid box with its heavier corner brackets. */
	fun selectionBox(bounds: io.github.psd2live.core.Bounds, color: Color, stroke: Float = 2f, cornerBracketLength: Int = 10) {
		val x = viewport.x(bounds.left).toInt().toFloat()
		val y = (viewport.offsetY + bounds.top * viewport.scale).toInt().toFloat()
		val w = (bounds.width * viewport.scale).toInt().coerceAtLeast(2).toFloat()
		val h = (bounds.height * viewport.scale).toInt().coerceAtLeast(2).toFloat()
		val argb = color.rgb
		items += LineBatch(argb, stroke, loop(floatArrayOf(wx(x), wy(y), wx(x + w), wy(y), wx(x + w), wy(y + h), wx(x), wy(y + h))))
		val cl = minOf(cornerBracketLength.toFloat(), w / 3, h / 3)
		if (cl > 2f) {
			val s = ArrayList<Float>()
			fun seg(x0: Float, y0: Float, x1: Float, y1: Float) { s += listOf(wx(x0), wy(y0), wx(x1), wy(y1)) }
			seg(x, y, x + cl, y); seg(x, y, x, y + cl)
			seg(x + w, y, x + w - cl, y); seg(x + w, y, x + w, y + cl)
			seg(x, y + h, x + cl, y + h); seg(x, y + h, x, y + h - cl)
			seg(x + w, y + h, x + w - cl, y + h); seg(x + w, y + h, x + w, y + h - cl)
			items += LineBatch(argb, stroke * 1.5f, s.toFloatArray())
		}
	}

	/**
	 * [RigInformationOverlay.paintDeformPaths]'s guides: each curve with its halo, the width and hardness rings
	 * round its points when asked for, then its points, corners as diamonds.
	 */
	fun paths(looks: List<RigInformationOverlay.DeformPathLook>) {
		for (look in looks) {
			val curve = look.curvePoints
			if (curve.size >= 2) {
				val world = FloatArray(curve.size * 2)
				curve.forEachIndexed { i, (x, y) -> world[i * 2] = wx(x); world[i * 2 + 1] = wy(y) }
				items += PolylineBatch(look.haloColor.rgb, look.strokeWidth + 2f, world, look.path.closed)
				items += PolylineBatch(look.curveColor.rgb, look.strokeWidth, world, look.path.closed)
			}
			if (look.drawWidth || look.drawHardness) {
				for ((x, y) in look.screenPoints) {
					val inner = look.innerRadiusPx
					if (look.drawHardness && inner > 1f) {
						items += PointBatch(Color(33, 150, 243, 35).rgb, Color(33, 150, 243, 160).rgb, inner + 0.6f, 1.2f,
							floatArrayOf(wx(x), wy(y)))
					}
					val outer = look.outerRadiusPx
					if (look.drawWidth && outer > 1f) items += LineBatch(Color(244, 67, 54, 180).rgb, 1.4f, dashedCircle(x, y, outer))
				}
			}
			val dimmed = look.isDimmed
			val curveColor = look.curveColor
			look.screenPoints.forEachIndexed { i, (x, y) ->
				if (look.isCorner(i)) {
					val d = look.diamondSize()
					val diamond = floatArrayOf(wx(x), wy(y - d), wx(x + d), wy(y), wx(x), wy(y + d), wx(x - d), wy(y))
					items += FillBatch((if (dimmed) Color(180, 150, 50, 60) else Color(255, 202, 40)).rgb, listOf(diamond))
					items += LineBatch((if (dimmed) Color(20, 20, 24, 40) else Color(20, 20, 24, 220)).rgb, 1.2f, loop(diamond))
				} else {
					// Java2D's fillOval then a 1.2 px drawOval on the same circle: a disc with a ring over its edge.
					val r = look.pointRadius().toFloat()
					val fill = if (dimmed) Color(curveColor.red, curveColor.green, curveColor.blue, 50) else curveColor
					val ring = if (dimmed) Color(20, 20, 24, 40) else Color.WHITE
					items += PointBatch(fill.rgb, ring.rgb, r + 0.6f, 1.2f, floatArrayOf(wx(x), wy(y)))
				}
			}
		}
	}

	/** A circle of [radius] screen pixels round ([cx], [cy]) as 4 px dashes with 4 px gaps, Java2D's dash pattern. */
	private fun dashedCircle(cx: Float, cy: Float, radius: Float): FloatArray {
		val circumference = 2.0 * Math.PI * radius
		val dashes = (circumference / 8.0).toInt().coerceIn(1, 2048)
		val step = 2.0 * Math.PI / (circumference / 8.0).coerceAtLeast(1.0)
		val dash = step * 0.5
		val out = ArrayList<Float>(dashes * 8)
		for (k in 0 until dashes) {
			// Each dash as a few short pieces, so a large circle's dashes stay on the circle.
			val start = k * step
			val pieces = 3
			for (p in 0 until pieces) {
				val a0 = start + dash * p / pieces
				val a1 = start + dash * (p + 1) / pieces
				out += wx((cx + cos(a0) * radius).toFloat()); out += wy((cy + sin(a0) * radius).toFloat())
				out += wx((cx + cos(a1) * radius).toFloat()); out += wy((cy + sin(a1) * radius).toFloat())
			}
		}
		return out.toFloatArray()
	}

	/** The closed outlines of [area], flattened, in world units. */
	private fun contours(area: Area): List<FloatArray> {
		val out = ArrayList<FloatArray>()
		val current = ArrayList<Float>()
		val coords = DoubleArray(6)
		val iterator = area.getPathIterator(null, 0.25)
		while (!iterator.isDone) {
			when (iterator.currentSegment(coords)) {
				PathIterator.SEG_MOVETO -> {
					if (current.size >= 6) out += current.toFloatArray()
					current.clear()
					current += wx(coords[0].toFloat()); current += wy(coords[1].toFloat())
				}
				PathIterator.SEG_LINETO -> { current += wx(coords[0].toFloat()); current += wy(coords[1].toFloat()) }
				PathIterator.SEG_CLOSE -> {
					if (current.size >= 6) out += current.toFloatArray()
					current.clear()
				}
			}
			iterator.next()
		}
		if (current.size >= 6) out += current.toFloatArray()
		return out
	}

	/** A closed polygon's edges as segments. */
	private fun loop(points: FloatArray): FloatArray {
		val n = points.size / 2
		val out = FloatArray(n * 4)
		for (i in 0 until n) {
			val j = (i + 1) % n
			out[i * 4] = points[i * 2]; out[i * 4 + 1] = points[i * 2 + 1]
			out[i * 4 + 2] = points[j * 2]; out[i * 4 + 3] = points[j * 2 + 1]
		}
		return out
	}

}
