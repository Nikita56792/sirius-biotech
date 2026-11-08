package com.gmcnav.navi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.GroundTruth
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.ReplayPart
import com.gmcnav.navi.core.Route
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.sin

/** Status line data for the UI. */
class SourceStatus {
    @Volatile var mode = ""
    @Volatile var scans = 0
    @Volatile var aps = 0
    @Volatile var steps = 0
    @Volatile var cells = 0
    @Volatile var scanIntervalS = 0f
    @Volatile var lastScanNs = 0L
    @Volatile var error = ""
    @Volatile var truth: GroundTruth? = null
    @Volatile var replayPos = ""
    /** Live Wi-Fi limited by Android to 4 scans / 2 min (no developer option); scans per last minute: all, ours, the system's. */
    @Volatile var limited = false
    @Volatile var scansMin = 0
    @Volatile var ownMin = 0
    @Volatile var sysMin = 0
}

/**
 * Owns the positioning engine on its own thread and one source feeding it:
 * live (Wi-Fi scans back to back, accelerometer/game rotation/rotation vector/pressure, cell info when not in airplane mode),
 * replay of a recorded calibration part (demo), or a simulated walk along the active route.
 */
class Positioning(private val ctx: Context, private val onFix: (Fix) -> Unit) {
    private val thread = HandlerThread("pos").also { it.start() }
    val h = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    val status = SourceStatus()
    @Volatile var engine: PositionEngine? = null; private set
    private var live: LiveSource? = null
    private var replay: ReplayRunner? = null
    private var sim: SimRunner? = null

    fun running() = live != null || replay != null || sim != null

    /**
     * Source generation: [stop] bumps it, and fixes of an older source (still queued on the main thread, or from a start
     * that was posted before the stop) are dropped — after a reset or a site / source switch no old position comes back.
     */
    @Volatile private var gen = 0

    private fun newEngine(g: Int): PositionEngine {
        val e = PositionEngine(Core.b, Core.radio, Core.cells)
        e.onFix = { f -> main.post { if (g == gen) onFix(f) } }
        engine = e
        return e
    }

    fun startLive() {
        stop(); val g = gen
        h.post { if (g != gen) return@post; val e = newEngine(g); live = LiveSource(ctx, e, h, status).also { it.start() }; status.mode = "Wi-Fi + шаги" }
    }

    fun startReplay(file: String, speed: Double) {
        stop(); val g = gen
        h.post {
            if (g != gen) return@post
            val e = newEngine(g)
            val idx = Core.replayIndex
            val rp = ReplayPart(ctx.assets.open(file).readBytes(), idx.bssids, idx.cellKeys)
            replay = ReplayRunner(rp, e, speed, status).also { it.start() }
            status.mode = "Повтор записи · часть ${rp.part}"
        }
    }

    fun startSim(routeProvider: () -> Route?) {
        stop(); engine = null; val g = gen
        sim = SimRunner(routeProvider, status) { f -> main.post { if (g == gen) onFix(f) } }.also { it.start() }
        status.mode = "Симуляция"
    }

    fun setReplaySpeed(v: Double) { replay?.speed = v; sim?.speed = v }

    /** Stops the source; live / replay are stopped on the positioning thread, after any start posted before. */
    fun stop() {
        gen++
        h.post {
            live?.stop(); live = null
            replay?.stop(); replay = null
        }
        sim?.stop(); sim = null
        engine = null
        status.mode = ""; status.replayPos = ""; status.truth = null
    }

    fun quit() { stop(); thread.quitSafely() }
}

// ====================================================================== live
/** Our own scans when Android limits them to 4 per 2 min: evenly, one per 30 s. */
private const val LIMITED_GAP_NS = 30_000_000_000L

class LiveSource(private val ctx: Context, private val eng: PositionEngine, private val h: Handler, private val st: SourceStatus) {
    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    @Volatile private var on = false
    private var lastReqNs = 0L
    private var retry = false
    private val exec = Executor { h.post(it) }

    // ---- Android limits Wi-Fi scans to 4 per 2 min (phones without the developer option). Without the limit (the developer
    // phone) nothing below changes the 29.10 behaviour: scans back to back. With it (03.11: requesting back to back hammered
    // startScan 200 times a second, 4 scans went at once and then 2 min without a position):
    //  - our scan once per 30 s (evenly spread was the best policy for this engine on the replays);
    //  - the system location service is asked for a network fix every second: it scans Wi-Fi itself, outside our limit, and
    //    every scan on the phone reaches us through SCAN_RESULTS_AVAILABLE_ACTION.
    private var limited = false
    private var refusedInRow = 0
    private var lastLimitCheckNs = 0L
    private var lastOkReqNs = 0L
    private var ownPending = false
    private val ownT = ArrayDeque<Long>()
    private val sysT = ArrayDeque<Long>()
    private val lm = ctx.getSystemService(android.location.LocationManager::class.java)
    private val sysListener = android.location.LocationListener { }

    private fun checkLimited() {
        if (Build.VERSION.SDK_INT >= 30) setLimited(runCatching { wifi.isScanThrottleEnabled }.getOrDefault(false))
    }

    private fun setLimited(v: Boolean) {
        if (v == limited) return
        limited = v; st.limited = v
        if (v) startSysScans() else stopSysScans()
    }

    @SuppressLint("MissingPermission")
    private fun startSysScans() {
        if (!sysScans) return
        runCatching {
            if (lm != null && lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 1000L, 0f, sysListener, h.looper)
        }
    }

    private fun stopSysScans() { runCatching { lm?.removeUpdates(sysListener) } }

    private fun countScan(now: Long, own: Boolean) {
        (if (own) ownT else sysT).addLast(now)
        for (q in listOf(ownT, sysT)) while (q.isNotEmpty() && now - q.first() > 60_000_000_000L) q.removeFirst()
        st.ownMin = ownT.size; st.sysMin = sysT.size; st.scansMin = ownT.size + sysT.size
        android.util.Log.i("NaviScan", "result ${if (own) "own" else "system"} limited=$limited perMin=${st.scansMin} own=${st.ownMin} sys=${st.sysMin}")
    }

    companion object {
        /** Ask the location service for system Wi-Fi scans when limited (intent extra sysscan=false turns it off for tests). */
        @Volatile var sysScans = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onScan(i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
    }

    private val sensors = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> { eng.onAccel(e.timestamp, v[0], v[1], v[2]); st.steps = eng.stepCount }
                Sensor.TYPE_GAME_ROTATION_VECTOR -> eng.onGameRot(e.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), w(v))
                Sensor.TYPE_ROTATION_VECTOR -> eng.onRotVec(e.timestamp, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), w(v))
                Sensor.TYPE_PRESSURE -> eng.onPressure(e.timestamp, v[0].toDouble())
            }
        }
        private fun w(v: FloatArray): Double = if (v.size >= 4) v[3].toDouble() else Math.sqrt(maxOf(0.0, 1.0 - v[0] * v[0] - v[1] * v[1] - v[2] * v[2].toDouble()))
        override fun onAccuracyChanged(s: Sensor, a: Int) {}
    }

    fun start() {
        on = true
        ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), null, h)
        for ((type, us) in listOf(Sensor.TYPE_ACCELEROMETER to 10_000, Sensor.TYPE_GAME_ROTATION_VECTOR to 20_000,
                                   Sensor.TYPE_ROTATION_VECTOR to 40_000, Sensor.TYPE_PRESSURE to 40_000)) {
            sm.getDefaultSensor(type)?.let { sm.registerListener(sensors, it, us, 0, h) }
        }
        checkLimited()
        requestScan()
        h.post(tick)
    }

    fun stop() {
        on = false
        h.post {
            runCatching { ctx.unregisterReceiver(receiver) }
            runCatching { sm.unregisterListener(sensors) }
            stopSysScans()
            h.removeCallbacks(tick)
        }
    }

    private var lastCellNs = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!on) return
            val now = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= 30 && now - lastLimitCheckNs > 10_000_000_000L) { lastLimitCheckNs = now; checkLimited() }
            if (limited) {
                pollResults(now)
                // one scan of ours per 30 s; a refused one (the limit still counts older requests) is tried again in 5 s
                if (now - lastOkReqNs >= LIMITED_GAP_NS && now - lastReqNs >= 5_000_000_000L) requestScan()
            } else if (!retry && now - maxOf(lastReqNs, st.lastScanNs) > 6_000_000_000L) requestScan()   // watchdog
            val air = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            if (!air && now - lastCellNs > 1_500_000_000L) { lastCellNs = now; requestCells() }
            h.postDelayed(this, 1000)
        }
    }

    private fun requestScan() {
        if (!on) return
        retry = false
        @Suppress("DEPRECATION") val ok = runCatching { wifi.startScan() }.getOrDefault(false)
        lastReqNs = SystemClock.elapsedRealtimeNanos()
        if (ok) { lastOkReqNs = lastReqNs; ownPending = true; refusedInRow = 0 } else refusedInRow++
        // Android 9–10 cannot be asked: two refusals in a row there = the limit (Android 11+ asks wifi.isScanThrottleEnabled)
        if (Build.VERSION.SDK_INT < 30 && refusedInRow >= 2) setLimited(true)
        if (limited) return     // the tick asks again; never retry right away (that is what hammered startScan)
        if (!ok) { retry = true; h.postDelayed({ if (retry) requestScan() }, 2000) }
    }

    @SuppressLint("MissingPermission")
    private fun onScan(updated: Boolean) {
        if (!on) return
        val now = SystemClock.elapsedRealtimeNanos()
        val res = runCatching { wifi.scanResults }.getOrElse { st.error = "нет доступа к сканам Wi-Fi"; emptyList() }
        if (updated) {
            // limited: the same results may have been taken already by pollResults
            if (!limited || (res.maxOfOrNull { it.timestamp } ?: 0L) > seenResUs + 500_000L) consume(res, now)
        } else if (ownPending && now - lastOkReqNs < 10_000_000_000L) ownPending = false   // our scan failed
        if (!limited) requestScan()     // back to back (scan throttling is off on the test phone)
    }

    private fun consume(res: List<android.net.wifi.ScanResult>, now: Long) {
        val ap = HashMap<String, Float>()
        for (r in res) if (now - r.timestamp * 1000 < 6_000_000_000L) ap[r.BSSID.lowercase()] = r.level.toFloat()
        seenResUs = maxOf(seenResUs, res.maxOfOrNull { it.timestamp } ?: 0L)
        if (st.lastScanNs > 0) st.scanIntervalS = st.scanIntervalS * 0.7f + (now - st.lastScanNs) / 1e9f * 0.3f
        st.lastScanNs = now; st.scans++; st.aps = ap.size
        countScan(now, ownPending && now - lastOkReqNs < 10_000_000_000L)
        ownPending = false
        eng.onWifi(now, ap)
    }

    /** Newest ScanResult.timestamp (µs since boot) already given to the engine. */
    private var seenResUs = 0L

    /**
     * Limited: the system location service scans through WifiScanner, and those results land in getScanResults() WITHOUT the
     * SCAN_RESULTS_AVAILABLE broadcast (03.11, S24: network fixes every ~20 s, 0 broadcasts in 3 min). Reading the list is
     * free (not limited): newer results than the last scan used = a fresh scan of the system.
     */
    @SuppressLint("MissingPermission")
    private fun pollResults(now: Long) {
        val res = runCatching { wifi.scanResults }.getOrNull() ?: return
        val newest = res.maxOfOrNull { it.timestamp } ?: return
        if (newest <= seenResUs + 500_000L) return
        if (now - newest * 1000 > 6_000_000_000L) { seenResUs = newest; return }   // an old cache, not a fresh scan
        consume(res, now)
    }

    @SuppressLint("MissingPermission")
    private fun requestCells() {
        runCatching {
            tm.requestCellInfoUpdate(exec, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(list: List<CellInfo>) { if (on) onCells(list) }
            })
        }
    }

    private fun onCells(list: List<CellInfo>) {
        val now = SystemClock.elapsedRealtimeNanos()
        val m = HashMap<String, Float>()
        fun put(type: String, ch: Int, id: Int, dbm: Int) {
            if (ch == Int.MAX_VALUE || ch < 0 || id == Int.MAX_VALUE || id < 0 || dbm == Int.MAX_VALUE || dbm < -150 || dbm > -20) return
            m["$type:$ch:$id"] = dbm.toFloat()
        }
        for (ci in list) {
            val ts = if (Build.VERSION.SDK_INT >= 30) ci.timestampMillis * 1_000_000 else @Suppress("DEPRECATION") ci.timeStamp
            if (now - ts >= 10_000_000_000L) continue
            when (ci) {
                is CellInfoLte -> put("L", ci.cellIdentity.earfcn, ci.cellIdentity.pci, ci.cellSignalStrength.rsrp)
                is CellInfoNr -> put("N", (ci.cellIdentity as CellIdentityNr).nrarfcn, (ci.cellIdentity as CellIdentityNr).pci, (ci.cellSignalStrength as CellSignalStrengthNr).ssRsrp)
                is CellInfoGsm -> put("G", ci.cellIdentity.arfcn, ci.cellIdentity.bsic, ci.cellSignalStrength.dbm)
                is CellInfoWcdma -> put("W", ci.cellIdentity.uarfcn, ci.cellIdentity.psc, ci.cellSignalStrength.dbm)
            }
        }
        st.cells = m.size
        if (m.isNotEmpty()) eng.onCells(now, m)
    }
}

// ====================================================================== replay
class ReplayRunner(private val rp: ReplayPart, private val eng: PositionEngine, @Volatile var speed: Double, private val st: SourceStatus) {
    @Volatile private var on = true
    private var th: Thread? = null

    fun start() {
        th = Thread({
            val ev = rp.events()
            val t0 = ev.firstOrNull()?.t ?: return@Thread
            var wall0 = SystemClock.elapsedRealtimeNanos(); var sim0 = t0; var lastSpeed = speed
            for (e in ev) {
                if (!on) break
                if (speed != lastSpeed) { wall0 = SystemClock.elapsedRealtimeNanos(); sim0 = e.t; lastSpeed = speed }
                val due = wall0 + ((e.t - sim0) / speed).toLong()
                val wait = due - SystemClock.elapsedRealtimeNanos()
                if (wait > 2_000_000) try { Thread.sleep(wait / 1_000_000) } catch (_: InterruptedException) { break }
                synchronized(eng) { rp.feed(e, eng) }
                if (e.kind == 3) {
                    st.scans++; st.aps = rp.scans[e.i].size; st.lastScanNs = SystemClock.elapsedRealtimeNanos()
                    st.truth = rp.truth(e.t)
                    st.replayPos = "%d:%02d / %d:%02d".format((e.t - t0) / 60_000_000_000L, (e.t - t0) / 1_000_000_000L % 60,
                        (rp.t1 - rp.t0) / 60_000_000_000L, (rp.t1 - rp.t0) / 1_000_000_000L % 60)
                }
                if (e.kind == 4) st.cells = rp.cells[e.i].size
                st.steps = eng.stepCount
            }
        }, "replay").also { it.start() }
    }

    fun stop() { on = false; th?.interrupt() }
}

// ====================================================================== simulation
/** Walks along the active route at 1.25 m/s (dwell at lifts/stairs), small noise — to test the UI and voice at a desk. */
class SimRunner(private val route: () -> Route?, private val st: SourceStatus, private val emit: (Fix) -> Unit) {
    @Volatile var speed = 1.0
    @Volatile private var on = true
    private var th: Thread? = null
    private var cur: Route? = null
    private var s = 0.0
    private var dwell = 0.0
    private var steps = 0

    fun start() {
        th = Thread({
            val rnd = java.util.Random(3)
            var t = SystemClock.elapsedRealtimeNanos()
            while (on) {
                try { Thread.sleep((500 / speed).toLong().coerceAtLeast(20)) } catch (_: InterruptedException) { break }
                t += 500_000_000L
                val r = route() ?: continue
                if (r !== cur) { cur = r; s = 0.0; dwell = 0.0 }
                val conn = r.connSteps.firstOrNull { s >= it.s - 0.4 && s < it.s + 0.3 }
                if (conn != null && dwell < (if (conn.kind == "lift") 15.0 else 8.0)) dwell += 0.5
                else {
                    if (conn != null) { s = conn.s + 0.31; dwell = 0.0 }
                    if (s < r.length) { s = minOf(r.length, s + 0.625); steps++ }
                }
                val (fi, x, y) = r.pointAt(s)
                val h = r.headingAt(s)
                val f = Fix(t, Core.b.floorOf(fi), x + rnd.nextGaussian() * 0.6, y + rnd.nextGaussian() * 0.6, 2.5, h + rnd.nextGaussian() * 0.05,
                    "sim", moving = s < r.length, steps = steps, stepLen = 0.6, floorProb = 0.97)
                st.steps = steps
                emit(f)
            }
        }, "sim").also { it.start() }
    }

    fun stop() { on = false; th?.interrupt() }

    @Suppress("unused") private fun off(x: Double, h: Double, d: Double) = x + d * sin(h) + 0 * cos(h)
}
