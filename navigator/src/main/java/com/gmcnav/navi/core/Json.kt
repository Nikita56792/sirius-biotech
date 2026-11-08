package com.gmcnav.navi.core

/**
 * Minimal JSON reader (no Android dependency, so the core can run in plain JVM tests).
 * Objects -> LinkedHashMap<String, Any?>, arrays -> ArrayList<Any?>, numbers -> Double, plus String/Boolean/null.
 */
object Json {
    fun parse(s: String): Any? = Parser(s).run { val v = value(); ws(); v }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] <= ' ') i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c in '0'..'9') num() else error("bad json at $i: '$c'")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); i++ /* : */
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                i++; return m
            }
        }
        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return a }
            while (true) {
                a.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                i++; return a
            }
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(st, i).toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Any?.arr(): List<Any?> = this as List<Any?>
fun Any?.dbl(): Double = (this as Number).toDouble()
fun Any?.int(): Int = (this as Number).toInt()
fun Any?.str(): String = this as String
fun Any?.strs(): List<String> = (this as? List<*>)?.map { it?.toString() ?: "" } ?: emptyList()

/** Escapes a string for JSON output. */
fun jsonStr(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) when (c) {
        '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n")
        else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
    }
    return sb.append('"').toString()
}
