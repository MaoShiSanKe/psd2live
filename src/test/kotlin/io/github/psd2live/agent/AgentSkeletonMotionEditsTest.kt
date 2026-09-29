package io.github.psd2live.agent

import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSpec
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentSkeletonMotionEditsTest {
    private val skeleton = SkeletonSpec(bones = listOf(
        SkeletonBone("body", "Body", null, BoneRole.LOWER_BODY, headX = 10f, headY = 0f, tailX = 10f, tailY = 50f),
        SkeletonBone("arm", "Arm", "body", BoneRole.UPPER_ARM, headX = 10f, headY = 50f, tailX = 35f, tailY = 65f),
    ))

    @Test fun jointMoveAndBindingPreserveSkeletonSemantics() {
        val moved = AgentSkeletonMotionEdits.skeleton(skeleton, buildJsonObject {
            put("mode", "move"); put("bone_id", "body"); put("end", "tail")
            put("point", buildJsonArray { add(20); add(55) })
        }) { error("unused") }
        assertEquals(20f, moved.bone("body")!!.tailX)
        assertEquals(20f, moved.bone("arm")!!.headX)
        assertEquals(55f, moved.bone("arm")!!.headY)

        val bound = AgentSkeletonMotionEdits.skeleton(moved, buildJsonObject {
            put("mode", "bind"); put("drawable_id", "mesh-a"); put("bone_id", "arm")
        }) { error("unused") }
        assertEquals(listOf("mesh-a"), bound.bone("arm")!!.drawableIds)
        val unbound = AgentSkeletonMotionEdits.skeleton(bound, buildJsonObject {
            put("mode", "bind"); put("drawable_id", "mesh-a")
        }) { error("unused") }
        assertTrue(unbound.bone("arm")!!.drawableIds.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            AgentSkeletonMotionEdits.skeleton(skeleton, buildJsonObject {
                put("mode", "remove"); put("bone_id", "body")
            }) { error("unused") }
        }
    }

    @Test fun motionKeysRoundTripAndRejectInvalidParameter() {
        val ranges = mapOf("ParamArmLA" to (-90f..150f))
        val put = AgentSkeletonMotionEdits.motion(emptyList(), buildJsonObject {
            put("mode", "put")
            put("clip", buildJsonObject {
                put("id", "wave"); put("name", "Wave custom"); put("duration", 2)
            })
        }, ranges, skeleton)
        val keyed = AgentSkeletonMotionEdits.motion(put, buildJsonObject {
            put("mode", "set_key"); put("id", "wave"); put("parameter", "ParamArmLA")
            put("key", buildJsonObject { put("time", 1); put("value", 45); put("interpolation", "BEZIER") })
        }, ranges, skeleton)
        val clip = MotionClips.fromJson(MotionClips.toJson(keyed.single()))
        assertEquals(45f, clip.curve("ParamArmLA")!!.keys.single().value)
        assertEquals("BEZIER", clip.curve("ParamArmLA")!!.keys.single().interpolation.name)
        val removed = AgentSkeletonMotionEdits.motion(keyed, buildJsonObject {
            put("mode", "delete_key"); put("id", "wave"); put("parameter", "ParamArmLA"); put("time", 1)
        }, ranges, skeleton)
        assertTrue(removed.single().curves.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            AgentSkeletonMotionEdits.motion(put, buildJsonObject {
                put("mode", "set_key"); put("id", "wave"); put("parameter", "missing")
                put("key", buildJsonObject { put("time", 1); put("value", 1) })
            }, ranges, skeleton)
        }
    }
}
