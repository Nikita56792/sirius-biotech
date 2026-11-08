package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Рекордов 15 (calibration 1.8.7 assets): every stair and lift is usable between every pair of its floors from the
 * corridors (not only from its own shaft), and 216 → «Администратор» goes by a near stair/lift.
 * Before 1.8.7 the connector cells were written as [col, row]: lift S1 snapped behind its shaft, the others off the building.
 * Report: build/validation/rekordov15_conn.md
 */
class RekordovConnTest {
    private val b by lazy { Building(PrefixAssets(FileAssets(File("src/main/assets")), "sites/rekordov15/")) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }

    @Test
    fun everyConnectorUsableFromCorridors() {
        val sb = StringBuilder("# Рекордов 15: лестницы и лифты\n\n"); var ok = true
        router.userLevel = 1; router.rebuildAccess()
        // the start on each floor: a numbered room (reached from its corridor), the attic: next to the stair itself
        val ref = mapOf(1 to "101", 2 to "201", 3 to "301", 4 to "401", 5 to "501")
        fun roomPlace(f: Int) = search.query(ref.getValue(f)).firstOrNull { it.floor == f }?.mk?.invoke()
        for (c in b.conns) {
            if (0 in c.floors) continue                                  // street ↔ basement — RekordovTest
            val others = b.conns.filter { it.id != c.id }.map { it.id }.toSet()
            for (f in c.floors) for (g in c.floors) {
                if (f >= g) continue
                val a = roomPlace(f) ?: continue
                val z = if (g == 9) c.pos[9]?.let { planner.placeFromFix(9, it[0], it[1]) } else roomPlace(g)
                if (z == null) { sb.append("- FAIL ${c.name}: нет точки на этаже $g\n"); ok = false; continue }
                val r = planner.plan(a, z, extraAvoid = others).firstOrNull()
                val used = r?.connSteps?.any { it.conn?.id == c.id } == true
                val good = r != null && used && r.accessStop == null && r.length < 220
                sb.append(String.format(Locale.US, "- %s %s эт.%d → эт.%d: %s\n", if (good) "OK" else "FAIL", c.name, f, g,
                    if (r == null) "маршрута нет" else String.format(Locale.US, "%.0f м, через %s", r.length, r.connSteps.joinToString { it.conn?.name ?: it.kind })))
                ok = ok && good
            }
        }
        // 216 → Администратор: a near stair/lift (lift S3 ≈ 41 m, stair S2 ≈ 43 m by the grid), alternatives — other near ones
        val from = search.query("216").first { it.floor == 2 }.mk()
        val to = search.query("Администратор").first { it.floor == 1 }.mk()
        val rs = planner.plan(from, to)
        sb.append("\n216 → Администратор:\n")
        for (r in rs) sb.append(String.format(Locale.US, "  - %s: %.0f м, %s\n", r.label, r.length, r.connSteps.joinToString { it.conn?.name ?: it.kind }))
        val first = rs.firstOrNull()
        val near = setOf("Лифт (секция 3)", "Лестница (секция 2)", "Лестница (секция 3)", "Лифт (секция 2)")
        val fastOk = first != null && first.length < 65 && first.connSteps.all { it.conn?.name in near }
        sb.append("- ${if (fastOk) "OK" else "FAIL"} быстрый — через ближнюю лестницу/лифт\n"); ok = ok && fastOk
        val altFar = rs.drop(1).count { r -> r.connSteps.any { it.conn?.name !in near } }
        sb.append("- ${if (altFar == 0) "OK" else "NOTE"} альтернативы через дальние секции: $altFar\n")
        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("rekordov15_conn.md").writeText(sb.toString())
        assertTrue(ok)
    }
}
