package io.github.psd2live.tools

import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.Bounds
import io.github.psd2live.core.MotionCurveMath
import io.github.psd2live.core.SemanticTag
import io.github.psd2live.core.SkeletonAutoBuilder
import io.github.psd2live.core.SkeletonMotions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test

/**
 * Contact sheets and frame sequences of a sample's generated motion, for checking it by eye. Each
 * renders the sample PSD2LIVE_SAMPLE names (tml by default) on its auto skeleton into build/tools/:
 *
 * - [motions]: every preset motion across its length, the idle loop, a breath with its difference
 *   image, and Body Z, into motion-sheet/<sample>-*.png. PSD2LIVE_VERBOSE=1 also prints the curves.
 * - [body]: Body X x Body Y, the legs and the upper body up close, the lean, the proportions and the leg
 *   poses, plain and on the skeleton, into motion-sheet/<sample>-{stance,lean,size,legposes}*.png.
 * - [tracking]: twelve seconds of the pointer following a slow figure across the canvas, as the
 *   preview drives the head and the body, into motion-frames/<sample>-track/.
 * - [idle]: twelve seconds of the idle into motion-frames/<sample>-idle/.
 */
class MotionSheetTool {
	/** The saved armature's weight transfer and shin rotations, including the project's mesh bindings. */
	@Test fun weight() = kotlinx.coroutines.runBlocking {
		requireTools()
		val sample = Sample.fromEnvironment()
		val preview = if (sample.path.toString().endsWith(".psd2live", true)) {
			io.github.psd2live.project.ProjectRepository().open(sample.path).use { opened ->
				val document = opened.history.head().snapshot
				val builder = io.github.psd2live.application.WorkspacePreviewBuilder()
				val saved = builder.build(document)
				val enabled = saved.config.rigEdits.skeleton?.takeIf { it.enabled }
				if (enabled != null && System.getenv("PSD2LIVE_JOINT_STRESS") == "1") {
					// Expand only the in-memory test rig; an out-of-range render would silently
					// clamp to the saved limit and provide misleading high-angle comparisons.
					val stress = enabled.copy(bones = enabled.bones.map {
						if (it.role == BoneRole.SHIN) it.copy(minAngle = -150f, maxAngle = 150f) else it
					})
					builder.build(document.copy(rigEdits = document.rigEdits.copy(skeleton = stress)))
				} else if (enabled != null) saved else {
					val skeleton = SkeletonAutoBuilder.build(saved.analysis, saved.rig)
					println("Saved project has no enabled skeleton; testing an auto skeleton on its artwork")
					val candidate = document.copy(rigEdits = document.rigEdits.copy(skeleton = skeleton))
					builder.build(builder.normalizeMeshEdits(candidate, saved))
				}
			}
		} else build(sample).skeletal
		val skeleton = requireNotNull(preview.config.rigEdits.skeleton)
		val renderer = Renderer(preview, 520)
		val character = preview.analysis.anchors.character
		val thighs = skeleton.bones.filter { it.role == BoneRole.THIGH }
		val rect = Bounds(character.left, thighs.minOf { it.headY } - character.height * 0.06f,
			character.right, character.bottom + character.height * 0.01f)
		val values = listOf(-1f, -0.5f, 0f, 0.5f, 1f)
		val evaluator = org.umamo.render.eval.CpuDeformationEvaluator()
		val rest = evaluator.evaluate(preview.rig.puppet, emptyMap()).worldPositions
		val footLayers = preview.analysis.layers.filter { it.semantic.tag == SemanticTag.FOOTWEAR }.mapTo(HashSet()) { it.source.id.raw }
		val feet = preview.rig.layerIdByDrawableId.filterValues { it in footLayers }.keys
		for (step in 0..40) {
			val key = -1f + step / 20f
			val posed = evaluator.evaluate(preview.rig.puppet, mapOf(org.umamo.runtime.model.ParameterId("ParamSkelWeight") to key)).worldPositions
			for (raw in feet) {
				val id = org.umamo.runtime.model.DrawableId(raw)
				val before = rest.getValue(id)
				val after = posed.getValue(id)
				val floor = before.filterIndexed { index, _ -> index % 2 == 1 }.min()
				for (vertex in 0 until before.size / 2) if (before[vertex * 2 + 1] <= floor + 2f) {
					kotlin.test.assertEquals(before[vertex * 2], after[vertex * 2], 1f, "$raw sole x at $key")
					kotlin.test.assertEquals(before[vertex * 2 + 1], after[vertex * 2 + 1], 1f, "$raw sole y at $key")
				}
			}
		}
		val out = output("motion-sheet")
		val restCanvas = org.umamo.render.restMeshesToCanvasSpace(preview.rig.puppet)
		var checked = 0
		var maxThighDrift = 0f
		for (shin in skeleton.bones.filter { it.role == BoneRole.SHIN }) {
			val thigh = skeleton.bones.single { it.id == shin.parentId }
			val angles = listOf(shin.minAngle, shin.minAngle * 0.5f, 0f, shin.maxAngle * 0.5f, shin.maxAngle)
			sheet(angles.map { "shin %.1f".format(it) to renderer.render(mapOf(shin.parameterId to it), rect) }, File(out, "${sample.name}-${shin.id}-legs.png"))
			val radius = minOf(thigh.length, shin.length) * 0.28f
			val knee = Bounds(shin.headX - radius, shin.headY - radius, shin.headX + radius, shin.headY + radius)
			sheet(listOf(0f, 60f, 90f, 120f, -120f).map { angle ->
				"knee %.0f".format(angle) to renderer.render(mapOf(shin.parameterId to angle), knee)
			}, File(out, "${sample.name}-${shin.id}-knee.png"))
			if (System.getenv("PSD2LIVE_JOINT_STRESS") == "1") {
				kotlin.test.assertTrue(shin.minAngle <= -150f && shin.maxAngle >= 150f)
				sheet(listOf(90f, 120f, 135f, 150f, -150f).map { angle ->
					"knee %.0f".format(angle) to renderer.render(mapOf(shin.parameterId to angle), knee)
				}, File(out, "${sample.name}-${shin.id}-high-angle.png"))
			}
			for (step in 0..24) {
				val angle = shin.minAngle + (shin.maxAngle - shin.minAngle) * step / 24f
				val posed = evaluator.evaluate(preview.rig.puppet, mapOf(org.umamo.runtime.model.ParameterId(shin.parameterId) to angle)).worldPositions
				for (raw in thigh.drawableIds) {
					val id = org.umamo.runtime.model.DrawableId(raw)
					val mesh = restCanvas.drawables.single { it.id == id }.mesh ?: continue
					val before = rest.getValue(id)
					val after = posed.getValue(id)
					val tree = io.github.psd2live.core.SkeletonRig.jointBones(skeleton)
					val parents = io.github.psd2live.core.SkeletonRig.jointParents(skeleton)
					val skinBones = io.github.psd2live.core.SkeletonRig.skinBones(tree, parents)
					val skins = io.github.psd2live.core.SkeletonManualWeights.weights(mesh.positions, mesh.indices, tree, parents, skeleton.manualWeights[raw])
					val template = io.github.psd2live.core.SkeletonJointTemplates(mesh.positions, mesh.indices, skins, skinBones, tree.map { it.role })
					val turns = FloatArray(tree.size) { if (tree[it].id == shin.id) angle * shin.direction else 0f }
					val folding = template.folding(turns)
					fun area(points: FloatArray, a: Int, b: Int, c: Int) =
						(points[b * 2] - points[a * 2]) * (points[c * 2 + 1] - points[a * 2 + 1]) -
						(points[b * 2 + 1] - points[a * 2 + 1]) * (points[c * 2] - points[a * 2])
					for (i in mesh.indices.indices step 3) {
						val a = mesh.indices[i]; val b = mesh.indices[i + 1]; val c = mesh.indices[i + 2]
						if (folding[a] || folding[b] || folding[c]) continue // intentional fold and overlapping transition
						val initial = area(before, a, b, c)
						if (abs(initial) < 0.01f) continue
						kotlin.test.assertTrue(initial * area(after, a, b, c) > 0f, "$raw triangle ${i / 3} flipped when shin=$angle")
					}
					for (vertex in 0 until mesh.vertexCount) {
						val y = mesh.positions[vertex * 2 + 1]
						if (y < thigh.headY + thigh.length * 0.1f || y > shin.headY - minOf(thigh.length, shin.length) * 0.5f) continue
						checked++
						maxThighDrift = maxOf(maxThighDrift, kotlin.math.hypot(after[vertex * 2] - before[vertex * 2], after[vertex * 2 + 1] - before[vertex * 2 + 1]))
						kotlin.test.assertEquals(before[vertex * 2], after[vertex * 2], 1f, "$raw upper thigh x when shin=$angle")
						kotlin.test.assertEquals(before[vertex * 2 + 1], after[vertex * 2 + 1], 1f, "$raw upper thigh y when shin=$angle")
					}
				}
			}
		}
		kotlin.test.assertTrue(checked > 0, "checked rigid upper-thigh vertices")
		println("Shin rotation: $checked upper-thigh vertices checked; maximum drift $maxThighDrift px")
		sheet(values.map { "weight $it" to renderer.render(mapOf("ParamSkelWeight" to it)) }, File(out, "${sample.name}-weight-keys.png"))
		sheet(values.map { "weight $it" to renderer.render(mapOf("ParamSkelWeight" to it), rect) }, File(out, "${sample.name}-weight-legs.png"))
		val tracks = SkeletonMotions.weightShift(skeleton)
		val times = (0..9).map { SkeletonMotions.WEIGHT_SHIFT_DURATION * it / 9f }
		sheet(times.map { time -> "%.2fs".format(time) to renderer.render(tracks.associate {
			it.parameterId to MotionCurveMath.value(it, time)
		}) }, File(out, "${sample.name}-WeightShift.png"))
		for (thigh in thighs) println("${thigh.id}: hip ${thigh.headX},${thigh.headY} knee ${thigh.tailX},${thigh.tailY}")
	}

	@Test fun motions() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = build(sample)
		val renderer = Renderer(built.skeletal, 360)
		val out = output("motion-sheet")
		for (preset in SkeletonMotions.presets) {
			val tracks = preset.tracks(built.spec)
			if (tracks.isEmpty()) continue
			val duration = tracks.maxOf { it.keys.last().time }
			if (setting("PSD2LIVE_VERBOSE", "") == "1") for (track in tracks) {
				println("${preset.name} ${track.parameterId}: " + (0..14).joinToString(" ") { "%.1f".format(MotionCurveMath.value(track, duration * it / 14f)) } +
					"  keys=" + track.keys.joinToString(",") { "%.2f".format(it.time) })
			}
			val frames = (0 until 10).map { duration * it / 9f }.map { t ->
				"%.2fs".format(t) to renderer.render(tracks.associate { it.parameterId to MotionCurveMath.value(it, t) })
			}
			sheet(frames, File(out, "${sample.name}-${preset.name}.png"))
		}
		val idle = SkeletonMotions.idle(built.spec)
		val idleTimes = (0 until 10).map { SkeletonMotions.IDLE_DURATION * it / 10f }
		sheet(idleTimes.map { t -> "%.1fs".format(t) to renderer.render(idle.associate { it.parameterId to MotionCurveMath.value(it, t) }) },
			File(out, "${sample.name}-Idle.png"))
		// A full breath against none, and where they differ.
		val rest = renderer.render(mapOf("ParamBreath" to 0f))
		val breath = renderer.render(mapOf("ParamBreath" to 1f))
		sheet(listOf("breath 0" to rest, "breath 1" to breath, "diff" to difference(rest, breath)), File(out, "${sample.name}-breath.png"))
		sheet(listOf("Z -10" to renderer.render(mapOf("ParamBodyAngleZ" to -10f)), "Z 0" to rest, "Z +10" to renderer.render(mapOf("ParamBodyAngleZ" to 10f))),
			File(out, "${sample.name}-bodyZ.png"))
	}

	/**
	 * The body's parameters, plain and on the skeleton. PSD2LIVE_BONES (id=hx,hy,tx,ty;... in canvas
	 * pixels) moves bones the auto skeleton misplaces, PSD2LIVE_BIND_LEGS=1 binds the leg and foot meshes
	 * to the first thigh as a user would, and PSD2LIVE_ZOOM (left,top,right,bottom as shares of the
	 * canvas) frames the close-up of the legs.
	 */
	@Test fun body() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = build(sample) { plain ->
			val auto = SkeletonAutoBuilder.build(plain.analysis, plain.rig)
			val placed = System.getenv("PSD2LIVE_BONES")?.let { text ->
				val at = text.split(";").associate { e -> e.substringBefore("=") to e.substringAfter("=").split(",").map { it.toFloat() } }
				auto.copy(bones = auto.bones.map { b -> at[b.id]?.let { b.copy(headX = it[0], headY = it[1], tailX = it[2], tailY = it[3]) } ?: b })
			} ?: auto
			if (setting("PSD2LIVE_BIND_LEGS", "") != "1") placed else {
				val legLayers = plain.analysis.layers.filter { it.semantic.tag == SemanticTag.LEGWEAR || it.semantic.tag == SemanticTag.FOOTWEAR }
					.mapTo(HashSet()) { it.source.id.raw }
				val legs = plain.rig.layerIdByDrawableId.filterValues { it in legLayers }.keys.toList()
				val thigh = placed.bones.first { it.role == BoneRole.THIGH }
				placed.copy(bones = placed.bones.map { if (it.id == thigh.id) it.copy(drawableIds = legs) else it })
			}
		}
		val out = output("motion-sheet")
		for ((tag, preview) in listOf("plain" to built.plain, "skeleton" to built.skeletal)) {
			val renderer = Renderer(preview, 420)
			val c = preview.analysis.anchors.character
			val hipY = preview.analysis.anchors.hipY
			val canvas = renderer.canvas
			val legs = System.getenv("PSD2LIVE_ZOOM")?.split(",")?.map { it.toFloat() }
				?.let { Bounds(it[0] * canvas.right, it[1] * canvas.bottom, it[2] * canvas.right, it[3] * canvas.bottom) }
				?: Bounds(c.centerX - c.height * 0.2f, hipY - c.height * 0.05f, c.centerX + c.height * 0.2f, c.bottom + c.height * 0.01f)
			val upper = Bounds(c.centerX - c.height * 0.25f, c.top, c.centerX + c.height * 0.25f, hipY + c.height * 0.1f)
			fun body(x: Float, y: Float) = mapOf("ParamBodyAngleX" to x, "ParamBodyAngleY" to y)
			val grid = listOf(10f, 0f, -10f).flatMap { y -> listOf(-10f, 0f, 10f).map { x -> x to y } }
			sheet(grid.map { (x, y) -> "X %+.0f Y %+.0f".format(x, y) to renderer.render(body(x, y)) }, File(out, "${sample.name}-stance-$tag.png"), 3)
			val near = listOf(0f to 0f, -10f to 0f, 10f to 0f, 0f to -10f, 0f to 10f, 10f to -10f)
			sheet(near.map { (x, y) -> "X %+.0f Y %+.0f".format(x, y) to renderer.render(body(x, y), legs) }, File(out, "${sample.name}-stance-legs-$tag.png"), 3)
			sheet(near.map { (x, y) -> "X %+.0f Y %+.0f".format(x, y) to renderer.render(body(x, y), upper) }, File(out, "${sample.name}-stance-upper-$tag.png"), 3)
			val keys = listOf(-10f, 0f, 10f)
			sheet(keys.map { "lean %+.0f".format(it) to renderer.render(mapOf("ParamBodyLean" to it)) } +
				keys.map { "lean %+.0f".format(it) to renderer.render(mapOf("ParamBodyLean" to it), upper) }, File(out, "${sample.name}-lean-$tag.png"), 3)
			sheet(keys.map { "proportion %+.0f".format(it) to renderer.render(mapOf("ParamProportion" to it)) } +
				keys.map { "lean +10 proportion %+.0f".format(it) to renderer.render(mapOf("ParamBodyLean" to 10f, "ParamProportion" to it)) },
				File(out, "${sample.name}-size-$tag.png"), 3)
			if (tag == "skeleton") {
				val poses = listOf("rest" to emptyMap(), "crouch" to mapOf("ParamSkelCrouch" to 1f), "kneesIn" to mapOf("ParamSkelKneesIn" to 1f),
					"weight +1" to mapOf("ParamSkelWeight" to 1f), "weight -1" to mapOf("ParamSkelWeight" to -1f), "hop" to mapOf("ParamSkelHop" to 1f))
				sheet(poses.map { (name, values) -> name to renderer.render(values) }, File(out, "${sample.name}-legposes.png"), 3)
			}
		}
	}

	/**
	 * The pointer runs a slow figure across the canvas, drawn as a red dot; the head follows it quickly and
	 * the body more slowly, with the gains and the rates the preview uses (PSD2LiveViewModel's live
	 * parameters: head 7.5/s, body 2.6/s, Body X/Y 8 at full reach).
	 */
	@Test fun tracking() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val renderer = Renderer(build(sample).skeletal, 560)
		val out = File(output("motion-frames"), "${sample.name}-track").apply { deleteRecursively(); mkdirs() }
		fun pointer(t: Double) = sin(2 * PI * t / 6.0).toFloat() to (sin(2 * PI * t / 4.0) * 0.8).toFloat()
		var headX = 0f; var headY = 0f; var bodyX = 0f; var bodyY = 0f
		for (i in 0 until (SECONDS * FPS).toInt()) {
			// Integrate the follow in small steps between frames.
			for (k in 0 until STEPS) {
				val (px, py) = pointer((i + k / STEPS.toFloat()) / FPS.toDouble())
				val dt = 1f / FPS / STEPS
				headX += (px - headX) * (dt * 7.5f).coerceAtMost(1f); headY += (py - headY) * (dt * 7.5f).coerceAtMost(1f)
				bodyX += (headX - bodyX) * (dt * 2.6f).coerceAtMost(1f); bodyY += (headY - bodyY) * (dt * 2.6f).coerceAtMost(1f)
			}
			val image = renderer.render(mapOf(
				"ParamAngleX" to headX * 38f, "ParamAngleY" to -headY * 24f,
				"ParamBodyAngleX" to (bodyX * 8f).coerceIn(-10f, 10f), "ParamBodyAngleY" to (-bodyY * 8f).coerceIn(-10f, 10f),
				"ParamEyeBallX" to headX.coerceIn(-1f, 1f), "ParamEyeBallY" to (-headY).coerceIn(-1f, 1f),
			))
			val (px, py) = pointer(i / FPS.toDouble())
			val g = image.createGraphics()
			g.color = Color.RED
			g.fillOval((image.width * (0.5f + 0.45f * px)).toInt() - 6, (image.height * (0.5f + 0.45f * py)).toInt() - 6, 12, 12)
			g.dispose()
			ImageIO.write(image, "png", File(out, "%03d.png".format(i)))
		}
		println("wrote $out")
	}

	@Test fun idle() {
		requireTools()
		val sample = Sample.fromEnvironment()
		val built = build(sample)
		val renderer = Renderer(built.skeletal, 640)
		val idle = SkeletonMotions.idle(built.spec)
		val out = File(output("motion-frames"), "${sample.name}-idle").apply { deleteRecursively(); mkdirs() }
		for (i in 0 until (SECONDS * FPS).toInt()) {
			val t = i / FPS.toDouble()
			ImageIO.write(renderer.render(idle.associate { it.parameterId to SkeletonMotions.sample(it, t, loop = true) }), "png", File(out, "%03d.png".format(i)))
		}
		println("wrote $out")
	}

	/** Where [a] and [b] differ, brighter the more. */
	private fun difference(a: BufferedImage, b: BufferedImage): BufferedImage {
		val diff = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_RGB)
		for (y in 0 until a.height) for (x in 0 until a.width) {
			val p = a.getRGB(x, y)
			val q = b.getRGB(x, y)
			val d = (0..2).sumOf { abs(((p shr (it * 8)) and 255) - ((q shr (it * 8)) and 255)) }
			val v = (d * 3).coerceAtMost(255)
			diff.setRGB(x, y, Color(v, v, v).rgb)
		}
		return diff
	}

	private companion object {
		const val SECONDS = 12f
		const val FPS = 12.5f
		const val STEPS = 8
	}
}
