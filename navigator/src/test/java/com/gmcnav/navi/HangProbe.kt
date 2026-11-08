package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import org.junit.Test
import java.io.File
import java.util.Locale

/** ГМЦ: время построения до «Зал 1» (за СКУД / без прохода) с разных стартов и уровней пропуска, затем обычные цели. */
class HangProbe {
    @Test
    fun probe() {
        val b = Building(FileAssets(File("src/main/assets")))
        val router = Router(b); val planner = Planner(b, router); val search = Search(b)
        val sb = StringBuilder()
        val starts = listOf("К20" to search.query("К20").first().mk(), "улица (территория)" to planner.placeFromFix(1, 150.0, 30.0)!!,
            "К0" to search.query("К0").first().mk())
        for (lvl in listOf(0, 1, 2)) {
            router.userLevel = lvl; router.rebuildAccess()
            for ((sn, from) in starts) for (q in listOf("Зал 1", "К0", "2001", "Зал 1")) {
                val z = search.query(q).firstOrNull()?.mk?.invoke() ?: continue
                val t0 = System.nanoTime(); val rs = planner.plan(from, z); val dt = (System.nanoTime() - t0) / 1e6
                sb.append(String.format(Locale.US, "pass %d, %s → %s: %.0f ms, %s\n", lvl, sn, z.title.take(30), dt,
                    rs.firstOrNull()?.let { "${it.label} ${it.length.toInt()} m${if (it.unreachable) " (ближе не подойти)" else ""}" } ?: "—"))
            }
        }
        println(sb); File("build/validation").also { it.mkdirs() }.resolve("hang_probe.md").writeText(sb.toString())
    }
}
