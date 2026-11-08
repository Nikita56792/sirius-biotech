package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.GridPt
import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.Leg
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.ReplayIndex
import com.gmcnav.navi.core.ReplayPart
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.RouteQuery
import com.gmcnav.navi.core.Router
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.hypot

/**
 * False reroutes on real walks: the recorded sessions are replayed through the positioning engine (full radio map, as
 * deployed) while Guidance follows a "route" that is the TRUE walked path (marks joined by the shortest grid path
 * between consecutive marks, same floor, ≤ 60 s apart). Every needReroute here is a false alarm of the off-route rule
 * (upper bound: the true path between marks is approximated). Report: build/validation/offroute_replay.md
 */
class OffRouteReplayTest {
    private val assets = File("src/main/assets")
    private val replayDir = File(System.getProperty("gmc.replay") ?: "../build_data/replay")
    private val outDir = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }
    private val b by lazy { Building(FileAssets(assets)) }
    private val router by lazy { Router(b).apply { userLevel = 2; rebuildAccess() } }
    private val planner by lazy { com.gmcnav.navi.core.Planner(b, router) }
    private val idx by lazy { ReplayIndex(File(replayDir, "index.json").readText()) }

    private fun clear(fi: Int, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        val n = maxOf(1, (hypot(x1 - x0, y1 - y0) / 0.15).toInt())
        for (i in 1 until n) { val t = i.toDouble() / n; if (!b.passableXY(fi, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)) return false }
        return true
    }

    /** Walked path between two marks on floor fi: straight if clear, else the shortest grid path. */
    private fun between(fi: Int, x0: Double, y0: Double, x1: Double, y1: Double): List<Pair<Double, Double>> {
        if (clear(fi, x0, y0, x1, y1)) return listOf(x0 to y0, x1 to y1)
        val a = b.snapXY(fi, x0, y0, 3.0); val z = b.snapXY(fi, x1, y1, 3.0)
        if (a < 0 || z < 0) return listOf(x0 to y0, x1 to y1)
        val raw = router.route(RouteQuery(listOf(GridPt(fi, a)), listOf(GridPt(fi, z)), emptySet())) ?: return listOf(x0 to y0, x1 to y1)
        val r = router.buildRoute(raw, "")
        if (r.legs.size != 1) return listOf(x0 to y0, x1 to y1)
        val L = r.legs[0]
        return listOf(x0 to y0) + L.xs.indices.map { L.xs[it] to L.ys[it] } + listOf(x1 to y1)
    }

    private fun mkRoute(fi: Int, pts: List<Pair<Double, Double>>): Route {
        val p = ArrayList<Pair<Double, Double>>()
        for (q in pts) if (p.isEmpty() || hypot(q.first - p.last().first, q.second - p.last().second) > 0.05) p.add(q)
        if (p.size == 1) p.add(p[0].first + 0.1 to p[0].second)
        val ss = DoubleArray(p.size); for (i in 1 until p.size) ss[i] = ss[i - 1] + hypot(p[i].first - p[i - 1].first, p[i].second - p[i - 1].second)
        val leg = Leg(fi, b.floorOf(fi), DoubleArray(p.size) { p[it].first }, DoubleArray(p.size) { p[it].second }, ss)
        return Route("truth", ss.last(), ss.last(), listOf(leg), emptyList(), IntArray(0))
    }

    @Test
    fun falseReroutesOnRealWalks() {
        val radio = RadioMap().apply { loadPacked(File(assets, "radio/wifi.json").readText()) }
        val sb = StringBuilder("# False off-route alarms on real walks (guidance along the true path, full radio map)\n\n")
        sb.append("| part | segments | walked m | minutes | false reroute episodes | by reason | per km | time > 6 m off |\n|---|---|---|---|---|---|---|---|\n")
        var totM = 0.0; var totMin = 0.0; var totN = 0; var totOff = 0.0; var totAll = 0.0
        val accAll = ArrayList<Double>(); val accTrig = ArrayList<Double>()
        val reasons = HashMap<String, Int>()
        // per floor: walked m, episodes, announced (the rebuilt route is not the same path -> «Маршрут перестроен»)
        val flM = HashMap<Int, Double>(); val flN = HashMap<Int, Int>(); val flAnn = HashMap<Int, Int>()
        for (e in idx.parts.filter { it.marks >= 3 }) {
            val rp = ReplayPart(File(replayDir, e.file.removePrefix("replay/")).readBytes(), idx.bssids, idx.cellKeys)
            // truth segments: runs of indoor marks on one floor, ≤ 60 s apart
            class Seg(val fi: Int, val t0: Long, val t1: Long, val route: Route)
            val segs = ArrayList<Seg>()
            var i = 0
            while (i < rp.markT.size) {
                var j = i
                while (j + 1 < rp.markT.size && rp.markFloor[j + 1] == rp.markFloor[i] && !rp.markOut[j + 1] && !rp.markOut[i] &&
                    rp.markT[j + 1] - rp.markT[j] <= 60_000_000_000L) j++
                val fi = b.fi(rp.markFloor[i])
                if (j > i && fi >= 0 && !rp.markOut[i]) {
                    val pts = ArrayList<Pair<Double, Double>>()
                    for (k in i until j) pts.addAll(between(fi, rp.markX[k], rp.markY[k], rp.markX[k + 1], rp.markY[k + 1]))
                    val r = mkRoute(fi, pts)
                    if (r.length >= 20) segs.add(Seg(fi, rp.markT[i], rp.markT[j], r))
                }
                i = j + 1
            }
            if (segs.isEmpty()) continue
            val eng = PositionEngine(b, radio, null, seed = 11L + e.part)
            var lastFixT = -1L
            var si = 0; var g: Guidance? = null
            var n = 0; val rs = HashMap<String, Int>(); var armed = true; var offT = 0.0; var allT = 0.0; var lastT = 0L; var ts = 0.0; var errSum = 0.0
            for (ev in rp.events()) {
                rp.feed(ev, eng)
                while (si < segs.size && ev.t > segs[si].t1) { si++; g = null; ts = 0.0 }
                if (si >= segs.size) break
                val sg = segs[si]
                if (ev.t < sg.t0) continue
                val f = eng.fix ?: continue
                if (f.tNs == lastFixT) continue
                lastFixT = f.tNs
                if (g == null) g = Guidance(b, sg.route)
                // true progress: the true position projected onto the truth route near the previous one
                rp.truth(f.tNs)?.takeIf { it.floor == b.floorOf(sg.fi) }?.let { gt -> sg.route.project(sg.fi, gt.x, gt.y, ts - 5, ts + 60)?.let { ts = it.s } }
                val st = g.update(f)
                errSum += hypot(st.dispX - f.x, st.dispY - f.y) * 0
                val dt = if (lastT == 0L) 0.0 else ((f.tNs - lastT) / 1e9).coerceIn(0.0, 5.0); lastT = f.tNs
                allT += dt; if (st.lateral > 6.0) offT += dt
                // one episode per excursion: in the app the new route starts at the fused position
                accAll.add(f.acc)
                if (st.needReroute && armed) {
                    n++; rs.merge(st.offReason, 1, Int::plus); armed = false; accTrig.add(f.acc)
                    val fl = b.floorOf(sg.fi); flN.merge(fl, 1, Int::plus)
                    // what the app does: re-plan from the fused position to the same destination, announce only a different path
                    val me = planner.placeFromFix(f.floor, f.x, f.y); val (_, ex, ey) = sg.route.pointAt(sg.route.length)
                    val dest = planner.placeFromFix(fl, ex, ey)
                    val nr = if (me != null && dest != null) planner.plan(me, dest).firstOrNull() else null
                    // the app's current route ≈ the route planned from the TRUE position to the same destination
                    val gt = rp.truth(f.tNs)
                    val truePl = gt?.let { planner.placeFromFix(it.floor, it.x, it.y) }
                    val old = if (truePl != null && dest != null) planner.plan(truePl, dest).firstOrNull() else null
                    if (nr == null || old == null || !planner.samePath(nr, old, 0.0)) flAnn.merge(fl, 1, Int::plus)
                }
                if (st.needReroute) g.replaceRoute(sg.route, ts)
                if (!armed && st.lateral < 4.0) armed = true
            }
            val walked = segs.sumOf { it.route.length }; val mins = segs.sumOf { (it.t1 - it.t0) / 60e9 }
            totM += walked; totMin += mins; totN += n; rs.forEach { (k, v) -> reasons.merge(k, v, Int::plus) }
            totOff += offT; totAll += allT
            for (sg in segs) flM.merge(b.floorOf(sg.fi), sg.route.length, Double::plus)
            sb.append(String.format(Locale.US, "| %d | %d | %.0f | %.1f | %d | %s | %.1f | %.0f %% |\n", e.part, segs.size, walked, mins, n, rs, n / maxOf(0.001, walked / 1000), 100 * offT / maxOf(1.0, allT)))
        }
        sb.append(String.format(Locale.US, "| all | | %.0f | %.1f | %d | %s | %.1f | %.0f %% |\n\n", totM, totMin, totN, reasons, totN / maxOf(0.001, totM / 1000), 100 * totOff / maxOf(1.0, totAll)))
        sb.append(String.format(Locale.US, "False reroutes: %.1f per km walked, %.1f per hour of walking.\n", totN / maxOf(0.001, totM / 1000), totN / maxOf(0.001, totMin / 60)))
        for (fl in flM.keys.sorted()) sb.append(String.format(Locale.US, "Floor %d: %.0f m walked, %d false off-route episodes (%.1f/km), %d would be announced «Маршрут перестроен» (%.1f/km), the rest replaced silently\n",
            fl, flM.getValue(fl), flN[fl] ?: 0, (flN[fl] ?: 0) / (flM.getValue(fl) / 1000), flAnn[fl] ?: 0, (flAnn[fl] ?: 0) / (flM.getValue(fl) / 1000)))
        fun q(l: List<Double>, p: Double) = l.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * p).toInt()] }
        sb.append(String.format(Locale.US, "Filter accuracy estimate (m): all fixes p25/p50/p75/p90 %.1f/%.1f/%.1f/%.1f; at false alarms %.1f/%.1f/%.1f/%.1f\n",
            q(accAll, .25), q(accAll, .5), q(accAll, .75), q(accAll, .9), q(accTrig, .25), q(accTrig, .5), q(accTrig, .75), q(accTrig, .9)))
        println(sb)
        outDir.resolve("offroute_replay.md").writeText(sb.toString())
    }
}
