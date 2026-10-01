package io.github.psd2live.core

/** Structural edits always return a complete valid armature, with old IDs retained where possible. */
object SkeletonAuthoring {
    data class Result(val spec: SkeletonSpec, val selected: Set<String>)

    private fun fresh(used: MutableSet<String>): String = generateSequence(1) { it + 1 }
        .map { "custom_$it" }.first { used.add(it) }

    /** Copy bindings are transferred, so one drawable never gains two competing owners. */
    fun duplicate(spec: SkeletonSpec, ids: Set<String>, includeDescendants: Boolean = false,
        dx: Float = 20f, dy: Float = 20f, transferBindings: Boolean = false,
        mirrorAxis: Float? = null, drawableMirrors: Map<String, String> = emptyMap(), suffix: String = " copy"): Result {
        require(listOf(dx, dy).all(Float::isFinite) && (mirrorAxis == null || mirrorAxis.isFinite()))
        val selected = ids + if (includeDescendants) ids.flatMap(spec::descendants) else emptyList()
        val source = spec.topological().filter { it.id in selected }
        if (source.isEmpty()) return Result(spec, emptySet())
        val used = spec.bones.mapTo(mutableSetOf()) { it.id }
        val mapping = source.associate { it.id to fresh(used) }
        fun mirroredSide(side: Side) = when (side) { Side.LEFT -> Side.RIGHT; Side.RIGHT -> Side.LEFT; Side.NONE -> Side.NONE }
        val additions = source.map { b ->
            val id = mapping.getValue(b.id)
            val parentId = mapping[b.parentId] ?: if (mirrorAxis != null) spec.bone(b.parentId ?: "")?.mirrorId ?: b.parentId else b.parentId
            b.copy(id = id, name = b.name + suffix, parentId = parentId,
                // Only the original torso/head carries the built-in body/face rig.
                role = if (b.role.body || b.role.anchor) BoneRole.CUSTOM else b.role,
                side = if (mirrorAxis != null) mirroredSide(b.side) else b.side,
                direction = if (mirrorAxis != null) -b.direction else b.direction,
                headX = if (mirrorAxis != null) 2f * mirrorAxis - b.headX else b.headX + dx,
                tailX = if (mirrorAxis != null) 2f * mirrorAxis - b.tailX else b.tailX + dx,
                headY = if (mirrorAxis != null) b.headY else b.headY + dy,
                tailY = if (mirrorAxis != null) b.tailY else b.tailY + dy,
                parameterOverride = "ParamSkel_$id", mirrorId = if (mirrorAxis != null) b.id else null,
                connected = if (b.parentId in mapping) b.connected else false,
                drawableIds = when {
                    mirrorAxis != null -> b.drawableIds.mapNotNull(drawableMirrors::get).distinct()
                    transferBindings -> b.drawableIds
                    else -> emptyList()
                })
        }
        val transferred = additions.flatMap { it.drawableIds }.toSet()
        val originals = spec.bones.map { b ->
            var next = b.copy(drawableIds = b.drawableIds.filterNot { it in transferred })
            // Break an earlier partner link when replacing it, preserving reciprocal pairs.
            if (mirrorAxis != null && b.mirrorId in selected) next = next.copy(mirrorId = null)
            if (mirrorAxis != null && b.id in mapping) next = next.copy(mirrorId = mapping.getValue(b.id))
            next
        }
        val copiedTargets = source.mapNotNull { b -> spec.ikTargets[b.id]?.let { target ->
            mapping.getValue(b.id) to target.copy(x = if (mirrorAxis != null) 2 * mirrorAxis - target.x else target.x + dx,
                y = if (mirrorAxis != null) target.y else target.y + dy)
        } }.toMap()
        val manual = spec.manualWeights.toMutableMap()
        val weightMapping = spec.bones.associate { it.id to (if (mirrorAxis != null) it.mirrorId ?: it.id else it.id) } + mapping
        for (b in source) for (drawable in b.drawableIds) {
            val weights = spec.manualWeights[drawable] ?: continue
            val target = if (mirrorAxis != null) drawableMirrors[drawable] else drawable.takeIf { transferBindings }
            if (target == null) continue
            var copied = weights.remapBones(weightMapping)
            if (mirrorAxis != null) copied = copied.copy(positions = copied.positions.mapIndexed { i, v -> if (i % 2 == 0) 2 * mirrorAxis - v else v })
            manual[target] = copied
        }
        return Result(spec.copy(bones = originals + additions, symmetryAxisX = mirrorAxis ?: spec.symmetryAxisX,
            ikTargets = spec.ikTargets + copiedTargets, manualWeights = manual), mapping.values.toSet())
    }

    fun subdivide(spec: SkeletonSpec, id: String, segments: Int): Result {
        require(segments in 2..16)
        val source = spec.bone(id) ?: return Result(spec, emptySet())
        require(!source.role.anchor && !source.role.body && source.length / segments >= 1f) { "Bone cannot be subdivided" }
        val used = spec.bones.mapTo(mutableSetOf()) { it.id }
        val ids = listOf(id) + List(segments - 1) { fresh(used) }
        fun x(t: Float) = source.headX + (source.tailX - source.headX) * t
        fun y(t: Float) = source.headY + (source.tailY - source.headY) * t
        val chain = ids.mapIndexed { i, next -> source.copy(id = next,
            name = if (i == 0) source.name else "${source.name} ${i + 1}",
            parentId = if (i == 0) source.parentId else ids[i - 1],
            role = if (i == 0) source.role else BoneRole.CUSTOM,
            headX = x(i.toFloat() / segments), headY = y(i.toFloat() / segments),
            tailX = x((i + 1f) / segments), tailY = y((i + 1f) / segments),
            drawableIds = if (i == 0) source.drawableIds else emptyList(),
            connected = if (i == 0) source.connected else true,
            parameterOverride = if (i == 0) source.parameterOverride else "ParamSkel_$next", mirrorId = null) }
        val others = spec.bones.filterNot { it.id == id }.map { b ->
            var next = if (b.parentId == id) b.copy(parentId = ids.last()) else b
            if (b.mirrorId == id) next = next.copy(mirrorId = null)
            next
        }
        val targets = (spec.ikTargets - id) + (spec.ikTargets[id]?.let { mapOf(ids.last() to it) } ?: emptyMap())
        val affected = spec.manualWeights.filterValues { map -> map.weights.any { id in it } }.keys
        return Result(spec.copy(bones = others + chain, ikTargets = targets, manualWeights = spec.manualWeights - affected), ids.toSet())
    }

    /** Merge a connected non-branching child into its parent; bindings and grandchildren survive. */
    fun canDissolve(spec: SkeletonSpec, id: String): Boolean {
        val child = spec.bone(id) ?: return false
        val parent = spec.bone(child.parentId ?: "") ?: return false
        return !child.role.anchor && !child.role.body && !parent.role.anchor && !parent.role.body &&
            spec.isConnected(id) && spec.children(parent.id).size == 1 &&
            kotlin.math.hypot(child.tailX - parent.headX, child.tailY - parent.headY) >= 1f
    }

    fun dissolve(spec: SkeletonSpec, id: String): Result {
        require(canDissolve(spec, id)) { "Dissolve requires a connected child with an unbranched limb parent" }
        val child = spec.bone(id)!!; val parent = spec.bone(child.parentId!!)!!
        val bones = spec.bones.filterNot { it.id == id }.map { b ->
            var next = when {
                b.id == parent.id -> b.copy(tailX = child.tailX, tailY = child.tailY,
                    drawableIds = (b.drawableIds + child.drawableIds).distinct(), mirrorId = null)
                b.parentId == id -> b.copy(parentId = parent.id)
                else -> b
            }
            if (next.mirrorId == id || next.mirrorId == parent.id) next = next.copy(mirrorId = null)
            next
        }
        val targets = (spec.ikTargets - id) + (spec.ikTargets[id]?.let { mapOf(parent.id to it) } ?: emptyMap())
        return Result(spec.copy(bones = bones, ikTargets = targets,
            manualWeights = spec.manualWeights.mapValues { it.value.remapBones(mapOf(id to parent.id)) }), setOf(parent.id))
    }

    /** Mirrors only partners outside the explicit selection, avoiding double transforms. */
    fun synchronizeMirrors(spec: SkeletonSpec, changed: Set<String>): SkeletonSpec {
        val axis = spec.symmetryAxisX ?: return spec
        var next = spec
        for (id in changed) {
            val source = next.bone(id) ?: continue
            val partner = source.mirrorId?.takeIf { it !in changed } ?: continue
            if (next.bone(partner) == null) continue
            next = next.withJointMoved(partner, BoneEnd.HEAD, 2 * axis - source.headX, source.headY)
                .withJointMoved(partner, BoneEnd.TAIL, 2 * axis - source.tailX, source.tailY)
        }
        return next
    }
}
