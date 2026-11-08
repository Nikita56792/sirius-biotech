package com.gmcnav.navi

import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.FileAssets
import com.gmcnav.navi.core.ParticleFilter
import com.gmcnav.navi.core.PrefixAssets
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Vertical
import com.gmcnav.navi.core.VerticalTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Vertical model: connector times / walked lengths by the real floor heights, and the lift-ride detector on a synthetic ride
 * (standing at a lift: 0.6 m/s² push for 1.5 s, cruise at 0.9 m/s, the opposite push; sensor noise and a 0.05 m/s² bias) —
 * up one floor, up two, down one; standing still with the phone moved up and down by hand fires nothing.
 */
class VerticalTest {
    private val gmc by lazy { Building(FileAssets(File("src/main/assets"))) }
    private val r15 by lazy { Building(PrefixAssets(FileAssets(File("src/main/assets")), "sites/rekordov15/")) }

    @Test
    fun costs() {
        // ГМЦ 1→2 (5.25 m): stairs up ≈ 17.5 + 3.3 + 8 s, down ≈ 11.7 + 3.3 + 8 s; lift 25 + 8 + 5.25 + 3 s
        assertEquals(28.8, Vertical.cost("stair", 0.0, 5.25), 0.2)
        assertEquals(23.0, Vertical.cost("stair", 5.25, 0.0), 0.2)
        assertEquals(41.25, Vertical.cost("lift", 0.0, 5.25), 0.01)
        assertEquals(14.5, Vertical.length("stair", 0.0, 5.25), 0.01)   // 2 × 5.25 along the slope + 2 landings
        // Рекордов 5 → attic: one flight 3.15 m (the floor numbers 5 → 9 used to count as 4 floors: 92 s)
        assertEquals(20.2, Vertical.cost("stair", r15.elevOf(5), r15.elevOf(9)), 0.3)
        val route = com.gmcnav.navi.core.Planner(gmc, Router(gmc))
        val a = route.placeFromFix(1, 150.0, 120.0)!!; val z = route.placeFromFix(2, 150.0, 240.0)!!
        val r = route.plan(a, z).first()
        assertTrue("walk includes the stair flights", r.connSteps.none { it.kind == "stair" } || r.walk > r.length + 10)
    }

    private fun ride(b: Building, floor: Int, liftX: Double, liftY: Double, profile: List<Pair<Double, Double>>, seed: Long): List<VerticalTracker.Transit> {
        val vt = VerticalTracker(b); val rnd = Random(seed)
        val est = ParticleFilter.Est(b.fi(floor), liftX, liftY, 3.0, DoubleArray(b.floors.size), 0.0, 1.0, 1.0)
        var t = 1_000_000_000L
        // 4 s still, then the profile (duration s, acceleration m/s²), then 4 s still
        for ((dur, acc) in listOf(4.0 to 0.0) + profile + listOf(4.0 to 0.0)) {
            val n = (dur * 100).toInt()
            for (i in 0 until n) { vt.accel(t, 9.81 + 0.05 + acc + rnd.nextGaussian() * 0.03, est); t += 10_000_000L }
        }
        return vt.log
    }

    @Test
    fun liftRide() {
        val lift = gmc.conns.first { it.kind == "lift" && 1 in it.floors && 2 in it.floors }
        val p = lift.pos.getValue(1)
        fun profile(h: Double, up: Boolean): List<Pair<Double, Double>> {
            val a = if (up) 0.6 else -0.6; val v = 0.9; val pushT = 1.5; val cruise = (h - v * pushT) / v
            return listOf(pushT to a, cruise to 0.0, pushT to -a)
        }
        val up1 = ride(gmc, 1, p[0], p[1], profile(5.25, true), 1)
        assertTrue("up one floor: $up1", up1.size == 1 && up1[0].to == listOf(2))
        val lift3 = gmc.conns.firstOrNull { it.kind == "lift" && 1 in it.floors && 3 in it.floors }
        if (lift3 != null) { val q = lift3.pos.getValue(1); val up2 = ride(gmc, 1, q[0], q[1], profile(10.35, true), 2)
            assertTrue("up two floors: ${up2.map { it.to }}", up2.size == 1 && up2[0].to == listOf(3)) }
        val q2 = lift.pos.getValue(2)
        val dn = ride(gmc, 2, q2[0], q2[1], profile(5.25, false), 3)
        assertTrue("down one floor: ${dn.map { it.to }}", dn.size == 1 && dn[0].to == listOf(1))
        // the phone raised and lowered by hand (0.3 m in 0.5 s and back) — no ride
        val hand = ride(gmc, 1, p[0], p[1], listOf(0.25 to 2.4, 0.25 to -2.4, 1.0 to 0.0, 0.25 to -2.4, 0.25 to 2.4), 4)
        assertTrue("hand motion is not a ride: ${hand.map { it.to }}", hand.isEmpty())
        // away from any lift — nothing
        val away = ride(gmc, 1, p[0] + 40, p[1] + 40, profile(5.25, true), 5)
        assertTrue(away.isEmpty())
    }
}
