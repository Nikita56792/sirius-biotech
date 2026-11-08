package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/** Router + alternatives + maneuvers on the real building data. Writes build/validation/routes.txt. */
class RouterValidationTest {
    companion object {
        val assets = File("src/main/assets")
        val building by lazy { Building(FileAssets(assets)) }
        val router by lazy { Router(building) }
        val planner by lazy { Planner(building, router) }
        val search by lazy { Search(building) }
        fun out(name: String): File = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve(name)
    }

    private fun describe(r: Route): String {
        val sb = StringBuilder()
        val via = if (r.connSteps.isEmpty()) "без смены этажа" else r.connSteps.joinToString(" → ") { it.conn?.shortName ?: it.kind }
        sb.append(String.format("  [%s] %.0f с (%.1f мин), %.0f м пешком, %s\n", r.label, r.time, r.time / 60, r.walk, via))
        for (m in r.maneuvers) {
            sb.append(String.format("     %6.1f м  эт.%d  %-38s %s\n", m.s, m.floor, m.text + if (m.type.name.startsWith("T") || m.type.name.startsWith("H")) " (${m.angle.toInt()}°)" else "",
                if (m.hint.isNotEmpty()) "→ ${m.hint}" else ""))
        }
        return sb.toString()
    }

    @Test
    fun routesBetweenNamedRooms() {
        val sb = StringBuilder()
        sb.append("Routes on real map data (${building.rooms.size} rooms, ${building.conns.size} connectors, router states ${router.total})\n\n")
        val pairs = listOf(
            "1417" to "2415", "лифт 7а" to "2034", "1626" to "2295", "1489" to "2327", "1281" to "1514",
            "1340" to "2404", "1358" to "1311",
        )
        var n = 0
        for ((qa, qz) in pairs) {
            val a = search.query(qa).firstOrNull(); val z = search.query(qz).firstOrNull()
            if (a == null || z == null) { sb.append("not found: $qa / $qz\n"); continue }
            val pa = a.mk(); val pz = z.mk()
            val t0 = System.nanoTime()
            val rs = planner.plan(pa, pz)
            val ms = (System.nanoTime() - t0) / 1e6
            sb.append("${pa.title} (${pa.floor} эт.) → ${pz.title} (${pz.floor} эт.): ${rs.size} вариантов, ${"%.0f".format(ms)} мс\n")
            for (r in rs) sb.append(describe(r))
            sb.append('\n')
            if (rs.isNotEmpty()) n++
            out("routes.txt").writeText(sb.toString())
            for (r in rs) {
                assertTrue("route has start and finish", r.maneuvers.first().type == MType.START && r.maneuvers.last().type == MType.FINISH)
                if (r.legs.first().floor != r.legs.last().floor) assertTrue("floor change has a connector maneuver", r.maneuvers.any { it.type == MType.CONNECTOR })
            }
        }
        // accessible mode
        val a = search.query("1417").first().mk(); val z = search.query("2415").first().mk()
        val acc = planner.plan(a, z, accessible = true)
        sb.append("Маломобильный режим 1417 → 2415:\n"); acc.forEach { sb.append(describe(it)) }
        assertTrue(acc.all { r -> r.connSteps.all { it.kind == "lift" } })
        // closure: block the first connector of the best route, and a corridor point; the route must change
        val best = planner.plan(a, z).first()
        val c0 = best.connSteps.firstOrNull()?.conn
        if (c0 != null) {
            planner.addClosure(c0.floors.first(), c0.pos.getValue(c0.floors.first())[0], c0.pos.getValue(c0.floors.first())[1], 0.0, connId = c0.id, title = c0.name)
            val alt = planner.plan(a, z)
            sb.append("\nПерекрыто: ${c0.shortName}\n"); alt.forEach { sb.append(describe(it)) }
            assertTrue(alt.isEmpty() || alt.all { r -> r.connSteps.none { it.conn?.id == c0.id } })
            planner.clearClosures()
        }
        val mid = best.pointAt(best.legs.first().s1 * 0.5)
        val f = building.floorOf(mid.first)
        val (cx, cy, rad) = planner.closureAt(f, mid.second, mid.third)
        planner.addClosure(f, cx, cy, rad.first)
        val alt2 = planner.plan(a, z)
        sb.append(String.format("\nПерекрыт проход на %d эт. (%.1f, %.1f) r=%.1f м:\n", f, cx, cy, rad.first)); alt2.forEach { sb.append(describe(it)) }
        if (alt2.isNotEmpty()) assertTrue(alt2.first().project(mid.first, cx, cy)!!.dist > rad.first - 0.5)
        planner.clearClosures()
        val txt = sb.toString()
        println(txt)
        out("routes.txt").writeText(txt)
        assertTrue(n >= 5)
    }

    @Test
    fun searchKeywords() {
        val sb = StringBuilder()
        val qs = listOf("лифт 7а", "Эскалатор 31", "лестница 1", "2415", "переговор", "туалет 2 этаж", "ВХОД",
            "К20", "к19", "K20", "К0", "Альфа 5.4", "альфа", "Штаб пресс-центра", "пом. 13", "пом 13", "Росатом", "Зона рекреации",
            "Центр когнитивных", "Карпова", "Семёнов", "К25", "пом. 6",
            "1-07-06", "1 07 06", "IT-лаборатория", "1-07-01", "Холл", "Комната 3D", "1268", "1-18-07", "Муфельные", "1-04-02",
            "Архитектура и дизайн", "К2", "Коворкинг", "α5.4", "a5.4", "а5.4", "альфа 5.4", "1454", "α5.7", "КПП", "кпп 3", "проходная",
            "Ворота", "Парковка", "Шлагбаум")
        for (q in qs) {
            val r = search.query(q)
            sb.append("«$q» → ${r.take(4).joinToString(" | ") { it.title + " (" + it.floor + " эт.)" }}\n")
        }
        out("search.txt").writeText(sb.toString())
        for (q in qs) assertTrue("no results for $q", search.query(q).isNotEmpty())
        assertTrue(search.query("лифт 7а").first().title.startsWith("Лифт 7а"))
        assertTrue(search.query("лестница 1").first().title == "Лестница 1")
        assertTrue(search.query("К20").first().title.startsWith("К20"))
        assertTrue(search.query("K20").first().title.startsWith("К20"))
        assertTrue(search.query("к19").first().title.startsWith("К19"))
        assertTrue(search.query("К0").first().title.startsWith("К0"))
        assertTrue(search.query("Альфа 5.4").first().title.contains("Альфа 5.4"))
        // both the room named «Альфа 5.4» (1454) and a site label «Альфа 5.4» on a neighbouring room exist in the data
        for (q in listOf("α5.4", "a5.4", "а5.4", "альфа 5.4")) assertTrue(q, search.query(q).first().title.startsWith("α5.4 · Альфа 5.4"))
        // map 28.10 13:27: 1454 is «Альфа 5.3», «Альфа 5.4» is 1455 (site renames)
        assertTrue(search.query("1454").first().title == "α5.3 · Альфа 5.3 · 1454")
        assertTrue(search.query("1455").first().title == "α5.4 · Альфа 5.4 · 1455")
        assertTrue(search.query("α5.7").first().title.endsWith("1458"))
        // map 28.10 13:27: 1-07-06 is «Бета 5 · IT-лаборатория» -> «β5 · Бета 5 · IT-лаборатория · 1-07-06»
        for (q in listOf("1-07-06", "1 07 06")) assertTrue(q, search.query(q).first().title.let { it.contains("IT-лаборатория") && it.endsWith("1-07-06") })
        assertTrue(search.query("К0").first().title.startsWith("К0 · Архитектура и дизайн"))
        assertTrue(search.query("кпп 3").first().title.startsWith("КПП №3"))
        assertTrue(search.query("пом. 13").first().title.startsWith("пом. 13"))
        assertTrue(search.query("пом 13").first().title.startsWith("пом. 13"))
        // labels sharing the big hall region keep their own point
        val k25 = search.query("К25").first().mk(); val p6 = search.query("пом. 6").first().mk()
        sb.append(String.format("К25 at (%.1f, %.1f), пом. 6 at (%.1f, %.1f), same region id %d/%d\n", k25.x, k25.y, p6.x, p6.y, k25.roomId, p6.roomId))
        assertTrue(hypot(k25.x - p6.x, k25.y - p6.y) > 3.0)
        val txt = sb.toString(); println(txt); out("search.txt").writeText(txt)
    }

    /** Room changes along a path on the same floor, and how many of them happen at a door (≤ 2 m, door joining the two rooms). */
    private fun doorCheck(r: Route, inRooms: Set<Int>): Pair<Int, List<String>> {
        val n = building.n; val cols = building.cols
        var ok = 0; val bad = ArrayList<String>()
        for (i in 1 until r.states.size) {
            val a = r.states[i - 1]; val z = r.states[i]
            val fa = a / n; val fz = z / n
            if (fa != fz) continue
            val ca = a % n; val cz = z % n
            val ra = building.roomIdCell(fa, ca / cols, ca % cols); val rz = building.roomIdCell(fz, cz / cols, cz % cols)
            if (ra == rz || (ra !in inRooms && rz !in inRooms)) continue
            val x = building.xOf(cz % cols); val y = building.yOf(cz / cols); val fl = building.floorOf(fz)
            // a door joining these two rooms within 4 m (door strips along niches can be several metres long)
            val d = building.doors.filter { it.floor == fl && hypot(it.x - x, it.y - y) <= 4.0 && it.rooms.contains(ra) && it.rooms.contains(rz) }
            // (the tambour door to the courtyard at (329, 180), drawn as arcs only, is registered since 30.10 — from_notes door_line)
            if (d.isNotEmpty()) ok++ else bad.add(String.format("%d→%d at (%.1f, %.1f)", ra, rz, x, y))
        }
        return ok to bad
    }

    @Test
    fun finalMap2022() {
        val sb = StringBuilder("Final map (2022 plans + site renames)\n\n")
        fun place(q: String) = search.query(q).first().mk()
        val press = building.rooms.values.filter { q -> q.floor == 2 && q.labels.indices.any { i -> val p = q.labels[i]
            p[0] in 0.0..89.0 && p[1] in -4.0..89.0 && (q.nums.getOrNull(i)?.let { it.startsWith("пом.") || it.startsWith("К") } == true) } }
            .filter { it.area < 1500 }.map { it.id }.toSet()
        sb.append("press-centre rooms (separate regions): ${press.size}\n")
        for ((qa, qz) in listOf("К0" to "К20", "Альфа 5.4" to "К19", "Зона рекреации" to "Штаб пресс-центра", "пом. 13" to "К20")) {
            val a = place(qa); val z = place(qz)
            val rs = planner.plan(a, z)
            sb.append("${a.title} (${a.floor} эт.) → ${z.title} (${z.floor} эт.): ${rs.size} вариантов\n")
            assertTrue("no route $qa -> $qz", rs.isNotEmpty())
            for (r in rs) {
                sb.append(describe(r))
                val (ok, bad) = doorCheck(r, press)
                sb.append("     press-centre room changes: $ok through doors, ${bad.size} without a door ${bad.take(3)}\n")
                assertTrue("route into the press centre crosses a wall: $bad", bad.isEmpty())
            }
            sb.append('\n')
        }
        // outdoor territory: building <-> КПП / parking, only through real entrances
        val outdoor = building.outdoorRooms
        sb.append("\nOutdoor territory regions: $outdoor; building footprint ${building.footprint.joinToString { "%.0f".format(it) }}\n")
        for (from in listOf("К20", "α5.4")) for (to in listOf("КПП №2", "КПП №3", "Парковка")) for (rev in listOf(false, true)) {
            val a = place(if (rev) to else from); val z = place(if (rev) from else to)
            val t0 = System.nanoTime()
            val rs = planner.plan(a, z)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("no route ${a.title} -> ${z.title}", rs.isNotEmpty())
            val r = rs.first()
            val (ok, bad) = doorCheck(r, outdoor)
            sb.append(String.format("%s → %s: %d вариантов, %.0f мс; [%s] %.0f м, %.1f мин, %s; вход/выход через двери: %d, без двери: %d %s\n",
                a.title, z.title, rs.size, ms, r.label, r.walk, r.time / 60, if (r.connSteps.isEmpty()) "без смены этажа" else r.connSteps.joinToString(" → ") { it.conn?.shortName ?: it.kind },
                ok, bad.size, bad.take(2)))
            assertTrue("outdoor transition without a door: $bad", bad.isEmpty())
            // which building room the route leaves/enters the territory from
            val n = building.n; val cols = building.cols
            val exits = (1 until r.states.size).mapNotNull { i -> val a = r.states[i - 1]; val z = r.states[i]
                if (a / n != z / n) null else { val ra = building.roomIdCell(a / n, (a % n) / cols, (a % n) % cols); val rz = building.roomIdCell(z / n, (z % n) / cols, (z % n) % cols)
                    if ((ra in outdoor) != (rz in outdoor)) building.rooms[if (ra in outdoor) rz else ra]?.let { building.roomTitle(it) } else null } }
            sb.append("     граница здание/улица: ${exits.distinct().joinToString()}\n")
        }
        // Rosatom hall: furniture lines exist in floor2.bin (drawn only), routing unaffected
        val furn = File(assets, "map/floor2.bin").readBytes().let { String(it, 4, java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(0)) }
        sb.append("floor2.bin arrays: ${Regex("\"k\": ?\"(\\w+)\"").findAll(furn).map { it.groupValues[1] }.toList()}\n")
        val txt = sb.toString(); println(txt); out("final_map.txt").writeText(txt)
    }
}
