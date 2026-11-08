package com.gmcnav.navi

import com.gmcnav.navi.core.PositionEngine
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.hypot

/**
 * Floor changes on the recordings: for every recorded transition (consecutive marks on different floors) — how long after
 * the first mark on the new floor the displayed floor follows, and the share of the 90 s after it shown on a wrong floor.
 * Scan cadence: as recorded (~2 s) and every 2nd scan (~4 s, the navigator's live cadence). Report: floor_transitions.md
 */
class FloorTransitionTest {
    class Tr(val part: Int, val from: Int, val to: Int, val tNew: Long, var lag: Double = Double.NaN, var wrong: Double = 0.0)

    fun measure(site: ScanBudgetReplayTest.Site, every: Int, seed: Long, tune: (PositionEngine) -> Unit = {}): List<Tr> =
        Par.map(site.parts) { rp ->
            val trs = (1 until rp.markT.size).filter { rp.markFloor[it] != rp.markFloor[it - 1] }
                .map { Tr(rp.part, rp.markFloor[it - 1], rp.markFloor[it], rp.markT[it]) }
            if (trs.isEmpty()) return@map emptyList<Tr>()
            val eng = PositionEngine(site.b, site.radioCopy(), null, seed = seed + rp.part); tune(eng)
            val dev = DeviceProfile("cad", scanEvery = every).Feeder(rp, seed)
            val shown = ArrayList<Pair<Long, Int>>()
            for (ev in rp.events()) {
                if (ev.kind == 3) { dev.scan(ev)?.let { eng.onWifi(ev.t, it) } } else dev.feed(ev, eng)
                eng.fix?.let { if (shown.isEmpty() || shown.last().second != it.floor || ev.t - shown.last().first > 500_000_000L) shown.add(ev.t to it.floor) }
            }
            for (tr in trs) {
                val after = shown.filter { it.first >= tr.tNew - 60_000_000_000L }
                // first moment (from 60 s before the mark) after which the shown floor is the new one for ≥ 10 s
                var lag = Double.NaN
                for ((i, s) in after.withIndex()) {
                    if (s.second != tr.to) continue
                    val stay = after.drop(i).takeWhile { it.first - s.first < 10_000_000_000L }
                    if (stay.all { it.second == tr.to }) { lag = (s.first - tr.tNew) / 1e9; break }
                }
                tr.lag = lag
                val win = shown.filter { it.first in tr.tNew..(tr.tNew + 90_000_000_000L) }
                // the walker may change floors again inside the window: count only until the next transition
                val next = trs.firstOrNull { it.tNew > tr.tNew }?.tNew ?: Long.MAX_VALUE
                val w2 = win.filter { it.first < next }
                tr.wrong = if (w2.isEmpty()) 0.0 else w2.count { it.second != tr.to }.toDouble() / w2.size
            }
            trs
        }.flatten()

    @Test
    fun transitions() {
        val t = ScanBudgetReplayTest()
        val sb = StringBuilder("# Смена этажа на записях (полная карта)\n\n")
        for (site in listOf(t.gmc, t.r15)) for (every in listOf(1, 2)) {
            val all = listOf(11L, 23L).flatMap { measure(site, every, it) }
            val lags = all.map { if (it.lag.isNaN()) 999.0 else it.lag }.sorted()
            sb.append(String.format(Locale.US, "## %s, скан %s\n\nпереходов %d; задержка медиана %.0f с, p75 %.0f с, не сменился за 90 с: %d; доля времени на чужом этаже за 90 с после: %.0f %%\n\n",
                site.name, if (every == 1) "как записано (~2 с)" else "через один (~4 с)", all.size, lags[lags.size / 2], lags[lags.size * 3 / 4],
                all.count { it.lag.isNaN() || it.lag > 90 }, 100 * all.map { it.wrong }.average()))
            for (tr in all.take(all.size / 2)) sb.append(String.format(Locale.US, "- часть %d: %d→%d: %s, чужой этаж %.0f %%\n", tr.part, tr.from, tr.to,
                if (tr.lag.isNaN()) "не сменился" else String.format(Locale.US, "%+.0f с", tr.lag), 100 * tr.wrong))
            sb.append('\n')
        }
        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("floor_transitions.md").writeText(sb.toString())
    }
}
