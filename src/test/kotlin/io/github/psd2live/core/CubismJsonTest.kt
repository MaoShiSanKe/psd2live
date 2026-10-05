package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CubismJsonTest {
	@Test fun generatedMotionsStayInsideTheCubismReader() {
		val parameters = setOf(
			"ParamBreath", "ParamAngleX", "ParamAngleY", "ParamAngleZ",
			"ParamBodyAngleX", "ParamBodyAngleY", "ParamBodyAngleZ",
			"ParamEyeLOpen", "ParamEyeROpen",
		)
		val authored = MotionClip(
			id = "motion_1",
			name = "动作",
			fadeIn = 1f,
			fadeOut = 1f,
			curves = listOf(
				MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f), MotionKey(1f, 15f))),
				MotionCurve("ParamAngleY", listOf(MotionKey(0f, 0f), MotionKey(0.5f, -8f))),
				MotionCurve("ParamEyeLOpen", listOf(MotionKey(0f, 1f), MotionKey(1.2f, 1.0E-4f))),
			),
		)
		val documents = listOf(
			"idle" to MotionGenerator.idle(parameters),
			"blink" to MotionGenerator.blink(parameters),
			"nod" to MotionGenerator.nod(parameters),
			"authored" to MotionGenerator.clip(authored, parameters),
			"singleKey" to MotionGenerator.clip(
				MotionClip("motion_2", "Motion", curves = listOf(MotionCurve("ParamAngleX", listOf(MotionKey(0f, 0f))))),
				parameters,
			),
		) + skeletonDocuments()
		for ((name, raw) in documents) {
			val json = CubismJson.normalize(checkNotNull(raw) { "$name missing" })
			assertNull(cubismJsonError(json), "$name rejected:\n${window(json)}")
			assertConsistent(json, name)
			assertTrue(!Regex("""[0-9][eE][+-]?\d""").containsMatchIn(json), name)
		}
	}

	/** Skeleton presets whose samples pass near zero; those values used to be written as exponents. */
	private fun skeletonDocuments(): List<Pair<String, String?>> {
		fun bone(id: String, parent: String?, role: BoneRole, x: Float, y0: Float, y1: Float, side: Side = Side.NONE) =
			SkeletonBone(id, id, parent, role, side, x, y0, x, y1, drawableIds = listOf(id))
		val spec = SkeletonSpec(bones = listOf(
			bone("hip", null, BoneRole.LOWER_BODY, 0f, 200f, 240f),
			bone("chest", "hip", BoneRole.UPPER_BODY, 0f, 120f, 200f),
			bone("arm", "chest", BoneRole.UPPER_ARM, 40f, 130f, 200f, Side.RIGHT),
			bone("fore", "arm", BoneRole.FOREARM, 40f, 200f, 280f, Side.RIGHT),
			bone("tail", "hip", BoneRole.TAIL, 0f, 240f, 320f),
			bone("wing", "chest", BoneRole.WING, -30f, 140f, 180f, Side.LEFT),
			bone("thigh", "hip", BoneRole.THIGH, 20f, 240f, 340f, Side.LEFT),
			bone("shin", "thigh", BoneRole.SHIN, 20f, 340f, 440f, Side.LEFT),
			bone("foot", "shin", BoneRole.FOOT, 20f, 440f, 470f, Side.LEFT),
		))
		val idleTracks = SkeletonMotions.idle(spec)
		val idleIds = idleTracks.mapTo(HashSet()) { it.parameterId }
		return listOf("skeletonIdle" to MotionGenerator.idle(idleIds, spec)) + SkeletonMotions.presets.mapNotNull { preset ->
			val tracks = preset.tracks(spec)
			if (tracks.isEmpty()) null
			else preset.name to MotionGenerator.skeleton(tracks, tracks.mapTo(HashSet()) { it.parameterId }, loop = preset.loop)
		}
	}

	private fun window(json: String): String {
		val lines = json.lines()
		val hit = lines.indexOfFirst { Regex("""[0-9][eE]""").containsMatchIn(it) }.takeIf { it >= 0 } ?: 0
		return lines.drop((hit - 2).coerceAtLeast(0)).take(8).joinToString("\n")
	}

	/** The same point and segment walk Cubism uses before it will play a motion. */
	private fun assertConsistent(json: String, name: String) {
		val root = Json.parseToJsonElement(json).jsonObject
		val meta = root.getValue("Meta").jsonObject
		val curves = root.getValue("Curves").jsonArray
		var segments = 0
		var points = 0
		for (curve in curves) {
			val values = curve.jsonObject.getValue("Segments").jsonArray.map { it.jsonPrimitive.float }
			var index = 2
			points += 1
			while (index < values.size) {
				when (values[index].toInt()) {
					0, 2, 3 -> {
						points += 1
						index += 3
					}
					1 -> {
						points += 3
						index += 7
					}
					else -> error("$name bad segment ${values[index]} at $index")
				}
				segments += 1
			}
			assertEquals(values.size, index, "$name segment walk stopped short")
		}
		assertEquals(curves.size, meta.getValue("CurveCount").jsonPrimitive.int, name)
		assertEquals(segments, meta.getValue("TotalSegmentCount").jsonPrimitive.int, name)
		assertEquals(points, meta.getValue("TotalPointCount").jsonPrimitive.int, name)
	}
}

/**
 * A transcription of Cubism SDK 5-r.5 `CubismJson` for the cases these sidecars use.
 * A number ends only at `,` or `\n`; `]`, `}` and an exponent are errors.
 */
private fun cubismJsonError(text: String): String? {
	val probe = CubismProbe(text)
	probe.parse()
	return probe.error?.let { "$it @line ${probe.line + 1}" }
}

private class CubismProbe(private val buffer: String) {
	var error: String? = null
	var line: Int = 0

	fun parse() {
		val end = intArrayOf(0)
		parseValue(0, end)
	}

	private fun parseString(begin: Int, outEnd: IntArray): Boolean {
		if (error != null) return false
		var i = begin
		while (i < buffer.length) {
			when (buffer[i]) {
				'"' -> {
					outEnd[0] = i + 1
					return true
				}
				'\\' -> {
					i++
					if (i >= buffer.length) {
						error = "parse string/escape error"
						return false
					}
					if (buffer[i] == 'u') {
						error = "parse string/unicode escape not supported"
						return false
					}
				}
			}
			i++
		}
		error = "parse string/illegal end"
		return false
	}

	private fun parseNumeric(begin: Int, outEnd: IntArray): Boolean {
		if (error != null) return false
		var decimalPointSeen = false
		var i = begin
		while (i < buffer.length) {
			when (buffer[i]) {
				'-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> Unit
				'.' -> {
					if (decimalPointSeen) {
						error = "multiple decimal points found"
						return false
					}
					decimalPointSeen = true
				}
				'\n', ',' -> {
					outEnd[0] = i
					return true
				}
				'\r' -> Unit
				else -> {
					error = "non-numeric character '${buffer[i]}'"
					return false
				}
			}
			i++
		}
		error = "parse numeric/illegal end"
		return false
	}

	private fun parseObject(begin: Int, outEnd: IntArray): Boolean {
		if (error != null) return false
		var i = begin
		while (i < buffer.length) {
			var keyFound = false
			while (i < buffer.length) {
				when (buffer[i]) {
					'"' -> {
						if (!parseString(i + 1, outEnd)) return false
						i = outEnd[0]
						keyFound = true
						break
					}
					'}' -> {
						outEnd[0] = i + 1
						return true
					}
					':' -> {
						error = "illegal ':' position"
						return false
					}
					'\n' -> line++
				}
				i++
			}
			if (!keyFound) {
				error = "key not found"
				return false
			}
			var colon = false
			while (i < buffer.length) {
				when (buffer[i]) {
					':' -> {
						colon = true
						i++
						break
					}
					'}' -> {
						error = "illegal '}' position"
						return false
					}
					'\n' -> line++
				}
				i++
			}
			if (!colon) {
				error = "':' not found"
				return false
			}
			if (!parseValue(i, outEnd)) return false
			i = outEnd[0]
			while (i < buffer.length) {
				when (buffer[i]) {
					',' -> break
					'}' -> {
						outEnd[0] = i + 1
						return true
					}
					'\n' -> line++
				}
				i++
			}
			i++
		}
		error = "illegal end of parseObject"
		return false
	}

	private fun parseArray(begin: Int, outEnd: IntArray): Boolean {
		if (error != null) return false
		var i = begin
		while (i < buffer.length) {
			if (!parseValue(i, outEnd)) return false
			i = outEnd[0]
			while (i < buffer.length) {
				when (buffer[i]) {
					',' -> break
					']' -> {
						outEnd[0] = i + 1
						return true
					}
					'\n' -> line++
				}
				i++
			}
			i++
		}
		error = "illegal end of parseArray"
		return false
	}

	private fun parseValue(begin: Int, outEnd: IntArray): Boolean {
		if (error != null) return false
		var i = begin
		while (i < buffer.length) {
			when (val c = buffer[i]) {
				'-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> return parseNumeric(i, outEnd)
				'"' -> return parseString(i + 1, outEnd)
				'[' -> return parseArray(i + 1, outEnd)
				'{' -> return parseObject(i + 1, outEnd)
				'n' -> {
					outEnd[0] = i + 4
					return true
				}
				't' -> {
					outEnd[0] = i + 4
					return true
				}
				'f' -> {
					outEnd[0] = i + 5
					return true
				}
				',' -> {
					error = "illegal ',' position"
					return false
				}
				']' -> {
					outEnd[0] = i
					return true
				}
				'\n' -> line++
				else -> Unit
			}
			i++
		}
		error = "illegal end of value"
		return false
	}
}
