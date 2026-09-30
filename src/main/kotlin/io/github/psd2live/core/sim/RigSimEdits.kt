package io.github.psd2live.core.sim

import io.github.psd2live.core.PhysicsInput
import kotlinx.serialization.json.*
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.VertexGroupKind

/** What a simulated body is made of. Rigid and soft bodies come later. */
enum class SimKind(val jsonName: String) {
    CLOTH("cloth"),
    HAIR("hair");

    companion object {
        fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown simulation kind: $text (${entries.joinToString { it.jsonName }})")
    }
}

/**
 * What one glue does in a simulation. A glue is never a pin by itself: the user picks this per glue, and
 * [IGNORE] - the runtime weld only - is the default.
 */
enum class GlueRole(val jsonName: String) {
    IGNORE("ignore"),
    /** The simulated side's glued vertices follow the other side's deformed vertices. */
    PIN("pin"),
    /** Both sides are simulated and held together, weighted as the glue weights them. */
    CONSTRAINT("constraint");

    companion object {
        fun parse(text: String) = entries.firstOrNull { it.jsonName.equals(text, ignoreCase = true) || it.name.equals(text, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown glue role: $text (ignore, pin, constraint)")
    }
}

/** The key a glue role is stored under: its two meshes, as the glue itself is addressed. */
fun glueKey(glue: Glue): String = "${glue.meshA.raw}|${glue.meshB.raw}"

/**
 * Material values, each 0..1 except [mass]. A vertex group of the matching kind multiplies its value per
 * vertex, so painting 0 removes it there and 1 keeps the full value.
 */
data class SimMaterial(
    /** Relative particle mass. */
    val mass: Float = 1f,
    /** Resistance to stretching; cloth and hair want this near 1. */
    val stretch: Float = 0.98f,
    /** Resistance to bending. */
    val bend: Float = 0.3f,
    /** Velocity damping rate in 1/s. */
    val damping: Float = 1.5f,
    /** Spring toward the rig-carried rest shape; 0 turns it off. */
    val goal: Float = 0.1f,
    /** How far a vertex may drift from its pinned root beyond the rest path, as a fraction (long-range limit). */
    val slack: Float = 0.03f,
) {
    init {
        require(listOf(mass, stretch, bend, damping, goal, slack).all(Float::isFinite)) { "Material values must be finite" }
        require(mass > 0f && stretch in 0f..1f && bend in 0f..1f && damping >= 0f && goal in 0f..1f && slack in 0f..1f) {
            "Material out of range: mass > 0, stretch/bend/goal/slack 0..1, damping >= 0"
        }
    }

    fun toJson() = buildJsonObject {
        put("mass", mass); put("stretch", stretch); put("bend", bend); put("damping", damping); put("goal", goal); put("slack", slack)
    }

    companion object {
        fun fromJson(o: JsonObject, base: SimMaterial = SimMaterial()) = SimMaterial(
            o.number("mass") ?: base.mass, o.number("stretch") ?: base.stretch, o.number("bend") ?: base.bend,
            o.number("damping") ?: base.damping, o.number("goal") ?: base.goal, o.number("slack") ?: base.slack,
        )

        fun preset(kind: SimKind) = when (kind) {
            SimKind.CLOTH -> SimMaterial(stretch = 0.98f, bend = 0.3f, damping = 1.5f, goal = 0.1f)
            SimKind.HAIR -> SimMaterial(stretch = 1f, bend = 0.45f, damping = 2f, goal = 0.15f, slack = 0.01f)
        }
    }
}

/** A mesh that pushes simulated vertices out; [group] names its COLLIDER vertex group, null the whole mesh. */
data class SimColliderRef(val drawableId: String, val group: String? = null, val margin: Float = 2f) {
    init {
        require(drawableId.isNotBlank()) { "Collider mesh is required" }
        require(margin.isFinite() && margin >= 0f) { "Collider margin must be >= 0" }
    }

    fun toJson() = buildJsonObject {
        put("mesh", drawableId); group?.let { put("group", it) }; put("margin", margin)
    }

    companion object {
        fun fromJson(o: JsonObject) = SimColliderRef(o.getValue("mesh").jsonPrimitive.content,
            o["group"]?.jsonPrimitive?.contentOrNull, o.number("margin") ?: 2f)
    }
}

/**
 * One simulated body: ArtMesh [targets] simulated together, held by pins and glue, pushed by colliders.
 *
 * [groups] names the vertex group used for each kind; a kind without an entry uses the target's first
 * group of that kind, and none at all means the material value everywhere (no pins, for [VertexGroupKind.PIN]).
 */
data class RigSimEdit(
    val id: String,
    val name: String,
    val kind: SimKind,
    val targets: List<String>,
    val material: SimMaterial = SimMaterial.preset(kind),
    val groups: Map<VertexGroupKind, String> = emptyMap(),
    val glueRoles: Map<String, GlueRole> = emptyMap(),
    val colliders: List<SimColliderRef> = emptyList(),
    /** Parameters that move the rig during training and preview; the bake reads them. Empty uses the head and body angles. */
    val inputs: List<PhysicsInput> = emptyList(),
    val enabled: Boolean = true,
    /** Dynamic modes the bake keeps, 1..[MAX_MODES]: one parameter and one pendulum each. */
    val modes: Int = 1,
    /**
     * Parameters whose pose is baked exactly, as corrections on their own axes (a leg pushing the skirt).
     * Null picks the parameters that move a collider.
     */
    val staticInputs: List<String>? = null,
    /** Keys on each mode parameter and static axis, odd within [KEY_COUNTS]: more follow arcs and pushes more closely. */
    val keys: Int = 5,
    /**
     * Writes the modes as blend shapes (true) or keyform axes (false) where the target runtime has blend
     * shapes; null picks blend shapes only where keyform axes would multiply a mesh's keyforms past a few dozen.
     */
    val blendShapes: Boolean? = null,
    /** Bakes again with every change made in the simulation panel or through MCP, in the same history step. */
    val autoBake: Boolean = true,
    /**
     * How much larger than simulated the modes swing, within [EXAGGERATIONS]: the key shapes are scaled as
     * they are written back, so it applies without baking again and never pushes the parameters to ±1.
     */
    val exaggeration: Float = DEFAULT_EXAGGERATION,
    /** The materialized bake; the rebuild writes it back without simulating. */
    val bake: SimBakeResult? = null,
) {
    init {
        require(listOf(id, name).all { it.isNotBlank() && it.none(Char::isISOControl) }) { "Simulation ID and name are required" }
        require(targets.isNotEmpty() && targets.distinct().size == targets.size && targets.all { it.isNotBlank() }) { "Simulation needs distinct target meshes" }
        require(colliders.none { it.drawableId in targets }) { "A simulated mesh cannot also be its own collider" }
        require(inputs.map { it.parameter }.distinct().size == inputs.size) { "A parameter feeds a simulation once" }
        require(modes in 1..MAX_MODES) { "A simulation bakes 1..$MAX_MODES modes" }
        require(staticInputs == null || staticInputs.size <= MAX_STATIC_INPUTS && staticInputs.distinct().size == staticInputs.size) {
            "At most $MAX_STATIC_INPUTS distinct static inputs"
        }
        require(keys in KEY_COUNTS && keys % 2 == 1) { "A simulation bakes an odd number of keys within $KEY_COUNTS" }
        require(exaggeration in EXAGGERATIONS) { "Exaggeration is within $EXAGGERATIONS" }
    }

    /** The mode parameters' keys as the bake solves them: [keys] values evenly spread over -1..1, written out times [SimGenerator.MODE_RANGE]. */
    val modeKeys: FloatArray get() = FloatArray(keys) { -1f + 2f * it / (keys - 1) }

    fun toJson() = buildJsonObject {
        put("id", id); put("name", name); put("kind", kind.jsonName)
        putJsonArray("targets") { targets.forEach { add(it) } }
        put("material", material.toJson())
        if (groups.isNotEmpty()) putJsonObject("groups") { groups.forEach { (k, v) -> put(k.jsonName, v) } }
        if (glueRoles.isNotEmpty()) putJsonObject("glue_roles") { glueRoles.forEach { (k, v) -> put(k, v.jsonName) } }
        if (colliders.isNotEmpty()) putJsonArray("colliders") { colliders.forEach { add(it.toJson()) } }
        if (inputs.isNotEmpty()) putJsonArray("inputs") { inputs.forEach { add(it.toJson()) } }
        if (!enabled) put("enabled", false)
        if (modes != 1) put("modes", modes)
        staticInputs?.let { list -> putJsonArray("static_inputs") { list.forEach { add(it) } } }
        if (keys != 5) put("keys", keys)
        blendShapes?.let { put("blend_shapes", it) }
        if (!autoBake) put("auto_bake", false)
        if (exaggeration != DEFAULT_EXAGGERATION) put("exaggeration", exaggeration)
        bake?.let { put("bake", it.toJson()) }
    }

    /**
     * [o] laid over this edit: arrays and maps replace, `material` merges field by field. `bake` is taken
     * as given (null clears it); leaving it out keeps the bake, which then reads as stale if the setup changed.
     */
    fun patched(o: JsonObject): RigSimEdit {
        val nextKind = o.string("kind")?.let(SimKind::parse) ?: kind
        return copy(
            name = o.string("name") ?: name,
            kind = nextKind,
            targets = o["targets"]?.jsonArray?.map { it.jsonPrimitive.content } ?: targets,
            material = o["material"]?.jsonObject?.let { SimMaterial.fromJson(it, if (nextKind != kind) SimMaterial.preset(nextKind) else material) }
                ?: if (nextKind != kind) SimMaterial.preset(nextKind) else material,
            groups = o["groups"]?.jsonObject?.map { (k, v) -> VertexGroupKind.parse(k) to v.jsonPrimitive.content }?.toMap() ?: groups,
            glueRoles = o["glue_roles"]?.jsonObject?.map { (k, v) -> k to GlueRole.parse(v.jsonPrimitive.content) }?.toMap() ?: glueRoles,
            colliders = o["colliders"]?.jsonArray?.map { SimColliderRef.fromJson(it.jsonObject) } ?: colliders,
            inputs = o["inputs"]?.jsonArray?.map { PhysicsInput.fromJson(it.jsonObject) } ?: inputs,
            enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: enabled,
            modes = o["modes"]?.jsonPrimitive?.intOrNull ?: modes,
            staticInputs = when (val value = o["static_inputs"]) {
                null -> staticInputs
                is JsonNull -> null
                else -> value.jsonArray.map { it.jsonPrimitive.content }
            },
            keys = o["keys"]?.jsonPrimitive?.intOrNull ?: keys,
            blendShapes = when (val value = o["blend_shapes"]) {
                null -> blendShapes
                is JsonNull -> null
                else -> value.jsonPrimitive.booleanOrNull ?: blendShapes
            },
            autoBake = o["auto_bake"]?.jsonPrimitive?.booleanOrNull ?: autoBake,
            exaggeration = o["exaggeration"]?.jsonPrimitive?.floatOrNull ?: exaggeration,
            bake = when (val value = o["bake"]) {
                null -> bake
                is JsonNull -> null
                else -> SimBakeResult.fromJson(value.jsonObject)
            },
        )
    }

    companion object {
        const val MAX_MODES = 3
        const val MAX_STATIC_INPUTS = 4
        val KEY_COUNTS = 3..9
        val EXAGGERATIONS = 1f..2f
        const val DEFAULT_EXAGGERATION = 1.3f

        fun fromJson(o: JsonObject): RigSimEdit {
            val kind = o.string("kind")?.let(SimKind::parse) ?: SimKind.CLOTH
            val id = requireNotNull(o.string("id")) { "id is required" }
            val targets = requireNotNull(o["targets"]?.jsonArray) { "targets is required" }.map { it.jsonPrimitive.content }
            return RigSimEdit(id, o.string("name") ?: id, kind, targets).patched(o)
        }
    }
}

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.floatOrNull
