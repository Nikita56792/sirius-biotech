package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.GridPt
import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.RouteQuery
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Map 29.10: guests / basic pass prefer the paths walked during calibration; unreachable destinations lead as close as
 * possible; a rebuilt route that is the same path is recognised; a positioning jump forward along the route is progress.
 * Report: build/validation/route_preference.md
 */
class RoutePreferenceTest {
    private val assets = File("src/main/assets")
    private val b by lazy { Building(FileAssets(assets)) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }
    private val sb = StringBuilder("# Route preference, unreachable targets, same-path reroutes, forward jumps\n\n")
    private var ok = true
    private fun check(name: String, cond: Boolean, info: String = "") { sb.append("- ${if (cond) "OK" else "FAIL"} $name${if (info.isNotEmpty()) " — $info" else ""}\n"); ok = ok && cond }

    private fun walkedFrac(r: Route): Double {
        val n = b.n
        return r.states.count { b.isWalked(it / n, it % n) }.toDouble() / maxOf(1, r.states.size)
    }

    @Test
    fun routePreference() {
        // ---------------------------------------------------------------- walked preference (guest / basic)
        sb.append("## Walked paths preferred for guests / basic pass (cost ×${Router.WALKED_FACTOR} on walked cells)\n\n")
        router.userLevel = 1; router.rebuildAccess()
        val radio = RadioMap().apply { loadPacked(File(assets, "radio/wifi.json").readText()) }
        val fps = radio.points.filter { !it.outdoor && !it.seg && it.floor in 1..2 }
        val rnd = Random(5)
        var pairs = 0; var worse = 0; var longer = 0
        var example: String? = null
        var tries = 0
        while (pairs < 60 && tries < 600) {
            tries++
            val a = fps[rnd.nextInt(fps.size)]; val z = fps[rnd.nextInt(fps.size)]
            if (a.floor != z.floor || hypot(a.x - z.x, a.y - z.y) !in 40.0..200.0) continue
            val pa = planner.placeFromFix(a.floor, a.x, a.y) ?: continue; val pz = planner.placeFromFix(z.floor, z.x, z.y) ?: continue
            val q = RouteQuery(pa.goals, pz.goals, setOf("stair", "lift", "escalator"))
            router.preferWalked = false; val rawOff = router.route(q) ?: continue
            router.preferWalked = true; val rawOn = router.route(q) ?: continue
            val off = router.buildRoute(rawOff, "off"); val on = router.buildRoute(rawOn, "on")
            pairs++
            val fOff = walkedFrac(off); val fOn = walkedFrac(on)
            if (fOn < fOff - 0.02) worse++
            if (on.length > off.length * 1.18 + 2) { longer++
                sb.append(String.format(Locale.US, "  - longer: floor %d (%.0f, %.0f) → (%.0f, %.0f): neutral %.0f m (walk %.0f, %s), preferring walked %.0f m (walk %.0f, %s)\n",
                    a.floor, a.x, a.y, z.x, z.y, off.length, off.walk, off.connSteps.joinToString { it.conn?.name ?: it.kind }, on.length, on.walk,
                    on.connSteps.joinToString { it.conn?.name ?: it.kind })) }
            val differ = abs(on.length - off.length) > 2 || fOn - fOff > 0.05
            if (example == null && differ && on.length <= off.length * 1.15 && fOn - fOff >= 0.2)
                example = String.format(Locale.US, "floor %d (%.0f, %.0f) → (%.0f, %.0f): neutral %.0f m, %.0f %% walked; preferring walked %.0f m, %.0f %% walked",
                    a.floor, a.x, a.y, z.x, z.y, off.length, 100 * fOff, on.length, 100 * fOn)
        }
        router.preferWalked = false
        sb.append("  - $pairs pairs of calibrated places 40–200 m apart\n")
        check("walked share never lower with the preference", worse == 0, "$worse worse")
        check("detour bounded (≤ 18 %)", longer == 0, "$longer longer")
        check("a guest route with a walked and an unwalked alternative of similar length takes the walked one", example != null, example ?: "no such pair found")
        // the planner switches it by the pass level
        val p1 = search.query("К20").first().mk(); val p2 = search.query("К0").first().mk()
        router.userLevel = 1; planner.plan(p1, p2); val on1 = router.preferWalked
        router.userLevel = 0; planner.plan(p1, p2); val on0 = router.preferWalked
        router.userLevel = 2; planner.plan(p1, p2); val on2 = router.preferWalked
        check("preference on for guest and basic, off for расширенный", on0 && on1 && !on2)
        router.userLevel = 1; router.rebuildAccess()

        // ---------------------------------------------------------------- unreachable destinations
        sb.append("\n## Unreachable destinations: as close as possible\n\n")
        val from = search.query("К20").first().mk()
        var tested = 0; var good = 0
        // rooms whose goal cell lies in a small sealed pocket of passable cells (no door / opening in the grid)
        fun sealed(fi: Int, cell: Int): Boolean {
            val seen = HashSet<Int>(); val q = ArrayDeque<Int>(); q.add(cell); seen.add(cell)
            while (q.isNotEmpty()) {
                val c = q.removeFirst(); val r0 = c / b.cols; val c0 = c % b.cols
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nr = r0 + dr; val nc = c0 + dc; val n = nr * b.cols + nc
                    if (!b.inGrid(nr, nc) || n in seen || !b.passable(fi, nr, nc)) continue
                    seen.add(n); q.add(n); if (seen.size > 6000) return false
                }
            }
            return true
        }
        val pockets = b.rooms.values.filter { !it.void && it.area in 3.0..400.0 && it.floor in 1..2 }
            .filter { q -> search.placeFromRoom(q).goals.firstOrNull()?.let { g -> sealed(g.fi, g.cell) } == true }
        sb.append("  - rooms sealed in the grid (no opening): ${pockets.size}\n")
        for (q in pockets.take(8)) {
            val z = search.placeFromRoom(q)
            val r = planner.plan(from, z).firstOrNull() ?: continue
            if (!r.unreachable) continue
            tested++
            val (_, ex, ey) = r.pointAt(r.length)
            val d = hypot(ex - q.poleX, ey - q.poleY)
            if (d < 15) good++
            if (tested <= 4) sb.append(String.format(Locale.US, "  - %s: «%s», %.0f m, ends %.1f m from the room's centre\n", b.roomTitle(q), r.label, r.length, d))
        }
        check("sealed rooms get a route to the nearest reachable point (not «route not found»)", tested > 0 && good == tested, "$good/$tested end within 15 m of the room's centre")
        // the new door into the cabinet of Семёнов / Кручинин (floor 2) makes it reachable
        val cab = b.rooms.values.firstOrNull { it.floor == 2 && it.names.any { n -> n.contains("Семёнов") } }
        if (cab != null) {
            router.userLevel = 2; router.rebuildAccess()
            val r = planner.plan(from, search.placeFromRoom(cab)).first()
            router.userLevel = 1; router.rebuildAccess()
            check("cabinet «Семёнов / Кручинин» reachable through its new door", !r.unreachable && r.accessStop == null, "${r.label}, ${r.length.toInt()} m")
        } else check("cabinet «Семёнов / Кручинин» present", false)

        // ---------------------------------------------------------------- same path vs a different one
        sb.append("\n## Same-path rebuilds are silent\n\n")
        val a = search.query("1289").first { it.floor == 1 }.mk(); val z = search.query("1295").first { it.floor == 1 }.mk()
        val rs = planner.plan(a, z)
        val best = rs.first()
        val (fi, x, y) = best.pointAt(30.0); val h = best.headingAt(30.0)
        val off = planner.placeFromFix(b.floorOf(fi), x + 1.5 * Math.cos(h), y - 1.5 * Math.sin(h))!!
        val again = planner.plan(off, z).first()
        sb.append("  - rooms: rebuilt ${planner.roomSeq(again, 0.0).map { it % 65536 }} vs old from 30 m ${planner.roomSeq(best, 30.0).map { it % 65536 }}; start room ${b.roomIdAt(b.fi(off.floor), off.x, off.y)}\n")
        check("rebuilt from 1.5 m beside the route at 30 m: same path", planner.samePath(again, best, 30.0), "${again.length.toInt()} m vs rest ${(best.length - 30).toInt()} m")
        val alt = rs.drop(1).firstOrNull { it.connSeq() == best.connSeq() }
        if (alt != null) check("the alternative through another corridor is not the same path", !planner.samePath(alt, best, 0.0), "${alt.label}, ${alt.length.toInt()} m")
        else sb.append("  - (no single-floor alternative for this pair)\n")

        // ---------------------------------------------------------------- forward jumps along the route
        sb.append("\n## A positioning jump forward along the route is progress, not a reroute\n\n")
        val long = rs.first().takeIf { it.legs.size == 1 && it.length > 90 } ?: planner.plan(search.query("К0").first().mk(), search.query("Росатом").first { it.floor == 1 }.mk()).first()
        for ((label, jumpTo) in listOf("within the window (+25 m)" to 45.0, "beyond the window (+55 m)" to 75.0)) {
            val g = Guidance(b, long)
            var t = 0L; var steps = 0; var reroutes = 0; var sAt = 0.0
            fun fix(s: Double) { val (f2, fx, fy) = long.pointAt(s); t += 500_000_000L; steps++
                val st = g.update(Fix(t, b.floorOf(f2), fx, fy, 4.0, long.headingAt(s), "sim", steps = steps, stepLen = 0.6, moving = true, floorProb = 0.95))
                if (st.needReroute) reroutes++; sAt = st.s }
            var s = 0.0
            while (s < 20) { fix(s); s += 0.6 }
            s = jumpTo
            repeat(12) { fix(s); s += 0.6 }
            sb.append(String.format(Locale.US, "  - %s: progress %.0f m after the jump to %.0f m, reroutes %d, jumps accepted %d\n", label, sAt, jumpTo, reroutes, g.jumpsAccepted))
            check("jump $label: no reroute, progress follows", reroutes == 0 && sAt >= jumpTo - 2)
        }

        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("route_preference.md").writeText(sb.toString())
        assertTrue(ok)
    }
}
