package com.gmcnav.navi.core

// Browser facilities only. Routing decisions stay in the Android core sources.
class AssetSource(val texts: dynamic, val grids: dynamic) {
    fun open(path: String): TextAsset = TextAsset(texts[path] as? String ?: error("Missing asset: $path"))
    fun shorts(path: String): ShortArray = grids[path].unsafeCast<ShortArray>()
}
class TextAsset(private val value: String) { fun readText(): String = value }
class ThreadLocal<T> { private var value: T? = null; fun get(): T? = value; fun set(v: T) { value = v } }
object System {
    fun currentTimeMillis(): Long = kotlin.js.Date.now().toLong()
    private var nextId = 0
    fun identityHashCode(value: Any): Int {
        if (value.asDynamic().__sbsIdentity == null) value.asDynamic().__sbsIdentity = ++nextId
        return value.asDynamic().__sbsIdentity as Int
    }
}
object Math {
    const val PI = kotlin.math.PI
    fun toRadians(v: Double) = v * PI / 180.0
    fun toDegrees(v: Double) = v * 180.0 / PI
    fun round(v: Double) = kotlin.math.floor(v + 0.5).toLong()
    fun atan2(y: Double, x: Double) = kotlin.math.atan2(y, x)
    fun cos(v: Double) = kotlin.math.cos(v)
    fun sqrt(v: Double) = kotlin.math.sqrt(v)
    fun hypot(x: Double, y: Double) = kotlin.math.hypot(x, y)
}
fun String.format(vararg args: Any): String {
    var i = if (args.firstOrNull() is java.util.Locale) 1 else 0
    return Regex("%(0?)(\\d*)(?:\\.(\\d+))?([dfx])").replace(this) { m ->
        val v = args[i++] as Number
        val raw = when (m.groupValues[4]) {
            "f" -> v.toDouble().asDynamic().toFixed(m.groupValues[3].toIntOrNull() ?: 6) as String
            "x" -> v.toInt().toString(16)
            else -> v.toInt().toString()
        }
        raw.padStart(m.groupValues[2].toIntOrNull() ?: 0, if (m.groupValues[1] == "0") '0' else ' ')
    }
}
