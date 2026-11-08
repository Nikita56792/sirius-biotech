package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Аудитории Альфа 4.1–4.7 в павильоне «Умного города» (заметки и стены пользователя 30.10): каждая находится поиском и
 * достижима из кольцевого коридора 1321 с базовым пропуском (у 4.1 и 4.2 вход изнутри павильона без СКУД, у 4.3–4.5 двери,
 * которые на чертеже были нарисованы стенами); из 4.2 наружу через дверь «ключ на вход, выход свободен» (E186) — без ключа.
 * Report: build/validation/smart_city.md
 */
class SmartCityTest {
    private val b by lazy { Building(FileAssets(File("src/main/assets"))) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }

    @Test
    fun alphaRooms() {
        val sb = StringBuilder("# Альфа 4.1–4.7 (павильон «Умного города»)\n\n"); var ok = true
        val ring = planner.placeFromFix(1, 270.5, 185.0)!!
        for (lvl in listOf(1, 2)) {
            router.userLevel = lvl; router.rebuildAccess()
            for (k in 1..7) {
                val hit = search.query("Альфа 4.$k").firstOrNull { it.floor == 1 }
                if (hit == null) { sb.append("- FAIL Альфа 4.$k: не найдено поиском\n"); ok = false; continue }
                val r = planner.plan(ring, hit.mk()).firstOrNull()
                val good = r != null && r.accessStop == null && r.length < 80
                sb.append(String.format(Locale.US, "- %s пропуск %d → Альфа 4.%d: %s\n", if (good) "OK" else "FAIL", lvl, k,
                    if (r == null) "маршрута нет" else String.format(Locale.US, "%.0f м%s", r.length, if (r.accessStop != null) ", до прохода СКУД" else "")))
                ok = ok && good
            }
        }
        // из 4.2 в кольцевой коридор: выход через дверь E186 свободен (или через дверь в павильон)
        router.userLevel = 1; router.rebuildAccess()
        val r = planner.plan(planner.placeFromFix(1, 264.6, 183.4)!!, ring).firstOrNull()
        val good = r != null && r.accessStop == null
        sb.append(String.format(Locale.US, "\n- %s из Альфа 4.2 в коридор 1321 с базовым пропуском: %s\n", if (good) "OK" else "FAIL",
            if (r == null) "маршрута нет" else String.format(Locale.US, "%.0f м", r.length)))
        ok = ok && good
        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("smart_city.md").writeText(sb.toString())
        assertTrue(sb.toString(), ok)
    }
}
