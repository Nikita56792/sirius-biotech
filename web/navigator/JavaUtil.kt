package java.util

// Small platform adapters for the JVM APIs used by the shared core.
class Locale { companion object { val US = Locale() } }
class TimeZone { companion object { fun getTimeZone(name: String) = TimeZone() } }
class Calendar {
    var timeInMillis: Long = kotlin.js.Date.now().toLong()
    fun get(field: Int): Int {
        val d = kotlin.js.Date(timeInMillis.toDouble() + 3 * 3600_000).asDynamic()
        return (when (field) { YEAR -> d.getUTCFullYear(); MONTH -> d.getUTCMonth(); DAY_OF_MONTH -> d.getUTCDate()
            DAY_OF_WEEK -> (d.getUTCDay() as Int) + 1; HOUR_OF_DAY -> d.getUTCHours(); else -> d.getUTCMinutes() }) as Int
    }
    companion object {
        const val YEAR = 1; const val MONTH = 2; const val DAY_OF_MONTH = 3
        const val DAY_OF_WEEK = 4; const val HOUR_OF_DAY = 5; const val MINUTE = 6
        fun getInstance(zone: TimeZone) = Calendar()
    }
}
object Arrays { fun fill(array: ByteArray, value: Int) { array.fill(value.toByte()) } }
class BitSet(size: Int = 0) {
    private var words = IntArray((size + 31) / 32)
    fun get(i: Int): Boolean = i >= 0 && (i ushr 5) < words.size && words[i ushr 5] and (1 shl (i and 31)) != 0
    fun set(i: Int) {
        val w = i ushr 5
        if (w >= words.size) words = words.copyOf(maxOf(w + 1, words.size * 2, 32))
        words[w] = words[w] or (1 shl (i and 31))
    }
    fun set(from: Int, until: Int) { for (i in from until until) set(i) }
}
// java.util.Random's exact 48-bit sequence (voice variant parity, deterministic tests).
class Random(seed: Long) {
    private var state = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)
    private fun next(bits: Int): Int { state = (state * 0x5DEECE66DL + 11) and ((1L shl 48) - 1); return (state ushr (48 - bits)).toInt() }
    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)).toDouble() / (1L shl 53).toDouble()
}
