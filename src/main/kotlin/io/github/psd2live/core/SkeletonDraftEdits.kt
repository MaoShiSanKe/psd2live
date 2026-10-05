package io.github.psd2live.core

/**
 * One reshaping step of an open skeleton draft. The canvas editor and the public draft session both build these,
 * so a gesture and an agent request run through the same candidate code.
 */
sealed interface SkeletonDraftIntent {
    /** Moves, turns and scales [boneIds] about their common centre; [symmetric] makes outside mirror partners follow. */
    data class Transform(val boneIds: Set<String>, val dx: Float = 0f, val dy: Float = 0f, val degrees: Float = 0f,
        val scale: Float = 1f, val descendants: Boolean = false, val symmetric: Boolean = false) : SkeletonDraftIntent
    data class MoveJoint(val boneId: String, val end: BoneEnd, val x: Float, val y: Float, val symmetric: Boolean = false) : SkeletonDraftIntent
    /** Copies, or with [mirror] reflects across the symmetry axis (canvas centre when unset), the chosen bones. */
    data class Duplicate(val boneIds: Set<String>, val descendants: Boolean = false, val transferBindings: Boolean = false,
        val mirror: Boolean = false, val suffix: String? = null) : SkeletonDraftIntent
    data class Subdivide(val boneId: String, val segments: Int) : SkeletonDraftIntent
    data class Dissolve(val boneId: String) : SkeletonDraftIntent
    /** Adds the template tail or wing chain proposed from the tagged artwork, or removes every bone of that role. */
    data class OptionalChain(val role: BoneRole, val enabled: Boolean) : SkeletonDraftIntent
    data class CreateBone(val headX: Float, val headY: Float, val tailX: Float, val tailY: Float, val parentId: String? = null) : SkeletonDraftIntent
    data class RemoveBone(val boneId: String) : SkeletonDraftIntent
    data class Rename(val boneId: String, val name: String) : SkeletonDraftIntent
    data class Parent(val boneId: String, val parentId: String?, val connect: Boolean = false) : SkeletonDraftIntent
    /** Half width of the joint blend band in canvas pixels; null returns it to automatic. */
    data class BlendWidth(val boneId: String, val width: Float?) : SkeletonDraftIntent
    /** Joint limits in parameter degrees; each is kept on its own side of rest. */
    data class Limits(val boneId: String, val min: Float, val max: Float) : SkeletonDraftIntent
    data class Ik(val boneId: String, val settings: SkeletonIkSettings) : SkeletonDraftIntent
    /** Binds [drawableIds] to [boneId] alone, or unbinds them when it is null. */
    data class Bind(val drawableIds: Set<String>, val boneId: String?) : SkeletonDraftIntent
    data class SymmetryAxis(val x: Float) : SkeletonDraftIntent
    data class Enabled(val enabled: Boolean) : SkeletonDraftIntent
    /**
     * Brush dabs on [boneId]'s manual weights of one mesh. [capture] first resamples the mesh's current weights,
     * which a stroke does once at its start; later dabs of the same stroke continue on the stored map.
     */
    data class PaintWeights(val drawableId: String, val boneId: String, val points: List<Pair<Float, Float>>,
        val radius: Float, val strength: Float, val mode: SkeletonWeightBrushMode, val replaceValue: Float = 1f,
        val capture: Boolean = true) : SkeletonDraftIntent
    data class CleanupWeights(val drawableId: String, val influences: Int = 2, val cutoff: Float = 0.001f) : SkeletonDraftIntent
    /** Drops the mesh's manual weights so it follows the automatic skin again. */
    data class ClearWeights(val drawableId: String) : SkeletonDraftIntent
    /** Copies weights from [sourceId] onto [targetId]; [mapping] overrides the inferred source-to-target bone pairs. */
    data class TransferWeights(val sourceId: String, val targetId: String, val mode: SkeletonWeightTransferMode,
        val tolerance: Float = 10f, val mirror: Boolean = false, val mapping: Map<String, String> = emptyMap()) : SkeletonDraftIntent
    /** Puts back an earlier draft of the same session, such as the one a cancelled drag started from. */
    data class Restore(val spec: SkeletonSpec) : SkeletonDraftIntent
}

/** A candidate draft; [selected] is the bone selection the step leaves, when it chooses one. */
data class SkeletonDraftResult(val spec: SkeletonSpec, val selected: Set<String>? = null)

/** Pure candidate processors for skeleton drafts. They never read live editor state and fail without a partial result. */
object SkeletonDraftEdits {
    const val MAX_BONES = 128

    fun apply(spec: SkeletonSpec, model: RigPreviewModel, intent: SkeletonDraftIntent): SkeletonDraftResult {
        val puppet = model.rig.puppet
        fun bone(id: String) = requireNotNull(spec.bone(id)) { "Bone not found: $id" }
        fun drawable(id: String) = require(puppet.drawables.any { it.id.raw == id }) { "Drawable not found: $id" }
        val result = when (intent) {
            is SkeletonDraftIntent.Transform -> {
                intent.boneIds.forEach(::bone)
                var next = spec.withBonesTransformed(intent.boneIds, intent.dx, intent.dy, intent.degrees, intent.scale, intent.descendants)
                if (intent.symmetric) next = SkeletonAuthoring.synchronizeMirrors(next,
                    intent.boneIds + if (intent.descendants) intent.boneIds.flatMap(spec::descendants) else emptyList())
                SkeletonDraftResult(next)
            }
            is SkeletonDraftIntent.MoveJoint -> {
                bone(intent.boneId)
                require(intent.x.isFinite() && intent.y.isFinite()) { "Joint coordinates must be finite" }
                var next = spec.withJointMoved(intent.boneId, intent.end, intent.x, intent.y)
                if (intent.symmetric) next = SkeletonAuthoring.synchronizeMirrors(next, setOf(intent.boneId))
                SkeletonDraftResult(next, setOf(intent.boneId))
            }
            is SkeletonDraftIntent.Duplicate -> {
                intent.boneIds.forEach(::bone)
                require(intent.boneIds.isNotEmpty()) { "Choose bones to copy" }
                val copied = SkeletonAuthoring.duplicate(spec, intent.boneIds, intent.descendants,
                    transferBindings = intent.transferBindings,
                    mirrorAxis = if (intent.mirror) spec.symmetryAxisX ?: puppet.canvasWidth / 2f else null,
                    drawableMirrors = if (intent.mirror) mirrorDrawables(model) else emptyMap(),
                    suffix = intent.suffix ?: if (intent.mirror) " mirror" else " copy")
                SkeletonDraftResult(copied.spec, copied.selected)
            }
            is SkeletonDraftIntent.Subdivide -> {
                bone(intent.boneId)
                SkeletonAuthoring.subdivide(spec, intent.boneId, intent.segments).let { SkeletonDraftResult(it.spec, it.selected) }
            }
            is SkeletonDraftIntent.Dissolve -> {
                bone(intent.boneId)
                SkeletonAuthoring.dissolve(spec, intent.boneId).let { SkeletonDraftResult(it.spec, it.selected) }
            }
            is SkeletonDraftIntent.OptionalChain -> {
                require(intent.role == BoneRole.TAIL || intent.role == BoneRole.WING) { "Only tail and wing chains are optional" }
                if (!intent.enabled) {
                    val removed = spec.bones.filter { it.role == intent.role }.mapTo(HashSet()) { it.id }
                    SkeletonDraftResult(removed.fold(spec) { next, id -> next.withoutBone(id) })
                } else {
                    val template = SkeletonAutoBuilder.build(model.analysis, model.rig)
                    val additions = template.bones.filter { it.role == intent.role && spec.bone(it.id) == null }
                    SkeletonDraftResult(if (additions.isEmpty()) spec else spec.copy(bones = spec.bones + additions))
                }
            }
            is SkeletonDraftIntent.CreateBone -> {
                val next = spec.withCustomBone(intent.headX, intent.headY, intent.tailX, intent.tailY, intent.parentId)
                SkeletonDraftResult(next, if (next == spec) null else setOf(next.bones.last().id))
            }
            is SkeletonDraftIntent.RemoveBone -> {
                val removed = bone(intent.boneId)
                require(!removed.role.anchor && !removed.role.body) { "Body and anchor bones cannot be removed" }
                SkeletonDraftResult(spec.withoutBone(removed.id), setOfNotNull(removed.parentId))
            }
            is SkeletonDraftIntent.Rename -> {
                bone(intent.boneId)
                SkeletonDraftResult(spec.withBoneRenamed(intent.boneId, intent.name))
            }
            is SkeletonDraftIntent.Parent -> {
                bone(intent.boneId)
                SkeletonDraftResult(spec.withBoneParent(intent.boneId, intent.parentId, intent.connect))
            }
            is SkeletonDraftIntent.BlendWidth -> {
                require(intent.width == null || intent.width.isFinite()) { "Blend width must be finite" }
                SkeletonDraftResult(spec.withBone(bone(intent.boneId).copy(blendWidth = intent.width?.coerceAtLeast(0f))))
            }
            is SkeletonDraftIntent.Limits -> {
                require(intent.min.isFinite() && intent.max.isFinite()) { "Bone limits must be finite" }
                SkeletonDraftResult(spec.withBone(bone(intent.boneId).copy(minAngle = intent.min.coerceIn(-180f, 0f),
                    maxAngle = intent.max.coerceIn(0f, 180f))))
            }
            is SkeletonDraftIntent.Ik -> SkeletonDraftResult(spec.withBone(bone(intent.boneId).copy(ik = intent.settings)))
            is SkeletonDraftIntent.Bind -> {
                intent.drawableIds.forEach(::drawable)
                SkeletonDraftResult(spec.withDrawablesBound(intent.drawableIds, intent.boneId))
            }
            is SkeletonDraftIntent.SymmetryAxis -> {
                require(intent.x.isFinite()) { "Symmetry axis must be finite" }
                SkeletonDraftResult(spec.copy(symmetryAxisX = intent.x))
            }
            is SkeletonDraftIntent.Enabled -> SkeletonDraftResult(spec.copy(enabled = intent.enabled))
            is SkeletonDraftIntent.PaintWeights -> {
                drawable(intent.drawableId)
                require(intent.boneId in SkeletonManualWeights.treeIds(spec, intent.drawableId)) {
                    "Bone ${intent.boneId} does not skin ${intent.drawableId}"
                }
                require(intent.points.isNotEmpty() || intent.capture) { "Give at least one brush point" }
                require(intent.points.all { it.first.isFinite() && it.second.isFinite() }) { "Brush points must be finite" }
                val stored = spec.manualWeights[intent.drawableId]
                val base = if (intent.capture || stored == null) requireNotNull(SkeletonManualWeights.capture(spec, puppet, intent.drawableId)) {
                    "${intent.drawableId} has no skinned mesh"
                } else stored
                val painted = intent.points.fold(base) { map, (x, y) ->
                    SkeletonManualWeights.paint(spec, map, intent.boneId, x, y, intent.radius, intent.strength, intent.mode, intent.replaceValue)
                }
                SkeletonDraftResult(spec.withManualWeights(intent.drawableId, painted))
            }
            is SkeletonDraftIntent.CleanupWeights -> {
                drawable(intent.drawableId)
                val map = requireNotNull(SkeletonManualWeights.capture(spec, puppet, intent.drawableId)) { "${intent.drawableId} has no skinned mesh" }
                val fallback = requireNotNull(SkeletonManualWeights.capture(spec.withManualWeights(intent.drawableId, null), puppet, intent.drawableId))
                SkeletonDraftResult(spec.withManualWeights(intent.drawableId,
                    SkeletonManualWeights.cleanup(spec, map, fallback, intent.influences, intent.cutoff)))
            }
            is SkeletonDraftIntent.ClearWeights -> {
                drawable(intent.drawableId)
                SkeletonDraftResult(spec.withManualWeights(intent.drawableId, null))
            }
            is SkeletonDraftIntent.TransferWeights -> {
                drawable(intent.sourceId); drawable(intent.targetId)
                val transfer = requireNotNull(transfer(spec, model, intent)) { "${intent.sourceId} and ${intent.targetId} cannot exchange weights" }
                require(transfer.matched > 0) { "No target vertex received a mapped weight" }
                SkeletonDraftResult(spec.withManualWeights(intent.targetId, transfer.map))
            }
            is SkeletonDraftIntent.Restore -> SkeletonDraftResult(intent.spec)
        }
        return result.copy(spec = validated(result.spec, puppet.drawables.mapTo(HashSet()) { it.id.raw }))
    }

    /** Folds [intents] in order; any failure leaves the caller's draft as it was. */
    fun applyAll(spec: SkeletonSpec, model: RigPreviewModel, intents: List<SkeletonDraftIntent>,
                 failure: (Int, Exception) -> Exception = { _, error -> error }): SkeletonDraftResult {
        var result = SkeletonDraftResult(spec)
        for ((index, intent) in intents.withIndex()) {
            val next = try { apply(result.spec, model, intent) } catch (error: Exception) { throw failure(index, error) }
            result = SkeletonDraftResult(next.spec, next.selected ?: result.selected)
        }
        return result
    }

    /** The invariants a stored armature must keep, whether it was drafted or written whole. */
    fun validated(spec: SkeletonSpec, drawableIds: Set<String>? = null): SkeletonSpec {
        require(spec.bones.size <= MAX_BONES) { "A skeleton may contain at most $MAX_BONES bones" }
        require(spec.bones.map { it.id }.distinct().size == spec.bones.size) { "Bone IDs must be unique" }
        require(spec.bones.flatMap { it.drawableIds }.distinct().size == spec.bones.sumOf { it.drawableIds.size }) {
            "A drawable can be bound to only one bone"
        }
        require(spec.bones.filterNot { it.role.anchor }.map { it.parameterId }.distinct().size ==
            spec.bones.count { !it.role.anchor }) { "Bones must have distinct parameter IDs" }
        require(spec.bones.all { it.direction == 1f || it.direction == -1f }) { "Bone direction must be +1 or -1" }
        if (drawableIds != null) require(spec.bones.flatMap { it.drawableIds }.all { it in drawableIds }) {
            "Skeleton binding references an unknown drawable"
        }
        return spec
    }

    /** Source-to-target bone pairs for a weight copy: explicit [overrides] first, then mirror partners, IDs or role/chain matches. */
    fun weightMapping(spec: SkeletonSpec, sourceId: String, targetId: String, overrides: Map<String, String> = emptyMap(),
                      mirror: Boolean = false): Map<String, String> {
        val targetIds = SkeletonManualWeights.treeIds(spec, targetId)
        val sourceIds = SkeletonManualWeights.treeIds(spec, sourceId)
        return sourceIds.mapNotNull { id ->
            if (id in overrides) return@mapNotNull overrides[id]?.takeIf { it in targetIds }?.let { id to it }
            val bone = spec.bone(id) ?: return@mapNotNull null
            val side = when (bone.side) { Side.LEFT -> Side.RIGHT; Side.RIGHT -> Side.LEFT; Side.NONE -> Side.NONE }
            val partner = bone.mirrorId?.takeIf { mirror && it in targetIds }
                ?: id.takeIf { !mirror && it in targetIds }
                ?: targetIds.filter { t -> spec.bone(t)?.let { b -> b.role == bone.role && b.chainIndex == bone.chainIndex &&
                    (!mirror || b.side == side) } == true }.singleOrNull()
            partner?.let { id to it }
        }.toMap()
    }

    /** The weights a transfer would write, or null when the two meshes cannot exchange them in [SkeletonDraftIntent.TransferWeights.mode]. */
    fun transfer(spec: SkeletonSpec, model: RigPreviewModel, intent: SkeletonDraftIntent.TransferWeights): SkeletonManualWeights.Transfer? {
        val puppet = model.rig.puppet
        require(intent.tolerance.isFinite() && intent.tolerance >= 0f) { "Transfer tolerance must be finite and nonnegative" }
        val source = SkeletonManualWeights.capture(spec, puppet, intent.sourceId) ?: return null
        val target = SkeletonManualWeights.capture(spec.withManualWeights(intent.targetId, null), puppet, intent.targetId) ?: return null
        if (intent.mode == SkeletonWeightTransferMode.TOPOLOGY &&
            (source.weights.size != target.weights.size || source.triangles != target.triangles)) return null
        return SkeletonManualWeights.transfer(source, target, weightMapping(spec, intent.sourceId, intent.targetId, intent.mapping, intent.mirror),
            intent.mode, intent.tolerance, if (intent.mirror) spec.symmetryAxisX ?: puppet.canvasWidth / 2f else null)
    }

    /** Opposite-side drawables, matched only when their semantic/name match identifies exactly one. */
    fun mirrorDrawables(model: RigPreviewModel): Map<String, String> {
        val layers = model.analysis.layers.associateBy { it.source.id.raw }
        val byDrawable = model.rig.puppet.drawables.mapNotNull { d ->
            layers[model.rig.layerIdByDrawableId[d.id.raw] ?: d.id.raw]?.let { d.id.raw to it }
        }.toMap()
        fun neutral(name: String) = name.lowercase().replace(Regex("left|right|左|右|[_. ]l\\b|[_. ]r\\b"), "")
        return byDrawable.mapNotNull { (id, layer) ->
            val side = layer.semantic.side
            if (side == Side.NONE) return@mapNotNull null
            val matches = byDrawable.filter { (_, other) ->
                other.semantic.side != side && other.semantic.side != Side.NONE &&
                other.semantic.tag == layer.semantic.tag && other.semantic.variant == layer.semantic.variant &&
                other.semantic.type == layer.semantic.type && other.semantic.parameter == layer.semantic.parameter &&
                other.semantic.switchId == layer.semantic.switchId
            }
            val named = matches.filter { (_, other) -> neutral(other.source.name) == neutral(layer.source.name) }
            val target = (if (named.isNotEmpty()) named else matches).keys.singleOrNull() ?: return@mapNotNull null
            id to target
        }.toMap()
    }
}
