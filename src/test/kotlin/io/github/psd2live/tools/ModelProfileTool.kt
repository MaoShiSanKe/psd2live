package io.github.psd2live.tools

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CParameterSource
import org.umamo.format.cmo3.model.gen.CParameterSourceSet
import org.umamo.format.cmo3.model.gen.CPhysicsInput
import org.umamo.format.cmo3.model.gen.CPhysicsOutput
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSource
import org.umamo.format.cmo3.model.gen.CPhysicsSettingsSourceSet
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PuppetModel
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * How a model is rigged and how its parameters move it, as text and pictures, to study a rig or compare
 * two. Outputs go to build/tools/model-profile/:
 *
 * - [cmo3]: each .cmo3 PSD2LIVE_CMO3 names (a file, or a directory of them): its parameters, deformer tree
 *   with grid axes and bounds, drawables, a band profile, how the body parameters move each drawable
 *   (<name>.txt), flat silhouettes over Body X x Body Y (<name>.png) and its physics groups
 *   (<name>-physics.txt).
 * - [sample]: the band profile of the sample PSD2LIVE_SAMPLE names, as generated and on its auto skeleton
 *   (<sample>.txt).
 *
 * The band profile cuts the figure into ten bands top to bottom and gives, per probed parameter, each
 * band's mean shift and width change in percent of the figure's height: PSD2LIVE_PROBES picks the probes
 * (id=value,..., Body X, Body Y and Body Z at their ends by default). PSD2LIVE_SHEET_PARAM draws the
 * silhouettes over that parameter instead of Body X and Body Y.
 */
class ModelProfileTool {
	@Test fun cmo3() {
		requireTools()
		val path = File(setting("PSD2LIVE_CMO3", ""))
		val files = if (path.isDirectory) path.listFiles { f -> f.name.endsWith(".cmo3") }.orEmpty().sortedBy { it.name } else listOf(path)
		require(files.isNotEmpty() && files.all { it.isFile }) { "Set PSD2LIVE_CMO3 to a .cmo3 file or a directory of them" }
		val out = output("model-profile")
		for (file in files) {
			val source = Cmo3.read(file).root as CModelSource
			val model = Cmo3Import.fromModelSource(source)
			File(out, file.nameWithoutExtension + ".txt").writeText(describe(model))
			silhouettes(model, File(out, file.nameWithoutExtension + ".png"))
			File(out, file.nameWithoutExtension + "-physics.txt").writeText(physics(source))
			println("wrote ${file.nameWithoutExtension}.* to $out")
		}
	}

	@Test fun sample() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = build(sample)
		val text = buildString {
			appendLine("== body layers")
			for (layer in built.plain.analysis.layers) if (layer.semantic.tag.group == io.github.psd2live.core.LayerGroup.BODY) {
				appendLine("  ${layer.source.id} '${layer.source.name}' ${layer.semantic.tag} ${layer.semantic.side} ${layer.bounds}")
			}
			appendLine("== auto skeleton")
			for (b in built.spec.bones) appendLine("  ${b.id} ${b.role} head=${b.headX},${b.headY} tail=${b.tailX},${b.tailY} drawables=${b.drawableIds}")
			appendLine("== plain")
			append(profile(built.plain.rig.puppet))
			appendLine("== skeleton")
			append(profile(built.skeletal.rig.puppet))
		}
		val file = File(output("model-profile"), "${sample.name}.txt")
		file.writeText(text)
		println(text)
		println("wrote $file")
	}

	/** The band profile of [model] (see the class). World y points up. */
	private fun profile(model: PuppetModel): String = buildString {
		val evaluator = CpuDeformationEvaluator()
		val rest = evaluator.evaluate(model, emptyMap()).worldPositions
		var top = -Float.MAX_VALUE
		var bottom = Float.MAX_VALUE
		for (p in rest.values) for (i in 1 until p.size step 2) { top = maxOf(top, p[i]); bottom = minOf(bottom, p[i]) }
		val height = top - bottom
		appendLine("band profile (figure height %.0f; band 0 at the top): dx%% dy%% (y down) width%%".format(height))
		val present = model.parameters.mapTo(HashSet()) { it.id.raw }
		val probes = System.getenv("PSD2LIVE_PROBES")?.split(",")?.map { it.substringBefore("=") to it.substringAfter("=").toFloat() }
			?: listOf("ParamBodyAngleX" to 10f, "ParamBodyAngleY" to 10f, "ParamBodyAngleY" to -10f, "ParamBodyAngleZ" to 10f)
		for ((id, value) in probes) {
			if (id !in present) continue
			val moved = evaluator.evaluate(model, mapOf(ParameterId(id) to value)).worldPositions
			val n = IntArray(BANDS)
			val sx = DoubleArray(BANDS)
			val sy = DoubleArray(BANDS)
			val before = Array(BANDS) { ArrayList<Double>() }
			val after = Array(BANDS) { ArrayList<Double>() }
			for ((drawable, a) in rest) {
				val m = moved[drawable] ?: continue
				for (i in a.indices step 2) {
					val band = ((top - a[i + 1]) / height * BANDS).toInt().coerceIn(0, BANDS - 1)
					n[band]++
					sx[band] += (m[i] - a[i]).toDouble()
					sy[band] -= (m[i + 1] - a[i + 1]).toDouble()
					before[band] += a[i].toDouble()
					after[band] += m[i].toDouble()
				}
			}
			fun spread(v: List<Double>): Double { val mean = v.average(); return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size) }
			appendLine("  $id=$value: " + (0 until BANDS).joinToString("  ") { b ->
				if (n[b] == 0) "-" else "%+.1f,%+.1f,%+.0f".format(sx[b] / n[b] / height * 100, sy[b] / n[b] / height * 100, (spread(after[b]) / spread(before[b]) - 1) * 100)
			})
		}
	}

	private fun describe(model: PuppetModel): String = buildString {
		appendLine("canvas ${model.canvasWidth} x ${model.canvasHeight}  ppu=${model.pixelsPerUnit}")
		appendLine("== parameters")
		for (p in model.parameters) appendLine("  ${p.id.raw}  '${p.name}'  ${p.min}..${p.max} (${p.default})")
		val deformers = model.deformers.associateBy { it.id }
		val children = model.deformers.groupBy { it.parent }
		val drawablesUnder = model.drawables.groupBy { it.parentDeformerId }
		val evaluator = CpuDeformationEvaluator()
		val rest = evaluator.evaluate(model, emptyMap()).worldPositions
		fun descendants(id: DeformerId): List<Drawable> = drawablesUnder[id].orEmpty() + children[id].orEmpty().flatMap { descendants(it.id) }
		fun bounds(drawables: List<Drawable>): String {
			var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
			for (d in drawables) rest[d.id]?.let { p -> for (i in p.indices step 2) { l = minOf(l, p[i]); r = maxOf(r, p[i]); t = minOf(t, p[i + 1]); b = maxOf(b, p[i + 1]) } }
			return if (l > r) "-" else "[%.0f,%.0f..%.0f,%.0f]".format(l, t, r, b)
		}
		fun axes(d: Deformer): String {
			val grid = when (d) { is Deformer.Warp -> d.geometryGrid; is Deformer.Rotation -> d.geometryGrid; else -> null }
			return grid?.axes?.joinToString(" ") { a -> a.parameterId.raw + a.keys.joinToString(",", "(", ")") { "%.0f".format(it) } } ?: ""
		}
		appendLine("== deformers (type name id grid axes | drawables direct/total | rest bounds)")
		fun walk(id: DeformerId?, depth: Int) {
			for (d in children[id].orEmpty()) {
				val kind = when (d) { is Deformer.Warp -> "W ${d.columns}x${d.rows}"; is Deformer.Rotation -> "R"; else -> "?" }
				val all = descendants(d.id)
				appendLine("  ".repeat(depth + 1) + "$kind '${d.name}' ${d.id.raw} ${axes(d)} | ${drawablesUnder[d.id].orEmpty().size}/${all.size} | ${bounds(all)}")
				walk(d.id, depth + 1)
			}
		}
		walk(null, 0)
		appendLine("== drawables at the root: " + drawablesUnder[null].orEmpty().joinToString { it.name })
		appendLine("== drawables (name <- parent : axes)")
		for (d in model.drawables) {
			val axes = d.geometryGrid?.axes?.joinToString(" ") { a -> a.parameterId.raw + "(" + a.keys.size + ")" } ?: ""
			appendLine("  '${d.name}' <- ${d.parentDeformerId?.let { deformers[it]?.name }} : $axes")
		}
		append(profile(model))
		appendLine("== motion by parameter (drawable: mean dx,dy / max |d|)")
		val probes = listOf("ParamBodyAngleX" to 1f, "ParamBodyAngleX" to -1f, "ParamBodyAngleY" to 1f, "ParamBodyAngleY" to -1f,
			"ParamBodyAngleZ" to 1f, "ParamAngleX" to 1f, "ParamAngleY" to 1f, "ParamBreath" to 1f)
		val byId = model.parameters.associateBy { it.id.raw }
		for ((id, sign) in probes) {
			val p = byId[id] ?: continue
			val value = if (sign > 0) p.max else p.min
			val moved = evaluator.evaluate(model, mapOf(ParameterId(id) to value)).worldPositions
			appendLine("  $id = $value")
			for (d in model.drawables) {
				val a = rest[d.id] ?: continue
				val b = moved[d.id] ?: continue
				var sx = 0.0; var sy = 0.0; var most = 0.0
				for (i in a.indices step 2) {
					val dx = b[i] - a[i]
					val dy = b[i + 1] - a[i + 1]
					sx += dx; sy += dy
					most = maxOf(most, hypot(dx.toDouble(), dy.toDouble()))
				}
				val n = a.size / 2
				if (most > 0.5) appendLine("    '${d.name}': %.1f,%.1f / %.1f".format(sx / n, sy / n, most))
			}
		}
	}

	/** Every physics group of [source]: its inputs and outputs with their types, weights and scales. */
	private fun physics(source: CModelSource): String = buildString {
		fun list(v: Any?): List<Any?> = when (v) { is Iterable<*> -> v.toList(); is Array<*> -> v.toList(); null -> emptyList(); else -> listOf(v) }
		val names = HashMap<Any, String>()
		(source.parameterSourceSet as? CParameterSourceSet)?.let { set ->
			for (p in list(set._sources)) { p as CParameterSource; names[p.guid!!] = "${p.id} '${p.name}'" }
		}
		val set = source.physicsSettingsSourceSet as? CPhysicsSettingsSourceSet ?: return@buildString
		for (s in list(set._sourceCubismPhysics)) {
			s as CPhysicsSettingsSource
			appendLine("[${s.name}] vertices ${list(s.vertices).size}")
			for (i in list(s.inputs)) {
				i as CPhysicsInput
				appendLine("  in  ${names[i.source] ?: i.source} ${i.type} weight=${i.weight}${if (i.isReverse) " reversed" else ""}")
			}
			for (o in list(s.outputs)) {
				o as CPhysicsOutput
				appendLine("  out ${names[o.destination] ?: o.destination} vertex ${o.vertexIndex} ${o.type} scale=${o.angleScale}${if (o.isReverse) " reversed" else ""}")
			}
		}
	}

	/** Flat silhouettes over Body X x Body Y at -10, 0, 10 on the grey rest pose, each top-level part its own colour. */
	private fun silhouettes(model: PuppetModel, file: File) {
		val evaluator = CpuDeformationEvaluator()
		val partOf = HashMap<DrawableId, String>()
		fun visit(part: Part, top: String) {
			for (child in part.children) when (child) {
				is OrgChild.Drawable -> partOf[child.id] = top
				is OrgChild.Part -> model.partById[child.id]?.let { visit(it, top) }
			}
		}
		for (child in model.rootChildren) if (child is OrgChild.Part) model.partById[child.id]?.let { root ->
			// One level below the root, which usually holds the whole figure.
			val tops = root.children.filterIsInstance<OrgChild.Part>()
			if (tops.isEmpty()) visit(root, root.name)
			for (t in tops) model.partById[t.id]?.let { visit(it, it.name) }
			for (d in root.children.filterIsInstance<OrgChild.Drawable>()) partOf[d.id] = root.name
		}
		val rest = evaluator.evaluate(model, emptyMap())
		var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
		for (p in rest.worldPositions.values) for (i in p.indices step 2) { l = minOf(l, p[i]); r = maxOf(r, p[i]); t = minOf(t, p[i + 1]); b = maxOf(b, p[i + 1]) }
		val cellH = 900
		val scale = cellH / (b - t) * 0.96f
		val cellW = ((r - l) * scale / 0.96f).toInt() + 20
		val image = BufferedImage(cellW * 3, cellH * 3, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.color = Color.WHITE
		g.fillRect(0, 0, image.width, image.height)
		val names = partOf.values.distinct()
		val shown = model.drawables.filter { it.isVisible && it.mesh != null }
		val only = System.getenv("PSD2LIVE_SHEET_PARAM")
		for ((row, y) in listOf(10f, 0f, -10f).withIndex()) for ((column, x) in listOf(-10f, 0f, 10f).withIndex()) {
			val values = if (only != null) mapOf(ParameterId(only) to if (row == 1) x else 0f)
				else mapOf(ParameterId("ParamBodyAngleX") to x, ParameterId("ParamBodyAngleY") to y)
			val posed = evaluator.evaluate(model, values)
			val ox = column * cellW
			val oy = row * cellH
			fun px(v: Float) = ox + 10 + (v - l) * scale
			// World y points up.
			fun py(v: Float) = oy + cellH * 0.02f + (b - v) * scale
			fun draw(geometry: DeformedGeometry, color: (Drawable) -> Color) {
				for (d in shown.sortedBy { geometry.drawOrder[it.id] ?: 500f }) {
					val p = geometry.worldPositions[d.id] ?: continue
					if ((geometry.opacity[d.id] ?: 1f) < 0.05f) continue
					g.color = color(d)
					val indices = d.mesh!!.indices
					for (k in indices.indices step 3) {
						g.fillPolygon(IntArray(3) { px(p[indices[k + it] * 2]).toInt() }, IntArray(3) { py(p[indices[k + it] * 2 + 1]).toInt() }, 3)
					}
				}
			}
			draw(rest) { Color(120, 120, 120) }
			draw(posed) { d ->
				val c = Color(Color.HSBtoRGB((names.indexOf(partOf[d.id]).coerceAtLeast(0) * 0.137f) % 1f, 0.45f, 0.95f))
				Color(c.red, c.green, c.blue, 170)
			}
			g.color = Color(255, 0, 0, 120)
			for (gy in 0 until cellH step 60) g.drawLine(ox, oy + gy, ox + cellW, oy + gy)
			g.drawLine(ox + cellW / 2, oy, ox + cellW / 2, oy + cellH)
			g.color = Color.BLACK
			g.drawRect(ox, oy, cellW - 1, cellH - 1)
			g.drawString(if (only != null) "$only %.0f".format(if (row == 1) x else 0f) else "X %.0f Y %.0f".format(x, y), ox + 4, oy + 14)
		}
		g.dispose()
		ImageIO.write(image, "png", file)
	}

	private companion object {
		const val BANDS = 10
	}
}
