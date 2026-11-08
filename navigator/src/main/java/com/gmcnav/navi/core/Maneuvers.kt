package com.gmcnav.navi.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class MType { START, TAKE_LEFT, TAKE_RIGHT, TURN_LEFT, TURN_RIGHT, HARD_LEFT, HARD_RIGHT, UTURN, CONNECTOR, FINISH }

/**
 * One instruction of the route. s = route distance (m) where it happens; angle = signed turn (deg, + = left).
 * For CONNECTOR: conn/kind, up, fromFloor/toFloor. hint = the area entered after the maneuver (plate's "street").
 */
class Maneuver(
    val type: MType, val s: Double, val fi: Int, val floor: Int, val x: Double, val y: Double,
    val angle: Double = 0.0, val conn: Connector? = null, val connKind: String = "",
    val fromFloor: Int = 0, val toFloor: Int = 0, var hint: String = "",
) {
    val up get() = toFloor > fromFloor
    /** Stable identity across reroutes (to never repeat a prompt for the same physical maneuver). */
    val key: String get() = "${type.name}:$floor:${(x / 4).toInt()}:${(y / 4).toInt()}" + if (type == MType.CONNECTOR) ":${conn?.id}:$toFloor" else ""

    val text: String get() = when (type) {
        MType.START -> "Идите прямо"
        MType.TAKE_LEFT -> "Держитесь левее"
        MType.TAKE_RIGHT -> "Держитесь правее"
        MType.TURN_LEFT -> "Поверните налево"
        MType.TURN_RIGHT -> "Поверните направо"
        MType.HARD_LEFT -> "Крутой поворот налево"
        MType.HARD_RIGHT -> "Крутой поворот направо"
        MType.UTURN -> "Развернитесь"
        MType.CONNECTOR -> connText()
        MType.FINISH -> "Вы пришли"
    }

    fun connText(): String {
        val verb = if (up) "Поднимитесь" else "Спуститесь"
        val how = when (connKind) { "lift" -> "на лифте"; "escalator" -> "на эскалаторе"; else -> "по лестнице" }
        return "$verb $how на $toFloor этаж"
    }

    /** Drawable name of the plate arrow. */
    val icon: String get() = when (type) {
        MType.START -> "ya_forward"
        MType.TAKE_LEFT -> "ya_take_left"; MType.TAKE_RIGHT -> "ya_take_right"
        MType.TURN_LEFT -> "ya_turn_left"; MType.TURN_RIGHT -> "ya_turn_right"
        MType.HARD_LEFT -> "ya_hard_left"; MType.HARD_RIGHT -> "ya_hard_right"
        MType.UTURN -> if (angle >= 0) "ya_uturn_left" else "ya_uturn_right"
        MType.CONNECTOR -> when (connKind) {
            "lift" -> if (up) "ya_lift_up" else "ya_lift_down"
            "escalator" -> if (up) "ya_escalator_up" else "ya_escalator_down"
            else -> if (up) "ya_stairs_up" else "ya_stairs_down"
        }
        MType.FINISH -> "ya_finish"
    }

    val isTurn get() = type in TURNS

    companion object {
        val TURNS = setOf(MType.TAKE_LEFT, MType.TAKE_RIGHT, MType.TURN_LEFT, MType.TURN_RIGHT, MType.HARD_LEFT, MType.HARD_RIGHT, MType.UTURN)
    }
}

/**
 * Turn-by-turn maneuvers from the smoothed route polyline:
 * the turn angle at each vertex is measured between the direction ~4 m before and ~4 m after it (robust to short jogs
 * through doors); vertices closer than 5 m are merged into one maneuver (two 45° bends at a corner = one 90° turn,
 * a left-right jog = nothing). Floor changes become CONNECTOR maneuvers, the end FINISH.
 */
object ManeuverBuilder {
    private const val LOOK = 4.0
    private const val GROUP = 5.0
    private const val MIN_TURN = 40.0

    fun build(b: Building, r: Route): List<Maneuver> {
        val out = ArrayList<Maneuver>()
        val l0 = r.legs[0]
        out.add(Maneuver(MType.START, 0.0, l0.fi, l0.floor, l0.xs[0], l0.ys[0]))
        for ((li, L) in r.legs.withIndex()) {
            // candidate vertices with their individual turn
            val cand = ArrayList<Pair<Int, Double>>()
            for (k in 1 until L.xs.size - 1) {
                val a = turnAt(r, L, L.ss[k], L.ss[k])
                if (abs(a) >= 12) cand.add(k to a)
            }
            // group close vertices
            var i = 0
            while (i < cand.size) {
                var j = i
                while (j + 1 < cand.size && L.ss[cand[j + 1].first] - L.ss[cand[j].first] < GROUP &&
                    L.ss[cand[j + 1].first] - L.ss[cand[i].first] < 2 * GROUP) j++
                val sA = L.ss[cand[i].first]; val sB = L.ss[cand[j].first]
                val total = turnAt(r, L, sA, sB)
                if (abs(total) >= MIN_TURN) {
                    // apex: vertex with the largest individual turn
                    var apex = cand[i]
                    for (q in i..j) if (abs(cand[q].second) > abs(apex.second)) apex = cand[q]
                    val k = apex.first
                    out.add(Maneuver(classify(total), L.ss[k], L.fi, L.floor, L.xs[k], L.ys[k], total))
                }
                i = j + 1
            }
            if (li < r.connSteps.size) {
                val cs = r.connSteps[li]
                val fromF = b.floorOf(cs.fromFi); val toF = b.floorOf(cs.toFi)
                out.add(Maneuver(MType.CONNECTOR, cs.s, L.fi, L.floor, L.xs.last(), L.ys.last(), 0.0, cs.conn, cs.kind, fromF, toF,
                    hint = cs.conn?.shortName ?: ""))
            }
        }
        val lz = r.legs.last()
        out.add(Maneuver(MType.FINISH, r.length, lz.fi, lz.floor, lz.xs.last(), lz.ys.last(), hint = r.goalTitle))
        // drop turns too close to a connector or the finish (the connector/finish instruction covers them)
        val res = ArrayList<Maneuver>()
        for (m in out) {
            if (m.isTurn) {
                if (m.s < 4.0) continue                 // the start instruction and the route line cover it
                val nextSpecial = out.firstOrNull { (it.type == MType.CONNECTOR || it.type == MType.FINISH) && it.s >= m.s }
                if (nextSpecial != null && nextSpecial.s - m.s < 3.0) continue
                val prevConn = out.lastOrNull { it.type == MType.CONNECTOR && it.s <= m.s }
                if (prevConn != null && m.s - prevConn.s < 1.0 && m.type == MType.UTURN) continue
            }
            res.add(m)
        }
        // area hints: what you walk into after a turn
        for (m in res) if (m.isTurn || m.type == MType.START) {
            val (fi, x, y) = r.pointAt(min(r.length, m.s + 4.0))
            if (fi == m.fi) m.hint = b.areaNameAt(fi, x, y)
        }
        return res
    }

    fun classify(a: Double): MType {
        val left = a > 0; val x = abs(a)
        return when {
            x >= 125 -> if (left) MType.HARD_LEFT else MType.HARD_RIGHT
            x >= 60 -> if (left) MType.TURN_LEFT else MType.TURN_RIGHT
            else -> if (left) MType.TAKE_LEFT else MType.TAKE_RIGHT
        }
    }

    /** Signed direction change (deg, + = left/counter-clockwise) from LOOK m before sA to LOOK m after sB, within the leg. */
    private fun turnAt(r: Route, L: Leg, sA: Double, sB: Double): Double {
        val sBefore = max(L.s0, sA - LOOK); val sAfter = min(L.s1, sB + LOOK)
        val pA0 = pointOnLeg(L, sBefore); val pA1 = pointOnLeg(L, sA)
        val pB0 = pointOnLeg(L, sB); val pB1 = pointOnLeg(L, sAfter)
        val d1x = pA1[0] - pA0[0]; val d1y = pA1[1] - pA0[1]
        val d2x = pB1[0] - pB0[0]; val d2y = pB1[1] - pB0[1]
        if (hypot(d1x, d1y) < 0.3 || hypot(d2x, d2y) < 0.3) return 0.0
        var a = Math.toDegrees(atan2(d2y, d2x) - atan2(d1y, d1x))
        while (a > 180) a -= 360
        while (a <= -180) a += 360
        return a
    }

    fun pointOnLeg(L: Leg, s: Double): DoubleArray {
        val ss = L.ss
        if (s <= ss[0]) return doubleArrayOf(L.xs[0], L.ys[0])
        for (k in 1 until ss.size) if (s <= ss[k]) {
            val t = if (ss[k] > ss[k - 1]) (s - ss[k - 1]) / (ss[k] - ss[k - 1]) else 0.0
            return doubleArrayOf(L.xs[k - 1] + (L.xs[k] - L.xs[k - 1]) * t, L.ys[k - 1] + (L.ys[k] - L.ys[k - 1]) * t)
        }
        return doubleArrayOf(L.xs.last(), L.ys.last())
    }

    @Suppress("unused") private const val TAU = 2 * PI
}
