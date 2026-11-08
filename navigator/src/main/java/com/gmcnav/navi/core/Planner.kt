package com.gmcnav.navi.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.hypot

/** A passage closed by the user ("перекрыто"): a disc of the grid, or a whole connector (lift out of order…). */
class Closure(val id: Int, val floor: Int, val x: Double, val y: Double, val radius: Double, val connId: Int = 0, val title: String = "")

/**
 * Route alternatives through genuinely different paths (port of runRoute() in gmc_nav/web/js/app.js):
 * the fastest; lift-only («Без лестниц», for wheelchairs/luggage); without lifts; via another connector;
 * another corridor (cells of the fastest made 3× more expensive). Duplicates (same connectors, similar length,
 * >75 % shared cells) and much slower ones are dropped.
 */
class Planner(val b: Building, val router: Router) {
    val closures = ArrayList<Closure>()
    private var nextId = 1

    fun addClosure(floor: Int, x: Double, y: Double, radius: Double = 2.5, connId: Int = 0, title: String = ""): Closure {
        val c = Closure(nextId++, floor, x, y, radius, connId, title)
        closures.add(c); applyClosures(); return c
    }

    fun removeClosure(id: Int) { closures.removeAll { it.id == id }; applyClosures() }
    fun clearClosures() { closures.clear(); applyClosures() }

    fun applyClosures() {
        router.clearBlocked()
        for (c in closures) if (c.connId == 0) router.block(b.fi(c.floor), c.x, c.y, c.radius)
    }

    private fun closedConns(): Set<Int> = closures.filter { it.connId != 0 }.map { it.connId }.toSet()

    /** A closure placed at a point: on a connector -> the connector; near a door -> the whole door opening. */
    fun closureAt(floor: Int, x: Double, y: Double): Triple<Double, Double, Pair<Double, Connector?>> {
        val c = b.connNear(floor, x, y, 4.0)
        if (c != null) { val p = c.pos.getValue(floor); return Triple(p[0], p[1], 0.0 to c) }
        val d = b.doorNear(floor, x, y, 2.5)
        if (d != null) return Triple(d.x, d.y, maxOf(1.8, d.size * 0.75) to null)
        return Triple(x, y, 2.5 to null)
    }

    /** Clock for scheduled СКУД places (tests may pin it). */
    var clock: () -> Long = { System.currentTimeMillis() }

    fun plan(a: Place, z: Place, accessible: Boolean = false, extraAvoid: Set<Int> = emptySet()): List<Route> {
        if (a.goals.isEmpty() || z.goals.isEmpty()) return emptyList()
        router.myGen.set(++router.gen)                // a newer plan stops this one (see Router.cancelled)
        router.refreshSchedule(clock())          // Учебный офис: open without a pass in its time windows
        router.preferWalked = router.userLevel <= 1   // guests / basic pass: prefer the paths walked during calibration
        val allow = if (accessible) setOf("lift") else setOf("stair", "lift", "escalator")
        val avoid = closedConns() + extraAvoid
        val q = RouteQuery(a.goals.take(1).takeIf { a.kind != "conn" && a.kind != "zone" } ?: a.goals, z.goals, allow, avoid)
        val bestRaw = (if (router.surelyUnreachable(q, soft = false)) null else router.route(q))
            ?: return if (router.cancelled()) emptyList() else accessFallback(a, z, q).ifEmpty { if (router.cancelled()) emptyList() else nearestFallback(a, z, q) }
        val best = router.buildRoute(bestRaw, "Быстрый")
        val out = arrayListOf(best)
        val multi = best.connSteps.isNotEmpty()
        val tries = ArrayList<Pair<String, RouteQuery>>()
        val usedKinds = best.connSteps.map { it.kind }.toSet()
        if (multi) {
            if (!accessible && usedKinds.any { it != "lift" }) tries.add("Без лестниц" to RouteQuery(q.starts, q.goals, setOf("lift"), avoid))
            if (!accessible && "lift" in usedKinds) tries.add("Без лифта" to RouteQuery(q.starts, q.goals, setOf("stair", "escalator"), avoid))
            tries.add("via" to RouteQuery(q.starts, q.goals, allow, avoid + best.connSteps.mapNotNull { it.conn?.id }))
        }
        tries.add("Другой путь" to RouteQuery(q.starts, q.goals, allow, avoid, penalCells = dilate(best.states), penalFactor = 3.0))
        for ((label, qq) in tries) {
            val raw = router.route(qq) ?: continue
            val r = router.buildRoute(raw, label)
            if (label == "via") r.label = "Через " + (r.connSteps.firstOrNull()?.conn?.shortName?.replaceFirstChar { it.lowercase() }
                ?.replace("лестница", "лестницу") ?: "другой проход")
            if (isDup(out, r)) continue
            if (r.cost > best.cost * 2.2 + 60) continue
            out.add(r)
        }
        // single-floor: one more corridor avoiding both previous ones
        if (!multi && out.size == 2) {
            val pen = dilate(best.states + out[1].states)
            router.route(RouteQuery(q.starts, q.goals, allow, avoid, penalCells = pen, penalFactor = 3.0))?.let { raw ->
                val r = router.buildRoute(raw, "Ещё вариант")
                if (!isDup(out, r) && r.cost <= best.cost * 2.2 + 60) out.add(r)
            }
        }
        for (r in out) {
            r.startTitle = a.title; r.goalTitle = z.title
            r.maneuvers = ManeuverBuilder.build(b, r)
        }
        // the lift-only variant may be the fastest itself: name the best by what it is
        if (best.connSteps.isNotEmpty() && best.connSteps.all { it.kind == "lift" } && out.none { it.label == "Без лестниц" }) best.label = "Быстрый · лифт"
        return out.take(4)
    }

    /**
     * The destination is unreachable with the user's access level: find the path that crosses СКУД as little as possible
     * (restricted cells cost 120 s each), stop in front of the first barrier on it (≈ at the door), and route there.
     * Never "route not found" only because of СКУД.
     */
    private fun accessFallback(a: Place, z: Place, q: RouteQuery): List<Route> {
        if (b.access.isEmpty() && b.accessRooms.isEmpty()) return emptyList()
        val raw: RawPath
        val lvl = router.userLevel
        val exempt = router.startExempt(q.starts)       // starting inside a restricted room is fine
        if (router.surelyUnreachable(q, soft = true)) return emptyList()   // no passage even with a pass: «ближе не подойти»
        // fast path: the barrier nearest to the destination, found from the goal side (only the restricted zone is explored)
        val cross = router.crossingsFromGoal(q)
        if (cross != null) {
            val n = b.n
            val toCross = router.route(RouteQuery(q.starts, cross.map { GridPt(it.first / n, it.first % n) }, q.allow, q.avoidConn))
            if (toCross != null) {
                val end = toCross.states.last(); val line = cross.firstOrNull { it.first == end }?.second ?: cross.first().second
                if (line != null) {
                    // step back ≈1.5 m so the stop point is in front of the barrier, not on it (as in the full search below)
                    val goal = toCross.states[maxOf(0, toCross.states.size - 1 - 5)]
                    val r0 = router.route(RouteQuery(q.starts, listOf(GridPt(goal / n, goal % n)), q.allow, q.avoidConn))
                    if (r0 != null) {
                        val r = router.buildRoute(r0, "До прохода СКУД")
                        r.accessStop = line
                        r.startTitle = a.title; r.goalTitle = "${z.title} — ${line.note().replaceFirstChar { it.lowercase() }}"
                        r.maneuvers = ManeuverBuilder.build(b, r)
                        return listOf(r)
                    }
                }
            }
        }
        synchronized(router) {
            router.softAccess = true
            val r = try { router.route(q) } finally { router.softAccess = false }
            raw = r ?: return emptyList()
        }
        val n = b.n
        var stop = -1; var line: AccessLine? = null
        for ((i, st) in raw.states.withIndex()) {
            // restricted step: needs a higher pass, not the start's own room, not a free crossing of a one-way exit
            if (router.stepRestricted(if (i > 0) raw.states[i - 1] else st, st, exempt)) { stop = i - 1; line = router.accessLineAt(st / n, st % n); break }
        }
        if (stop < 0 || line == null) return emptyList()
        // step back a little more (≈1.5 m) so the stop point is in front of the barrier, not on it
        stop = maxOf(0, stop - 5)
        val goal = raw.states[stop]
        val r0 = router.route(RouteQuery(q.starts, listOf(GridPt(goal / n, goal % n)), q.allow, q.avoidConn)) ?: return emptyList()
        val r = router.buildRoute(r0, "До прохода СКУД")
        r.accessStop = line
        r.startTitle = a.title; r.goalTitle = "${z.title} — ${line.note().replaceFirstChar { it.lowercase() }}"
        r.maneuvers = ManeuverBuilder.build(b, r)
        return listOf(r)
    }

    /**
     * No passage to the destination at all (not a СКУД matter): lead as close as possible — to the reachable cell nearest
     * to it (usually just in front of its door / wall), marked [Route.unreachable].
     */
    private fun nearestFallback(a: Place, z: Place, q: RouteQuery): List<Route> {
        val n = b.n
        val fast = router.nearestInArea(q)
        val raw = fast.takeIf { it >= 0 }?.let { router.route(RouteQuery(q.starts, listOf(GridPt(it / n, it % n)), q.allow, q.avoidConn)) }
            ?: run {
                val st = router.nearestReachable(q)
                if (st < 0) return emptyList()
                router.route(RouteQuery(q.starts, listOf(GridPt(st / n, st % n)), q.allow, q.avoidConn)) ?: return emptyList()
            }
        val r = router.buildRoute(raw, "Ближе не подойти")
        r.unreachable = true
        r.startTitle = a.title; r.goalTitle = "${z.title} — ближе не подойти: прохода нет"
        r.maneuvers = ManeuverBuilder.build(b, r)
        return listOf(r)
    }

    /**
     * Rooms the route passes from [from] on, in order (floor * 65536 + room id): rooms crossed for < 2.5 m (door strips)
     * dropped, a brief pass A → X → A collapsed to A.
     */
    fun roomSeq(r: Route, from: Double): List<Long> {
        val runs = ArrayList<Pair<Long, Double>>()          // (room, metres)
        var s = max(0.0, from)
        while (s <= r.length) {
            val (fi, x, y) = r.pointAt(s)
            val id = b.roomIdAt(fi, x, y)
            if (id != 0) {
                val key = b.floorOf(fi) * 65536L + id
                if (runs.isNotEmpty() && runs.last().first == key) runs[runs.size - 1] = key to runs.last().second + 0.5 else runs.add(key to 0.5)
            }
            s += 0.5
        }
        val out = ArrayList<Long>()
        for ((k, m) in runs) if (m >= 2.5 && (out.isEmpty() || out.last() != k)) out.add(k)
        // a brief pass through a side room that returns to the same space (A, X, A — the line clipping a corner) is A
        var i = 1
        while (i + 1 < out.size) { if (out[i - 1] == out[i + 1]) { out.removeAt(i); out.removeAt(i); i = maxOf(1, i - 1) } else i++ }
        return out
    }

    /**
     * «Маршрут перестроен» only for a different path: the rebuilt route [nr] (from the current position) has the same
     * connectors and the same room / door sequence as the rest of [old] from [fromS] -> same path, replaced silently.
     * Progress may lag a little, so the old sequence may start one room earlier (left within the last 10 m).
     */
    fun samePath(nr: Route, old: Route, fromS: Double): Boolean {
        val oldConn = old.connSteps.filter { it.s >= fromS - 1 }.map { it.conn?.id ?: -1 }
        if (oldConn != nr.connSteps.map { it.conn?.id ?: -1 }) return false
        val a = roomSeq(nr, 0.0); val o = roomSeq(old, fromS)
        if (a == o) return true
        if (o.size >= 2 && a == o.drop(1) && roomSeq(old, fromS + 10.0).firstOrNull() == o[1]) return true
        return false
    }

    private fun isDup(out: List<Route>, r: Route): Boolean = out.any { o ->
        o.connSeq() == r.connSeq() && (abs(o.walk - r.walk) < 0.08 * o.walk + 5 && overlap(o, r) > 0.6 || overlap(o, r) > 0.85)
    }

    /** Shared part of two paths on ~1.5 m blocks (a parallel path one cell aside is the same corridor). */
    private fun overlap(r1: Route, r2: Route): Double {
        fun blocks(r: Route): HashSet<Long> {
            val s = HashSet<Long>()
            for (st in r.states) { val f = st / b.n; val c = st % b.n; s.add(f * 1_000_000_000L + (c / b.cols / 5) * 100_000L + (c % b.cols) / 5) }
            return s
        }
        val a = blocks(r1); val z = blocks(r2)
        val k = z.count { it in a }
        return k.toDouble() / maxOf(1, minOf(a.size, z.size))
    }

    /** Path cells widened by ~1.5 m: the penalised band covers the whole corridor, so the alternative must use another one. */
    private fun dilate(states: IntArray, rad: Int = 5): IntArray {
        val out = HashSet<Int>(states.size * 4)
        val n = b.n; val cols = b.cols
        for ((i, st) in states.withIndex()) {
            if (i % 2 == 1) continue
            val f = st / n; val c = st % n; val r0 = c / cols; val c0 = c % cols
            for (dr in -rad..rad) for (dc in -rad..rad) {
                val r = r0 + dr; val cc = c0 + dc
                if (dr * dr + dc * dc <= rad * rad && b.inGrid(r, cc)) out.add(f * n + r * cols + cc)
            }
        }
        return out.toIntArray()
    }

    /**
     * Synthetic СКУД line for testing (until real lines arrive): perpendicular to the a→z route at fraction frac of its length,
     * halfLen metres to each side.
     */
    fun syntheticAccessLine(a: Place, z: Place, frac: Double, level: Int, halfLen: Double = 6.0, id: Int = -1): AccessLine? {
        val raw = synchronized(router) {
            val lvl = router.userLevel; router.userLevel = 127
            val r = router.route(RouteQuery(a.goals.take(1), z.goals)); router.userLevel = lvl; r
        } ?: return null
        val rt = router.buildRoute(raw, "")
        val s = rt.length * frac
        val (fi, x, y) = rt.pointAt(s); val h = rt.headingAt(s)
        val px = kotlin.math.cos(h); val py = -kotlin.math.sin(h)     // perpendicular to (sin h, cos h)
        return AccessLine(id, b.floorOf(fi), level, "Тестовая линия СКУД", doubleArrayOf(x - px * halfLen, x + px * halfLen),
            doubleArrayOf(y - py * halfLen, y + py * halfLen))
    }

    /** Synthetic closed СКУД loop (square, half-size m) around a place: it is reachable only by crossing the line. */
    fun syntheticAccessBox(p: Place, half: Double, level: Int, id: Int = -1): AccessLine =
        AccessLine(id, p.floor, level, "Тестовый СКУД вокруг ${p.title.substringBefore(" ·")}",
            doubleArrayOf(p.x - half, p.x + half, p.x + half, p.x - half, p.x - half), doubleArrayOf(p.y - half, p.y - half, p.y + half, p.y + half, p.y - half))

    /** "Моё местоположение" as a start point. */
    fun placeFromFix(floor: Int, x: Double, y: Double): Place? {
        val fi = b.fi(floor); if (fi < 0) return null
        val cell = b.snap(fi, b.row(y), b.col(x), 40) { router.walkable(fi, it) }     // inside a СКУД room is a valid start
        if (cell < 0) return null
        return Place("me", "Моё местоположение", Building.floorName(floor), floor, b.xOf(cell % b.cols), b.yOf(cell / b.cols), listOf(GridPt(fi, cell)))
    }

    @Suppress("unused") private fun d(a: Place, z: Place) = hypot(a.x - z.x, a.y - z.y)
}
