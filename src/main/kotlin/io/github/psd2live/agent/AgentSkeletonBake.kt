package io.github.psd2live.agent

import io.github.psd2live.core.BakeOptions
import io.github.psd2live.core.BakeResult
import io.github.psd2live.core.BakeShape
import io.github.psd2live.core.BakeWrite
import io.github.psd2live.core.IkTarget
import io.github.psd2live.core.MotionClip
import io.github.psd2live.core.MotionClips
import io.github.psd2live.core.PoseEase
import io.github.psd2live.core.PoseKey
import io.github.psd2live.core.PosePreset
import io.github.psd2live.core.SkeletonBake
import io.github.psd2live.core.SkeletonSpec
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

/**
 * The bake and pose-snapshot requests of the `motion` tool, shared with the editor's bake dialog through
 * the same [io.github.psd2live.core.SkeletonBake]. Pure, so the MCP boundary and its tests need no ViewModel.
 */
internal object AgentSkeletonBake {
	/** Pose keys one request may carry. */
	const val MAX_KEYS = 256

	class Request(val keys: List<PoseKey>, val options: BakeOptions, val write: BakeWrite)

	/** [defaultFps] is the frame rate when the request names none: the target clip's. */
	fun parse(request: JsonObject, defaultFps: Float = 30f): Request {
		val keys = request.getValue("keys").jsonArray.map { element ->
			val key = element.jsonObject
			PoseKey(
				time = key.getValue("time").jsonPrimitive.float,
				values = key["values"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.float },
				ik = key["ik"]?.jsonArray.orEmpty().map { target ->
					val o = target.jsonObject
					val point = o.getValue("target").jsonArray
					IkTarget(o.getValue("bone_id").jsonPrimitive.content, point[0].jsonPrimitive.float, point[1].jsonPrimitive.float)
				},
				ease = key["ease"]?.jsonPrimitive?.contentOrNull?.let(PoseEase::valueOf) ?: PoseEase.SMOOTH,
			)
		}
		require(keys.size in 1..MAX_KEYS) { "Baking takes 1..$MAX_KEYS pose keys" }
		val options = BakeOptions(
			fps = request["fps"]?.jsonPrimitive?.float ?: defaultFps,
			tolerance = request["tolerance"]?.jsonPrimitive?.float ?: 0.5f,
			shape = if (request["curve"]?.jsonPrimitive?.contentOrNull == "bezier") BakeShape.BEZIER else BakeShape.LINEAR,
			parameterIds = request["parameters"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet(),
			start = request["start"]?.jsonPrimitive?.float,
			end = request["end"]?.jsonPrimitive?.float,
		)
		val write = if (request["write"]?.jsonPrimitive?.contentOrNull == "merge") BakeWrite.MERGE else BakeWrite.REPLACE
		return Request(keys, options, write)
	}

	/** The rate a `bake` request without `fps` uses: the clip [request] targets, or 30. */
	fun defaultFps(request: JsonObject, clips: List<MotionClip>): Float =
		request["id"]?.jsonPrimitive?.contentOrNull?.let { id -> clips.firstOrNull { it.id == id }?.fps } ?: 30f

	fun bake(model: PuppetModel, spec: SkeletonSpec?, request: Request, cancelled: () -> Unit = {}): BakeResult {
		require(spec?.enabled == true) { "No enabled skeleton is available" }
		return SkeletonBake.bake(model, spec, request.keys, request.options, cancelled)
	}

	/** [current] with [result] written into the clip `id` names; a clip that does not exist yet is created. */
	fun apply(current: List<MotionClip>, request: JsonObject, parsed: Request, result: BakeResult): List<MotionClip> {
		val id = request.getValue("id").jsonPrimitive.content
		val existing = current.firstOrNull { it.id == id }
		val base = existing ?: MotionClip(
			id = id,
			name = MotionClips.uniqueName(current, request["name"]?.jsonPrimitive?.contentOrNull ?: "Baked"),
			loop = request["loop"]?.jsonPrimitive?.booleanOrNull ?: false,
			duration = maxOf(result.end, 1f / parsed.options.fps),
			fps = parsed.options.fps,
		)
		val written = SkeletonBake.applyTo(base, result, parsed.write)
		return if (existing == null) current + written else current.map { if (it.id == id) written else it }
	}

	fun report(result: BakeResult, includeCurves: Boolean): JsonObject = buildJsonObject {
		put("frames", result.frameCount)
		put("samples", result.sampleCount)
		put("keys", result.keyCount)
		put("max_error", result.maxError)
		put("start", result.start)
		put("end", result.end)
		putJsonObject("keys_by_parameter") { result.keysByParameter.forEach { (parameter, count) -> put(parameter, count) } }
		if (includeCurves) {
			val clip = MotionClip(id = "preview", name = "preview", duration = maxOf(result.end, 0.001f), curves = result.curves)
			put("curves", MotionClips.toJson(clip).getValue("curves"))
		}
	}

	/** The pose snapshots after a `pose_put` or `pose_delete`. */
	fun poses(current: List<PosePreset>, request: JsonObject, ranges: Map<String, ClosedFloatingPointRange<Float>>): List<PosePreset> {
		val next = when (val mode = request.getValue("mode").jsonPrimitive.content) {
			"pose_put" -> {
				val o = request.getValue("pose").jsonObject
				val id = o.getValue("id").jsonPrimitive.content
				val preset = PosePreset(
					id = id,
					name = o["name"]?.jsonPrimitive?.contentOrNull ?: current.firstOrNull { it.id == id }?.name ?: id,
					values = o.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.float },
				)
				if (current.any { it.id == id }) current.map { if (it.id == id) preset else it } else current + preset
			}
			"pose_delete" -> {
				val id = request.getValue("id").jsonPrimitive.content
				require(current.any { it.id == id }) { "Pose not found: $id" }
				current.filterNot { it.id == id }
			}
			else -> error("Unknown pose mode: $mode")
		}
		require(next.size <= PosePreset.MAX_POSES) { "At most ${PosePreset.MAX_POSES} poses" }
		require(next.map { it.name.lowercase() }.distinct().size == next.size) { "Duplicate pose names" }
		next.forEach { pose ->
			for ((parameter, value) in pose.values) {
				val range = requireNotNull(ranges[parameter]) { "Unknown pose parameter: $parameter" }
				require(value in range) { "Pose value for $parameter must lie within ${range.start}..${range.endInclusive}" }
			}
		}
		return next
	}
}
