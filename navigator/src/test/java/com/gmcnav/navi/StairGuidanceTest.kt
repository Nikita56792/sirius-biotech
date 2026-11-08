package com.gmcnav.navi

import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.Router
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.hypot

/**
 * Guidance over the recorded floor changes: a route from the user's position 25 s before the first mark on the new floor to
 * the mark 20 s after it, followed with the replayed position. When does guidance move to the new floor (progress past the
 * connector) relative to that mark — with counted stair steps (Guidance.useClimb) and without (Wi-Fi only) — and does it
 * ask for a reroute meanwhile. Scan every ~4 s (live cadence). Report: stair_guidance.md
 */
class StairGuidanceTest {
    @Test
    fun stairs() {
        val t = ScanBudgetReplayTest()
        val sb = StringBuilder("# Ведение по лестнице: переход на этаж маршрута (скан ~4 с)\n\n| объект | часть | переход | лестница маршрута | без ступеней | со ступенями | перестроения без / со |\n|---|---|---|---|---|---|---|\n")
        var sumOff = 0.0; var sumOn = 0.0; var n = 0; var rrOff = 0; var rrOn = 0
        for (site in listOf(t.gmc, t.r15)) {
            val router = Router(site.b); val planner = Planner(site.b, router)
            for (rp in site.parts) {
                for (k in 1 until rp.markT.size) {
                    if (rp.markFloor[k] == rp.markFloor[k - 1]) continue
                    val tNew = rp.markT[k]
                    val ia = (0 until k).lastOrNull { rp.markT[it] <= tNew - 25_000_000_000L && rp.markFloor[it] == rp.markFloor[k - 1] } ?: continue
                    val iz = (k until rp.markT.size).firstOrNull { rp.markT[it] >= tNew + 20_000_000_000L && rp.markFloor[it] == rp.markFloor[k] } ?: continue
                    if ((k until iz).any { rp.markFloor[it] != rp.markFloor[k] }) continue
                    val a = planner.placeFromFix(rp.markFloor[ia], rp.markX[ia], rp.markY[ia]) ?: continue
                    val z = planner.placeFromFix(rp.markFloor[iz], rp.markX[iz], rp.markY[iz]) ?: continue
                    val route = planner.plan(a, z).firstOrNull() ?: continue
                    val cm = route.maneuvers.firstOrNull { it.type == MType.CONNECTOR } ?: continue
                    if (cm.connKind != "stair") continue
                    val vr = (System.getenv("CLIMB") ?: "5:1.0").split(":").map { it.toDouble() }
                    val res = listOf(false, true).map { climb ->
                        val eng = PositionEngine(site.b, site.radioCopy(), null, seed = 11L + rp.part)
                        val dev = DeviceProfile("cad", scanEvery = 2).Feeder(rp, 11L)
                        var g: Guidance? = null; var passT = Double.NaN; var rr = 0; var lastFixT = -1L
                        for (ev in rp.events()) {
                            if (ev.kind == 3) dev.scan(ev)?.let { eng.onWifi(ev.t, it) } else dev.feed(ev, eng)
                            if (ev.t < rp.markT[ia]) continue
                            if (ev.t > rp.markT[iz]) break
                            val f = eng.fix ?: continue
                            if (f.tNs == lastFixT) continue; lastFixT = f.tNs
                            if (g == null) g = Guidance(site.b, route).also { it.useClimb = climb; it.climbR = vr[0]; it.climbShare = vr[1] }
                            val st = g.update(f)
                            if (st.needReroute) rr++
                            if (System.getenv("STAIR_DEBUG") != null && climb && (st.offRoute || kotlin.math.abs(st.s - cm.s) < 12)) {
                                val cp = cm.conn?.pos
                                val dc = cp?.get(f.floor)?.let { hypot(f.x - it[0], f.y - it[1]) } ?: Double.NaN
                                println(String.format(Locale.US, "DBG p%d %d→%d t%+.1f fixFloor=%d pos=(%.1f,%.1f) dConn=%.1f s=%.1f connS=%.1f off=%s why=%s need=%s acc=%.0f",
                                    rp.part, rp.markFloor[k - 1], rp.markFloor[k], (f.tNs - tNew) / 1e9, f.floor, f.x, f.y, dc, st.s, cm.s, st.offRoute, st.offReason, st.needReroute, f.acc))
                            }
                            if (passT.isNaN() && st.s > cm.s) passT = (f.tNs - tNew) / 1e9
                        }
                        passT to rr
                    }
                    val (off, on) = res
                    sb.append(String.format(Locale.US, "| %s | %d | %d→%d | %s | %s | %s | %d / %d |\n", site.name, rp.part, rp.markFloor[k - 1], rp.markFloor[k],
                        cm.conn?.name ?: "?", fmt(off.first), fmt(on.first), off.second, on.second))
                    if (!off.first.isNaN() && !on.first.isNaN()) { sumOff += off.first; sumOn += on.first; n++ }
                    rrOff += off.second; rrOn += on.second
                }
            }
        }
        sb.append(String.format(Locale.US, "\nСреднее время перехода относительно первой отметки на новом этаже: без ступеней %+.1f с, со ступенями %+.1f с (%d переходов); запросов перестроения %d / %d\n",
            sumOff / maxOf(1, n), sumOn / maxOf(1, n), n, rrOff, rrOn))
        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("stair_guidance.md").writeText(sb.toString())
    }
    private fun fmt(v: Double) = if (v.isNaN()) "не перешёл" else String.format(Locale.US, "%+.0f с", v)
}
