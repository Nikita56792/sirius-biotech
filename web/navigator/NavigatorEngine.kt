package com.gmcnav.navi.core

/** JSON boundary shared by browser and JVM parity checks. No routing implementation here. */
class NavigatorEngine(val b: Building) {
    private val router = Router(b)
    private val planner = Planner(b, router)
    private val search = Search(b).apply { items.removeAll { it.floor != 2 } }
    private val places = HashMap<String, Place>()
    private var routes = emptyList<Route>()
    private var guidance: Guidance? = null
    private var voice = VoicePlanner()
    private var pointId = 0

    fun command(json: String): String {
        val m = Json.parse(json).obj()
        val result: Any? = when (m["type"].str()) {
            "info" -> mapOf("title" to b.title, "northHeading" to b.northHeading, "footprint" to b.footprint.toList(),
                "categories" to Categories.CHIPS.filter { search.category(it.first, limit = 1).isNotEmpty() }.map { listOf(it.first, it.second) },
                "access" to b.access.map { a -> mapOf("id" to a.id, "floor" to a.floor, "level" to a.levelAt(System.currentTimeMillis()),
                    "xs" to a.xs.toList(), "ys" to a.ys.toList(), "note" to a.note(),
                    "doors" to a.doors.map { listOf(it.x, it.y) }) })
            "search" -> {
                val items = if (m["category"] is String) search.category(m["category"].str()) else search.query(m["query"] as? String ?: "")
                items.map { item -> mapOf("key" to item.key, "title" to item.title, "sub" to item.sub,
                    "floor" to item.floor, "x" to item.x, "y" to item.y, "cat" to item.cat) }
            }
            "pick" -> {
                val key = m["key"].str()
                val place = places[key] ?: search.items.firstOrNull { it.key == key }?.mk?.invoke()
                place?.let { places[key] = it; placeMap(key, it) }
            }
            "point" -> {
                val x = m["x"].dbl(); val y = m["y"].dbl(); val floor = (m["floor"] as? Number)?.toInt() ?: 2
                val room = b.rooms[b.roomIdAt(b.fi(floor), x, y)]
                val conn = if (m["preferPoint"] == true) null else b.connNear(floor, x, y, 3.0)
                val place = if (m["long"] != true && room == null) null
                    else if (conn != null) search.placeFromConn(conn) else search.placeFromPoint(floor, x, y)
                place?.let { val key = "point:${++pointId}"; places[key] = it; placeMap(key, it) }
            }
            "plan" -> {
                router.userLevel = (m["level"] as? Number)?.toInt() ?: 1
                (m["now"] as? Number)?.toLong()?.let { now -> planner.clock = { now } }
                val a = resolve(m["from"].str()); val z = resolve(m["to"].str())
                routes = planner.plan(a, z, m["accessible"] == true)
                routes.map { routeMap(it, m["debug"] == true) }
            }
            "closure" -> {
                val floor = (m["floor"] as? Number)?.toInt() ?: 2
                val (x, y, rc) = planner.closureAt(floor, m["x"].dbl(), m["y"].dbl())
                val conn = rc.second
                planner.addClosure(floor, x, y, rc.first, conn?.id ?: 0,
                    conn?.let { "${it.shortName}: не работает" } ?: "Проход перекрыт")
                closureMaps()
            }
            "removeClosure" -> {
                val id = (m["id"] as? Number)?.toInt()
                if (id == null) planner.clearClosures() else planner.removeClosure(id)
                closureMaps()
            }
            "closures" -> closureMaps()
            "voice" -> {
                voice.mode = VoiceMode.values()[(m["mode"] as Number).toInt().coerceIn(0, 2)]
                emptyList<Any>()
            }
            "voiceInit" -> {
                val variants = VoiceVariants(m["text"].str(), (m["seed"] as Number).toLong())
                (m["rareDays"] as? Map<*, *>)?.forEach { (key, value) -> variants.rareDays[key as String] = value as String }
                voice.variants = variants
                Phrases.hasRec = { key -> key in m["recordings"].strs() }
                emptyList<Any>()
            }
            "start" -> {
                val route = routes[(m["index"] as Number).toInt()]
                guidance = Guidance(b, route)
                mapOf("prompts" to voice.onStart(route, nanos(m)).map(::promptMap), "rareDays" to voice.variants?.rareDays)
            }
            "stepVoice" -> {
                val route = routes[(m["index"] as Number).toInt()]
                val maneuver = route.maneuvers[(m["step"] as Number).toInt()]
                if (voice.mode == VoiceMode.OFF) emptyList<Any>() else listOf(promptMap(Phrases.prompt(Phrases.action(maneuver), "manual", true)))
            }
            "stop" -> { guidance = null; routes = emptyList(); emptyList<Any>() }
            else -> error("Unknown navigation command")
        }
        return navigationJson(result)
    }

    private fun nanos(m: Map<String, Any?>): Long = (m["timeMs"] as? Number)?.toDouble()?.times(1e6)?.toLong() ?: 1L
    private fun resolve(key: String): Place = places[key] ?: search.items.first { it.key == key }.mk().also { places[key] = it }
    private fun placeMap(key: String, p: Place): Map<String, Any?> = mapOf("key" to key, "kind" to p.kind, "title" to p.title,
        "sub" to p.sub, "floor" to p.floor, "x" to p.x, "y" to p.y, "approx" to p.approx,
        "roomId" to p.roomId, "connId" to p.connId)
    private fun closureMaps() = planner.closures.map { c -> mapOf("id" to c.id, "floor" to c.floor, "x" to c.x, "y" to c.y,
        "radius" to c.radius, "connId" to c.connId, "title" to c.title) }
    private fun promptMap(p: Prompt) = mapOf("text" to p.text, "tag" to p.tag, "urgent" to p.urgent,
        "parts" to p.parts.map { mapOf("key" to it.key, "file" to it.file) })
    private fun routeMap(r: Route, debug: Boolean): Map<String, Any?> = mapOf(
        "label" to r.label, "time" to r.time, "walk" to r.walk, "length" to r.length, "cost" to r.cost,
        "unreachable" to r.unreachable, "accessStop" to r.accessStop?.let { mapOf("id" to it.id, "note" to it.note()) },
        "startTitle" to r.startTitle, "goalTitle" to r.goalTitle,
        "legs" to r.legs.map { l -> mapOf("floor" to l.floor, "xs" to l.xs.toList(), "ys" to l.ys.toList(), "ss" to l.ss.toList()) },
        "connSteps" to r.connSteps.map { c -> mapOf("id" to c.conn?.id, "kind" to c.kind, "name" to c.conn?.shortName,
            "fromFloor" to b.floorOf(c.fromFi), "toFloor" to b.floorOf(c.toFi), "s" to c.s, "cost" to c.cost) },
        "maneuvers" to r.maneuvers.map { m -> mapOf("type" to m.type.name, "floor" to m.floor, "x" to m.x, "y" to m.y,
            "s" to m.s, "icon" to m.icon, "text" to m.text, "hint" to m.hint, "toFloor" to m.toFloor,
            "conn" to m.conn?.shortName) },
        "states" to if (debug) r.states.toList() else null)
}

fun navigationJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> jsonStr(value)
    is Double -> if (value.isFinite()) value.toString() else "null"
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { jsonStr(it.key.toString()) + ":" + navigationJson(it.value) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { navigationJson(it) }
    else -> error("Unsupported JSON value")
}
