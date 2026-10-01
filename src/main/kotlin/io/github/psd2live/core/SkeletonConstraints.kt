package io.github.psd2live.core

import kotlinx.serialization.json.*

data class SkeletonIkSettings(val chainLength: Int = 3, val iterations: Int = 32,
    val tolerancePx: Float = 0.05f, val bendDirection: Int = 0) {
    init {
        require(chainLength in 1..32 && iterations in 1..256)
        require(tolerancePx.isFinite() && tolerancePx in 0.001f..10f && bendDirection in -1..1)
    }
    fun toJson() = buildJsonObject { put("chainLength", chainLength); put("iterations", iterations); put("tolerancePx", tolerancePx); put("bendDirection", bendDirection) }
    companion object {
        fun fromJson(o: JsonObject) = SkeletonIkSettings(o["chainLength"]?.jsonPrimitive?.int ?: 3,
            o["iterations"]?.jsonPrimitive?.int ?: 32, o["tolerancePx"]?.jsonPrimitive?.float ?: 0.05f,
            o["bendDirection"]?.jsonPrimitive?.int ?: 0)
    }
}

data class SkeletonIkTarget(val x: Float, val y: Float, val enabled: Boolean = true) {
    init { require(x.isFinite() && y.isFinite()) }
    fun toJson() = buildJsonObject { put("x", x); put("y", y); put("enabled", enabled) }
    companion object {
        fun fromJson(o: JsonObject) = SkeletonIkTarget(o.getValue("x").jsonPrimitive.float,
            o.getValue("y").jsonPrimitive.float, o["enabled"]?.jsonPrimitive?.boolean ?: true)
    }
}
