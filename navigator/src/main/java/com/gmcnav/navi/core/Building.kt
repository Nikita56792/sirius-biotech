package com.gmcnav.navi.core

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.hypot

/** Asset access that works both on Android (AssetManager) and in JVM tests (files). */
fun interface AssetSource { fun open(path: String): InputStream }

class FileAssets(private val root: File) : AssetSource {
    override fun open(path: String): InputStream = File(root, path).inputStream()
}

class FloorInfo(val n: Int, val elev: Double, val name: String)

/** Time window when a СКУД place is open without a pass: ISO days (1 = Mon … 7 = Sun), minutes from local midnight. */
class AccessWindow(val days: Set<Int>, val startMin: Int, val endMin: Int)

/**
 * СКУД line (absolute plan metres), level 1 = базовый, 2 = расширенный. It applies to the doors it touches (doorIds):
 * only the line's part at those doors is a barrier. A line without doors (synthetic test lines) is a barrier as a whole.
 */
class AccessLine(val id: Int, val floor: Int, val level: Int, val name: String, val xs: DoubleArray, val ys: DoubleArray,
                 val schedule: List<AccessWindow> = emptyList(), val auto: Boolean = false,
                 /** Entrance from the street that needs the key (does not lock the room inside). */
                 val exterior: Boolean = false,
                 /** one_way = "exit": crossing along this unit normal (towards the exit side) is free, the other way needs the level. */
                 val exitDir: DoubleArray? = null) {
    val levelName: String get() = Access.levelName(level)
    /** Doors on this line (filled by Building). */
    val doors = ArrayList<Door>()
    fun levelAt(nowMs: Long): Int = Access.effective(level, schedule, nowMs)
    fun note(): String = Access.note(level, schedule)
    /** For the voice: no brackets or abbreviations. */
    fun spokenNote(): String = Access.spokenNote(level, schedule)
}

/** The whole room behind a СКУД door (nav-grid room id): restricted as a whole, so its other entrances are covered too. */
class AccessRoom(val floor: Int, val room: Int, val level: Int, val name: String, val line: Int, val area: Double,
                 val schedule: List<AccessWindow> = emptyList()) {
    fun levelAt(nowMs: Long): Int = Access.effective(level, schedule, nowMs)
}

object Access {
    /** 0 = нет пропуска (гость), 1 = базовый, 2 = расширенный. */
    fun levelName(level: Int) = when { level >= 2 -> "расширенный"; level == 1 -> "базовый"; else -> "нет пропуска" }
    fun note(level: Int, schedule: List<AccessWindow> = emptyList()) =
        "Дальше проход по СКУД (${levelName(level)} доступ" + (if (schedule.isNotEmpty()) "; без пропуска ${scheduleText(schedule)}" else "") + ")"
    /** Local time of the building (Sochi). */
    val TZ: java.util.TimeZone = java.util.TimeZone.getTimeZone("Europe/Moscow")
    fun openNow(schedule: List<AccessWindow>, nowMs: Long): Boolean {
        if (schedule.isEmpty()) return false
        val c = java.util.Calendar.getInstance(TZ).apply { timeInMillis = nowMs }
        val dow = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1     // Calendar: Sun = 1 … Sat = 7 -> ISO Mon = 1 … Sun = 7
        val min = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        return schedule.any { dow in it.days && min >= it.startMin && min < it.endMin }
    }
    /** Level required now: 0 while a schedule window is open, else the level. */
    fun effective(level: Int, schedule: List<AccessWindow>, nowMs: Long) = if (openNow(schedule, nowMs)) 0 else level
    private val DAY = arrayOf("", "пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val DAY_GEN = arrayOf("", "понедельника", "вторника", "среды", "четверга", "пятницы", "субботы", "воскресенья")
    private val DAY_ACC = arrayOf("", "понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")
    private val GEN = arrayOf("нуля", "одного", "двух", "трёх", "четырёх", "пяти", "шести", "семи", "восьми", "девяти", "десяти",
        "одиннадцати", "двенадцати", "тринадцати", "четырнадцати", "пятнадцати", "шестнадцати", "семнадцати", "восемнадцати", "девятнадцати")
    private val GEN_TENS = arrayOf("", "", "двадцати", "тридцати", "сорока", "пятидесяти")
    /** 11 -> «одиннадцати», 21 -> «двадцати одного» (genitive, for «с … до …»). */
    fun genNum(n: Int): String = if (n < 20) GEN[n] else GEN_TENS[n / 10] + if (n % 10 != 0) " " + GEN[n % 10] else ""
    private fun spokenTime(m: Int) = genNum(m / 60) + if (m % 60 != 0) " " + genNum(m % 60) else ""
    /** «с понедельника по четверг с одиннадцати до тринадцати и с четырнадцати до шестнадцати» */
    fun spokenSchedule(schedule: List<AccessWindow>): String =
        schedule.groupBy { it.days.sorted() }.entries.joinToString("; ") { (d, ws) ->
            val days = if (d.size >= 2 && d.last() - d.first() == d.size - 1) "с ${DAY_GEN[d.first()]} по ${DAY_ACC[d.last()]}"
                       else "в " + d.joinToString(", ") { DAY_ACC[it] }
            days + " " + ws.joinToString(" и ") { "с ${spokenTime(it.startMin)} до ${spokenTime(it.endMin)}" }
        }
    /** What the СКУД voice prompt says (made of recordings): «Дальше проход по СКУД, необходим расширенный доступ. …» */
    fun spokenNote(level: Int, schedule: List<AccessWindow> = emptyList()): String = Phrases.text(Phrases.accessUnits(level, schedule))
    fun scheduleText(schedule: List<AccessWindow>): String {
        fun days(d: Set<Int>): String { val l = d.sorted(); return if (l.size >= 3 && l.last() - l.first() == l.size - 1) "${DAY[l.first()]}–${DAY[l.last()]}" else l.joinToString(", ") { DAY[it] } }
        fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)
        return schedule.groupBy { days(it.days) }.entries.joinToString("; ") { (d, ws) -> d + " " + ws.joinToString(", ") { hm(it.startMin) + "–" + hm(it.endMin) } }
    }
    fun parseSchedule(o: Any?): List<AccessWindow> = (o as? List<*>)?.mapNotNull { w ->
        val m = w as? Map<*, *> ?: return@mapNotNull null
        fun mins(v: Any?): Int { val p = (v as String).split(":"); return p[0].toInt() * 60 + p[1].toInt() }
        AccessWindow((m["days"] as List<*>).map { (it as Number).toInt() }.toSet(), mins(m["start"]), mins(m["end"]))
    } ?: emptyList()
}

class Room(
    val id: Int, val floor: Int, val nums: List<String>, val names: List<String>, val func: List<String>,
    val zone: String?, val area: Double, val poleX: Double, val poleY: Double, val labels: List<DoubleArray>,
    val reach: Boolean, val anchorR: Int, val anchorC: Int, val void: Boolean,
)

class Door(val id: Int, val floor: Int, val x: Double, val y: Double, val rooms: IntArray, val ext: Boolean, val size: Double,
           val access: Int = 0, val accessLine: Int = 0, val schedule: List<AccessWindow> = emptyList())

/** Stairs / lift / escalator. pos: floor -> [x, y, row, col] (absolute plan metres, grid cell). */
class Connector(
    val id: Int, val kind: String, val name: String, val floors: List<Int>, val pos: Map<Int, DoubleArray>,
    val near: String?, val bbox: DoubleArray?,
) {
    /** Name for speech/UI without the capacity: "Лифт 7а (2000 кг)" -> "Лифт 7а". */
    val shortName: String get() = name.replace(Regex("\\s*\\(.*\\)"), "")
    val kindRu: String get() = when (kind) { "lift" -> "лифт"; "escalator" -> "эскалатор"; else -> "лестница" }
}

/**
 * The building model: grid geometry, floors, rooms, doors, floor connectors and the 0.3 m navigation grids
 * (uint16 room id per cell, 0 = wall/impassable; row 0 = y1 = north, col 0 = x0).
 * Coordinates are absolute plan metres (x right/east, y up/north), as in the calibration data.
 */
class Building(private val src: AssetSource) {
    val title: String
    val x0: Double
    val y1: Double
    val res: Double
    val cols: Int
    val rows: Int
    val n: Int get() = rows * cols
    val cx: Double
    val cy: Double
    val floors: List<FloorInfo>
    val rooms = HashMap<Int, Room>()
    val doors = ArrayList<Door>()
    val conns = ArrayList<Connector>()
    /** СКУД lines (meta.json "access"); empty until the data exists. Mutable for synthetic test lines. */
    val access = ArrayList<AccessLine>()
    /** Rooms behind СКУД doors (meta.json "access_rooms"). */
    val accessRooms = ArrayList<AccessRoom>()


    /** Stair drawings around stair connectors (map/stairs.json): conn id -> floor -> (stair segments, railing segments), x0,y0,x1,y1. */
    val stairs: Map<Int, Map<Int, Pair<List<DoubleArray>, List<DoubleArray>>>> by lazy {
        try {
            val o = Json.parse(src.open("map/stairs.json").bufferedReader(Charsets.UTF_8).readText()).obj()
            o.entries.associate { (cid, fv) ->
                cid.toInt() to fv.obj().entries.associate { (f, d) ->
                    val m = d.obj()
                    fun segs(k: String) = (m[k] as? List<*>)?.map { s -> s.arr().map { it.dbl() }.toDoubleArray() } ?: emptyList()
                    f.toInt() to (segs("stair") to segs("rail"))
                }
            }
        } catch (e: Exception) { emptyMap() }
    }

    /** plan metres -> lon/lat affine ([x, y, 1] @ M), null until meta.json has "geo". */
    var planToLonLat: Array<DoubleArray>? = null
    /** grids[fi] — navigation grid of floor index fi (floors[fi]). */
    val grids: Array<ShortArray>

    init {
        val m = Json.parse(src.open("map/places.json").bufferedReader(Charsets.UTF_8).readText()).obj()
        title = m["title"].str()
        val g = m["grid"].obj()
        x0 = g["x0"].dbl(); y1 = g["y1"].dbl(); res = g["res"].dbl(); cols = g["cols"].int(); rows = g["rows"].int()
        val c = m["center"].arr(); cx = c[0].dbl(); cy = c[1].dbl()
        floors = m["floors"].arr().map { val f = it.obj(); FloorInfo(f["n"].int(), f["elev"].dbl(), f["name"].str()) }
        for (o in m["rooms"].arr()) {
            val q = o.obj()
            val pole = q["pole"].arr()
            val anchor = q["anchor"]?.arr()
            rooms[q["id"].int()] = Room(
                q["id"].int(), q["floor"].int(), q["nums"].strs(), q["names"].strs(), q["func"].strs(),
                q["zone"] as? String, q["area"].dbl(), pole[0].dbl(), pole[1].dbl(),
                (q["labels"]?.arr() ?: emptyList()).map { val p = it.arr(); doubleArrayOf(p[0].dbl(), p[1].dbl()) },
                q["reach"] as? Boolean ?: true, anchor?.get(0)?.int() ?: -1, anchor?.get(1)?.int() ?: -1, q["void"] as? Boolean ?: false,
            )
        }
        for (o in m["doors"].arr()) {
            val d = o.obj()
            doors.add(Door(d["id"].int(), d["floor"].int(), d["x"].dbl(), d["y"].dbl(), d["rooms"].arr().map { it.int() }.toIntArray(),
                d["ext"] as? Boolean ?: false, (d["size"] as? Number)?.toDouble() ?: 1.0,
                (d["access"] as? Number)?.toInt() ?: 0, (d["access_line"] as? Number)?.toInt() ?: 0, Access.parseSchedule(d["schedule"])))
        }
        for (o in m["conn"].arr()) {
            val cn = o.obj()
            val pos = HashMap<Int, DoubleArray>()
            for ((k, v) in cn["pos"].obj()) pos[k.toInt()] = v.arr().map { it.dbl() }.toDoubleArray()
            conns.add(Connector(cn["id"].int(), cn["kind"].str(), cn["name"].str(), cn["floors"].arr().map { it.int() }, pos,
                cn["near"] as? String, (cn["bbox"] as? List<*>)?.map { it.dbl() }?.toDoubleArray()))
        }
        (m["access"] as? List<*>)?.forEach { o ->
            val a = o.obj(); val pts = a["pts"].arr().map { it.arr() }
            access.add(AccessLine(a["id"].int(), a["floor"].int(), a["level"].int(), a["name"] as? String ?: "СКУД",
                pts.map { it[0].dbl() }.toDoubleArray(), pts.map { it[1].dbl() }.toDoubleArray(),
                Access.parseSchedule(a["schedule"]), a["auto"] as? Boolean ?: false, a["exterior"] as? Boolean ?: false,
                if (a["one_way"] == "exit") (a["exit_dir"] as? List<*>)?.map { it.dbl() }?.toDoubleArray() else null))
        }
        val byId = access.associateBy { it.id }
        for (d in doors) if (d.accessLine != 0) byId[d.accessLine]?.takeIf { it.floor == d.floor }?.doors?.add(d)
        (m["access_rooms"] as? List<*>)?.forEach { o ->
            val a = o.obj()
            accessRooms.add(AccessRoom(a["floor"].int(), a["room"].int(), a["level"].int(), a["name"] as? String ?: "",
                (a["line"] as? Number)?.toInt() ?: 0, (a["area"] as? Number)?.toDouble() ?: 0.0, Access.parseSchedule(a["schedule"])))
        }
        ((m["geo"] as? Map<*, *>)?.get("plan_to_lonlat") as? List<*>)?.let { mm ->
            planToLonLat = mm.map { r -> r.arr().map { it.dbl() }.toDoubleArray() }.toTypedArray()
        }
        grids = Array(floors.size) { fi ->
            val bytes = src.open("map/nav${floors[fi].n}.u16").use { it.readBytes() }
            val sa = ShortArray(rows * cols)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sa)
            sa
        }
    }

    fun fi(floor: Int): Int = floors.indexOfFirst { it.n == floor }
    /** Floor elevation (m) by floor number; unknown floors: 0. */
    fun elevOf(floor: Int): Double = floors.firstOrNull { it.n == floor }?.elev ?: 0.0
    fun floorOf(fi: Int): Int = floors[fi].n

    fun row(y: Double): Int = floor((y1 - y) / res).toInt()
    fun col(x: Double): Int = floor((x - x0) / res).toInt()
    fun xOf(c: Int): Double = x0 + (c + 0.5) * res
    fun yOf(r: Int): Double = y1 - (r + 0.5) * res
    fun inGrid(r: Int, c: Int) = r in 0 until rows && c in 0 until cols

    fun roomIdCell(fi: Int, r: Int, c: Int): Int = if (inGrid(r, c)) grids[fi][r * cols + c].toInt() and 0xFFFF else 0
    fun passable(fi: Int, r: Int, c: Int): Boolean = roomIdCell(fi, r, c) != 0
    fun passableXY(fi: Int, x: Double, y: Double): Boolean = passable(fi, row(y), col(x))
    fun roomIdAt(fi: Int, x: Double, y: Double): Int = roomIdCell(fi, row(y), col(x))

    /** Nearest passable cell (ring search up to maxCells), optionally inside a given room. Returns r*cols+c or -1. */
    fun snap(fi: Int, r0: Int, c0: Int, maxCells: Int, room: Int = 0, ok: ((Int) -> Boolean)? = null): Int {
        val g = grids[fi]
        for (d in 0..maxCells) {
            var best = -1; var bd = Int.MAX_VALUE
            for (dr in -d..d) for (dc in -d..d) {
                if (maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc)) != d) continue
                val r = r0 + dr; val c = c0 + dc
                if (!inGrid(r, c)) continue
                val cell = r * cols + c
                val v = g[cell].toInt() and 0xFFFF
                if (v != 0 && (room == 0 || v == room) && (ok == null || ok(cell))) {
                    val dd = dr * dr + dc * dc
                    if (dd < bd) { bd = dd; best = cell }
                }
            }
            if (best >= 0) return best
        }
        return -1
    }

    /** Cells walked during calibration (map/walked.json, dilated 1.5 m): definitely walkable by ordinary people. Per floor index. */
    val walked: Array<java.util.BitSet> by lazy {
        val out = Array(floors.size) { java.util.BitSet() }
        try {
            val o = Json.parse(src.open("map/walked.json").bufferedReader(Charsets.UTF_8).readText()).obj()
            for ((f, v) in o["floors"].obj()) {
                val fi = fi(f.toInt()); if (fi < 0) continue
                val runs = v.arr()
                var k = 0
                while (k + 1 < runs.size) { val st = runs[k].int(); out[fi].set(st, st + runs[k + 1].int()); k += 2 }
            }
        } catch (e: Exception) { }
        out
    }
    fun isWalked(fi: Int, cell: Int): Boolean = walked[fi].get(cell)

    fun snapXY(fi: Int, x: Double, y: Double, maxM: Double = 4.0): Int = snap(fi, row(y), col(x), (maxM / res).toInt())

    // ------------------------------------------------------------------ titles (as in the web navigator)
    fun roomName(q: Room): String = q.names.firstOrNull { it.isNotEmpty() } ?: q.func.firstOrNull() ?: ""

    /**
     * Building code from a site label: «Альфа 5.4» -> «α5.4», «Бета 4.1 · Комната 3D проектирования» -> «β4.1»
     * (α Альфа, β Бета, γ Гамма, δ Дельта, η Эта).
     */
    fun alphaCode(name: String): String? = GREEK_RE.find(name.trim())?.let { m -> GREEK.getValue(m.groupValues[1].lowercase()) + m.groupValues[2] }

    /** Title of one label: «1454 · Альфа 5.4» becomes «α5.4 · Альфа 5.4 · 1454», «1-07-12 · Бета 4.1 · …» -> «β4.1 · Бета 4.1 · … · 1-07-12». */
    fun labelTitle(num: String, name: String): String {
        alphaCode(name)?.let { return "$it · ${name.trim()} · $num" }
        alphaCode(num)?.let { return "$it · ${num.trim()}" + if (name.isNotEmpty()) " · $name" else "" }   // site label used as the number
        return num + if (name.isNotEmpty()) " · $name" else ""
    }

    fun roomTitle(q: Room): String {
        val num = q.nums.firstOrNull(); val name = roomName(q)
        if (num != null) return labelTitle(num, q.names.firstOrNull() ?: name).let { t -> if (q.names.firstOrNull().isNullOrEmpty() && name.isNotEmpty()) "$num · $name" else t }
        if (name.isNotEmpty()) return name.replaceFirstChar { it.uppercase() }
        return if (q.area > 300) "Открытое пространство" else if (q.area > 40) "Помещение без номера" else "Проход"
    }

    private val lowPri = Regex("сан\\.?\\s*узел|санузел|уборн|душ|туалет|кладов|техн|щитов|инвентар|слаботоч|вентк|подсоб", RegexOption.IGNORE_CASE)

    /** Title of a big merged space at a point: the nearest room number label (utility rooms only if nothing else is near). */
    fun roomTitleAt(q: Room?, x: Double, y: Double): String {
        if (q == null) return ""
        if (q.nums.size < 2 || q.labels.size < q.nums.size) return roomTitle(q)
        var bi = 0; var bd = Double.MAX_VALUE
        for (i in q.nums.indices) {
            val p = q.labels[i]
            val d = hypot(p[0] - x, p[1] - y) + if (lowPri.containsMatchIn(q.names.getOrElse(i) { "" })) 15.0 else 0.0
            if (d < bd) { bd = d; bi = i }
        }
        return labelTitle(q.nums[bi], q.names.getOrElse(bi) { "" })
    }

    /** Short area name for the maneuver plate: "Главная улица", "Коридор" or the room number. */
    fun areaNameAt(fi: Int, x: Double, y: Double): String {
        val id = roomIdAt(fi, x, y)
        val q = rooms[id] ?: return ""
        if (q.nums.size >= 2 && q.labels.size >= q.nums.size) {
            var bi = 0; var bd = Double.MAX_VALUE
            for (i in q.nums.indices) {
                val p = q.labels[i]; val d = hypot(p[0] - x, p[1] - y) + if (lowPri.containsMatchIn(q.names.getOrElse(i) { "" })) 15.0 else 0.0
                if (d < bd) { bd = d; bi = i }
            }
            val nm = q.names.getOrElse(bi) { "" }
            alphaCode(nm)?.let { return it }
            return if (nm.isNotEmpty()) nm.replaceFirstChar { it.uppercase() } else q.nums[bi]
        }
        val nm = roomName(q)
        alphaCode(nm)?.let { return it }
        return if (nm.isNotEmpty()) nm.replaceFirstChar { it.uppercase() } else q.nums.firstOrNull() ?: ""
    }

    fun doorNear(floor: Int, x: Double, y: Double, maxD: Double): Door? =
        doors.filter { it.floor == floor }.minByOrNull { hypot(it.x - x, it.y - y) }?.takeIf { hypot(it.x - x, it.y - y) <= maxD }

    fun connNear(floor: Int, x: Double, y: Double, maxD: Double): Connector? {
        var best: Connector? = null; var bd = maxD
        for (c in conns) {
            val p = c.pos[floor] ?: continue
            val d = hypot(p[0] - x, p[1] - y)
            if (d <= bd) { bd = d; best = c }
        }
        return best
    }

    /** Plan point -> (lon, lat). Without the affine: placeholder centre (lat 43.4086, lon 39.9530) at the plan centre, plan north = north. */
    fun toLonLat(x: Double, y: Double): DoubleArray {
        val M = planToLonLat
        if (M != null) return doubleArrayOf(x * M[0][0] + y * M[1][0] + M[2][0], x * M[0][1] + y * M[1][1] + M[2][1])
        val lat = GEO_LAT + (y - cy) / 111_132.0
        val lon = GEO_LON + (x - cx) / (111_320.0 * Math.cos(Math.toRadians(GEO_LAT)))
        return doubleArrayOf(lon, lat)
    }

    /** (lon, lat) -> plan point (inverse of toLonLat). */
    fun fromLonLat(lon: Double, lat: Double): DoubleArray {
        val M = planToLonLat
        if (M != null) {
            val a = M[0][0]; val b = M[1][0]; val c = M[0][1]; val d = M[1][1]
            val u = lon - M[2][0]; val v = lat - M[2][1]; val det = a * d - b * c
            return doubleArrayOf((u * d - b * v) / det, (a * v - c * u) / det)
        }
        return doubleArrayOf(cx + (lon - GEO_LON) * 111_320.0 * Math.cos(Math.toRadians(GEO_LAT)), cy + (lat - GEO_LAT) * 111_132.0)
    }

    /** Outdoor walkable regions (the territory around the building, from OSM). */
    /** The ground floor (level 1 when the building has it; a basement may come first in the list). */
    val groundFi: Int get() = fi(1).takeIf { it >= 0 } ?: 0

    val outdoorRooms: Set<Int> by lazy {
        rooms.values.filter { q -> q.floor == floors[groundFi].n && (q.area > 100_000 || (q.nums + q.names).any { it.contains("Территория", true) || it.contains("территория вокруг", true) }) }
            .map { it.id }.toSet()
    }

    /**
     * x0, y0, x1, y1 of the building itself: the largest 4-connected component of floor-1 passable cells that are not
     * the territory (other buildings drawn from OSM are separate, small components).
     */
    val footprint: DoubleArray by lazy {
        val g = grids[groundFi]; val out = outdoorRooms; val n = rows * cols
        val seen = java.util.BitSet(n); val q = IntArray(n)
        var best = intArrayOf(0, 0, 0, 0, 0)    // size, r0, c0, r1, c1
        for (start in 0 until n) {
            if (seen.get(start)) continue
            val v0 = g[start].toInt() and 0xFFFF
            if (v0 == 0 || v0 in out) continue
            var head = 0; var tail = 0; q[tail++] = start; seen.set(start)
            var r0 = rows; var r1 = -1; var c0 = cols; var c1 = -1
            while (head < tail) {
                val cell = q[head++]; val r = cell / cols; val c = cell % cols
                if (r < r0) r0 = r; if (r > r1) r1 = r; if (c < c0) c0 = c; if (c > c1) c1 = c
                for (k in 0 until 4) {
                    val nr = r + (if (k == 0) -1 else if (k == 1) 1 else 0); val nc = c + (if (k == 2) -1 else if (k == 3) 1 else 0)
                    if (nr < 0 || nc < 0 || nr >= rows || nc >= cols) continue
                    val nc2 = nr * cols + nc
                    if (seen.get(nc2)) continue
                    val v = g[nc2].toInt() and 0xFFFF
                    if (v == 0 || v in out) continue
                    seen.set(nc2); q[tail++] = nc2
                }
            }
            if (tail > best[0]) best = intArrayOf(tail, r0, c0, r1, c1)
        }
        if (best[0] == 0) extent else doubleArrayOf(xOf(best[2]), yOf(best[3]), xOf(best[4]), yOf(best[1]))
    }

    /** Map heading (rad, clockwise from plan +y) of true north, from the geo affine; 0 without it. */
    val northHeading: Double by lazy {
        val M = planToLonLat ?: return@lazy 0.0
        val a = M[0][0]; val c = M[1][0]; val b = M[0][1]; val d = M[1][1]
        val det = a * d - c * b
        Math.atan2(-c / det, a / det)
    }

    /** Distance (m) from a plan point to the building footprint (0 inside it). */
    fun distToBuilding(x: Double, y: Double): Double {
        val bb = footprint
        val dx = maxOf(bb[0] - x, 0.0, x - bb[2]); val dy = maxOf(bb[1] - y, 0.0, y - bb[3])
        return Math.hypot(dx, dy)
    }

    /** x0, y0, x1, y1 of passable cells on floor 1 (the building footprint, plus outdoor paths once the grid has them). */
    val extent: DoubleArray by lazy {
        val g = grids[0]; var r0 = rows; var r1 = -1; var c0 = cols; var c1 = -1
        for (r in 0 until rows) for (c in 0 until cols) if (g[r * cols + c].toInt() != 0) { if (r < r0) r0 = r; if (r > r1) r1 = r; if (c < c0) c0 = c; if (c > c1) c1 = c }
        doubleArrayOf(xOf(c0), yOf(r1), xOf(c1), yOf(r0))
    }

    companion object {
        fun floorName(n: Int) = when (n) { 0 -> "Подвал"; 9 -> "Чердак"; else -> "$n этаж" }
        const val GEO_LAT = 43.41441
        const val GEO_LON = 39.95090
        val GREEK = mapOf("альфа" to "α", "бета" to "β", "гамма" to "γ", "дельта" to "δ", "эта" to "η")
        /** Greek symbol -> (Russian word, Latin letter, Cyrillic first letter) for search aliases. */
        val GREEK_ALIAS = mapOf("α" to listOf("альфа", "a", "а"), "β" to listOf("бета", "b", "б"), "γ" to listOf("гамма", "g", "г"),
            "δ" to listOf("дельта", "d", "д"), "η" to listOf("эта", "h", "э"))
        private val GREEK_RE = Regex("^(Альфа|Бета|Гамма|Дельта|Эта)\\s*(\\d+(?:\\.\\d+)*)(?=$|\\s|·)", RegexOption.IGNORE_CASE)
    }
}
