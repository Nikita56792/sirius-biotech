package com.gmcnav.navi.core

import kotlin.math.hypot

/** A start/destination: room, number inside a big space, connector, entrance, zone, map point or "my location". */
class Place(
    val kind: String, val title: String, val sub: String, val floor: Int, val x: Double, val y: Double,
    val goals: List<GridPt>, val roomId: Int = 0, val connId: Int = 0, val approx: Boolean = false,
) {
    val isMe get() = kind == "me"
    override fun toString() = title
}

class SearchItem(val title: String, val sub: String, val key: String, val rank: Int, val floor: Int, val x: Double, val y: Double,
                 val cat: String, val service: Boolean = false, val mk: () -> Place)

/**
 * Keyword search over rooms (every number of merged spaces at its own label point), names, functions, zones,
 * connectors («Лифт 7а», «Эскалатор 31/32», «Лестница 1»), entrances. Case/«ё»-insensitive, partial words,
 * a few synonyms (туалет = санузел, кафе = питание…). Port of the web navigator's index.
 */
class Search(private val b: Building) {
    val items = ArrayList<SearchItem>()

    private fun norm(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[«»\"()·,]"), " ")
        .replace(Regex("\\b([a-z]{1,2})(\\d)")) { m -> m.groupValues[1].map { LAT[it] ?: it }.joinToString("") + m.groupValues[2] }

    private val LAT = mapOf('a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к', 'm' to 'м', 'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у')

    fun placeFromRoom(q: Room, cellOverride: Int = -1): Place {
        val fi = b.fi(q.floor)
        var cell = cellOverride
        if (cell < 0 && q.anchorR >= 0) cell = q.anchorR * b.cols + q.anchorC
        if (cell < 0 || !b.passable(fi, cell / b.cols, cell % b.cols)) cell = b.snap(fi, b.row(q.poleY), b.col(q.poleX), 40)
        val x = b.xOf(cell % b.cols); val y = b.yOf(cell / b.cols)
        val sub = Building.floorName(q.floor) + (if (q.area > 0) " · ${q.area.toInt()} м²" else "") + (q.zone?.let { " · зона $it" } ?: "")
        return Place("room", b.roomTitle(q), sub, q.floor, x, y, listOf(GridPt(fi, cell)), roomId = q.id, approx = !q.reach)
    }

    fun placeFromRoomNum(q: Room, i: Int): Place {
        val fi = b.fi(q.floor)
        val p = q.labels[i]
        val cell = b.snap(fi, b.row(p[1]), b.col(p[0]), 40, q.id).let { if (it < 0) b.snap(fi, b.row(p[1]), b.col(p[0]), 40) else it }
        val name = q.names.getOrElse(i) { "" }
        return Place("room", b.labelTitle(q.nums[i], name), Building.floorName(q.floor) + (q.zone?.let { " · зона $it" } ?: ""),
            q.floor, b.xOf(cell % b.cols), b.yOf(cell / b.cols), listOf(GridPt(fi, cell)), roomId = q.id, approx = !q.reach)
    }

    fun placeFromConn(c: Connector): Place {
        val f0 = c.floors.first(); val p = c.pos.getValue(f0)
        val goals = c.floors.mapNotNull { f -> c.pos[f]?.let { pp -> val fi = b.fi(f); b.snap(fi, pp[2].toInt(), pp[3].toInt(), 12).takeIf { it >= 0 }?.let { GridPt(fi, it) } } }
        return Place("conn", c.shortName, c.floors.joinToString("–") + " этажи" + (c.near?.let { " · у $it" } ?: ""), f0, p[0], p[1], goals, connId = c.id)
    }

    fun placeFromPoint(floor: Int, x: Double, y: Double, title: String? = null): Place? {
        val fi = b.fi(floor)
        val cell = b.snap(fi, b.row(y), b.col(x), 30)
        if (cell < 0) return null
        val q = b.rooms[b.roomIdCell(fi, cell / b.cols, cell % b.cols)]
        val px = b.xOf(cell % b.cols); val py = b.yOf(cell / b.cols)
        val t = title ?: q?.let { b.roomTitleAt(it, px, py) }?.takeIf { it.isNotEmpty() } ?: "Точка на карте"
        return Place("point", t, Building.floorName(floor) + " · точка на карте", floor, px, py, listOf(GridPt(fi, cell)), roomId = q?.id ?: 0)
    }

    /** A building people live in (has living rooms): its kitchens are the residents' ones, not back of house. */
    private val residential = b.rooms.values.any { q -> q.names.any { it.contains("Жилая комната", true) } }

    private fun build() {
        // exterior doors, and (since the territory is part of the ground floor grid) doors between the territory and the building
        val out = b.outdoorRooms
        // that do not lead into service / technical rooms (substations, loading docks, waste rooms…)
        val entrances = b.doors.filter { d -> d.ext || (d.rooms.any { it in out } && d.rooms.any { it != 0 && it !in out }) }.filter { d ->
            d.rooms.filter { it != 0 && it !in out }.mapNotNull { b.rooms[it] }.none { q -> Categories.isTechnical(b.roomTitleAt(q, d.x, d.y), residential) }
        }.fold(ArrayList<Door>()) { kept, d -> if (kept.none { it.floor == d.floor && hypot(it.x - d.x, it.y - d.y) < 8.0 }) kept.add(d); kept }   // double doors: one entry
        for (q in b.rooms.values) {
            if (q.void) continue
            if (q.nums.size >= 2) {
                for (i in q.nums.indices) {
                    if (i >= q.labels.size) continue
                    val name = q.names.getOrElse(i) { "" }
                    val key = norm("${q.nums[i]} $name ${alias(name)} ${alias(q.nums[i])} ${if (q.id in b.outdoorRooms) "улица территория снаружи" else q.func.joinToString(" ")} ${q.zone?.let { "зона $it" } ?: ""}")
                    val p = q.labels[i]
                    // category / service by this number's own label (not by the functions of the whole merged space)
                    items.add(SearchItem(b.labelTitle(q.nums[i], name),
                        Building.floorName(q.floor) + (q.zone?.let { " · зона $it" } ?: "") + " · в общем пространстве ${q.area.toInt()} м²" + if (q.reach) "" else " · вход не распознан",
                        key, 1, q.floor, p[0], p[1], Categories.of(label(q.nums[i], name), residential), Categories.isService(label(q.nums[i], name), residential)) { placeFromRoomNum(q, i) })
                }
            } else {
                if (q.nums.isEmpty() && q.func.isEmpty() && q.area < 300) continue
                val key = norm("${q.nums.joinToString(" ")} ${q.names.joinToString(" ")} ${q.names.joinToString(" ") { alias(it) }} ${q.nums.joinToString(" ") { alias(it) }} ${q.func.joinToString(" ")} ${q.zone?.let { "зона $it" } ?: ""}")
                // what the room is: its name; the function only when it has no name
                val what = if (q.names.any { it.isNotBlank() }) label(q.nums.joinToString(" "), q.names.joinToString(" · ")) else label(q.nums.joinToString(" "), q.func.joinToString(" · "))
                items.add(SearchItem(b.roomTitle(q), Building.floorName(q.floor) + " · ${q.area.toInt()} м²" + (q.zone?.let { " · зона $it" } ?: "") +
                    (q.func.firstOrNull()?.let { " · $it" } ?: "") + if (q.reach) "" else " · вход не распознан",
                    key, if (q.nums.isNotEmpty()) 1 else 2, q.floor, q.poleX, q.poleY, Categories.of(what, residential), Categories.isService(what, residential)) { placeFromRoom(q) })
            }
        }
        val sameName = b.conns.groupingBy { it.shortName }.eachCount()
        for (c in b.conns) {
            val p = c.pos.getValue(c.floors.first())
            // several connectors with one name (Рекордов 15: «Лифт» ×4): tell them apart by the section / area they are in
            val where = if ((sameName[c.shortName] ?: 0) > 1 && c.near == null) connArea(c)?.let { " · $it" } ?: "" else ""
            items.add(SearchItem(c.shortName, c.floors.joinToString("–") + " этажи" + (c.near?.let { " · у $it" } ?: "") + where,
                norm("${c.name} ${c.kindRu} ${c.kind}$where"), 3, c.floors.first(), p[0], p[1], c.kind) { placeFromConn(c) })
        }
        entrances.forEachIndexed { i, d ->
            val q = d.rooms.toList().filter { it !in out }.mapNotNull { b.rooms[it] }.let { rs -> rs.firstOrNull { it.nums.isNotEmpty() } ?: rs.firstOrNull() }
                ?: b.rooms[d.rooms.firstOrNull() ?: 0]
            val t = if (d.floor == 1) "Вход / выход №${i + 1}" else "Выход наружу №${i + 1} (${d.floor} эт.)"
            items.add(SearchItem(t, Building.floorName(d.floor) + (q?.let { " · у ${b.roomTitle(it)}" } ?: ""), norm("$t вход выход улица"), 3,
                d.floor, d.x, d.y, "entrance") {
                placeFromPoint(d.floor, d.x, d.y, t) ?: Place("door", t, "", d.floor, d.x, d.y, emptyList())
            })
        }
        // zones and functional areas: several rooms as goals
        val zones = LinkedHashMap<String, Triple<String, Int, ArrayList<Room>>>()
        for (q in b.rooms.values) {
            if (q.void) continue
            val keys = ArrayList<Pair<String, String>>()
            q.zone?.let { keys.add("зона $it (${q.floor} эт.)" to "Зона $it") }
            for (f in q.func) keys.add("$f (${q.floor} эт.)" to f.replaceFirstChar { it.uppercase() })
            for ((k, t) in keys) zones.getOrPut(k) { Triple(t, q.floor, ArrayList()) }.third.add(q)
        }
        for ((k, z) in zones) {
            val (t, fl, rs) = z
            val q0 = rs.first()
            items.add(SearchItem(t, "зона · ${Building.floorName(fl)} · ${rs.size} ${plural(rs.size, "помещение", "помещения", "помещений")}",
                norm(k), 0, fl, q0.poleX, q0.poleY, "", Categories.isService(t, residential)) {
                val goals = rs.filter { it.reach && it.anchorR >= 0 }.map {
                    val fi = b.fi(it.floor)
                    val c = if (it.area > 1500 || it.nums.size >= 2) b.snap(fi, b.row(it.poleY), b.col(it.poleX), 40, it.id) else -1
                    GridPt(fi, if (c >= 0) c else it.anchorR * b.cols + it.anchorC)
                }
                Place("zone", t, "зона · ${Building.floorName(fl)}", fl, q0.poleX, q0.poleY, goals.ifEmpty { placeFromRoom(q0).goals })
            })
        }
    }

    /** Extra search keys: «Бета 4.1» is also «β4.1», «b4.1», «б4.1», «бета4.1» (likewise α/a/а, γ/g/г, δ/d/д, η/h/э). */
    private fun alias(name: String): String = b.alphaCode(name)?.let { c ->
        val sym = c.take(1); val v = c.drop(1)
        (listOf(sym) + (Building.GREEK_ALIAS[sym] ?: emptyList())).joinToString(" ") { "$it$v" }
    } ?: ""

    /** What a label says: its name, plus the «number» when that is itself a name («Электрощитовая 0.4 кВ», «Альфа 5.4»). */
    private fun label(num: String, name: String): String = (if (num.any { it.isLetter() } && !num.startsWith("пом")) "$num " else "") + name

    /** Section / zone of the room at a connector (its first floor), e.g. «секция 2». */
    private fun connArea(c: Connector): String? {
        val f = c.floors.first(); val p = c.pos[f] ?: return null; val fi = b.fi(f)
        val cell = b.snap(fi, p[2].toInt(), p[3].toInt(), 12).takeIf { it >= 0 } ?: return null
        val q = b.rooms[b.roomIdCell(fi, cell / b.cols, cell % b.cols)] ?: return null
        return q.zone?.let { "зона $it" } ?: q.func.firstOrNull { it.isNotBlank() }
    }

    private val SYN = listOf(
        listOf("туалет", "санузел", "сан.узел", "сан узел", "уборн", "wc", "с/у"),
        listOf("кафе", "питани", "буфет", "столов", "ресторан", "обеден", "еда", "кофе"),
        listOf("вход", "выход", "ворота", "шлагбаум"),
        listOf("кпп", "проходн", "контрольно-пропускн"),
        listOf("парковк", "стоянк", "parking"),
        listOf("лифт", "lift"),
        listOf("лестниц", "stair"),
        listOf("эскалатор", "escalator"),
        listOf("аудитор", "зал", "лекц"),
    )

    private fun wordMatches(key: String, w: String): Boolean {
        if (key.contains(w)) return true
        for (g in SYN) if (g.any { w.startsWith(it) || it.startsWith(w) && w.length >= 3 }) if (g.any { key.contains(it) }) return true
        return false
    }

    /** Results for a query; with a known position they are ordered by relevance, then distance. */
    fun query(qs: String, floor: Int = 0, x: Double = Double.NaN, y: Double = Double.NaN, limit: Int = 40): List<SearchItem> {
        var q = norm(qs).trim().replace(Regex("(^|\\s)пом\\.?\\s*(\\d)"), "$1пом. $2")
            .replace(Regex("(^|\\s)(\\d)[\\s.-](\\d{2})[\\s.-](\\d{2})(?=\\s|$)"), "$1$2-$3-$4")   // «1 07 06» -> 1-07-06
        // "2 этаж", "этаж 2", "2 эт" -> floor filter
        var floorFilter = 0
        Regex("(\\d)\\s*(этаж\\w*|эт\\.?)(?=\\s|$)|(этаж\\w*|эт\\.?)\\s*(\\d)(?=\\s|$)").find(q)?.let { m ->
            floorFilter = (m.groupValues[1].ifEmpty { m.groupValues[4] }).toInt()
            q = q.removeRange(m.range).trim()
        }
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("\\s+"))
        val res = ArrayList<Pair<Double, SearchItem>>()
        for (it in items) {
            if (floorFilter != 0 && it.floor != floorFilter) continue
            val tl = norm(it.title)
            val k = it.key + " " + tl
            if (!words.all { w -> wordMatches(k, w) }) continue
            var score = it.rank.toDouble()
            val toks = tl.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val num = toks.firstOrNull() ?: ""
            // service / technical rooms are not suggested by words; the exact room number still finds them
            if (it.service && !(q.any(Char::isDigit) && (num == q || tl.trim() == q || tl.startsWith("$q ")))) continue
            if (num == q) score -= 10 else if (num.startsWith(q)) score -= 5
            if (tl.trim() == q) score -= 8
            if (tl.startsWith(q)) score -= 2
            for (w in words) { if (toks.contains(w)) score -= 1.5; if (toks.any { t -> t.startsWith(w) }) score -= 1.0 }
            if (!x.isNaN()) score += dist(it, floor, x, y) / 400.0
            res.add(score to it)
        }
        res.sortWith(compareBy<Pair<Double, SearchItem>> { it.first }.thenBy { it.second.title })
        return res.take(limit).map { it.second }
    }

    /** Category chip: nearest first. */
    fun category(cat: String, floor: Int = 0, x: Double = Double.NaN, y: Double = Double.NaN, limit: Int = 40): List<SearchItem> {
        val l0 = items.filter { it.cat == cat && it.rank > 0 }
        // a room listed twice (a number of a merged space and the same room on its own): once; connectors / entrances all
        val l = if (cat in setOf("lift", "stair", "escalator", "entrance")) l0 else l0.distinctBy { it.title to it.floor }
        return (if (x.isNaN()) l.sortedBy { it.title } else l.sortedBy { dist(it, floor, x, y) }).take(limit)
    }

    fun dist(it: SearchItem, floor: Int, x: Double, y: Double): Double =
        hypot(it.x - x, it.y - y) + kotlin.math.abs(it.floor - floor) * 40.0

    init { build() }

    companion object {
        fun plural(n: Int, a: String, b: String, c: String): String {
            val m = n % 10; val h = n % 100
            return if (m == 1 && h != 11) a else if (m in 2..4 && (h < 10 || h >= 20)) b else c
        }
    }
}
