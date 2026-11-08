package com.gmcnav.navi

import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.Router
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Рекордов 15, a route up the stairs over several floors (03.11: «на лестнице маршрут как будто перестраивается»).
 * Walk to the stair, climb through the floors in between with the position in the stairwell (Wi-Fi flipping the floor
 * back once), come out on the target floor: guidance must not ask for a reroute and must go on to the target floor's leg.
 */
class StairTransitTest {
    @Test
    fun climbThroughFloors() {
        val site = ScanBudgetReplayTest().r15
        val b = site.b
        val planner = Planner(b, Router(b))
        val rooms = b.rooms.values.filter { r -> r.reach && !r.void && r.nums.any { it.matches(Regex("\\d{3}")) } }.sortedBy { it.nums.first() }
        var tried = 0; var checked = 0
        for (a in rooms.filter { it.floor == 1 }.take(6)) for (z in rooms.filter { it.floor >= 3 }.take(12)) {
            val pa = planner.placeFromFix(a.floor, a.poleX, a.poleY) ?: continue
            val pz = planner.placeFromFix(z.floor, z.poleX, z.poleY) ?: continue
            val route = planner.plan(pa, pz).firstOrNull { r -> r.maneuvers.any { it.type == MType.CONNECTOR && it.connKind == "stair" && kotlin.math.abs(it.toFloor - it.fromFloor) >= 2 } } ?: continue
            tried++
            val cm = route.maneuvers.first { it.type == MType.CONNECTOR && it.connKind == "stair" && kotlin.math.abs(it.toFloor - it.fromFloor) >= 2 }
            val conn = cm.conn ?: continue
            val g = Guidance(b, route)
            var t = 1_000_000_000L; var steps = 0; var asks = 0
            fun fix(floor: Int, x: Double, y: Double) {
                t += 1_000_000_000L; steps += 2
                if (g.update(Fix(t, floor, x, y, 4.0, 0.0, "sim", steps = steps, moving = true)).needReroute) asks++
            }
            // along the route to 2 m before the stair
            var s = 0.0
            while (s < cm.s - 2) { val (fi, x, y) = route.pointAt(s); fix(b.floorOf(fi), x, y); s += 1.2 }
            // the flights: on each floor of the climb, around the stairwell (± 3 m), 8 s per floor
            val up = if (cm.toFloor > cm.fromFloor) 1 else -1
            var fl = cm.fromFloor
            while (true) {
                val p = conn.pos[fl] ?: break
                for (k in 0 until 8) fix(fl, p[0] + (k % 3 - 1) * 1.5, p[1] + (k % 2) * 3.0)
                if (fl == cm.toFloor) break
                if (fl != cm.fromFloor) { val q = conn.pos[fl - up] ?: break; fix(fl - up, q[0], q[1]) }   // Wi-Fi flips back once
                fl += up
            }
            // out on the target floor along the route
            s = cm.s + 0.5
            for (k in 0 until 12) { val (fi, x, y) = route.pointAt(s); fix(b.floorOf(fi), x, y); s += 1.2 }
            println("stair climb ${a.nums.first()}→${z.nums.first()} via ${conn.name} ${cm.fromFloor}→${cm.toFloor}: reroute asks $asks, progress ${"%.1f".format(g.s)} / connector ${"%.1f".format(cm.s)}, transit fixes ${g.transitFixes}")
            assertTrue("reroute asked on the stairs", asks == 0)
            assertTrue("did not continue on the target floor", g.s > cm.s + 3)
            checked++
            if (checked >= 3) return
        }
        assertTrue("no multi-floor stair route found (tried $tried)", checked > 0)
    }
}
