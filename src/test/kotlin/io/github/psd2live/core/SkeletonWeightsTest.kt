package io.github.psd2live.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkeletonWeightsTest {
	private val bones = listOf(
		SkinBone(-1, 0.0, -100.0, 0.0, 0.0, 0.0),
		SkinBone(0, 0.0, 0.0, 0.0, 100.0, 20.0),
	)

	@Test fun lateralBranchFollowsItsConnectedChildRegion() {
		// The branch lies just behind the joint plane, like a thumb beside a wrist. In canvas
		// distance alone it receives mostly parent weight, but the mesh joins it to the child.
		val points = floatArrayOf(
			0f, -40f, 0f, -20f, 0f, 0f, 0f, 20f, 0f, 40f,
			50f, -5f,
		)
		val triangles = intArrayOf(0, 1, 2, 1, 2, 3, 2, 3, 4, 3, 4, 5)
		val planar = SkeletonWeights.skin(points, bones, intArrayOf())
		val diffused = SkeletonWeights.skin(points, bones, triangles)
		assertTrue(diffused[5].weight > planar[5].weight + 0.15f,
			"the lateral branch should receive weight through its connected child surface")
		assertEquals(planar[2].weight, diffused[2].weight, 1e-6f,
			"the centre of the joint keeps its axial blend")
		assertTrue(diffused[0].rigid)
		assertTrue(diffused[4].rigid)
	}

	@Test fun disconnectedIslandCannotReceiveWeightsFromAnotherMeshComponent() {
		val points = floatArrayOf(
			0f, -40f, 0f, -20f, 0f, 0f, 0f, 20f, 0f, 40f,
			50f, -5f, 55f, -4f, 60f, -6f,
		)
		val triangles = intArrayOf(0, 1, 2, 1, 2, 3, 2, 3, 4, 5, 6, 7)
		val planar = SkeletonWeights.skin(points, bones, intArrayOf())
		val diffused = SkeletonWeights.skin(points, bones, triangles)
		val otherMesh = SkeletonWeights.skin(points.copyOfRange(0, 10), bones, triangles.copyOfRange(0, 9))
		for (vertex in 0..4) {
			assertEquals(otherMesh[vertex].from, diffused[vertex].from)
			assertEquals(otherMesh[vertex].to, diffused[vertex].to)
			assertEquals(otherMesh[vertex].weight, diffused[vertex].weight, 1e-6f,
				"a disconnected mesh component must not alter another component")
		}
		for (vertex in 5..7) {
			assertEquals(planar[vertex].from, diffused[vertex].from)
			assertEquals(planar[vertex].to, diffused[vertex].to)
			assertEquals(planar[vertex].weight, diffused[vertex].weight, 1e-6f)
		}
	}
}
