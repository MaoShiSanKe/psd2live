package io.github.psd2live.ui.state

import io.github.psd2live.core.CubismSdkFrame
import io.github.psd2live.core.Physics3Json
import io.github.psd2live.core.PhysicsAuthoring
import io.github.psd2live.core.PhysicsOutput
import io.github.psd2live.core.PhysicsSegment
import io.github.psd2live.core.RigEditOverlay
import io.github.psd2live.core.RigPhysicsEdit
import io.github.psd2live.core.StandardParameters
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one frame rate and the pose every panel reads from the preview. */
class LivePoseTest {
	@Test
	fun everyPreviewFramePublishesThePoseAndAPausedOneOnlyThePointersLook() {
		PSD2LiveViewModel().use { vm ->
			val id = vm.state.value.activeCanvas.id
			vm.setCanvasMode(id, CanvasMode.PREVIEW)
			val key = vm.canvasRenderKey(id)
			vm.sdkFrameFor(key)
			val hair = ParameterId("ParamHairFront")
			val angle = StandardParameters.ANGLE_X
			vm.setParameterValue(hair, 0.3f)
			fun frame(animated: Boolean, vararg values: Pair<ParameterId, Float>) =
				CubismSdkFrame(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), mapOf(*values), animationEnabled = animated, viewId = key)

			// Playing: each frame is the pose, not one in ten as the document publishes it.
			vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, id, CanvasMode.PREVIEW) { it.copy(animationEnabled = true) }
			vm.acceptSdkFrame(frame(true, hair to 0.1f), 1_000_000_000L)
			vm.acceptSdkFrame(frame(true, hair to 0.2f), 1_016_000_000L)
			assertEquals(0.2f, vm.livePose.value[hair])
			assertEquals(0.1f, vm.state.value.previewParameterValues[hair])

			// Paused, the pointer turns the head; the rest is the edit pose, whatever the frame says.
			vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, id, CanvasMode.PREVIEW) { it.copy(animationEnabled = false) }
			vm.updatePointer(0.5f, 0f, key)
			vm.acceptSdkFrame(frame(false, hair to 0.9f, angle to 15f))
			assertEquals(15f, vm.livePose.value[angle])
			assertEquals(0.3f, vm.livePose.value[hair])

			// With the pointer gone the preview holds the edit pose, and the panels show the document again.
			vm.clearPointer(key)
			vm.acceptSdkFrame(frame(false, angle to 0f))
			assertTrue(vm.livePose.value.isEmpty())
		}
	}

	@Test
	fun aPausedPreviewSwingsPhysicsFromTheParametersAndComesToRest() = kotlinx.coroutines.runBlocking {
		val temp = java.nio.file.Files.createTempDirectory("paused-physics")
		val png = temp.resolve("art.png")
		val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		for (y in 2..13) for (x in 2..13) image.setRGB(x, y, 0xffff3366.toInt())
		javax.imageio.ImageIO.write(image, "png", png.toFile())
		PSD2LiveViewModel().use { vm ->
			io.github.psd2live.agent.ViewModelAgentWorkspace(vm, temp.resolve("store")).use { workspace ->
				vm.attachAgentWorkspace(workspace)
				workspace.createArtwork(kotlinx.serialization.json.buildJsonObject {
					put("width", kotlinx.serialization.json.JsonPrimitive(16)); put("height", kotlinx.serialization.json.JsonPrimitive(16))
					put("layers", kotlinx.serialization.json.buildJsonArray {
						add(kotlinx.serialization.json.buildJsonObject {
							put("path", kotlinx.serialization.json.JsonPrimitive(png.toString()))
							put("name", kotlinx.serialization.json.JsonPrimitive("decoration"))
							put("role", kotlinx.serialization.json.JsonPrimitive("objects"))
						})
					})
				})
				val hair = ParameterId("ParamHairFront")
				vm.putPhysicsGroup(RigPhysicsEdit("Ribbon", "Ribbon",
					listOf(io.github.psd2live.core.PhysicsInput("ParamAngleX", 100f, io.github.psd2live.core.PhysicsSourceType.X)),
					listOf(PhysicsOutput(hair.raw, 1, 1f)), listOf(PhysicsSegment(8f, 0.9f, 0.9f, 1.2f))))
				val id = vm.state.value.activeCanvas.id
				vm.setCanvasMode(id, CanvasMode.PREVIEW)
				val key = vm.canvasRenderKey(id)
				vm.updateCanvasPresentation(vm.state.value.activeWorkspace.id, id, CanvasMode.PREVIEW) { it.copy(animationEnabled = false) }
				vm.setGeneratePhysics(true)
				fun frames(n: Int) = repeat(n) { vm.requestSdkFrame(8, 8, 1f, 0f, 0f, viewId = key); Thread.sleep(8) }

				frames(5)
				assertTrue(vm.pausedPhysicsSettled || kotlin.math.abs(vm.livePose.value[hair] ?: 0f) < 1e-3f)
				// Turning the head while paused swings the hair; the preview and panels see it.
				vm.setParameterValue(StandardParameters.ANGLE_X, 30f)
				frames(10)
				val swung = vm.livePose.value[hair] ?: 0f
				assertTrue(kotlin.math.abs(swung) > 1e-3f, "hair $swung")
				assertFalse(vm.pausedPhysicsSettled)
				frames(250)
				assertTrue(vm.pausedPhysicsSettled)

				// Physics off: the paused preview shows the edit pose alone.
				vm.setGeneratePhysics(false)
				// With the SDK up, frames come back on the UI thread a little later.
				val deadline = System.nanoTime() + 3_000_000_000L
				while (vm.livePose.value[hair] != null && System.nanoTime() < deadline) frames(1)
				assertNull(vm.livePose.value[hair])
			}
		}
	}

	@Test
	fun unlimitedIsAProjectRateThatDeclaresNoPhysicsFps() {
		val overlay = PhysicsAuthoring.setFps(RigEditOverlay(), RigEditOverlay.UNLIMITED_FPS)
		assertEquals(0, overlay.physicsFps)
		val group = RigPhysicsEdit("Tail", "Tail", emptyList(), listOf(PhysicsOutput("ParamTail", 1, 1f)), listOf(PhysicsSegment(5f, 0.9f, 0.9f, 1.2f)))
		val json = Physics3Json.write(listOf(group), overlay.physicsFps)!!
		assertFalse("\"Fps\"" in json)
		assertNull(Physics3Json.read(json).fps)
		assertEquals(90, PhysicsAuthoring.setFps(overlay, 90).physicsFps)
		assertTrue(RigEditOverlay.FPS_CHOICES.all(RigEditOverlay::validFps))
	}

	@Test
	fun thePacerKeepsTheRateOnAnyDisplay() {
		fun rendered(fps: Int, hz: Double): Int {
			val pacer = FramePacer(frameIntervalNanos(fps))
			val frame = 1e9 / hz
			// A little jitter on each vsync stamp, as a real display has.
			return (1..(hz * 2).toInt()).count { pacer.due((it * frame + (if (it % 2 == 0) 300_000 else -300_000)).toLong()) }
		}
		assertEquals(120, rendered(60, 60.0))
		assertEquals(60, rendered(30, 60.0), 1)
		assertEquals(240, rendered(120, 144.0), 2)
		assertEquals(180, rendered(90, 144.0), 2)
		assertEquals(288, rendered(0, 144.0))
		// A rate above the display's gets every frame.
		assertEquals(120, rendered(120, 60.0))
	}

	private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
		assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, got $actual")
}
