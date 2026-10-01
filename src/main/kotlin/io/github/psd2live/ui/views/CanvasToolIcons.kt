package io.github.psd2live.ui.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.psd2live.ui.BrushShape
import io.github.psd2live.ui.CanvasTool
import io.github.psd2live.ui.EditHierarchyMode
import io.github.psd2live.ui.GlueSubTool
import io.github.psd2live.ui.PaintShape
import io.github.psd2live.ui.components.drawSingleBoneIcon
import io.github.psd2live.ui.components.drawBoneIcon
import org.umamo.runtime.model.VertexGroupKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * The canvas toolbar's icons. Every one is drawn on the same 18-unit grid with the same round 1.4-unit
 * stroke and scaled to whatever box it is given, so the tool rows and the shape rows under them read as
 * one set. Hand tools (brush, pencil, knife ...) are drawn lying flat with the working end on the left
 * and turned 45 degrees, so they all point the same way.
 */

private const val GRID = 18f
private const val LINE_WIDTH = 1.4f

/** Grid-unit drawing helpers bound to one icon's scope and tint. */
private class IconPen(val scope: DrawScope, val color: Color) {
    val s = scope.size.minDimension / GRID
    val soft = color.copy(alpha = color.alpha * 0.28f)

    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    fun stroke(width: Float = LINE_WIDTH) = Stroke(width * s, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun path(block: GridPath.() -> Unit): Path = GridPath(s).apply(block).path

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = LINE_WIDTH, tint: Color = color) =
        scope.drawLine(tint, p(x1, y1), p(x2, y2), width * s, cap = StrokeCap.Round)

    fun outline(path: Path, width: Float = LINE_WIDTH) = scope.drawPath(path, color, style = stroke(width))

    fun fill(path: Path, tint: Color = color) = scope.drawPath(path, tint)

    fun ring(x: Float, y: Float, r: Float, width: Float = LINE_WIDTH) =
        scope.drawCircle(color, r * s, p(x, y), style = stroke(width))

    fun dot(x: Float, y: Float, r: Float, tint: Color = color) = scope.drawCircle(tint, r * s, p(x, y))

    fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 1f, width: Float = LINE_WIDTH) =
        scope.drawRoundRect(color, p(x, y), Size(w * s, h * s), CornerRadius(r * s), style = stroke(width))

    fun fillBox(x: Float, y: Float, w: Float, h: Float, r: Float = 1f, tint: Color = color) =
        scope.drawRoundRect(tint, p(x, y), Size(w * s, h * s), CornerRadius(r * s))

    /** An open arrowhead at ([x], [y]) pointing along ([dx], [dy]). */
    fun chevron(x: Float, y: Float, dx: Float, dy: Float, length: Float = 3f) {
        val n = hypot(dx, dy)
        val ux = dx / n
        val uy = dy / n
        val c = cos(0.7f)
        val sn = sin(0.7f)
        outline(path {
            m(x - length * (ux * c - uy * sn), y - length * (uy * c + ux * sn))
            l(x, y)
            l(x - length * (ux * c + uy * sn), y - length * (uy * c - ux * sn))
        })
    }

    /** Draws [block] turned [degrees] about ([x], [y]). */
    fun turned(degrees: Float, x: Float = 9f, y: Float = 9f, block: () -> Unit) =
        scope.rotate(degrees, p(x, y)) { block() }
}

private class GridPath(private val s: Float) {
    val path = Path()
    fun m(x: Float, y: Float) = path.moveTo(x * s, y * s)
    fun l(x: Float, y: Float) = path.lineTo(x * s, y * s)
    fun q(x1: Float, y1: Float, x: Float, y: Float) = path.quadraticTo(x1 * s, y1 * s, x * s, y * s)
    fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        path.cubicTo(x1 * s, y1 * s, x2 * s, y2 * s, x * s, y * s)
    fun z() = path.close()
}

@Composable
internal fun ToolIcon(
    tool: CanvasTool,
    color: Color,
    brushShape: BrushShape? = null,
    paintShape: PaintShape? = null,
) {
    Canvas(Modifier.size(18.dp)) { drawToolIcon(tool, color, brushShape, paintShape) }
}

/** The hierarchy mode bar's icons, one per mode, on the same grid as the tools. */
@Composable
internal fun ModeIcon(mode: EditHierarchyMode, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawModeIcon(mode, color) }
}

@Composable
internal fun BrushShapeIcon(shape: BrushShape, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawBrushShapeIcon(shape, color) }
}

@Composable
internal fun PaintShapeIcon(shape: PaintShape, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawPaintShapeIcon(shape, color) }
}

@Composable
internal fun GlueSubToolIcon(subTool: GlueSubTool, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawGlueSubToolIcon(subTool, color) }
}

internal fun DrawScope.drawToolIcon(
    tool: CanvasTool,
    color: Color,
    brushShape: BrushShape? = null,
    paintShape: PaintShape? = null,
) {
    val pen = IconPen(this, color)
    when (tool) {
        CanvasTool.SELECT -> pen.selectArrow()
        CanvasTool.LASSO_SELECT -> pen.lasso()
        CanvasTool.BRUSH_SELECT -> pen.brushSelect()
        CanvasTool.BRUSH -> pen.deformBrush(brushShape ?: BrushShape.CIRCLE)
        CanvasTool.SMOOTH -> pen.smooth()
        CanvasTool.INFLATE -> pen.inflate()
        CanvasTool.SKELETON_POSE -> pen.skeletonPose()
        CanvasTool.SKELETON_EDIT -> pen.skeletonEdit()
        CanvasTool.CREATE_WARP -> pen.warpLattice()
        CanvasTool.CREATE_ROTATION -> pen.rotationDeformer()
        CanvasTool.CREATE_DEFORM_PATH -> pen.deformPath()
        CanvasTool.GLUE -> pen.glue()
        CanvasTool.SUBDIVIDE -> pen.subdivide()
        CanvasTool.KNIFE -> pen.knife()
        CanvasTool.WEIGHT_PAINT -> pen.weightPaint()
        CanvasTool.WEIGHT_GRADIENT -> pen.weightGradient()
        CanvasTool.PAINT_BRUSH -> pen.paintBrush()
        CanvasTool.PAINT_PENCIL -> pen.pencil()
        CanvasTool.PAINT_ERASER -> pen.eraser()
        CanvasTool.PAINT_BUCKET -> pen.bucket()
        CanvasTool.PAINT_EYEDROPPER -> pen.eyedropper()
        // The row carries the face in hand, the way the deform brush's row carries its footprint.
        CanvasTool.PAINT_SHAPE -> pen.paintShape(paintShape ?: PaintShape.LINE)
    }
}

internal fun DrawScope.drawModeIcon(mode: EditHierarchyMode, color: Color) {
    val pen = IconPen(this, color)
    when (mode) {
        // Object: a part inside the transform box it is moved by.
        EditHierarchyMode.SELECT -> {
            pen.box(3.4f, 3.4f, 11.2f, 11.2f, 0.6f, width = 1.1f)
            pen.dot(9f, 9f, 3.4f, pen.soft)
            pen.ring(9f, 9f, 3.4f)
            listOf(3.4f to 3.4f, 14.6f to 3.4f, 3.4f to 14.6f, 14.6f to 14.6f).forEach { (x, y) ->
                pen.fillBox(x - 1.7f, y - 1.7f, 3.4f, 3.4f, 0.6f)
            }
        }
        // Deform: the part's rest shape (dashed) and the shape it is bent into, in front of it.
        EditHierarchyMode.DEFORM -> {
            val bent = pen.path {
                m(6.4f, 2.2f)
                c(9.4f, 0.6f, 12.4f, 4f, 15.8f, 2.4f)
                c(14f, 5.6f, 17.4f, 9.2f, 15.6f, 12.2f)
                c(12.4f, 13.8f, 9.4f, 10.4f, 6.2f, 12f)
                c(7.8f, 8.8f, 4.6f, 5.4f, 6.4f, 2.2f)
                z()
            }
            val dash = 1.8f * pen.s
            clipPath(bent, ClipOp.Difference) {
                drawRoundRect(
                    color, pen.p(2.4f, 6f), Size(9.6f * pen.s, 9.6f * pen.s), CornerRadius(0.8f * pen.s),
                    style = Stroke(1.1f * pen.s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            }
            pen.fill(bent, pen.soft)
            pen.outline(bent)
        }
        // Edit: the mesh itself, its triangles and vertices.
        EditHierarchyMode.EDIT -> {
            val a = 2.8f to 14.6f
            val b = 7.6f to 3.2f
            val c = 15.2f to 5.4f
            val d = 12.6f to 15f
            listOf(a to b, b to c, c to d, d to a, b to d).forEach { (p, q) ->
                pen.line(p.first, p.second, q.first, q.second, width = if (p == b && q == d) 1f else LINE_WIDTH)
            }
            listOf(a, b, c, d).forEach { (x, y) -> pen.dot(x, y, 1.9f) }
        }
        // Simulate: a cloth pinned along its top edge, its hem swinging free.
        EditHierarchyMode.SIMULATE -> {
            val cloth = pen.path {
                m(3.2f, 3.4f)
                l(14.8f, 3.4f)
                c(15.4f, 7.6f, 16.6f, 11.4f, 15.6f, 14.8f)
                c(13.6f, 13.2f, 11.6f, 16.4f, 9.2f, 14.6f)
                c(7f, 16.4f, 4.6f, 13.2f, 2.4f, 14.6f)
                c(2f, 10.6f, 3.4f, 7f, 3.2f, 3.4f)
                z()
            }
            pen.fill(cloth, pen.soft)
            pen.outline(cloth)
            pen.dot(3.2f, 3.4f, 1.8f)
            pen.dot(9f, 3.4f, 1.8f)
            pen.dot(14.8f, 3.4f, 1.8f)
        }
        EditHierarchyMode.SKELETON -> drawSingleBoneIcon(color)
        // Paint: a palette with its wells.
        EditHierarchyMode.PAINT -> {
            val palette = pen.path {
                m(9f, 2f)
                c(13f, 2f, 16f, 5f, 16f, 8.6f)
                c(16f, 11f, 14.3f, 11.4f, 12.8f, 11.1f)
                c(11.2f, 10.8f, 10.3f, 12.2f, 11f, 13.6f)
                c(11.6f, 15f, 10.6f, 16f, 9f, 16f)
                c(5.1f, 16f, 2f, 12.9f, 2f, 9f)
                c(2f, 5.1f, 5.1f, 2f, 9f, 2f)
                z()
            }
            pen.fill(palette, pen.soft)
            pen.outline(palette)
            pen.dot(5.6f, 7.6f, 1.4f)
            pen.dot(8.8f, 5.2f, 1.4f)
            pen.dot(12.4f, 6.4f, 1.4f)
            pen.dot(5.8f, 11.8f, 1.4f)
        }
    }
}

/** Preview in the mode list: an eye, since the canvas only watches the model play. */
internal fun DrawScope.drawPreviewModeIcon(color: Color) {
    val pen = IconPen(this, color)
    val eye = pen.path {
        m(1.8f, 9f)
        c(4.2f, 4.4f, 13.8f, 4.4f, 16.2f, 9f)
        c(13.8f, 13.6f, 4.2f, 13.6f, 1.8f, 9f)
        z()
    }
    pen.fill(eye, pen.soft)
    pen.outline(eye)
    pen.ring(9f, 9f, 2.6f)
    pen.dot(9f, 9f, 1.1f)
}

internal fun DrawScope.drawBrushShapeIcon(shape: BrushShape, color: Color) {
    val pen = IconPen(this, color)
    when (shape) {
        BrushShape.CIRCLE -> {
            pen.ring(9f, 9f, 6.2f)
            pen.dot(9f, 9f, 1.6f)
        }
        BrushShape.LINE -> {
            pen.line(4f, 14f, 14f, 4f)
            pen.dot(4f, 14f, 1.9f)
            pen.dot(14f, 4f, 1.9f)
        }
        BrushShape.RECTANGLE -> {
            pen.fillBox(3f, 4.5f, 12f, 9f, 1.4f, pen.soft)
            pen.box(3f, 4.5f, 12f, 9f, 1.4f)
        }
    }
}

internal fun DrawScope.drawPaintShapeIcon(shape: PaintShape, color: Color) = IconPen(this, color).paintShape(shape)

internal fun DrawScope.drawGlueSubToolIcon(subTool: GlueSubTool, color: Color) {
    val pen = IconPen(this, color)
    when (subTool) {
        // A brush ring over a weld: two points pulled into one.
        GlueSubTool.BRUSH -> {
            pen.ring(9f, 9f, 6.8f)
            pen.line(6.6f, 9f, 11.4f, 9f)
            pen.dot(6.6f, 9f, 1.9f)
            pen.dot(11.4f, 9f, 1.9f)
        }
        // A balance: how the weld's weight splits between A and B.
        GlueSubTool.WEIGHT -> {
            val fulcrum = pen.path { m(9f, 7f); l(12f, 14f); l(6f, 14f); z() }
            pen.fill(fulcrum, pen.soft)
            pen.outline(fulcrum)
            pen.line(2.5f, 7f, 15.5f, 7f)
            pen.dot(3f, 7f, 1.9f)
            pen.dot(15f, 7f, 1.9f)
        }
        // Two sides pushed back together onto a seam.
        GlueSubTool.REMERGE -> {
            pen.line(9f, 3f, 9f, 15f)
            pen.line(2f, 9f, 6.6f, 9f)
            pen.chevron(6.6f, 9f, 1f, 0f, 3.2f)
            pen.line(16f, 9f, 11.4f, 9f)
            pen.chevron(11.4f, 9f, -1f, 0f, 3.2f)
        }
    }
}

// --- selection -------------------------------------------------------------------------------------------

private fun IconPen.selectArrow() {
    val arrow = path {
        m(4.5f, 2.5f); l(4.5f, 14.5f); l(7.6f, 11.8f); l(9.5f, 16f); l(11.6f, 15.1f); l(9.7f, 10.9f); l(13.8f, 10.8f); z()
    }
    fill(arrow, soft)
    outline(arrow)
}

private fun IconPen.lasso() {
    val loop = path {
        m(6.2f, 11.6f)
        c(2.2f, 10.4f, 2.4f, 3.6f, 9.6f, 3.1f)
        c(15.4f, 2.7f, 17.2f, 7.4f, 13.2f, 10f)
        c(11.2f, 11.4f, 8.4f, 12.1f, 6.2f, 11.6f)
    }
    fill(loop, soft)
    outline(loop)
    outline(path { m(6.2f, 11.6f); c(4.4f, 12.6f, 6.6f, 14.4f, 4.2f, 16f) })
    dot(6.2f, 11.6f, 1.5f)
}

private fun IconPen.brushSelect() {
    val r = 6.4f
    val period = (2 * PI * r / 10).toFloat()
    scope.drawCircle(
        color, r * s, p(9f, 9f),
        style = Stroke(LINE_WIDTH * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(period * 0.55f * s, period * 0.45f * s))),
    )
    dot(9f, 9f, 2.6f)
}

// --- deform brushes --------------------------------------------------------------------------------------

/** The deform brush: its footprint in hand, dragged along with the mesh under it. */
private fun IconPen.deformBrush(shape: BrushShape) {
    when (shape) {
        BrushShape.CIRCLE -> {
            scope.drawCircle(soft, 5f * s, p(11f, 7f))
            ring(11f, 7f, 5f)
        }
        BrushShape.RECTANGLE -> {
            fillBox(6.4f, 2.6f, 9.2f, 8.8f, 1.2f, soft)
            box(6.4f, 2.6f, 9.2f, 8.8f, 1.2f)
        }
        BrushShape.LINE -> {
            line(6.4f, 2.4f, 15.6f, 11.6f)
            dot(6.4f, 2.4f, 1.6f)
            dot(15.6f, 11.6f, 1.6f)
        }
    }
    line(2.6f, 15.4f, 6.4f, 11.6f)
    line(2.2f, 11.2f, 4.2f, 9.2f)
    line(6.8f, 15.8f, 8.8f, 13.8f)
}

/** Smooth: a ripple dying out into a straight line under the brush. */
private fun IconPen.smooth() {
    ring(9f, 9f, 7.2f, width = 1.1f)
    outline(path {
        m(3.2f, 9.6f)
        c(4.2f, 5.2f, 6.2f, 5.2f, 7.1f, 9f)
        c(7.9f, 11.6f, 9.6f, 11.4f, 10.6f, 9.4f)
        q(11.4f, 8.4f, 14.8f, 8.6f)
    })
}

/** Inflate: a core pushed outwards on all four sides. */
private fun IconPen.inflate() {
    scope.drawCircle(soft, 2.8f * s, p(9f, 9f))
    ring(9f, 9f, 2.8f)
    listOf(0f to -1f, 1f to 0f, 0f to 1f, -1f to 0f).forEach { (dx, dy) ->
        line(9f + dx * 5.2f, 9f + dy * 5.2f, 9f + dx * 7.8f, 9f + dy * 7.8f)
        chevron(9f + dx * 7.8f, 9f + dy * 7.8f, dx, dy, 2.3f)
    }
}

// --- skeleton --------------------------------------------------------------------------------------------

/** Pose: a bone swung about its head. */
private fun IconPen.skeletonPose() {
    scope.drawBoneIcon(p(4.2f, 13.8f), p(12.2f, 5.8f), color, stroke = 1.2f * s, headRadius = 1.9f * s)
    val r = 12f
    val start = -80f
    val end = -12f
    scope.drawArc(
        color, start, end - start, false, p(4.2f - r, 13.8f - r), Size(2 * r * s, 2 * r * s),
        style = stroke(),
    )
    val a = Math.toRadians(end.toDouble())
    chevron(4.2f + r * cos(a).toFloat(), 13.8f + r * sin(a).toFloat(), -sin(a).toFloat(), cos(a).toFloat(), 2.8f)
}

/** Edit: a bone with both joints opened as handles. */
private fun IconPen.skeletonEdit() {
    scope.drawBoneIcon(p(4.8f, 13.2f), p(13.2f, 4.8f), color, stroke = 1.2f * s, headRadius = 0f)
    listOf(3.6f to 14.4f, 14.4f to 3.6f).forEach { (x, y) ->
        dot(x, y, 2.4f, soft)
        ring(x, y, 2.4f)
    }
}

// --- deformers -------------------------------------------------------------------------------------------

/** Warp: a lattice bowed out by the deformer, corners as handles. */
private fun IconPen.warpLattice() {
    fun at(u: Float, v: Float): Offset {
        val bulge = 0.16f
        val x = 9f + (u - 0.5f) * 12f * (1f + bulge * sin(PI.toFloat() * v))
        val y = 9f + (v - 0.5f) * 12f * (1f + bulge * sin(PI.toFloat() * u))
        return Offset(x, y)
    }
    val steps = 12
    for (i in 0..3) {
        val t = i / 3f
        val edge = i == 0 || i == 3
        val width = if (edge) LINE_WIDTH else 1f
        listOf<(Float) -> Offset>({ at(t, it) }, { at(it, t) }).forEach { curve ->
            outline(path {
                val first = curve(0f)
                m(first.x, first.y)
                for (k in 1..steps) curve(k / steps.toFloat()).let { l(it.x, it.y) }
            }, width)
        }
    }
    listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (u, v) -> at(u, v).let { dot(it.x, it.y, 1.7f) } }
}

/** Rotation: a pivot with its handle and the turn it gives. */
private fun IconPen.rotationDeformer() {
    val cx = 9f
    val cy = 12.4f
    val r = 7f
    val start = -158f
    val end = -22f
    scope.drawArc(color, start, end - start, false, p(cx - r, cy - r), Size(2 * r * s, 2 * r * s), style = stroke())
    val a = Math.toRadians(end.toDouble())
    chevron(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), -sin(a).toFloat(), cos(a).toFloat(), 2.8f)
    line(cx, cy - 2.4f, cx, 2.6f)
    dot(cx, 2.6f, 1.4f)
    dot(cx, cy, 2.4f, soft)
    ring(cx, cy, 2.4f)
}

/** Deform path: a curve through anchors, the middle one showing its tangent handles. */
private fun IconPen.deformPath() {
    outline(path { m(3f, 14.6f); c(3f, 7f, 15f, 11f, 15f, 3.4f) })
    line(5.2f, 10.2f, 12.8f, 7.8f, width = 1f)
    ring(5.2f, 10.2f, 1.1f, width = 1f)
    ring(12.8f, 7.8f, 1.1f, width = 1f)
    dot(9f, 9f, 1.8f)
    fillBox(1.6f, 13.2f, 2.8f, 2.8f, 0.5f)
    fillBox(13.6f, 2f, 2.8f, 2.8f, 0.5f)
}

// --- mesh editing ----------------------------------------------------------------------------------------

/** Glue: two meshes whose overlap is welded into one. */
private fun IconPen.glue() {
    val a = Path().apply { addOval(androidx.compose.ui.geometry.Rect(p(6.4f, 9f), 4.8f * s)) }
    val b = Path().apply { addOval(androidx.compose.ui.geometry.Rect(p(11.6f, 9f), 4.8f * s)) }
    fill(Path().apply { op(a, b, PathOperation.Intersect) })
    outline(a)
    outline(b)
}

/** Subdivide: a triangle split at its edge midpoints. */
private fun IconPen.subdivide() {
    outline(path { m(9f, 2.6f); l(16f, 15.2f); l(2f, 15.2f); z() })
    outline(path { m(5.5f, 8.9f); l(12.5f, 8.9f); l(9f, 15.2f); z() }, width = 1f)
    dot(5.5f, 8.9f, 1.5f)
    dot(12.5f, 8.9f, 1.5f)
    dot(9f, 15.2f, 1.5f)
}

private fun IconPen.knife() = turned(-45f) {
    val blade = path {
        m(11f, 7f); l(4.4f, 7f)
        c(2.4f, 7f, 1.1f, 8.6f, 0.8f, 10.8f)
        l(11f, 10.8f); z()
    }
    fill(blade, soft)
    outline(blade)
    fillBox(12f, 7.2f, 5.6f, 3.4f, 1.4f)
}

/** A triangle whose corners carry falling weights, under a brush ring's arc. */
private fun IconPen.weightPaint() {
    val face = path { m(3f, 14.5f); l(9f, 4f); l(15f, 14.5f); z() }
    fill(face, soft)
    outline(face)
    dot(3f, 14.5f, 2.4f)
    dot(9f, 4f, 1.7f)
    dot(15f, 14.5f, 1.1f)
}

/** A ramp from full to empty along the drag, with the drag's two ends. */
private fun IconPen.weightGradient() {
    val bands = 5
    for (i in 0 until bands) {
        val alpha = 1f - i / bands.toFloat()
        fillBox(2.6f + i * 2.56f, 5f, 2.56f, 8f, 0f, color.copy(alpha = color.alpha * (0.12f + 0.5f * alpha)))
    }
    box(2.6f, 5f, 12.8f, 8f, 0.8f)
    line(3.6f, 9f, 14.4f, 9f, width = 1.1f)
    dot(3.6f, 9f, 1.8f)
    ring(14.4f, 9f, 1.6f, width = 1.1f)
}

// --- painting --------------------------------------------------------------------------------------------

private fun IconPen.paintBrush() = turned(-45f) {
    fill(path {
        m(0.6f, 9f)
        c(2f, 7.4f, 3.8f, 6.9f, 5.8f, 7.1f)
        l(5.8f, 10.9f)
        c(3.8f, 11.1f, 2f, 10.6f, 0.6f, 9f)
        z()
    })
    box(6.4f, 6.9f, 3f, 4.2f, 0.6f)
    outline(path { m(10f, 7.6f); l(16.2f, 8.1f); q(17.8f, 9f, 16.2f, 9.9f); l(10f, 10.4f) })
}

private fun IconPen.pencil() = turned(-45f) {
    fillBox(14f, 6.8f, 3.4f, 4.4f, 1.2f, soft)
    outline(path {
        m(5f, 6.8f); l(16.2f, 6.8f)
        q(17.4f, 6.8f, 17.4f, 8f); l(17.4f, 10f)
        q(17.4f, 11.2f, 16.2f, 11.2f); l(5f, 11.2f)
        l(0.8f, 9f); z()
    })
    line(5f, 6.8f, 5f, 11.2f, width = 1f)
    line(14f, 6.8f, 14f, 11.2f, width = 1f)
    fill(path { m(0.8f, 9f); l(2.5f, 8.1f); l(2.5f, 9.9f); z() })
}

private fun IconPen.eraser() {
    turned(-45f, 9f, 8f) {
        fillBox(3.5f, 5.3f, 4.4f, 5.4f, 1.2f, soft)
        box(3.5f, 5.3f, 11f, 5.4f, 1.2f)
        line(7.9f, 5.3f, 7.9f, 10.7f)
    }
    line(9.5f, 15.8f, 16f, 15.8f)
}

private fun IconPen.bucket() {
    turned(-30f, 8f, 10f) {
        val body = path {
            m(2.6f, 7f); l(13.4f, 7f)
            l(12.2f, 14.6f); q(12f, 15.8f, 10.8f, 15.8f)
            l(5.2f, 15.8f); q(4f, 15.8f, 3.8f, 14.6f); z()
        }
        fill(body, soft)
        outline(body)
        outline(path { m(4f, 7f); c(4f, 1.8f, 12f, 1.8f, 12f, 7f) }, width = 1.1f)
    }
    fill(path {
        m(15.4f, 9.4f)
        c(16.4f, 11f, 17.2f, 12.2f, 17.2f, 13.3f)
        c(17.2f, 14.4f, 16.4f, 15.1f, 15.4f, 15.1f)
        c(14.4f, 15.1f, 13.6f, 14.4f, 13.6f, 13.3f)
        c(13.6f, 12.2f, 14.4f, 11f, 15.4f, 9.4f)
        z()
    })
}

private fun IconPen.eyedropper() = turned(-45f) {
    fill(path { m(1.6f, 9f); l(3.4f, 8f); l(6.4f, 8f); l(6.4f, 10f); l(3.4f, 10f); z() }, soft)
    outline(path { m(10.2f, 7.7f); l(3.2f, 7.7f); l(0.8f, 9f); l(3.2f, 10.3f); l(10.2f, 10.3f) })
    line(10.6f, 6.2f, 10.6f, 11.8f, width = 1.6f)
    fillBox(11.6f, 6.8f, 5.8f, 4.4f, 2.2f)
}

private fun IconPen.paintShape(shape: PaintShape) {
    when (shape) {
        PaintShape.LINE -> {
            line(3.6f, 14.4f, 14.4f, 3.6f)
            fillBox(2.2f, 13f, 2.8f, 2.8f, 0.5f)
            fillBox(13f, 2.2f, 2.8f, 2.8f, 0.5f)
        }
        PaintShape.RECTANGLE -> box(2.6f, 4.2f, 12.8f, 9.6f, 1.2f)
        PaintShape.ELLIPSE ->
            scope.drawOval(color, p(2.2f, 4f), Size(13.6f * s, 10f * s), style = stroke())
    }
}

/** What each kind of vertex group does to a simulated mesh, drawn on the tool grid. */
@Composable
internal fun VertexGroupKindIcon(kind: VertexGroupKind, color: Color, size: Dp = 14.dp) {
    Canvas(Modifier.size(size)) { drawVertexGroupKindIcon(kind, color) }
}

internal fun DrawScope.drawVertexGroupKindIcon(kind: VertexGroupKind, color: Color) {
    val pen = IconPen(this, color)
    when (kind) {
        // A pushpin: the head, and the needle into the mesh.
        VertexGroupKind.PIN -> {
            pen.dot(9f, 6f, 3.6f, pen.soft)
            pen.ring(9f, 6f, 3.6f)
            pen.line(9f, 9.6f, 9f, 15.4f)
        }
        // A spring.
        VertexGroupKind.STIFFNESS -> pen.outline(pen.path {
            m(2.4f, 9f); l(4.4f, 9f); l(6f, 4f); l(8.4f, 14f); l(10.8f, 4f); l(13.2f, 14f); l(14.4f, 9f); l(15.6f, 9f)
        })
        // A weight with its handle.
        VertexGroupKind.MASS -> {
            val body = pen.path { m(5.4f, 7.4f); l(12.6f, 7.4f); l(15f, 15.2f); l(3f, 15.2f); z() }
            pen.fill(body, pen.soft)
            pen.outline(body)
            pen.ring(9f, 4.8f, 2.1f)
        }
        // A swing that dies out.
        VertexGroupKind.DAMPING -> pen.outline(pen.path {
            m(2f, 9f); q(3.6f, 1.6f, 5.2f, 9f); q(6.6f, 15f, 8f, 9f); q(9.2f, 5.2f, 10.4f, 9f); q(11.4f, 11.6f, 12.4f, 9f); l(16f, 9f)
        })
        // Gusts.
        VertexGroupKind.WIND -> {
            pen.outline(pen.path { m(2.4f, 6f); l(11.6f, 6f); q(14.4f, 6f, 14.4f, 3.8f); q(14.4f, 2f, 12.6f, 2.2f) })
            pen.outline(pen.path { m(2.4f, 10f); l(13.8f, 10f); q(16.2f, 10f, 16.2f, 12.2f); q(16.2f, 14f, 14.4f, 13.8f) })
            pen.line(2.4f, 14f, 8.4f, 14f)
        }
        // The drawn shape, and the way back to it.
        VertexGroupKind.GOAL -> {
            pen.fillBox(9f, 9f, 6.4f, 6.4f, 1.2f, pen.soft)
            pen.box(9f, 9f, 6.4f, 6.4f, 1.2f)
            pen.outline(pen.path { m(3.4f, 13.6f); q(3.4f, 4f, 12.2f, 4f) })
            pen.chevron(12.2f, 4f, 1f, 0f, 2.6f)
        }
    }
}

/** The simulation panel's sections. */
internal enum class SimSectionIcon { BAKE, MATERIAL, INPUTS, GLUE, GROUPS }

@Composable
internal fun SimSectionIconView(icon: SimSectionIcon, color: Color, size: Dp = 12.dp) {
    Canvas(Modifier.size(size)) { drawSimSectionIcon(icon, color) }
}

internal fun DrawScope.drawSimSectionIcon(icon: SimSectionIcon, color: Color) {
    val pen = IconPen(this, color)
    when (icon) {
        // Motion caught into keys: a swing over a keyframe.
        SimSectionIcon.BAKE -> {
            pen.outline(pen.path { m(2.4f, 5.4f); q(5.6f, 0.6f, 9f, 5.4f); q(12.4f, 10.2f, 15.6f, 5.4f) })
            val key = pen.path { m(9f, 9.4f); l(13f, 13.2f); l(9f, 17f); l(5f, 13.2f); z() }
            pen.fill(key, pen.soft)
            pen.outline(key)
        }
        // A hanging cloth, like the Simulate mode.
        SimSectionIcon.MATERIAL -> {
            val cloth = pen.path {
                m(3.4f, 3.4f); l(14.6f, 3.4f)
                c(15.2f, 7.6f, 16.2f, 11.4f, 15.2f, 14.8f)
                c(13.4f, 13.2f, 11.4f, 16.4f, 9f, 14.6f)
                c(6.8f, 16.4f, 4.6f, 13.2f, 2.8f, 14.8f)
                c(2f, 10.6f, 3.4f, 7f, 3.4f, 3.4f)
                z()
            }
            pen.fill(cloth, pen.soft)
            pen.outline(cloth)
        }
        // Two parameter sliders.
        SimSectionIcon.INPUTS -> {
            pen.line(2.6f, 5.6f, 15.4f, 5.6f)
            pen.dot(6.4f, 5.6f, 2.2f)
            pen.line(2.6f, 12.4f, 15.4f, 12.4f)
            pen.dot(11.8f, 12.4f, 2.2f)
        }
        SimSectionIcon.GLUE -> pen.glue()
        // Three vertices, each weighted differently.
        SimSectionIcon.GROUPS -> {
            val tri = pen.path { m(9f, 3.6f); l(15f, 14.4f); l(3f, 14.4f); z() }
            pen.fill(tri, pen.soft)
            pen.outline(tri)
            pen.dot(9f, 3.6f, 2.3f)
            pen.dot(15f, 14.4f, 1.6f)
            pen.dot(3f, 14.4f, 1.1f)
        }
    }
}
