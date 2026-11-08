package com.gmcnav.navi.core

/**
 * When to ask Android for a Wi-Fi scan.
 *
 * Unthrottled (developer option «Ограничение запросов на сканирование Wi-Fi» off — the calibration phone): back to back,
 * a scan every ~2 s. Throttled (the default since Android 9): a foreground app may start 4 scans per 2 min (sliding window);
 * further startScan() calls fail at once (and do not count). Requested back to back that is a burst of 4 scans from one
 * spot and ~110 s of nothing. Here the 4 scans are spent where they help: at the start, right after a barometer floor event,
 * when the cloud has spread, and otherwise after a stretch of walking; standing with a compact cloud costs nothing.
 * While standing (and for a spreading cloud) one slot stays in reserve for a floor event. Scans started by the system or by
 * other apps (connectivity scans while the screen is on) arrive for free through the same broadcast and count as results here.
 */
class ScanScheduler(var throttled: Boolean, val maxInWindow: Int = 4, val windowNs: Long = 120_000_000_000L) {
    private val req = ArrayDeque<Long>()
    var lastReqNs = Long.MIN_VALUE / 2; private set
    var lastResultNs = Long.MIN_VALUE / 2; private set
    var pending = false; private set
    private var baroSeenNs = Long.MIN_VALUE / 2
    /** Why the last request was made (debug panel / tests). */
    var reason = ""; private set

    /** Requests still allowed in the sliding window. */
    fun slots(now: Long): Int = if (throttled) maxInWindow - inWindow(now) else Int.MAX_VALUE

    /** Accepted requests in the last window (also counted when unthrottled: a failure after 4 of them = throttling is on). */
    fun inWindow(now: Long): Int {
        while (req.isNotEmpty() && now - req.first() >= windowNs) req.removeFirst()
        return req.size
    }

    /** True = call startScan() now (then [requested]). */
    fun want(now: Long, eng: PositionEngine): Boolean {
        if (pending && now - lastReqNs < PENDING_NS) return false
        pending = false
        if (!throttled) { reason = "continuous"; return true }
        val free = slots(now)
        if (free <= 0) return false
        val since = (now - lastResultNs) / 1e9
        val sinceReq = (now - lastReqNs) / 1e9
        val fix = eng.fix
        // no position yet: a scan every few seconds
        if (fix == null || !eng.pf.initialized) return go("start", sinceReq >= START_GAP_S)
        if (firstFixNs == 0L) firstFixNs = now
        // the first seconds: the rest of the window settles the start (where, and which way the phone points)
        if (now - firstFixNs < SETTLE_S * 1e9 && since >= SETTLE_GAP_S) return go("settle", true)
        // a barometer floor event next to stairs / a lift (elsewhere it is hand heat)
        val baro = eng.baro.last
        if (baro != null && baro.tNs > baroSeenNs && baro.tNs > lastResultNs && since >= 3) {
            baroSeenNs = baro.tNs
            if (eng.b.connNear(fix.floor, fix.rawX, fix.rawY, CONN_R) != null) return go("floor", true)
        }
        if (fix.acc > LOST_ACC && since >= LOST_GAP_S && free > RESERVE) return go("spread", true)
        // otherwise evenly: one scan per PERIOD while walking (4 per 2 min is one per 30 s), rarely while standing
        val walking = eng.lastStepNs > 0 && now - eng.lastStepNs < 3_000_000_000L
        if (walking && since >= PERIOD_S && free > WALK_RESERVE) return go("walk", true)
        if (!walking && since >= STAND_PERIOD_S && free > RESERVE) return go("stand", true)
        return false
    }

    private fun go(why: String, ok: Boolean): Boolean { if (ok) reason = why; return ok }

    /** startScan() was called; ok = it was accepted (a throttled call fails and does not use a slot). */
    fun requested(now: Long, ok: Boolean) {
        lastReqNs = now
        pending = ok
        if (ok) { req.addLast(now); if (req.size > 64) req.removeFirst() }
    }

    /** Our scan finished without new results (the driver aborted it, or throttled): the request is over. */
    fun failed(now: Long) { pending = false }

    /** Fresh scan results arrived (own request or someone else's). */
    fun result(now: Long) {
        lastResultNs = now; pending = false
    }

    private var firstFixNs = 0L

    // tuned on the replays (ScanBudgetReplayTest)
    var START_GAP_S = 4.0
    var SETTLE_S = 20.0
    var SETTLE_GAP_S = 6.0
    var RESERVE = 1
    var WALK_RESERVE = 0
    var LOST_ACC = 25.0
    var LOST_GAP_S = 12.0
    var PERIOD_S = 30.0
    var STAND_PERIOD_S = 60.0
    var CONN_R = 20.0

    companion object {
        const val PENDING_NS = 6_000_000_000L
    }
}
