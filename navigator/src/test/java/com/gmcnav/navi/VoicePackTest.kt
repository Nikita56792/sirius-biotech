package com.gmcnav.navi

import com.gmcnav.navi.core.AccessWindow
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Maneuver
import com.gmcnav.navi.core.Phrases
import com.gmcnav.navi.core.Prompt
import com.gmcnav.navi.core.VPart
import com.gmcnav.navi.core.VoicePlanner
import com.gmcnav.navi.core.VoiceVariants
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Recordings only: every prompt the navigator can build is made of files that exist in assets/sounds/ru_alice, the
 * sources contain no speech synthesis, the voice variants follow their weights without immediate repeats, the rare
 * arrival joke is at most once a day and only on eligible routes, and the position-lost / found announcements behave.
 * Report: build/validation/voice_pack.md
 */
class VoicePackTest {
    private val assets = File("src/main/assets")
    private val sb = StringBuilder("# Voice pack: recordings only\n\n")
    private fun exists(p: VPart.Rec) = File(assets, "sounds/ru_alice/${p.key}/${p.file}").isFile
    private val variantsText by lazy { File(assets, "voice_variants.json").readText() }

    private fun allPrompts(vv: VoiceVariants): List<Prompt> {
        val out = ArrayList<Prompt>()
        fun m(type: MType, kind: String = "", from: Int = 1, to: Int = 1) = Maneuver(type, 10.0, 0, from, 0.0, 0.0, connKind = kind, fromFloor = from, toFloor = to)
        val turns = listOf(MType.TAKE_LEFT, MType.TAKE_RIGHT, MType.TURN_LEFT, MType.TURN_RIGHT, MType.HARD_LEFT, MType.HARD_RIGHT, MType.UTURN).map { m(it) }
        val conns = listOf("lift", "escalator", "stair").flatMap { k -> (1..3).flatMap { a -> (1..3).filter { it != a }.map { z -> m(MType.CONNECTOR, k, a, z) } } }
        val actions = turns + conns
        for (a in actions) {
            out.add(Phrases.prompt(Phrases.action(a), "near", true))
            for (d in listOf(10, 15, 20, 30)) out.add(Phrases.prompt(Phrases.overDist(d) + Phrases.action(a), "far", false))
            for (b in actions) out.add(Phrases.prompt(Phrases.action(a) + VPart.Rec("Then") + Phrases.action(b), "chain", true))
        }
        val sched = listOf(AccessWindow(setOf(1, 2, 3, 4), 660, 780), AccessWindow(setOf(1, 2, 3, 4), 840, 960))
        for (lvl in 1..2) for (sc in listOf(emptyList(), sched)) out.add(Phrases.prompt(Phrases.accessUnits(lvl, sc), "finish", true))
        // events with variants (many draws), reroutes, position, start
        val vp = VoicePlanner(variants = vv)
        repeat(300) { i ->
            for (ev in vv.events.keys) vv.pick(ev, routeLen = if (i % 2 == 0) 200.0 else 50.0, floorChange = i % 3 == 0)?.let { out.add(Phrases.prompt(listOf(it), ev, true)) }
            out += vp.onReroute(i * 6_000_000_000L, faster = i % 3 == 1, closure = i % 3 == 2)
        }
        for (d in listOf(10, 15, 20, 30)) out.add(Phrases.prompt(listOf(VPart.Rec("RouteWillFinish")) + Phrases.numberKeys(d).map { VPart.Rec(it) } + VPart.Rec(Phrases.meterKey(d)), "far", false))
        out.add(Phrases.prompt(listOf(VPart.Rec("RouteCalculated"), VPart.Rec("WalkStraight")), "start", true))
        return out
    }

    @Test
    fun recordingsOnly() {
        val vv = VoiceVariants(variantsText, 1)
        // 1. every option of the config exists
        val opts = vv.events.values.flatten()
        val missingOpts = opts.filter { !exists(VPart.Rec(it.key, it.file)) }.map { it.id }
        sb.append("Variant options: ${opts.size}, missing files: $missingOpts\n")
        // 2. every prompt the planner can build is made of existing recordings
        val prompts = allPrompts(vv)
        val parts = prompts.flatMap { it.parts }.toSet()
        val missing = parts.filter { !exists(it) }.map { "${it.key}/${it.file}" }
        sb.append("Prompts built: ${prompts.size}; distinct recordings used: ${parts.size}; missing: $missing\n")
        // 3. no speech synthesis left in the sources
        val src = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        val tts = src.filter { f -> f.readText().let { it.contains("TextToSpeech") || it.contains("VPart.Tts") || it.contains("speech.tts") } }.map { it.name }
        sb.append("Sources mentioning TextToSpeech / VPart.Tts: $tts\n")
        // the new units are all in the pack
        val units = listOf("FloorTo1", "FloorTo2", "FloorTo3", "FloorTo4", "FloorTo5", "FloorToBasement", "FloorToAttic", "FloorToRoof", "ConnLiftUp", "ConnLiftDown", "ConnEscalatorUp", "ConnEscalatorDown", "AccessAhead",
            "AccessNeedExtended", "AccessNeedBasic", "AccessFreeIntro", "AccessDaysMonThu", "AccessTime11to13", "AccessTimeAnd14to16",
            "RouteAroundClosure", "StairsUp", "StairsDown")
        val noUnit = units.filter { !exists(VPart.Rec(it)) }
        sb.append("New units present: ${units.size - noUnit.size}/${units.size} $noUnit\n")
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("voice_pack.md").writeText(sb.toString() + "\n" + variantStats())
        println(sb)
        assertTrue(missingOpts.isEmpty() && missing.isEmpty() && tts.isEmpty() && noUnit.isEmpty())
    }

    /** Weighted picks: frequencies within ±3 % of the weights; characterful variants never twice in a row; minRoute; rare once per day. */
    private fun variantStats(): String {
        val out = StringBuilder("## Variant statistics (seeded, 40 000 picks per event; Arrival on a 120 m single-floor route — joke not eligible)\n\n")
        val vv = VoiceVariants(variantsText, 42)
        for ((ev, opts) in vv.events) {
            val cnt = HashMap<String, Int>(); var prev = ""; var repeats = 0; var defRepeats = 0
            val N = 40000
            repeat(N) {
                val p = vv.pick(ev, 120.0, false)!!; val id = "${p.key}/${p.file}"
                val isDef = opts.first { o -> o.id == id }.default
                if (id == prev) { if (isDef) defRepeats++ else repeats++ }
                prev = id; cnt.merge(id, 1, Int::plus)
            }
            val elig = opts.filter { it.rare == null && it.w > 0 && 120.0 >= it.minRoute }
            val total = elig.sumOf { it.w }
            var worst = 0.0
            out.append("- $ev: characterful repeats $repeats, default repeats $defRepeats; " + elig.joinToString(", ") { o ->
                val f = (cnt[o.id] ?: 0).toDouble() / N; val w = o.w / total; worst = maxOf(worst, abs(f - w))
                String.format(Locale.US, "%s%s w=%.3f → %.3f", o.id, if (o.default) " (default)" else "", w, f) } +
                String.format(Locale.US, "; max |freq − w| = %.3f\n", worst))
            check(repeats == 0) { "$ev: a characterful variant repeated" }
            check(worst <= 0.03) { "$ev: frequencies off by ${"%.3f".format(worst)}" }
        }
        // minRoute: short routes never get the ≥ 100 m variant; the rare joke never on short single-floor routes
        val vs = VoiceVariants(variantsText, 7)
        val shortIds = (1..5000).map { vs.pick("Arrival", 60.0, false)!!.let { "${it.key}/${it.file}" } }.toSet()
        out.append("- Arrival on 60 m routes without a floor change: ${shortIds.sorted()}\n")
        check("RouteFinished/6.mp3" !in shortIds && "RouteFinished/3.mp3" !in shortIds)
        // rare: at most once per day, ≈ 5 % of the first eligible arrival of a day
        val vr = VoiceVariants(variantsText, 11); var d = 0; vr.today = { "day$d" }
        var rareDays = 0; var maxPerDay = 0
        for (dd in 0 until 2000) {
            d = dd; var n = 0
            repeat(10) { if (vr.pick("Arrival", 300.0, false)!!.file == "3.mp3") n++ }
            if (n > 0) rareDays++; maxPerDay = maxOf(maxPerDay, n)
        }
        out.append(String.format(Locale.US, "- Rare joke over 2000 days × 10 eligible arrivals: on %d days (%.1f %%), at most %d per day\n", rareDays, 100.0 * rareDays / 2000, maxPerDay))
        check(maxPerDay <= 1 && rareDays in 600..1200)       // 1 − 0.95^10 ≈ 40 % of days
        return out.toString()
    }

    /** «Местоположение (не) определено» are no longer voiced (30.10): the recordings are gone from the pack and nothing refers to them. */
    @Test
    fun noPositionPhrases() {
        assertTrue(listOf("../../source/sounds/ru_alice", "src/main/assets/sounds/ru_alice").all { d -> !File(d, "PositionUnknown").exists() && !File(d, "PositionFound").exists() } && File("../../source/sounds/ru_alice/FloorTo3").isDirectory)
        val src = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(!src.contains("\"PositionUnknown\"") && !src.contains("\"PositionFound\""))
    }
}
