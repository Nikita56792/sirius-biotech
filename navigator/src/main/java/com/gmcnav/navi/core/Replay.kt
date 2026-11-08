package com.gmcnav.navi.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One recorded part of a calibration session (tools/build_assets.py -> replay/<session>/partNN.bin):
 * accelerometer, game rotation vector, rotation vector, Wi-Fi scans (AP sets as the phone saw them), cell responses,
 * and the user's ground-truth marks.
 */
class ReplayPart(bytes: ByteArray, bssids: List<String>, cellKeys: List<String>) {
    val session: String
    val part: Int
    val t0: Long
    val t1: Long
    val accT: LongArray; val acc: FloatArray
    val qT: LongArray; val q: FloatArray
    val rT: LongArray; val r: FloatArray
    val pT: LongArray; val p: FloatArray
    val scanT: LongArray; val scans: List<Map<String, Float>>
    val cellT: LongArray; val cells: List<Map<String, Float>>
    val markT: LongArray; val markFloor: IntArray; val markX: DoubleArray; val markY: DoubleArray; val markId: IntArray
    val markOut: BooleanArray
    val degraded: List<LongArray>

    init {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val hl = bb.getInt(0)
        val hdr = Json.parse(String(bytes, 4, hl, Charsets.UTF_8)).obj()
        var base = 4 + hl; base += (4 - base % 4) % 4
        val arrs = HashMap<String, Map<String, Any?>>()
        for (a in hdr["arrays"].arr()) { val o = a.obj(); arrs[o["k"].str()] = o }
        fun buf(k: String): ByteBuffer? {
            val o = arrs[k] ?: return null
            val off = base + o["off"].int(); val len = o["len"].int()
            return ByteBuffer.wrap(bytes, off, len).slice().order(ByteOrder.LITTLE_ENDIAN)
        }
        fun longs(k: String): LongArray { val b = buf(k) ?: return LongArray(0); val a = LongArray(b.remaining() / 8); b.asLongBuffer().get(a); return a }
        fun floats(k: String): FloatArray { val b = buf(k) ?: return FloatArray(0); val a = FloatArray(b.remaining() / 4); b.asFloatBuffer().get(a); return a }
        fun ints(k: String): IntArray { val b = buf(k) ?: return IntArray(0); val a = IntArray(b.remaining() / 4); b.asIntBuffer().get(a); return a }
        fun shorts(k: String): ShortArray { val b = buf(k) ?: return ShortArray(0); val a = ShortArray(b.remaining() / 2); b.asShortBuffer().get(a); return a }
        fun bytesOf(k: String): ByteArray { val b = buf(k) ?: return ByteArray(0); val a = ByteArray(b.remaining()); b.get(a); return a }
        session = hdr["session"].str(); part = hdr["part"].int(); t0 = hdr["t0"].dbl().toLong(); t1 = hdr["t1"].dbl().toLong()
        degraded = (hdr["degraded"] as? List<*>)?.map { w -> w.arr().map { it.dbl().toLong() }.toLongArray() } ?: emptyList()
        accT = longs("acc_t"); acc = floats("acc")
        qT = longs("q_t"); q = floats("q")
        rT = longs("r_t"); r = floats("r")
        pT = longs("p_t"); p = floats("p")
        scanT = longs("scan_t")
        val so = ints("scan_off"); val ai = ints("ap_idx"); val ar = bytesOf("ap_rssi")
        scans = scanT.indices.map { s -> val m = HashMap<String, Float>(); for (j in so[s] until so[s + 1]) m[bssids[ai[j]]] = ar[j].toFloat(); m }
        cellT = longs("cell_t")
        val co = ints("cell_off"); val ci = ints("cell_idx"); val cd = shorts("cell_dbm")
        cells = cellT.indices.map { s -> val m = HashMap<String, Float>(); for (j in co[s] until co[s + 1]) m[cellKeys[ci[j]]] = cd[j].toFloat(); m }
        markT = longs("mark_t")
        val mk = floats("mark")
        markFloor = IntArray(markT.size) { mk[it * 3].toInt() }
        markX = DoubleArray(markT.size) { mk[it * 3 + 1].toDouble() }
        markY = DoubleArray(markT.size) { mk[it * 3 + 2].toDouble() }
        markId = ints("mark_id")
        val mo = bytesOf("mark_out")
        markOut = BooleanArray(markT.size) { it < mo.size && mo[it].toInt() != 0 }
    }

    /** Ground truth at time t: linear between consecutive marks ≤ maxGap apart (same floor), else null. (floor, x, y, outdoor) */
    fun truth(t: Long, maxGapNs: Long = 30_000_000_000L): GroundTruth? {
        if (markT.isEmpty() || t < markT[0] || t > markT.last()) return null
        var i = java.util.Arrays.binarySearch(markT, t)
        if (i >= 0) return GroundTruth(markFloor[i], markX[i], markY[i], markOut[i], markId[i])
        i = -i - 1
        val a = i - 1; val z = i
        if (markT[z] - markT[a] > maxGapNs || markFloor[a] != markFloor[z]) return null
        val u = (t - markT[a]).toDouble() / (markT[z] - markT[a])
        return GroundTruth(markFloor[a], markX[a] + (markX[z] - markX[a]) * u, markY[a] + (markY[z] - markY[a]) * u,
            markOut[a] || markOut[z], if (u < 0.5) markId[a] else markId[z])
    }

    /** All events in time order. */
    fun events(): List<ReplayEvent> {
        val ev = ArrayList<ReplayEvent>(accT.size + qT.size + rT.size + scanT.size + cellT.size + pT.size)
        for (i in accT.indices) ev.add(ReplayEvent(accT[i], 0, i))
        for (i in qT.indices) ev.add(ReplayEvent(qT[i], 1, i))
        for (i in rT.indices) ev.add(ReplayEvent(rT[i], 2, i))
        for (i in scanT.indices) ev.add(ReplayEvent(scanT[i], 3, i))
        for (i in cellT.indices) ev.add(ReplayEvent(cellT[i], 4, i))
        for (i in pT.indices) ev.add(ReplayEvent(pT[i], 5, i))
        ev.sortWith(compareBy({ it.t }, { it.kind }))
        return ev
    }

    fun feed(e: ReplayEvent, eng: PositionEngine) {
        val i = e.i
        when (e.kind) {
            0 -> eng.onAccel(e.t, acc[i * 3], acc[i * 3 + 1], acc[i * 3 + 2])
            1 -> eng.onGameRot(e.t, q[i * 4].toDouble(), q[i * 4 + 1].toDouble(), q[i * 4 + 2].toDouble(), q[i * 4 + 3].toDouble())
            2 -> eng.onRotVec(e.t, r[i * 4].toDouble(), r[i * 4 + 1].toDouble(), r[i * 4 + 2].toDouble(), r[i * 4 + 3].toDouble())
            3 -> eng.onWifi(e.t, scans[i])
            4 -> eng.onCells(e.t, cells[i])
            5 -> eng.onPressure(e.t, p[i].toDouble())
        }
    }
}

class ReplayEvent(val t: Long, val kind: Int, val i: Int)
class GroundTruth(val floor: Int, val x: Double, val y: Double, val outdoor: Boolean, val markId: Int)

class ReplayIndex(text: String) {
    class Entry(val session: String, val part: Int, val file: String, val durS: Int, val marks: Int, val scans: Int, val cells: Int,
                val floors: List<Int>, val degraded: Boolean)
    val parts: List<Entry>
    val bssids: List<String>
    val cellKeys: List<String>
    init {
        val o = Json.parse(text).obj()
        bssids = o["bssids"].arr().map { it.str() }
        cellKeys = (o["cell_keys"] as? List<*>)?.map { it.str() } ?: emptyList()
        parts = o["parts"].arr().map { val p = it.obj()
            Entry(p["session"].str(), p["part"].int(), p["file"].str(), p["dur_s"].int(), p["marks"].int(), p["scans"].int(),
                (p["cells"] as? Number)?.toInt() ?: 0, p["floors"].arr().map { f -> f.int() }, p["degraded"] as? Boolean ?: false)
        }
    }
}
