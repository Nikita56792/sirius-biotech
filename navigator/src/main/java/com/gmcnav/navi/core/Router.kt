package com.gmcnav.navi.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** A grid point: floor index + cell (row * cols + col). */
data class GridPt(val fi: Int, val cell: Int)

class RouteQuery(
    val starts: List<GridPt>,
    val goals: List<GridPt>,
    val allow: Set<String> = setOf("stair", "lift", "escalator"),
    val avoidConn: Set<Int> = emptySet(),
    /** Cells (global ids fi*N+cell) made more expensive — used to find a genuinely different corridor. */
    val penalCells: IntArray? = null,
    val penalFactor: Double = 3.0,
)

class RawPath(val states: IntArray, val cost: Double, val expanded: Int)

/**
 * Multi-floor A* over the 0.3 m navigation grids (port of gmc_nav/web/js/router.js).
 * State = floor index * N + cell. Cost = seconds of walking at 1.2 m/s; floors change only at connectors, priced by the real
 * height between the floors (Vertical: stairs up 0.3 m/s, down 0.45 m/s + landings, escalator, lift wait + doors + ride).
 * Only passable cells get search memory (rank bitset), user closures ("перекрыто") are impassable.
 */
class Router(val b: Building) {
    private val nF = b.floors.size
    private val nCells = b.n
    private val cols = b.cols
    private val rows = b.rows
    private val words = (nCells + 63) / 64
    private val bits = Array(nF) { LongArray(words) }
    private val cum = Array(nF) { IntArray(words) }
    private val base = IntArray(nF + 1)
    val total: Int

    private val g: FloatArray
    private val came: IntArray
    private val visit: IntArray
    private val pen: ByteArray
    /** 1 = closed by the user (blocked passage). Indexed by compact state id. */
    private val blocked: ByteArray
    /** Railings / dividers between stair flights (static): impassable. Indexed by compact state id. */
    private val fence: ByteArray
    /** Stair connector attachment cells per (conn id, floor) when its flights are fenced apart (see [stairFlights]). */
    val flightEntries = HashMap<Pair<Int, Int>, List<Int>>()
    val flightLog = ArrayList<String>()
    /** СКУД: required access level of cells on an access line (0 = free). */
    private val accessLevel: ByteArray
    private val accessLineAt = HashMap<Int, AccessLine>()
    /** One-way (exit) line cells: compact id -> unit normal towards the free (exit) side. */
    private val oneWay = HashMap<Int, DoubleArray>()
    /** The user's pass: 0 = гость, 1 = базовый (default), 2 = расширенный. Cells of lines with a higher level are impassable. */
    @Volatile var userLevel = 1
        set(v) { if (field != v) { field = v; stateVer++ } }

    /** Version of everything reachability depends on (access, closures, pass level): bumps invalidate [labels]. */
    @Volatile private var stateVer = 0

    /** Plan cancellation: a new plan bumps [gen]; searches started for an older one (see [myGen]) stop early. */
    @Volatile var gen = 0
    val myGen = ThreadLocal<Int?>()
    fun cancelled(): Boolean { val m = myGen.get() ?: return false; return m != gen }
    /** Fallback search: restricted cells are passable at +120 s each (only the unavoidable barrier gets crossed). */
    @Volatile var softAccess = false
    /** Guest / basic pass: cells walked during calibration cost ×[WALKED_FACTOR] (public paths preferred); others neutral. */
    @Volatile var preferWalked = false
    private var gid = 0

    class Edge(val to: Int, val conn: Connector, val df: Int, val cost: Double)
    private val edges = HashMap<Int, ArrayList<Edge>>()
    /** Cheapest connector time per floor-index step (keeps the A* heuristic a lower bound). */
    private var minConn = Double.MAX_VALUE
    /** Compact ids that have connector edges (no HashMap lookup for every expanded cell). */
    private lateinit var hasEdge: BooleanArray
    /** Goal marks for the current search (compact id -> search id), instead of a boxed HashSet lookup per expansion. */
    private lateinit var goalTag: IntArray

    /** Compact ids of the cells of each access room, computed once (key = floor index * 65536 + room id). */
    private val roomCells: Map<Int, IntArray> by lazy {
        val want = b.accessRooms.map { b.fi(it.floor) * 65536 + it.room }.toSet()
        val lists = HashMap<Int, ArrayList<Int>>()
        for (fi in 0 until nF) {
            val gr = b.grids[fi]
            for (cell in 0 until nCells) {
                val id = gr[cell].toInt() and 0xffff
                if (id == 0) continue
                val k = fi * 65536 + id
                if (k in want) lists.getOrPut(k) { ArrayList() }.add(sid(fi, cell))
            }
        }
        lists.mapValues { it.value.toIntArray() }
    }
    /** Which scheduled places were open at the last rebuild (so a rebuild happens only when that changes). */
    private var schedState = ""


    init {
        var acc = 0
        for (fi in 0 until nF) {
            val grid = b.grids[fi]; val bw = bits[fi]; val cw = cum[fi]
            base[fi] = acc
            var cnt = 0
            for (w in 0 until words) {
                cw[w] = cnt
                var word = 0L
                val c0 = w * 64
                val c1 = min(nCells, c0 + 64)
                for (c in c0 until c1) if (grid[c].toInt() != 0) word = word or (1L shl (c - c0))
                bw[w] = word
                cnt += java.lang.Long.bitCount(word)
            }
            acc += cnt
        }
        base[nF] = acc
        total = acc
        g = FloatArray(total); came = IntArray(total); visit = IntArray(total); pen = ByteArray(total); blocked = ByteArray(total)
        accessLevel = ByteArray(total)
        fence = ByteArray(total)
        rebuildAccess()
        stairFlights()
        // connector edges between their floor positions (snapped onto the grid; split stairs: one per flight start)
        for (c in b.conns) {
            val fl = c.floors.filter { c.pos[it] != null && b.fi(it) >= 0 }
            val st = HashMap<Int, List<Int>>()
            for (f in fl) {
                val fi = b.fi(f)
                val fe = flightEntries[c.id to f]
                if (fe != null) { st[f] = fe.map { fi * nCells + it }; continue }
                val p = c.pos.getValue(f)
                val cell = b.snap(fi, p[2].toInt(), p[3].toInt(), 12) { fence[sid(fi, it)].toInt() == 0 }
                if (cell >= 0) st[f] = listOf(fi * nCells + cell)
            }
            for (a in st.keys) for (z in st.keys) if (a != z) {
                val cc = Vertical.cost(c.kind, b.elevOf(a), b.elevOf(z))
                val dfi = abs(b.fi(a) - b.fi(z))
                if (cc / dfi < minConn) minConn = cc / dfi
                for (sa in st.getValue(a)) for (sz in st.getValue(z))
                    edges.getOrPut(sa) { ArrayList() }.add(Edge(sz, c, abs(a - z), cc))
            }
        }
        if (minConn == Double.MAX_VALUE) minConn = 0.0
        hasEdge = BooleanArray(total); for (st in edges.keys) { val x = sid(st / nCells, st % nCells); if (x >= 0) hasEdge[x] = true }
        goalTag = IntArray(total)
    }

    /** Compact id of a passable cell, -1 for walls. */
    fun sid(fi: Int, cell: Int): Int {
        val w = cell ushr 6
        val word = bits[fi][w]
        val bit = 1L shl (cell and 63)
        if (word and bit == 0L) return -1
        return base[fi] + cum[fi][w] + java.lang.Long.bitCount(word and (bit - 1))
    }

    fun open(fi: Int, cell: Int): Boolean { val s = sid(fi, cell); return s >= 0 && fence[s].toInt() == 0 && blocked[s].toInt() == 0 && accessLevel[s] <= userLevel }

    /**
     * A grid step into a one-way (exit) line cell is free when it goes along the exit direction (cos > 0.3).
     * dr/dc: row/column change of the step (rows grow southwards, so y changes by −dr).
     */
    private fun freeExit(s: Int, dr: Int, dc: Int): Boolean {
        val e = oneWay[s] ?: return false
        val len = hypot(dr.toDouble(), dc.toDouble()); if (len == 0.0) return false
        return (dc * e[0] - dr * e[1]) / len > 0.3
    }

    /** The step between two grid states (same floor) needs a pass the user does not have (one-way exits respected). */
    fun stepRestricted(from: Int, to: Int, exempt: Set<Int> = emptySet()): Boolean {
        val f = to / nCells; val cell = to % nCells; val s = sid(f, cell)
        if (s < 0 || accessLevel[s] <= userLevel || s in exempt) return false
        if (from / nCells != f) return true
        val fc = from % nCells
        return !freeExit(s, cell / cols - fc / cols, cell % cols - fc % cols)
    }

    /** Required access level at a cell (0 = free). */
    fun accessAt(fi: Int, cell: Int): Int { val s = sid(fi, cell); return if (s >= 0) accessLevel[s].toInt() else 0 }
    fun accessLineAt(fi: Int, cell: Int): AccessLine? { val s = sid(fi, cell); return if (s >= 0) accessLineAt[s] else null }

    /**
     * СКУД barriers (call after changing b.access; [refreshSchedule] calls it when a time window opens or closes):
     *  - a line is a 4-connected 1-cell barrier along its whole length (no diagonal leaks; the ends are extended 0.45 m
     *    into the walls, so no holes — rooms often touch along open boundaries in the grid, a door-only barrier leaks);
     *    the doors it touches carry its level;
     *  - access rooms: every cell of the room needs the level, so its other entrances are covered too;
     *  - scheduled places (Учебный офис): level 0 while a window is open (local time), else their level.
     */
    @Synchronized
    fun rebuildAccess(nowMs: Long = System.currentTimeMillis()) {
        stateVer++
        java.util.Arrays.fill(accessLevel, 0); accessLineAt.clear(); oneWay.clear()
        schedState = scheduleState(nowMs)
        val lineById = b.access.associateBy { it.id }
        for (a in b.access) {
            val fi = b.fi(a.floor); if (fi < 0) continue
            val lvl = a.levelAt(nowMs); if (lvl <= 0) continue
            var pr = Int.MIN_VALUE; var pc = 0
            fun mark(r: Int, c: Int) {
                if (!b.inGrid(r, c)) return
                val s = sid(fi, r * cols + c); if (s < 0) return
                val cur = accessLevel[s].toInt()
                if (lvl > cur) { accessLevel[s] = lvl.coerceIn(1, 120).toByte(); accessLineAt[s] = a; a.exitDir?.let { oneWay[s] = it } ?: oneWay.remove(s) }
                else if (lvl == cur && a.exitDir == null) oneWay.remove(s)          // a two-way line over the same cell wins
            }
            val n0 = a.xs.size
            for (k in 1 until n0) {
                var x0 = a.xs[k - 1]; var y0 = a.ys[k - 1]; var x1 = a.xs[k]; var y1 = a.ys[k]
                val len = hypot(x1 - x0, y1 - y0)
                if (len > 1e-6) {       // extend the polyline ends into the walls
                    val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
                    if (k == 1) { x0 -= ux * 0.45; y0 -= uy * 0.45 }
                    if (k == n0 - 1) { x1 += ux * 0.45; y1 += uy * 0.45 }
                }
                val n = maxOf(1, ceil(hypot(x1 - x0, y1 - y0) / (b.res / 4)).toInt())
                for (i in 0..n) {
                    val t = i.toDouble() / n
                    val r = b.row(y0 + (y1 - y0) * t); val c = b.col(x0 + (x1 - x0) * t)
                    if (r == pr && c == pc) continue
                    if (pr != Int.MIN_VALUE && abs(r - pr) == 1 && abs(c - pc) == 1) mark(pr, c)   // close the diagonal gap
                    mark(r, c); pr = r; pc = c
                }
            }
        }
        for (q in b.accessRooms) {
            val fi = b.fi(q.floor); if (fi < 0) continue
            val lvl = q.levelAt(nowMs); if (lvl <= 0) continue
            val line = lineById[q.line] ?: AccessLine(-q.room, q.floor, q.level, q.name, DoubleArray(0), DoubleArray(0), q.schedule)
            for (s in roomCells[fi * 65536 + q.room] ?: continue) {
                if (s < 0) continue
                if (lvl > accessLevel[s]) { accessLevel[s] = lvl.coerceIn(1, 120).toByte(); accessLineAt[s] = line }
            }
        }
    }

    private fun scheduleState(nowMs: Long): String =
        (b.access.filter { it.schedule.isNotEmpty() }.map { Access.openNow(it.schedule, nowMs) } +
         b.accessRooms.filter { it.schedule.isNotEmpty() }.map { Access.openNow(it.schedule, nowMs) }).joinToString("") { if (it) "1" else "0" }

    /** Rebuilds the barriers if a scheduled place opened or closed since the last build. Returns true if it did. */
    fun refreshSchedule(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (scheduleState(nowMs) == schedState) return false
        rebuildAccess(nowMs); return true
    }

    /**
     * Starting inside a restricted place is allowed: the restricted cells connected to a start that is itself restricted
     * (the room, its door line) are passable for this query.
     */
    fun startExempt(starts: List<GridPt>): Set<Int> {
        val out = HashSet<Int>()
        for (st in starts) {
            val s0 = sid(st.fi, st.cell)
            if (s0 < 0 || accessLevel[s0] <= userLevel || s0 in out) continue
            val q = ArrayDeque<Int>(); q.add(st.cell); out.add(s0)
            while (q.isNotEmpty() && out.size < 400_000) {
                val cell = q.removeFirst(); val r = cell / cols; val c = cell % cols
                for (k in 0 until 4) {
                    val nr = r + DR[k]; val nc = c + DC[k]
                    if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue
                    val ncell = nr * cols + nc; val ns = sid(st.fi, ncell)
                    if (ns < 0 || ns in out || fence[ns].toInt() != 0 || blocked[ns].toInt() != 0 || accessLevel[ns] <= userLevel) continue
                    out.add(ns); q.add(ncell)
                }
            }
        }
        return out
    }

    /** Passable and not closed by the user (СКУД ignored): where "my location" may be. */
    fun walkable(fi: Int, cell: Int): Boolean { val s = sid(fi, cell); return s >= 0 && fence[s].toInt() == 0 && blocked[s].toInt() == 0 }

    /**
     * Stair flights. Where a stairwell's flights are drawn with arrows in opposite directions they are separate flights
     * fenced off from each other by railings: the railings (rail lines) and the long divider lines of the drawing
     * (perpendicular to the step hatching, not arrow shafts) become fences, and when the stairwell then falls apart into
     * several flights that carry arrows, the connector is entered at each flight's own start (the tail of its first arrow)
     * instead of one point between the flights. Stairs that stay in one piece keep their single connector point.
     */
    private fun stairFlights() {
        for (c in b.conns) {
            if (c.kind != "stair") continue
            val sd = b.stairs[c.id] ?: continue
            val bb = c.bbox ?: continue
            for ((f, segs) in sd) {
                val fi = b.fi(f); if (fi < 0) continue
                val (stair, rail) = segs
                fun len(s: DoubleArray) = hypot(s[2] - s[0], s[3] - s[1])
                fun ang(s: DoubleArray): Double { var a = atan2(s[3] - s[1], s[2] - s[0]); if (a < 0) a += Math.PI; if (a >= Math.PI) a -= Math.PI; return a }
                // dominant step direction (hatching), 5° bins weighted by length
                val hist = DoubleArray(36)
                for (s in stair) { val l = len(s); if (l in 0.8..6.0) hist[(ang(s) / Math.PI * 36).toInt().coerceIn(0, 35)] += l }
                if (hist.max() <= 0) continue
                val stepA = (hist.indices.maxByOrNull { hist[it] }!! + 0.5) / 36 * Math.PI
                // arrow tails: little circles (clusters of tiny segments)
                val tiny = stair.filter { len(it) < 0.06 }
                val tails = ArrayList<DoubleArray>()
                val used = BooleanArray(tiny.size)
                for (i in tiny.indices) {
                    if (used[i]) continue
                    val grp = arrayListOf(i); used[i] = true; var k = 0
                    while (k < grp.size) { val a = tiny[grp[k]]; for (j in tiny.indices) if (!used[j] && hypot(tiny[j][0] - a[2], tiny[j][1] - a[3]) < 0.03 || !used[j] && hypot(tiny[j][2] - a[0], tiny[j][3] - a[1]) < 0.03) { used[j] = true; grp.add(j) }; k++ }
                    if (grp.size >= 6) tails.add(doubleArrayOf(grp.map { tiny[it][0] }.average(), grp.map { tiny[it][1] }.average()))
                }
                // arrows: shafts (0.6–5 m) with a head — ≥ 2 short strokes (0.1–0.5 m) leaving the tip -> (tail x, y, dir x, y)
                val short = stair.filter { len(it) in 0.1..0.5 }
                fun strokesAt(x: Double, y: Double) = short.count { hypot(it[0] - x, it[1] - y) < 0.03 || hypot(it[2] - x, it[3] - y) < 0.03 }
                val arrows = ArrayList<DoubleArray>(); val shafts = HashSet<DoubleArray>()
                for (s in stair) {
                    val l = len(s); if (l !in 0.6..5.0) continue
                    val h1 = strokesAt(s[2], s[3]); val h0 = strokesAt(s[0], s[1])
                    if (h1 >= 2 && h0 < 2) { arrows.add(doubleArrayOf(s[0], s[1], (s[2] - s[0]) / l, (s[3] - s[1]) / l)); shafts.add(s) }
                    else if (h0 >= 2 && h1 < 2) { arrows.add(doubleArrayOf(s[2], s[3], (s[0] - s[2]) / l, (s[1] - s[3]) / l)); shafts.add(s) }
                }
                // shafts that start at a little circle (tail mark) are arrows too, whatever their head looks like
                for (s in stair) {
                    val l = len(s); if (l !in 0.6..5.0 || s in shafts) continue
                    for (t in tails) {
                        if (hypot(s[0] - t[0], s[1] - t[1]) < 0.1) { arrows.add(doubleArrayOf(t[0], t[1], (s[2] - s[0]) / l, (s[3] - s[1]) / l)); shafts.add(s); break }
                        if (hypot(s[2] - t[0], s[3] - t[1]) < 0.1) { arrows.add(doubleArrayOf(t[0], t[1], (s[0] - s[2]) / l, (s[1] - s[3]) / l)); shafts.add(s); break }
                    }
                }
                // fences: railings + long dividers along the run (perpendicular to the steps)
                val fences = ArrayList<DoubleArray>(rail)
                for (s in stair) {
                    if (s in shafts || len(s) < 2.0) continue
                    var da = abs(ang(s) - stepA); if (da > Math.PI / 2) da = Math.PI - da
                    if (da > Math.toRadians(70.0)) fences.add(s)
                }
                for (s in fences) rasterFence(fi, s[0], s[1], s[2], s[3])
                // tiny enclosed pockets (e.g. the channel between the two lines of a double divider) become fence too
                run {
                    val r0 = b.row(bb[3] + 0.6); val r1 = b.row(bb[1] - 0.6); val c0 = b.col(bb[0] - 0.6); val c1 = b.col(bb[2] + 0.6)
                    val seen = HashSet<Int>()
                    for (r in r0..r1) for (cc in c0..c1) {
                        if (!b.inGrid(r, cc)) continue
                        val cell = r * cols + cc; val s0 = sid(fi, cell)
                        if (s0 < 0 || fence[s0].toInt() != 0 || cell in seen) continue
                        val pocket = arrayListOf(cell); seen.add(cell); var k = 0; var big = false
                        while (k < pocket.size && !big) {
                            val x = pocket[k++]; val rr = x / cols; val xc = x % cols
                            for (j in 0 until 4) {
                                val nr = rr + DR[j]; val nc = xc + DC[j]
                                if (!b.inGrid(nr, nc)) continue
                                val nx = nr * cols + nc; val ns = sid(fi, nx)
                                if (ns < 0 || fence[ns].toInt() != 0 || nx in seen) continue
                                if (nr < r0 || nr > r1 || nc < c0 || nc > c1) { big = true; break }   // reaches outside the stairwell
                                seen.add(nx); pocket.add(nx)
                                if (pocket.size > 24) { big = true; break }
                            }
                        }
                        if (!big) for (x in pocket) fence[sid(fi, x)] = 1
                    }
                }
                // flights: arrows grouped by direction; a flight starts at the tail of its rearmost arrow
                val groups = ArrayList<DoubleArray>()
                for (a in arrows) {
                    val gi = groups.indexOfFirst { gr -> gr[2] * a[2] + gr[3] * a[3] > 0.7 }
                    if (gi < 0) groups.add(a)
                    else if (a[0] * a[2] + a[1] * a[3] < groups[gi][0] * groups[gi][2] + groups[gi][1] * groups[gi][3]) groups[gi] = a
                }
                val opposite = groups.any { p -> groups.any { q -> p[2] * q[2] + p[3] * q[3] < -0.5 } }
                if (groups.size < 2 || !opposite) continue      // one direction only: keep the connector point
                val entries = groups.map { a ->
                    // a little into the flight from the tail (0.3 m), on a free cell
                    b.snap(fi, b.row(a[1] + a[3] * 0.3), b.col(a[0] + a[2] * 0.3), 4) { val x = sid(fi, it); x >= 0 && fence[x].toInt() == 0 }
                }.filter { it >= 0 }.distinct()
                if (entries.size >= 2) {
                    flightEntries[c.id to f] = entries
                    flightLog.add("${c.name} floor $f: ${entries.size} flights, entries " + entries.joinToString { "(%.1f, %.1f)".format(java.util.Locale.US, b.xOf(it % cols), b.yOf(it / cols)) })
                }
            }
        }
    }

    private fun rasterFence(fi: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
        var pr = Int.MIN_VALUE; var pc = 0
        fun mark(r: Int, c: Int) { if (!b.inGrid(r, c)) return; val s = sid(fi, r * cols + c); if (s >= 0) fence[s] = 1 }
        val n = maxOf(1, ceil(hypot(x1 - x0, y1 - y0) / (b.res / 4)).toInt())
        for (i in 0..n) {
            val t = i.toDouble() / n
            val r = b.row(y0 + (y1 - y0) * t); val c = b.col(x0 + (x1 - x0) * t)
            if (r == pr && c == pc) continue
            if (pr != Int.MIN_VALUE && abs(r - pr) == 1 && abs(c - pc) == 1) mark(pr, c)
            mark(r, c); pr = r; pc = c
        }
    }

    // ------------------------------------------------------------------ closures
    fun clearBlocked() { java.util.Arrays.fill(blocked, 0); stateVer++ }

    /** Marks a disc of passable cells as impassable (a closed passage / door). */
    fun block(fi: Int, x: Double, y: Double, radius: Double) {
        val r0 = b.row(y); val c0 = b.col(x); val rc = ceil(radius / b.res).toInt()
        for (dr in -rc..rc) for (dc in -rc..rc) {
            if (dr * dr + dc * dc > rc * rc) continue
            val r = r0 + dr; val c = c0 + dc
            if (!b.inGrid(r, c)) continue
            val s = sid(fi, r * cols + c)
            if (s >= 0) blocked[s] = 1
        }
        stateVer++
    }

    // ------------------------------------------------------------------ reachability: connected areas of the grid
    // «Нет пути» used to cost a full A* over the whole site (millions of cells, minutes on a phone), several times per plan
    // (strict search, СКУД search, nearest point). Areas are labelled once per state (pass level, closures, allowed
    // connectors) and reused: different areas = no path, at once. One-way exits count as passable, so a label may only
    // say «maybe reachable» (then the search runs as before) — never hides a real route.
    private var labKey: String? = null; private var lab: IntArray? = null
    private var labSoftKey: String? = null; private var labSoft: IntArray? = null

    private fun labels(soft: Boolean, allow: Set<String>, avoid: Set<Int>): IntArray {
        val key = "$stateVer|$userLevel|${allow.sorted()}|${avoid.sorted()}"
        if (soft) { if (labSoftKey == key) return labSoft!! } else if (labKey == key) return lab!!
        val L = IntArray(total); var comp = 0
        val queue = IntArray(total)
        fun pass(x: Int) = fence[x].toInt() == 0 && blocked[x].toInt() == 0 && (soft || accessLevel[x] <= userLevel || oneWay.containsKey(x))
        for (fi in 0 until nF) for (cell in 0 until nCells) {
            val s0 = sid(fi, cell); if (s0 < 0 || L[s0] != 0 || !pass(s0)) continue
            comp++; L[s0] = comp; var qh = 0; var qt = 0; queue[qt++] = cell
            while (qh < qt) {
                val c = queue[qh++]; val r = c / cols; val cc = c - r * cols
                for (k in 0 until 4) {
                    val nr = r + DR[k]; val nc = cc + DC[k]
                    if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue
                    val nx = nr * cols + nc; val ns = sid(fi, nx)
                    if (ns < 0 || L[ns] != 0 || !pass(ns)) continue
                    L[ns] = comp; queue[qt++] = nx
                }
            }
        }
        val parent = IntArray(comp + 1) { it }
        fun find(x: Int): Int { var a = x; while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }; return a }
        for ((from, es) in edges) {
            val sa = sid(from / nCells, from % nCells); if (sa < 0 || L[sa] == 0) continue
            for (e in es) {
                if (e.conn.kind !in allow || e.conn.id in avoid) continue
                val sb = sid(e.to / nCells, e.to % nCells); if (sb < 0 || L[sb] == 0) continue
                val ra = find(L[sa]); val rb = find(L[sb]); if (ra != rb) parent[ra] = rb
            }
        }
        for (i in L.indices) if (L[i] != 0) L[i] = find(L[i])
        if (soft) { labSoft = L; labSoftKey = key } else { lab = L; labKey = key }
        return L
    }

    /** True when no start can reach any goal for sure (different connected areas). Starts inside a restricted room: unknown. */
    @Synchronized
    fun surelyUnreachable(q: RouteQuery, soft: Boolean): Boolean {
        if (!soft && startExempt(q.starts).isNotEmpty()) return false
        val L = labels(soft, q.allow, q.avoidConn)
        val sl = HashSet<Int>()
        for (st in q.starts) { val x = sid(st.fi, st.cell); if (x < 0 || L[x] == 0) return false; sl.add(L[x]) }
        for (gp in q.goals) { val x = sid(gp.fi, gp.cell); if (x >= 0 && L[x] in sl) return false }
        return sl.isNotEmpty()
    }

    /**
     * Where to stop in front of a СКУД barrier, found from the GOAL side: BFS from the goal through cells outside the starts'
     * area (restricted rooms and lines, ignoring access) until it touches the starts' area. Returns the touching cells of the
     * starts' area at the smallest number of steps (the barrier nearest to the destination) with the line crossed there,
     * or null when unknown (start inside a restricted room, no labels) — then the full СКУД search runs as before.
     * Explores only the restricted zone around the destination instead of the whole site.
     */
    @Synchronized
    fun crossingsFromGoal(q: RouteQuery): List<Pair<Int, AccessLine?>>? {
        if (startExempt(q.starts).isNotEmpty() || q.goals.isEmpty()) return null
        val L = labels(false, q.allow, q.avoidConn)
        val sl = q.starts.mapNotNull { st -> sid(st.fi, st.cell).takeIf { it >= 0 && L[it] != 0 }?.let { L[it] } }.toSet()
        if (sl.isEmpty()) return null
        gid++; val tag = gid * 2
        val depth = HashMap<Int, Int>()
        var cur = ArrayList<Int>(); val out = ArrayList<Pair<Int, AccessLine?>>(); val seenOut = HashSet<Int>()
        for (gp in q.goals) { val x = sid(gp.fi, gp.cell); if (x >= 0 && visit[x] != tag) { visit[x] = tag; cur.add(gp.fi * nCells + gp.cell) } }
        var d = 0
        while (cur.isNotEmpty() && out.isEmpty() && d < 4000) {
            if (cancelled()) return null
            val next = ArrayList<Int>()
            for (state in cur) {
                val f = state / nCells; val cell = state - f * nCells; val r = cell / cols; val c = cell - r * cols
                val xs = sid(f, cell)
                fun touch(ns: Int, nstate: Int) {
                    if (L[ns] in sl) { if (seenOut.add(nstate)) out.add(nstate to (accessLineAt[xs] ?: accessLineAt[ns])) }
                    else if (visit[ns] != tag) { visit[ns] = tag; next.add(nstate) }
                }
                for (k in 0 until 4) {
                    val nr = r + DR[k]; val nc = c + DC[k]
                    if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue
                    val ns = sid(f, nr * cols + nc)
                    if (ns < 0 || fence[ns].toInt() != 0 || blocked[ns].toInt() != 0) continue
                    touch(ns, f * nCells + nr * cols + nc)
                }
                if (hasEdge[xs]) edges[state]?.forEach { e ->
                    if (e.conn.kind in q.allow && e.conn.id !in q.avoidConn) {
                        val ts = sid(e.to / nCells, e.to % nCells); if (ts >= 0 && blocked[ts].toInt() == 0) touch(ts, e.to)
                    }
                }
            }
            cur = next; d++
        }
        return out.ifEmpty { null }
    }

    /** The cell of the starts' area nearest to the goal (same rule as [nearestReachable]); -1 = unknown (use the BFS). */
    @Synchronized
    fun nearestInArea(q: RouteQuery): Int {
        if (startExempt(q.starts).isNotEmpty() || q.goals.isEmpty()) return -1
        val L = labels(false, q.allow, q.avoidConn)
        val sl = q.starts.mapNotNull { st -> sid(st.fi, st.cell).takeIf { it >= 0 && L[it] != 0 }?.let { L[it] } }.toSet()
        if (sl.isEmpty()) return -1
        val gfi = q.goals.first().fi
        val gp = q.goals.filter { it.fi == gfi }.map { doubleArrayOf(b.xOf(it.cell % cols), b.yOf(it.cell / cols)) }
        var best = -1; var bestD = Double.MAX_VALUE
        for (pass in 0..1) {
            for (fi in 0 until nF) {
                if ((pass == 0) != (fi == gfi)) continue
                for (cell in 0 until nCells) {
                    val x = sid(fi, cell); if (x < 0 || L[x] !in sl) continue
                    val px = b.xOf(cell % cols); val py = b.yOf(cell / cols)
                    var d = Double.MAX_VALUE; for (p in gp) { val v = hypot(p[0] - px, p[1] - py); if (v < d) d = v }
                    if (d < bestD) { bestD = d; best = fi * nCells + cell }
                }
            }
            if (best >= 0) break
        }
        return best
    }

    // ------------------------------------------------------------------ A*
    private class Heap {
        var k = DoubleArray(1 shl 16); var v = IntArray(1 shl 16); var n = 0
        fun clear() { n = 0 }
        fun push(key: Double, value: Int) {
            if (n >= k.size) { k = k.copyOf(k.size * 2); v = v.copyOf(v.size * 2) }
            var i = n++
            while (i > 0) { val p = (i - 1) shr 1; if (k[p] <= key) break; k[i] = k[p]; v[i] = v[p]; i = p }
            k[i] = key; v[i] = value
        }
        fun pop(): Int {
            val top = v[0]; val m = --n
            if (m > 0) {
                val key = k[m]; val value = v[m]; var i = 0
                while (true) {
                    var l = 2 * i + 1; if (l >= m) break
                    if (l + 1 < m && k[l + 1] < k[l]) l++
                    if (k[l] >= key) break
                    k[i] = k[l]; v[i] = v[l]; i = l
                }
                k[i] = key; v[i] = value
            }
            return top
        }
    }
    private val heap = Heap()

    @Synchronized
    fun route(q: RouteQuery): RawPath? {
        gid++
        val openTag = gid * 2; val closedTag = gid * 2 + 1
        var nGoals = 0
        val gb = HashMap<Int, IntArray>()   // floor index -> bbox r0,c0,r1,c1 of goals
        for (s in q.goals) {
            val gx = sid(s.fi, s.cell); if (gx < 0) continue
            goalTag[gx] = gid; nGoals++
            val r = s.cell / cols; val c = s.cell % cols
            val bb = gb.getOrPut(s.fi) { intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE) }
            bb[0] = min(bb[0], r); bb[1] = min(bb[1], c); bb[2] = max(bb[2], r); bb[3] = max(bb[3], c)
        }
        if (nGoals == 0) return null
        val gbl = gb.entries.map { it.key to it.value }
        val res = b.res
        val pw = preferWalked
        val hk = if (pw) WALKED_FACTOR else 1.0          // keep the heuristic admissible under the discount
        // СКУД search (soft): from an area that does not contain the goal at least one restricted cell must be crossed
        // (+120 s) — added to the heuristic (still a lower bound), so A* does not flood ~150 m around before crossing
        val labS: IntArray? = if (softAccess && labKey == "$stateVer|$userLevel|${q.allow.sorted()}|${q.avoidConn.sorted()}") lab else null
        val goalLab = HashSet<Int>()
        if (labS != null) for (s0 in q.goals) { val x = sid(s0.fi, s0.cell); if (x >= 0) goalLab.add(labS[x]) }
        fun h0(f: Int, r: Int, c: Int): Double {
            var best = Double.MAX_VALUE
            for ((gf, bb) in gbl) {
                val dr = if (r < bb[0]) bb[0] - r else if (r > bb[2]) r - bb[2] else 0
                val dc = if (c < bb[1]) bb[1] - c else if (c > bb[3]) c - bb[3] else 0
                val hv = sqrt((dr * dr + dc * dc).toDouble()) * res / WALK * hk + abs(gf - f) * minConn
                if (hv < best) best = hv
            }
            return best
        }
        fun h(f: Int, r: Int, c: Int): Double {
            if (labS != null) {
                val x = sid(f, r * cols + c)
                if (x >= 0 && labS[x] != 0 && labS[x] !in goalLab) return h0(f, r, c) + 120.0
            }
            return h0(f, r, c)
        }
        val penCells = q.penalCells
        if (penCells != null) for (s in penCells) { val c = sid(s / nCells, s % nCells); if (c >= 0) pen[c] = 1 }
        val penF = q.penalFactor
        val exempt = startExempt(q.starts)
        val ex: Set<Int>? = exempt.ifEmpty { null }
        val soft = softAccess
        val lvlU = userLevel
        fun ok(f: Int, cell: Int): Boolean { val x = sid(f, cell); return x >= 0 && fence[x].toInt() == 0 && blocked[x].toInt() == 0 && (soft || accessLevel[x] <= lvlU || (ex != null && x in ex)) }
        heap.clear()
        for (s in q.starts) {
            val c = sid(s.fi, s.cell); if (c < 0) continue
            g[c] = 0f; visit[c] = openTag; came[c] = -1
            heap.push(h(s.fi, s.cell / cols, s.cell % cols), s.fi * nCells + s.cell)
        }
        val step = res / WALK
        var found = -1; var expanded = 0
        while (heap.n > 0) {
            val s = heap.pop()
            val f = s / nCells; val cell = s - f * nCells
            val cs = sid(f, cell)
            if (visit[cs] == closedTag) continue
            visit[cs] = closedTag; expanded++
            if ((expanded and 4095) == 0 && cancelled()) break
            if (goalTag[cs] == gid) { found = s; break }
            val r = cell / cols; val c = cell - r * cols
            val gs = g[cs]
            for (k in 0 until 8) {
                val nr = r + DR[k]; val nc = c + DC[k]
                if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue
                val ncell = nr * cols + nc
                val ns = sid(f, ncell)
                if (ns < 0 || fence[ns].toInt() != 0 || blocked[ns].toInt() != 0) continue
                val restricted = accessLevel[ns] > lvlU && (ex == null || ns !in ex) && !freeExit(ns, DR[k], DC[k])
                if (restricted && !soft) continue
                if (k >= 4 && (!ok(f, r * cols + nc) || !ok(f, nr * cols + c))) continue
                if (visit[ns] == closedTag) continue
                val w = step * DW[k] * (if (pen[ns].toInt() != 0) penF else 1.0) * (if (pw && b.isWalked(f, ncell)) WALKED_FACTOR else 1.0) +
                    (if (restricted) 120.0 else 0.0)
                val ng = gs + w
                if (visit[ns] != openTag || ng < g[ns]) {
                    g[ns] = ng.toFloat(); visit[ns] = openTag; came[ns] = s
                    heap.push(ng + h(f, nr, nc), f * nCells + ncell)
                }
            }
            val es = if (hasEdge[cs]) edges[s] else null
            if (es != null) for (e in es) {
                if (e.conn.kind !in q.allow || e.conn.id in q.avoidConn) continue
                val tf = e.to / nCells; val tc = e.to - tf * nCells
                val ts = sid(tf, tc)
                if (ts < 0 || blocked[ts].toInt() != 0 || (accessLevel[ts] > lvlU && !soft && (ex == null || ts !in ex)) || visit[ts] == closedTag) continue
                val ng = gs + e.cost
                if (visit[ts] != openTag || ng < g[ts]) {
                    g[ts] = ng.toFloat(); visit[ts] = openTag; came[ts] = s
                    heap.push(ng + h(tf, tc / cols, tc % cols), e.to)
                }
            }
        }
        if (penCells != null) for (s in penCells) { val c = sid(s / nCells, s % nCells); if (c >= 0) pen[c] = 0 }
        if (found < 0) return null
        val out = ArrayList<Int>()
        var s = found
        while (s != -1) { out.add(s); val c = sid(s / nCells, s % nCells); s = came[c] }
        out.reverse()
        return RawPath(out.toIntArray(), g[sid(found / nCells, found % nCells)].toDouble(), expanded)
    }

    /**
     * The target cannot be reached at all (no passage): the reachable cell nearest to it (straight distance on the goal's
     * floor; other floors only if nothing on it is reachable), searched by BFS from the starts through open cells and the
     * allowed connectors. Returns a global state (fi * N + cell) or -1.
     */
    @Synchronized
    fun nearestReachable(q: RouteQuery): Int {
        gid++
        val tag = gid * 2
        val goals = q.goals.filter { sid(it.fi, it.cell) >= 0 || true }
        if (goals.isEmpty()) return -1
        val gfi = goals.first().fi
        val gpts = goals.filter { it.fi == gfi }.map { doubleArrayOf(b.xOf(it.cell % cols), b.yOf(it.cell / cols)) }
        val exempt = startExempt(q.starts)
        var best = -1; var bestD = Double.MAX_VALUE
        val queue = IntArray(total.coerceAtMost(8_000_000)); var qh = 0; var qt = 0
        fun push(state: Int) {
            val f = state / nCells; val c = state - f * nCells; val s = sid(f, c)
            if (s < 0 || visit[s] == tag || qt >= queue.size) return
            visit[s] = tag; queue[qt++] = state
        }
        for (st in q.starts) push(st.fi * nCells + st.cell)
        while (qh < qt) {
            if ((qh and 4095) == 0 && cancelled()) return -1
            val state = queue[qh++]
            val f = state / nCells; val cell = state - f * nCells; val r = cell / cols; val c = cell - r * cols
            val x = b.xOf(c); val y = b.yOf(r)
            val d = (if (f == gfi) 0.0 else 1e6) + gpts.minOf { hypot(it[0] - x, it[1] - y) }
            if (d < bestD) { bestD = d; best = state }
            for (k in 0 until 4) {
                val nr = r + DR[k]; val nc = c + DC[k]
                if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue
                val ns = sid(f, nr * cols + nc)
                if (ns < 0 || fence[ns].toInt() != 0 || blocked[ns].toInt() != 0) continue
                if (accessLevel[ns] > userLevel && ns !in exempt && !freeExit(ns, DR[k], DC[k])) continue
                push(f * nCells + nr * cols + nc)
            }
            edges[state]?.forEach { e -> if (e.conn.kind in q.allow && e.conn.id !in q.avoidConn) push(e.to) }
        }
        return best
    }

    /** Connector used between two consecutive states on different floors. */
    fun edgeConn(a: Int, z: Int): Connector? = edges[a]?.firstOrNull { it.to == z }?.conn

    // ------------------------------------------------------------------ path -> route
    /** Line of sight on the grid (no squeezing diagonally between two walls, closures respected). */
    fun los(fi: Int, r0: Int, c0: Int, r1: Int, c1: Int): Boolean {
        val dr = (r1 - r0).toDouble(); val dc = (c1 - c0).toDouble()
        val n = ceil(max(abs(dr), abs(dc)) * 2).toInt()
        for (i in 1 until n) {
            val t = i.toDouble() / n
            val r = r0 + dr * t; val c = c0 + dc * t
            val ri = round(r).toInt(); val ci = round(c).toInt()
            if (!open(fi, ri * cols + ci)) return false
            val rf = floor(r).toInt(); val cf = floor(c).toInt()
            if (!open(fi, rf * cols + cf) && !open(fi, (rf + 1) * cols + cf + 1)) return false
            if (!open(fi, rf * cols + cf + 1) && !open(fi, (rf + 1) * cols + cf)) return false
        }
        return true
    }

    private fun smoothLeg(fi: Int, cells: IntArray): IntArray {
        if (cells.size <= 2) return cells
        val out = ArrayList<Int>(); out.add(cells[0])
        var i = 0
        while (i < cells.size - 1) {
            var j = i + 1
            val lim = min(cells.size - 1, i + 700)
            val ri = cells[i] / cols; val ci = cells[i] % cols
            var k = i + 2
            while (k <= lim) {
                if (los(fi, ri, ci, cells[k] / cols, cells[k] % cols)) j = k else if (k - j > 25) break
                k++
            }
            out.add(cells[j]); i = j
        }
        return out.toIntArray()
    }

    fun buildRoute(raw: RawPath, label: String): Route {
        val st = raw.states
        val legs = ArrayList<Leg>()
        val steps = ArrayList<ConnStep>()
        var segStart = 0
        var s = 0.0
        var cost = 0.0; var climb = 0.0
        fun flush(endExcl: Int) {
            val f = st[segStart] / nCells
            val cells = IntArray(endExcl - segStart) { st[segStart + it] - f * nCells }
            val sm = smoothLeg(f, cells)
            val xs = DoubleArray(sm.size) { b.xOf(sm[it] % cols) }
            val ys = DoubleArray(sm.size) { b.yOf(sm[it] / cols) }
            val ss = DoubleArray(sm.size)
            ss[0] = s
            for (k in 1 until sm.size) ss[k] = ss[k - 1] + hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
            s = ss.last()
            legs.add(Leg(f, b.floorOf(f), xs, ys, ss))
        }
        for (i in 1 until st.size) {
            val fa = st[i - 1] / nCells; val fz = st[i] / nCells
            if (fa != fz) {
                flush(i)
                val c = edgeConn(st[i - 1], st[i])
                val kind = c?.kind ?: "stair"
                val ea = b.floors[fa].elev; val ez = b.floors[fz].elev
                val cc = Vertical.cost(kind, ea, ez)
                cost += cc; climb += Vertical.length(kind, ea, ez)
                steps.add(ConnStep(c, kind, fa, fz, s, cc, Vertical.length(kind, ea, ez), ez - ea))
                segStart = i
            }
        }
        flush(st.size)
        cost += s / WALK
        // walk = what the user covers on foot: the floor legs plus the stair / escalator flights (along the slope)
        return Route(label, cost, s + climb, legs, steps, st)
    }

    companion object {
        const val WALK = 1.2
        const val WALKED_FACTOR = 0.85
        private val DR = intArrayOf(-1, 1, 0, 0, -1, -1, 1, 1)
        private val DC = intArrayOf(0, 0, -1, 1, -1, 1, -1, 1)
        private val DW = doubleArrayOf(1.0, 1.0, 1.0, 1.0, Math.sqrt(2.0), Math.sqrt(2.0), Math.sqrt(2.0), Math.sqrt(2.0))
    }
}

/** Walking part of a route on one floor; ss = route distance (m) at each vertex. */
class Leg(val fi: Int, val floor: Int, val xs: DoubleArray, val ys: DoubleArray, val ss: DoubleArray) {
    val s0 get() = ss.first()
    val s1 get() = ss.last()
}

/** Floor change between leg k and k+1 at route distance s. */
/** Floor change at route distance s: cost = time (s), climb = walked length on the stairs / escalator (m), rise = height (m, + up). */
class ConnStep(val conn: Connector?, val kind: String, val fromFi: Int, val toFi: Int, val s: Double, val cost: Double,
               val climb: Double = 0.0, val rise: Double = 0.0)

class Proj(val s: Double, val dist: Double, val x: Double, val y: Double, val leg: Int)

class Route(
    var label: String, var cost: Double, val walk: Double, val legs: List<Leg>, val connSteps: List<ConnStep>,
    val states: IntArray,
) {
    val length: Double get() = legs.last().s1
    var maneuvers: List<Maneuver> = emptyList()
    /** СКУД: the route stops in front of this access line (the destination is behind it). */
    var accessStop: AccessLine? = null
    /** The destination has no passage at all: the route leads to the nearest reachable point in front of it. */
    var unreachable = false


    var startTitle = ""
    var goalTitle = ""

    fun legIndexAt(s: Double): Int {
        for (i in legs.indices) if (s <= legs[i].s1 + 1e-6) return i
        return legs.size - 1
    }

    /** Point at route distance s: floor index, x, y. */
    fun pointAt(s: Double): Triple<Int, Double, Double> {
        val li = legIndexAt(s); val L = legs[li]
        val ss = L.ss
        if (s <= ss[0]) return Triple(L.fi, L.xs[0], L.ys[0])
        for (k in 1 until ss.size) if (s <= ss[k]) {
            val t = if (ss[k] > ss[k - 1]) (s - ss[k - 1]) / (ss[k] - ss[k - 1]) else 0.0
            return Triple(L.fi, L.xs[k - 1] + (L.xs[k] - L.xs[k - 1]) * t, L.ys[k - 1] + (L.ys[k] - L.ys[k - 1]) * t)
        }
        return Triple(L.fi, L.xs.last(), L.ys.last())
    }

    /** Direction of travel at s, as map heading (rad, clockwise from north / +y). */
    fun headingAt(s: Double): Double {
        val li = legIndexAt(s); val L = legs[li]
        for (k in 1 until L.ss.size) if (s < L.ss[k] || k == L.ss.size - 1) {
            val dx = L.xs[k] - L.xs[k - 1]; val dy = L.ys[k] - L.ys[k - 1]
            if (hypot(dx, dy) > 1e-6) return atan2(dx, dy)
        }
        return 0.0
    }

    /** Nearest point of the route on floor index fi within [sMin, sMax]. */
    fun project(fi: Int, x: Double, y: Double, sMin: Double = 0.0, sMax: Double = Double.MAX_VALUE): Proj? {
        var best: Proj? = null
        for ((li, L) in legs.withIndex()) {
            if (L.fi != fi || L.s1 < sMin || L.s0 > sMax) continue
            if (L.xs.size == 1) {
                val d = hypot(x - L.xs[0], y - L.ys[0])
                if (best == null || d < best.dist) best = Proj(L.s0, d, L.xs[0], L.ys[0], li)
                continue
            }
            for (k in 1 until L.xs.size) {
                if (L.ss[k] < sMin || L.ss[k - 1] > sMax) continue
                val ax = L.xs[k - 1]; val ay = L.ys[k - 1]; val bx = L.xs[k]; val by = L.ys[k]
                val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
                var t = if (l2 > 0) ((x - ax) * vx + (y - ay) * vy) / l2 else 0.0
                t = t.coerceIn(0.0, 1.0)
                var sp = L.ss[k - 1] + (L.ss[k] - L.ss[k - 1]) * t
                if (sp < sMin || sp > sMax) {
                    sp = sp.coerceIn(sMin, sMax)
                    t = if (L.ss[k] > L.ss[k - 1]) ((sp - L.ss[k - 1]) / (L.ss[k] - L.ss[k - 1])).coerceIn(0.0, 1.0) else 0.0
                }
                val px = ax + vx * t; val py = ay + vy * t
                val d = hypot(x - px, y - py)
                if (best == null || d < best.dist) best = Proj(sp, d, px, py, li)
            }
        }
        return best
    }

    /** Remaining distance on foot (m) from route distance s: the floor legs + stair / escalator flights still ahead. */
    fun distLeft(s: Double): Double = max(0.0, length - s) + connSteps.filter { it.s >= s - 1 }.sumOf { it.climb }

    /** Remaining time (s) from route distance s: walking + connectors still ahead. */
    fun timeLeft(s: Double): Double = max(0.0, length - s) / Router.WALK + connSteps.filter { it.s >= s - 1 }.sumOf { it.cost }
    val time: Double get() = timeLeft(0.0)

    fun connSeq(): String = connSteps.joinToString(",") { (it.conn?.id ?: -1).toString() }
}
