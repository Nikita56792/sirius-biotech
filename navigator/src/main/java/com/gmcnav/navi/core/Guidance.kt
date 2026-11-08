package com.gmcnav.navi.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/** What the UI and the voice need after each position update. */
class GuidanceState(
    val tNs: Long,
    val s: Double,                 // progress along the route (m)
    val fi: Int, val x: Double, val y: Double, val heading: Double,   // route point at the progress and route direction there
    val lateral: Double,           // distance of the fused position from the route (m)
    val next: Maneuver?,           // the next maneuver ahead
    val after: Maneuver?,          // the one after it
    val dist: Double,              // to the next maneuver (m)
    val remaining: Double,         // to the destination (m)
    val timeLeft: Double,          // s
    val speed: Double,             // walking speed estimate (m/s)
    val offRoute: Boolean,
    val needReroute: Boolean,
    val arrived: Boolean,
    val acc: Double,
    val confident: Boolean,        // good enough to speak distance-based prompts
    /** Where to draw the arrow: the route point nearest to the fused position when [snapped], else the fused position itself. */
    val dispFloor: Int = 0, val dispX: Double = x, val dispY: Double = y,
    val snapped: Boolean = false,
    /** Why the position counts as off the route: "" | far | wall | away | floor. */
    val offReason: String = "",
)

/**
 * Route following. Everything here works on the FUSED position (particle filter, not influenced by the route);
 * the route-snapped point is only a display convenience.
 *
 * Progress comes from the fused position projected onto the route and is bounded by the steps actually walked
 * (≤ 1.3 × steps × step length + 1 m per update), so a radio correction can never make it leap ahead and trigger a
 * prompt early. It may go back only when the user is consistently behind (turned around).
 * Floors: progress passes a connector only once the position is on the connector's target floor.
 *
 * Off route (any of):
 *  - far:   farther from the route than the free width of the space on that side + 2 m (≥ 4 m), unless the user is in a
 *           room / corridor the route passes here and moves along the route direction (wide halls: no false reroutes), or
 *  - room:  in a room / corridor the route does not pass near the current progress, or
 *  - wall:  > 3.5 m from it with a wall between and the walk back to the route a real detour (> 2 × distance + 4 m):
 *           another corridor / room, e.g. a parallel corridor 4–6 m away,
 *           either for ≥ 3 s or ≥ 4 steps spent beyond the threshold (hysteresis: the 4–6 m band pauses the count,
 *           only < 4 m with a clear line resets it);
 *  - away:  walking away from the route — 5 consecutive steps whose fused motion (over ~3 steps) points > ~41° off the route direction,
 *           outwards, the distance growing by ≥ 1.5 m and now ≥ 3 m (a side corridor at a junction);
 *  - floor: on a floor the route does not expect here (not the target of the connector just ahead) — immediately.
 * The distances scale with the filter's accuracy estimate: ×(acc / 5 m), clamped to 1…1.6 (unchanged while confident).
 * Then needReroute, at most once per 5 s (the caller re-plans from the fused position and calls [replaceRoute]).
 * The arrow is snapped to the route only when the fused position is ≤ 3.5 m from it, on the same floor, with a clear
 * line to it, the walking direction agrees with the route, and the user is not off route.
 */
class Guidance(val b: Building, route: Route) {
    var route: Route = route; private set
    var s = 0.0; private set
    private var lastSteps = -1
    private var lastT = 0L
    private var offSince = 0L; private var offAccNs = 0L; private var offAccSteps = 0; private var offWhy = ""
    private var awaySteps = 0; private var awayLat0 = 0.0
    private var awayFired = false
    private var stepX = Double.NaN; private var stepY = Double.NaN
    private var behindSince = 0L
    private var aheadSince = 0L; private var aheadFixes = 0
    /** Progress jumps accepted silently (a positioning jump forward along the route). */
    var jumpsAccepted = 0; private set
    private var lastAskNs = Long.MIN_VALUE / 2
    var reroutesAsked = 0; private set
    var arrived = false; private set
    // stairs climbed by counted steps (see update)
    private var climbKey = ""; private var climbSteps = 0; private var climbNeed = 0.0; private var climbLen = 0.0
    private var climbedTo: Int? = null; private var climbFrom = 0; private var climbedAt = 0L
    /** Floor changes taken from counted stair steps before the position got there. */
    var climbsByStep = 0; private set
    var useClimb = true
    /** Steps count within this distance of the stair (m); the climb is done at this share of height / riser steps. */
    var climbR = 5.0; var climbShare = 1.0
    private val hist = ArrayDeque<Pair<Long, Double>>()   // (t, s) for speed
    // a position on a floor the route is not on (outside the stairwell / lift of the route): since when, how many fixes
    private var floorWrongSince = 0L; private var floorWrongFixes = 0
    /** Fixes inside the stairwell / lift of the route between its floors (counted as on the route). */
    var transitFixes = 0; private set

    fun replaceRoute(r: Route, startS: Double = 0.0) {
        route = r; s = startS; offSince = 0; offAccNs = 0; offAccSteps = 0; offWhy = ""; awaySteps = 0; awayFired = false; stepHist.clear()
        aheadSince = 0; aheadFixes = 0
        behindSince = 0; hist.clear(); arrived = false; floorWrongSince = 0; floorWrongFixes = 0
    }

    /**
     * The route's floor change around the progress (ahead within 25 m, or passed within 25 m) whose stairwell / lift the
     * position is in: on any floor from its start to its end floor (also the floors in between on a multi-floor climb),
     * within TRANSIT_R of the connector there. The route line has no geometry between the floors — the position walking the
     * flights is next to the line's break, on a floor of the climb, and that is following the route, not leaving it.
     */
    private fun transitAt(f: Fix): Maneuver? = route.maneuvers.firstOrNull { m ->
        if (m.type != MType.CONNECTOR || abs(m.s - s) > 25.0) return@firstOrNull false
        val lo = min(m.fromFloor, m.toFloor); val hi = max(m.fromFloor, m.toFloor)
        // floors outside the numeric range but on the connector (e.g. an attic level numbered 9) count only if they are an end
        if (!(f.floor in lo..hi || f.floor == m.fromFloor || f.floor == m.toFloor)) return@firstOrNull false
        val pos = m.conn?.pos?.get(f.floor) ?: return@firstOrNull hypot(f.x - m.x, f.y - m.y) <= TRANSIT_R
        hypot(f.x - pos[0], f.y - pos[1]) <= TRANSIT_R
    }

    private val stepHist = ArrayDeque<DoubleArray>()

    /** Rooms (nav-grid ids) the route passes within [s − 15, s + 60] m (and 1 m around its line), cached per 5 m of progress. */
    private var roomsKey = Long.MIN_VALUE; private var roomsCache: Set<Int> = emptySet()
    private fun routeRoomsNear(fi: Int, sAt: Double): Set<Int> {
        val key = (sAt / 5).toLong() * 16 + fi + (System.identityHashCode(route).toLong() shl 24)
        if (key == roomsKey) return roomsCache
        val out = HashSet<Int>()
        var q = max(0.0, sAt - 15)
        while (q <= min(route.length, sAt + 60)) {
            val (lf, x, y) = route.pointAt(q)
            if (lf == fi) for (dx in listOf(-1.0, 0.0, 1.0)) for (dy in listOf(-1.0, 0.0, 1.0)) b.roomIdAt(fi, x + dx, y + dy).let { if (it != 0) out.add(it) }
            q += 1.0
        }
        roomsKey = key; roomsCache = out
        return out
    }

    /** Free distance from a route point along a unit direction to the first non-passable cell (≤ 40 m). */
    private fun freeWidth(fi: Int, x: Double, y: Double, ux: Double, uy: Double): Double {
        var d = 0.0
        while (d < 40.0) { if (!b.passableXY(fi, x + ux * d, y + uy * d)) return d; d += 0.3 }
        return 40.0
    }

    /** True when the walk from (x, y) to within 1 m of (px, py) is longer than maxM (grid BFS, 4-connected, bounded). */
    private fun separated(fi: Int, x: Double, y: Double, px: Double, py: Double, maxM: Double): Boolean {
        val start = b.snapXY(fi, x, y, 1.0); if (start < 0) return false
        val maxSteps = (maxM / b.res).toInt()
        val tr = b.row(py); val tc = b.col(px); val near = (1.0 / b.res).toInt()
        val dist = HashMap<Int, Int>(); val q = ArrayDeque<Int>(); q.add(start); dist[start] = 0
        while (q.isNotEmpty()) {
            val c = q.removeFirst(); val d = dist.getValue(c); val r = c / b.cols; val cc = c % b.cols
            if (abs(r - tr) <= near && abs(cc - tc) <= near) return false
            if (d >= maxSteps) continue
            for (k in 0 until 4) {
                val nr = r + DR4[k]; val nc = cc + DC4[k]
                if (!b.inGrid(nr, nc) || !b.passable(fi, nr, nc)) continue
                val n = nr * b.cols + nc
                if (n !in dist) { dist[n] = d + 1; q.add(n) }
            }
        }
        return true
    }

    fun nextIndex(): Int = route.maneuvers.indexOfFirst { it.s > s + 1.0 && it.type != MType.START }.let { if (it < 0) route.maneuvers.size - 1 else it }

    /** Straight line between two points on floor fi without crossing a wall (0.15 m samples on the 0.3 m grid). */
    private fun clear(fi: Int, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        val d = hypot(x1 - x0, y1 - y0)
        val n = max(1, (d / 0.15).toInt())
        for (i in 1 until n) {
            val t = i.toDouble() / n
            if (!b.passableXY(fi, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)) return false
        }
        return true
    }

    fun update(f: Fix): GuidanceState {
        val r = route
        val fi0 = b.fi(f.floor)
        val dt = if (lastT == 0L) 0.0 else ((f.tNs - lastT) / 1e9).coerceIn(0.0, 10.0)
        lastT = f.tNs
        val dSteps = if (lastSteps < 0) 0 else max(0, f.steps - lastSteps)
        lastSteps = f.steps
        val stepLen = f.stepLen.takeIf { it > 0.3 } ?: PositionEngine.STEP_LEN
        // connector ahead (or just reached): the position is already on its target floor -> continue on the next leg
        val conn = r.maneuvers.firstOrNull { it.type == MType.CONNECTOR && it.s >= s - 0.5 }
        // stairs of the route: steps taken in that stairwell are the climb (a floor ≈ height / 0.17 m risers). After all of them
        // the user counts as on the target floor for up to 45 s, until the position (Wi-Fi) gets there too — or, if it never
        // does, the floor is wrong after all (reroute). The positioning itself is not told: it must stay free of the route.
        var fi = fi0
        if (useClimb && conn != null && conn.connKind == "stair" && fi0 == b.fi(conn.fromFloor) && s > conn.s - 15 && hypot(f.x - conn.x, f.y - conn.y) <= climbR) {
            if (climbKey != conn.key) { climbKey = conn.key; climbSteps = 0 }
            climbSteps += dSteps
            climbNeed = Vertical.steps(b.elevOf(conn.toFloor) - b.elevOf(conn.fromFloor))
            climbLen = Vertical.length("stair", b.elevOf(conn.fromFloor), b.elevOf(conn.toFloor))
            if (climbSteps >= climbShare * climbNeed && climbedTo == null) { climbedTo = conn.toFloor; climbFrom = conn.fromFloor; climbedAt = f.tNs; climbsByStep++ }
        }
        val climbing = climbedTo != null && fi0 == b.fi(climbFrom) && f.tNs - climbedAt < 45_000_000_000L
        if (climbing) fi = b.fi(climbedTo!!)
        else if (climbedTo != null) { climbedTo = null; climbKey = "" }
        if (conn != null && fi == b.fi(conn.toFloor) && s > conn.s - 25) s = conn.s + 0.01
        // projection of the fused position within a window around the current progress, on this floor
        val legNow = r.legs[r.legIndexAt(s)]
        val onLegFloor = legNow.fi == fi
        val p = if (onLegFloor) r.project(fi, f.x, f.y, max(0.0, s - 15), min(legNow.s1, s + 40)) else null
        val lateral = p?.dist ?: Double.MAX_VALUE
        val los = p != null && (lateral < 0.5 || clear(fi, f.x, f.y, p.x, p.y))
        if (p != null) {
            val maxAdv = 1.3 * dSteps * stepLen + 1.0 + 0.3 * dt     // PDR-bounded advance
            when {
                p.s >= s -> { s = min(p.s, s + maxAdv); behindSince = 0 }
                p.s < s - 8 -> {      // consistently behind -> the user went back
                    if (behindSince == 0L) behindSince = f.tNs
                    if (f.tNs - behindSince > 6_000_000_000L) { s = p.s; behindSince = 0 }
                }
                else -> behindSince = 0
            }
        }
        // ---- a jump forward ALONG the route is progress, not a deviation: the position is on the route further ahead
        // (beyond the step-bounded advance or the projection window, possibly on a later floor). Accepted silently once it
        // holds for ≥ 2 fixes and ≥ 2 s; meanwhile it does not count as off route.
        val ahead = r.legs.withIndex().filter { (_, L) -> L.fi == fi && L.s1 > s + 1 }.mapNotNull { (_, L) ->
            r.project(fi, f.x, f.y, max(L.s0, s + 1), L.s1) }.minByOrNull { it.dist }
        val maxAdvNow = 1.3 * dSteps * stepLen + 1.0 + 0.3 * dt
        // (≥ 10 m ahead inside the window — smaller radio corrections keep the step-bounded advance, so no early prompts)
        val jumpCand = ahead != null && ahead.dist <= JUMP_NEAR && ahead.s > s + maxAdvNow + 3 &&
            (ahead.s - s >= 10 || lateral > ahead.dist + 1.5 || !onLegFloor)
        if (jumpCand) {
            if (aheadSince == 0L) aheadSince = f.tNs
            aheadFixes++
            if (aheadFixes >= 2 && f.tNs - aheadSince >= 2_000_000_000L) {
                s = ahead!!.s; jumpsAccepted++
                aheadSince = 0; aheadFixes = 0; offSince = 0; offAccNs = 0; offAccSteps = 0; awaySteps = 0; awayFired = false
                return update(f)                          // continue from the new progress (same fix)
            }
        } else { aheadSince = 0; aheadFixes = 0 }
        // ---- off route
        val transit = transitAt(f)
        if (transit != null) transitFixes++
        // a floor the route is not on: only when it holds (≥ 2 fixes, ≥ FLOOR_HOLD_NS) — Wi-Fi in a stairwell flips floors for
        // a few seconds — and never inside the stairwell / lift of the route's floor change
        val floorOff = !onLegFloor && !jumpCand && transit == null
        if (floorOff) { if (floorWrongSince == 0L) floorWrongSince = f.tNs; floorWrongFixes++ } else { floorWrongSince = 0; floorWrongFixes = 0 }
        val floorWrong = floorOff && floorWrongFixes >= 2 && f.tNs - floorWrongSince >= FLOOR_HOLD_NS
        // distances scale with the filter's own uncertainty: unchanged while it is confident (acc ≤ 5 m), up to ×1.6
        val k = (f.acc / 5.0).coerceIn(1.0, 1.6)
        // the space the user is in: a room / corridor (nav-grid room) the route passes near the current progress?
        val myRoom = b.roomIdAt(fi, f.x, f.y)
        val sameSpace = p != null && myRoom != 0 && myRoom in routeRoomsNear(fi, s)
        // motion over the last steps vs the route direction at the projection
        val o0 = stepHist.firstOrNull()
        val mvx = if (o0 != null) f.x - o0[0] else 0.0; val mvy = if (o0 != null) f.y - o0[1] else 0.0; val mvl = hypot(mvx, mvy)
        val rdir = if (p != null) r.headingAt(p.s) else 0.0
        val along = if (mvl > 0.6) (mvx * sin(rdir) + mvy * cos(rdir)) / mvl else 1.0       // 1 = along the route, 0 = across
        // far: beyond the free width of the space on that side (to the wall, ≤ 40 m) plus a margin — not a fixed 6 m
        val freeW = if (p != null && lateral > 2.0) freeWidth(fi, p.x, p.y, (f.x - p.x) / lateral, (f.y - p.y) / lateral) else 99.0
        // (on another floor there is no distance to the line: that case is the floor rule's, with its hold)
        val far = p != null && lateral > (max(OFF_FAR_MIN, freeW + FAR_MARGIN)) * k && !(sameSpace && along > ALONG_COS)
        // another room / corridor that the route does not pass here
        val otherRoom = p != null && myRoom != 0 && !sameSpace && lateral > OFF_WALL * k
        // another corridor / room behind a wall: the walk back to the route is a real detour (> 2 × distance + 4 m)
        val wall = lateral > OFF_WALL * k && !los && p != null && separated(fi, f.x, f.y, p.x, p.y, 2 * lateral + 4)
        // time and steps spent beyond the threshold; the band in between pauses the count, close with a clear line resets it
        when {
            transit != null -> { offSince = 0; offWhy = "" }
            far || wall || otherRoom -> if (offSince == 0L) { offSince = f.tNs; offAccNs = 0; offAccSteps = 0; offWhy = if (far) "far" else if (wall) "wall" else "room" }
                           else { offAccNs += (dt * 1e9).toLong(); offAccSteps += dSteps }
            lateral < ON_BACK && los -> { offSince = 0; offWhy = "" }
        }
        val held = !jumpCand && offSince != 0L && (offAccNs >= OFF_HOLD_NS || offAccSteps >= OFF_HOLD_STEPS)
        // walking away from the route (side corridor): per step, the fused motion (not the compass) vs the outward normal
        if (dSteps > 0 && p != null) {
            // motion over the last 3 step updates (≈ 2 m): steadier than one step
            stepHist.addLast(doubleArrayOf(f.x, f.y)); while (stepHist.size > 4) stepHist.removeFirst()
            val o = stepHist.first()
            val mx = f.x - o[0]; val my = f.y - o[1]; val ml = hypot(mx, my)
            // away = moving outwards AND not along the route (> 60° off its direction); sideways drift while walking on is not
            val alongNow = if (ml > 0.6) (mx * sin(r.headingAt(p.s)) + my * cos(r.headingAt(p.s))) / ml else 1.0
            val away = lateral > 1.0 && stepHist.size >= 3 && ml > 0.6 && (mx * (f.x - p.x) + my * (f.y - p.y)) / (ml * lateral) > AWAY_COS &&
                alongNow < AWAY_ALONG
            if (away) { if (awaySteps == 0) awayLat0 = lateral; awaySteps += dSteps } else awaySteps = 0
            if (awaySteps >= AWAY_STEPS && lateral - awayLat0 >= AWAY_GROW * k && lateral >= AWAY_MIN * k) awayFired = true
            stepX = f.x; stepY = f.y
        }
        if (awayFired && lateral < ON_BACK && los && awaySteps == 0) awayFired = false
        if (transit != null) { awayFired = false; awaySteps = 0 }
        val offRoute = transit == null && (floorWrong || held || (awayFired && !jumpCand))
        val reason = when { floorWrong -> "floor"; held -> offWhy; awayFired -> "away"; else -> "" }
        val need = offRoute && f.tNs - lastAskNs >= REROUTE_MIN_NS
        if (need) { lastAskNs = f.tNs; reroutesAsked++ }
        // speed from progress over ~5 s
        hist.addLast(f.tNs to s)
        while (hist.size > 2 && f.tNs - hist.first().first > 5_000_000_000L) hist.removeFirst()
        val speed = if (hist.size >= 2 && hist.last().first > hist.first().first)
            ((hist.last().second - hist.first().second) / ((hist.last().first - hist.first().first) / 1e9)).coerceIn(0.0, 2.5) else 0.0
        val ni = nextIndex()
        val next = r.maneuvers.getOrNull(ni)
        val after = r.maneuvers.getOrNull(ni + 1)
        // incl. the stair flights still ahead, minus the part of the current flight already climbed (counted steps)
        val climbedPart = if (conn != null && climbKey == conn.key && climbNeed > 0) min(1.0, climbSteps / climbNeed) * climbLen else 0.0
        val remaining = max(0.0, r.distLeft(s) - climbedPart)
        if (max(0.0, r.length - s) < 4.0 && lateral < 12) arrived = true
        val (sfi, sx, sy) = r.pointAt(s)
        // display: on the route only when clearly on it and walking along it
        val agrees = f.heading.isNaN() || !f.moving || listOf(s - 2, s, s + 4).any { abs(Orientation.wrap(f.heading - r.headingAt(it.coerceIn(0.0, r.length)))) < SNAP_ANGLE }
        val snapped = p != null && !offRoute && offSince == 0L && awaySteps < 2 && lateral <= SNAP_DIST && los && agrees
        return GuidanceState(f.tNs, s, sfi, sx, sy, r.headingAt(s), lateral, next, after, if (next != null) max(0.0, next.s - s) else remaining,
            remaining, r.timeLeft(s), speed, offRoute, need, arrived, f.acc, f.acc <= 25 && lateral <= 15,
            if (snapped || climbing) b.floorOf(fi) else f.floor, if (snapped) p!!.x else f.x, if (snapped) p!!.y else f.y, snapped, reason)
    }

    companion object {
        const val OFF_FAR = 6.0            // m from the route (kept for reports; the rule uses the free width below)
        const val OFF_FAR_MIN = 4.0        // m: never closer than this
        const val FAR_MARGIN = 2.0         // m beyond the free width of the space on that side
        const val ALONG_COS = 0.34         // motion within ~70° of the route direction = "moving along the route"
        const val AWAY_ALONG = 0.5         // "away" needs the motion > 60° off the route direction (a 45° drift in a hall is not)
        const val OFF_WALL = 3.5           // m, with a wall in between
        const val ON_BACK = 4.0            // hysteresis: back on the route below this (and a clear line)
        const val OFF_HOLD_NS = 3_000_000_000L
        const val OFF_HOLD_STEPS = 4
        const val AWAY_STEPS = 5
        const val AWAY_COS = 0.65          // motion component along the outward normal (> ~41° off the route direction)
        const val AWAY_GROW = 1.5          // m the distance grew over those steps
        const val AWAY_MIN = 3.0           // m from the route at least
        const val REROUTE_MIN_NS = 5_000_000_000L
        const val TRANSIT_R = 8.0          // m around the route's stair / lift on a floor of its climb: in the stairwell
        const val FLOOR_HOLD_NS = 5_000_000_000L   // a wrong floor (outside the stairwell) must hold this long
        const val JUMP_NEAR = 4.0          // m: "on the route further ahead"
        private val DR4 = intArrayOf(-1, 1, 0, 0); private val DC4 = intArrayOf(0, 0, -1, 1)
        const val SNAP_DIST = 3.5
        val SNAP_ANGLE = Math.toRadians(60.0)
    }
}

// ====================================================================== voice

/**
 * A piece of an utterance: a recording sounds/ru_alice/<key>/<file>. There is no speech synthesis — every prompt is made
 * of recordings only (the files are listed in assets/sounds/durations.json; VoicePackTest checks every key exists).
 */
sealed class VPart { data class Rec(val key: String, val file: String = "0.mp3") : VPart() }

class Prompt(val parts: List<VPart.Rec>, val text: String, val tag: String, val urgent: Boolean) {
    override fun toString() = text
}

enum class VoiceMode { OFF, IMPORTANT, ALL }

/** Russian phrases from the ru_alice recordings (texts verified by transcribing the files, new units from the script). */
object Phrases {
    /** key -> what the recording says */
    val TEXT = mapOf(
        "Over" to "через", "Meter" to "метр", "Meters" to "метров", "Meters2_4" to "метра", "Then" to "а затем",
        "TurnLeft" to "поверните налево", "TurnRight" to "поверните направо", "TakeLeft" to "держитесь левее", "TakeRight" to "держитесь правее",
        "HardTurnLeft" to "крутой поворот налево", "HardTurnRight" to "крутой поворот направо", "TurnBack" to "развернитесь",
        "LeftTurn" to "поворот налево", "RightTurn" to "поворот направо", "Forward" to "прямо", "ContinueStraight" to "держитесь прямо",
        "WalkStraight" to "идите прямо", "Stairs" to "пройдите по лестнице", "StairsUp" to "поднимитесь по лестнице",
        "StairsDown" to "спуститесь по лестнице", "RouteCalculated" to "маршрут построен", "RouteRecalculated" to "маршрут перестроен",
        "RouteLost" to "вы ушли с маршрута", "RouteReturn" to "вы вернулись на маршрут", "RouteFinished" to "вы приехали",
        "PedestrianRouteFinished" to "вы пришли", "RouteWillFinish" to "до конца маршрута осталось", "OneAndHalf" to "полтора",
        "Kilometer" to "километр", "Kilometers" to "километров", "Kilometers2_4" to "километра",
        "FasterRouteAvailable" to "найден более быстрый маршрут",
        // recorded 28.10 for the navigator
        "FloorTo1" to "на первый этаж", "FloorTo2" to "на второй этаж", "FloorTo3" to "на третий этаж",
        "FloorTo4" to "на четвёртый этаж", "FloorTo5" to "на пятый этаж", "FloorToBasement" to "в подвал", "FloorToAttic" to "на чердак",
        "FloorToRoof" to "на крышу",
        "ConnLiftUp" to "поднимитесь на лифте", "ConnLiftDown" to "спуститесь на лифте",
        "ConnEscalatorUp" to "поднимитесь на эскалаторе", "ConnEscalatorDown" to "спуститесь на эскалаторе",
        "AccessAhead" to "дальше проход по СКУД,", "AccessNeedExtended" to "необходим расширенный доступ.", "AccessNeedBasic" to "необходим базовый доступ.",
        "AccessFreeIntro" to "без пропуска можно пройти", "AccessDaysMonThu" to "с понедельника по четверг",
        "AccessTime11to13" to "с одиннадцати до тринадцати", "AccessTimeAnd14to16" to "и с четырнадцати до шестнадцати",
        "RouteAroundClosure" to "маршрут перестроен в обход перекрытия",

    ) + (1..19).associate { "$it" to num(it) } + listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 200, 300, 400, 500, 600, 700, 800, 900).associate { "$it" to num(it) }

    /** Texts of the other variants (key/file), for logs and the screen; set from voice_variants.json. */
    val VARIANT_TEXT = HashMap<String, String>()

    private fun num(n: Int): String = when (n) {
        1 -> "один"; 2 -> "два"; 3 -> "три"; 4 -> "четыре"; 5 -> "пять"; 6 -> "шесть"; 7 -> "семь"; 8 -> "восемь"; 9 -> "девять"
        10 -> "десять"; 11 -> "одиннадцать"; 12 -> "двенадцать"; 13 -> "тринадцать"; 14 -> "четырнадцать"; 15 -> "пятнадцать"
        16 -> "шестнадцать"; 17 -> "семнадцать"; 18 -> "восемнадцать"; 19 -> "девятнадцать"; 20 -> "двадцать"; 30 -> "тридцать"
        40 -> "сорок"; 50 -> "пятьдесят"; 60 -> "шестьдесят"; 70 -> "семьдесят"; 80 -> "восемьдесят"; 90 -> "девяносто"
        100 -> "сто"; 200 -> "двести"; 300 -> "триста"; 400 -> "четыреста"; 500 -> "пятьсот"; 600 -> "шестьсот"; 700 -> "семьсот"
        800 -> "восемьсот"; 900 -> "девятьсот"; else -> n.toString()
    }

    /** Distance as spoken while walking: ≤ 20 m to 5 m, ≤ 100 m to 10 m, else to 50 m. */
    fun roundDist(d: Double): Int = when {
        d <= 20 -> max(5, (round(d / 5) * 5).toInt())
        d <= 100 -> (round(d / 10) * 10).toInt()
        else -> (round(d / 50) * 50).toInt()
    }

    /** 125 -> ["100", "20", "5"]: concatenation of the recorded numbers. */
    fun numberKeys(n: Int): List<String> {
        val out = ArrayList<String>(); var r = n
        if (r >= 100) { out.add("${r / 100 * 100}"); r %= 100 }
        if (r >= 20) { out.add("${r / 10 * 10}"); r %= 10 }
        if (r > 0) out.add("$r")
        return out
    }

    fun meterKey(n: Int): String { val h = n % 100; val m = n % 10
        return if (h in 11..14) "Meters" else if (m == 1) "Meter" else if (m in 2..4) "Meters2_4" else "Meters" }

    /** "через 30 метров" */
    fun overDist(d: Int): List<VPart.Rec> = listOf(VPart.Rec("Over")) + numberKeys(d).map { VPart.Rec(it) } + VPart.Rec(meterKey(d))

    /** Floor as an ordinal word for «на … этаж»: 2 -> «второй» (texts only). */
    fun floorWord(n: Int): String = when (n) {
        1 -> "первый"; 2 -> "второй"; 3 -> "третий"; 4 -> "четвёртый"; 5 -> "пятый"; else -> "$n-й"
    }

    /** Is a recording in the pack? The app / tests set this (file exists); by default the floors recorded so far. */
    @Volatile var hasRec: (String) -> Boolean = { true }   // the pack (30.10): FloorTo1–5, Basement, Attic, Roof

    /** Recording key for «на N этаж» / «в подвал» (level 0) / «на чердак» (level 9). */
    fun floorKey(n: Int): String = when (n) { 0 -> "FloorToBasement"; 9 -> "FloorToAttic"; else -> "FloorTo$n" }

    /** The floor unit if its recording is in the pack; otherwise the connector prompt is said without the floor. */
    fun floorUnit(n: Int): VPart.Rec? = floorKey(n).takeIf { hasRec(it) }?.let { VPart.Rec(it) }

    /**
     * Action of a maneuver as recordings: turns as before; connectors = «поднимитесь / спуститесь на лифте / на эскалаторе /
     * по лестнице» + «на N-й этаж» (stairs use the pack's StairsUp/StairsDown).
     */
    fun action(m: Maneuver): List<VPart.Rec> = when (m.type) {
        MType.TAKE_LEFT -> listOf(VPart.Rec("TakeLeft")); MType.TAKE_RIGHT -> listOf(VPart.Rec("TakeRight"))
        MType.TURN_LEFT -> listOf(VPart.Rec("TurnLeft")); MType.TURN_RIGHT -> listOf(VPart.Rec("TurnRight"))
        MType.HARD_LEFT -> listOf(VPart.Rec("HardTurnLeft")); MType.HARD_RIGHT -> listOf(VPart.Rec("HardTurnRight"))
        MType.UTURN -> listOf(VPart.Rec("TurnBack"))
        MType.START -> listOf(VPart.Rec("WalkStraight"))
        MType.FINISH -> listOf(VPart.Rec("PedestrianRouteFinished"))
        MType.CONNECTOR -> listOf(VPart.Rec(when (m.connKind) {
            "lift" -> if (m.up) "ConnLiftUp" else "ConnLiftDown"
            "escalator" -> if (m.up) "ConnEscalatorUp" else "ConnEscalatorDown"
            else -> if (m.up) "StairsUp" else "StairsDown"
        })) + listOfNotNull(floorUnit(m.toFloor))
    }

    /** Recorded schedule phrases: windows -> units (only the schedules that were recorded; others are on screen only). */
    private val SCHEDULES = listOf(
        listOf(Triple(setOf(1, 2, 3, 4), 11 * 60, 13 * 60), Triple(setOf(1, 2, 3, 4), 14 * 60, 16 * 60)) to
            listOf("AccessFreeIntro", "AccessDaysMonThu", "AccessTime11to13", "AccessTimeAnd14to16"),
    )

    /** «Дальше проход по СКУД, необходим расширенный доступ. [Без пропуска можно пройти с понедельника по четверг …]» */
    fun accessUnits(level: Int, schedule: List<AccessWindow>): List<VPart.Rec> {
        val out = arrayListOf(VPart.Rec("AccessAhead"), VPart.Rec(if (level >= 2) "AccessNeedExtended" else "AccessNeedBasic"))
        if (schedule.isNotEmpty()) {
            val w = schedule.map { Triple(it.days, it.startMin, it.endMin) }
            SCHEDULES.firstOrNull { it.first == w }?.second?.forEach { out.add(VPart.Rec(it)) }
        }
        return out
    }

    fun partText(p: VPart.Rec): String = VARIANT_TEXT["${p.key}/${p.file}"] ?: TEXT[p.key] ?: p.key

    fun text(parts: List<VPart.Rec>): String = parts.joinToString(" ") { partText(it) }.replaceFirstChar { it.uppercase() }

    fun prompt(parts: List<VPart.Rec>, tag: String, urgent: Boolean): Prompt = Prompt(parts, text(parts), tag, urgent)
}

/**
 * Weighted variants of an event (assets/voice_variants.json): RouteCalculated, RouteRecalculated, FasterRouteAvailable,
 * RouteWillFinish, Arrival. The plain (default) variant may repeat; a characterful one is never played twice in a row,
 * and the long-run frequencies still match the weights; options may require a minimum route length; a "rare" option is
 * tried first with its chance, at most once per day (persisted by the app via [rareDays]).
 */
class VoiceVariants(text: String, seed: Long) {
    class Rare(val chance: Double, val minRoute: Double, val orFloorChange: Boolean)
    class Option(val key: String, val file: String, val w: Double, val minRoute: Double, val rare: Rare?, val text: String?, val default: Boolean = false) {
        val id get() = "$key/$file"
    }
    val events: Map<String, List<Option>>
    private val rnd = java.util.Random(seed)
    private val last = HashMap<String, String>()
    /** Rare option id -> local day it was last played ("2026-10-28"); load / persist from outside. */
    val rareDays = HashMap<String, String>()
    var onRareUsed: ((String, String) -> Unit)? = null
    /** Local day for the once-per-day rule. */
    var today: () -> String = {
        val c = java.util.Calendar.getInstance(Access.TZ)
        "%04d-%02d-%02d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
    }

    init {
        val o = Json.parse(text).obj()
        events = (o["events"] as Map<*, *>).entries.associate { (k, v) ->
            k as String to (v as List<*>).map { e ->
                val m = e as Map<*, *>
                val r = m["rare"] as? Map<*, *>
                Option(m["key"] as String, m["file"] as? String ?: "0.mp3", (m["w"] as? Number)?.toDouble() ?: 0.0,
                    (m["minRoute"] as? Number)?.toDouble() ?: 0.0,
                    r?.let { Rare((it["chance"] as Number).toDouble(), (it["minRoute"] as? Number)?.toDouble() ?: 0.0, it["orFloorChange"] as? Boolean ?: false) },
                    m["text"] as? String, m["default"] as? Boolean ?: false)
            }
        }
        for (l in events.values) for (op in l) op.text?.let { Phrases.VARIANT_TEXT[op.id] = it }
    }

    /** The recording to play for an event, or null when the event has no options. */
    fun pick(event: String, routeLen: Double = 0.0, floorChange: Boolean = false): VPart.Rec? {
        val opts = events[event] ?: return null
        val day = today()
        val rare = opts.firstOrNull { op -> op.rare != null && rareDays[op.id] != day && last[event] != op.id &&
            (routeLen >= op.rare.minRoute || (op.rare.orFloorChange && floorChange)) && rnd.nextDouble() < op.rare.chance }
        if (rare != null) {
            rareDays[rare.id] = day; onRareUsed?.invoke(rare.id, day); last[event] = rare.id
            return VPart.Rec(rare.key, rare.file)
        }
        val elig = opts.filter { it.rare == null && it.w > 0 && routeLen >= it.minRoute }
        if (elig.isEmpty()) return null
        val def = elig.firstOrNull { it.default }
        val pickOp: Option
        if (def != null) {
            // the plain (default) variant may repeat; a characterful one never twice in a row. Choosing each characterful
            // option j with q_j = w_j / (1 − w_j) (0 right after itself) and the default otherwise gives long-run frequencies
            // exactly w_j (weights normalised over the eligible options)
            val total = elig.sumOf { it.w }
            val others = elig.filter { !it.default }
            val q = others.associateWith { val w = it.w / total; w / (1 - w) }
            val scale = q.values.sum().let { if (it > 1.0) 1.0 / it else 1.0 }     // weights too large for the rule: keep proportions
            var u = rnd.nextDouble(); var chosen: Option = def
            for (op in others) { if (op.id == last[event]) continue; u -= q.getValue(op) * scale; if (u < 0) { chosen = op; break } }
            pickOp = chosen
        } else {
            var cand = elig
            if (cand.size > 1) cand = cand.filter { it.id != last[event] }
            var u = rnd.nextDouble() * cand.sumOf { it.w }
            var c = cand.last()
            for (op in cand) { u -= op.w; if (u <= 0) { c = op; break } }
            pickOp = c
        }
        last[event] = pickOp.id
        return VPart.Rec(pickOp.key, pickOp.file)
    }
}

/**
 * Decides what to say and when (like Yandex Navigator, tuned for walking):
 *  - pre-announcement ~30 m before a maneuver: "Через 30 метров поверните направо" (only if it is ≥ 12 m away);
 *  - the maneuver itself at ~7 m (6 m connectors): "Поверните направо", chained "…, а затем поверните налево" when the next
 *    maneuver follows within 15 m (then that one gets no separate pre-announcement);
 *  - "До конца маршрута осталось 30 метров", then the arrival phrase (variants) or, before a СКУД door, the СКУД phrase;
 *  - start "Маршрут построен" (+ "Идите прямо" if the first maneuver is > 40 m away), "Маршрут перестроен" after a reroute,
 *    «Найден более быстрый маршрут» (a lifted closure), «Маршрут перестроен в обход перекрытия» (a closure);
 *  - «Местоположение не определено» / «Местоположение определено» when the position is lost / found again
 *    (not at start, ≥ 20 s apart).
 * Everything is recorded (no TTS). Distances use the PDR-bounded route progress, compensated for ~1 s of latency at the
 * walking speed. Each stage of each maneuver is spoken at most once (keys survive reroutes); nothing is said while off
 * route or when the position is too uncertain; a missed stage is not spoken late.
 */
class VoicePlanner(var mode: VoiceMode = VoiceMode.ALL, var variants: VoiceVariants? = null) {
    private val fired = HashSet<String>()
    private var lastSpokenS = 0.0
    private var lastRerouteT = Long.MIN_VALUE / 2
    val log = ArrayList<Pair<Long, Prompt>>()
    // position lost / found

    companion object {
        const val FAR = 30.0
        const val FAR_MIN = 12.0
        const val NEAR_TURN = 7.0
        const val NEAR_CONN = 6.0
        const val NEAR_FINISH = 5.0
        const val CHAIN = 20.0             // m: the next maneuver is chained with «а затем»
        const val POST_AFTER = 2.0         // m past a maneuver: say what comes next
        const val POST_MAX = 200.0         // m: «Через N метров …» up to this, else «Идите прямо»
        const val PRE_MAX = 40.0           // m: a «what next» this close already is the pre-announcement
        const val REMIND_MIN = 90.0        // m: long-straight reminder window
        const val REMIND_MAX = 105.0
        const val REMIND_GAP = 40.0        // m walked without any prompt before a reminder
        const val LATENCY = 1.0
    }

    private fun emit(t: Long, p: Prompt, out: MutableList<Prompt>) {
        if (mode == VoiceMode.OFF) return
        if (mode == VoiceMode.IMPORTANT && !p.urgent) return
        out.add(p); log.add(t to p)
    }

    private fun variant(event: String, fallback: String, routeLen: Double = 0.0, floorChange: Boolean = false): VPart.Rec =
        variants?.pick(event, routeLen, floorChange) ?: VPart.Rec(fallback)

    /**
     * «Маршрут построен», then what comes first (as after any maneuver, see [whatNext]): «а затем»-close maneuvers are left to
     * their own prompt, «Через N метров …» up to 200 m, «До конца маршрута осталось N метров» before the finish, else «Идите прямо».
     */
    fun onStart(r: Route, t: Long): List<Prompt> {
        val out = ArrayList<Prompt>()
        val parts = arrayListOf(variant("RouteCalculated", "RouteCalculated"))
        val first = r.maneuvers.indexOfFirst { it.type != MType.START }
        if (first >= 0) parts.addAll(whatNext(r, first, r.maneuvers[first].s))
        emit(t, Phrases.prompt(parts, "start", true), out)
        lastSpokenS = 0.0
        return out
    }

    /** The «what comes next» phrase for maneuver [i] at distance [d] (marks its stages that it covers as said). */
    private fun whatNext(r: Route, i: Int, d: Double): List<VPart.Rec> {
        val m = r.maneuvers[i]
        fired.add("${m.key}:post")
        if (d <= CHAIN) return emptyList()                       // close: its own prompt comes right away
        if (d <= PRE_MAX) fired.add("${m.key}:far")              // this already is the pre-announcement
        if (d <= REMIND_MAX + 15) fired.add("${m.key}:rem")
        val n = Phrases.roundDist(d)
        return when {
            m.type == MType.FINISH && n < 1000 -> listOf(variant("RouteWillFinish", "RouteWillFinish")) + Phrases.numberKeys(n).map { VPart.Rec(it) } + VPart.Rec(Phrases.meterKey(n))
            m.type != MType.FINISH && d <= POST_MAX -> Phrases.overDist(n) + Phrases.action(m)
            else -> listOf(VPart.Rec("WalkStraight"))
        }
    }

    /**
     * After a reroute: «Маршрут перестроен» (variants); faster = a lifted closure gave a faster route («Найден более быстрый
     * маршрут», variants); closure = rebuilt around a closure («Маршрут перестроен в обход перекрытия»).
     */
    fun onReroute(t: Long, faster: Boolean = false, closure: Boolean = false): List<Prompt> {
        val out = ArrayList<Prompt>()
        if (t - lastRerouteT >= Guidance.REROUTE_MIN_NS - 500_000_000L) {
            val part = when {
                closure -> VPart.Rec("RouteAroundClosure")
                faster -> variant("FasterRouteAvailable", "FasterRouteAvailable")
                else -> variant("RouteRecalculated", "RouteRecalculated")
            }
            emit(t, Phrases.prompt(listOf(part), "reroute", true), out)
        }
        lastRerouteT = t
        return out
    }

    /**
     * Announcement schedule (pedestrian-scaled, like Yandex Navigator):
     *  - at the maneuver (~7 m, connectors 6 m, finish 5 m): «Поверните направо», chained «… а затем …» when the next one is
     *    within 20 m;
     *  - right after a maneuver (passed by 2 m): what comes next — «Через N метров <next>» up to 200 m, «До конца маршрута
     *    осталось N метров» before the finish, «Идите прямо» if farther (nothing if the next one was chained);
     *  - a pre-announcement at ~30 m: «Через 30 метров поверните направо» (unless just said);
     *  - on long straights a reminder at ~100 m: «Через 100 метров …» / «До конца маршрута осталось 100 метров» if nothing was
     *    said for the last 40 m.
     * One prompt per update; nothing while off route or too uncertain; a missed stage is not said late.
     */
    fun update(r: Route, g: GuidanceState): List<Prompt> {
        val out = ArrayList<Prompt>()
        if (g.offRoute || !g.confident) return out
        val ms = r.maneuvers
        val dEffShift = g.speed * LATENCY
        fun said(parts: List<VPart.Rec>, tag: String, urgent: Boolean): List<Prompt> { emit(g.tNs, Phrases.prompt(parts, tag, urgent), out); lastSpokenS = g.s; return out }
        // 1) at the maneuver
        for ((i, m) in ms.withIndex()) {
            if (m.type == MType.START) continue
            val d = m.s - g.s
            val kf = m.key + ":far"; val kn = m.key + ":near"
            if (d < -4) { fired.add(kf); fired.add(kn); fired.add(m.key + ":post"); fired.add(m.key + ":rem"); continue }   // passed: never late
            if (kn in fired) continue
            val dEff = d - dEffShift
            val near = when (m.type) { MType.CONNECTOR -> NEAR_CONN; MType.FINISH -> NEAR_FINISH; else -> NEAR_TURN }
            if (dEff <= near) {
                val parts: ArrayList<VPart.Rec> = when {
                    m.type == MType.FINISH && r.accessStop != null -> ArrayList(Phrases.accessUnits(r.accessStop!!.level, r.accessStop!!.schedule))
                    m.type == MType.FINISH && r.unreachable -> arrayListOf(VPart.Rec("PedestrianRouteFinished"))   // as close as possible: plain
                    m.type == MType.FINISH -> arrayListOf(variant("Arrival", "PedestrianRouteFinished", r.length, r.connSteps.isNotEmpty()))
                    else -> ArrayList(Phrases.action(m))
                }
                val nx = ms.getOrNull(i + 1)
                if (nx != null && nx.type != MType.FINISH && nx.s - m.s <= CHAIN && m.type != MType.FINISH) {
                    parts.add(VPart.Rec("Then")); parts.addAll(Phrases.action(nx))
                    fired.add(nx.key + ":far"); fired.add(nx.key + ":post"); fired.add(nx.key + ":rem")
                }
                fired.add(kn); fired.add(kf); fired.add(m.key + ":post"); fired.add(m.key + ":rem")
                return said(parts, if (m.type == MType.FINISH) "finish" else "near:$i", true)
            }
            break                                                 // only the nearest maneuver ahead can be due
        }
        // the next maneuver ahead and the one just passed
        val ni = ms.indexOfFirst { it.type != MType.START && it.s - g.s > -4 && (it.key + ":near") !in fired }
        if (ni < 0) return out
        val nm = ms[ni]; val d = nm.s - g.s; val dEff = d - dEffShift
        val pm = ms.getOrNull(ni - 1)
        // 2) right after a maneuver: what comes next
        if (pm != null && pm.type != MType.START && "${nm.key}:post" !in fired && g.s >= pm.s + POST_AFTER && g.s <= pm.s + POST_AFTER + 20)
            return said(whatNext(r, ni, d).ifEmpty { return out }, "post:$ni", false)
        // 3) pre-announcement at ~30 m (after the previous maneuver is completed: its «what next» comes first)
        val prevDone = pm == null || pm.type == MType.START || g.s >= pm.s + POST_AFTER
        if (prevDone && "${nm.key}:far" !in fired && dEff <= FAR && d >= FAR_MIN) {
            fired.add("${nm.key}:far"); fired.add("${nm.key}:rem")
            val n = Phrases.roundDist(d)
            val parts = if (nm.type == MType.FINISH) listOf(variant("RouteWillFinish", "RouteWillFinish")) + Phrases.numberKeys(n).map { VPart.Rec(it) } + VPart.Rec(Phrases.meterKey(n))
                        else Phrases.overDist(n) + Phrases.action(nm)
            return said(parts, "far:$ni", false)
        }
        // 4) long straight: a reminder at ~100 m if nothing was said for a while
        if ("${nm.key}:rem" !in fired && d in REMIND_MIN..REMIND_MAX && g.s - lastSpokenS >= REMIND_GAP) {
            fired.add("${nm.key}:rem")
            val n = Phrases.roundDist(d)
            val parts = if (nm.type == MType.FINISH) listOf(variant("RouteWillFinish", "RouteWillFinish")) + Phrases.numberKeys(n).map { VPart.Rec(it) } + VPart.Rec(Phrases.meterKey(n))
                        else Phrases.overDist(n) + Phrases.action(nm)
            return said(parts, "rem:$ni", false)
        }
        return out
    }
}
