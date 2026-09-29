package io.github.psd2live.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal

/**
 * Cubism Framework's JSON reader accepts a number only when a comma or a line break ends it, and only
 * in plain decimal: an exponent is a syntax error. Pretty-printing supplies the break, and exponents
 * are written out in full, so generated sidecars stay inside that subset.
 */
internal object CubismJson {
	private val writer = Json { prettyPrint = true }

	/** A JSON number in scientific notation, not a digit run inside an identifier. */
	private val exponent = Regex("""(?<![A-Za-z0-9_])-?(?:\d+\.\d*|\d*\.\d+|\d+)[eE][+-]?\d+""")

	fun normalize(source: String): String = expandExponents(
		writer.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(source)),
	)

	private fun expandExponents(text: String): String = exponent.replace(text) { match ->
		BigDecimal(match.value).toPlainString()
	}
}
