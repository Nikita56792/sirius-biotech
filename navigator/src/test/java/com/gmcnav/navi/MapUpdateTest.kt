package com.gmcnav.navi

import com.gmcnav.navi.core.Access
import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.hypot

/**
 * Map of 28.10 13:27: stair flights fenced apart, СКУД on doors + access rooms + schedule, Greek codes and new labels.
 * Report: build/validation/map_update.md
 */
class MapUpdateTest {
    private val b by lazy { Building(FileAssets(File("src/main/assets"))) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }
    private val outDir = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }
    private val sb = StringBuilder("# Map update 28.10 13:27\n\n")
    private var ok = true
    private fun check(name: String, cond: Boolean, info: String = "") { sb.append("- ${if (cond) "OK" else "FAIL"} $name${if (info.isNotEmpty()) " — $info" else ""}\n"); ok = ok && cond }

    /** Local (Sochi) time in ms for a weekday (ISO 1 = Mon) and hh:mm in the week of 2 Nov 2026. */
    private fun at(dow: Int, h: Int, m: Int): Long = Calendar.getInstance(Access.TZ).apply {
        clear(); set(2026, Calendar.NOVEMBER, 1 + dow, h, m)     // 2 Nov 2026 is a Monday
    }.timeInMillis

    @Test
    fun mapUpdate() {
        sb.append("Rooms ${b.rooms.size}, doors ${b.doors.size}, СКУД lines ${b.access.size} (door-bound ${b.access.count { it.doors.isNotEmpty() }}), " +
            "access rooms ${b.accessRooms.size}, access doors ${b.doors.count { it.access > 0 }}\n\n")

        // ---------------------------------------------------------------- stairs
        sb.append("## Stair flights\n\n")
        router.flightLog.forEach { sb.append("- $it\n") }
        val s16 = b.conns.first { it.name == "Лестница 16" }
        val e16 = router.flightEntries[s16.id to 1]
        check("Лестница 16 (1273/1274) split into flights on floor 1", e16 != null && e16.size >= 2, e16?.size?.toString() ?: "not split")
        // from both sides of the stairwell up to floor 2: the floor-1 part must end at one of the flight starts
        val up = search.query("1289").firstOrNull { it.floor == 1 }?.mk?.invoke()
        val left = search.query("1295").firstOrNull { it.floor == 1 }?.mk?.invoke()
        val f2 = b.conns.first { it.id == s16.id }.pos.getValue(2).let { p -> planner.placeFromFix(2, p[0] + 6, p[1] + 6) }
        for ((nm, a) in listOf("from 1289 (east)" to up, "from 1295 (west)" to left)) {
            if (a == null || f2 == null) { check("route $nm", false, "place not found"); continue }
            val r = planner.plan(a, f2).firstOrNull()
            val step = r?.connSteps?.firstOrNull()
            val leg1 = r?.legs?.firstOrNull()
            val endCell = leg1?.let { b.row(it.ys.last()) * b.cols + b.col(it.xs.last()) }
            val viaS16 = step?.conn?.id == s16.id
            val atEntry = !viaS16 || (e16 != null && endCell != null && e16.any { c -> hypot(b.xOf(c % b.cols) - leg1!!.xs.last(), b.yOf(c / b.cols) - leg1.ys.last()) < 0.5 })
            check("route $nm to floor 2 enters the stair at a flight start", r != null && atEntry,
                if (r == null) "no route" else String.format(Locale.US, "via %s, floor-1 leg ends at (%.1f, %.1f)", step?.conn?.name, leg1!!.xs.last(), leg1.ys.last()))
            if (r != null && viaS16) File(outDir, "stair16_${if (nm.contains("east")) "e" else "w"}.json").writeText(
                "[" + leg1!!.xs.indices.joinToString(",") { "[${leg1.xs[it]},${leg1.ys[it]}]" } + "]")
        }
        // every stair still usable between its floors (pass «расширенный»: some stairs arrive in СКУД areas)
        router.userLevel = 2; router.rebuildAccess()
        var stairFail = 0
        for (c in b.conns.filter { it.kind == "stair" }) for (fa in c.floors) for (fz in c.floors) if (fa < fz) {
            val pa = c.pos[fa] ?: continue; val pz = c.pos[fz] ?: continue
            val a = planner.placeFromFix(fa, pa[0], pa[1]); val z = planner.placeFromFix(fz, pz[0], pz[1])
            val r = if (a != null && z != null) planner.plan(a, z).firstOrNull() else null
            if (r == null || r.connSteps.isEmpty()) { stairFail++; sb.append("  - ${c.name} $fa→$fz: ${if (r == null) "no route" else "no floor change"}\n") }
        }
        check("all stairs route between their floors", stairFail == 0, "$stairFail failures")
        router.userLevel = 1; router.rebuildAccess()

        // ---------------------------------------------------------------- СКУД
        sb.append("\n## СКУД (pass «базовый»: every line in the data is «расширенный»)\n\n")
        router.userLevel = 1
        router.rebuildAccess(at(1, 9, 0))
        planner.clock = { at(1, 9, 0) }
        // no holes: at each access door, points 1.5 m on either side of its line must not connect within 4 m of the door
        // (room labels are unreliable at door strips);
        // sides that connect within 8 m through another, free door are listed (data: the room is not an access room)
        var holes = 0; var sealed = 0; var checked = 0; var around = 0; var gaps = 0; val gapList = ArrayList<String>()
        val holeList = ArrayList<String>(); val aroundList = ArrayList<String>()
        /** Cells of room `other` reached from `from` within rad of the door (≥ 12 = a real passage, not a pocket of a misaligned line). */
        fun reach(fi: Int, d: com.gmcnav.navi.core.Door, from: Int, other: Int, rad: Double): Int {
            val seen = HashSet<Int>(); val q = ArrayDeque<Int>(); q.add(from); seen.add(from); var n = 0
            while (q.isNotEmpty()) {
                val c = q.removeFirst(); val r = c / b.cols; val cc = c % b.cols
                if ((b.grids[fi][c].toInt() and 0xffff) == other) n++
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nr = r + dr; val nc = cc + dc; val nn = nr * b.cols + nc
                    if (!b.inGrid(nr, nc) || nn in seen || !router.open(fi, nn)) continue
                    if (hypot(b.xOf(nc) - d.x, b.yOf(nr) - d.y) > rad) continue
                    seen.add(nn); q.add(nn)
                }
            }
            return n
        }
        fun connects(fi: Int, d: com.gmcnav.navi.core.Door, sa: Int, sz: Int, rad: Double): Boolean {
            val seen = HashSet<Int>(); val q = ArrayDeque<Int>(); q.add(sa); seen.add(sa)
            while (q.isNotEmpty()) {
                val c = q.removeFirst(); val r = c / b.cols; val cc = c % b.cols
                for ((dr, dc) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                    val nr = r + dr; val nc = cc + dc; val n = nr * b.cols + nc
                    if (!b.inGrid(nr, nc) || n in seen || !router.open(fi, n)) continue
                    if (hypot(b.xOf(nc) - d.x, b.yOf(nr) - d.y) > rad) continue
                    if (n == sz) return true
                    seen.add(n); q.add(n)
                }
            }
            return false
        }
        val lines = b.access.associateBy { it.id }
        for (d in b.doors.filter { it.access > 0 }) {
            val fi = b.fi(d.floor); if (fi < 0) continue
            val ln = lines[d.accessLine] ?: continue
            // the line's segment nearest to the door and its normal: the two sides are 1.5 m off the line at the door
            var best = Double.MAX_VALUE; var px = 0.0; var py = 0.0; var nx = 0.0; var ny = 0.0
            for (k in 1 until ln.xs.size) {
                val ax = ln.xs[k - 1]; val ay = ln.ys[k - 1]; val vx = ln.xs[k] - ax; val vy = ln.ys[k] - ay; val l2 = vx * vx + vy * vy
                if (l2 <= 0) continue
                val t = (((d.x - ax) * vx + (d.y - ay) * vy) / l2).coerceIn(0.0, 1.0)
                val qx = ax + vx * t; val qy = ay + vy * t; val dd = hypot(d.x - qx, d.y - qy)
                if (dd < best) { best = dd; px = qx; py = qy; val l = Math.sqrt(l2); nx = -vy / l; ny = vx / l }
            }
            // a door of a restricted room is sealed by the room itself (all its cells need the level)
            if (d.rooms.any { r -> b.accessRooms.any { it.floor == d.floor && it.room == r } }) { sealed++; continue }
            fun side(sg: Double): Int = b.snap(fi, b.row(py + sg * 1.5 * ny), b.col(px + sg * 1.5 * nx), 2) { router.open(fi, it) }
            val sa = side(1.0); val sz = side(-1.0)
            if (sa < 0 || sz < 0) { sealed++; continue }
            checked++
            val info = String.format(Locale.US, "door %d floor %d (%.1f, %.1f) rooms %s line %d", d.id, d.floor, d.x, d.y, d.rooms.toList(), d.accessLine)
            // data gap: the line cannot span the opening (door centre farther than half its width, or line much shorter)
            val lineLen = (1 until ln.xs.size).sumOf { k -> hypot(ln.xs[k] - ln.xs[k - 1], ln.ys[k] - ln.ys[k - 1]) }
            val gap = best > maxOf(0.6, d.size / 2) || lineLen < 0.6 * d.size
            if (connects(fi, d, sa, sz, 4.0)) { if (gap) { gaps++; gapList.add(info + String.format(Locale.US, ", size %.1f m, door→line %.1f m, line %.1f m", d.size, best, lineLen)) } else { holes++; holeList.add(info) } }
            else if (connects(fi, d, sa, sz, 8.0)) { around++; aroundList.add(info) }
        }
        holeList.forEach { sb.append("  - HOLE: $it\n") }
        sb.append("  - data: $gaps access doors whose line does not span the opening (passable around the line — the line in meta.json is too short / off the door):\n")
        gapList.forEach { sb.append("    - $it\n") }
        check("no holes in the СКУД barrier at access doors (pass «базовый»)", holes == 0,
            "$checked doors with both sides open next to the door, $sealed sealed (a side is a restricted room), $holes holes")
        sb.append("  - info: $around access doors whose sides also connect within 8 m through another free door (the room behind is not in access_rooms):\n")
        aroundList.forEach { sb.append("    - $it\n") }
        // destination inside an access room -> stop at its door; start inside -> may leave
        val room = b.accessRooms.filter { it.schedule.isEmpty() && it.area in 8.0..200.0 }.firstOrNull { b.rooms[it.room]?.nums?.isNotEmpty() == true }
        if (room != null) {
            val q = b.rooms.getValue(room.room)
            val inside = search.placeFromRoom(q)
            val outside = search.query("1105").firstOrNull()?.mk?.invoke() ?: search.items.first { it.floor == room.floor && it.title.contains("Коридор") }.mk()
            val r = planner.plan(outside, inside).firstOrNull()
            check("destination in access room ${b.roomTitle(q)} -> route stops at its door", r?.accessStop != null,
                r?.let { String.format(Locale.US, "%s, %.0f m, «%s»", it.label, it.length, it.goalTitle) } ?: "no route")
            val back = planner.plan(inside, outside).firstOrNull()
            check("start inside the access room -> leaves normally", back != null && back.accessStop == null, back?.let { "${it.label}, ${it.length.toInt()} m" } ?: "no route")
        } else check("an access room for the inside/outside test", false)
        // schedule: Учебный офис
        val office = b.accessRooms.firstOrNull { it.schedule.isNotEmpty() }
        if (office != null) {
            val q = b.rooms.getValue(office.room)
            val dest = search.placeFromRoom(q)
            val from = search.query("Планетарий").first().mk()
            fun tryAt(t: Long): String { planner.clock = { t }; val r = planner.plan(from, dest).first(); return if (r.accessStop == null) "open" else "closed: ${r.accessStop!!.note()}" }
            val mon1130 = tryAt(at(1, 11, 30)); val mon1330 = tryAt(at(1, 13, 30)); val thu1500 = tryAt(at(4, 15, 0)); val fri1130 = tryAt(at(5, 11, 30))
            sb.append("  - ${office.name}: Mon 11:30 $mon1130 | Mon 13:30 $mon1330 | Thu 15:00 $thu1500 | Fri 11:30 $fri1130\n")
            check("Учебный офис schedule (open Mon–Thu 11–13, 14–16, else расширенный)",
                mon1130 == "open" && mon1330.startsWith("closed") && thu1500 == "open" && fri1130.startsWith("closed") && mon1330.contains("пн–чт 11:00–13:00, 14:00–16:00"), mon1330)
            planner.clock = { at(1, 9, 0) }
        } else check("scheduled access room present", false)
        // one-way exit (line 148 «открыто только на выход, на вход ключ»): leaving is free, entering needs the key
        b.access.firstOrNull { it.exitDir != null }?.let { ow ->
            val e = ow.exitDir!!
            // the line's door that the line actually crosses (nearest to it)
            fun lineDist(x: Double, y: Double): Double = (1 until ow.xs.size).minOf { k ->
                val ax = ow.xs[k - 1]; val ay = ow.ys[k - 1]; val vx = ow.xs[k] - ax; val vy = ow.ys[k] - ay; val l2 = vx * vx + vy * vy
                val t = if (l2 > 0) (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
                hypot(x - ax - vx * t, y - ay - vy * t) }
            val d = ow.doors.minByOrNull { lineDist(it.x, it.y) }!!
            // cross at the middle of the line, 1.5 m to each side
            val mx = (ow.xs.first() + ow.xs.last()) / 2; val my = (ow.ys.first() + ow.ys.last()) / 2
            val inside = planner.placeFromFix(ow.floor, mx - 1.5 * e[0], my - 1.5 * e[1])!!
            val street = planner.placeFromFix(ow.floor, mx + 1.5 * e[0], my + 1.5 * e[1])!!
            sb.append(String.format(Locale.US, "  - one-way probe: inside (%.1f, %.1f) room %d, street (%.1f, %.1f) room %d\n", inside.x, inside.y,
                b.roomIdAt(b.fi(ow.floor), inside.x, inside.y), street.x, street.y, b.roomIdAt(b.fi(ow.floor), street.x, street.y)))
            val out = planner.plan(inside, street).first(); val inn = planner.plan(street, inside).first()
            sb.append(String.format(Locale.US, "  - one-way line %d «%s», door %d: inside→street %s %.0f m; street→inside %s %.0f m%s\n", ow.id, ow.name, d.id,
                out.label, out.length, inn.label, inn.length, inn.accessStop?.let { " (stop: ${it.note()})" } ?: ""))
            // real line 148: in the grid it runs along the wall of vestibule 617, so a straight probe may not cross it —
            // reported only; the rule itself is checked on a synthetic one-way line below
            check("one-way line 148: entering from the street needs the key (detour or stop)", inn.accessStop != null || inn.length > 15)
            sb.append("  - (info) leaving through line 148 from the vestibule: ${out.label}, ${out.length.toInt()} m\n")
            // does a route actually cross the one-way line? (a state on its cells)
            fun crosses(r: com.gmcnav.navi.core.Route) = r.states.any { st -> router.accessLineAt(st / b.n, st % b.n)?.id == ow.id }
            val fi = b.fi(ow.floor)
            // street point just beyond the middle of the line (exit side)
            val streetPt = planner.placeFromFix(ow.floor, mx + 2.5 * e[0], my + 2.5 * e[1])!!
            // the vestibule «1697 Тамбур» and corridor «1275» behind line 148, by number (room ids shift on every map rebuild)
            for (num in listOf("1697", "1275")) {
                val q = b.rooms.values.firstOrNull { it.floor == ow.floor && num in it.nums } ?: continue
                val p0 = search.placeFromRoom(q)
                val o2 = planner.plan(p0, streetPt).first(); val i2 = planner.plan(streetPt, p0).first()
                val stopD = i2.legs.last().let { L -> lineDist(L.xs.last(), L.ys.last()) }
                sb.append(String.format(Locale.US, "  - line %d: %s → street: %s %.0f m, crosses the line: %s | street → %s: %s %.0f m, crosses: %s%s\n",
                    ow.id, b.roomTitle(q), o2.label, o2.length, crosses(o2), b.roomTitle(q), i2.label, i2.length, crosses(i2),
                    i2.accessStop?.let { String.format(Locale.US, " — stops %.1f m before the line (%s)", stopD, it.note()) } ?: ""))
                check("one-way 148: exit from ${b.roomTitle(q)} crosses the line freely", o2.accessStop == null && crosses(o2), "${o2.length.toInt()} m")
                // without a pass the route never enters through 148: it stops at the line, or detours through a free entrance if one exists
                check("one-way 148: entering towards ${b.roomTitle(q)} without a pass does not cross the line (stop or detour)",
                    !crosses(i2) && (i2.accessStop == null || stopD < 3.0), "${i2.label}, ${i2.length.toInt()} m")
            }
        } ?: check("one-way line present", false)
        check("exterior lines", b.access.count { it.exterior } >= 20, "${b.access.count { it.exterior }}")
        // synthetic one-way line across a corridor on a real route: along the exit direction free, against it needs the key
        run {
            val a = search.query("1289").first { it.floor == 1 }.mk(); val z = search.query("1295").first { it.floor == 1 }.mk()
            val base = planner.plan(a, z).first()
            val ln = planner.syntheticAccessLine(a, z, 0.5, 2, 6.0, -55)!!
            val mx = (ln.xs[0] + ln.xs[1]) / 2; val my = (ln.ys[0] + ln.ys[1]) / 2
            val h = base.headingAt(base.project(b.fi(ln.floor), mx, my)!!.s)
            val ow = com.gmcnav.navi.core.AccessLine(-55, ln.floor, 2, "тест: выход свободный", ln.xs, ln.ys, exitDir = doubleArrayOf(Math.sin(h), Math.cos(h)))
            b.access.add(ow); router.rebuildAccess(at(1, 9, 0))
            val fwd = planner.plan(a, z).first(); val back = planner.plan(z, a).first()
            b.access.remove(ow); router.rebuildAccess(at(1, 9, 0))
            sb.append(String.format(Locale.US, "  - synthetic one-way line: a→z (exit direction) %s %.0f m (base %.0f m); z→a %s %.0f m%s\n",
                fwd.label, fwd.length, base.length, back.label, back.length, back.accessStop?.let { " — stop before the line" } ?: ""))
            check("one-way rule: crossing along the exit direction is free", fwd.accessStop == null && fwd.length < base.length + 5)
            check("one-way rule: crossing against it needs the key", back.accessStop != null || back.length > base.length + 10)
        }
        router.userLevel = 2; router.rebuildAccess(at(1, 9, 0))
        val inRoom = room?.let { planner.plan(search.query("1105").first().mk(), search.placeFromRoom(b.rooms.getValue(it.room))).firstOrNull() }
        check("pass «расширенный» enters the access room", inRoom != null && inRoom.accessStop == null)
        router.userLevel = 1; router.rebuildAccess()

        // ---------------------------------------------------------------- search
        sb.append("\n## Search\n\n")
        for ((q, want) in listOf("бета 4.1" to "β4.1", "β4.1" to "β4.1", "b4.1" to "β4.1", "1-07-12" to "β4.1", "бета4.1" to "β4.1",
                "альфа 5.4" to "α5.4", "гамма" to "γ", "Умный город" to "Умный город", "КПП 2" to "КПП 2", "КПП 1" to "КПП 1",
                "Планетарий" to "Планетарий", "Буран" to "Буран", "Фанагория" to "Фанагория", "Росатом" to "Росатом",
                "Газпром нефть" to "Газпром", "Учебный офис" to "Учебный офис", "Стойка информации" to "Стойка информации",
                "К12" to "К12", "Зал Зима" to "Зима", "Подъёмник МГН" to "Подъёмник")) {
            val res = search.query(q)
            val top = res.firstOrNull()?.title ?: "—"
            check("search «$q» -> $want", top.contains(want), "1st: $top")
        }
        val t = search.query("β4.1").firstOrNull()?.title
        check("title format «β4.1 · Бета 4.1 · Комната 3D проектирования · 1-07-12»", t == "β4.1 · Бета 4.1 · Комната 3D проектирования · 1-07-12", t ?: "—")

        println(sb)
        outDir.resolve("map_update.md").writeText(sb.toString())
        assertTrue(ok)
    }
}
