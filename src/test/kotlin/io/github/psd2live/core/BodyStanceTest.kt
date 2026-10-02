package io.github.psd2live.core

import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyStanceTest {
	private val character = Bounds(0f, 0f, 400f, 1000f)

	/** A figure 1000 px tall: shoulders at 250, waist at 450, legs from hip joints at 480 to ankles at 920 on a floor at 960. */
	private fun spec(skinned: Boolean = false): SkeletonSpec {
		fun bone(id: String, parent: String?, role: BoneRole, side: Side, hx: Float, hy: Float, tx: Float, ty: Float, meshes: List<String> = emptyList()) =
			SkeletonBone(id, id, parent, role, side, hx, hy, tx, ty, meshes)
		fun leg(s: String, side: Side, x: Float) = listOf(
			bone("thigh_$s", "hips", BoneRole.THIGH, side, x, 480f, x, 700f, if (skinned) listOf("leg_$s") else emptyList()),
			bone("shin_$s", "thigh_$s", BoneRole.SHIN, side, x, 700f, x, 920f),
			bone("foot_$s", "shin_$s", BoneRole.FOOT, side, x, 920f, x, 960f),
		)
		return SkeletonSpec(bones = listOf(
			bone("chest", null, BoneRole.UPPER_BODY, Side.NONE, 200f, 450f, 200f, 250f),
			bone("hips", null, BoneRole.LOWER_BODY, Side.NONE, 200f, 450f, 200f, 520f),
		) + leg("l", Side.LEFT, 240f) + leg("r", Side.RIGHT, 160f))
	}

	private val stance = BodyStance.of(spec(), character)

	@Test fun theFeetStayOnTheFloorWhateverTheBodyDoes() {
		assertTrue(stance.standing)
		for (x in listOf(-10f, 0f, 10f)) for (y in listOf(-10f, 0f, 10f)) {
			val solved = stance.Solved(stance.bodyPose(x, y))
			for (px in listOf(120f, 160f, 200f, 240f, 280f)) for (py in listOf(945f, 960f, 975f)) {
				val p = solved.legPoint(px.toDouble(), py.toDouble())
				assertEquals(px.toDouble(), p[0], 0.05, "foot x at $px,$py, body $x/$y")
				assertEquals(py.toDouble(), p[1], 0.05, "foot y at $px,$py, body $x/$y")
			}
		}
	}

	@Test fun bodyXMovesTheWholeBodyALittleWithTheHeadInTheMiddle() {
		val hips = stance.Solved(stance.bodyPose(10f, 0f)).pelvis
		val hip = hips.apply(200.0, 480.0)
		// The whole body moves a little toward +x, level.
		assertTrue(hip[0] - 200.0 in 5.0..15.0, "hips shift: ${hip[0]}")
		assertEquals(0.0, hips.degrees)
		for (leg in stance.legs) {
			val at = hips.apply(leg.hipX, leg.hipY)
			assertTrue(hypot(leg.ankleX - at[0], leg.ankleY - at[1]) <= leg.reach + 1e-6)
		}
		// The front of the chest turns toward +x, while the neck, near the axis, stays over the hips:
		// the head moves with the body, not past it.
		val chest = stance.torsoPoint(200.0, 330.0, 10f, 0f)
		val neck = stance.torsoPoint(200.0, 200.0, 10f, 0f)
		assertTrue(chest[0] - 200.0 > 8.0, "chest front: ${chest[0]}")
		assertTrue(abs(neck[0] - 200.0) < (chest[0] - 200.0) * 0.4, "neck: ${neck[0]}, chest ${chest[0]}")
		val head = stance.bodyPoint(200.0, 150.0, 10f, 0f)
		assertTrue(head[0] - 200.0 < (hip[0] - 200.0) * 1.6, "head ${head[0]} with hips ${hip[0]}")
		// The shoulder coming forward (the far side from the turn) is drawn larger and lower.
		val radius = stance.torso.halfWidth * 1.1
		val near = stance.torsoPoint(200.0 - radius, 260.0, 10f, 0f)
		val far = stance.torsoPoint(200.0 + radius, 260.0, 10f, 0f)
		assertTrue(near[1] > far[1], "near shoulder ${near[1]} far ${far[1]}")
		assertTrue(200.0 - near[0] > far[0] - 200.0, "near side wider")
		// The upper body turns as one: the waist as far as the chest, so the torso does not wring.
		val waist = stance.torsoPoint(200.0, 440.0, 10f, 0f)
		assertEquals(chest[0] - 200.0, waist[0] - 200.0, (chest[0] - 200.0) * 0.15, "waist ${waist[0]}, chest ${chest[0]}")
		// The turn eases out over the hips; below them everything only moves with the hips.
		for (x in listOf(120.0, 200.0, 280.0)) for (y in listOf(560.0, 700.0)) {
			val p = stance.torsoPoint(x, y, 10f, 0f)
			assertEquals(x, p[0], 1e-9)
			assertEquals(y, p[1], 1e-9)
		}
		// And all of it mirrors.
		val mirrored = stance.bodyPoint(200.0 + radius * 0.5, 300.0, -10f, 0f)
		val original = stance.bodyPoint(200.0 - radius * 0.5, 300.0, 10f, 0f)
		assertEquals(400.0 - original[0], mirrored[0], 1e-6)
		assertEquals(original[1], mirrored[1], 1e-6)
	}

	@Test fun bodyYSinksOntoKneesTurnedInAndStandsUpTaller() {
		val sinking = stance.Solved(stance.bodyPose(0f, -10f))
		val hip = sinking.pelvis.apply(160.0, 480.0)
		assertTrue(hip[1] > 480.0 + 10.0, "hips sink: ${hip[1]}")
		// The whole body comes down with them.
		assertTrue(stance.bodyPoint(200.0, 150.0, 0f, -10f)[1] > 150.0 + 10.0)
		// The knees come in toward the middle, and the thigh bending toward the viewer is drawn shorter
		// rather than narrower.
		val leg = stance.legs.first { it.hipX < 200.0 }
		val knee = stance.knee(leg, hip[0], hip[1], leg.ankleX, leg.ankleY, stance.bodyPose(0f, -10f).kneeIn)
		assertTrue(knee[0] > leg.kneeX + 3.0, "knee in: ${knee[0]}")
		val outer = sinking.legPoint(leg.hipX - 15.0, 590.0)
		val inner = sinking.legPoint(leg.hipX + 15.0, 590.0)
		assertEquals(30.0, hypot(inner[0] - outer[0], inner[1] - outer[1]), 1.0)
		// Going down the shoulders open a little; going up they draw in.
		assertTrue(stance.torsoPoint(120.0, 260.0, 0f, -10f)[0] < 120.0)
		assertTrue(stance.torsoPoint(120.0, 260.0, 0f, 10f)[0] > 120.0)

		// Standing up the legs straighten and the hips rise a little, the feet where they were.
		val rising = stance.Solved(stance.bodyPose(0f, 10f))
		val raised = rising.pelvis.apply(160.0, 480.0)
		assertTrue(raised[1] < 480.0 - 4.0, "hips rise: ${raised[1]}")
		assertEquals(920.0, rising.legPoint(160.0, 920.0)[1], 0.05)
	}

	@Test fun theLeanBowsTheBodyInThreeDimensions() {
		// Leaning in, the shoulders come lower and wider and the head larger; leaning back, smaller.
		val shoulder = stance.leanPoint(120.0, 260.0, 10f)
		assertTrue(shoulder[1] > 260.0 + 5.0, "shoulders come down: ${shoulder[1]}")
		assertTrue(shoulder[0] < 120.0 - 3.0, "shoulders widen: ${shoulder[0]}")
		assertTrue(stance.leanScale(150.0, 10f) > 1.04)
		val back = stance.leanPoint(120.0, 260.0, -10f)
		assertTrue(abs(back[1] - 260.0) < 4.0, "leaning back the shoulders stay about where they are: ${back[1]}")
		assertTrue(stance.leanScale(150.0, -10f) < 0.98)
		// The skirt below the hips goes back with the pelvis rather than staying folded flat.
		assertTrue(stance.leanScale(620.0, 10f) < 0.995, "the hem goes back: ${stance.leanScale(620.0, 10f)}")
		// The chest foreshortens; the head rides the neck whole, as tall as it is wide.
		assertTrue(stance.leanPoint(200.0, 250.0, 10f)[1] - stance.leanPoint(200.0, 450.0, 10f)[1] > -200.0 * 0.95, "the chest foreshortens")
		val top = stance.leanPoint(200.0, 50.0, 10f)
		val chin = stance.leanPoint(200.0, 200.0, 10f)
		assertEquals(stance.leanScale(120.0, 10f), (chin[1] - top[1]) / 150.0, 1e-6)
		// An arm moves with its shoulder as one piece: its points keep their offsets, all scaled alike.
		val shoulderX = (stance.torso.centerX - stance.torso.halfWidth).toDouble()
		val elbow = stance.armPoint(100.0, 380.0, shoulderX, 10f)
		val hand = stance.armPoint(90.0, 520.0, shoulderX, 10f)
		val k = stance.armPoint(shoulderX + 1.0, stance.torso.shoulderY.toDouble(), shoulderX, 10f)[0] -
			stance.armPoint(shoulderX, stance.torso.shoulderY.toDouble(), shoulderX, 10f)[0]
		assertTrue(k > 1.02, "the arm comes closer: $k")
		assertEquals(-10.0 * k, hand[0] - elbow[0], 1e-6)
		assertEquals(140.0 * k, hand[1] - elbow[1], 1e-6)
		assertEquals(1.0, stance.leanScale(150.0, 0f))
		assertEquals(120.0, stance.leanPoint(120.0, 260.0, 0f)[0])
	}

	@Test fun theLeanCurvesTheWaistRatherThanFoldingIt() {
		// Every stretch of the centre line from the waist up foreshortens about alike: none is squeezed more
		// than the bow itself would.
		var previous = stance.leanPoint(200.0, 460.0, 10f)[1]
		val rates = (440 downTo 260 step 20).map { y ->
			val p = stance.leanPoint(200.0, y.toDouble(), 10f)[1]
			((previous - p) / 20.0).also { previous = p }
		}
		assertTrue(rates.min() > 0.78 && rates.max() < 0.95, rates.toString())
		assertTrue(rates.max() - rates.min() < 0.1, rates.toString())
	}

	@Test fun theFaceBowsWithTheLean() {
		// A head warp on Angle X and Angle Y takes the lean as an axis: leaning in, its lattice is the one it
		// has with the face turned down a little, read between its keys; at no lean it is its own.
		val axes = listOf(KeyformAxis(StandardParameters.ANGLE_X, floatArrayOf(-45f, 0f, 45f)), KeyformAxis(StandardParameters.ANGLE_Y, floatArrayOf(-30f, 0f, 30f)))
		val cells = (0..2).flatMap { x -> (0..2).map { y -> KeyformCell(intArrayOf(x, y), WarpLatticeForm(floatArrayOf(x.toFloat(), y * 30f))) } }
		val warp = Deformer.Warp(DeformerId("DeformFace"), "face", null, null, 0, 0, true, KeyformGrid(axes, cells))
		val leaned = RigBuilder.withFaceLean(listOf(warp)).single() as Deformer.Warp
		val grid = leaned.geometryGrid!!
		assertEquals(StandardParameters.BODY_LEAN, grid.axes.last().parameterId)
		fun at(x: Int, y: Int, lean: Int) = grid.cells.single { it.coordinate.contentEquals(intArrayOf(x, y, lean)) }.form.controlPoints[1]
		assertEquals(30f, at(1, 1, 1))
		assertTrue(at(1, 1, 2) < 30f - 5f, "leaning in the face turns down: ${at(1, 1, 2)}")
		assertTrue(at(1, 1, 0) > 30f + 3f, "leaning back it turns up: ${at(1, 1, 0)}")
		assertEquals(0f, at(1, 0, 2))
	}

	@Test fun theProportionsDrawAChibiOrATallerFigure() {
		// Toward a chibi the head grows from the neck as one piece, the chest widens and the torso, the arms
		// and the legs shorten, the body coming down onto them; the feet stay.
		val head = stance.leanScale(120.0, 0f, 10f)
		assertTrue(head > 1.1, "the head grows: $head")
		val top = stance.leanPoint(200.0, 50.0, 0f, 10f)
		val chin = stance.leanPoint(200.0, 200.0, 0f, 10f)
		assertEquals(head, (chin[1] - top[1]) / 150.0, 1e-6)
		val shoulder = stance.leanPoint(120.0, 250.0, 0f, 10f)
		val waist = stance.leanPoint(120.0, 450.0, 0f, 10f)
		assertTrue(shoulder[0] < 120.0 - 4.0, "the chest widens: ${shoulder[0]}")
		assertTrue(waist[1] - shoulder[1] < 200.0 * 0.96, "the torso shortens: ${waist[1] - shoulder[1]}")
		assertTrue(waist[1] > 450.0 + 30.0, "the body comes down: ${waist[1]}")
		val hem = stance.leanPoint(200.0, 620.0, 0f, 10f)[1]
		assertEquals(920.0 - 300.0 * 0.9, hem, 1e-6)
		assertEquals(940.0, stance.legsAt(doubleArrayOf(160.0, 940.0), 10f)[1])
		assertEquals(hem, stance.legsAt(doubleArrayOf(160.0, 620.0), 10f)[1], 1e-9)
		assertTrue(stance.limbScale(300.0, 0f, 10f) < stance.leanScale(300.0, 0f, 10f) * 0.95, "the arms shorten")
		// Toward a taller figure, the other way.
		assertTrue(stance.leanScale(120.0, 0f, -10f) < 0.9)
		assertTrue(stance.leanPoint(120.0, 450.0, 0f, -10f)[1] < 450.0 - 30.0)
		assertTrue(stance.leanPoint(120.0, 250.0, 0f, -10f)[0] > 120.0 + 4.0)
	}

	@Test fun aFigureWithoutLegsTurnsAboveTheWaistOnly() {
		val noLegs = BodyStance.of(SkeletonSpec(bones = spec().bones.filter { it.role.body }), character)
		assertTrue(!noLegs.standing)
		assertTrue(noLegs.bodyPoint(200.0, 330.0, 10f, 0f)[0] > 205.0)
		for (x in listOf(50.0, 200.0, 350.0)) for (bx in listOf(-10f, 10f)) for (by in listOf(-10f, 10f)) {
			val p = noLegs.bodyPoint(x, 1000.0, bx, by)
			assertEquals(x, p[0], 1e-9)
			assertEquals(1000.0, p[1], 1e-9)
		}
		// Without legs the body warps are the body at the root and the lean under it.
		val frame = Bounds(80f, 150f, 320f, 700f)
		val warps = RigBuilder.bodyWarps(noLegs, frame, null)
		assertEquals(listOf("DeformBodyXY", "DeformBodyLean"), warps.map { it.id.raw })
		assertNull(warps[0].parent)
		assertEquals("DeformBodyXY", warps[1].parent?.raw)
	}

	@Test fun aSampleStandsOnItsFeet() {
		val preview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val puppet = preview.rig.puppet
		val parent = puppet.deformers.associate { it.id.raw to it.parent?.raw }
		assertNull(parent.getValue("DeformBodyXY"))
		assertNull(parent.getValue("DeformLegs"))
		assertEquals("DeformBodyXY", parent["DeformBodyLean"])
		assertEquals("DeformBodyLean", parent["DeformBodyZBreath"])
		assertTrue("DeformPelvis" !in parent)
		// The body warp spans the body, not the whole figure: neither the top of the head nor the feet.
		val body = puppet.deformers.single { it.id.raw == "DeformBodyXY" } as Deformer.Warp
		val points = body.geometryGrid!!.cells.first { it.coordinate.all { c -> c == 1 } }.form.controlPoints
		val character = preview.analysis.anchors.character
		val top = points.filterIndexed { i, _ -> i % 2 == 1 }.min()
		val bottom = points.filterIndexed { i, _ -> i % 2 == 1 }.max()
		assertTrue(top > character.top + character.height * 0.1f, "body warp top $top")
		assertTrue(bottom < character.bottom - character.height * 0.1f, "body warp bottom $bottom")

		val legLayers = preview.analysis.layers.filter { it.semantic.tag == SemanticTag.LEGWEAR || it.semantic.tag == SemanticTag.FOOTWEAR }
			.mapTo(HashSet()) { it.source.id.raw }
		val legMeshes = preview.rig.layerIdByDrawableId.filterValues { it in legLayers }.keys.map(::DrawableId)
		assertTrue(legMeshes.isNotEmpty())
		for (id in legMeshes) assertEquals("DeformLegs", ancestry(puppet, id).last())
		assertSoles(puppet, legMeshes, mapOf())
		// Each arm hangs in a warp of its own on Body X and the lean, under the body's.
		val armLayers = preview.analysis.layers.filter { it.semantic.tag == SemanticTag.HANDWEAR }.associate { it.source.id.raw to it.semantic.side }
		val armMeshes = preview.rig.layerIdByDrawableId.filterValues { it in armLayers }
		assertEquals(2, armMeshes.size)
		for ((id, layer) in armMeshes) {
			val chain = ancestry(puppet, DrawableId(id))
			assertEquals("DeformArmHang_" + (if (armLayers[layer] == Side.LEFT) "L" else "R"), chain.first())
			assertTrue("DeformBodyLean" in chain, chain.toString())
			val hang = puppet.deformers.single { it.id.raw == chain.first() } as Deformer.Warp
			assertEquals(listOf("ParamBodyAngleX", "ParamBodyLean", "ParamProportion"), hang.geometryGrid!!.axes.map { it.parameterId.raw })
		}
		assertSoles(puppet, legMeshes, mapOf(StandardParameters.PROPORTION to 10f, StandardParameters.BODY_LEAN to 10f))
		assertTrue(RigIntegrityValidator.validateDirectionalWarpDimensions("tml", puppet).isEmpty(), RigIntegrityValidator.validateDirectionalWarpDimensions("tml", puppet).joinToString("\n"))

		// With its skeleton, the leg poses stand on the same feet.
		val spec = SkeletonAutoBuilder.build(preview.analysis, preview.rig)
		assertTrue(SkeletonPoses.crouch in SkeletonPoses.available(spec))
		val skeletal = PSD2LivePipeline().buildPreview(preview.analysis, preview.config.copy(rigEdits = preview.config.rigEdits.copy(skeleton = spec)))
		val rig = skeletal.rig.puppet
		val skeletalBody = rig.deformers.single { it.id.raw == "DeformBodyXY" } as Deformer.Warp
		assertEquals(SkeletonPoses.legPoses.map { it.id }.toSet(), skeletalBody.blendShapes.map { it.parameterId }.toSet())
		val skeletalLegs = skeletal.rig.layerIdByDrawableId.filterValues { it in legLayers }.keys.map(::DrawableId)
		for (pose in listOf(SkeletonPoses.crouch, SkeletonPoses.kneesIn, SkeletonPoses.weight)) {
			assertSoles(rig, skeletalLegs, mapOf(pose.id to pose.max))
		}
		assertTrue(RigIntegrityValidator.validateDirectionalWarpDimensions("tml", rig).isEmpty(),
			RigIntegrityValidator.validateDirectionalWarpDimensions("tml", rig).joinToString("\n"))
	}

	@Test fun legsSkinnedToBonesFollowTheBodyOnTheirFeet() {
		val preview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val legLayers = preview.analysis.layers.filter { it.semantic.tag == SemanticTag.LEGWEAR || it.semantic.tag == SemanticTag.FOOTWEAR }
			.mapTo(HashSet()) { it.source.id.raw }
		val legMeshes = preview.rig.layerIdByDrawableId.filterValues { it in legLayers }.keys.toList()
		// The legs bound to the bones of one leg, as a user binds them: the rotations carry them, not the legs warp.
		val auto = SkeletonAutoBuilder.build(preview.analysis, preview.rig)
		val thigh = auto.bones.first { it.role == BoneRole.THIGH }
		val spec = auto.copy(bones = auto.bones.map { if (it.id == thigh.id) it.copy(drawableIds = legMeshes) else it })
		val rig = PSD2LivePipeline().buildPreview(preview.analysis, preview.config.copy(rigEdits = preview.config.rigEdits.copy(skeleton = spec))).rig.puppet
		val meshes = legMeshes.map(::DrawableId)
		for (id in meshes) {
			val chain = ancestry(rig, id)
			assertTrue(chain.first().endsWith("Stance") && "DeformLegs" in chain, "$id hangs from $chain")
		}
		assertSoles(rig, meshes, mapOf())

		// The hips go with the body: the top of the legs sinks on Body Y down, rises on up and moves aside on Body X.
		fun canvas(values: Map<ParameterId, Float>) = CpuDeformationEvaluator().evaluate(rig, values).worldPositions
		val rest = canvas(emptyMap())
		val top = meshes.maxOf { id -> rest.getValue(id).filterIndexed { i, _ -> i % 2 == 1 }.max() }
		fun hips(values: Map<ParameterId, Float>): DoubleArray {
			val posed = canvas(values)
			var dx = 0.0; var dy = 0.0; var n = 0
			for (id in meshes) {
				val a = rest.getValue(id)
				val b = posed.getValue(id)
				for (v in 0 until a.size / 2) if (a[v * 2 + 1] > top - 15f) { dx += b[v * 2] - a[v * 2]; dy += b[v * 2 + 1] - a[v * 2 + 1]; n++ }
			}
			return doubleArrayOf(dx / n, dy / n)
		}
		val stance = BodyStance.of(preview.analysis, preview.analysis.anchors.character, spec)
		val down = hips(mapOf(StandardParameters.BODY_Y to -10f))
		val up = hips(mapOf(StandardParameters.BODY_Y to 10f))
		val aside = hips(mapOf(StandardParameters.BODY_X to 10f))
		// World y points up.
		assertTrue(down[1] < -stance.legLength * stance.hipSink * 0.5, "down ${down.toList()}")
		assertTrue(up[1] > stance.legLength * stance.hipRise * 0.3, "up ${up.toList()}")
		assertTrue(aside[0] > stance.legLength * stance.hipShift * 0.5, "aside ${aside.toList()}")
		assertTrue(RigIntegrityValidator.validateDirectionalWarpDimensions("tml", rig).isEmpty(),
			RigIntegrityValidator.validateDirectionalWarpDimensions("tml", rig).joinToString("\n"))
	}

	@Test fun bodyXSwingsEachArmWholeAboutItsShoulder() {
		val preview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val armLayers = preview.analysis.layers.filter { it.semantic.tag == SemanticTag.HANDWEAR }.mapTo(HashSet()) { it.source.id.raw }
		val spec = SkeletonAutoBuilder.build(preview.analysis, preview.rig)
		val skeletal = PSD2LivePipeline().buildPreview(preview.analysis, preview.config.copy(rigEdits = preview.config.rigEdits.copy(skeleton = spec)))
		val height = preview.analysis.anchors.character.height
		for (built in listOf(preview, skeletal)) {
			val puppet = built.rig.puppet
			val arms = built.rig.layerIdByDrawableId.filterValues { it in armLayers }.keys.map(::DrawableId)
			assertEquals(2, arms.size)
			val rest = CpuDeformationEvaluator().evaluate(puppet, emptyMap()).worldPositions
			for (x in listOf(-10f, 10f)) {
				val posed = CpuDeformationEvaluator().evaluate(puppet, mapOf(StandardParameters.BODY_X to x)).worldPositions
				for (id in arms) {
					val a = rest.getValue(id)
					val b = posed.getValue(id)
					// The arm moves as one piece: every pair of its vertices keeps its distance, and turns by the swing.
					val n = a.size / 2
					val i = (0 until n).maxBy { a[it * 2 + 1] }
					val j = (0 until n).minBy { a[it * 2 + 1] }
					val before = hypot(a[j * 2] - a[i * 2], a[j * 2 + 1] - a[i * 2 + 1])
					val after = hypot(b[j * 2] - b[i * 2], b[j * 2 + 1] - b[i * 2 + 1])
					assertEquals(before, after, before * 0.005f, "$id length at Body X $x")
					val turn = Math.toDegrees(atan2((b[j * 2 + 1] - b[i * 2 + 1]).toDouble(), (b[j * 2] - b[i * 2]).toDouble()) -
						atan2((a[j * 2 + 1] - a[i * 2 + 1]).toDouble(), (a[j * 2] - a[i * 2]).toDouble()))
					// World y points up: at Body X + the hand swings back, clockwise on screen.
					assertEquals(-4.0 * x / 10, turn, 0.3, "$id turn at Body X $x")
					for (v in 0 until n) assertTrue(hypot(b[v * 2] - a[v * 2], b[v * 2 + 1] - a[v * 2 + 1]) < height * 0.03f, "$id vertex $v at Body X $x")
				}
			}
		}
	}

	@Test fun theBodyMotionValuesSetHowFarTheBodyMoves() {
		val tuned = BodyStance.of(spec(), character, tuning = RigTuning(turnDegrees = 24f, armSwingDegrees = 0f, sink = 7f))
		assertEquals(4.0, stance.armSwing(10f), 1e-9)
		assertEquals(0.0, tuned.armSwing(10f), 1e-9)
		assertEquals(stance.hipSink * 2, tuned.hipSink, 1e-9)
		// Twice the turn carries the front of the chest further.
		val chest = { s: BodyStance -> s.torsoPoint(200.0, 330.0, 10f, 0f)[0] - 200.0 }
		assertTrue(chest(tuned) > chest(stance) * 1.5, "${chest(tuned)} vs ${chest(stance)}")
		val (_, lift) = RigBuilder.bodySecondaryWarpPoint(character, RigBuilder.TorsoFrame(200f, 250f, 450f, 70f), 0.5f, 0.25f, 0f, 1f, 1f,
			RigTuning(breathLift = 0f))
		assertEquals(0.25f, lift, 1e-6f)

		// Through the pipeline: with no turn, no hip shift and no arm swing, Body X moves nothing.
		val preview = PSD2LivePipeline().buildPreview(Path.of("examples/tml/psd-input/tml.psd"))
		val still = PSD2LivePipeline().buildPreview(preview.analysis,
			preview.config.copy(rigTuning = RigTuning(turnDegrees = 0f, hipShift = 0f, armSwingDegrees = 0f))).rig.puppet
		val rest = CpuDeformationEvaluator().evaluate(still, emptyMap()).worldPositions
		val turned = CpuDeformationEvaluator().evaluate(still, mapOf(StandardParameters.BODY_X to 10f)).worldPositions
		for ((id, a) in rest) {
			val b = turned.getValue(id)
			for (i in a.indices) assertEquals(a[i], b[i], 0.05f, "$id at Body X 10")
		}
	}

	private fun ancestry(puppet: PuppetModel, id: DrawableId): List<String> {
		val byId = puppet.deformers.associateBy { it.id }
		return generateSequence(puppet.drawables.single { it.id == id }.parentDeformerId) { byId[it]?.parent }.map { it.raw }.toList()
	}

	/** The lowest vertices of the leg meshes - the soles - stay put at every Body X and Body Y key, on top of [extra]. */
	private fun assertSoles(puppet: PuppetModel, meshes: List<DrawableId>, extra: Map<ParameterId, Float>) {
		fun canvas(values: Map<ParameterId, Float>) = CpuDeformationEvaluator().evaluate(puppet, values).worldPositions
		val rest = canvas(emptyMap())
		val floor = meshes.maxOf { id -> rest.getValue(id).filterIndexed { i, _ -> i % 2 == 1 }.maxOf { -it } }
		for (x in listOf(-10f, 0f, 10f)) for (y in listOf(-10f, 0f, 10f)) {
			val posed = canvas(extra + mapOf(StandardParameters.BODY_X to x, StandardParameters.BODY_Y to y))
			var checked = 0
			for (id in meshes) {
				val a = rest.getValue(id)
				val b = posed.getValue(id)
				for (v in 0 until a.size / 2) {
					if (-a[v * 2 + 1] < floor - 6f) continue
					checked++
					assertEquals(a[v * 2], b[v * 2], 1f, "sole x of $id at $x/$y $extra")
					assertEquals(a[v * 2 + 1], b[v * 2 + 1], 1f, "sole y of $id at $x/$y $extra")
				}
			}
			assertTrue(checked > 0)
		}
	}
}
