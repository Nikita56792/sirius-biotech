package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.ReplayIndex
import com.gmcnav.navi.core.ReplayPart
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Debug trace (only with GMC_TRACE): basement scans of Рекордов 15 through the fused engine, held out. */
class BasementTraceTest {
    @Test
    fun trace() {
        if (System.getProperty("gmc.trace") == null) return
        val assets = File("src/main/assets"); val replay = "../build_data/replay_rekordov15"
        val b = Building(PrefixAssets(FileAssets(assets), "sites/rekordov15/"))
        val radio = RadioMap().apply { loadPacked(File(assets, "sites/rekordov15/radio/wifi.json").readText()) }
        val idx = ReplayIndex(File(replay, "index.json").readText())
        val sb = StringBuilder()
        for (e in idx.parts.filter { 0 in it.floors }) {
            val rp = ReplayPart(File(replay, e.file.removePrefix("replay/")).readBytes(), idx.bssids, idx.cellKeys)
            val sid = radio.sessions.indexOf(rp.session)
            val eng = PositionEngine(b, radio, null, seed = 11L + e.part)
            var now = 0L
            eng.exclude = { p -> p.session == sid && abs(p.tMs - now / 1_000_000) < 90_000 }
            for (ev in rp.events()) {
                now = ev.t; rp.feed(ev, eng)
                if (ev.kind != 3) continue
                val f = eng.fix ?: continue; val gt = rp.truth(ev.t) ?: continue
                if (gt.floor != 0 && f.floor != 0) continue
                val m = eng.lastMatch ?: continue
                val votes = HashMap<Int, Double>(); for (p in m.ranked.take(10)) votes.merge(p.floor, p.raw.toDouble().pow(4), Double::plus)
                val tot = votes.values.sum().coerceAtLeast(1e-12)
                sb.append(String.format(Locale.US, "part %d t=%.0f truth %d fused %d (p=%.2f) | nAp %d top %.2f (fl %d) votes %s | resets %d\n", e.part, (ev.t - rp.t0) / 1e9,
                    gt.floor, f.floor, f.floorProb, m.nAp, m.ranked.firstOrNull()?.raw ?: 0f, m.ranked.firstOrNull()?.floor ?: -1,
                    votes.entries.sortedBy { it.key }.joinToString(" ") { "%d:%.2f".format(Locale.US, it.key, it.value / tot) }, eng.floorResets))
            }
        }
        File(System.getProperty("gmc.out") ?: "build/validation").resolve("basement_trace.txt").writeText(sb.toString())
    }
}
