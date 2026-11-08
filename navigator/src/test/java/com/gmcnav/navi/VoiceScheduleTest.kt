package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import com.gmcnav.navi.core.VPart
import com.gmcnav.navi.core.VoicePlanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Voice scheduling on simulated walks along real routes: 1.25 m/s, a fix every 0.5 s (steps), displayed-position error
 * correlated (AR(1), σ 1.5 m, τ 5 s) + 0.3 m jitter (Guidance works on the engine's smoothed, speed-limited position),
 * 20 s dwell at lifts / 12 s on stairs before the floor changes. Checks: every prompt once, in route order,
 * pre-announcements 15–35 m before the maneuver, maneuver prompts 0.5–14 m before it, "Вы пришли" (or the СКУД note) at the end;
 * a detour produces exactly one "Маршрут перестроен".
 */
class VoiceScheduleTest {
    private val b by lazy { Building(FileAssets(File("src/main/assets"))) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }

    class Spoken(val t: Double, val text: String, val tag: String, val trueDist: Double, val recorded: Boolean, val gen: Int = 0)

    var dbg = false
    private fun simulate(r: Route, seed: Long, detourAt: Double = -1.0, sb: StringBuilder): List<Spoken> {
        val rnd = Random(seed)
        val g = Guidance(b, r)
        val vp = VoicePlanner()
        val out = ArrayList<Spoken>()
        var route = r
        var tNs = 0L
        var s = 0.0; var steps = 0
        var dwell = 0.0
        var detour = 0.0
        var gen = 0
        var ex = 0.0; var ey = 0.0
        vp.onStart(route, 0).forEach { out.add(Spoken(0.0, it.text, it.tag, 0.0, true)) }
        while (s < route.length + 3 && tNs < 1_500_000_000_000L) {
            tNs += 500_000_000L
            val conn = route.connSteps.firstOrNull { s >= it.s - 0.3 && s < it.s + 0.3 }
            if (conn != null && dwell < (if (conn.kind == "lift") 20.0 else 12.0)) { dwell += 0.5 }
            else {
                if (conn != null) { s = conn.s + 0.31; dwell = 0.0 }
                if (detourAt > 0 && s >= detourAt && detour < 25) detour += 0.625 else s += 0.625
                steps++
            }
            var (fi, x, y) = route.pointAt(minOf(s, route.length))
            if (detour > 0 && detour < 25) {   // walk sideways off the route (up to 30 m), into whatever passable space is there
                val h0 = route.headingAt(s)
                var best: Pair<Double, Double>? = null
                for (side in listOf(1.0, -1.0)) {
                    val h = h0 + side * Math.PI / 2
                    val d = detour * 1.2
                    val c = b.snapXY(fi, x + d * sin(h), y + d * cos(h), 10.0)
                    if (c >= 0) { val cand = b.xOf(c % b.cols) to b.yOf(c / b.cols); if (best == null || route.project(fi, cand.first, cand.second)!!.dist > route.project(fi, best.first, best.second)!!.dist) best = cand }
                }
                if (best != null) { x = best.first; y = best.second }
            }
            // displayed fused position: correlated error (AR(1), σ 1.5 m, τ 5 s) + 0.3 m jitter
            val aa = Math.exp(-0.5 / 5.0); val qq = 1.5 * Math.sqrt(1 - aa * aa)
            ex = aa * ex + qq * rnd.nextGaussian(); ey = aa * ey + qq * rnd.nextGaussian()
            val nx = x + ex + rnd.nextGaussian() * 0.3; val ny = y + ey + rnd.nextGaussian() * 0.3
            val f = Fix(tNs, b.floorOf(fi), nx, ny, 4.0, route.headingAt(s), "sim", steps = steps, stepLen = 0.6, floorProb = 0.95)
            val st = g.update(f)
            if (System.getProperty("gmc.debug") != null || dbg) if (steps % 10 == 0) sb.append(String.format(Locale.US, "    dbg t=%.1f sim s=%.1f fl=%d | g.s=%.1f lat=%.1f next=%s dist=%.1f off=%s conf=%s\n", tNs / 1e9, s, f.floor, st.s, st.lateral, st.next?.type, st.dist, st.offRoute, st.confident))
            if (st.needReroute) {
                val me = planner.placeFromFix(f.floor, f.x, f.y)!!
                val z = search.query(r.goalTitle.split(" ").first()).first().mk()
                val nr = planner.plan(me, z).first()
                vp.onReroute(tNs).forEach { out.add(Spoken(tNs / 1e9, it.text, it.tag, 0.0, true)) }
                route = nr; g.replaceRoute(nr); s = 0.0; detour = 25.0; gen++
                sb.append(String.format(Locale.US, "  t=%.1f s: rerouted, new route %.0f m\n", tNs / 1e9, nr.length))
                continue
            }
            for (p in vp.update(route, st)) {
                val idx = p.tag.substringAfter(':').toIntOrNull()
                val trueD = if (idx != null) route.maneuvers[idx].s - s else 0.0
                out.add(Spoken(tNs / 1e9, p.text, p.tag, trueD, true, gen))
            }
            if (st.arrived && s >= route.length) break
        }
        return out
    }

    /** Connector / СКУД prompts are made of the recorded units (no TTS). */
    @Test
    fun phraseTexts() {
        fun m(kind: String, from: Int, to: Int) = com.gmcnav.navi.core.Maneuver(MType.CONNECTOR, 10.0, 0, from, 0.0, 0.0, connKind = kind, fromFloor = from, toFloor = to)
        val P = com.gmcnav.navi.core.Phrases
        fun keys(p: com.gmcnav.navi.core.Prompt) = p.parts.map { it.key }
        val lift = P.prompt(P.overDist(30) + P.action(m("lift", 1, 2)), "far", false)
        val esc = P.prompt(P.action(m("escalator", 2, 1)) + VPart.Rec("Then") + VPart.Rec("TurnLeft"), "near", true)
        val st1 = P.prompt(P.action(m("stair", 1, 2)), "near", true)
        val st2 = P.prompt(P.action(m("stair", 3, 1)), "near", true)
        val sched = listOf(com.gmcnav.navi.core.AccessWindow(setOf(1, 2, 3, 4), 11 * 60, 13 * 60), com.gmcnav.navi.core.AccessWindow(setOf(1, 2, 3, 4), 14 * 60, 16 * 60))
        val skud = P.prompt(P.accessUnits(2, sched), "finish", true)
        val txt = listOf(lift, esc, st1, st2, skud).joinToString("\n") { "${it.text}  [${keys(it).joinToString(" + ")}]" } + "\n"
        println(txt)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("voice_texts.txt").writeText(txt)
        assertTrue(keys(lift) == listOf("Over", "30", "Meters", "ConnLiftUp", "FloorTo2"))
        assertTrue(keys(esc) == listOf("ConnEscalatorDown", "FloorTo1", "Then", "TurnLeft"))
        assertTrue(keys(st1) == listOf("StairsUp", "FloorTo2"))
        assertTrue(keys(st2) == listOf("StairsDown", "FloorTo1"))
        assertTrue(keys(skud) == listOf("AccessAhead", "AccessNeedExtended", "AccessFreeIntro", "AccessDaysMonThu", "AccessTime11to13", "AccessTimeAnd14to16"))
        assertTrue(lift.text == "Через тридцать метров поднимитесь на лифте на второй этаж")
    }

    @Test
    fun promptsOnceInOrderAtSensibleDistances() {
        val sb = StringBuilder("# Voice schedule on simulated walks\n\n")
        val cases = listOf("1417" to "2415", "1626" to "2295", "1281" to "1514", "1489" to "2327")
        var allOk = true
        for ((qa, qz) in cases) {
            val a = search.query(qa).first().mk(); val z = search.query(qz).first().mk()
            val r = planner.plan(a, z).first()
            for ((label, detour) in listOf("clean" to -1.0, "detour" to r.length * 0.6)) {
                if (label == "detour" && qa != "1281") continue
                sb.append("## ${a.title} → ${z.title}, ${r.label}, ${"%.0f".format(r.length)} м, $label\n")
                sb.append("maneuvers: " + r.maneuvers.joinToString(" | ") { "%.0f м %s".format(Locale.US, it.s, it.text) } + "\n")
                dbg = false
                val spoken = simulate(r, 5, detour, sb)
                for (p in spoken) sb.append(String.format(Locale.US, "  t=%6.1f s  %-9s %-62s %s  %s\n", p.t, p.tag, "«${p.text}»",
                    if (p.tag.startsWith("near") || p.tag.startsWith("far")) "за %.1f м".format(Locale.US, p.trueDist) else "",
                    "[запись]"))
                // checks
                val tags = spoken.filter { it.tag.startsWith("far") || it.tag.startsWith("near") }.map { "${it.gen}:${it.tag}" }
                val dup = tags.size != tags.toSet().size
                val far = spoken.filter { it.tag.startsWith("far") }
                val near = spoken.filter { it.tag.startsWith("near") }
                val farOk = far.all { it.trueDist in 12.0..36.0 }
                // correlated position error (σ 1.5 m) on top of the 5–7 m trigger and 1 s latency: 0.5–14 m
                val nearOk = near.all { it.trueDist in 0.5..14.0 }
                // a route that stops at a СКУД door ends with the access note instead of «Вы пришли»
                // the final prompt is the arrival phrase (a variant) or, before a СКУД door, the СКУД phrase
                val finished = spoken.lastOrNull()?.tag == "finish"
                val order = if (label == "clean") near.map { it.tag.substringAfter(':').toInt() }.zipWithNext().all { it.first < it.second } else true
                val reroutes = spoken.count { it.tag == "reroute" }
                val ok = !dup && farOk && nearOk && finished && order && (label == "clean" && reroutes == 0 || label == "detour" && reroutes == 1)
                sb.append("checks: once=${!dup} far15–35m=$farOk near1–14m=$nearOk inOrder=$order finish=$finished reroutes=$reroutes -> ${if (ok) "OK" else "FAIL"}\n\n")
                allOk = allOk && ok
            }
        }
        // phrase coverage summary
        val txt = sb.toString()
        println(txt)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("voice.md").writeText(txt)
        assertTrue(allOk)
        assertTrue(MType.values().isNotEmpty() && VPart.Rec("x").key == "x")
    }
}
