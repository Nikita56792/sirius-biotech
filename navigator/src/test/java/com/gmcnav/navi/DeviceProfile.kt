package com.gmcnav.navi

import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.ReplayEvent
import com.gmcnav.navi.core.ReplayPart
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Another phone emulated on a recording of the calibration phone (S24 Ultra: accelerometer ~116 Hz, game rotation vector and
 * rotation vector ~23 Hz in the replay, barometer 25 Hz, Wi-Fi scan every ~2 s):
 * accHz / gameHz — decimated rates; accNoise — white noise, m/s²; gyroDriftDegMin — slow yaw drift of the game rotation vector
 * (cheap gyro); gameYawNoiseDeg — jitter; noGyro — no game rotation vector at all (budget phones: then the rotation vector is the
 * accelerometer + magnetometer one, emulated by compassNoiseDeg on the recorded one); compassBiasDeg — constant compass error
 * (bad calibration, magnetised case); handDeg — phone held turned by this angle to the walking direction (both rotation vectors);
 * noBaro; scanEvery — every n-th recorded scan (slower scans); rssi — RSSI offset (APs below −92 dBm dropped).
 */
data class DeviceProfile(
    val name: String, val accHz: Double = 0.0, val accNoise: Double = 0.0, val gameHz: Double = 0.0, val gyroDriftDegMin: Double = 0.0,
    val gameYawNoiseDeg: Double = 0.0, val noGyro: Boolean = false, val compassNoiseDeg: Double = 0.0, val compassBiasDeg: Double = 0.0,
    val handDeg: Double = 0.0, val noBaro: Boolean = false, val scanEvery: Int = 1, val rssi: Float = 0f,
) {
    inner class Feeder(private val rp: ReplayPart, seed: Long) {
        private val rnd = Random(seed)
        private var lastAcc = Long.MIN_VALUE / 2; private var lastGame = Long.MIN_VALUE / 2; private var lastRot = Long.MIN_VALUE / 2
        private var scanN = 0
        private var qi = -1     // latest recorded game rotation vector (the true orientation, for turning the accelerometer)

        /** Feeds a non-scan event as this phone would deliver it. */
        fun feed(ev: ReplayEvent, eng: PositionEngine) {
            val i = ev.i
            when (ev.kind) {
                0 -> {
                    if (accHz > 0 && ev.t - lastAcc < 1e9 / accHz - 1e6) return
                    lastAcc = ev.t
                    fun n() = if (accNoise > 0) (rnd.nextGaussian() * accNoise).toFloat() else 0f
                    var a0 = rp.acc[i * 3].toDouble(); var a1 = rp.acc[i * 3 + 1].toDouble(); var a2 = rp.acc[i * 3 + 2].toDouble()
                    if (handDeg != 0.0 && qi >= 0) {
                        // the phone turned by handDeg about the vertical: the same physical acceleration read along other
                        // phone axes — a' = R(q')ᵀ · R(q) · a
                        val r = rot(rp.q, qi); val w0 = r[0] * a0 + r[1] * a1 + r[2] * a2; val w1 = r[3] * a0 + r[4] * a1 + r[5] * a2
                        val w2 = r[6] * a0 + r[7] * a1 + r[8] * a2
                        val rt = rot(turn(rp.q, qi, handDeg))
                        a0 = rt[0] * w0 + rt[3] * w1 + rt[6] * w2; a1 = rt[1] * w0 + rt[4] * w1 + rt[7] * w2; a2 = rt[2] * w0 + rt[5] * w1 + rt[8] * w2
                    }
                    eng.onAccel(ev.t, (a0 + n()).toFloat(), (a1 + n()).toFloat(), (a2 + n()).toFloat())
                }
                1 -> {
                    qi = i
                    if (noGyro) return
                    if (gameHz > 0 && ev.t - lastGame < 1e9 / gameHz - 1e6) return
                    lastGame = ev.t
                    val drift = gyroDriftDegMin * (ev.t - rp.t0) / 60e9
                    val q = turn(rp.q, i, handDeg + drift + if (gameYawNoiseDeg > 0) rnd.nextGaussian() * gameYawNoiseDeg else 0.0)
                    eng.onGameRot(ev.t, q[0], q[1], q[2], q[3])
                }
                2 -> {
                    if (noGyro && ev.t - lastRot < 1e9 / 15) return    // accelerometer + magnetometer fusion: ~15 Hz
                    lastRot = ev.t
                    val q = turn(rp.r, i, handDeg + compassBiasDeg + if (compassNoiseDeg > 0) rnd.nextGaussian() * compassNoiseDeg else 0.0)
                    eng.onRotVec(ev.t, q[0], q[1], q[2], q[3])
                }
                3 -> {}
                4 -> rp.feed(ev, eng)
                5 -> if (!noBaro) rp.feed(ev, eng)
            }
        }

        /** Whether this phone produces this recorded scan, and its readings. */
        fun scan(ev: ReplayEvent): Map<String, Float>? {
            if (scanN++ % scanEvery != 0) return null
            val s = rp.scans[ev.i]
            return if (rssi == 0f) s else s.mapValues { it.value + rssi }.filterValues { it >= -92f }
        }
    }

    companion object {
        fun rot(a: FloatArray, i: Int) = rot(doubleArrayOf(a[i * 4].toDouble(), a[i * 4 + 1].toDouble(), a[i * 4 + 2].toDouble(), a[i * 4 + 3].toDouble()))
        /** Row-major rotation matrix (phone → world) of a quaternion (x, y, z, w). */
        fun rot(q: DoubleArray): DoubleArray {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            return doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y))
        }

        /**
         * The rotation vector turned about the vertical so that its yaw (Orientation.yaw, clockwise) grows by deg:
         * world rotation by −deg (counter-clockwise positive), q' = qz ⊗ q.
         */
        fun turn(a: FloatArray, i: Int, deg: Double): DoubleArray {
            val x = a[i * 4].toDouble(); val y = a[i * 4 + 1].toDouble(); val z = a[i * 4 + 2].toDouble(); val w = a[i * 4 + 3].toDouble()
            if (deg == 0.0) return doubleArrayOf(x, y, z, w)
            val h = Math.toRadians(-deg) / 2; val cz = sin(h); val cw = cos(h)
            // (0, 0, cz, cw) ⊗ (x, y, z, w)
            return doubleArrayOf(cw * x - cz * y, cw * y + cz * x, cw * z + cz * w, cw * w - cz * z)
        }
    }
}
