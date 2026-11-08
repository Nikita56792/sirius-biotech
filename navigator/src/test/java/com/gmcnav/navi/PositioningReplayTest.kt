package com.gmcnav.navi

import com.gmcnav.navi.core.BaroDetector
import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.CellMap
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.ReplayIndex
import com.gmcnav.navi.core.ReplayPart
import com.gmcnav.navi.core.StepDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Offline replay of calibration session 20261027_111902 through the positioning engine.
 * Ground truth = the user's marks, linearly interpolated in time (only between marks ≤ 30 s apart on the same floor;
 * marks outside the building excluded). Error of the displayed position, measured after every Wi-Fi scan.
 *
 * honest: fingerprints recorded within ±90 s of the evaluated moment are hidden from the radio map, so a place is only
 * recognised from other visits (the full map contains the very scans being evaluated and is optimistic).
 * cov: moments where the visible map still has a fingerprint ≤ 5 m from the true position.
 * Jitter: displayed-position sd within standing periods (no step for ≥ 2 s around, ≥ 6 s long), jumps > 5 m between
 * consecutive displayed fixes per minute.
 */
class PositioningReplayTest {
    private val assets = File("src/main/assets")
    private val replayDir = File(System.getProperty("gmc.replay") ?: "../build_data/replay")
    private val outDir = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }
    private val building by lazy { Building(FileAssets(assets)) }
    private val idx by lazy { ReplayIndex(File(replayDir, "index.json").readText()) }
    private val parts by lazy { idx.parts.associate { it.part to ReplayPart(File(replayDir, it.file.removePrefix("replay/")).readBytes(), idx.bssids, idx.cellKeys) } }

    class Stat(val name: String) {
        val err = ArrayList<Double>(); var floorOk = 0
        val cov = ArrayList<Double>(); var covFloorOk = 0
        val standSd = ArrayList<Double>(); var jumps = 0; var minutes = 0.0
        fun q(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l.sorted()[((l.size - 1) * p).toInt()]
        fun row() = String.format(Locale.US, "| %-46s | %5d | %5.1f | %5.1f | %5.1f %% | %4d | %5.1f | %5.1f | %5.1f %% | %4.2f | %4.2f |",
            name, err.size, q(err, 0.5), q(err, 0.9), 100.0 * floorOk / maxOf(1, err.size), cov.size, q(cov, 0.5), q(cov, 0.9),
            100.0 * covFloorOk / maxOf(1, cov.size), q(standSd, 0.5), jumps / maxOf(1e-9, minutes))
    }

    private val hdr = "| config | n | median m | p90 m | floor ok | n cov | cov median | cov p90 | cov floor ok | standing sd m | jumps>5m /min |\n" +
        "|---|---|---|---|---|---|---|---|---|---|---|\n"

    /** mode: "knn" (Wi-Fi only), "pdr" (PDR only from the first mark), "fused" (Wi-Fi + PDR + grid + baro), "fused-nogrid". */
    private fun run(sel: List<Int>, name: String, mode: String, honest: Boolean, cells: Boolean = false, perPart: StringBuilder? = null,
                    trace: StringBuilder? = null, walk: StringBuilder? = null): Stat {
        val radio = RadioMap().apply { loadPacked(File(assets, "radio/wifi.json").readText()) }
        val cm = if (cells) CellMap().apply { loadPacked(File(assets, "radio/cell.json").readText()) } else null
        val st = Stat(name)
        for (pn in sel) {
            val rp = parts.getValue(pn)
            val eng = PositionEngine(building, radio, cm, pdr = mode != "knn", useGrid = mode != "fused-nogrid", seed = 11L + pn,
                useRadio = mode != "pdr")
            val sid = radio.sessions.indexOf(rp.session)
            var now = 0L
            if (honest) {
                eng.exclude = { p -> p.session == sid && abs(p.tMs - now / 1_000_000) < 90_000 }
                eng.excludeCell = { p -> abs(p.tMs - now / 1_000_000) < 90_000 }
            }
            // step times for standing periods (independent of the config)
            val sd = StepDetector(); val stepT = ArrayList<Long>()
            for (i in rp.accT.indices) { val s = sd.add(rp.accT[i], rp.acc[i * 3], rp.acc[i * 3 + 1], rp.acc[i * 3 + 2]); if (s > 0) stepT.add(s) }
            fun standingAt(t: Long): Boolean {
                val i = java.util.Collections.binarySearch(stepT, t).let { if (it < 0) -it - 1 else it }
                val prev = if (i > 0) stepT[i - 1] else Long.MIN_VALUE / 2; val next = if (i < stepT.size) stepT[i] else Long.MAX_VALUE / 2
                return t - prev > 2_000_000_000L && next - t > 2_000_000_000L
            }
            val ps = Stat("part $pn")
            var started = mode != "pdr"
            var lastX = Double.NaN; var lastY = Double.NaN; var lastF = 0; var lastFixT = -1L
            var standBuf = ArrayList<DoubleArray>(); var standStart = 0L
            fun closeStand(t: Long) {
                if (standBuf.size >= 3 && t - standStart >= 6_000_000_000L) {
                    val mx = standBuf.map { it[0] }.average(); val my = standBuf.map { it[1] }.average()
                    val v = sqrt(standBuf.map { (it[0] - mx).pow(2) + (it[1] - my).pow(2) }.average())
                    st.standSd.add(v); ps.standSd.add(v)
                }
                standBuf = ArrayList()
            }
            for (ev in rp.events()) {
                now = ev.t
                if (!started && rp.markT.isNotEmpty() && ev.t >= rp.markT[0]) {
                    eng.startAt(rp.markFloor[0], rp.markX[0], rp.markY[0], 1.0); started = true
                }
                rp.feed(ev, eng)
                if (!started) continue
                val f = eng.fix ?: continue
                if (f.tNs != lastFixT) {   // a new displayed fix (after a step or a scan)
                    lastFixT = f.tNs
                    if (!lastX.isNaN() && lastF == f.floor && hypot(f.x - lastX, f.y - lastY) > 5.0) { st.jumps++; ps.jumps++ }
                    lastX = f.x; lastY = f.y; lastF = f.floor
                    if (standingAt(f.tNs)) { if (standBuf.isEmpty()) standStart = f.tNs; standBuf.add(doubleArrayOf(f.x, f.y)) } else closeStand(f.tNs)
                }
                if (ev.kind != 3) continue
                val gt = rp.truth(ev.t) ?: continue
                if (gt.outdoor) continue
                val err = hypot(f.x - gt.x, f.y - gt.y)
                st.err.add(err); ps.err.add(err); if (f.floor == gt.floor) { st.floorOk++; ps.floorOk++ }
                val covered = radio.points.any { p -> !p.outdoor && p.floor == gt.floor && hypot(p.x - gt.x, p.y - gt.y) < 5.0 &&
                    !(honest && p.session == sid && abs(p.tMs - now / 1_000_000) < 90_000) }
                if (covered) { st.cov.add(err); ps.cov.add(err); if (f.floor == gt.floor) { st.covFloorOk++; ps.covFloorOk++ } }
                trace?.append(String.format(Locale.US, "%d,%.1f,%d,%.1f,%.1f,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%d,%.2f,%d,%.2f,%.1f,%.1f,%d,%.2f\n",
                    pn, (ev.t - rp.t0) / 1e9, gt.floor, gt.x, gt.y, f.floor, f.x, f.y, f.rawX, f.rawY, err, if (covered) 1 else 0,
                    eng.lastMatch?.ranked?.firstOrNull()?.raw ?: 0f, eng.lastScanAps, eng.pf.lastLikelihoodRatio, f.wifiX, f.wifiY, eng.stepCount, f.stepLen))
            }
            closeStand(Long.MAX_VALUE)
            val mins = (rp.t1 - rp.t0) / 60e9
            st.minutes += mins; ps.minutes += mins
            perPart?.append(ps.row())?.append(String.format(Locale.US, " %d marks, %d s, steps %d, re-seeds %d, gated %d, collapsed %d\n",
                rp.markT.size, ((rp.t1 - rp.t0) / 1e9).toInt(), eng.stepCount, eng.reseeds, eng.gated, eng.collapsed))
            if (walk != null) {
                var mapD = 0.0
                for (i in 1 until rp.markT.size) if (rp.markFloor[i] == rp.markFloor[i - 1] && !rp.markOut[i] && !rp.markOut[i - 1])
                    mapD += hypot(rp.markX[i] - rp.markX[i - 1], rp.markY[i] - rp.markY[i - 1])
                val stepsInMarks = stepT.count { rp.markT.isNotEmpty() && it >= rp.markT.first() && it <= rp.markT.last() }
                val sl = eng.fix?.stepLen ?: PositionEngine.STEP_LEN
                walk.append(String.format(Locale.US, "| %2d | %5d | %7.0f | %7.0f | %7.0f | %4.2f | %5.2f | %5.2f |\n", pn, stepsInMarks, mapD,
                    stepsInMarks * PositionEngine.STEP_LEN, stepsInMarks * sl, sl, stepsInMarks * PositionEngine.STEP_LEN / maxOf(1.0, mapD),
                    stepsInMarks * sl / maxOf(1.0, mapD)))
            }
        }
        return st
    }

    @Test
    fun replaySession() {
        val normal = idx.parts.filter { it.part != 13 && it.marks >= 3 }.map { it.part }
        val sb = StringBuilder()
        sb.append("# Positioning replay, session ${idx.parts.first().session}\n\n")
        sb.append("Ground truth: marks interpolated in time (gap ≤ 30 s, same floor, indoor only). Error of the displayed position after every Wi-Fi scan.\n")
        sb.append("honest = fingerprints recorded within ±90 s of the moment are hidden (place recognised only from other visits); full map = optimistic.\n")
        sb.append("cov = moments where the visible map has a fingerprint ≤ 5 m from the true position. PDR only = no radio, started at the first mark.\n")
        sb.append("standing sd = displayed-position sd inside standing periods (no step ±2 s, ≥ 6 s); jumps = displayed moves > 5 m between consecutive fixes.\n\n")
        sb.append("## Parts 1–12, 14 (part 13 separately)\n\n").append(hdr)
        val perPart = StringBuilder(); val perPartKnn = StringBuilder(); val walk = StringBuilder()
        val trace = StringBuilder("part,t,gf,gx,gy,f,x,y,rx,ry,err,cov,raw,naps,lr,wx,wy,steps,steplen\n")
        val rows = Par.map(listOf<() -> Stat>(
            { run(normal, "Wi-Fi only (kNN), honest", "knn", true, perPart = perPartKnn) },
            { run(normal, "PDR only from the first mark (no radio)", "pdr", false) },
            { run(normal, "Fused Wi-Fi + PDR + grid + baro, honest", "fused", true, perPart = perPart, trace = trace, walk = walk) },
            { run(normal, "Fused, no wall constraint, honest", "fused-nogrid", true) },
            { run(normal, "Wi-Fi only (kNN), full map", "knn", false) },
            { run(normal, "Fused Wi-Fi + PDR + grid + baro, full map", "fused", false) },
        )) { it() }
        rows.forEach { sb.append(it.row()).append('\n') }
        sb.append("\n### Per part: fused, honest\n\n").append(hdr).append(perPart)
        sb.append("\n### Per part: Wi-Fi only, honest\n\n").append(hdr).append(perPartKnn)
        sb.append("\n## Part 13 (outdoors → parking/loading area, 1–9 APs per scan → stairs 1348/1349 to floor 2)\n\n").append(hdr)
        Par.map(listOf<() -> Stat>(
            { run(listOf(13), "Wi-Fi only (kNN), honest", "knn", true) },
            { run(listOf(13), "PDR only from the first mark", "pdr", false) },
            { run(listOf(13), "Fused, honest", "fused", true) },
            { run(listOf(13), "Wi-Fi only (kNN), full map", "knn", false) },
            { run(listOf(13), "Fused, full map", "fused", false) },
        )) { it() }.forEach { sb.append(it.row()).append('\n') }
        val cellParts = idx.parts.filter { it.cells > 0 && it.marks >= 3 }.map { it.part }
        sb.append("\n## Cell-mode parts $cellParts (airplane off; cell fingerprints recorded in these parts)\n\n").append(hdr)
        Par.map(listOf<() -> Stat>(
            { run(cellParts, "Fused, honest, no cells", "fused", true) },
            { run(cellParts, "Fused + cells, honest (cells ±90 s hidden too)", "fused", true, cells = true) },
            { run(cellParts, "Fused, full map, no cells", "fused", false) },
            { run(cellParts, "Fused + cells, full maps", "fused", false, cells = true) },
            { run(cellParts, "Wi-Fi only (kNN), honest", "knn", true) },
        )) { it() }.forEach { sb.append(it.row()).append('\n') }
        val newParts = idx.parts.filter { it.part >= 15 && it.marks >= 3 }.map { it.part }
        sb.append("\n## New parts $newParts (28.10)\n\n").append(hdr)
        Par.map(listOf<() -> Stat>(
            { run(newParts, "Wi-Fi only (kNN), honest", "knn", true) },
            { run(newParts, "Fused (+cells where recorded), honest", "fused", true, cells = true) },
            { run(newParts, "Fused (+cells), full map", "fused", false, cells = true) },
        )) { it() }.forEach { sb.append(it.row()).append('\n') }
        sb.append("\n## Walked distance: PDR vs marks (mark-to-mark map distance)\n\n")
        sb.append("| part | steps | map m | PDR m (0.58 m/step) | PDR m (online step length) | step length m | ratio nominal | ratio online |\n|---|---|---|---|---|---|---|---|\n")
        sb.append(walk)
        sb.append("\n").append(baroReport())
        val txt = sb.toString()
        println(txt)
        outDir.resolve("positioning.md").writeText(txt)
        outDir.resolve("trace_fused_honest.csv").writeText(trace.toString())
        assertTrue(rows[2].q(rows[2].err, 0.5) < rows[0].q(rows[0].err, 0.5))
    }

    /** Barometer floor-transition detector on all parts vs the floor changes in marks.json. */
    private fun baroReport(): String {
        val sb = StringBuilder("## Barometer floor transitions\n\n")
        sb.append("Recorded transitions = consecutive marks on different floors. Detected = event in the right direction within 40 s of the change.\n\n")
        sb.append("| part | transition | marks | detected | Δp hPa | span s | steps in window | kind guess |\n|---|---|---|---|---|---|---|---|\n")
        var trans = 0; var det = 0; var fa = 0; var faNear = 0; var hours = 0.0
        for ((pn, rp) in parts.toSortedMap()) {
            val bd = BaroDetector()
            for (i in rp.pT.indices) bd.add(rp.pT[i], rp.p[i].toDouble())
            hours += (rp.t1 - rp.t0) / 3600e9
            val sd = StepDetector(); val stepT = ArrayList<Long>()
            for (i in rp.accT.indices) { val s = sd.add(rp.accT[i], rp.acc[i * 3], rp.acc[i * 3 + 1], rp.acc[i * 3 + 2]); if (s > 0) stepT.add(s) }
            val tr = (1 until rp.markT.size).filter { rp.markFloor[it] != rp.markFloor[it - 1] }
            val used = HashSet<BaroDetector.Event>()
            for (i in tr) {
                trans++
                val dir = if (rp.markFloor[i] > rp.markFloor[i - 1]) 1 else -1
                val e = bd.events.firstOrNull { it.dir == dir && it.tNs >= rp.markT[i - 1] - 40_000_000_000L && it.tNs <= rp.markT[i] + 40_000_000_000L }
                if (e != null) { det++; used.add(e) }
                val steps = if (e != null) stepT.count { it in (e.tNs - (e.durS * 1e9).toLong())..e.tNs } else 0
                val kind = if (e == null) "" else if (steps >= 10) "stairs" else if (e.durS < 15) "lift" else "escalator/lift"
                sb.append(String.format(Locale.US, "| %d | %d→%d | #%d→#%d | %s | %s | %s | %s | %s |\n", pn, rp.markFloor[i - 1], rp.markFloor[i],
                    rp.markId[i - 1], rp.markId[i], if (e != null) "yes" else "no", e?.let { "%.2f".format(Locale.US, it.dp) } ?: "",
                    e?.let { "%.0f".format(Locale.US, it.durS) } ?: "", if (e != null) "$steps" else "", kind))
            }
            for (e in bd.events) if (e !in used) {
                fa++
                val gt = rp.truth(e.tNs, 120_000_000_000L)
                if (gt != null && building.connNear(gt.floor, gt.x, gt.y, 15.0) != null) faNear++
            }
        }
        sb.append(String.format(Locale.US, "\nDetected %d of %d transitions. False alarms: %d in %.1f h of recordings (%.1f/h); %d of them within 15 m of a connector " +
            "(only those can change the floor in the filter, which also needs Wi-Fi floor evidence).\n", det, trans, fa, hours, fa / hours, faNear))
        return sb.toString()
    }
}
