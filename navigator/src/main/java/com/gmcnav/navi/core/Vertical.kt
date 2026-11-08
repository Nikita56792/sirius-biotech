package com.gmcnav.navi.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Moving between floors by the real height (meta.json floors[].elev): ГМЦ 5.25 / 5.1 m per floor, Рекордов 15 3.15–3.6 m,
 * basement −2.5, attic +3.15 above the 5th floor.
 *
 * Stairs: ~0.17 m risers, 0.29 m treads (≈ 30°) — the flights are walked along the slope (≈ 2 × the height) plus a landing
 * (≈ 2 m) per flight pair; vertical speed going up ≈ 0.3 m/s (≈ 1.8 steps/s), down ≈ 0.45 m/s.
 * Escalator: 0.5 m/s along a 30° slope (0.25 m/s vertical) + boarding. Lift: average wait 25 s, doors 2 × 4 s, ride at
 * 1 m/s + 3 s to accelerate / stop. Each stair / escalator use also costs 8 s of "hassle": without it a route on one floor
 * went down one staircase and up another to save 4 s (ГМЦ, floor 2: 166 m + 2 stairs instead of 214 m on the floor).
 */
object Vertical {
    const val RISER = 0.17
    const val LANDING = 2.0
    const val STAIR_UP = 0.30
    const val STAIR_DOWN = 0.45
    const val ESC_V = 0.25
    const val LIFT_WAIT = 25.0
    const val LIFT_DOORS = 8.0
    const val LIFT_V = 1.0
    const val LIFT_ACC = 3.0
    const val TRANSFER = 8.0

    private fun flights(h: Double) = max(1.0, Math.round(h / 3.0).toDouble())

    /** Time (s) from a floor at elevation ea to one at ez. */
    fun cost(kind: String, ea: Double, ez: Double): Double {
        val h = abs(ez - ea)
        return when (kind) {
            "lift" -> LIFT_WAIT + LIFT_DOORS + h / LIFT_V + LIFT_ACC
            "escalator" -> h / ESC_V + 3.0 + TRANSFER
            else -> h / (if (ez > ea) STAIR_UP else STAIR_DOWN) + LANDING * flights(h) / Router.WALK + TRANSFER
        }
    }

    /** Length walked (m): stairs and escalators along the slope plus landings; a lift ride is not walked. */
    fun length(kind: String, ea: Double, ez: Double): Double {
        val h = abs(ez - ea)
        return when (kind) {
            "lift" -> 0.0
            "escalator" -> 2.0 * h
            else -> 2.0 * h + LANDING * flights(h)
        }
    }

    /** Steps on a stair between floors this far apart. */
    fun steps(h: Double): Double = abs(h) / RISER
}

/**
 * Floor changes from the walker's own motion (30.10), next to Wi-Fi:
 * - stairs: steps counted while the position stays in a stairwell. A floor is ≈ height / 0.17 m risers (ГМЦ ≈ 31 steps, Рекордов
 *   ≈ 19–21). With the barometer agreeing (≥ 60 % of the height in one direction) 40 % of the steps are enough and the direction
 *   is known; without it ~90 % of the steps on switchback flights (little net horizontal motion), both neighbouring floors.
 *   The barometer alone does not decide: hand heat moves it by up to 0.18 hPa/s (≈ 1.5 m/s).
 * - lift: standing at a lift, the ride is seen in the vertical acceleration (world frame from the rotation vector): a start
 *   push ≥ 0.25 m/s² for ≥ 0.4 s, a cruise of ≥ 1.5 s, the opposite push at the stop. Height = cruise speed × time (+ the
 *   pushes), the barometer refines it when it agrees; the target is the lift's floor nearest to that height. Heating has no
 *   acceleration, so standing next to a lift with a warm phone does not move floors.
 */
class VerticalTracker(private val b: Building) {
    class Transit(val conn: Connector, val fromFloor: Int, val to: List<Int>, val known: Boolean, val why: String, val tNs: Long,
                  val height: Double)
    val log = ArrayList<Transit>()

    // ------------------------------------------------------------ barometric height (relative, m), 0.5 s samples
    private val hT = LongArray(256); private val hV = DoubleArray(256); private var hN = 0
    private var lp = Double.NaN; private var lpT = 0L
    fun pressure(t: Long, hpa: Double) {
        if (hpa < 800 || hpa > 1100) return
        if (lp.isNaN()) { lp = hpa; lpT = t }
        val dt = ((t - lpT) / 1e9).coerceIn(0.0, 1.0); lpT = t
        lp += (hpa - lp) * kotlin.math.min(1.0, dt / 1.0)
        if (hN > 0 && t - hT[(hN - 1) % hT.size] < 500_000_000L) return
        hT[hN % hT.size] = t; hV[hN % hV.size] = -lp * M_PER_HPA; hN++
    }
    /** Barometric height at t (nearest sample ≤ 2 s away), NaN without a barometer. */
    fun heightAt(t: Long): Double {
        val m = kotlin.math.min(hN, hT.size); var best = Double.NaN; var bd = Long.MAX_VALUE
        for (j in 0 until m) { val d = abs(hT[j] - t); if (d < bd) { bd = d; best = hV[j] } }
        return if (bd <= 2_000_000_000L) best else Double.NaN
    }

    private fun near(floor: Int, x: Double, y: Double, kinds: Set<String>, r: Double): Connector? =
        b.conns.filter { it.kind in kinds }.mapNotNull { c -> c.pos[floor]?.let { p -> c to kotlin.math.hypot(p[0] - x, p[1] - y) } }
            .filter { it.second <= r }.minByOrNull { it.second }?.first

    private fun neighbours(c: Connector, floor: Int): Pair<Int?, Int?> {
        val e = b.elevOf(floor)
        val fl = c.floors.filter { it != floor && c.pos[it] != null && b.fi(it) >= 0 }
        return fl.filter { b.elevOf(it) > e }.minByOrNull { b.elevOf(it) } to fl.filter { b.elevOf(it) < e }.maxByOrNull { b.elevOf(it) }
    }

    // ------------------------------------------------------------ stairs
    private var sc: Connector? = null; private var sFloor = 0; private var sSteps = 0; private var sAway = 0
    private var sH0 = Double.NaN; private var dx = 0.0; private var dy = 0.0; private var path = 0.0
    var stairSteps = 0; private set

    private fun startStair(c: Connector, floor: Int, t: Long) {
        sc = c; sFloor = floor; sSteps = 0; sAway = 0; sH0 = heightAt(t); dx = 0.0; dy = 0.0; path = 0.0
    }

    /** One step (yaw: device heading, any frame consistent within the walk); est = the filter's main mode. */
    /** Stairs from steps (+ barometer): off by default — on the recordings it fired mostly falsely (see the class comment). */
    var stairs = false

    fun step(t: Long, yaw: Double, est: ParticleFilter.Est): Transit? {
        if (!stairs) return null
        val floor = b.floorOf(est.fi)
        val c = near(floor, est.x, est.y, STAIR_KINDS, STAIR_R)
        val cur = sc
        if (c != null && (cur == null || c.id != cur.id || floor != sFloor)) startStair(c, floor, t)
        else if (c == null) { if (cur == null) return null; if (++sAway > 4) { sc = null; stairSteps = 0; return null } }
        else sAway = 0
        val c0 = sc ?: return null
        sSteps++; stairSteps = sSteps
        if (!yaw.isNaN()) { dx += kotlin.math.sin(yaw) * STEP; dy += kotlin.math.cos(yaw) * STEP; path += STEP }
        val (up, dn) = neighbours(c0, sFloor)
        val hUp = up?.let { b.elevOf(it) - b.elevOf(sFloor) }; val hDn = dn?.let { b.elevOf(sFloor) - b.elevOf(it) }
        val dh = heightAt(t) - sH0
        if (!dh.isNaN()) {
            if (up != null && dh >= 0.6 * hUp!! && sSteps >= 0.4 * Vertical.steps(hUp)) return fire(c0, sFloor, listOf(up), true, "лестница+барометр", t, dh)
            if (dn != null && -dh >= 0.6 * hDn!! && sSteps >= 0.4 * Vertical.steps(hDn)) return fire(c0, sFloor, listOf(dn), true, "лестница+барометр", t, dh)
        }
        val hMin = listOfNotNull(hUp, hDn).minOrNull() ?: return null
        val ratio = kotlin.math.hypot(dx, dy) / kotlin.math.max(path, 1e-9)
        if (sSteps >= 0.9 * Vertical.steps(hMin) && ratio <= 0.45)
            return fire(c0, sFloor, listOfNotNull(up, dn), false, "лестница: шаги", t, if (dh.isNaN()) 0.0 else dh)
        return null
    }

    // ------------------------------------------------------------ lift (vertical acceleration)
    private var lc: Connector? = null; private var lFloor = 0; private var lH0 = Double.NaN
    private var bias = Double.NaN; private var aLp = 0.0; private var lastA = 0L
    private var phase = 0                      // 0 idle, 1 start push, 2 cruise, 3 stop push
    private var v = 0.0; private var z = 0.0; private var pushT = 0L; private var quietT = 0L; private var cruiseT0 = 0L; private var sign = 0
    private var lastStepT = 0L
    var rides = 0; private set

    fun stepSeen(t: Long) { lastStepT = t; phase = 0; v = 0.0; z = 0.0 }

    /** World vertical acceleration (m/s², gravity included) at t; est = the main mode (null: unknown). */
    fun accel(t: Long, az: Double, est: ParticleFilter.Est?): Transit? {
        val dt = if (lastA == 0L) 0.0 else ((t - lastA) / 1e9).coerceIn(0.0, 0.1); lastA = t
        if (dt <= 0) { aLp = az; bias = az; return null }
        aLp += (az - aLp) * (dt / (LP_RC + dt))
        val standing = t - lastStepT > 2_000_000_000L
        val e = est
        val floor = e?.let { b.floorOf(it.fi) }
        val c = if (e != null && standing) near(floor!!, e.x, e.y, LIFT_KINDS, LIFT_R) else null
        if (c == null) { if (phase != 2) { lc = null; phase = 0 }; bias = if (bias.isNaN()) aLp else bias + (aLp - bias) * (dt / 4.0); return null }
        if (lc?.id != c.id || floor != lFloor && phase == 0) { lc = c; lFloor = floor!!; lH0 = heightAt(t); phase = 0; v = 0.0; z = 0.0 }
        val a = aLp - (if (bias.isNaN()) aLp else bias)
        when (phase) {
            0 -> {
                bias = if (bias.isNaN()) aLp else bias + (aLp - bias) * (dt / 4.0)
                if (abs(a) > PUSH) { if (pushT == 0L) { pushT = t; v = 0.0 }; v += a * dt
                    if (t - pushT >= 400_000_000L) { phase = 1; sign = if (a > 0) 1 else -1 } }
                else { pushT = 0L; v = 0.0 }
            }
            1 -> { v += a * dt; z += v * dt
                if (abs(a) < PUSH * 0.4) { if (quietT == 0L) quietT = t; if (t - quietT > 300_000_000L) { phase = 2; cruiseT0 = t; quietT = 0L } } else quietT = 0L
                if (t - pushT > 4_000_000_000L) phase = 0 }
            2 -> { z += v * dt
                if (abs(v) < 0.3 || t - cruiseT0 > 60_000_000_000L) { phase = 0; return null }
                if (a * sign < -PUSH && t - cruiseT0 >= 1_500_000_000L) { phase = 3; pushT = t } }
            3 -> { v += a * dt; z += v * dt
                if (abs(a) < PUSH * 0.4) {
                    phase = 0; rides++
                    val dhB = heightAt(t) - lH0
                    // barometer agrees in sign and roughly in size -> mean of both, else the acceleration alone
                    val h = if (!dhB.isNaN() && dhB * z > 0 && abs(dhB) in abs(z) * 0.5..abs(z) * 1.6) (dhB + z) / 2 else z
                    val c0 = lc ?: return null
                    val target = c0.floors.filter { it != lFloor && c0.pos[it] != null && b.fi(it) >= 0 }
                        .minByOrNull { abs(b.elevOf(it) - b.elevOf(lFloor) - h) } ?: return null
                    val hT = b.elevOf(target) - b.elevOf(lFloor)
                    if (abs(h - hT) <= 0.4 * abs(hT) + 0.5) return fire(c0, lFloor, listOf(target), true, "лифт", t, h)
                }
                if (t - pushT > 5_000_000_000L) phase = 0 }
        }
        return null
    }

    private fun fire(c: Connector, from: Int, to: List<Int>, known: Boolean, why: String, t: Long, h: Double): Transit {
        val tr = Transit(c, from, to, known, why, t, h); log.add(tr)
        if (known && to.size == 1) { if (c.kind == "lift") { lFloor = to[0]; lH0 = heightAt(t) } else startStair(c, to[0], t) }
        else { sSteps = 0; dx = 0.0; dy = 0.0; path = 0.0; sH0 = heightAt(t) }
        return tr
    }

    companion object {
        const val M_PER_HPA = 8.43
        const val STEP = 0.58
        const val STAIR_R = 9.0
        const val LIFT_R = 6.0
        const val PUSH = 0.25
        private const val LP_RC = 1.0 / (2 * Math.PI * 2.0)
        private val STAIR_KINDS = setOf("stair")
        private val LIFT_KINDS = setOf("lift")
    }
}
