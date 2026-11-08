package com.gmcnav.navi.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Position for the UI and guidance. (x, y) is the displayed, speed-limited position (never jumps);
 * rawX/rawY the filter's current estimate. heading: map heading (rad, clockwise from north), NaN = unknown.
 */
class Fix(
    val tNs: Long, val floor: Int, val x: Double, val y: Double, val acc: Double, val heading: Double,
    val source: String, val outside: Boolean = false, val floorProb: Double = 1.0, val moving: Boolean = false,
    val standing: Boolean = false, val stepLen: Double = PositionEngine.STEP_LEN, val steps: Int = 0,
    val rawX: Double = x, val rawY: Double = y,
    val wifiX: Double = Double.NaN, val wifiY: Double = Double.NaN, val wifiFloor: Int = 0,
    /** Time of the last scan that looked like this building (radio-valid); 0 = never. */
    val radioOkNs: Long = 0,
)

object Orientation {
    /**
     * Heading of the phone's "forward" direction from a rotation-vector quaternion (x, y, z, w):
     * horizontal projection of device +Y minus device +Z — works flat (top edge forward) and upright (back camera forward).
     * World frame X east, Y north; result clockwise from the frame's north.
     */
    fun yaw(x: Double, y: Double, z: Double, w: Double): Double {
        val yx = 2 * (x * y - z * w); val yy = 1 - 2 * (x * x + z * z)
        val zx = 2 * (x * z + y * w); val zy = 2 * (y * z - x * w)
        return atan2(yx - zx, yy - zy)
    }

    fun wrap(a: Double): Double { var v = a; while (v > PI) v -= 2 * PI; while (v <= -PI) v += 2 * PI; return v }

    /** Map heading = magnetic rotation-vector yaw + this (plan rotated vs magnetic north; −81° ± 10° over 14 recordings). */
    val MAG_TO_MAP = Math.toRadians(-81.0)
}

/**
 * Floor transitions from the barometer: only the short-term change counts (absolute pressure drifts ±2–3 hPa with hand
 * heat; heating rises up to ~0.18 hPa/s and decays over ~100 s). A floor is ≈ 5.1 m ≈ 0.55–0.6 hPa; stairs/escalators take
 * 15–40 s. An event = the 1 s-smoothed pressure changed by 0.55–1.6 hPa over 15–30 s, monotonically and no faster than
 * 0.04 hPa/s (grip heating is faster). Tuned on the 5 recorded transitions (all detected); hand-heat swings still give
 * false events (~1/min), so an event only supports floor moves of particles that are at a connector. Pressure down = up.
 */
class BaroDetector {
    class Event(val tNs: Long, val dir: Int, val dp: Double, val durS: Double)
    private val t = LongArray(4096); private val v = DoubleArray(4096); private var n = 0
    private var lp = Double.NaN; private var lastT = 0L
    private var lastEventT = Long.MIN_VALUE / 2
    var last: Event? = null; private set
    val events = ArrayList<Event>()
    /** +1 up / −1 down while a recent event is fresh (≤ 40 s), else 0. */
    fun evidence(now: Long): Int { val e = last ?: return 0; return if (now - e.tNs < 40_000_000_000L) e.dir else 0 }

    fun add(tNs: Long, hpa: Double): Event? {
        if (hpa < 800 || hpa > 1100) return null
        if (lp.isNaN()) { lp = hpa; lastT = tNs }
        val dt = (tNs - lastT) / 1e9; lastT = tNs
        if (dt <= 0) return null
        lp += (hpa - lp) * min(1.0, dt / 1.0)
        // keep ~1 sample per 0.5 s
        if (n > 0 && tNs - t[(n - 1) % t.size] < 500_000_000L) return null
        t[n % t.size] = tNs; v[n % v.size] = lp; n++
        if (tNs - lastEventT < 30_000_000_000L) return null
        val m = min(n, t.size)
        var best: Event? = null
        for (back in 30..60) {                     // 15..30 s in 0.5 s samples
            if (back >= m) break
            val j = (n - 1 - back) % t.size
            val span = (tNs - t[j]) / 1e9
            if (span < 14 || span > 31) continue
            val dp = lp - v[j]
            if (abs(dp) < 0.55 || abs(dp) > 1.6) continue
            if (abs(dp) / span > 0.04) continue
            // monotonic: the middle point lies between the ends (with slack)
            val mid = v[(n - 1 - back / 2) % t.size]
            val lo = min(v[j], lp) - 0.12; val hi = max(v[j], lp) + 0.12
            if (mid < lo || mid > hi) continue
            val e = Event(tNs, if (dp < 0) 1 else -1, dp, span)
            if (best == null || abs(dp) > abs(best.dp)) best = e
        }
        if (best != null) { lastEventT = tNs; last = best; events.add(best) }
        return best
    }
}

/**
 * Particle filter on the navigation grid — PDR is the motion model, radio only corrects drift.
 * Particle: floor index, x, y, heading offset (map yaw − game-rotation yaw: the game rotation vector has no north and its
 * offset changes per session and when the phone is re-gripped), step-length scale (0.45–0.8 m), weight.
 * Steps move particles along their heading; moves into walls slide along them or are rejected.
 * Radio: wide mixture likelihood around the best fingerprints (σ 9–12 m, flatter for weak/sparse/ambiguous scans,
 * tempered while standing, gated when it disagrees with a confident cloud). Floors switch only at connectors,
 * supported by barometer events. Partly re-seeded from Wi-Fi when the cloud stops explaining confident scans.
 */
class ParticleFilter(val b: Building, val n: Int = 1200, seed: Long = 7, val useGrid: Boolean = true) {
    val fi = IntArray(n); val px = DoubleArray(n); val py = DoubleArray(n)
    val off = DoubleArray(n); val k = DoubleArray(n); val w = DoubleArray(n)
    var initialized = false; private set
    private val rnd = Random(seed)
    private var poorScans = 0
    var lastLikelihoodRatio = 1.0; private set
    var lastSigma = 0.0; private set

    private class ConnPt(val fi: Int, val x: Double, val y: Double, val r: Double, val conn: Connector)
    private val connPts = b.conns.flatMap { c ->
        c.floors.mapNotNull { f -> c.pos[f]?.let { p -> ConnPt(b.fi(f), p[0], p[1], when (c.kind) { "lift" -> 4.0; "escalator" -> 8.0; else -> 7.0 }, c) } }
    }

    private fun gauss() = rnd.nextGaussian()
    private val SLIDE = doubleArrayOf(0.0, 0.35, -0.35, 0.7, -0.7)
    private val SLIDE_W = doubleArrayOf(1.0, 0.7, 0.7, 0.35, 0.35)

    private fun passable(f: Int, x: Double, y: Double) = !useGrid || b.passableXY(f, x, y)

    /** Seeds (a fraction of) the particles around the best Wi-Fi matches. offPrior = (mean, sd) or null = uniform. */
    fun init(m: Match, offPrior: Pair<Double, Double>?, frac: Double = 1.0) {
        val top = m.ranked.take(10)
        if (top.isEmpty()) return
        val ws = top.map { it.raw.toDouble().pow(4) }
        val tot = ws.sum()
        val sd = if (m.nAp < 8) 7.0 else 4.0
        seed(frac, offPrior) {
            var u = rnd.nextDouble() * tot; var j = 0
            while (j < ws.size - 1 && u > ws[j]) { u -= ws[j]; j++ }
            Triple(b.fi(top[j].floor), top[j].x, top[j].y) to sd
        }
    }

    /** Seeds all particles at a known point (PDR-only tests, "I am here" from the map). */
    fun initAt(floor: Int, x: Double, y: Double, sd: Double, offPrior: Pair<Double, Double>?) =
        seed(1.0, offPrior) { Triple(b.fi(floor), x, y) to sd }

    private fun seed(frac: Double, offPrior: Pair<Double, Double>?, pick: () -> Pair<Triple<Int, Double, Double>, Double>) {
        val idx = if (frac >= 1.0 || !initialized) (0 until n).toList() else (0 until n).sortedBy { w[it] }.take((n * frac).toInt())
        val wNew = if (frac >= 1.0 || !initialized) 1.0 / n else (w.sum() / n).coerceAtLeast(1e-12)
        for (i in idx) {
            val (p, sd) = pick(); val f = p.first
            var x = p.second; var y = p.third
            for (t in 0 until 20) {
                val cx = p.second + gauss() * sd; val cy = p.third + gauss() * sd
                if (b.passableXY(f, cx, cy)) { x = cx; y = cy; break }
            }
            if (!b.passableXY(f, x, y)) { val c = b.snapXY(f, x, y, 6.0); if (c >= 0) { x = b.xOf(c % b.cols); y = b.yOf(c / b.cols) } }
            fi[i] = f; px[i] = x; py[i] = y
            off[i] = if (offPrior != null && rnd.nextDouble() < 0.75) offPrior.first + gauss() * offPrior.second else rnd.nextDouble() * 2 * PI
            k[i] = 0.9 + rnd.nextDouble() * 0.2
            w[i] = wNew
        }
        normalize()
        initialized = true
    }

    /** One step: device yaw (game frame), nominal length; offNoise grows after a sudden yaw change (re-grip). */
    fun step(yawDev: Double, len: Double, offNoise: Double = 0.012) {
        for (i in 0 until n) {
            val h = yawDev + off[i] + gauss() * 0.10
            val l = max(0.15, len * k[i] + gauss() * 0.06)
            var moved = false
            for (a in SLIDE.indices) {
                val hh = h + SLIDE[a]
                val nx = px[i] + l * sin(hh); val ny = py[i] + l * cos(hh)
                if (passable(fi[i], nx, ny) && passable(fi[i], (px[i] + nx) / 2, (py[i] + ny) / 2)) {
                    px[i] = nx; py[i] = ny; w[i] *= SLIDE_W[a]; moved = true; break
                }
            }
            if (!moved) w[i] *= 0.05
            off[i] += gauss() * offNoise
            k[i] = (k[i] + gauss() * 0.004).coerceIn(K_MIN, K_MAX)
        }
        normalize()
        if (neff() < n / 2) resample()
    }

    /**
     * Particles near stairs / lifts / escalators may move to another floor of that connector.
     * baro: +1 up / −1 down (fresh barometer event), 0 none. With an event the move is likely and must agree with it;
     * without one a floor change is possible (lift doors, weak signal) but penalised.
     */
    fun floorMoves(baro: Int) {
        val p = if (baro != 0) 0.3 else 0.03
        for (i in 0 until n) {
            if (rnd.nextDouble() > p) continue
            for (c in connPts) {
                if (c.fi != fi[i] || hypot(px[i] - c.x, py[i] - c.y) > c.r) continue
                var others = c.conn.floors.filter { b.fi(it) != fi[i] && c.conn.pos[it] != null }
                if (baro != 0) others = others.filter { (b.fi(it) - fi[i]) * baro > 0 }.ifEmpty { others }
                if (others.isEmpty()) break
                val tf = others[rnd.nextInt(others.size)]
                val dir = if (b.fi(tf) > fi[i]) 1 else -1
                val tp = c.conn.pos.getValue(tf); val tfi = b.fi(tf)
                var nx = tp[0] + gauss(); var ny = tp[1] + gauss()
                if (!b.passableXY(tfi, nx, ny)) { val cc = b.snapXY(tfi, tp[0], tp[1], 3.0); if (cc < 0) break; nx = b.xOf(cc % b.cols); ny = b.yOf(cc / b.cols) }
                fi[i] = tfi; px[i] = nx; py[i] = ny
                w[i] *= when { baro == 0 -> 0.5; baro == dir -> 1.0; else -> 0.2 }
                break
            }
        }
        normalize()
    }

    /**
     * Radio update. Evidence per fingerprint is absolute (same place ≳ 0.4, other places ≲ 0.2); likelihood =
     * eps + conf · mixture N(dist; σ) with σ 9 m (12 m for sparse or ambiguous scans), floor votes on their own.
     * temper < 1 weakens it (standing still, outliers). Deliberately no route prior: the filter must be free to
     * leave the route so that guidance can detect a deviation (constraints are walls and radio only).
     */
    fun wifi(m: Match, temper: Double = 1.0): Boolean {
        val top = m.ranked.take(12)
        if (top.isEmpty() || top[0].raw < 0.12f) return false
        val ws = DoubleArray(top.size) { val v = ((top[it].raw - 0.15) / 0.3).coerceIn(0.0, 1.0); v * v }
        var conf = ws[0]
        if (conf <= 0.0) return false
        val sparse = m.nAp < 8
        // radio-flat / ambiguous: near-best candidates far apart
        val near = top.filter { it.raw >= top[0].raw - 0.08f && it.floor == top[0].floor }
        val spread = if (near.size >= 2) near.maxOf { a -> near.maxOf { z -> hypot(a.x - z.x, a.y - z.y) } } else 0.0
        val ambiguous = spread > 15.0
        if (ambiguous) conf *= 0.6
        val sigma = if (sparse || ambiguous) 12.0 else if (m.nAp < 15) 10.0 else 9.0
        lastSigma = sigma
        val floorEps = (if (sparse) 0.35 else 0.15) + 0.6 * (1 - conf)
        val tf = IntArray(top.size) { b.fi(top[it].floor) }
        val inv2s2 = 1.0 / (2 * sigma * sigma)
        val norm = ws.sum().coerceAtLeast(1e-9)
        val fv = DoubleArray(b.floors.size)
        for (p in m.ranked.take(10)) fv[b.fi(p.floor)] += p.raw.toDouble().pow(4)
        val fvMax = fv.max().coerceAtLeast(1e-12)
        val fConf = ((top[0].raw - 0.12) / 0.2).coerceIn(0.0, 1.0) * (if (sparse) 0.5 else 1.0)
        val ff = DoubleArray(b.floors.size) { 1 - fConf * (1 - (0.08 + 0.92 * fv[it] / fvMax)) }
        var expected = 0.0
        for (i in 0 until n) {
            var l = 0.0
            for (j in top.indices) {
                if (ws[j] < 1e-3) continue
                val d2 = (px[i] - top[j].x).pow(2) + (py[i] - top[j].y).pow(2)
                l += ws[j] * exp(-d2 * inv2s2) * (if (tf[j] == fi[i]) 1.0 else 0.1)
            }
            val mix = min(1.0, 1.5 * l / norm)
            expected += w[i] * mix * ff[fi[i]]
            var lik = (floorEps + conf * mix) * ff[fi[i]]
            if (temper < 1.0) lik = lik.pow(temper)
            w[i] *= lik
        }
        lastLikelihoodRatio = expected
        normalize()
        if (neff() < n / 2) resample()
        poorScans = if (top[0].raw >= 0.25f && !sparse && !ambiguous && lastLikelihoodRatio < 0.05) poorScans + 1 else 0
        return true
    }

    fun needsReseed() = poorScans >= 3
    fun reseeded() { poorScans = 0 }

    /** Weak cell hint: coarse kNN mixture, σ 15 m, mostly a floor/zone sanity check. */
    fun cells(top: List<CellPoint>) {
        val t = top.take(7).filter { !it.outdoor }
        if (t.isEmpty() || t[0].raw < 0.15f) return
        val wmax = t[0].raw.toDouble().pow(4)
        for (i in 0 until n) {
            var l = 0.0
            for (c in t) {
                val d2 = (px[i] - c.x).pow(2) + (py[i] - c.y).pow(2)
                l += c.raw.toDouble().pow(4) / wmax * exp(-d2 / (2 * 15.0 * 15.0)) * (if (b.fi(c.floor) == fi[i]) 1.0 else 0.4)
            }
            w[i] *= 0.6 + 0.4 * min(1.0, l)
        }
        normalize()
    }

    private fun normalize() {
        var s = 0.0; for (i in 0 until n) s += w[i]
        if (s <= 0 || s.isNaN()) { for (i in 0 until n) w[i] = 1.0 / n; return }
        for (i in 0 until n) w[i] /= s
    }

    private fun neff(): Double { var s = 0.0; for (i in 0 until n) s += w[i] * w[i]; return 1.0 / s }

    private fun resample() {
        val nf = IntArray(n); val nx = DoubleArray(n); val ny = DoubleArray(n); val no = DoubleArray(n); val nk = DoubleArray(n)
        val step = 1.0 / n; var u = rnd.nextDouble() * step; var c = w[0]; var j = 0
        for (i in 0 until n) {
            while (u > c && j < n - 1) { j++; c += w[j] }
            nf[i] = fi[j]; nx[i] = px[j]; ny[i] = py[j]; no[i] = off[j]; nk[i] = k[j]
            u += step
        }
        for (i in 0 until n) {
            fi[i] = nf[i]
            val jx = nx[i] + gauss() * 0.15; val jy = ny[i] + gauss() * 0.15
            if (passable(nf[i], jx, jy)) { px[i] = jx; py[i] = jy } else { px[i] = nx[i]; py[i] = ny[i] }
            off[i] = no[i] + gauss() * 0.02; k[i] = nk[i]; w[i] = 1.0 / n
        }
    }

    class Est(val fi: Int, val x: Double, val y: Double, val acc: Double, val floorProb: DoubleArray, val off: Double,
              val offConc: Double, val kMean: Double)

    /** Estimate on a floor (default: the most probable): mean-shift to the main mode, spread as accuracy. */
    fun estimate(forceFi: Int = -1): Est {
        val fp = DoubleArray(b.floors.size)
        for (i in 0 until n) fp[fi[i]] += w[i]
        val f = if (forceFi >= 0 && fp[forceFi] > 0.02) forceFi else fp.indices.maxByOrNull { fp[it] }!!
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        for (i in 0 until n) if (fi[i] == f) { sx += w[i] * px[i]; sy += w[i] * py[i]; sw += w[i] }
        var mx = sx / sw; var my = sy / sw
        for (it in 0 until 4) {
            var ax = 0.0; var ay = 0.0; var aw = 0.0
            for (i in 0 until n) if (fi[i] == f) {
                val kk = w[i] * exp(-((px[i] - mx).pow(2) + (py[i] - my).pow(2)) / (2 * 6.0 * 6.0))
                ax += kk * px[i]; ay += kk * py[i]; aw += kk
            }
            if (aw <= 1e-300) break
            mx = ax / aw; my = ay / aw
        }
        var v = 0.0; var cs = 0.0; var sn = 0.0; var km = 0.0
        for (i in 0 until n) {
            if (fi[i] == f) v += w[i] * ((px[i] - mx).pow(2) + (py[i] - my).pow(2))
            cs += w[i] * cos(off[i]); sn += w[i] * sin(off[i]); km += w[i] * k[i]
        }
        val acc = sqrt(v / sw).coerceIn(1.0, 80.0)
        return Est(f, mx, my, acc, fp, atan2(sn, cs), hypot(cs, sn), km)
    }

    companion object {
        const val K_MIN = 0.45 / PositionEngine.STEP_LEN
        const val K_MAX = 0.80 / PositionEngine.STEP_LEN
    }
}

/**
 * Positioning: pedestrian dead reckoning (accelerometer steps, game-rotation heading) on the grid is the short-term
 * motion; Wi-Fi (+ cells) are absolute but noisy corrections; the barometer's short-term change supports floor changes
 * at connectors. Standing still holds the position. The displayed position moves no faster than walking (radio
 * corrections are blended in over seconds), so the arrow and the route progress never jump.
 * Same code runs live, in the replay demo and in the tests. radio=false: PDR only (from a known start);
 * pdr=false: the Wi-Fi-only baseline (kNN as in the calibration app).
 */
class PositionEngine(
    val b: Building, val radio: RadioMap, val cellMap: CellMap? = null,
    val pdr: Boolean = true, val useGrid: Boolean = true, particles: Int = 1200, seed: Long = 7,
    val useRadio: Boolean = true, val useBaro: Boolean = true,
) {
    var exclude: ((FpPoint) -> Boolean)? = null
    var excludeCell: ((CellPoint) -> Boolean)? = null
    var onFix: ((Fix) -> Unit)? = null
    var fix: Fix? = null; private set
    val pf = ParticleFilter(b, particles, seed, useGrid)
    val baro = BaroDetector()
    private val knn = WifiKnn()
    private val steps = StepDetector()
    var stepCount = 0; private set
    var lastStepNs = 0L; private set
    var scans = 0; private set
    var lastScanAps = 0; private set
    var collapsed = 0; private set
    var reseeds = 0; private set
    var floorResets = 0; private set
    private var floorDisagree = 0
    var gated = 0; private set
    var lastMatch: Match? = null; private set
    var radioOk = false; private set
    var radioOkNs = 0L; private set
    var lastKnownAps = 0; private set

    private val yawT = LongArray(128); private val yawV = DoubleArray(128); private var yawN = 0
    private var yawGame = Double.NaN
    private var yawMag = Double.NaN
    private var magGameDiff = Double.NaN   // smoothed (map yaw by magnet) − game yaw
    private var regripSteps = 0
    private var dispFi = -1
    private var pendFi = -1; private var pendCount = 0
    private var outsideCount = 0
    private var lastWifi: PosEst? = null
    // standing detection: |a| variance over ~1 s
    private val accMag = DoubleArray(100); private var accN = 0
    // displayed (speed-limited) position
    private var dispX = Double.NaN; private var dispY = Double.NaN; private var dispT = 0L; private var farSince = 0L

    fun reset() {
        fix = null; knn.reset(); steps.reset(); yawN = 0; dispFi = -1; pendFi = -1; pendCount = 0; outsideCount = 0
        stepCount = 0; scans = 0; magGameDiff = Double.NaN; dispX = Double.NaN
    }

    private var lastAccT = 0L
    /** No steps for 2 s and a calm accelerometer (|a| sd < 0.35 m/s² over ~1 s). */
    val standing: Boolean get() {
        if (lastStepNs > 0 && lastAccT - lastStepNs < 2_000_000_000L) return false
        val m = min(accN, accMag.size); if (m < 50) return false
        var s = 0.0; var s2 = 0.0
        for (i in 0 until m) { s += accMag[i]; s2 += accMag[i] * accMag[i] }
        val sd = sqrt(max(0.0, s2 / m - (s / m).pow(2)))
        return sd < 0.35
    }

    fun onAccel(t: Long, ax: Float, ay: Float, az: Float) {
        accMag[accN % accMag.size] = sqrt((ax * ax + ay * ay + az * az).toDouble()); accN++; lastAccT = t
        val st = steps.add(t, ax, ay, az)
        if (st > 0) onStep(st)
    }

    fun onGameRot(t: Long, x: Double, y: Double, z: Double, w: Double) {
        val yw = Orientation.yaw(x, y, z, w)
        yawGame = yw
        val i = yawN % yawT.size; yawT[i] = t; yawV[i] = yw; yawN++
        if (!yawMag.isNaN()) {
            val d = Orientation.wrap(yawMag + Orientation.MAG_TO_MAP - yw)
            magGameDiff = if (magGameDiff.isNaN()) d else Orientation.wrap(magGameDiff + 0.02 * Orientation.wrap(d - magGameDiff))
        }
        // a fast large yaw change while standing or between steps = probably re-gripped: loosen offsets for a few steps
        val old = yawAt(t - 700_000_000L)
        if (!old.isNaN() && abs(Orientation.wrap(yw - old)) > Math.toRadians(60.0)) regripSteps = 6
    }

    fun onRotVec(t: Long, x: Double, y: Double, z: Double, w: Double) { yawMag = Orientation.yaw(x, y, z, w) }

    fun onPressure(t: Long, hpa: Double) { if (useBaro) baro.add(t, hpa) }

    private fun yawAt(t: Long): Double {
        if (yawN == 0) return Double.NaN
        var best = Double.NaN; var bd = Long.MAX_VALUE
        val m = min(yawN, yawT.size)
        for (j in 0 until m) { val d = abs(yawT[j] - t); if (d < bd) { bd = d; best = yawV[j] } }
        return if (bd < 1_000_000_000L) best else Double.NaN
    }

    private fun onStep(t: Long) {
        stepCount++; lastStepNs = t
        if (!pdr || !pf.initialized) return
        val yw = yawAt(t)
        if (yw.isNaN()) return
        pf.step(yw, STEP_LEN, if (regripSteps > 0) 0.2 else 0.012)
        if (regripSteps > 0) regripSteps--
        emit(t, "pdr")
    }

    /** Known start (PDR-only evaluation, or the user tapping "I am here"). */
    fun startAt(floor: Int, x: Double, y: Double, sd: Double = 2.0) {
        pf.initAt(floor, x, y, sd, offPrior())
        dispX = Double.NaN
    }

    /** One Wi-Fi scan (BSSID -> RSSI). */
    fun onWifi(t: Long, ap: Map<String, Float>) {
        scans++; lastScanAps = ap.size
        if (!useRadio) return
        // only this site's model: access points the model does not know are not features of any of its places
        val apKnown = ap.filterKeys { radio.dict.find(it) >= 0 }
        val m = radio.match(apKnown, exclude)
        lastMatch = m
        // does this scan look like the building at all? (≥ 3 known APs, or a very strong match; similarity ≥ 0.2)
        val known = radio.knownCount(ap)
        val bestSim = maxOf(m.bestIndoor, m.bestOutdoor)
        lastKnownAps = known
        radioOk = bestSim >= RADIO_OK && (known >= 3 || bestSim >= 0.4f)
        if (radioOk) radioOkNs = t
        if (!radioOk) { emit(t, "wifi-invalid"); return }
        outsideCount = if (m.bestOutdoor > 0.3f && m.bestOutdoor > m.bestIndoor + 0.05f) outsideCount + 1 else 0
        // collapsed scan: far fewer APs than the radio map has around the current estimate (few APs in the parking is normal)
        val cur = fix
        if (cur != null && ap.size < 30) {
            val exp = radio.expectedApCount(cur.floor, cur.rawX, cur.rawY)
            if (exp >= 20 && ap.size < exp * 0.25) { collapsed++; emit(t, "wifi-skip"); return }
        }
        lastWifi = knn.update(m)
        if (!pdr) { emit(t, "wifi"); return }
        if (!pf.initialized) {
            if (m.ranked.isNotEmpty() && m.ranked[0].raw >= 0.15f) { pf.init(m, offPrior()); emit(t, "wifi") }
            return
        }
        if (useGrid) pf.floorMoves(baro.evidence(t))
        val est = pf.estimate()
        // sensor resetting: a few particles at confident Wi-Fi candidates, so a lost cloud can recover
        if (m.ranked.isNotEmpty() && m.ranked[0].raw >= 0.3f && m.nAp >= 8) pf.init(m, est.off to Math.toRadians(25.0), 0.03)
        // gating: a compact cloud and a scan pointing far away -> weak update (persistent disagreement re-seeds below)
        var temper = if (standing) 0.35 else 1.0
        val best = m.ranked.firstOrNull()
        if (best != null && est.acc < 12 && hypot(best.x - est.x, best.y - est.y) > 35 && b.fi(best.floor) == est.fi) { temper *= 0.3; gated++ }
        pf.wifi(m, temper)
        if (pf.needsReseed()) { reseeds++; pf.init(m, offPrior(), 0.3); pf.reseeded() }
        // strong floor evidence: the scans keep voting for another floor (≥ 60 % of the top-10 weight, 3 scans in a row)
        // -> half the particles re-seeded from Wi-Fi. Floors otherwise change only at stairs / lifts (and data gaps, e.g. a
        // level without a connector, still recover); the displayed floor keeps its hysteresis.
        run {
            val top = m.ranked.take(10)
            if (top.isEmpty() || top[0].raw < 0.12f || m.nAp < 4) { floorDisagree = 0; return@run }
            val votes = HashMap<Int, Double>()
            for (p in top) votes.merge(p.floor, p.raw.toDouble().pow(4), Double::plus)
            val tot = votes.values.sum()
            val (vf, vw) = votes.maxByOrNull { it.value }!!.toPair()
            val pfFloor = b.floorOf(pf.estimate().fi)
            if (vf != pfFloor && vw / tot >= 0.6) floorDisagree++ else floorDisagree = 0
            if (floorDisagree >= 3) { pf.init(m, offPrior(), 0.5); floorDisagree = 0; floorResets++ }
        }
        emit(t, "wifi")
    }

    fun onCells(t: Long, cells: Map<String, Float>) {
        val cm = cellMap ?: return
        if (!pdr || !pf.initialized || !useRadio) return
        val top = cm.match(cells, excludeCell)
        if (top.isNotEmpty()) pf.cells(top)
    }

    private fun offPrior(): Pair<Double, Double>? = if (magGameDiff.isNaN()) null else magGameDiff to Math.toRadians(30.0)

    private fun emit(t: Long, src: String) {
        val wf = lastWifi
        val f: Fix
        if (!pdr) {
            val e = wf ?: return
            f = Fix(t, e.floor, e.x, e.y, max(e.radius, 4.0), if (magGameDiff.isNaN() || yawGame.isNaN()) Double.NaN else Orientation.wrap(yawGame + magGameDiff),
                src, outsideCount >= 2, wifiX = e.x, wifiY = e.y, wifiFloor = e.floor, steps = stepCount, radioOkNs = radioOkNs)
        } else {
            if (!pf.initialized) return
            val e0 = pf.estimate()
            // floor hysteresis: a new floor needs > 65 % of the weight twice in a row (or > 90 % once)
            if (dispFi < 0) dispFi = e0.fi
            else if (e0.fi != dispFi && src != "pdr") {
                val p = e0.floorProb[e0.fi]
                if (p > 0.9 || (p > 0.65 && pendFi == e0.fi && pendCount >= 1)) { dispFi = e0.fi; pendFi = -1; pendCount = 0; dispX = Double.NaN }
                else if (p > 0.65) { if (pendFi == e0.fi) pendCount++ else { pendFi = e0.fi; pendCount = 1 } }
            } else if (e0.fi == dispFi) { pendFi = -1; pendCount = 0 }
            val e = if (e0.fi == dispFi) e0 else pf.estimate(dispFi)
            var x = e.x; var y = e.y
            if (!b.passableXY(e.fi, x, y)) { val c = b.snapXY(e.fi, x, y, 3.0); if (c >= 0) { x = b.xOf(c % b.cols); y = b.yOf(c / b.cols) } }
            val stand = standing
            // displayed position: speed-limited towards the estimate (walking ≤ 2.2 m/s, standing ≤ 0.3 m/s);
            // a persistent large gap (re-localisation) is closed quickly after 8 s
            if (dispX.isNaN()) { dispX = x; dispY = y; dispT = t; farSince = 0 }
            else {
                val dt = ((t - dispT) / 1e9).coerceIn(0.0, 5.0); dispT = t
                val gap = hypot(x - dispX, y - dispY)
                if (gap > 25) { if (farSince == 0L) farSince = t } else farSince = 0
                val vmax = when { farSince != 0L && t - farSince > 8_000_000_000L -> 10.0; stand -> 0.3; else -> 2.2 }
                val stepMax = vmax * dt + if (src == "pdr") 0.7 else 0.0
                if (gap <= stepMax) { dispX = x; dispY = y } else { dispX += (x - dispX) / gap * stepMax; dispY += (y - dispY) / gap * stepMax }
                if (!b.passableXY(e.fi, dispX, dispY)) { val c = b.snapXY(e.fi, dispX, dispY, 2.0); if (c >= 0) { dispX = b.xOf(c % b.cols); dispY = b.yOf(c / b.cols) } }
            }
            val heading = when {
                yawGame.isNaN() -> Double.NaN
                e.offConc > 0.55 -> Orientation.wrap(yawGame + e.off)
                !magGameDiff.isNaN() -> Orientation.wrap(yawGame + magGameDiff)
                else -> Double.NaN
            }
            val moving = lastStepNs > 0 && t - lastStepNs < 1_500_000_000L
            f = Fix(t, b.floorOf(e.fi), dispX, dispY, e.acc, heading, src, outsideCount >= 2, e.floorProb[e.fi], moving, stand,
                STEP_LEN * e.kMean, stepCount, x, y, wf?.x ?: Double.NaN, wf?.y ?: Double.NaN, wf?.floor ?: 0, radioOkNs)
        }
        fix = f
        onFix?.invoke(f)
    }

    companion object {
        const val STEP_LEN = 0.58
        /** Best similarity below this: the scan is not from a calibrated place of this building ("same place" ≳ 0.2–0.4). */
        const val RADIO_OK = 0.2f
    }
}
