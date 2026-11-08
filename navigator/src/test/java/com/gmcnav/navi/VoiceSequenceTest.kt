package com.gmcnav.navi

import com.gmcnav.navi.core.GuidanceState
import com.gmcnav.navi.core.Leg
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Maneuver
import com.gmcnav.navi.core.Prompt
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.VoicePlanner
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The announcement sequence (Yandex-like, pedestrian-scaled) on synthetic straight routes walked at 1.2 m/s:
 *  1) «Держитесь левее» at 10 m, then 160 m straight, a right turn at 170 m, the finish at 200 m;
 *  2) a straight 250 m run to the finish.
 * Report: build/validation/voice_sequence.md
 */
class VoiceSequenceTest {
    private fun route(len: Double, vararg ms: Pair<MType, Double>): Route {
        val leg = Leg(0, 1, doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, len), doubleArrayOf(0.0, len))
        val r = Route("test", len / 1.2, len, listOf(leg), emptyList(), IntArray(0))
        r.maneuvers = listOf(Maneuver(MType.START, 0.0, 0, 1, 0.0, 0.0)) + ms.map { (t, s) -> Maneuver(t, s, 0, 1, 0.0, s) } +
            Maneuver(MType.FINISH, len, 0, 1, 0.0, len)
        return r
    }

    /** (s, tag, text) of everything said while walking the route. */
    private fun walk(r: Route): List<Triple<Double, String, String>> {
        val vp = VoicePlanner()
        val out = ArrayList<Triple<Double, String, String>>()
        vp.onStart(r, 0).forEach { out.add(Triple(0.0, it.tag, it.text)) }
        var s = 0.0; var t = 0L
        while (s <= r.length + 1) {
            t += 500_000_000L
            val st = GuidanceState(t, s, 0, 0.0, s, 0.0, 0.0, null, null, 0.0, r.length - s, (r.length - s) / 1.2, 1.2,
                offRoute = false, needReroute = false, arrived = false, acc = 4.0, confident = true)
            vp.update(r, st).forEach { p: Prompt -> out.add(Triple(s, p.tag, p.text)) }
            s += 0.6
        }
        return out
    }

    @Test
    fun sequences() {
        val sb = StringBuilder("# Announcement sequences (synthetic straight routes, 1.2 m/s)\n\n")
        val r1 = route(200.0, MType.TAKE_LEFT to 10.0, MType.TURN_RIGHT to 170.0)
        val r2 = route(250.0)
        val res = listOf("«Держитесь левее» at 10 m, 160 m straight, right turn at 170 m, finish at 200 m" to walk(r1),
                         "straight 250 m to the finish" to walk(r2))
        for ((name, seq) in res) {
            sb.append("## $name\n")
            seq.forEach { (s, tag, text) -> sb.append(String.format(Locale.US, "- at %5.1f m  %-8s «%s»\n", s, tag.substringBefore(':'), text)) }
            sb.append('\n')
        }
        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("voice_sequence.md").writeText(sb.toString())
        val t1 = res[0].second.map { it.third }
        assertEquals(listOf(
            "Маршрут построен",
            "Держитесь левее",
            "Через сто пятьдесят метров поверните направо",
            "Через сто метров поверните направо",
            "Через тридцать метров поверните направо",
            "Поверните направо",
            "До конца маршрута осталось тридцать метров",
            "Вы пришли"), t1)
        val t2 = res[1].second.map { it.third }
        assertEquals(listOf(
            "Маршрут построен до конца маршрута осталось двести пятьдесят метров",
            "До конца маршрута осталось сто метров",
            "До конца маршрута осталось тридцать метров",
            "Вы пришли"), t2)
    }
}
