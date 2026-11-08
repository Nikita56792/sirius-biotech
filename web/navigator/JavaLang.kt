package java.lang

object Long {
    fun bitCount(v: kotlin.Long): Int {
        fun count(value: Int): Int {
            var x = value
            x -= (x ushr 1) and 0x55555555
            x = (x and 0x33333333) + ((x ushr 2) and 0x33333333)
            x = (x + (x ushr 4)) and 0x0f0f0f0f
            return (x * 0x01010101) ushr 24
        }
        return count(v.toInt()) + count((v ushr 32).toInt())
    }
}
