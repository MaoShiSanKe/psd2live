package io.github.psd2live.agent

import io.github.psd2live.core.BoneEnd
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.MotionCurve
import io.github.psd2live.core.MotionHandle
import io.github.psd2live.core.MotionInterpolation
import io.github.psd2live.core.MotionKey
import io.github.psd2live.core.SkeletonBone
import io.github.psd2live.core.SkeletonSpec
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Pure edits shared by the MCP boundary and its tests. Persistence belongs to AgentWorkspace. */
internal object AgentSkeletonMotionEdits {
    fun skeleton(current: SkeletonSpec?, request: JsonObject, proposal: () -> SkeletonSpec): SkeletonSpec {
        val mode = request.requiredText("mode")
        val spec = when (mode) {
            "auto" -> proposal().copy(enabled = true)
            "put" -> SkeletonSpec.fromJson(request.getValue("spec").jsonObject)
            "enable" -> requireNotNull(current) { "Create a skeleton before changing its enabled state" }
                .copy(enabled = request.getValue("enabled").jsonPrimitive.boolean)
            "bone" -> {
                val before = requireNotNull(current) { "Create a skeleton before editing bones" }
                val input = request.getValue("bone").jsonObject
                val id = input.requiredText("id")
                val fields = before.bone(id)?.toJson()?.let { JsonObject(it + input) } ?: input
                before.withBone(SkeletonBone.fromJson(fields))
            }
            "move" -> {
                val before = requireNotNull(current) { "Create a skeleton before moving joints" }
                val id = request.requiredText("bone_id")
                require(before.bone(id) != null) { "Bone not found: $id" }
                val end = BoneEnd.valueOf(request.requiredText("end").uppercase())
                val point = request.getValue("point").jsonArray
                before.withJointMoved(id, end, point[0].jsonPrimitive.float, point[1].jsonPrimitive.float)
            }
            "bind" -> {
                val before = requireNotNull(current) { "Create a skeleton before binding meshes" }
                before.withDrawableBound(request.requiredText("drawable_id"), request["bone_id"]?.jsonPrimitive?.contentOrNull)
            }
            "remove" -> {
                val before = requireNotNull(current) { "Create a skeleton before removing bones" }
                val id = request.requiredText("bone_id")
                val bone = requireNotNull(before.bone(id)) { "Bone not found: $id" }
                require(!bone.role.anchor && !bone.role.body) { "Body and anchor bones cannot be removed" }
                before.withoutBone(id)
            }
            else -> error("Unknown skeleton mode: $mode")
        }
        require(spec.bones.size <= 128) { "A skeleton may contain at most 128 bones" }
        require(spec.bones.flatMap { it.drawableIds }.distinct().size == spec.bones.sumOf { it.drawableIds.size }) {
            "A drawable can be bound to only one bone"
        }
        require(spec.bones.filterNot { it.role.anchor }.map { it.parameterId }.distinct().size ==
            spec.bones.count { !it.role.anchor }) { "Bones must have distinct parameter IDs" }
        require(spec.bones.all { it.direction == 1f || it.direction == -1f }) { "Bone direction must be +1 or -1" }
        return spec
    }

    fun motion(
        current: List<MotionClip>,
        request: JsonObject,
        ranges: Map<String, ClosedFloatingPointRange<Float>>,
        skeleton: SkeletonSpec?,
    ): List<MotionClip> {
        val mode = request.requiredText("mode")
        val next = when (mode) {
            "put" -> {
                val clip = MotionClips.fromJson(request.getValue("clip").jsonObject)
                current.filterNot { it.id == clip.id } + clip
            }
            "delete" -> {
                val id = request.requiredText("id")
                require(current.any { it.id == id }) { "Motion not found: $id" }
                current.filterNot { it.id == id }
            }
            "seed_builtin" -> {
                val name = request.requiredText("builtin")
                require(name in MotionClips.BUILTIN_NAMES) { "Unknown built-in motion: $name" }
                if (current.any { it.builtin == name }) current else {
                    val tracks = MotionClips.builtinTracks(name, skeleton)
                    require(tracks.isNotEmpty()) { "Built-in motion has no tracks for this model: $name" }
                    current + MotionClips.fromTracks(
                        id = request["id"]?.jsonPrimitive?.contentOrNull ?: MotionClips.newId(current),
                        name = name, builtin = name, loop = MotionClips.isLoopBuiltin(name), tracks = tracks,
                        duration = MotionClips.builtinDuration(name, tracks),
                    )
                }
            }
            "set_key" -> {
                val clip = requireClip(current, request.requiredText("id"))
                val parameter = request.requiredText("parameter")
                val key = parseKey(request.getValue("key").jsonObject)
                val curve = clip.curve(parameter)
                val replacement = MotionCurve(parameter, MotionClips.normalized(curve?.keys.orEmpty() + key))
                val updated = clip.copy(curves = if (curve == null) clip.curves + replacement
                    else clip.curves.map { if (it.parameterId == parameter) replacement else it })
                current.map { if (it.id == clip.id) updated else it }
            }
            "delete_key" -> {
                val clip = requireClip(current, request.requiredText("id"))
                val parameter = request.requiredText("parameter")
                val time = request.getValue("time").jsonPrimitive.float
                val curve = requireNotNull(clip.curve(parameter)) { "Motion curve not found: $parameter" }
                require(curve.keys.any { abs(it.time - time) < MotionClips.TIME_EPSILON }) { "Motion key not found at $time" }
                val kept = curve.keys.filterNot { abs(it.time - time) < MotionClips.TIME_EPSILON }
                val updated = clip.copy(curves = clip.curves.filterNot { it.parameterId == parameter } +
                    if (kept.isEmpty()) emptyList() else listOf(MotionCurve(parameter, kept)))
                current.map { if (it.id == clip.id) updated else it }
            }
            "remove_curve" -> {
                val clip = requireClip(current, request.requiredText("id"))
                val parameter = request.requiredText("parameter")
                require(clip.curve(parameter) != null) { "Motion curve not found: $parameter" }
                current.map { if (it.id == clip.id) clip.copy(curves = clip.curves.filterNot { c -> c.parameterId == parameter }) else it }
            }
            else -> error("Unknown motion mode: $mode")
        }
        return validated(next, ranges)
    }

    /** [next] once every clip is known to fit the model's parameters and the limits a request may not pass. */
    fun validated(next: List<MotionClip>, ranges: Map<String, ClosedFloatingPointRange<Float>>): List<MotionClip> {
        require(next.map { it.id }.distinct().size == next.size) { "Duplicate motion IDs" }
        require(next.mapNotNull { it.builtin }.distinct().size == next.count { it.builtin != null }) { "Duplicate built-in override" }
        require(next.map { it.name.lowercase() }.distinct().size == next.size) { "Duplicate motion names" }
        next.forEach { clip ->
            require(clip.builtin == null || clip.builtin in MotionClips.BUILTIN_NAMES) { "Unknown built-in override: ${clip.builtin}" }
            require(clip.builtin != null || MotionClips.BUILTIN_NAMES.none { it.equals(clip.name, ignoreCase = true) }) {
                "Custom motion name conflicts with a generated motion: ${clip.name}"
            }
            require(clip.curves.size <= 256) { "A motion may contain at most 256 curves" }
            clip.curves.forEach { curve ->
                val range = requireNotNull(ranges[curve.parameterId]) { "Unknown motion parameter: ${curve.parameterId}" }
                require(curve.keys.size <= 4096) { "A motion curve may contain at most 4096 keys" }
                require(curve.keys.all { it.time in 0f..clip.duration && it.value in range }) {
                    "Motion keys for ${curve.parameterId} must lie within the clip and parameter ranges"
                }
                require(curve.keys.all { it.outHandle.x in 0f..1f && it.inHandle.x in 0f..1f }) {
                    "Motion handle time fractions must be between 0 and 1"
                }
            }
        }
        return next
    }

    private fun requireClip(clips: List<MotionClip>, id: String): MotionClip =
        requireNotNull(clips.firstOrNull { it.id == id }) { "Motion not found: $id" }

    private fun parseKey(value: JsonObject): MotionKey {
        fun handle(name: String) = value[name]?.jsonArray?.let { MotionHandle(it[0].jsonPrimitive.float, it[1].jsonPrimitive.float) }
            ?: MotionHandle()
        val interpolation = value["interpolation"]?.jsonPrimitive?.contentOrNull?.let(MotionInterpolation::valueOf)
            ?: MotionInterpolation.LINEAR
        return MotionKey(value.getValue("time").jsonPrimitive.float, value.getValue("value").jsonPrimitive.float,
            interpolation, handle("out"), handle("in"))
    }

    private fun JsonObject.requiredText(key: String): String = getValue(key).jsonPrimitive.content
}
