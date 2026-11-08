package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.ReplayIndex
import com.gmcnav.navi.core.ReplayPart
import com.gmcnav.navi.core.ScanScheduler
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Positioning on an ordinary phone: Wi-Fi scan throttling (4 scans / 2 min) vs the developer-option phone (a scan every ~2 s).
 * The recordings have back-to-back scans; a policy decides when the app would call startScan(), and the request is served
 * by the first recorded scan finishing ≥ 1.5 s later (a scan takes ~2 s). Error is sampled at every recorded scan time, so all
 * policies are measured at the same moments. honest = fingerprints within ±90 s of the moment hidden (as PositioningReplayTest).
 * Policies: all = every scan (unthrottled); burst = back to back under the budget (what a naive app gets: 4 in a row, then
 * a pause); every30 = a request every 30 s; smart = ScanScheduler.
 * Report: build/validation/scan_budget.md
 */
class ScanBudgetReplayTest {
    private val assets = File("src/main/assets")
    private val outDir = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }

    class Site(val name: String, prefix: String, replay: String, val skip: Set<Int> = emptySet()) {
        val b = Building(PrefixAssets(FileAssets(File("src/main/assets")), prefix))
        private val radioText = File("src/main/assets", "${prefix}radio/wifi.json").readText()
        /** Shared, read-only use (coverage); the engines get [radioCopy]. */
        val radio = RadioMap().apply { loadPacked(radioText) }
        private val perThread = ThreadLocal.withInitial { RadioMap().apply { loadPacked(radioText) } }
        /** This thread's own radio map: matching writes scores into the fingerprints. */
        fun radioCopy(): RadioMap = perThread.get()
        val idx = ReplayIndex(File(replay, "index.json").readText())
        val parts = idx.parts.filter { it.marks >= 3 && it.part !in skip }.map { e ->
            ReplayPart(File(replay, e.file.removePrefix("replay/")).readBytes(), idx.bssids, idx.cellKeys)
        }
    }

    class Stat {
        val err = ArrayList<Double>(); var floorOk = 0; val cov = ArrayList<Double>(); var noFix = 0
        var delivered = 0; var recorded = 0; var minutes = 0.0
        val reasons = HashMap<String, Int>()
        fun q(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l.sorted()[((l.size - 1) * p).toInt()]
        fun add(o: Stat) {
            err.addAll(o.err); floorOk += o.floorOk; cov.addAll(o.cov); noFix += o.noFix; delivered += o.delivered; recorded += o.recorded
            minutes += o.minutes; for ((k, v) in o.reasons) reasons.merge(k, v, Int::plus)
        }
        fun row(name: String) = String.format(Locale.US, "| %-34s | %5d | %5.1f | %5.1f | %5.1f %% | %4d | %5.1f | %5.1f | %4.1f | %4.1f %% | %s |",
            name, err.size, q(err, 0.5), q(err, 0.9), 100.0 * floorOk / maxOf(1, err.size), cov.size, q(cov, 0.5), q(cov, 0.9),
            delivered / maxOf(1e-9, minutes), 100.0 * noFix / maxOf(1, err.size + noFix),
            reasons.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key} ${it.value}" })
    }

    val hdr = "| policy | n | median m | p90 m | floor ok | n cov | cov median | cov p90 | scans/min | no fix | requests by reason |\n" +
        "|---|---|---|---|---|---|---|---|---|---|---|\n"

    /** policy: "all" | "burst" | "every30" | "smart". tune: changes the scheduler / engine before the run. */
    fun run(site: Site, policy: String, honest: Boolean = true, perPart: StringBuilder? = null, trace: StringBuilder? = null,
            seeds: List<Long> = listOf(11L), rssiOffset: Float = 0f, onEnd: (PositionEngine) -> Unit = {}, device: DeviceProfile? = null,
            pdr: Boolean = true, tune: (ScanScheduler, PositionEngine) -> Unit = { _, _ -> }): Stat {
        class Job(val st: Stat, val line: String, val trace: String, val eng: PositionEngine)
        val jobs = Par.map(seeds.flatMap { seed -> site.parts.map { seed to it } }) { (seed, rp) ->
            val st = Stat(); val tr = if (trace != null) StringBuilder() else null
            val radio = site.radioCopy()
            val eng = PositionEngine(site.b, radio, null, pdr = pdr, seed = seed + rp.part)
            val sid = radio.sessions.indexOf(rp.session)
            var now = 0L
            if (honest) eng.exclude = { p -> p.session == sid && abs(p.tMs - now / 1_000_000) < 90_000 }
            val sch = ScanScheduler(throttled = policy != "all")
            tune(sch, eng)
            var reqT = Long.MIN_VALUE; var lastCheck = Long.MIN_VALUE / 2
            val dev = device?.Feeder(rp, seed * 31 + rp.part)
            for (ev in rp.events()) {
                now = ev.t
                if (ev.kind != 3) {
                    if (dev != null) dev.feed(ev, eng) else rp.feed(ev, eng)
                    if (policy == "all" || now - lastCheck < 250_000_000L) continue
                    lastCheck = now
                    if (reqT != Long.MIN_VALUE) {                     // a request in flight: served by a scan, or lost after 6 s
                        if (now - reqT > ScanScheduler.PENDING_NS) reqT = Long.MIN_VALUE else continue
                    }
                    val want = when (policy) {
                        "burst" -> sch.slots(now) > 0
                        "every30" -> sch.slots(now) > 0 && (now - sch.lastResultNs > 30_000_000_000L || eng.fix == null && now - sch.lastReqNs > 4_000_000_000L)
                        else -> sch.want(now, eng)
                    }
                    if (want) { sch.requested(now, true); reqT = now; st.reasons.merge(if (policy == "smart") sch.reason else policy, 1, Int::plus) }
                    continue
                }
                st.recorded++
                val devScan = dev?.scan(ev)
                val deliver = (dev == null || devScan != null) && (policy == "all" || (reqT != Long.MIN_VALUE && ev.t - reqT >= 1_500_000_000L))
                if (deliver) {
                    // another phone model: RSSI shifted, APs below its sensitivity (−92 dBm) not seen
                    if (devScan != null) eng.onWifi(ev.t, devScan)
                    else if (rssiOffset == 0f) rp.feed(ev, eng)
                    else eng.onWifi(ev.t, rp.scans[ev.i].mapValues { it.value + rssiOffset }.filterValues { it >= -92f })
                    st.delivered++; reqT = Long.MIN_VALUE
                    sch.result(now)
                }
                val gt = rp.truth(ev.t) ?: continue
                if (gt.outdoor) continue
                val f = eng.fix
                if (f == null) { st.noFix++; continue }
                val e = hypot(f.x - gt.x, f.y - gt.y)
                st.err.add(e); if (f.floor == gt.floor) st.floorOk++
                val covered = site.radio.points.any { p -> !p.outdoor && p.floor == gt.floor && hypot(p.x - gt.x, p.y - gt.y) < 5.0 &&
                    !(honest && p.session == sid && abs(p.tMs - now / 1_000_000) < 90_000) }
                if (covered) st.cov.add(e)
                tr?.append(String.format(Locale.US, "%d,%.1f,%d,%.1f,%.1f,%d,%.1f,%.1f,%.1f,%.1f,%d,%d,%.1f\n", rp.part, (ev.t - rp.t0) / 1e9,
                    gt.floor, gt.x, gt.y, f.floor, f.x, f.y, e, f.acc, if (deliver) 1 else 0, eng.stepCount, Math.toDegrees(eng.pf.estimate().off)))
            }
            st.minutes += (rp.t1 - rp.t0) / 60e9
            val line = String.format(Locale.US, "part %2d: n %4d median %5.1f p90 %5.1f floor %5.1f %% reseeds %d floorResets %d gated %d\n", rp.part,
                st.err.size, st.q(st.err, 0.5), st.q(st.err, 0.9), 100.0 * st.floorOk / maxOf(1, st.err.size), eng.reseeds, eng.floorResets, eng.gated)
            Job(st, line, tr?.toString() ?: "", eng)
        }
        val st = Stat()
        for (j in jobs) { st.add(j.st); perPart?.append(j.line); trace?.append(j.trace); onEnd(j.eng) }
        return st
    }

    // one copy per JVM (JUnit makes a test instance per method; the sites hold the replays and per-thread radio maps)
    val gmc get() = gmcSite
    val r15 get() = r15Site
    companion object {
        val gmcSite by lazy { Site("ГМЦ", "", "../build_data/replay", skip = setOf(13)) }
        val r15Site by lazy { Site("Рекордов 15", "sites/rekordov15/", "../build_data/replay_rekordov15") }
    }

    @Test
    fun probe() {
        val which = System.getenv("GMC_PROBE") ?: return
        val site = if (which.startsWith("r")) r15 else gmc
        val honest = !which.contains("full")
        for (p in listOf("all", "every30")) {
            val pp = StringBuilder(); val tr = StringBuilder("part,t,gf,gx,gy,f,x,y,err,acc,deliv,steps,off\n")
            val s = run(site, p, honest = honest, perPart = pp, trace = tr)
            println("== ${site.name} $p\n" + s.row(p) + "\n" + pp)
            outDir.resolve("probe_${which}_$p.csv").writeText(tr.toString())
        }
    }

    /** Robustness to the phone model: the radio map is from one phone; others read RSSI several dB higher or lower. */
    @Test
    fun deviceOffset() {
        if (System.getenv("GMC_PROBE") != null || System.getenv("GMC_FULL") == null) return   // ~4 min: GMC_FULL=1
        val sb = StringBuilder("# Another phone model: all scan RSSI shifted (APs below −92 dBm dropped), unthrottled and 4 scans / 2 min\n\n")
        for (site in listOf(gmc, r15)) for (honest in listOf(false, true)) {
            sb.append("## ${site.name}, ${if (honest) "honest" else "full map"}\n\n").append(hdr)
            for (off in listOf(0f, -8f, 8f)) for (p in listOf("all", "smart")) {
                val s = run(site, p, honest, seeds = listOf(11L, 23L), rssiOffset = off)
                sb.append(s.row(String.format(Locale.US, "%s, %+.0f dB", p, off))).append('\n')
            }
            sb.append('\n')
        }
        println(sb)
        outDir.resolve("device_offset.md").writeText(sb.toString())
    }

    /** Different phones emulated on the recordings (DeviceProfile); DEVICES=1 (~10 min). Report: device_profiles.md */
    @Test
    fun deviceProfiles() {
        if (System.getenv("DEVICES") == null) return
        val profiles = listOf(
            DeviceProfile("S24 Ultra как записано"),
            DeviceProfile("акселерометр 50 Гц + шум 0.15", accHz = 50.0, accNoise = 0.15),
            DeviceProfile("акселерометр 25 Гц", accHz = 25.0),
            DeviceProfile("дешёвый гироскоп: дрейф 4°/мин, 15 Гц, шум 2°", gameHz = 15.0, gyroDriftDegMin = 4.0, gameYawNoiseDeg = 2.0),
            DeviceProfile("компас сбит на 40°", compassBiasDeg = 40.0),
            DeviceProfile("телефон боком (90° к ходьбе)", handDeg = 90.0),
            DeviceProfile("телефон под 30° к ходьбе", handDeg = 30.0),
            DeviceProfile("без гироскопа: курс по компасу (шум 10°, сдвиг 15°)", noGyro = true, compassNoiseDeg = 10.0, compassBiasDeg = 15.0),
            DeviceProfile("без барометра", noBaro = true),
            DeviceProfile("скан Wi-Fi 4 с, −6 дБ", scanEvery = 2, rssi = -6f),
            DeviceProfile("бюджетный: всё сразу", accHz = 50.0, accNoise = 0.15, gameHz = 15.0, gyroDriftDegMin = 4.0, gameYawNoiseDeg = 2.0,
                compassBiasDeg = 20.0, noBaro = true, scanEvery = 2, rssi = -6f),
        )
        val only = System.getenv("DEVICES")!!.split(",").mapNotNull { it.toIntOrNull() }.toSet()
        val sb = StringBuilder("# Разные телефоны, эмуляция на записях S24 Ultra\n\nКаждая строка: все части × 2 сида фильтра; «полная карта».\n\n")
        for (site in listOf(gmc, r15)) {
            sb.append("## ${site.name}\n\n").append(hdr)
            for ((k, d) in profiles.withIndex()) {
                if (only.isNotEmpty() && k !in only) continue
                val variants = listOf<Pair<String, (PositionEngine) -> Unit>>("" to { _ -> })
                for (p in listOf("all", "smart")) for ((vn, vf) in variants) {
                    val s = run(site, p, honest = false, seeds = listOf(11L, 23L), device = d, tune = { _, e -> vf(e) })
                    sb.append(s.row("$k ${d.name.take(22)} · $p$vn")).append('\n')
                }
            }
            sb.append('\n')
        }
        println(sb)
        outDir.resolve("device_profiles.md").writeText(sb.toString())
    }

    @Test
    fun scanBudget() {
        if (System.getenv("GMC_PROBE") != null) return
        val sb = StringBuilder("# Wi-Fi scan budget: developer-option phone vs ordinary phone (4 scans / 2 min)\n\n")
        // the full report (GMC_FULL=1, ~6 min): 3 filter seeds + scheduler variants; the default suite: 1 seed
        val full = System.getenv("GMC_FULL") != null
        val seeds = if (full) listOf(11L, 23L, 37L) else listOf(11L)
        sb.append("Each row: all parts × ${seeds.size} PF seed(s).\n\n")
        val got = HashMap<String, Stat>()
        for (site in listOf(gmc, r15)) for (honest in listOf(true, false)) {
            sb.append("## ${site.name}, ${if (honest) "honest" else "full map"}\n\n").append(hdr)
            fun row(name: String, p: String, tune: (ScanScheduler, PositionEngine) -> Unit = { _, _ -> }) {
                val s = run(site, p, honest, seeds = seeds, tune = tune); sb.append(s.row(name)).append('\n'); println(s.row(name))
                got["${site.name}|$honest|$name"] = s
            }
            for (p in listOf("all", "burst", "every30", "smart")) row(p, p)
            if (full) {
                row("smart period 40", "smart") { s, _ -> s.PERIOD_S = 40.0 }
                row("smart walk reserve 1", "smart") { s, _ -> s.WALK_RESERVE = 1 }
                row("smart no spread", "smart") { s, _ -> s.LOST_ACC = 1e9 }
                row("smart no settle", "smart") { s, _ -> s.SETTLE_S = 0.0 }
                row("smart no floor", "smart") { s, _ -> s.CONN_R = -1.0 }
            }
            sb.append('\n')
        }
        println(sb)
        outDir.resolve("scan_budget.md").writeText(sb.toString())
        // regressions (full map ≈ walking calibrated corridors) of the 29.10 engine, restored 03.11.2026:
        // unthrottled (developer option) 3.0 / 6.1 and 2.4 / 5.6 m; 4 scans / 2 min 6.4 / 32.7 and 3.2 / 25.0 m (1 seed)
        fun med(k: String) = got.getValue(k).let { it.q(it.err, 0.5) }
        fun p90(k: String) = got.getValue(k).let { it.q(it.err, 0.9) }
        assertTrue(med("ГМЦ|false|all") < 3.6 && p90("ГМЦ|false|all") < 8.0)
        assertTrue(med("ГМЦ|false|smart") < 8.0 && p90("ГМЦ|false|smart") < 40.0)
        assertTrue(med("Рекордов 15|false|all") < 3.0 && p90("Рекордов 15|false|all") < 8.0)
        assertTrue(med("Рекордов 15|false|smart") < 4.5 && p90("Рекордов 15|false|smart") < 32.0)
    }
}
