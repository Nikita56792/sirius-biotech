package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.Router
import org.junit.Test
import java.io.File
import java.util.Locale

/** Рекордов 15: явные точки по обе стороны каждой двери «выход свободно, вход по ключу» (тамбур ↔ улица). */
class RekordovExitProbe {
    @Test
    fun probe() {
        val b = Building(PrefixAssets(FileAssets(File("src/main/assets")), "sites/rekordov15/"))
        val router = Router(b); val planner = Planner(b, router); router.userLevel = 1; router.rebuildAccess()
        val pts = mapOf(   // line id -> (inside, outside)
            1 to (doubleArrayOf(8.6, 45.5) to doubleArrayOf(6.0, 45.5)),
            2 to (doubleArrayOf(22.5, 43.4) to doubleArrayOf(25.0, 39.5)),
            3 to (doubleArrayOf(35.8, 53.2) to doubleArrayOf(35.8, 56.5)),
            4 to (doubleArrayOf(60.9, 10.0) to doubleArrayOf(60.9, 7.0)))
        val sb = StringBuilder()
        for ((id, io) in pts) {
            val a = planner.placeFromFix(1, io.first[0], io.first[1])!!; val z = planner.placeFromFix(1, io.second[0], io.second[1])!!
            fun crosses(r: com.gmcnav.navi.core.Route) = r.states.any { st -> router.accessLineAt(st / b.n, st % b.n)?.id == id }
            val ex = planner.plan(a, z).first(); val en = planner.plan(z, a).first()
            sb.append(String.format(Locale.US, "line %d: inside(%.1f,%.1f)→street(%.1f,%.1f) %.0f m crosses %s stop %s | street→inside %.0f m crosses %s stop %s\n",
                id, a.x, a.y, z.x, z.y, ex.length, crosses(ex), ex.accessStop != null, en.length, crosses(en), en.accessStop != null))
        }
        println(sb); File("build/validation").also { it.mkdirs() }.resolve("rekordov15_exits.md").writeText(sb.toString())
    }
}
