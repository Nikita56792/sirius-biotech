package com.gmcnav.navi.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walking direction relative to the phone ("hand angle"), from the accelerometer alone: while walking the horizontal
 * acceleration swings along the walking direction. The accelerometer is turned into the world frame by the current
 * orientation, the slow part removed (1 s mean), and over a 2.5 s window the principal axis of the horizontal acceleration is
 * the walking axis; forward vs backward from the phase against the vertical acceleration (forward acceleration rises with it).
 * hand = walking direction − phone forward yaw (Orientation.yaw), smoothed over windows.
 * On the recordings (phone in hand): axis error median 6–7.5°, p90 17–24° (offline); here, with a 4 Hz low-pass first, a single
 * window is within 25° in 90–91 % (116, 50 + noise and 25 Hz accelerometers alike), the smoothed estimate in 95–96 %. Better than
 * the compass, and it does not care how the phone is held (sideways, in a pocket), which the compass prior alone assumes.
 */
class WalkAxis {
    private val n = 512
    private val t = LongArray(n); private val ax = DoubleArray(n); private val ay = DoubleArray(n); private val az = DoubleArray(n)
    private val yw = DoubleArray(n)
    private var k = 0
    private var mx = 0.0; private var my = 0.0; private var mz = 0.0; private var lastT = 0L
    private var l1x = 0.0; private var l1y = 0.0; private var l1z = 0.0; private var l2x = 0.0; private var l2y = 0.0; private var l2z = 0.0
    private var lastEval = 0L
    private var hs = 0.0; private var hc = 0.0
    /** Smoothed hand angle (rad), NaN until a few walking windows were seen. */
    var hand = Double.NaN; private set
    var windows = 0; private set
    /** The last window's own estimate (rad) and the smoothed vector length (consistency, 0–1). */
    var lastRaw = Double.NaN; private set
    val consistency: Double get() = sqrt(hs * hs + hc * hc)

    fun reset() { k = 0; lastT = 0L; lastEval = 0L; hs = 0.0; hc = 0.0; hand = Double.NaN; windows = 0 }

    /** Accelerometer sample (phone frame) with the current orientation quaternion (x, y, z, w) and its phone yaw. */
    fun add(tNs: Long, a0: Double, a1: Double, a2: Double, x: Double, y: Double, z: Double, w: Double, yaw: Double) {
        // world = R(q) · a
        val wx = (1 - 2 * (y * y + z * z)) * a0 + 2 * (x * y - z * w) * a1 + 2 * (x * z + y * w) * a2
        val wy = 2 * (x * y + z * w) * a0 + (1 - 2 * (x * x + z * z)) * a1 + 2 * (y * z - x * w) * a2
        val wz = 2 * (x * z - y * w) * a0 + 2 * (y * z + x * w) * a1 + (1 - 2 * (x * x + y * y)) * a2
        val dt = if (lastT == 0L) 0.0 else ((tNs - lastT) / 1e9).coerceIn(0.0, 0.5)
        if (lastT == 0L || tNs - lastT > 500_000_000L) { mx = wx; my = wy; mz = wz; l1x = wx; l1y = wy; l1z = wz; l2x = wx; l2y = wy; l2z = wz }
        lastT = tNs
        val a = (dt / 1.0).coerceAtMost(1.0)
        mx += a * (wx - mx); my += a * (wy - my); mz += a * (wz - mz)
        // two 1st-order low-passes at 4 Hz (same delay on all axes, so the forward/vertical phase is kept): the impacts above
        // that alias at low sampling rates and flipped the forward/backward decision (25 Hz: right in 59 % of windows)
        val b = dt / (LP_RC + dt)
        l1x += b * (wx - l1x); l1y += b * (wy - l1y); l1z += b * (wz - l1z)
        l2x += b * (l1x - l2x); l2y += b * (l1y - l2y); l2z += b * (l1z - l2z)
        val i = k % n
        t[i] = tNs; ax[i] = l2x - mx; ay[i] = l2y - my; az[i] = l2z - mz; yw[i] = yaw; k++
        if (tNs - lastEval >= 500_000_000L) { lastEval = tNs; evaluate(tNs) }
    }

    private fun evaluate(now: Long) {
        val m = minOf(k, n)
        var cnt = 0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var sz = 0.0; var szz = 0.0; var ys = 0.0; var yc = 0.0
        var first = -1
        for (j in 0 until m) {
            val i = (k - 1 - j) % n
            if (now - t[i] > WIN_NS) break
            first = i; cnt++
            sxx += ax[i] * ax[i]; syy += ay[i] * ay[i]; sxy += ax[i] * ay[i]; sz += az[i]; szz += az[i] * az[i]
            ys += sin(yw[i]); yc += cos(yw[i])
        }
        if (cnt < 40 || first < 0) return
        val vzSd = sqrt(maxOf(0.0, szz / cnt - (sz / cnt) * (sz / cnt)))
        if (vzSd < WALK_SD) return                               // not walking
        if (sqrt(ys * ys + yc * yc) / cnt < STRAIGHT) return     // the phone turned inside the window (a turn): axis smeared
        // principal axis of [[sxx, sxy], [sxy, syy]]
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val l1 = tr / 2 + sqrt(maxOf(0.0, tr * tr / 4 - det)); val l2 = tr - l1
        if (l1 < 2 * maxOf(l2, 1e-9)) return                     // no clear axis
        val ex: Double; val ey: Double
        if (abs(sxy) > 1e-12) { ex = l1 - syy; ey = sxy } else if (sxx >= syy) { ex = 1.0; ey = 0.0 } else { ex = 0.0; ey = 1.0 }
        var psi = atan2(ex, ey)                                   // clockwise from world y, like Orientation.yaw
        // forward: the forward acceleration correlates with the rate of the vertical one
        var c = 0.0; var prevZ = Double.NaN
        for (j in cnt - 1 downTo 0) {
            val i = (k - 1 - j) % n
            if (!prevZ.isNaN()) c += (ax[i] * ex + ay[i] * ey) * (az[i] - prevZ)
            prevZ = az[i]
        }
        if (c < 0) psi += PI
        val h = Orientation.wrap(psi - atan2(ys, yc))
        lastRaw = h
        // smoothing: circular, a new window weighs 0.25 (re-grips are followed within a few seconds)
        if (windows == 0) { hs = sin(h); hc = cos(h) } else { hs += 0.25 * (sin(h) - hs); hc += 0.25 * (cos(h) - hc) }
        windows++
        // only a consistent estimate is used (smoothed vector ≥ 0.7; recorded walks: median 0.99, p10 0.94)
        hand = if (windows >= 3 && hs * hs + hc * hc > 0.49) atan2(hs, hc) else Double.NaN
    }

    companion object {
        const val WIN_NS = 2_500_000_000L
        private const val LP_RC = 1.0 / (2 * PI * 4.0)
        /** Vertical acceleration sd (m/s², after the 4 Hz low-pass) above which the window counts as walking (0.45: more windows, less right). */
        private const val WALK_SD = 0.6
        /** Phone yaw consistency inside the window (mean resultant length): 0.97 ≈ ±14° — straight walking only. */
        private const val STRAIGHT = 0.97
    }
}
