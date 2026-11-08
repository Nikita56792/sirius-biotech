package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.PlanGate
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Route panel closing (MainActivity.plan / clearPlanState): a plan result that arrives after the panel was closed, or after
 * a newer plan started, must never reach the map; closing cancels the running search (router.gen).
 */
class PlanGateTest {
    @Test
    fun tokens() {
        var cancels = 0
        val gate = PlanGate { cancels++ }
        val t1 = gate.begin()
        assertTrue(gate.current(t1))
        gate.close()
        assertFalse("closed panel: old result is stale", gate.current(t1))
        val t2 = gate.begin()
        assertTrue(gate.current(t2)); assertFalse(gate.current(t1))
        val t3 = gate.begin()
        assertFalse("newer plan: older result is stale", gate.current(t2)); assertTrue(gate.current(t3))
        gate.close(); gate.close()
        assertFalse(gate.current(t3))
        assertEquals("every begin / close cancels the search in flight", 6, cancels)
    }

    /** As in the activity: plan on a worker thread, result posted to the "main" queue and applied only if still current. */
    @Test
    fun lateResultsNeverReachTheMap() {
        val b = Building(FileAssets(File("src/main/assets")))
        val router = Router(b); val planner = Planner(b, router); val search = Search(b)
        val gate = PlanGate { router.gen++ }
        router.userLevel = 0; router.rebuildAccess()           // guest: «Зал 1» is behind СКУД (the slow case before)
        val from = search.query("К20").first().mk()
        val hall = search.query("Зал 1").first().mk()
        val room = search.query("2001").first().mk()
        val mainQ = LinkedBlockingQueue<() -> Unit>()
        var shown: List<Route> = emptyList()                   // map.routes
        fun planAsync(z: com.gmcnav.navi.core.Place): Thread {
            val tok = gate.begin()
            return thread { val rs = runCatching { planner.plan(from, z) }.getOrDefault(emptyList()); mainQ.put { if (gate.current(tok)) shown = rs } }
        }
        val sb = StringBuilder()

        // 1. the user closes the panel right after «Сюда»: the result is dropped, the worker ends
        var t0 = System.nanoTime()
        val th1 = planAsync(hall)
        gate.close(); shown = emptyList()
        th1.join(60_000)
        assertFalse("plan thread finished", th1.isAlive)
        mainQ.poll(5, TimeUnit.SECONDS)!!.invoke()
        assertTrue("closed panel shows no routes", shown.isEmpty())
        sb.append("closed right away: worker done in ${(System.nanoTime() - t0) / 1_000_000} ms, result dropped\n")

        // 2. a newer target while the first plan is computing: only the newer result is applied
        t0 = System.nanoTime()
        val th2 = planAsync(hall)
        val th3 = planAsync(room)
        th2.join(60_000); th3.join(60_000)
        repeat(2) { mainQ.poll(5, TimeUnit.SECONDS)!!.invoke() }
        assertTrue("newest plan applied", shown.isNotEmpty())
        assertTrue("it is the route to 2001", shown.first().legs.last().floor == room.floor)
        sb.append("superseded plan: newest applied (${shown.first().label}, ${shown.first().length.toInt()} m) in ${(System.nanoTime() - t0) / 1_000_000} ms\n")

        // 3. closing after a result was shown: a late duplicate does not bring the routes back
        val th4 = planAsync(room)
        th4.join(60_000)
        gate.close(); shown = emptyList()
        mainQ.poll(5, TimeUnit.SECONDS)!!.invoke()
        assertTrue("routes stay cleared after close", shown.isEmpty())
        sb.append("result arriving after close: dropped\n")
        println(sb)
        File("build/validation").also { it.mkdirs() }.resolve("plan_gate.md").writeText(sb.toString())
    }
}
