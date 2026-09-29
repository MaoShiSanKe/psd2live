package io.github.psd2live.agent

import io.github.psd2live.core.BakeShape
import io.github.psd2live.core.BakeWrite
import io.github.psd2live.core.BoneRole
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.PoseEase
import io.github.psd2live.core.PosePreset
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

    private fun bakeRequest(block: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        putJsonArray("keys") {
            add(buildJsonObject { put("time", 0); put("values", buildJsonObject { put("ParamArmLA", 0) }); put("ease", "LINEAR") })
            add(buildJsonObject {
                put("time", 1)
                put("ik", buildJsonArray { add(buildJsonObject { put("bone_id", "arm"); put("target", buildJsonArray { add(30); add(70) }) }) })
            })
        }
        block()
    }

    @Test fun bakeRequestParsesKeysOptionsAndDefaults() {
        val parsed = AgentSkeletonBake.parse(bakeRequest {
            put("fps", 60); put("tolerance", 0.25); put("curve", "bezier"); put("write", "merge")
            putJsonArray("parameters") { add("ParamArmLA") }; put("start", 0.5)
        })
        assertEquals(listOf(0f, 1f), parsed.keys.map { it.time })
        assertEquals(PoseEase.LINEAR, parsed.keys[0].ease)
        assertEquals(PoseEase.SMOOTH, parsed.keys[1].ease)
        assertEquals("arm", parsed.keys[1].ik.single().boneId)
        assertEquals(60f, parsed.options.fps)
        assertEquals(0.25f, parsed.options.tolerance)
        assertEquals(BakeShape.BEZIER, parsed.options.shape)
        assertEquals(setOf("ParamArmLA"), parsed.options.parameterIds)
        assertEquals(0.5f, parsed.options.start)
        assertEquals(BakeWrite.MERGE, parsed.write)
        val plain = AgentSkeletonBake.parse(bakeRequest {}, defaultFps = 24f)
        assertEquals(24f, plain.options.fps)
        assertEquals(0.5f, plain.options.tolerance)
        assertEquals(BakeShape.LINEAR, plain.options.shape)
        assertEquals(BakeWrite.REPLACE, plain.write)
        assertFailsWith<IllegalArgumentException> { AgentSkeletonBake.parse(buildJsonObject { putJsonArray("keys") {} }) }
    }

    @Test fun poseSnapshotsPutReplaceAndDeleteWithinTheParameterRanges() {
        val ranges = mapOf("ParamArmLA" to (-90f..150f))
        fun put(id: String, name: String?, value: Float) = buildJsonObject {
            put("mode", "pose_put")
            put("pose", buildJsonObject {
                put("id", id); if (name != null) put("name", name)
                put("values", buildJsonObject { put("ParamArmLA", value) })
            })
        }
        val one = AgentSkeletonBake.poses(emptyList(), put("wave", "Wave", 40f), ranges)
        assertEquals(40f, one.single().values.getValue("ParamArmLA"))
        // Same ID replaces it and keeps the name when none is given.
        val replaced = AgentSkeletonBake.poses(one, put("wave", null, 55f), ranges)
        assertEquals(listOf("Wave"), replaced.map { it.name })
        assertEquals(55f, replaced.single().values.getValue("ParamArmLA"))
        assertFailsWith<IllegalArgumentException> { AgentSkeletonBake.poses(one, put("other", "wave", 1f), ranges) }
        assertFailsWith<IllegalArgumentException> { AgentSkeletonBake.poses(one, put("high", "High", 500f), ranges) }
        assertFailsWith<IllegalArgumentException> {
            AgentSkeletonBake.poses(one, buildJsonObject {
                put("mode", "pose_put"); put("pose", buildJsonObject { put("id", "x"); put("values", buildJsonObject { put("Nope", 1) }) })
            }, ranges)
        }
        assertFailsWith<IllegalArgumentException> { AgentSkeletonBake.poses(one, buildJsonObject { put("mode", "pose_delete"); put("id", "gone") }, ranges) }
        assertTrue(AgentSkeletonBake.poses(one, buildJsonObject { put("mode", "pose_delete"); put("id", "wave") }, ranges).isEmpty())
        assertFailsWith<IllegalArgumentException> { PosePreset("1bad", "Bad", mapOf("ParamArmLA" to 1f)) }
    }
}
