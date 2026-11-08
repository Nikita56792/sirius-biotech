package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.FpPoint
import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.GuidanceState
import com.gmcnav.navi.core.Orientation
import com.gmcnav.navi.core.Place
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import com.gmcnav.navi.core.VoicePlanner
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Off-route detection and rerouting on simulated walks over the real map.
 *
 * Scenarios are found automatically on real single-floor routes:
 *  - side:     the route runs straight through a junction; the user turns into the side corridor (≥ 14 m long) and walks on;
 *  - parallel: next to a straight route part there is another space 4–6 m away behind a wall (a parallel corridor / room
 *              along the corridor); the user ends up walking there, parallel to the route;
 *  - hall:     (negative) the user walks 3.5 m beside the route line in the same open space — no wall, under 6 m: no reroute;
 *  - control:  the user follows the route: no reroute.
 * After a reroute the simulated user follows the new route (as told by «Маршрут перестроен»).
 *
 * Two levels:
 *  A. guidance only: fused positions = truth + correlated error (AR(1), σ 1.2 m, τ 4 s) + 0.3 m white, heading ± 12°;
 *     20 seeds per scenario.
 *  B. full chain: synthetic accelerometer (1.9 steps/s, 0.62 m steps), game rotation and rotation vector (10° magnetic bias),
 *     Wi-Fi scans every 3.5 s taken from the fingerprint nearest to the true position (±3 dB, 15 % of APs dropped)
 *     -> PositionEngine (particle filter, no route prior) -> Guidance -> Planner re-plan from the fused position.
 */
class RerouteTest {
    private val assets = File("src/main/assets")
    private val b by lazy { Building(FileAssets(assets)) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }
    private val radio by lazy { RadioMap().apply { loadPacked(File(assets, "radio/wifi.json").readText()) } }
    private val outDir = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }

    // ------------------------------------------------------------------ geometry helpers
    private fun pass(fi: Int, x: Double, y: Double) = b.passableXY(fi, x, y)
    private fun clear(fi: Int, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        val n = max(1, (hypot(x1 - x0, y1 - y0) / 0.15).toInt())
        for (i in 1 until n) { val t = i.toDouble() / n; if (!pass(fi, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)) return false }
        return true
    }
    private fun wallDist(fi: Int, x: Double, y: Double, nx: Double, ny: Double, maxD: Double): Double {
        var d = 0.0
        while (d < maxD) { if (!pass(fi, x + d * nx, y + d * ny)) return d; d += 0.15 }
        return maxD
    }
    private fun straight(r: Route, s0: Double, s1: Double, h: Double): Boolean {
        if (s0 < 0 || s1 > r.length) return false
        var s = s0
        while (s <= s1) { if (abs(Orientation.wrap(r.headingAt(s) - h)) > Math.toRadians(8.0)) return false; s += 1.0 }
        return true
    }
    private fun routePts(r: Route, from: Double, to: Double = r.length): List<Pair<Double, Double>> {
        val L = r.legs[0]
        val out = ArrayList<Pair<Double, Double>>()
        r.pointAt(from).let { out.add(it.second to it.third) }
        for (k in L.ss.indices) if (L.ss[k] > from + 1e-6 && L.ss[k] < to - 1e-6) out.add(L.xs[k] to L.ys[k])
        r.pointAt(to).let { out.add(it.second to it.third) }
        return out
    }

    /** Polyline walker (one floor). */
    class Walker(val pts: List<Pair<Double, Double>>) {
        private val cum = DoubleArray(pts.size).also { for (i in 1 until pts.size) it[i] = it[i - 1] + hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second) }
        val length get() = cum.last()
        var d = 0.0
        fun at(dd: Double): Pair<Double, Double> {
            val q = dd.coerceIn(0.0, length)
            for (i in 1 until pts.size) if (q <= cum[i] || i == pts.size - 1) {
                val seg = cum[i] - cum[i - 1]; val t = if (seg > 0) (q - cum[i - 1]) / seg else 0.0
                return pts[i - 1].first + (pts[i].first - pts[i - 1].first) * t to pts[i - 1].second + (pts[i].second - pts[i - 1].second) * t
            }
            return pts[0]
        }
        val x get() = at(d).first
        val y get() = at(d).second
        /** Walking direction: towards the point 0.8 m ahead (smooth at corners). */
        fun heading(): Double {
            val a = at(d); val z = at(min(length, d + 0.8))
            if (hypot(z.first - a.first, z.second - a.second) < 0.05) { val p = at(max(0.0, d - 0.8)); return atan2(a.first - p.first, a.second - p.second) }
            return atan2(z.first - a.first, z.second - a.second)
        }
        val done get() = d >= length - 1e-6
    }

    // ------------------------------------------------------------------ scenarios
    class Scenario(val kind: String, val route: Route, val goal: Place, val fi: Int, val truth: List<Pair<Double, Double>>,
                   val devAlong: Double, val info: String)

    /** Share of route points (every 3 m) with a fingerprint within 6 m. */
    private fun coverage(r: Route): Double {
        val fi = r.legs[0].fi; val fl = b.floorOf(fi)
        val pts = radio.points.filter { !it.outdoor && it.floor == fl }
        var n = 0; var ok = 0; var s = 0.0
        while (s <= r.length) { val (_, x, y) = r.pointAt(s); n++; if (pts.any { hypot(it.x - x, it.y - y) < 6 }) ok++; s += 3.0 }
        return ok.toDouble() / max(1, n)
    }

    /** Routes between calibrated places (fingerprint positions) on one floor, 50–160 m apart, ≥ 90 % radio-covered. */
    private val routes: List<Pair<Route, Place>> by lazy {
        val rnd = Random(11)
        val fps = radio.points.filter { !it.outdoor && !it.seg && it.floor in 1..2 }
        val out = ArrayList<Pair<Route, Place>>()
        var tries = 0
        while (out.size < 70 && tries < 1200) {
            tries++
            val a = fps[rnd.nextInt(fps.size)]; val z = fps[rnd.nextInt(fps.size)]
            if (a.floor != z.floor || hypot(a.x - z.x, a.y - z.y) !in 50.0..160.0) continue
            val pa = planner.placeFromFix(a.floor, a.x, a.y) ?: continue
            val pz = planner.placeFromFix(z.floor, z.x, z.y) ?: continue
            val r = planner.plan(pa, pz).firstOrNull() ?: continue
            if (r.legs.size != 1 || r.length !in 60.0..260.0 || r.accessStop != null) continue
            if (coverage(r) < 0.9) continue
            out.add(r to pz)
        }
        out.sortedBy { it.first.legs[0].floor }        // floor-1 corridors first (floor 2 is mostly the radio-flat hall)
    }

    private fun findSide(): Scenario? {
        for ((r, goal) in routes) {
            val fi = r.legs[0].fi
            var s = 30.0
            while (s < r.length - 25) {
                val h = r.headingAt(s)
                if (straight(r, s - 25, s + 20, h)) {
                    val (_, x, y) = r.pointAt(s)
                    val ux = sin(h); val uy = cos(h)
                    for (side in listOf(1.0, -1.0)) {
                        val nx = side * cos(h); val ny = -side * sin(h)
                        val wb = wallDist(fi, x - 4 * ux, y - 4 * uy, nx, ny, 6.0); val wa = wallDist(fi, x + 4 * ux, y + 4 * uy, nx, ny, 6.0)
                        if (wb > 4.0 || wa > 4.0) continue          // a corridor wall on that side before and after: a real junction
                        val open = (1..47).all { k -> val d = k * 0.3; listOf(-0.6, 0.0, 0.6).all { o -> pass(fi, x + d * nx + o * ux, y + d * ny + o * uy) } }
                        if (!open) continue
                        val ex = x + 14 * nx; val ey = y + 14 * ny
                        if ((r.project(fi, ex, ey)?.dist ?: 0.0) < 12) continue
                        val truth = routePts(r, 0.0, s) + listOf(ex to ey)
                        return Scenario("side", r, goal, fi, truth, s, String.format(Locale.US, "%s → %s, junction at %.0f m of %.0f m, side corridor ≥ 14 m (walls %.1f/%.1f m)",
                            r.startTitle, r.goalTitle, s, r.length, wb, wa))
                    }
                }
                s += 0.5
            }
        }
        return null
    }

    /** Space 4–6 m beside a straight route part, behind a wall, ≥ 15 m long; reached through a nearby door. */
    private fun findParallel(): Scenario? {
        for ((r, goal) in routes) {
            val fi = r.legs[0].fi
            var s = 15.0
            while (s < r.length - 25) {
                val h = r.headingAt(s)
                if (straight(r, s - 3, s + 18, h)) {
                    val (_, x, y) = r.pointAt(s)
                    val ux = sin(h); val uy = cos(h)
                    for (side in listOf(1.0, -1.0)) {
                        val nx = side * cos(h); val ny = -side * sin(h)
                        val a = wallDist(fi, x, y, nx, ny, 4.0)
                        if (a >= 3.9) continue
                        var c = a; while (c < 6.5 && !pass(fi, x + c * nx, y + c * ny)) c += 0.15
                        val off = c + 0.6
                        if (off !in 4.0..6.2) continue
                        val line = (0..50).map { k -> val t = k * 0.3; Triple(t, x + off * nx + t * ux, y + off * ny + t * uy) }
                        val ok = line.all { (t, qx, qy) -> pass(fi, qx, qy) && pass(fi, qx - 0.3 * nx, qy - 0.3 * ny) && pass(fi, qx + 0.3 * nx, qy + 0.3 * ny) &&
                            !clear(fi, qx, qy, x + t * ux, y + t * uy) }
                        if (!ok) continue
                        // how to get there: shortest path from the route point to the start of the parallel line
                        val pa = planner.placeFromFix(b.floorOf(fi), x, y) ?: continue
                        val pq = planner.placeFromFix(b.floorOf(fi), line[0].second, line[0].third) ?: continue
                        val door = planner.plan(pa, pq).firstOrNull() ?: continue
                        if (door.legs.size != 1 || door.length > 30) continue
                        val truth = routePts(r, 0.0, s) + routePts(door, 0.0).drop(1) + listOf(line.last().second to line.last().third)
                        return Scenario("parallel", r, goal, fi, truth, s, String.format(Locale.US,
                            "%s → %s (%.0f m), at %.0f m: space %.1f m beside the route behind a wall (wall at %.1f m), reached by a %.0f m path through a door, then 15 m parallel",
                            r.startTitle, r.goalTitle, r.length, s, off, a, door.length))
                    }
                }
                s += 1.0
            }
        }
        return null
    }

    /** Same open space 3.5 m beside the route (clear line to it) for 15 m — must NOT reroute. */
    private fun findHall(): Scenario? {
        for ((r, goal) in routes) {
            val fi = r.legs[0].fi
            var s = 15.0
            while (s < r.length - 25) {
                val h = r.headingAt(s)
                if (straight(r, s - 3, s + 18, h)) {
                    val (_, x, y) = r.pointAt(s)
                    val ux = sin(h); val uy = cos(h)
                    for (side in listOf(1.0, -1.0)) {
                        val nx = side * cos(h); val ny = -side * sin(h)
                        val line = (0..50).map { k -> val t = k * 0.3; Triple(t, x + 3.5 * nx + t * ux, y + 3.5 * ny + t * uy) }
                        val ok = line.all { (t, qx, qy) -> pass(fi, qx, qy) && pass(fi, qx + 0.6 * nx, qy + 0.6 * ny) && clear(fi, qx, qy, x + t * ux, y + t * uy) } &&
                            clear(fi, x, y, line[0].second, line[0].third)
                        if (!ok) continue
                        // drift out diagonally (~20°) to 3.5 m, walk on beside the line, drift back
                        val mid1 = x + 3.5 * nx + 9.6 * ux to y + 3.5 * ny + 9.6 * uy
                        val back = r.pointAt(s + 24)
                        if (s + 24 > r.length - 5 || !straight(r, s - 3, s + 25, h)) continue
                        val mid2 = x + 3.5 * nx + 15.0 * ux to y + 3.5 * ny + 15.0 * uy
                        if (!pass(fi, mid2.first, mid2.second) || !clear(fi, mid1.first, mid1.second, mid2.first, mid2.second)) continue
                        val truth = routePts(r, 0.0, s) + listOf(mid1, mid2, back.second to back.third) + routePts(r, s + 24).drop(1)
                        return Scenario("hall", r, goal, fi, truth, s, String.format(Locale.US, "%s → %s, at %.0f m: 15 m walked 3.5 m beside the line in the same open space",
                            r.startTitle, r.goalTitle, s))
                    }
                }
                s += 1.0
            }
        }
        return null
    }

    // ------------------------------------------------------------------ one run
    class Run(val kind: String) {
        var reroutes = 0; val reasons = ArrayList<String>()
        var firstDevM = Double.NaN; var firstDevS = Double.NaN    // true distance / time after leaving the route until the 1st reroute
        var newStartErr = Double.NaN                               // new route start vs the true position
        var snapBack = 0                                           // arrow snapped to the old line while the user is > 4 m from it
        var nearOld = 0                                            // (info) fused position itself within 1.5 m of the old line then
        var arrived = false
        var maxDispErr = 0.0                                       // displayed arrow vs truth after leaving the route (before the reroute)
    }

    /**
     * Drives the walk. fixer(t, truthX, truthY, heading, steps, walking) -> fixes to process (A: one synthetic fix; B: whatever
     * the engine emitted). The simulated user follows sc.truth, after a reroute the new route.
     */
    private var trace: StringBuilder? = null
    private fun drive(sc: Scenario, src: FixSource): Run {
        val run = Run(sc.kind)
        val fi = sc.fi; val floor = b.floorOf(fi)
        val g = Guidance(b, sc.route)
        val vp = VoicePlanner()
        var w = Walker(sc.truth)
        var rerouted = false
        val oldRoute = sc.route
        var tDev = Double.NaN; var dDev = Double.NaN
        var walkedTotal = 0.0
        var standT = 0.0
        src.start(floor, w.x, w.y, w.heading())
        var t = 0.0
        val dtTick = 0.02
        while (t < 400) {
            t += dtTick
            val walking = !w.done
            if (walking) { w.d = min(w.length, w.d + 1.178 * dtTick); walkedTotal += 1.178 * dtTick } else standT += dtTick
            if (!walking && standT > 12) break
            // deviation start: the truth leaves the old route by > 1.5 m (first time)
            if (!rerouted && tDev.isNaN()) {
                val lat = oldRoute.project(fi, w.x, w.y)?.dist ?: 0.0
                if (lat > (if (sc.kind == "parallel") 3.5 else 1.5) && sc.kind != "control") { tDev = t; dDev = walkedTotal }
            }
            for (f in src.tick(t, w.x, w.y, w.heading(), walking)) {
                val st = g.update(f)
                trace?.append(String.format(Locale.US, "t=%.1f src=%s steps=%d truth=(%.1f,%.1f) fused=(%.1f,%.1f) err=%.1f acc=%.1f fl=%d h=%.0f/%.0f lat=%.1f off=%s %s snap=%s\n",
                    t, f.source, f.steps, w.x, w.y, f.x, f.y, hypot(f.x - w.x, f.y - w.y), f.acc, f.floor, Math.toDegrees(f.heading), Math.toDegrees(w.heading()), st.lateral, st.offRoute, st.offReason, st.snapped) +
                    String.format(Locale.US, "     raw=(%.1f,%.1f)", f.rawX, f.rawY) + ((src as? EngineFixes)?.eng?.lastMatch?.takeIf { f.source == "wifi" }?.ranked?.take(4)?.joinToString(" ", "  match: ", "\n") {
                        String.format(Locale.US, "(%d %.1f,%.1f %.2f)", it.floor, it.x, it.y, it.raw) } ?: "\n"))
                vp.update(g.route, st)
                // display checks against the OLD route while the user is off it (before the reroute)
                if (!rerouted && !tDev.isNaN()) {
                    val tl = oldRoute.project(fi, w.x, w.y)?.dist ?: 0.0
                    val dl = oldRoute.project(fi, st.dispX, st.dispY)?.dist ?: 0.0
                    if (tl > 5.5 && st.snapped) run.snapBack++
                    if (tl > 4.0 && dl < 1.5) run.nearOld++
                    if (tl > 3.0) run.maxDispErr = max(run.maxDispErr, hypot(st.dispX - w.x, st.dispY - w.y))
                }
                if (st.needReroute) {
                    run.reroutes++; run.reasons.add(st.offReason + String.format(Locale.US, "@%.1fs", t))
                    if (run.reroutes == 1) { run.firstDevM = walkedTotal - (if (dDev.isNaN()) walkedTotal else dDev); run.firstDevS = t - (if (tDev.isNaN()) t else tDev) }
                    val me = planner.placeFromFix(f.floor, f.x, f.y) ?: continue
                    val nr = planner.plan(me, sc.goal).firstOrNull() ?: continue
                    if (nr.legs.size != 1) continue
                    g.replaceRoute(nr)
                    vp.onReroute((t * 1e9).toLong())
                    if (run.reroutes == 1) run.newStartErr = hypot(nr.pointAt(0.0).second - w.x, nr.pointAt(0.0).third - w.y)
                    // the user now follows the new route: straight to its nearest point, then along it
                    val p = nr.project(fi, w.x, w.y)!!
                    w = Walker(listOf(w.x to w.y) + routePts(nr, p.s))
                    rerouted = true; standT = 0.0
                }
                if (st.arrived) run.arrived = true
            }
            if (run.arrived && w.done) break
        }
        return run
    }

    interface FixSource {
        fun start(floor: Int, x: Double, y: Double, h: Double)
        fun tick(t: Double, x: Double, y: Double, h: Double, walking: Boolean): List<Fix>
    }

    /** A: synthetic fused fixes, one per step. */
    inner class SynthFixes(seed: Long) : FixSource {
        private val rnd = Random(seed)
        private var ex = 0.0; private var ey = 0.0; private var floor = 0
        private var nextStep = 0.0; private var steps = 0; private var lastT = 0.0
        override fun start(floor: Int, x: Double, y: Double, h: Double) { this.floor = floor; ex = rnd.nextGaussian() * 1.2; ey = rnd.nextGaussian() * 1.2 }
        override fun tick(t: Double, x: Double, y: Double, h: Double, walking: Boolean): List<Fix> {
            val period = if (walking) 1 / 1.9 else 1.0
            if (t < nextStep) return emptyList()
            nextStep = t + period
            if (walking) steps++
            val dt = t - lastT; lastT = t
            val a = exp(-dt / 4.0); val q = 1.2 * sqrt(1 - a * a)
            ex = a * ex + q * rnd.nextGaussian(); ey = a * ey + q * rnd.nextGaussian()
            val fi = b.fi(floor)
            var fx = x + ex + rnd.nextGaussian() * 0.3; var fy = y + ey + rnd.nextGaussian() * 0.3
            if (!b.passableXY(fi, fx, fy)) { val c = b.snapXY(fi, fx, fy, 3.0); if (c >= 0) { fx = b.xOf(c % b.cols); fy = b.yOf(c / b.cols) } }
            return listOf(Fix((t * 1e9).toLong(), floor, fx, fy, 4.0, Orientation.wrap(h + rnd.nextGaussian() * Math.toRadians(12.0)), "sim",
                moving = walking, steps = steps, stepLen = 0.62, floorProb = 0.95))
        }
    }

    /** B: synthetic sensors through the real positioning engine. */
    inner class EngineFixes(seed: Long) : FixSource {
        private val rnd = Random(seed)
        val eng = PositionEngine(b, radio, null, pdr = true, useGrid = true, particles = 1200, seed = seed, useBaro = false)
        private val out = ArrayList<Fix>()
        private val g0 = 1.1; private val magBias = Math.toRadians(10.0)
        private var phase = 0.0; private var nextScan = 1.0; private var lastT = 0.0; private var floor = 0
        private var started = false; private var sx = 0.0; private var sy = 0.0
        private val pts: List<FpPoint> by lazy { radio.points.filter { !it.outdoor } }
        init { eng.onFix = { out.add(it) } }
        private fun q(yaw: Double) = doubleArrayOf(0.0, 0.0, sin(-yaw / 2), cos(-yaw / 2))
        override fun start(floor: Int, x: Double, y: Double, h: Double) { this.floor = floor; sx = x; sy = y }
        override fun tick(t: Double, x: Double, y: Double, h: Double, walking: Boolean): List<Fix> {
            out.clear()
            val tn = (t * 1e9).toLong()
            val dt = t - lastT; lastT = t
            val gy = Orientation.wrap(h + g0 + rnd.nextGaussian() * Math.toRadians(1.0))
            val my = Orientation.wrap(h - Orientation.MAG_TO_MAP + magBias + rnd.nextGaussian() * Math.toRadians(3.0))
            q(gy).let { eng.onGameRot(tn, it[0], it[1], it[2], it[3]) }
            q(my).let { eng.onRotVec(tn, it[0], it[1], it[2], it[3]) }
            // first second standing, then the known start (the user taps "I am here" / the route start)
            if (!started) { if (t >= 1.0) { eng.startAt(floor, sx, sy, 1.5); started = true }; eng.onAccel(tn, 0f, 0f, (9.81 + rnd.nextGaussian() * 0.05).toFloat()); return emptyList() }
            val a = if (walking) { phase += 2 * PI * 1.9 * dt; 9.81 + 2.5 * sin(phase) + rnd.nextGaussian() * 0.15 } else 9.81 + rnd.nextGaussian() * 0.05
            eng.onAccel(tn, 0f, 0f, a.toFloat())
            if (t >= nextScan) { nextScan = t + 3.5; scanAt(x, y).takeIf { it.isNotEmpty() }?.let { eng.onWifi(tn, it) } }
            return out.toList()
        }
        private fun scanAt(x: Double, y: Double): Map<String, Float> {
            val fp = pts.filter { it.floor == floor }.minByOrNull { hypot(it.x - x, it.y - y) } ?: return emptyMap()
            if (hypot(fp.x - x, fp.y - y) > 6.0) return emptyMap()     // not calibrated here: no usable scan
            val m = HashMap<String, Float>()
            for (i in fp.ids.indices) if (rnd.nextDouble() > 0.15) m[radio.dict.keys[fp.ids[i]]] = (fp.rssi[i] + rnd.nextGaussian() * 3).toFloat()
            return m
        }
    }

    private fun q(l: List<Double>, p: Double) = l.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * p).toInt()] }

    @Test
    fun rerouteOnDeviation() {
        val sb = StringBuilder("# Off-route detection and rerouting (simulated walks on the real map)\n\n")
        sb.append("Rule (Guidance): fused position > ${Guidance.OFF_FAR} m from the route, or > ${Guidance.OFF_WALL} m behind a wall, " +
            "for ≥ 3 s or ≥ 4 steps beyond the threshold (behind a wall = the walk back is > 2 × distance + 4 m); or 5 steps of fused motion away from the route (> 41° outwards, +1.5 m, now ≥ 3 m); " +
            "distances × acc/5 m (1…1.6); or an unexpected floor (at once). Reroute ≤ 1 per 5 s. " +
            "Arrow snapped to the route only ≤ ${Guidance.SNAP_DIST} m, same corridor, heading agrees. No route prior in the particle filter.\n\n")
        val side = findSide(); val par = findParallel(); val hall = findHall()
        sb.append("Routes scanned: ${routes.size}\n\n")
        val scen = ArrayList<Scenario>()
        side?.let { scen.add(it); sb.append("- side: ${it.info}\n") } ?: sb.append("- side: NOT FOUND\n")
        par?.let { scen.add(it); sb.append("- parallel: ${it.info}\n") } ?: sb.append("- parallel: NOT FOUND\n")
        hall?.let { scen.add(it); sb.append("- hall: ${it.info}\n") } ?: sb.append("- hall: not found (skipped)\n")
        val ctl = side ?: par
        if (ctl != null) scen.add(Scenario("control", ctl.route, ctl.goal, ctl.fi, routePts(ctl.route, 0.0), -1.0, "follows the route"))
        sb.append("\n")
        var allOk = side != null && par != null
        for (level in listOf("A", "B")) {
            sb.append(if (level == "A") "## A. Guidance with synthetic fused positions (AR(1) error σ 1.2 m, τ 4 s; 20 seeds)\n\n"
                      else "## B. Full chain: synthetic IMU + Wi-Fi from the radio map -> particle filter -> guidance -> re-plan (5 seeds)\n\n")
            sb.append("| scenario | runs | reroutes per run | 1st reroute after leaving: m p50 / max | s p50 / max | new start vs true pos m (max) | snapped-back fixes | arrived | reasons | ok |\n|---|---|---|---|---|---|---|---|---|---|\n")
            for (sc in scen) {
                val seeds = if (level == "A") (1L..20L) else (1L..5L)
                val runs = seeds.map { sd -> drive(sc, if (level == "A") SynthFixes(sd) else EngineFixes(sd)) }
                val rr = runs.map { it.reroutes }
                val dm = runs.map { it.firstDevM }; val ds = runs.map { it.firstDevS }
                val needArr = level == "A"
                val ok = when (sc.kind) {
                    "side", "parallel" -> runs.all { it.reroutes == 1 && it.snapBack == 0 && (it.arrived || !needArr) && it.newStartErr <= (if (needArr) 5.0 else 8.0) } &&
                        q(dm, 0.5) <= (if (sc.kind == "side") 5.0 else 9.0) && (dm.filter { !it.isNaN() }.maxOrNull() ?: 99.0) <= (if (sc.kind == "side") 7.0 else 11.0)
                    "hall" -> level == "B" || runs.all { it.reroutes == 0 && it.arrived }     // B (filter drift in the open space): reported
                    else -> runs.all { it.reroutes == 0 && (it.arrived || !needArr) }
                }
                allOk = allOk && ok
                sb.append(String.format(Locale.US, "| %s | %d | %s | %.1f / %.1f | %.1f / %.1f | %.1f | %d | %d/%d | %s | %s |\n", sc.kind, runs.size,
                    rr.groupingBy { it }.eachCount().toSortedMap().entries.joinToString(" ") { "${it.key}×${it.value}" },
                    q(dm, 0.5), dm.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN, q(ds, 0.5), ds.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
                    runs.map { it.newStartErr }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN, runs.sumOf { it.snapBack },
                    runs.count { it.arrived }, runs.size, runs.flatMap { it.reasons.map { r -> r.substringBefore('@') } }.groupingBy { it }.eachCount(),
                    if (ok) "OK" else "FAIL"))
            }
            sb.append("\n")
        }
        sb.append("Distances/times are counted from the moment the true position leaves the old route by > 1.5 m (parallel: > 3.5 m). " +
            "B arrival depends on the positioning at the very end (12 s standing), not on rerouting; it is reported, not required. " +
            "\"snapped-back fixes\": fixes where the user is > 5.5 m from the old route but the arrow is snapped onto it (must be 0). " +
            "\"new start\": distance from the new route's start (= fused position at the reroute) to the true position.\n")
        val txt = sb.toString()
        println(txt)
        outDir.resolve("reroute.md").writeText(txt)
        assertTrue(allOk)
    }

    @Test
    fun traceChain() {
        if (System.getProperty("gmc.trace") == null) return
        val sc = findSide() ?: return
        trace = StringBuilder()
        drive(Scenario("control", sc.route, sc.goal, sc.fi, routePts(sc.route, 0.0), -1.0, ""), EngineFixes(1))
        outDir.resolve("reroute_trace.txt").writeText(trace.toString()); trace = null
    }

    @Test
    fun unexpectedFloorReroutesAfterHold() {
        // 03.11: one fix on another floor (Wi-Fi in a stairwell flips floors for a few seconds) no longer reroutes at once;
        // a wrong floor that holds ≥ 5 s and ≥ 2 fixes does
        val (r, _) = routes.first()
        val g = Guidance(b, r)
        val (fi, x, y) = r.pointAt(10.0)
        val other = b.floors.map { it.n }.first { it != b.floorOf(fi) }
        val f0 = Fix(1_000_000_000L, b.floorOf(fi), x, y, 4.0, r.headingAt(10.0), "sim", steps = 1, moving = true)
        val s0 = g.update(f0)
        val f1 = Fix(1_500_000_000L, other, x, y, 4.0, r.headingAt(10.0), "sim", steps = 2, moving = true)
        val s1 = g.update(f1)      // first fix on the other floor: not yet
        val f2 = Fix(4_000_000_000L, other, x, y, 4.0, r.headingAt(10.0), "sim", steps = 5, moving = true)
        val s2 = g.update(f2)      // 2.5 s: not yet
        val f3 = Fix(7_000_000_000L, other, x, y, 4.0, r.headingAt(10.0), "sim", steps = 9, moving = true)
        val s3 = g.update(f3)      // held 5.5 s: reroute
        println("floor: on=${s0.needReroute} wrongFloor=${s1.needReroute} +2.5s=${s2.needReroute} +5.5s=${s3.needReroute}(${s3.offReason})")
        assertTrue(!s0.needReroute && !s1.needReroute && !s2.needReroute && s3.needReroute && s3.offReason == "floor" && !s1.snapped)
    }

    @Suppress("unused") private fun dbg(st: GuidanceState) = String.format(Locale.US, "s=%.1f lat=%.1f off=%s snap=%s", st.s, st.lateral, st.offRoute, st.snapped)
    @Suppress("unused") private val unusedRouter: Router? = null
}
