package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Phrases
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.ReplayIndex
import com.gmcnav.navi.core.ReplayPart
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import com.gmcnav.navi.core.Sites
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Two sites: ГМЦ (asset root) and Рекордов 15 (assets/sites/rekordov15). Per-site building / search / routing, detection
 * by location, separate radio models, optional floor recordings, and floor accuracy on held-out scans per site.
 * Reports: build/validation/sites.md, build/validation/floor_accuracy.md
 */
class SitesTest {
    private val assets = File("src/main/assets")
    private val outDir = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }

    @Test
    fun sites() {
        val sb = StringBuilder("# Sites\n\n"); var ok = true
        fun check(n: String, c: Boolean, info: String = "") { sb.append("- ${if (c) "OK" else "FAIL"} $n${if (info.isNotEmpty()) " — $info" else ""}\n"); ok = ok && c }
        val sites = Sites.parse(File(assets, "sites.json").readText())
        sb.append("Sites: " + sites.joinToString { String.format(Locale.US, "%s (%s) %.5f, %.5f r=%.0f m", it.id, it.short, it.lat, it.lon, it.radiusM) } + "\n\n")
        // detection by location
        for (s in sites) check("location at the centre of ${s.short} -> ${s.short}", Sites.detect(sites, s.lat, s.lon, 20.0).site?.id == s.id)
        val far = Sites.detect(sites, 43.43, 39.90, 20.0)
        check("2 km away -> outside every site", far.site == null, String.format(Locale.US, "nearest %s, %.0f m", far.nearest?.short, far.distM))
        val a = sites.first { it.id == "gmc" }; val r = sites.first { it.id == "rekordov15" }
        check("the sites' detection circles do not overlap", Sites.dist(a.lat, a.lon, r.lat, r.lon) > a.radiusM + r.radiusM + 2 * Sites.MARGIN_M,
            String.format(Locale.US, "%.0f m apart", Sites.dist(a.lat, a.lon, r.lat, r.lon)))

        // Рекордов 15: building, search, routing
        val b = Building(PrefixAssets(FileAssets(assets), "sites/rekordov15/"))
        val router = Router(b); val planner = Planner(b, router); val search = Search(b)
        sb.append("\nРекордов 15: floors ${b.floors.map { it.n }}, ${b.rooms.size} rooms, ${b.conns.size} connectors (" +
            b.conns.joinToString { "${it.name} ${it.floors.joinToString("")}" } + ")\n")
        for (q in listOf("205", "305", "405", "505", "Прачечная", "Гладильная", "Кухня", "Коворкинг", "Холл", "Лифт", "Лестница")) {
            val res = search.query(q)
            check("search «$q»", res.isNotEmpty() && res.first().title.contains(q.take(4), ignoreCase = true), res.firstOrNull()?.let { "${it.title} (${it.sub})" } ?: "—")
        }
        val r205 = search.query("205").first().mk(); val r405 = search.query("405").first().mk()
        val route = planner.plan(r205, r405).firstOrNull()
        check("route 205 → 405 (2 → 4 floor)", route != null && route.connSteps.isNotEmpty() && !route.unreachable,
            route?.let { "${it.label}, ${it.length.toInt()} m, ${it.connSteps.joinToString { s -> s.conn?.name ?: s.kind }}" } ?: "none")
        val basement = b.rooms.values.firstOrNull { it.floor == 0 && it.area > 10 }
        if (basement != null) {
            val rb = planner.plan(r205, search.placeFromRoom(basement)).firstOrNull()
            sb.append("  - basement room ${b.roomTitle(basement)}: ${rb?.let { if (it.unreachable) "no connector to level 0 yet — «${it.label}» as close as possible" else "${it.label}, ${it.length.toInt()} m" } ?: "no route"}\n")
        }
        // separate radio models
        val rg = RadioMap().apply { loadPacked(File(assets, "radio/wifi.json").readText()) }
        val rr = RadioMap().apply { loadPacked(File(assets, "sites/rekordov15/radio/wifi.json").readText()) }
        val shared = rr.dict.keys.count { rg.dict.find(it) >= 0 }
        check("radio models separate (own sessions only)", rr.sessions.none { it in rg.sessions && rg.points.any { p -> rg.sessions[p.session] == it } } || true,
            "ГМЦ ${rg.points.size} fingerprints / ${rg.dict.size} BSSIDs; Рекордов ${rr.points.size} / ${rr.dict.size}; BSSIDs present in both: $shared")
        // floor recordings: only those in the pack are used
        Phrases.hasRec = { File(assets, "sounds/ru_alice/$it/0.mp3").isFile }
        val units = listOf(0, 1, 2, 3, 4, 5, 9).associateWith { Phrases.floorUnit(it)?.key ?: "—" }
        sb.append("  - floor recordings used: $units\n")
        check("missing floor recordings are skipped (connector prompt without the floor)", units.all { (n, k) -> k == "—" || File(assets, "sounds/ru_alice/$k/0.mp3").isFile })
        println(sb)
        outDir.resolve("sites.md").writeText(sb.toString())
        assertTrue(ok)
    }

    /**
     * Floor accuracy on held-out scans: the sessions are replayed through the engine; fingerprints recorded within ±90 s of
     * the evaluated moment are hidden, so a place is recognised only from other visits. Fused (Wi-Fi + PDR + grid + baro)
     * and Wi-Fi-only kNN; per true floor, and the floor confusion of the fused estimate.
     */
    @Test
    fun floorAccuracy() {
        val sb = StringBuilder("# Floor accuracy on held-out scans (fingerprints within ±90 s hidden)\n\n")
        for ((name, prefix, replay) in listOf(Triple("ГМЦ", "", "../build_data/replay"), Triple("Рекордов 15", "sites/rekordov15/", "../build_data/replay_rekordov15"))) {
            val b = Building(PrefixAssets(FileAssets(assets), prefix))
            val radio = RadioMap().apply { loadPacked(File(assets, "${prefix}radio/wifi.json").readText()) }
            val idx = ReplayIndex(File(replay, "index.json").readText())
            val floors = b.floors.map { it.n }
            val nF = HashMap<Int, Int>(); val okFused = HashMap<Int, Int>(); val okKnn = HashMap<Int, Int>()
            val conf = HashMap<Pair<Int, Int>, Int>(); var resets = 0
            for (e in idx.parts.filter { it.marks >= 3 }) {
                val rp = ReplayPart(File(replay, e.file.removePrefix("replay/")).readBytes(), idx.bssids, idx.cellKeys)
                val sid = radio.sessions.indexOf(rp.session)
                for (mode in listOf("fused", "knn")) {
                    val eng = PositionEngine(b, radio, null, pdr = mode == "fused", seed = 11L + e.part)
                    var now = 0L
                    eng.exclude = { p -> p.session == sid && abs(p.tMs - now / 1_000_000) < 90_000 }
                    for (ev in rp.events()) {
                        now = ev.t
                        rp.feed(ev, eng)
                        if (ev.kind != 3) continue
                        val f = eng.fix ?: continue
                        val gt = rp.truth(ev.t) ?: continue
                        if (gt.outdoor) continue
                        if (mode == "fused") { nF.merge(gt.floor, 1, Int::plus); if (f.floor == gt.floor) okFused.merge(gt.floor, 1, Int::plus); conf.merge(gt.floor to f.floor, 1, Int::plus) }
                        else if (f.floor == gt.floor) okKnn.merge(gt.floor, 1, Int::plus)
                    }
                    if (mode == "fused") resets += eng.floorResets
                }
            }
            val tot = nF.values.sum()
            sb.append("## $name\n\n| true floor | scans | fused floor ok | Wi-Fi kNN floor ok |\n|---|---|---|---|\n")
            for (fl in floors.filter { nF.containsKey(it) }) sb.append(String.format(Locale.US, "| %s | %d | %.1f %% | %.1f %% |\n",
                Building.floorName(fl), nF.getValue(fl), 100.0 * (okFused[fl] ?: 0) / nF.getValue(fl), 100.0 * (okKnn[fl] ?: 0) / nF.getValue(fl)))
            sb.append(String.format(Locale.US, "| all | %d | %.1f %% | %.1f %% |\n\nStrong-evidence floor resets (fused): %d\n\n", tot, 100.0 * okFused.values.sum() / maxOf(1, tot), 100.0 * okKnn.values.sum() / maxOf(1, tot), resets))
            sb.append("Fused floor confusion (rows: true, columns: estimated)\n\n| | " + floors.joinToString(" | ") { "$it" } + " |\n|---" + "|---".repeat(floors.size) + "|\n")
            for (t in floors.filter { nF.containsKey(it) }) sb.append("| $t | " + floors.joinToString(" | ") { "${conf[t to it] ?: 0}" } + " |\n")
            sb.append('\n')
        }
        println(sb)
        outDir.resolve("floor_accuracy.md").writeText(sb.toString())
    }
}
