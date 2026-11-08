package com.gmcnav.navi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.hypot

/** Подпись на карте (координаты локальные: относительно центра здания). */
class Label(val x: Float, val y: Float, val text: String, val name: String, val prio: Int)

/** Лифт / лестница / эскалатор: позиции по этажам (локальные координаты). */
class Conn(val kind: String, val name: String, val pos: Map<Int, FloatArray>)

/** Геометрия этажа, готовая к загрузке в GPU (direct-буферы). */
class FloorGeom(
    val n: Int, val elev: Float, val name: String, val bbox: FloatArray,
    val wallXY: ByteBuffer, val wallIdx: ByteBuffer, val wallIdxCount: Int,
    val roomXY: ByteBuffer, val roomIdx: ByteBuffer, val roomIdxCount: Int, val roomRGB: ByteBuffer,
    val doorXY: ByteBuffer, val doorCount: Int, val stairXY: ByteBuffer, val stairCount: Int,
    val labels: List<Label>,
    /** Optional furniture lines (tribunes, seat rows): drawn only, never routed. */
    val furnXY: ByteBuffer? = null, val furnCount: Int = 0,
    /** Optional 2D line layers delivered later: roof_xy (roof outline), fence_xy (territory fence), path_xy (outdoor paths). */
    val extra: Map<String, Pair<ByteBuffer, Int>> = emptyMap(),
)

/**
 * План ГМЦ из assets/map (генерируется tools/export_android_assets.py из модели навигатора).
 * Абсолютные координаты плана (м) = локальные + (cx, cy). Отметки калибровки хранятся в абсолютных.
 */
class MapData(ctx: Context, val prefix: String = "") {
    val title: String
    val cx: Double
    val cy: Double
    val gridX0: Double
    val gridY1: Double
    val gridRes: Double
    val cols: Int
    val rows: Int
    val floors: List<FloorGeom>
    /** Map heading (rad) of true north from meta.json "geo" (same formula as Building.northHeading): the map can be shown before the building model. */
    val northHeading: Double
    val roomTitle = HashMap<Int, String>()
    val conns = ArrayList<Conn>()
    private val nav = HashMap<Int, ShortArray>()
    private val am = ctx.assets

    init {
        val meta = JSONObject(am.open("${prefix}map/meta.json").bufferedReader(Charsets.UTF_8).readText())
        title = meta.getString("title")
        val c = meta.getJSONArray("center"); cx = c.getDouble(0); cy = c.getDouble(1)
        val g = meta.getJSONObject("grid")
        gridX0 = g.getDouble("x0"); gridY1 = g.getDouble("y1"); gridRes = g.getDouble("res")
        cols = g.getInt("cols"); rows = g.getInt("rows")
        val rs = meta.getJSONArray("rooms")
        for (i in 0 until rs.length()) {
            val r = rs.getJSONArray(i); roomTitle[r.getInt(0)] = r.getString(2)
        }
        val cs = meta.getJSONArray("conns")
        for (i in 0 until cs.length()) {
            val o = cs.getJSONObject(i); val p = o.getJSONObject("pos"); val m = HashMap<Int, FloatArray>()
            for (k in p.keys()) { val a = p.getJSONArray(k); m[k.toInt()] = floatArrayOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat()) }
            conns.add(Conn(o.getString("kind"), o.getString("name"), m))
        }
        northHeading = meta.optJSONObject("geo")?.optJSONArray("plan_to_lonlat")?.let { m ->
            val a = m.getJSONArray(0).getDouble(0); val c = m.getJSONArray(1).getDouble(0); val d = m.getJSONArray(1).getDouble(1); val b = m.getJSONArray(0).getDouble(1)
            val det = a * d - c * b
            Math.atan2(-c / det, a / det)
        } ?: 0.0
        val fl = meta.getJSONArray("floors")
        floors = (0 until fl.length()).map { loadFloor(fl.getJSONObject(it)) }
    }

    fun floor(n: Int): FloorGeom = floors.first { it.n == n }

    private fun loadFloor(o: JSONObject): FloorGeom {
        val n = o.getInt("n")
        val arrays = readBin("${prefix}map/floor$n.bin")
        val bb = o.getJSONArray("bbox")
        val labels = ArrayList<Label>()
        val la = o.getJSONArray("labels")
        for (i in 0 until la.length()) {
            val l = la.getJSONArray(i)
            labels.add(Label(l.getDouble(0).toFloat(), l.getDouble(1).toFloat(), l.getString(2), l.getString(3), l.getInt(4)))
        }
        labels.sortByDescending { it.prio }
        fun a(k: String) = arrays.getValue(k)
        return FloorGeom(
            n, o.getDouble("elev").toFloat(), o.getString("name"),
            FloatArray(4) { bb.getDouble(it).toFloat() },
            a("wall_xy").first, a("wall_idx").first, a("wall_idx").second,
            a("room_xy").first, a("room_idx").first, a("room_idx").second, a("room_rgb").first,
            a("door_xy").first, a("door_xy").second / 2, a("stair_xy").first, a("stair_xy").second / 2,
            labels,
            (arrays["furn_xy"] ?: arrays["furn_lines"])?.first, ((arrays["furn_xy"] ?: arrays["furn_lines"])?.first?.capacity() ?: 0) / 8,
            listOf("roof_xy", "fence_xy", "path_xy", "edit_xy").mapNotNull { k -> arrays[k]?.let { k to (it.first to it.first.capacity() / 8) } }.toMap(),
        )
    }

    /** Возвращает k -> (direct-буфер, число элементов первой оси). */
    private fun readBin(path: String): Map<String, Pair<ByteBuffer, Int>> {
        val bytes = am.open(path).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val hl = bb.getInt(0)
        val hdr = JSONArray(String(bytes, 4, hl, Charsets.UTF_8))
        var base = 4 + hl; base += (4 - base % 4) % 4
        val out = HashMap<String, Pair<ByteBuffer, Int>>()
        for (i in 0 until hdr.length()) {
            val h = hdr.getJSONObject(i)
            val off = base + h.getInt("off"); val len = h.getInt("len")
            val d = ByteBuffer.allocateDirect(len).order(ByteOrder.nativeOrder())
            d.put(bytes, off, len); d.position(0)
            out[h.getString("k")] = d to h.getJSONArray("shape").getInt(0)
        }
        return out
    }

    /** Навигационная сетка этажа: id помещения (0 — непроходимо). */
    fun nav(n: Int): ShortArray = synchronized(nav) {
        nav.getOrPut(n) {
            val raw = am.open("${prefix}map/nav$n.u16").use { s ->
                val bo = ByteArrayOutputStream(cols * rows * 2); s.copyTo(bo); bo.toByteArray()
            }
            val sa = ShortArray(cols * rows)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sa)
            sa
        }
    }

    /** id помещения в абсолютной точке плана, 0 — стена/вне здания. */
    fun roomAt(n: Int, x: Double, y: Double): Int {
        val c = floor((x - gridX0) / gridRes).toInt(); val r = floor((gridY1 - y) / gridRes).toInt()
        if (c < 0 || r < 0 || c >= cols || r >= rows) return 0
        return nav(n)[r * cols + c].toInt() and 0xFFFF
    }

    /** Ближайшее помещение в радиусе maxM: (id, расстояние). */
    fun nearestRoom(n: Int, x: Double, y: Double, maxM: Double): Pair<Int, Double>? {
        val g = nav(n)
        val c0 = floor((x - gridX0) / gridRes).toInt(); val r0 = floor((gridY1 - y) / gridRes).toInt()
        val rad = (maxM / gridRes).toInt()
        var best: Pair<Int, Double>? = null
        for (dr in -rad..rad) for (dc in -rad..rad) {
            val r = r0 + dr; val c = c0 + dc
            if (r < 0 || c < 0 || r >= rows || c >= cols) continue
            val v = g[r * cols + c].toInt() and 0xFFFF
            if (v == 0) continue
            val d = hypot(dr.toDouble(), dc.toDouble()) * gridRes
            if (d <= maxM && (best == null || d < best.second)) best = v to d
        }
        return best
    }
}

/** Вид карты (из приложения калибровки): центр (локальные м), масштаб (пикс/м), поворот (рад). Экран: y вниз, мир: y вверх (север). */
class Viewport {
    var cx = 0f; var cy = 0f
    var scale = 2f
    var rot = 0f
    var w = 1; var h = 1

    @Synchronized fun toScreen(x: Float, y: Float, out: FloatArray) {
        val c = kotlin.math.cos(rot); val s = kotlin.math.sin(rot)
        val dx = (x - cx) * scale; val dy = -(y - cy) * scale
        out[0] = w / 2f + dx * c - dy * s
        out[1] = h / 2f + dx * s + dy * c
    }

    @Synchronized fun toWorld(sx: Float, sy: Float, out: FloatArray) {
        val c = kotlin.math.cos(rot); val s = kotlin.math.sin(rot)
        val ux = sx - w / 2f; val uy = sy - h / 2f
        val dx = ux * c + uy * s; val dy = -ux * s + uy * c
        out[0] = cx + dx / scale; out[1] = cy - dy / scale
    }

    /** Сдвиг карты на вектор экрана (dx, dy). */
    @Synchronized fun pan(dxs: Float, dys: Float) {
        val c = kotlin.math.cos(rot); val s = kotlin.math.sin(rot)
        val dx = dxs * c + dys * s; val dy = -dxs * s + dys * c
        cx -= dx / scale; cy += dy / scale
    }

    /** Двумя пальцами: точка мира под prev остаётся под next, масштаб *= k, поворот += da. */
    @Synchronized fun twoFinger(px: Float, py: Float, nx: Float, ny: Float, k: Float, da: Float) {
        val wp = FloatArray(2); toWorld(px, py, wp)
        scale = (scale * k).coerceIn(0.4f, 600f)
        rot += da
        val c = kotlin.math.cos(rot); val s = kotlin.math.sin(rot)
        val ux = nx - w / 2f; val uy = ny - h / 2f
        val dx = ux * c + uy * s; val dy = -ux * s + uy * c
        cx = wp[0] - dx / scale; cy = wp[1] + dy / scale
    }

    fun fit(bbox: FloatArray, margin: Float = 0.92f) {
        synchronized(this) {
            cx = (bbox[0] + bbox[2]) / 2; cy = (bbox[1] + bbox[3]) / 2; rot = 0f
            scale = minOf(w / (bbox[2] - bbox[0]), h / (bbox[3] - bbox[1])) * margin
        }
    }

    /** mat3 (column-major) мир -> NDC. */
    @Synchronized fun ndc(m: FloatArray) {
        val c = kotlin.math.cos(rot); val s = kotlin.math.sin(rot)
        val a = 2f * scale * c / w; val b = 2f * scale * s / w
        val c2 = -2f * scale * s / h; val d = 2f * scale * c / h
        m[0] = a; m[1] = c2; m[2] = 0f
        m[3] = b; m[4] = d; m[5] = 0f
        m[6] = -(a * cx + b * cy); m[7] = -(c2 * cx + d * cy); m[8] = 1f
    }
}
