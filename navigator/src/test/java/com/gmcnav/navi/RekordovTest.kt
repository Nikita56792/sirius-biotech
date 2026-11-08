package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.hypot

/**
 * Рекордов 15 final assets (calibration 1.8.4): doors, СКУД (one-way street exits, balcony doors, server room), basement
 * reached only from the street, door-aware routes and search. Report: build/validation/rekordov15.md
 */
class RekordovTest {
    private val assets = File("src/main/assets")
    private val b by lazy { Building(PrefixAssets(FileAssets(assets), "sites/rekordov15/")) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }
    private val sb = StringBuilder("# Рекордов 15 (assets 1.8.4)\n\n")
    private var ok = true
    private fun check(n: String, c: Boolean, info: String = "") { sb.append("- ${if (c) "OK" else "FAIL"} $n${if (info.isNotEmpty()) " — $info" else ""}\n"); ok = ok && c }

    private val outdoor by lazy { b.outdoorRooms }
    private fun describe(r: Route) = String.format(Locale.US, "%s, %.0f m, %s%s", r.label, r.length,
        r.connSteps.joinToString(" → ") { it.conn?.name ?: it.kind }.ifEmpty { "один этаж" }, r.accessStop?.let { " (stop: ${it.note()})" } ?: "")
    /** Does the route go through the territory (outside) on floor 1? */
    private fun goesOutside(r: Route): Boolean = r.states.any { st -> val f = st / b.n; val c = st % b.n
        b.floorOf(f) == 1 && b.roomIdCell(f, c / b.cols, c % b.cols) in outdoor }

    @Test
    fun rekordov() {
        // ---------------------------------------------------------------- data
        val oneWay = b.access.filter { it.exitDir != null }
        sb.append("Doors ${b.doors.size}, access lines ${b.access.size} (one-way exits ${oneWay.size}), access rooms ${b.accessRooms.map { "${it.name} (floor ${it.floor})" }}, " +
            "connectors ${b.conns.size}: ${b.conns.joinToString { "${it.name} ${it.floors.joinToString("")}" }}\n\n")
        check("doors ≥ 425", b.doors.size >= 425, "${b.doors.size}")
        check("13 access lines incl. 4 one-way street exits on floor 1", b.access.size == 13 && oneWay.size == 4 && oneWay.all { it.floor == 1 })
        check("server room on floor 4 is an access room", b.accessRooms.any { it.floor == 4 && it.name.contains("Серверная") })
        check("basement reached only by the street entrances (0 ↔ 1)", b.conns.filter { 0 in it.floors }.let { l -> l.size == 4 && l.all { it.floors == listOf(0, 1) } })

        // ---------------------------------------------------------------- basement: from the street, and up to floor 2
        val street = b.rooms.values.first { it.id in outdoor && it.floor == 1 }.let { q ->
            // a territory cell near the middle of the building's south-west side
            planner.placeFromFix(1, q.poleX, q.poleY) ?: search.placeFromRoom(q) }
        val base = b.rooms.values.filter { it.floor == 0 && it.area > 10 && it.id !in outdoor }.maxByOrNull { it.area }!!
        val pBase = search.placeFromRoom(base)
        for (lvl in listOf(1, 2)) {
            router.userLevel = lvl; router.rebuildAccess()
            val r1 = planner.plan(street, pBase).first()
            sb.append("  - pass $lvl: street → basement (${b.roomTitle(base)}): ${describe(r1)}\n")
            check("pass $lvl: street → basement through a street entrance", !r1.unreachable && r1.accessStop == null &&
                r1.connSteps.any { it.conn?.name?.startsWith("Вход в подвал") == true })
            val r205 = search.query("205").first().mk()
            val r2 = planner.plan(pBase, r205).first()
            sb.append("  - pass $lvl: basement → 205: ${describe(r2)}; outside: ${goesOutside(r2)}\n")
            check("pass $lvl: basement → floor 2 found via a basement entrance", !r2.unreachable && r2.connSteps.any { it.conn?.name?.startsWith("Вход в подвал") == true })
            if (!goesOutside(r2)) sb.append("  - DATA: this route does not pass the street — a «Вход в подвал с улицы» connector ends inside the building on floor 1\n")
        }
        router.userLevel = 1; router.rebuildAccess()

        // ---------------------------------------------------------------- one-way street doors
        for (ow in oneWay) {
            val e = ow.exitDir!!
            val mx = (ow.xs.first() + ow.xs.last()) / 2; val my = (ow.ys.first() + ow.ys.last()) / 2
            val inside = planner.placeFromFix(1, mx - 2.0 * e[0], my - 2.0 * e[1]); val out = planner.placeFromFix(1, mx + 2.0 * e[0], my + 2.0 * e[1])
            if (inside == null || out == null) { check("one-way ${ow.id}: probe points", false); continue }
            fun crosses(r: Route) = r.states.any { st -> router.accessLineAt(st / b.n, st % b.n)?.id == ow.id }
            val ex = planner.plan(inside, out).first(); val en = planner.plan(out, inside).first()
            sb.append(String.format(Locale.US, "  - «%s»: inside → street %s, crosses %s | street → inside %s, crosses %s\n", ow.name, describe(ex), crosses(ex), describe(en), crosses(en)))
            check("one-way ${ow.id}: exit through it is free", ex.accessStop == null && crosses(ex) && ex.length < 12)
            check("one-way ${ow.id}: entry without a pass never crosses the line", !crosses(en))
            if (en.accessStop == null && en.length <= ex.length + 5)
                sb.append(String.format(Locale.US, "  - DATA: around line %d «%s» (%.1f, %.1f) there is an opening the line does not cover — %.0f m in without crossing it\n",
                    ow.id, ow.name, mx, my, en.length))
        }

        // ---------------------------------------------------------------- door-aware routes and search
        var viaDoor = 0; var total = 0
        val start = search.query("205").first().mk()
        for (q in listOf("209", "Прачечная", "Гладильная", "Кухня", "Холл", "405", "Коворкинг", "101")) {
            val z = search.query(q).firstOrNull()?.mk?.invoke() ?: continue
            val r = planner.plan(start, z).firstOrNull() ?: continue
            if (r.accessStop != null || r.unreachable) continue
            // entering the destination room: the step into it happens within 2 m of a door of that room
            val n = b.n; var enter: Pair<Double, Double>? = null
            val goalRoom = r.states.last().let { st -> b.roomIdCell(st / n, (st % n) / b.cols, (st % n) % b.cols) }
            for (i in 1 until r.states.size) { val a = r.states[i - 1]; val c = r.states[i]
                val ra = b.roomIdCell(a / n, (a % n) / b.cols, (a % n) % b.cols); val rc = b.roomIdCell(c / n, (c % n) / b.cols, (c % n) % b.cols)
                if (rc == goalRoom && ra != goalRoom) enter = b.xOf((c % n) % b.cols) to b.yOf((c % n) / b.cols) }
            val fl = b.floorOf(r.legs.last().fi)
            // open zones (холл, кухня, рекреация, коридор) have no doors to their corridors by design
            val open = b.rooms[goalRoom]?.names?.any { Regex("(?i)холл|кухня|рекреац|коридор").containsMatchIn(it) } == true
            val dd = enter?.let { e -> b.doors.filter { d -> d.floor == fl && goalRoom in d.rooms.toList() }.minOfOrNull { d -> hypot(d.x - e.first, d.y - e.second) } }
            val near = enter == null || (dd != null && dd < 2.5)
            if (!open) { total++; if (near) viaDoor++ }
            sb.append("  - 205 → ${z.title}: ${describe(r)}; " + when {
                enter == null -> "starts inside"
                open -> "open zone (no door by design)"
                near -> "enters through its door"
                else -> String.format(Locale.US, "enters at (%.1f, %.1f), %.1f m from its nearest door — an opening without a door in the grid", enter.first, enter.second, dd ?: -1.0)
            } + "\n")
        }
        sb.append("  - ${if (viaDoor == total) "OK" else "DATA"} rooms with doors are entered through a door: $viaDoor/$total (an entry elsewhere = an opening without a door in the map)\n")
        val entrances = search.query("вход")
        check("search «вход» lists the street entrances", entrances.isNotEmpty(), entrances.take(3).joinToString { it.title })
        // technical rooms are no longer suggested by name (user request, see CategoriesTest); the server room stays on the map, routable
        val serverRoom = b.rooms.values.firstOrNull { q -> q.names.any { it == "Серверная" } }
        check("server room on the map, routable, not suggested by name", serverRoom != null && search.placeFromRoom(serverRoom).goals.isNotEmpty() &&
            search.query("Серверная").none { it.service }, serverRoom?.let { search.placeFromRoom(it).title } ?: "—")

        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("rekordov15.md").writeText(sb.toString())
        assertTrue(ok)
    }
}
