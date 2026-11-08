package com.gmcnav.navi

import com.gmcnav.navi.core.Access
import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.PositionEngine
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.ReplayIndex
import com.gmcnav.navi.core.ReplayPart
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import com.gmcnav.navi.core.VoicePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/** СКУД routing with synthetic access lines, radio validity ("is the user here at all"), plan <-> lon/lat. */
class AccessAndValidityTest {
    private val assets = File("src/main/assets")
    private val b by lazy { Building(FileAssets(assets)) }
    private val router by lazy { Router(b) }
    private val planner by lazy { Planner(b, router) }
    private val search by lazy { Search(b) }
    private fun out(name: String) = File(System.getProperty("gmc.out") ?: "build/validation").also { it.mkdirs() }.resolve(name)

    /** True if any step of the route goes through a cell whose required level exceeds `level`. */
    private fun crosses(r: Route, level: Int): Boolean = r.states.any { st -> router.accessAt(st / b.n, st % b.n) > level }

    @Test
    fun skudRouting() {
        val sb = StringBuilder("# СКУД with synthetic lines\n\n")
        val a = search.query("пом. 13").first().mk(); val z = search.query("К20").first().mk()
        // 1) расширенный line across the corridor between пом. 13 and К20
        val line = planner.syntheticAccessLine(a, z, 0.45, 2, 6.0, -1)!!
        b.access.add(line); router.rebuildAccess()
        sb.append(String.format(Locale.US, "line «%s» floor %d, (%.1f, %.1f)–(%.1f, %.1f), level %d (%s)\n\n", line.name, line.floor,
            line.xs[0], line.ys[0], line.xs[1], line.ys[1], line.level, line.levelName))
        for (lvl in listOf(0, 1, 2)) {
            router.userLevel = lvl
            val rs = planner.plan(a, z)
            sb.append("pass «${Access.levelName(lvl)}»: ${rs.size} routes\n")
            assertTrue(rs.isNotEmpty())                               // never "not found" because of СКУД
            for (r in rs) {
                sb.append(String.format(Locale.US, "  [%s] %.0f м, stop=%s, ends at (%.1f, %.1f), goal: %s\n", r.label, r.walk,
                    r.accessStop?.let { Access.note(it.level) } ?: "—", r.legs.last().xs.last(), r.legs.last().ys.last(), r.goalTitle))
                assertTrue("route crosses a line above the pass level", !crosses(r, lvl))
            }
            if (lvl >= 2) assertNull(rs.first().accessStop) else {
                val r = rs.first()
                if (r.accessStop != null) {
                    // stop point in front of the line, not behind it
                    val ex = r.legs.last().xs.last(); val ey = r.legs.last().ys.last()
                    val dLine = segDist(ex, ey, line.xs[0], line.ys[0], line.xs[1], line.ys[1])
                    sb.append(String.format(Locale.US, "  stop point %.1f m from the line\n", dLine))
                    assertTrue(dLine < 4.0)
                    // voice: the arrival prompt says the СКУД note
                    val spoken = walk(r)
                    sb.append("  voice: ${spoken.joinToString(" → ") { "«$it»" }}\n")
                    assertEquals(Access.spokenNote(2), spoken.last())
                }
            }
        }
        // 1b) a closed расширенный loop around К20: базовый -> route to the line and the СКУД note, расширенный -> through
        b.access.clear(); b.access.add(planner.syntheticAccessBox(z, 6.0, 2, -3)); router.rebuildAccess()
        for (lvl in listOf(1, 2)) {
            router.userLevel = lvl
            val rs = planner.plan(a, z)
            assertTrue(rs.isNotEmpty())
            val r = rs.first()
            sb.append(String.format(Locale.US, "loop around К20, pass «%s»: [%s] %.0f м, %s\n", Access.levelName(lvl), r.label, r.walk,
                r.accessStop?.let { Access.note(it.level) } ?: "reaches К20"))
            assertTrue(rs.all { !crosses(it, lvl) })
            if (lvl == 1) {
                assertNotNull(r.accessStop)
                val box = b.access.first()
                val ex = r.legs.last().xs.last(); val ey = r.legs.last().ys.last()
                val dLine = (0 until box.xs.size - 1).minOf { segDist(ex, ey, box.xs[it], box.ys[it], box.xs[it + 1], box.ys[it + 1]) }
                sb.append(String.format(Locale.US, "  stops %.1f m in front of the line, %.1f m from К20\n", dLine, hypot(ex - z.x, ey - z.y)))
                assertTrue(dLine < 4.0)
                val spoken = walk(r)
                sb.append("  voice: ${spoken.joinToString(" → ") { "«$it»" }}\n")
                assertEquals(Access.spokenNote(2), spoken.last())
            } else assertNull(r.accessStop)
        }
        // 2) a базовый line: guests stop, базовый passes
        b.access.clear(); router.rebuildAccess()
        val l1 = planner.syntheticAccessLine(a, z, 0.45, 1, 6.0, -2)!!
        b.access.add(l1); router.rebuildAccess()
        router.userLevel = 0
        val g = planner.plan(a, z)
        sb.append("\nбазовый line, guest: ${g.first().label}, ${g.first().accessStop?.let { Access.note(it.level) } ?: "no stop (detour)"}\n")
        assertTrue(g.all { !crosses(it, 0) })
        router.userLevel = 1
        val bb = planner.plan(a, z)
        sb.append("базовый line, базовый pass: ${bb.first().label}, ${bb.first().walk.toInt()} м, stop=${bb.first().accessStop != null}\n")
        assertNull(bb.first().accessStop)
        // 3) multi-floor with alternatives: К0 -> К20 with the line in front of К20 and a базовый pass: every alternative obeys it
        b.access.clear(); b.access.add(line); router.rebuildAccess(); router.userLevel = 1
        val k0 = search.query("К0").first().mk()
        val alt = planner.plan(k0, z)
        sb.append("\nК0 → К20, базовый pass, расширенный line: ${alt.size} routes: ${alt.joinToString { it.label + (if (it.accessStop != null) " (до СКУД)" else "") }}\n")
        assertTrue(alt.isNotEmpty() && alt.all { !crosses(it, 1) })
        b.access.clear(); router.rebuildAccess(); router.userLevel = 1
        val txt = sb.toString(); println(txt); out("skud.md").writeText(txt)
    }

    /** Haversine (the JVM tests have no android.location). */
    private fun android_distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double, out: FloatArray) {
        val r = 6371000.0; val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2).let { it * it } + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2).let { it * it }
        out[0] = (2 * r * Math.asin(Math.sqrt(a))).toFloat()
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val vx = bx - ax; val vy = by - ay; val t = (((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy)).coerceIn(0.0, 1.0)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    /** Walks the route exactly and returns the spoken texts. */
    private fun walk(r: Route): List<String> {
        val g = Guidance(b, r); val vp = VoicePlanner(); val out = ArrayList<String>()
        var s = 0.0; var t = 0L; var steps = 0
        while (s <= r.length + 1) {
            val (fi, x, y) = r.pointAt(minOf(s, r.length))
            val st = g.update(Fix(t, b.floorOf(fi), x, y, 3.0, r.headingAt(s), "sim", steps = steps, stepLen = 0.6, floorProb = 1.0))
            vp.update(r, st).forEach { out.add(it.text) }
            s += 0.6; t += 500_000_000L; steps++
        }
        return out
    }

    @Test
    fun radioValidityAndGeo() {
        val sb = StringBuilder("# Is the user here?\n\n")
        val radio = RadioMap().apply { loadPacked(File(assets, "radio/wifi.json").readText()) }
        val replayDir = File(System.getProperty("gmc.replay") ?: "../build_data/replay")
        val idx = ReplayIndex(File(replayDir, "index.json").readText())
        for (honest in listOf(false, true)) {
            var n = 0; var ok = 0
            for (e in idx.parts) {
                val rp = ReplayPart(File(replayDir, e.file.removePrefix("replay/")).readBytes(), idx.bssids, idx.cellKeys)
                val sid = radio.sessions.indexOf(rp.session)
                for (i in rp.scanT.indices) {
                    val gt = rp.truth(rp.scanT[i]) ?: continue
                    if (gt.outdoor) continue
                    val now = rp.scanT[i] / 1_000_000
                    val m = radio.match(rp.scans[i], if (honest) { p -> p.session == sid && abs(p.tMs - now) < 90_000 } else null)
                    val known = radio.knownCount(rp.scans[i])
                    val best = maxOf(m.bestIndoor, m.bestOutdoor)
                    n++; if (best >= PositionEngine.RADIO_OK && (known >= 3 || best >= 0.4f)) ok++
                }
            }
            sb.append(String.format(Locale.US, "in-building scans judged radio-valid (%s map): %d of %d = %.1f %%\n", if (honest) "honest" else "full", ok, n, 100.0 * ok / n))
            if (!honest) assertTrue(ok > 0.97 * n)
        }
        // a scan from somewhere else (APs the building never saw)
        val foreign = (1..45).associate { String.format("02:%02x:%02x:10:20:30", it, it * 7 % 256) to (-50f - it) }
        val mf = radio.match(foreign)
        sb.append(String.format(Locale.US, "foreign scan: %d known APs, best similarity %.2f -> %s\n", radio.knownCount(foreign), maxOf(mf.bestIndoor, mf.bestOutdoor),
            if (radio.knownCount(foreign) < 3 && maxOf(mf.bestIndoor, mf.bestOutdoor) < 0.4f) "invalid (no cursor)" else "VALID?!"))
        assertTrue(radio.knownCount(foreign) < 3)
        // plan <-> lon/lat with the real affine from meta.json "geo"
        assertNotNull(b.planToLonLat)
        val c = b.toLonLat(180.0, 210.0)
        sb.append(String.format(Locale.US, "geo: plan (180, 210) -> lat %.5f lon %.5f (expected ≈ 43.41441, 39.95090); true north at plan heading %.1f°\n",
            c[1], c[0], Math.toDegrees(b.northHeading)))
        assertTrue(abs(c[1] - 43.41441) < 0.0005 && abs(c[0] - 39.95090) < 0.0005)
        val back = b.fromLonLat(c[0], c[1])
        assertTrue(hypot(back[0] - 180.0, back[1] - 210.0) < 0.01)
        fun gps(name: String, lat: Double, lon: Double, acc: Double) {
            val xy = b.fromLonLat(lon, lat)
            val d = b.distToBuilding(xy[0], xy[1])
            val res = FloatArray(1); android_distance(lat, lon, c[1], c[0], res)
            val outside = d - acc > 250
            sb.append(String.format(Locale.US, "  %-38s plan (%.0f, %.0f), %.0f m from the footprint, %.1f km from the centre -> %s\n", name, xy[0], xy[1], d,
                res[0] / 1000.0, if (outside) "«Вы вне ГМЦ · ≈%.1f км»".format(Locale.US, res[0] / 1000.0) else "on site (cursor allowed)"))
        }
        // 4.2 km north-east, the КПП №2 gate, the building centre, 300 m off the footprint
        gps("4.2 km NE of the building", c[1] + 4200 / 111_132.0 * Math.cos(Math.PI / 4), c[0] + 4200 / (111_320.0 * Math.cos(Math.toRadians(c[1]))) * Math.sin(Math.PI / 4), 30.0)
        val kpp = search.query("КПП №2").first().mk(); val kll = b.toLonLat(kpp.x, kpp.y)
        gps("at КПП №2", kll[1], kll[0], 15.0)
        gps("building centre", c[1], c[0], 15.0)
        val fp = b.footprint; val p300 = b.toLonLat(fp[2] + 300.0, (fp[1] + fp[3]) / 2)
        gps("300 m beyond the footprint edge", p300[1], p300[0], 20.0)
        val far = b.fromLonLat(c[0] + 4200.0 / (111_320.0 * Math.cos(Math.toRadians(c[1]))), c[1])
        assertTrue(b.distToBuilding(far[0], far[1]) > 3700)
        val k2 = b.fromLonLat(kll[0], kll[1]); assertTrue(b.distToBuilding(k2[0], k2[1]) < 250)
        assertNotNull(b.extent)
        val txt = sb.toString(); println(txt); out("validity.md").writeText(txt)
        assertEquals(MType.FINISH, MType.valueOf("FINISH"))
    }
}
