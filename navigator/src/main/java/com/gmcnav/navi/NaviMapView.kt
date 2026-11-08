package com.gmcnav.navi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.Closure
import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.GroundTruth
import com.gmcnav.navi.core.Place
import com.gmcnav.navi.core.Route
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Overlay above the GL floor plan: labels, connectors, routes (active blue with white casing, alternatives lighter with
 * "+N мин" balloons, the passed part grey), start/finish pins, closures, the yellow navigation arrow with its accuracy
 * circle, and gestures (pan, pinch-zoom, two-finger rotate, tap, long-press).
 * Building coordinates are absolute plan metres; the GL map works relative to the building centre.
 */
class NaviMapView(ctx: Context) : View(ctx) {
    var data: MapData? = null
    var b: Building? = null
    var vp: Viewport? = null
    var floor = 1
    var routes: List<Route> = emptyList()
    var active = 0
    var progress = -1.0               // guidance progress on the active route (m), -1 = none
    var guiding = false
    var start: Place? = null
    var goal: Place? = null
    var closures: List<Closure> = emptyList()
    var fix: Fix? = null
    var fixAlpha = 1f
    var arrowX = Double.NaN; var arrowY = Double.NaN; var arrowFloor = 0; var arrowHeading = Double.NaN
    var truth: GroundTruth? = null
    var debug = false
    var outside = false
    var onTap: ((Int, Double, Double) -> Unit)? = null
    var onLongPress: ((Int, Double, Double) -> Unit)? = null
    var onUserGesture: (() -> Unit)? = null
    var onViewChanged: (() -> Unit)? = null
    var onClosureTap: ((Closure) -> Unit)? = null
    /** Tap on an alternative route line (route choice): its index. */
    var onRouteTap: ((Int) -> Unit)? = null
    /** Finger down on the map: camera animations stop. */
    var onTouchDown: (() -> Unit)? = null
    /** The user's СКУД pass level (lines above it are drawn as barriers). */
    var accessLevel = 1
    companion object {
        const val ACCESS_LINE_ZOOM = 8f       // px per metre: thin СКУД dashes (setting on)
        const val ACCESS_BADGE_ZOOM = 12f     // locks (setting on)
        const val MAX_ACCESS_BADGES = 6
    }
    private val icLock: Drawable? = ctx.getDrawable(R.drawable.ya_lock)
    private val closureHits = ArrayList<Pair<Closure, FloatArray>>()

    private val dp = resources.displayMetrics.density
    private val tmp = FloatArray(2); private val tmp2 = FloatArray(2)
    private val placed = ArrayList<RectF>()
    private val path = Path()
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * dp; color = Color.rgb(70, 78, 88) }
    private val pLabelZone = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * dp; color = Color.rgb(120, 128, 140) }
    private val pHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = Color.argb(230, 255, 255, 255) }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * dp; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val icLift: Drawable? = ctx.getDrawable(R.drawable.ya_glyph_lift)
    private val icEsc: Drawable? = ctx.getDrawable(R.drawable.ya_glyph_escalator)
    private val icNoEntry: Drawable? = ctx.getDrawable(R.drawable.ya_no_entry)
    private val icStairsUp: Drawable? = ctx.getDrawable(R.drawable.ya_stairs_glyph)
    private val icFinish: Drawable? = ctx.getDrawable(R.drawable.ya_finish)
    private val icBarrier: Drawable? = ctx.getDrawable(R.drawable.ya_barrier)

    private fun scr(x: Double, y: Double, out: FloatArray = tmp) {
        val d = data!!; vp!!.toScreen((x - d.cx).toFloat(), (y - d.cy).toFloat(), out)
    }

    override fun onDraw(cv: Canvas) {
        val d = data ?: return; val v = vp ?: return; val bb = b
        val f = d.floors.firstOrNull { it.n == floor } ?: return
        placed.clear()
        // labels
        var nLab = 0
        for (l in f.labels) {
            if (nLab > 220) break
            val zone = l.prio >= 2
            if (!zone && v.scale < 6f) continue
            if (zone && v.scale < 2.0f) continue
            v.toScreen(l.x, l.y, tmp)
            if (tmp[0] < -50 || tmp[1] < -20 || tmp[0] > width + 50 || tmp[1] > height + 20) continue
            val text = if (!zone && v.scale >= 20f && l.name.isNotEmpty()) "${l.text} ${l.name}" else l.text
            val p = if (zone) pLabelZone else pLabel
            val tw = p.measureText(text); val th = p.textSize
            val r = RectF(tmp[0] - tw / 2, tmp[1] - th, tmp[0] + tw / 2, tmp[1] + 3)
            if (placed.any { RectF.intersects(it, r) }) continue
            placed.add(r)
            pHalo.textSize = p.textSize
            cv.drawText(text, r.left, tmp[1], pHalo); cv.drawText(text, r.left, tmp[1], p)
            nLab++
        }
        // connectors
        if (bb != null && v.scale >= 2.5f) for (c in bb.conns) {
            val p = c.pos[floor] ?: continue
            scr(p[0], p[1])
            if (tmp[0] < -20 || tmp[1] < -20 || tmp[0] > width + 20 || tmp[1] > height + 20) continue
            val s = 9 * dp
            pFill.color = when (c.kind) { "lift" -> Color.rgb(25, 110, 255); "escalator" -> Color.rgb(140, 90, 200); else -> Color.rgb(40, 160, 90) }
            cv.drawRoundRect(tmp[0] - s, tmp[1] - s, tmp[0] + s, tmp[1] + s, 4 * dp, 4 * dp, pFill)
            val ic = when (c.kind) { "lift" -> icLift; "escalator" -> icEsc; else -> icStairsUp }
            drawIcon(cv, ic, tmp[0], tmp[1], s * 0.78f)
        }
        // closures: a "no entry" sign (passages) or a sign on the connector badge (lift/stairs out of order), tappable
        closureHits.clear()
        for (c in closures) {
            if (c.connId != 0) {
                val conn = bb?.conns?.firstOrNull { it.id == c.connId } ?: continue
                val p = conn.pos[floor] ?: continue
                scr(p[0], p[1])
                pFill.color = Color.argb(60, 229, 57, 53); cv.drawCircle(tmp[0], tmp[1], 16 * dp, pFill)
                val s = 9 * dp
                drawIcon(cv, icNoEntry, tmp[0] + s, tmp[1] - s, 8 * dp, false)
                closureHits.add(c to floatArrayOf(tmp[0], tmp[1]))
                continue
            }
            if (c.floor != floor) continue
            scr(c.x, c.y)
            if (c.radius > 0) { pFill.color = Color.argb(55, 229, 57, 53); cv.drawCircle(tmp[0], tmp[1], (c.radius * v.scale).toFloat().coerceAtLeast(12 * dp), pFill) }
            pFill.color = Color.argb(70, 0, 0, 0); cv.drawCircle(tmp[0], tmp[1] + 1.5f * dp, 13 * dp, pFill)
            drawIcon(cv, icNoEntry, tmp[0], tmp[1], 13 * dp, false)
            closureHits.add(c to floatArrayOf(tmp[0], tmp[1]))
        }
        // СКУД lines: hidden unless «Показывать проходы СКУД» is on (then thin light dashes from a close zoom); the lines that
        // matter for the active route (where it stops, or that it passes) are always drawn
        val nowMs = System.currentTimeMillis()
        val routeIds = routeAccessIds()
        if (bb != null) for (a in bb.access) {
            if (a.floor != floor || a.xs.size < 2) continue
            val onRoute = a.id in routeIds
            if (!onRoute && !(showAccess && v.scale >= ACCESS_LINE_ZOOM)) continue
            val restricted = a.levelAt(nowMs) > accessLevel
            path.reset()
            for (k in a.xs.indices) { scr(a.xs[k], a.ys[k]); if (k == 0) path.moveTo(tmp[0], tmp[1]) else path.lineTo(tmp[0], tmp[1]) }
            pStroke.pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
            val c = if (restricted) Color.rgb(230, 81, 0) else Color.rgb(120, 130, 145)
            pStroke.color = if (onRoute) c else Color.argb(120, Color.red(c), Color.green(c), Color.blue(c))
            pStroke.strokeWidth = (if (onRoute) 2.5f else 1.5f) * dp
            cv.drawPath(path, pStroke); pStroke.pathEffect = null
        }
        // routes: alternatives first, active on top
        if (routes.isNotEmpty()) {
            for ((i, r) in routes.withIndex()) if (i != active && !guiding) drawRoute(cv, r, false)
            routes.getOrNull(active)?.let { drawRoute(cv, it, true) }
            if (!guiding) for ((i, r) in routes.withIndex()) if (i != active) balloon(cv, r, routes[active])
            routes.getOrNull(active)?.let { connMarkers(cv, it) }
        }
        // СКУД locks above the routes, icon only and sparse: the route's ones always; others only with the setting on, from a
        // close zoom, at most MAX_ACCESS_BADGES, never closer than 56 dp to each other and never over a room label
        if (bb != null) {
            val all = accessBadges(bb).filter { it.line.floor == floor }
            val mine = routeBadges(all, routeIds)
            val rest = if (showAccess && v.scale >= ACCESS_BADGE_ZOOM) all.filter { it.line.id !in routeIds } else emptyList()
            val done = ArrayList<FloatArray>(); val gap = 56 * dp
            fun lock(bd: Badge, force: Boolean): Boolean {
                scr(bd.x, bd.y)
                val x = tmp[0]; val y = tmp[1]
                if (x < -20 || y < -20 || x > width + 20 || y > height + 20) return false
                if (done.any { hypot((it[0] - x).toDouble(), (it[1] - y).toDouble()) < gap }) return false
                val big = force && bd.line === routes.getOrNull(active)?.accessStop
                val r = (if (big) 10f else 8f) * dp
                if (!force && placed.any { RectF.intersects(it, RectF(x - r, y - r, x + r, y + r)) }) return false
                val restricted = bd.line.levelAt(nowMs) > accessLevel
                val col = if (restricted) Color.rgb(230, 81, 0) else Color.rgb(90, 98, 110)
                pFill.color = Color.WHITE; pFill.setShadowLayer(2.5f * dp, 0f, 1 * dp, Color.argb(70, 0, 0, 0))
                cv.drawCircle(x, y, r, pFill); pFill.clearShadowLayer()
                icLock?.let { it.setTint(col); val q = r * 0.68f; it.setBounds(Math.round(x - q), Math.round(y - q), Math.round(x + q), Math.round(y + q)); it.draw(cv) }
                done.add(floatArrayOf(x, y))
                return true
            }
            for (bd in mine) lock(bd, true)
            val cx = width / 2f; val cy = height / 2f
            var n = 0
            for (bd in rest.sortedBy { scr(it.x, it.y); hypot((tmp[0] - cx).toDouble(), (tmp[1] - cy).toDouble()) }) {
                if (n >= MAX_ACCESS_BADGES) break
                if (lock(bd, false)) n++
            }
        }
        // pins
        start?.let { if (it.floor == floor && !it.isMe) { scr(it.x, it.y); pinStart(cv, tmp[0], tmp[1]) } }
        goal?.let { if (it.floor == floor) { scr(it.x, it.y); pinFinish(cv, tmp[0], tmp[1]) } }
        // ground truth of the replay (debug)
        truth?.let { t -> if (debug && t.floor == floor) { scr(t.x, t.y); pFill.color = Color.rgb(46, 160, 67); cv.drawCircle(tmp[0], tmp[1], 6 * dp, pFill) } }
        // user
        val fx = fix
        if (fx != null && !arrowX.isNaN() && arrowFloor == floor) {
            if (debug && !fx.wifiX.isNaN() && fx.wifiFloor == floor) {
                scr(fx.wifiX, fx.wifiY); pStroke.color = Color.rgb(14, 165, 233); pStroke.strokeWidth = 2 * dp; pStroke.pathEffect = null
                cv.drawCircle(tmp[0], tmp[1], 7 * dp, pStroke)
            }
            scr(arrowX, arrowY)
            val cx = tmp[0]; val cy = tmp[1]
            pFill.color = Color.argb((50 * fixAlpha).toInt(), 25, 110, 255)
            cv.drawCircle(cx, cy, (fx.acc * v.scale).toFloat().coerceIn(14 * dp, 2000f), pFill)
            pStroke.color = Color.argb((90 * fixAlpha).toInt(), 25, 110, 255); pStroke.strokeWidth = 1.2f * dp; pStroke.pathEffect = null
            cv.drawCircle(cx, cy, (fx.acc * v.scale).toFloat().coerceIn(14 * dp, 2000f), pStroke)
            if (!arrowHeading.isNaN()) {
                scr(arrowX + sin(arrowHeading), arrowY + cos(arrowHeading), tmp2)
                val ang = atan2((tmp2[1] - cy).toDouble(), (tmp2[0] - cx).toDouble()) + PI / 2
                drawArrow(cv, cx, cy, ang.toFloat())
            } else {
                pFill.color = Color.WHITE; cv.drawCircle(cx, cy, 11 * dp, pFill)
                pFill.color = Color.rgb(255, 200, 30); cv.drawCircle(cx, cy, 8 * dp, pFill)
            }
        }
    }

    private class Badge(val line: com.gmcnav.navi.core.AccessLine, val x: Double, val y: Double)

    /** «Показывать проходы СКУД» (settings, off by default). */
    var showAccess = false

    /** СКУД lines that matter for the active route: where it stops, and lines within 2.5 m of its path (cached per route). */
    private var routeIdsKey: Any? = null; private var routeIdsCache: Set<Int> = emptySet()
    private fun routeAccessIds(): Set<Int> {
        val bb = b ?: return emptySet(); val r = routes.getOrNull(active) ?: return emptySet()
        val key = listOf(r, bb.access.size)
        if (key == routeIdsKey) return routeIdsCache
        val out = HashSet<Int>(); r.accessStop?.let { out.add(it.id) }
        for (a in bb.access) {
            val legs = r.legs.filter { it.floor == a.floor }; if (legs.isEmpty()) continue
            if (legs.any { L -> L.xs.indices.any { k -> distToLine(a, L.xs[k], L.ys[k]) < 2.5 } || (1 until L.xs.size).any { k ->
                    val n = maxOf(1, (hypot(L.xs[k] - L.xs[k - 1], L.ys[k] - L.ys[k - 1]) / 1.0).toInt())
                    (1 until n).any { i -> val t = i.toDouble() / n; distToLine(a, L.xs[k - 1] + (L.xs[k] - L.xs[k - 1]) * t, L.ys[k - 1] + (L.ys[k] - L.ys[k - 1]) * t) < 2.5 } } })
                out.add(a.id)
        }
        routeIdsKey = key; routeIdsCache = out
        return out
    }

    private fun distToLine(a: com.gmcnav.navi.core.AccessLine, x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        for (k in 1 until a.xs.size) {
            val ax = a.xs[k - 1]; val ay = a.ys[k - 1]; val vx = a.xs[k] - ax; val vy = a.ys[k] - ay; val l2 = vx * vx + vy * vy
            val t = if (l2 > 0) (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            best = minOf(best, hypot(x - ax - vx * t, y - ay - vy * t))
        }
        return best
    }

    /** One lock per route-relevant line: at its door nearest to the route (its middle if it has no doors). */
    private fun routeBadges(all: List<Badge>, ids: Set<Int>): List<Badge> {
        val r = routes.getOrNull(active) ?: return emptyList()
        return all.filter { it.line.id in ids }.groupBy { it.line.id }.values.map { bs ->
            bs.minByOrNull { bd -> r.legs.filter { it.floor == bd.line.floor }.minOfOrNull { L -> L.xs.indices.minOf { k -> hypot(L.xs[k] - bd.x, L.ys[k] - bd.y) } } ?: Double.MAX_VALUE }!!
        }
    }
    private fun accessBadges(bb: Building): List<Badge> = bb.access.flatMap { a ->
        if (a.doors.isNotEmpty()) a.doors.map { Badge(a, it.x, it.y) }
        else if (a.xs.size >= 2) listOf(Badge(a, (a.xs.first() + a.xs.last()) / 2, (a.ys.first() + a.ys.last()) / 2)) else emptyList()
    }

    private fun legPath(r: Route, from: Double, to: Double): Path? {
        val p = Path(); var any = false
        for (L in r.legs) {
            if (L.floor != floor || L.s1 < from || L.s0 > to) continue
            var started = false
            for (k in L.xs.indices) {
                val sk = L.ss[k]
                if (sk < from && k + 1 < L.xs.size && L.ss[k + 1] < from) continue
                if (sk > to && started) {
                    val pk = com.gmcnav.navi.core.ManeuverBuilder.pointOnLeg(L, to); scr(pk[0], pk[1]); p.lineTo(tmp[0], tmp[1]); break
                }
                if (!started) {
                    val pk = if (sk < from) com.gmcnav.navi.core.ManeuverBuilder.pointOnLeg(L, from) else doubleArrayOf(L.xs[k], L.ys[k])
                    scr(pk[0], pk[1]); p.moveTo(tmp[0], tmp[1]); started = true; any = true
                    if (sk < from) continue
                } else { scr(L.xs[k], L.ys[k]); p.lineTo(tmp[0], tmp[1]) }
            }
        }
        return if (any) p else null
    }

    private fun drawRoute(cv: Canvas, r: Route, act: Boolean) {
        pStroke.pathEffect = null
        val w = if (act) 7f * dp else 5.5f * dp
        val prog = if (act && progress >= 0) progress else 0.0
        if (prog > 0) legPath(r, 0.0, prog)?.let {
            pStroke.color = Color.WHITE; pStroke.strokeWidth = w + 3 * dp; cv.drawPath(it, pStroke)
            pStroke.color = Color.rgb(170, 178, 190); pStroke.strokeWidth = w; cv.drawPath(it, pStroke)
        }
        val p = legPath(r, prog, r.length) ?: return
        pStroke.color = Color.WHITE; pStroke.strokeWidth = w + 3.5f * dp; cv.drawPath(p, pStroke)
        pStroke.color = if (act) Color.rgb(25, 110, 255) else Color.rgb(140, 175, 235); pStroke.strokeWidth = w; cv.drawPath(p, pStroke)
        // chevrons along the active route
        if (act && vp!!.scale > 4f) {
            pStroke.color = Color.argb(200, 255, 255, 255); pStroke.strokeWidth = 1.6f * dp
            var s = prog + 6.0
            while (s < r.length) {
                val (fi, x, y) = r.pointAt(s)
                if (b?.floorOf(fi) == floor) {
                    val h = r.headingAt(s)
                    scr(x, y); val ax = tmp[0]; val ay = tmp[1]
                    scr(x + sin(h), y + cos(h), tmp2)
                    val a = atan2((tmp2[1] - ay).toDouble(), (tmp2[0] - ax).toDouble())
                    val l = 3.2f * dp
                    cv.drawLine(ax - (l * cos(a - 0.6)).toFloat(), ay - (l * sin(a - 0.6)).toFloat(), ax, ay, pStroke)
                    cv.drawLine(ax - (l * cos(a + 0.6)).toFloat(), ay - (l * sin(a + 0.6)).toFloat(), ax, ay, pStroke)
                }
                s += (60 * dp / vp!!.scale).coerceAtLeast(5f)
            }
        }
    }

    private fun connMarkers(cv: Canvas, r: Route) {
        val bb = b ?: return
        for (cs in r.connSteps) {
            val fromF = bb.floorOf(cs.fromFi); val toF = bb.floorOf(cs.toFi)
            val between = floor != fromF && floor != toF && floor in minOf(fromF, toF)..maxOf(fromF, toF)
            if (floor != fromF && floor != toF && !between) continue
            if (between) {
                // a floor passed on the way (2→4 by the stairs: floor 3): the stairwell / lift there, "→ 4 эт."
                val pos = cs.conn?.pos?.get(floor) ?: continue
                scr(pos[0], pos[1])
            } else {
                val (fi, x, y) = r.pointAt(if (floor == fromF) cs.s - 0.01 else cs.s + 0.02)
                if (bb.floorOf(fi) != floor) continue
                scr(x, y)
            }
            val ic = when (cs.kind) { "lift" -> icLift; "escalator" -> icEsc; else -> icStairsUp }
            drawBadge(cv, tmp[0], tmp[1], Color.rgb(25, 110, 255), ic)
            val t = if (floor == fromF || between) "→ $toF эт." else "с $fromF эт."
            pText.textSize = 12 * dp; pText.color = Color.rgb(25, 110, 255)
            pHalo.textSize = pText.textSize
            val tw = pText.measureText(t)
            cv.drawText(t, tmp[0] - tw / 2 + 14 * dp + tw / 2, tmp[1] - 14 * dp, pHalo.apply { textAlign = Paint.Align.CENTER })
            cv.drawText(t, tmp[0] + 14 * dp + tw / 2, tmp[1] - 14 * dp, pText)
            pHalo.textAlign = Paint.Align.LEFT
        }
    }

    /** Balloon anchor per (alternative, best, floor): the point of r farthest from the best route (computed once, not per frame). */
    private val balloonAt = HashMap<Triple<Route, Route, Int>, DoubleArray?>()

    private fun balloon(cv: Canvas, r: Route, best: Route) {
        val bb = b ?: return
        val dt = ((r.time - best.time) / 60.0)
        if (kotlin.math.abs(dt) < 0.5) return            // same time: no balloon (as in Yandex)
        val txt = if (dt > 0) "+${kotlin.math.round(dt).toInt().coerceAtLeast(1)} мин" else "−${kotlin.math.round(-dt).toInt().coerceAtLeast(1)} мин"
        if (balloonAt.size > 64) balloonAt.clear()
        val at = balloonAt.getOrPut(Triple(r, best, floor)) {
            // a point of r far from the best route, on this floor
            var bestS = -1.0; var bestD = -1.0
            var s = 0.0
            while (s < r.length) {
                val (fi, x, y) = r.pointAt(s)
                if (bb.floorOf(fi) == floor) { val p = best.project(fi, x, y); val d = p?.dist ?: 50.0; if (d > bestD) { bestD = d; bestS = s } }
                s += 4.0
            }
            if (bestS < 0) null else r.pointAt(bestS).let { doubleArrayOf(it.second, it.third) }
        } ?: return
        scr(at[0], at[1])
        pText.textSize = 15 * dp; pText.color = Color.rgb(230, 60, 50)
        val tw = pText.measureText(txt) + 20 * dp
        pFill.color = Color.WHITE; pFill.setShadowLayer(4 * dp, 0f, 1 * dp, Color.argb(60, 0, 0, 0))
        cv.drawRoundRect(tmp[0] + 6 * dp, tmp[1] - 34 * dp, tmp[0] + 6 * dp + tw, tmp[1] - 4 * dp, 15 * dp, 15 * dp, pFill)
        pFill.clearShadowLayer()
        cv.drawText(txt, tmp[0] + 6 * dp + tw / 2, tmp[1] - 13 * dp, pText)
    }

    /** Draws a square drawable centred exactly on (x, y) with half-size r. */
    private fun drawIcon(cv: Canvas, ic: Drawable?, x: Float, y: Float, r: Float, tintWhite: Boolean = true) {
        ic ?: return
        if (tintWhite) ic.setTint(Color.WHITE) else ic.setTintList(null)
        ic.setBounds(Math.round(x - r), Math.round(y - r), Math.round(x + r), Math.round(y + r)); ic.draw(cv)
    }

    private fun drawBadge(cv: Canvas, x: Float, y: Float, col: Int, ic: Drawable?) {
        val s = 12 * dp
        pFill.color = Color.WHITE; cv.drawCircle(x, y, s + 2 * dp, pFill)
        pFill.color = col; cv.drawCircle(x, y, s, pFill)
        drawIcon(cv, ic, x, y, s * 0.72f)
    }

    private fun pinStart(cv: Canvas, x: Float, y: Float) {
        pFill.color = Color.WHITE; cv.drawCircle(x, y, 9 * dp, pFill)
        pFill.color = Color.rgb(244, 67, 54); cv.drawCircle(x, y, 6.5f * dp, pFill)
    }

    private fun pinFinish(cv: Canvas, x: Float, y: Float) {
        val r = 17 * dp; val cy = y - 26 * dp
        pFill.color = Color.rgb(33, 33, 33); pFill.setShadowLayer(4 * dp, 0f, 2 * dp, Color.argb(80, 0, 0, 0))
        path.reset(); path.addCircle(x, cy, r, Path.Direction.CW); path.moveTo(x - 7 * dp, cy + r - 3 * dp); path.lineTo(x, y - 4 * dp); path.lineTo(x + 7 * dp, cy + r - 3 * dp); path.close()
        cv.drawPath(path, pFill); pFill.clearShadowLayer()
        icFinish?.let { it.setTint(Color.WHITE); it.setBounds((x - r * 0.62f).toInt(), (cy - r * 0.62f).toInt(), (x + r * 0.62f).toInt(), (cy + r * 0.62f).toInt()); it.draw(cv) }
        pFill.color = Color.rgb(33, 33, 33); cv.drawCircle(x, y, 3 * dp, pFill)
    }

    /** Yandex-like yellow navigation arrow pointing to screen angle ang (0 = up). */
    private fun drawArrow(cv: Canvas, x: Float, y: Float, ang: Float) {
        cv.save(); cv.translate(x, y); cv.rotate(Math.toDegrees(ang.toDouble()).toFloat())
        val s = 15 * dp
        path.reset()
        path.moveTo(0f, -s * 1.15f); path.lineTo(s * 0.9f, s * 0.95f); path.lineTo(0f, s * 0.45f); path.lineTo(-s * 0.9f, s * 0.95f); path.close()
        pFill.color = Color.argb((255 * fixAlpha).toInt(), 255, 255, 255); pFill.setShadowLayer(5 * dp, 0f, 2 * dp, Color.argb(90, 0, 0, 0))
        pStroke.color = Color.WHITE; pStroke.strokeWidth = 5 * dp; pStroke.pathEffect = null
        cv.drawPath(path, pStroke)
        pFill.clearShadowLayer()
        pFill.color = Color.argb((255 * fixAlpha).toInt(), 255, 204, 0); cv.drawPath(path, pFill)
        // darker right half like the 3D Yandex cursor
        path.reset(); path.moveTo(0f, -s * 1.15f); path.lineTo(s * 0.9f, s * 0.95f); path.lineTo(0f, s * 0.45f); path.close()
        pFill.color = Color.argb((255 * fixAlpha).toInt(), 240, 170, 0); cv.drawPath(path, pFill)
        cv.restore()
    }

    // ---------------------------------------------------------------- gestures
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var moved = false; private var multi = false; private var longFired = false
    private var pMidX = 0f; private var pMidY = 0f; private var pDist = 0f; private var pAng = 0f; private var rotAcc = 0f; private var rotOn = false
    private val longPress = Runnable {
        if (!moved && !multi) { longFired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); worldAt(downX, downY)?.let { onLongPress?.invoke(floor, it.first, it.second) } }
    }

    private fun worldAt(x: Float, y: Float): Pair<Double, Double>? {
        val v = vp ?: return null; val d = data ?: return null
        v.toWorld(x, y, tmp2); return (tmp2[0] + d.cx) to (tmp2[1] + d.cy)
    }

    private var vt: android.view.VelocityTracker? = null
    private var fling: android.animation.ValueAnimator? = null
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4

    fun stopFling() { fling?.cancel(); fling = null }

    /** Pan inertia after a one-finger swipe: exponential decay (friction 4.5/s). */
    private fun startFling(vx0: Float, vy0: Float) {
        stopFling()
        var vx = vx0; var vy = vy0; var last = -1L
        val an = android.animation.ValueAnimator.ofFloat(0f, 1f)
        an.duration = 2500; an.interpolator = android.view.animation.LinearInterpolator()
        an.addUpdateListener {
            val t = it.currentPlayTime
            if (last < 0) { last = t; return@addUpdateListener }
            val dt = ((t - last) / 1000f).coerceIn(0f, 0.05f); last = t
            val k = kotlin.math.exp(-4.5f * dt)
            val f = (1 - k) / 4.5f                         // ∫ e^(−λt) over the frame
            vp?.pan(vx * f, vy * f); vx *= k; vy *= k
            changed()
            if (hypot(vx, vy) < 25f) stopFling()
        }
        fling = an; an.start()
    }

    /** Index of an alternative (non-active) route drawn within 18 dp of the screen point, on this floor. */
    private fun routeHit(x: Float, y: Float): Int? {
        if (guiding || routes.size < 2) return null
        var best = -1; var bd = 18 * dp
        for ((i, r) in routes.withIndex()) {
            if (i == active) continue
            for (L in r.legs) {
                if (L.floor != floor) continue
                var px = 0f; var py = 0f
                for (k in L.xs.indices) {
                    scr(L.xs[k], L.ys[k])
                    if (k > 0) {
                        val vx = tmp[0] - px; val vy = tmp[1] - py; val l2 = vx * vx + vy * vy
                        val t = if (l2 > 0) (((x - px) * vx + (y - py) * vy) / l2).coerceIn(0f, 1f) else 0f
                        val d = hypot(x - px - vx * t, y - py - vy * t)
                        if (d < bd) { bd = d; best = i }
                    }
                    px = tmp[0]; py = tmp[1]
                }
            }
        }
        return best.takeIf { it >= 0 }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val v = vp ?: return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { vt?.recycle(); vt = android.view.VelocityTracker.obtain() }
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; lastX = e.x; lastY = e.y; moved = false; multi = false; longFired = false
                stopFling(); onTouchDown?.invoke()
                postDelayed(longPress, 550) }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) { multi = true; removeCallbacks(longPress); two(e); rotAcc = 0f; rotOn = false; onUserGesture?.invoke() }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    val dist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    val ang = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                    var da = ang - pAng; if (da > PI) da -= (2 * PI).toFloat(); if (da < -PI) da += (2 * PI).toFloat()
                    rotAcc += da
                    if (!rotOn && abs(rotAcc) > 0.2f) rotOn = true
                    v.twoFinger(pMidX, pMidY, mx, my, if (pDist > 0) dist / pDist else 1f, if (rotOn) da else 0f)
                    pMidX = mx; pMidY = my; pDist = dist; pAng = ang
                    changed()
                } else if (!multi) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; removeCallbacks(longPress); onUserGesture?.invoke() }
                    if (moved) { v.pan(e.x - lastX, e.y - lastY); changed() }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> { val rest = if (e.actionIndex == 0) 1 else 0; lastX = e.getX(rest); lastY = e.getY(rest); if (e.pointerCount == 3) two(e) }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!moved && !multi && !longFired && e.eventTime - downT < 400) {
                    val hit = closureHits.minByOrNull { hypot(it.second[0] - e.x, it.second[1] - e.y) }
                    val ri = if (hit == null || hypot(hit.second[0] - e.x, hit.second[1] - e.y) >= 26 * dp) routeHit(e.x, e.y) else null
                    if (hit != null && hypot(hit.second[0] - e.x, hit.second[1] - e.y) < 26 * dp) onClosureTap?.invoke(hit.first)
                    else if (ri != null && onRouteTap != null) onRouteTap?.invoke(ri)
                    else worldAt(e.x, e.y)?.let { onTap?.invoke(floor, it.first, it.second) }
                } else if (moved && !multi) {
                    vt?.let { t -> t.computeCurrentVelocity(1000); if (hypot(t.xVelocity, t.yVelocity) > minFling) startFling(t.xVelocity, t.yVelocity) }
                }
                vt?.recycle(); vt = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); vt?.recycle(); vt = null }
        }
        return true
    }

    private fun two(e: MotionEvent) {
        pMidX = (e.getX(0) + e.getX(1)) / 2; pMidY = (e.getY(0) + e.getY(1)) / 2
        pDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)); pAng = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
    }

    fun changed() { invalidate(); onViewChanged?.invoke() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vp?.let { synchronized(it) { it.w = w; it.h = h } }
        super.onSizeChanged(w, h, ow, oh)
    }

    @Suppress("unused") private fun dashed() = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
    @Suppress("unused") private fun now() = SystemClock.uptimeMillis()
}
