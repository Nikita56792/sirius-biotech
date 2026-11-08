package com.gmcnav.navi.core

import java.io.InputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** One site of the navigator (assets/sites.json): asset prefix ("" = ГМЦ at the asset root), centre and radius. */
class SiteInfo(val id: String, val title: String, val prefix: String, val lon: Double, val lat: Double, val radiusM: Double) {
    /** Short name for the UI: «ГМЦ», «Рекордов 15». */
    val short: String get() = when (id) { "gmc" -> "НТУ"; "rekordov15" -> "Рекордов 15"; else -> title.substringBefore(",") }
}

/** Reads assets with a path prefix (the site's folder). */
class PrefixAssets(private val base: AssetSource, private val prefix: String) : AssetSource {
    override fun open(path: String): InputStream = base.open(prefix + path)
}

object Sites {
    /** Outside a site = farther than this beyond its radius (after subtracting the fix accuracy). */
    const val MARGIN_M = 250.0

    fun parse(text: String): List<SiteInfo> = (Json.parse(text).obj()["sites"] as List<*>).map { e ->
        val m = e as Map<*, *>
        SiteInfo(m["id"] as String, m["title"] as String, m["prefix"] as? String ?: "", (m["lon"] as Number).toDouble(),
            (m["lat"] as Number).toDouble(), (m["radius_m"] as Number).toDouble())
    }

    /** Great-circle distance in metres. */
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0; val p = PI / 180
        val a = sin((lat2 - lat1) * p / 2).let { it * it } + cos(lat1 * p) * cos(lat2 * p) * sin((lon2 - lon1) * p / 2).let { it * it }
        return 2 * r * asin(sqrt(a))
    }

    class Detect(val site: SiteInfo?, val nearest: SiteInfo?, val distM: Double)

    /**
     * Which site a location fix is in: the nearest site whose footprint circle (+ [MARGIN_M], + the fix accuracy) contains
     * it; site = null means outside every site (distM = distance to the nearest site's edge).
     */
    fun detect(sites: List<SiteInfo>, lat: Double, lon: Double, accM: Double = 0.0): Detect {
        var best: SiteInfo? = null; var bd = Double.MAX_VALUE
        for (s in sites) { val d = max(0.0, dist(lat, lon, s.lat, s.lon) - s.radiusM); if (d < bd) { bd = d; best = s } }
        val inside = best != null && bd - accM <= MARGIN_M
        return Detect(if (inside) best else null, best, bd)
    }
}
