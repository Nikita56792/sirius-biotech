package com.gmcnav.navi.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** BSSID / cell key dictionary (string -> dense int id). */
class KeyDict {
    private val ids = HashMap<String, Int>()
    val keys = ArrayList<String>()
    fun id(k: String): Int = ids.getOrPut(k) { keys.add(k); keys.size - 1 }
    fun find(k: String): Int = ids[k] ?: -1
    val size get() = keys.size
}

/**
 * Wi-Fi fingerprint (mark average or one scan on the path between marks), absolute plan metres.
 * ids/rssi sorted by id; top = up to 20 strongest ids ≥ −88 dBm. outdoor = walked outside the building outline.
 */
class FpPoint(
    val session: Int, val m: Int, val owner: Int, val seg: Boolean, val floor: Int, val x: Double, val y: Double,
    val nScans: Int, val tMs: Long, val part: Int, val outdoor: Boolean, val ids: IntArray, val rssi: FloatArray,
) {
    val top: IntArray = topIds(ids, rssi)
    val nAp get() = ids.size
    @JvmField var raw = 0f
    fun get(id: Int): Float {
        val i = java.util.Arrays.binarySearch(ids, id)
        return if (i >= 0) rssi[i] else Float.NaN
    }
    companion object {
        fun topIds(ids: IntArray, rssi: FloatArray): IntArray =
            ids.indices.filter { rssi[it] >= -88f }.sortedByDescending { rssi[it] }.take(20).map { ids[it] }.toIntArray()
    }
}

/** A scan prepared for matching: dense lookup over the dictionary + its own top-20. */
class ScanVec(dict: KeyDict, ap: Map<String, Float>) {
    val ids: IntArray
    val rssi: FloatArray
    val dense: FloatArray
    val top: IntArray
    val n: Int get() = ids.size

    init {
        val l = ap.entries.map { dict.id(it.key) to it.value }.sortedBy { it.first }
        ids = l.map { it.first }.toIntArray(); rssi = l.map { it.second }.toFloatArray()
        dense = FloatArray(dict.size) { Float.NaN }
        for (i in ids.indices) dense[ids[i]] = rssi[i]
        top = FpPoint.topIds(ids, rssi)
    }
}

class Match(
    /** Indoor points (not excluded), best first. */
    val ranked: List<FpPoint>,
    val bestIndoor: Float, val bestOutdoor: Float, val nAp: Int,
)

class PosEst(val floor: Int, val x: Double, val y: Double, val radius: Double, val best: Float)

/**
 * Wi-Fi radio map (all calibration sessions) with the similarity metric tuned on this building
 * (gmc_nav calibration app, RadioMap.kt): per scan the 20 strongest APs ≥ −88 dBm of each set; shared AP — |ΔRSSI|
 * capped at 25 dB; AP in only one set — penalty clamp(rssi + 95, 3, 25) (a strong AP missing is bad, a weak one is not);
 * raw = exp(−d/9). Same place ≥ 0.2 (median 0.4–0.58), other places ≤ 0.16.
 */
class RadioMap {
    val dict = KeyDict()
    val points = ArrayList<FpPoint>()
    val sessions = ArrayList<String>()
    val indoorCount get() = points.count { !it.outdoor }
    /** The territory is walkable (grid since 28.10): outdoor fingerprints may guide the filter outside too. */
    @Volatile var outdoorInRanking = true
    @Volatile private var knownCache: java.util.BitSet? = null
    /** Dictionary ids that occur in some fingerprint (BSSIDs the radio map knows). */
    private fun known(): java.util.BitSet = knownCache ?: java.util.BitSet().also { bs -> for (p in points) for (i in p.ids) bs.set(i); knownCache = bs }
    fun knownCount(ap: Map<String, Float>): Int { val k = known(); return ap.keys.count { val i = dict.find(it); i >= 0 && k.get(i) } }

    /** assets/radio/wifi.json produced by tools/build_assets.py */
    fun loadPacked(text: String) {
        knownCache = null
        val o = Json.parse(text).obj()
        val sess = o["sessions"].arr().map { it.str() }
        val base = sessions.size
        sessions.addAll(sess)
        val bss = o["bssids"].arr().map { dict.id(it.str()) }
        val fields = o["fields"].arr().map { it.str() }
        val fx = fields.withIndex().associate { it.value to it.index }
        for (pp in o["points"].arr()) {
            val p = pp.arr()
            val idx = p[fx.getValue("ap_idx")].arr(); val rs = p[fx.getValue("rssi")].arr()
            val pairs = idx.indices.map { bss[idx[it].int()] to rs[it].dbl().toFloat() }.sortedBy { it.first }
            points.add(FpPoint(base + p[fx.getValue("session")].int(), p[fx.getValue("m")].int(), p[fx.getValue("owner")].int(),
                p[fx.getValue("seg")].int() == 1, p[fx.getValue("floor")].int(), p[fx.getValue("x")].dbl(), p[fx.getValue("y")].dbl(),
                p[fx.getValue("nScans")].int(), p[fx.getValue("t_ms")].dbl().toLong(), p[fx.getValue("part")].int(),
                (fx["outdoor"]?.let { p[it].int() } ?: 0) == 1,
                pairs.map { it.first }.toIntArray(), pairs.map { it.second }.toFloatArray()))
        }
    }

    /**
     * A session exported by the calibration app (fp_marks.jsonl + marks.json): only fingerprints of valid marks.
     * outdoorTest decides the outdoor class (off the navigation grid).
     */
    fun addSession(name: String, marksJson: String, fpJsonl: String, outdoorTest: (Int, Double, Double) -> Boolean): Int {
        knownCache = null
        val mk = Json.parse(marksJson).obj()["marks"].arr().map { it.obj() }
        val marks = mk.associateBy { it["id"].int() }
        val si = sessions.size; sessions.add(name)
        var n = 0
        val seen = LinkedHashMap<Int, FpPoint>()
        for (line in fpJsonl.lineSequence()) {
            if (line.isBlank()) continue
            val o = runCatching { Json.parse(line).obj() }.getOrNull() ?: continue
            val owner = (o["o"] ?: o["m"]).int()
            val mo = marks[owner] ?: continue
            val pairs = o["ap"].obj().entries.map { dict.id(it.key.replace("\u0000", "").lowercase()) to it.value.dbl().toFloat() }.sortedBy { it.first }
            val f = o["f"].int(); val x = o["x"].dbl(); val y = o["y"].dbl()
            seen[o["m"].int()] = FpPoint(si, o["m"].int(), owner, (o["s"] as? Number)?.toInt() == 1, f, x, y, (o["n"] as? Number)?.toInt() ?: 1,
                (mo["t_ns"].dbl() / 1e6).toLong(), mo["part"].int(), outdoorTest(f, x, y),
                pairs.map { it.first }.toIntArray(), pairs.map { it.second }.toFloatArray())
        }
        points.addAll(seen.values); n += seen.size
        return n
    }

    private fun dist(s: ScanVec, p: FpPoint): Float {
        var sum = 0f; var n = 0
        for (k in s.top) {
            val f = p.get(k); val v = s.dense[k]
            sum += if (!f.isNaN()) min(25f, abs(v - f)) else (v + 95f).coerceIn(3f, 25f); n++
        }
        for (k in p.top) {
            if (k < s.dense.size && !s.dense[k].isNaN()) continue
            sum += (p.get(k) + 95f).coerceIn(3f, 25f); n++
        }
        return if (n == 0) 25f else sum / n
    }

    /** Scores every point (raw = exp(−d/9)); exclude = points hidden from this query (leave-out tests). */
    fun match(ap: Map<String, Float>, exclude: ((FpPoint) -> Boolean)? = null): Match {
        val s = ScanVec(dict, ap)
        var bi = 0f; var bo = 0f
        val ranked = ArrayList<FpPoint>()
        for (p in points) {
            if (exclude != null && exclude(p)) { p.raw = 0f; continue }
            p.raw = exp(-dist(s, p) / 9f)
            if (p.outdoor) { if (p.raw > bo) bo = p.raw; if (outdoorInRanking) ranked.add(p) } else { ranked.add(p); if (p.raw > bi) bi = p.raw }
        }
        ranked.sortByDescending { it.raw }
        return Match(ranked, bi, bo, s.n)
    }

    /**
     * Where the scan's strongest known APs are strongest in the map: for each of the k strongest (≥ −75 dBm) the indoor
     * fingerprint with its highest RSSI, weighted by the scan RSSI; virtual APs of one radio (same peak within 3 m) merged.
     * A coarse place when no fingerprint resembles the scan: an uncalibrated room next to an AP reads it 20–30 dB stronger
     * than anywhere in the map (ГМЦ, 30.10: EDS at −45 dBm, map maximum −75, best similarity 0.15).
     */
    fun apPeaks(ap: Map<String, Float>, k: Int = 6, exclude: ((FpPoint) -> Boolean)? = null): List<Pair<FpPoint, Float>> {
        val out = ArrayList<Pair<FpPoint, Float>>()
        for ((key, v) in ap.entries.filter { it.value >= -75f }.sortedByDescending { it.value }) {
            if (out.size >= k) break
            val id = dict.find(key); if (id < 0) continue
            var best: FpPoint? = null; var br = -999f
            for (p in points) {
                if (p.outdoor || (exclude != null && exclude(p))) continue
                val r = p.get(id); if (!r.isNaN() && r > br) { br = r; best = p }
            }
            val bp = best ?: continue
            if (out.none { it.first.floor == bp.floor && kotlin.math.hypot(it.first.x - bp.x, it.first.y - bp.y) < 3.0 }) out.add(bp to v)
        }
        return out
    }

    /** Typical AP count of fingerprints near a position (to recognise a collapsed scan), or -1 if unknown. */
    fun expectedApCount(floor: Int, x: Double, y: Double, r: Double = 12.0): Int {
        val l = points.filter { it.floor == floor && kotlin.math.hypot(it.x - x, it.y - y) <= r }.map { it.nAp }.sorted()
        return if (l.size >= 3) l[l.size / 2] else -1
    }
}

/**
 * Wi-Fi-only position (baseline, and the start guess): weighted kNN (k=5) on the floor of the best match,
 * weights raw^4, 60 % blend with the previous estimate, a new floor accepted on the second scan in a row.
 */
class WifiKnn {
    var est: PosEst? = null; private set
    private var pendingFloor = 0
    fun reset() { est = null; pendingFloor = 0 }

    fun update(m: Match): PosEst? {
        val top = m.ranked
        if (top.isEmpty() || top[0].raw < 0.05f) return est
        val fl = top[0].floor
        val k = top.asSequence().filter { it.floor == fl }.take(5).toList()
        var ws = 0.0; var x = 0.0; var y = 0.0
        for (p in k) { val w = p.raw.toDouble().pow(4); ws += w; x += w * p.x; y += w * p.y }
        if (ws <= 0) return est
        x /= ws; y /= ws
        var r = 0.0; for (p in k) r += p.raw.toDouble().pow(4) * ((p.x - x).pow(2) + (p.y - y).pow(2))
        val prev = est
        var of = fl; var ox = x; var oy = y
        if (prev != null) {
            if (fl != prev.floor) {
                if (pendingFloor == fl) pendingFloor = 0 else { pendingFloor = fl; of = prev.floor; ox = prev.x; oy = prev.y }
            } else {
                pendingFloor = 0
                ox = prev.x + (x - prev.x) * 0.6; oy = prev.y + (y - prev.y) * 0.6
            }
        }
        est = PosEst(of, ox, oy, sqrt(r / ws).coerceAtLeast(1.5), top[0].raw)
        return est
    }
}

/** Cell fingerprint: levels of visible cells ("L:earfcn:pci" -> dBm) at a plan point. */
class CellPoint(val src: String, val floor: Int, val x: Double, val y: Double, val outdoor: Boolean, val cells: Map<Int, Float>, val tMs: Long) {
    @JvmField var raw = 0f
}

/**
 * Cell radio map (port of CellMap.kt of the calibration app): shared cell |Δ| capped at 20 dB, a cell in one set only —
 * clamp(dBm + 125, 3, 20); raw = exp(−d/5), Wi-Fi-positioned points count 0.8. Coarse: only used as a weak hint.
 */
class CellMap {
    val dict = KeyDict()
    val points = ArrayList<CellPoint>()

    fun loadPacked(text: String) {
        val o = Json.parse(text).obj()
        val keys = o["keys"].arr().map { dict.id(it.str()) }
        val fields = o["fields"].arr().map { it.str() }
        val fx = fields.withIndex().associate { it.value to it.index }
        for (pp in o["points"].arr()) {
            val p = pp.arr()
            val idx = p[fx.getValue("cell_idx")].arr(); val db = p[fx.getValue("dbm")].arr()
            val c = HashMap<Int, Float>(); for (i in idx.indices) c[keys[idx[i].int()]] = db[i].dbl().toFloat()
            points.add(CellPoint(p[fx.getValue("src")].str(), p[fx.getValue("floor")].int(), p[fx.getValue("x")].dbl(), p[fx.getValue("y")].dbl(),
                (fx["outdoor"]?.let { p[it].int() } ?: 0) == 1, c, p[fx.getValue("t_ms")].dbl().toLong()))
        }
    }

    fun addJsonl(text: String) {
        val byKey = LinkedHashMap<String, CellPoint>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val o = runCatching { Json.parse(line).obj() }.getOrNull() ?: continue
            val c = HashMap<Int, Float>(); for ((k, v) in o["c"].obj()) c[dict.id(k)] = v.dbl().toFloat()
            val p = CellPoint(o["src"].str(), o["f"].int(), o["x"].dbl(), o["y"].dbl(), false, c, (o["t"].dbl() / 1e6).toLong())
            val k = "${o["p"]}:${o["t"]}"
            if (p.src == "mark" || byKey[k]?.src != "mark") byKey[k] = p
        }
        points.addAll(byKey.values)
    }

    private fun dist(a: Map<Int, Float>, b: Map<Int, Float>): Float {
        var s = 0f; var n = 0
        for ((k, v) in a) { val w = b[k]; s += if (w != null) min(20f, abs(v - w)) else (v + 125f).coerceIn(3f, 20f); n++ }
        for ((k, v) in b) if (k !in a) { s += (v + 125f).coerceIn(3f, 20f); n++ }
        return if (n == 0) 20f else s / n
    }

    /** Scores all points; returns them best first (empty if no map). */
    fun match(cells: Map<String, Float>, exclude: ((CellPoint) -> Boolean)? = null): List<CellPoint> {
        if (points.isEmpty() || cells.isEmpty()) return emptyList()
        val c = HashMap<Int, Float>(); for ((k, v) in cells) c[dict.id(k)] = v
        val l = ArrayList<CellPoint>()
        for (p in points) {
            if (exclude != null && exclude(p)) continue
            p.raw = exp(-dist(c, p.cells) / 5f) * if (p.src == "mark") 1f else 0.8f
            l.add(p)
        }
        l.sortByDescending { it.raw }
        return l
    }
}
