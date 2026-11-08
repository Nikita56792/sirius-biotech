package com.gmcnav.navi

import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Live mode «По Wi-Fi (как в калибровке)» (PositionEngine pdr = false: weighted kNN, 60 % blend, new floor on the 2nd scan)
 * vs «Wi-Fi + шаги» on the recordings, scan as recorded (~2 s) and every 2nd (~4 s). Also how jumpy the shown position is
 * (median move between consecutive fixes while the true position moved < 1 m). Report: knn_mode.md
 */
class KnnModeTest {
    @Test
    fun knnMode() {
        val t = ScanBudgetReplayTest()
        val sb = StringBuilder("# По Wi-Fi (как в калибровке) против Wi-Fi + шаги\n\n| объект | скан | режим | полная карта мед / p90 | честно мед / p90 | этаж ok |\n|---|---|---|---|---|---|\n")
        for (site in listOf(t.gmc, t.r15)) for (every in listOf(1, 2)) for (pdr in listOf(false, true)) {
            val dev = DeviceProfile("cad", scanEvery = every)
            val full = t.run(site, "all", honest = false, seeds = listOf(11L), device = dev, pdr = pdr)
            val hon = t.run(site, "all", honest = true, seeds = listOf(11L), device = dev, pdr = pdr)
            sb.append(String.format(Locale.US, "| %s | %s | %s | %.1f / %.1f | %.1f / %.1f | %.1f %% |\n", site.name, if (every == 1) "2 с" else "4 с",
                if (pdr) "Wi-Fi + шаги" else "по Wi-Fi", full.q(full.err, 0.5), full.q(full.err, 0.9), hon.q(hon.err, 0.5), hon.q(hon.err, 0.9),
                100.0 * full.floorOk / maxOf(1, full.err.size)))
        }
        println(sb)
        File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve("knn_mode.md").writeText(sb.toString())
    }
}
