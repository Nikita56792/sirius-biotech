package com.gmcnav.navi

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Material-style easing curves. */
object Ease {
    /** Fast out, slow in: camera moves, layout changes. */
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Entering elements. */
    val decel = PathInterpolator(0f, 0f, 0.2f, 1f)
    /** Leaving elements. */
    val accel = PathInterpolator(0.3f, 0f, 1f, 1f)
}

/**
 * Damped spring (mass 1, analytic solution) on one float property, driven by a ValueAnimator: vsync-aligned, honours the
 * system animator duration scale (0 = jump to the end). Keeps the velocity when retargeted, so a fling or a content resize
 * mid-flight continues smoothly (androidx SpringAnimation is not available: no androidx in this project).
 */
class Spring(private val get: () -> Float, private val set: (Float) -> Unit) {
    private var anim: ValueAnimator? = null
    private var a = 0.0; private var b = 0.0; private var v0 = 0.0; private var w = 1.0; private var z = 1.0; private var wd = 0.0
    private var t = 0.0
    var goal = 0f; private set
    val running: Boolean get() = anim != null

    private fun pos(t: Double): Double =
        if (z < 1) exp(-z * w * t) * (a * cos(wd * t) + b * sin(wd * t)) else (a + (v0 + w * a) * t) * exp(-w * t)

    private fun vel(t: Double): Double = if (z < 1) {
        val e = exp(-z * w * t)
        e * (-z * w * (a * cos(wd * t) + b * sin(wd * t)) + wd * (-a * sin(wd * t) + b * cos(wd * t)))
    } else { val c = v0 + w * a; exp(-w * t) * (c - w * (a + c * t)) }

    /** Current velocity (units/s); 0 when idle. */
    fun velocity(): Float = if (anim != null) vel(t).toFloat() else 0f

    fun animateTo(to: Float, velocity: Float = velocity(), stiffness: Float = 650f, damping: Float = 0.9f, onEnd: (() -> Unit)? = null) {
        cancel()
        goal = to
        a = (get() - to).toDouble(); v0 = velocity.toDouble(); w = sqrt(stiffness.toDouble()); z = damping.toDouble()
        if (abs(a) < 0.5 && abs(v0) < 5) { set(to); onEnd?.invoke(); return }
        wd = if (z < 1) w * sqrt(1 - z * z) else 0.0
        b = if (z < 1) (v0 + z * w * a) / wd else 0.0
        var ts = 0.0                                   // settled: < 0.5 px away and < 5 px/s, at most 1.2 s
        while (ts < 1.2 && (abs(pos(ts)) > 0.5 || abs(vel(ts)) > 5)) ts += 0.008
        val durS = max(ts, 0.016); t = 0.0
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = (durS * 1000).toLong(); an.interpolator = LinearInterpolator()
        an.addUpdateListener { t = it.animatedFraction * durS; if (anim === an) set((to + pos(t)).toFloat()) }
        an.addListener(object : AnimatorListenerAdapter() {
            var cancelled = false
            override fun onAnimationCancel(x: Animator) { cancelled = true }
            override fun onAnimationEnd(x: Animator) {
                if (anim === an) anim = null
                if (!cancelled) { set(to); onEnd?.invoke() }
            }
        })
        anim = an; an.start()
    }

    fun cancel() { val an = anim ?: return; anim = null; an.cancel() }
}

/**
 * Idempotent appear / disappear of views (alpha + optional slide / scale). Repeated calls with the same target are no-ops,
 * a reversal mid-animation continues from the current state; GONE is set only when a hide really finished.
 */
object Fx {
    private const val SHOWING = "showing"
    private const val HIDING = "hiding"

    fun show(v: View, dy: Float = 0f, scale: Float = 1f, dur: Long = 220, delay: Long = 0) {
        val st = v.getTag(R.id.fx_state)
        if (v.visibility == View.VISIBLE && st != HIDING) return
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) {
            v.alpha = 0f
            if (dy != 0f) v.translationY = dy
            if (scale != 1f) { v.scaleX = scale; v.scaleY = scale }
            v.visibility = View.VISIBLE
        }
        v.setTag(R.id.fx_state, SHOWING)
        val an = v.animate().alpha(1f).setStartDelay(delay).setDuration(dur).setInterpolator(Ease.decel)
        if (dy != 0f) an.translationY(0f)
        if (scale != 1f) an.scaleX(1f).scaleY(1f)
        an.withEndAction { if (v.getTag(R.id.fx_state) == SHOWING) v.setTag(R.id.fx_state, null) }.start()
    }

    fun hide(v: View, dy: Float = 0f, scale: Float = 1f, dur: Long = 160, gone: Int = View.GONE) {
        if (v.visibility != View.VISIBLE || v.getTag(R.id.fx_state) == HIDING) return
        v.animate().cancel()
        v.setTag(R.id.fx_state, HIDING)
        val an = v.animate().alpha(0f).setStartDelay(0).setDuration(dur).setInterpolator(Ease.accel)
        if (dy != 0f) an.translationY(dy)
        if (scale != 1f) an.scaleX(scale).scaleY(scale)
        an.withEndAction {
            if (v.getTag(R.id.fx_state) == HIDING) {
                v.visibility = gone; v.setTag(R.id.fx_state, null); v.alpha = 1f
                if (dy != 0f) v.translationY = 0f
                if (scale != 1f) { v.scaleX = 1f; v.scaleY = 1f }
            }
        }.start()
    }

    fun set(v: View, on: Boolean, dy: Float = 0f, scale: Float = 1f) = if (on) show(v, dy, scale) else hide(v, dy, scale)

    /** Shown (or about to be): visible and not fading out. */
    fun shown(v: View) = v.visibility == View.VISIBLE && v.getTag(R.id.fx_state) != HIDING
}

/** Runs [fn] once, right before the next frame is drawn (after layout). */
fun View.afterLayout(fn: () -> Unit) {
    val vto = viewTreeObserver
    vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
            fn(); return true
        }
    })
    invalidate()
}

/** One-line text that shrinks (down to [minSp]) to fit the width given by its layout, instead of being clipped. */
fun TextView.shrinkToFit(minSp: Float = 11f) {
    maxLines = 1; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
    val base = textSize
    val p = Paint()
    addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
        val avail = (r - l) - totalPaddingLeft - totalPaddingRight
        if (avail <= 0) return@addOnLayoutChangeListener
        p.set(paint); p.textSize = base
        val txt = text.toString()
        var s = base
        val min = minSp * resources.displayMetrics.scaledDensity
        while (s > min && p.measureText(txt) > avail) { s -= resources.displayMetrics.density; p.textSize = s }
        if (abs(textSize - s) > 0.5f) post { setTextSize(TypedValue.COMPLEX_UNIT_PX, s) }
    }
}

/** Largest text size (px, ≤ [maxSp]) at which [s] fits into [availPx] (for wrap-content texts like the maneuver distance). */
fun TextView.fitSize(s: String, availPx: Int, maxSp: Float, minSp: Float): Float {
    val p = Paint(paint); val sd = resources.displayMetrics.scaledDensity
    var px = maxSp * sd
    p.textSize = px
    while (px > minSp * sd && p.measureText(s) > availPx) { px -= resources.displayMetrics.density; p.textSize = px }
    return px
}

/**
 * Bottom sheet like the Yandex panels: dragged with a finger (follows it 1:1), released into the nearest resting state with
 * a spring that keeps the fling velocity. States: EXPANDED (whole content), COLLAPSED (only the top [peek] px visible),
 * HIDDEN (swiped down = dismissed, if [dismissible]). Height is capped to [maxHeightFrac] of the space below [topGap], width to
 * [maxWidthPx] (wide screens / landscape). A content resize glides instead of jumping.
 */
class DragSheet(ctx: Context) : FrameLayout(ctx) {
    enum class State { HIDDEN, COLLAPSED, EXPANDED }

    var state = State.HIDDEN; private set
    var dismissible = true
    var collapsible = false
    /** Visible height (px) in the collapsed state, measured from the laid-out content; ≤ 0 → no collapsed state. */
    var peek: () -> Int = { 0 }
    var maxHeightFrac = 0.9f
    var topGap = 0
    var maxWidthPx = 0
    /** Scrollable content: a downward drag on it first scrolls it back to the top. */
    var scrollTarget: View? = null
    /** Swiped away (called at release, before the slide-out animation). */
    var onDismiss: (() -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null

    private val spring = Spring({ translationY }, { translationY = max(0f, it) })
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val dens = resources.displayMetrics.density
    private var vt: VelocityTracker? = null
    private var downX = 0f; private var downY = 0f; private var startTy = 0f
    var dragging = false; private set

    init { isClickable = true; visibility = GONE }

    fun hasCollapsed(): Boolean { val p = peek(); return collapsible && p > 0 && p < height - 24 * dens }

    fun restingY(s: State): Float = when (s) {
        State.HIDDEN -> height + 16 * dens
        State.COLLAPSED -> if (hasCollapsed()) (height - peek()).toFloat() else 0f
        State.EXPANDED -> 0f
    }

    /** Visible height once the current animation settles (0 when hidden). */
    fun restingVisible(): Int = if (state == State.HIDDEN) 0 else (height - restingY(state)).toInt()

    /** Visible height right now. */
    fun visibleNow(): Int = if (visibility != VISIBLE) 0 else max(0, (height - translationY).toInt())

    fun show(s: State = if (collapsible) State.COLLAPSED else State.EXPANDED) {
        val wasHidden = state == State.HIDDEN || visibility != VISIBLE
        state = s
        if (wasHidden) {
            if (visibility != VISIBLE) translationY = if (height > 0) restingY(State.HIDDEN) else resources.displayMetrics.heightPixels.toFloat()
            visibility = VISIBLE
        }
        if (height > 0) settle()          // otherwise onSizeChanged starts it after the first layout
        onStateChange?.invoke(s)
    }

    fun hide(animated: Boolean = true, then: (() -> Unit)? = null) {
        if (visibility != VISIBLE) { state = State.HIDDEN; then?.invoke(); return }
        val was = state
        state = State.HIDDEN
        if (was != State.HIDDEN) onStateChange?.invoke(state)
        if (!animated || height == 0) { spring.cancel(); visibility = GONE; then?.invoke(); return }
        spring.animateTo(restingY(State.HIDDEN), stiffness = 900f, damping = 1f) { visibility = GONE; then?.invoke() }
    }

    fun setState(s: State) {
        if (s == State.HIDDEN) { hide(); return }
        if (state == State.HIDDEN) { show(s); return }
        state = s; settle(); onStateChange?.invoke(s)
    }

    fun toggle() {
        when {
            state == State.COLLAPSED && hasCollapsed() -> setState(State.EXPANDED)
            state == State.EXPANDED && hasCollapsed() -> setState(State.COLLAPSED)
        }
    }

    private fun settle(v: Float = spring.velocity()) {
        if (state == State.HIDDEN) return
        spring.animateTo(restingY(state), v, stiffness = 650f, damping = 0.9f)
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val pw = MeasureSpec.getSize(ws); val ph = MeasureSpec.getSize(hs)
        val w = if (maxWidthPx in 1 until pw) maxWidthPx else pw
        val mh = max(0, ((ph - topGap) * maxHeightFrac).toInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(mh, MeasureSpec.AT_MOST))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (state == State.HIDDEN || dragging || visibility != VISIBLE) return
        // keep the top edge where it was, then glide to the new resting position
        translationY = if (oh > 0) max(0f, translationY + (h - oh)) else h + 16 * dens
        settle()
    }

    private fun track(e: MotionEvent) {
        val c = MotionEvent.obtain(e); c.setLocation(e.rawX, e.rawY); vt?.addMovement(c); c.recycle()
    }

    private fun inside(v: View, x: Float, y: Float): Boolean {
        val l = IntArray(2); v.getLocationOnScreen(l)
        return x >= l[0] && x < l[0] + v.width && y >= l[1] && y < l[1] + v.height
    }

    private fun wants(dy: Float, e: MotionEvent): Boolean {
        val sc = scrollTarget?.takeIf { it.isShown && inside(it, e.rawX, e.rawY) }
        return if (dy > 0) (sc == null || !sc.canScrollVertically(-1)) && (dismissible || (state == State.EXPANDED && hasCollapsed()))
        else state == State.COLLAPSED && hasCollapsed()
    }

    private fun begin(e: MotionEvent) {
        dragging = true; spring.cancel(); downY = e.rawY; startTy = translationY
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); track(e) }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                val dy = e.rawY - downY; val dx = e.rawX - downX
                if (!dragging && state != State.HIDDEN && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f && wants(dy, e)) { begin(e); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) { vt?.recycle(); vt = null }
        }
        return dragging
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; vt?.recycle(); vt = VelocityTracker.obtain(); track(e) }
            MotionEvent.ACTION_MOVE -> {
                track(e)
                if (!dragging) {
                    val dy = e.rawY - downY
                    if (state != State.HIDDEN && abs(dy) > slop && wants(dy, e)) begin(e) else return true
                }
                var ty = startTy + (e.rawY - downY)
                if (!dismissible) {               // resist below the lowest resting state
                    val lim = restingY(if (hasCollapsed()) State.COLLAPSED else State.EXPANDED)
                    if (ty > lim) ty = lim + (ty - lim) * 0.25f
                }
                translationY = ty.coerceIn(0f, restingY(State.HIDDEN))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) { vt?.computeCurrentVelocity(1000); release(if (e.actionMasked == MotionEvent.ACTION_UP) vt?.yVelocity ?: 0f else 0f) }
                dragging = false; vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun release(vy: Float) {
        val states = arrayListOf(State.EXPANDED)
        if (hasCollapsed()) states.add(State.COLLAPSED)
        if (dismissible) states.add(State.HIDDEN)
        val ty = translationY
        var target = states.minByOrNull { abs(restingY(it) - (ty + vy * 0.2f)) }!!
        if (abs(vy) > 900 * dens) {        // a fling never goes against its direction
            if (vy > 0 && restingY(target) < ty) target = states.filter { restingY(it) >= ty }.minByOrNull { restingY(it) } ?: target
            if (vy < 0 && restingY(target) > ty) target = states.filter { restingY(it) <= ty }.maxByOrNull { restingY(it) } ?: target
        }
        if (target == State.HIDDEN) {
            state = State.HIDDEN; onStateChange?.invoke(state)
            spring.animateTo(restingY(State.HIDDEN), vy, stiffness = 900f, damping = 1f) { visibility = GONE }
            onDismiss?.invoke()
        } else {
            val changed = target != state
            state = target
            spring.animateTo(restingY(target), vy)
            if (changed) onStateChange?.invoke(target)
        }
    }
}

/**
 * Floating map controls laid out by hand so that nothing overlaps on any screen: top-right column (voice, floors — the floor
 * list scrolls when it does not fit), bottom-right (my location, compass) and bottom-left (closure «!») lifted above the
 * bottom panel, zoom ± centred in the space left between them (dropped when there is no room).
 */
class HudLayout(ctx: Context) : FrameLayout(ctx) {
    var insT = 0; var insB = 0; var insL = 0; var insR = 0
    /** Height covered by bottom panels under the right / left button columns (px). */
    var blockR = 0; private set
    var blockL = 0; private set
    lateinit var voice: View
    lateinit var floors: View
    lateinit var zoom: View
    lateinit var loc: View
    lateinit var compass: View
    lateinit var closure: View
    private val m = (12 * resources.displayMetrics.density).toInt()
    var zoomFits = true; private set

    init { clipChildren = false; clipToPadding = false }

    /** Moves the bottom buttons with the panels (every frame while a panel moves); re-fits the zoom when needed. */
    fun setBlocks(r: Int, l: Int) {
        if (r != blockR) {
            blockR = r
            loc.translationY = -r.toFloat(); compass.translationY = -r.toFloat()
            requestLayout()                            // floors cap and zoom position depend on it (a few views: cheap)
        }
        if (l != blockL) { blockL = l; closure.translationY = -l.toFloat() }
    }

    private fun exact(v: View) {
        val lp = v.layoutParams
        v.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY))
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs)
        setMeasuredDimension(w, h)
        for (v in listOf(voice, zoom, loc, compass, closure)) exact(v)
        var top = insT + m
        if (voice.visibility != GONE) top += voice.measuredHeight + m
        val bottom = h - insB - m - blockR - loc.measuredHeight - m
        val minFloors = (100 * resources.displayMetrics.density).toInt()
        floors.measure(MeasureSpec.makeMeasureSpec(floors.layoutParams.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(minFloors, bottom - top), MeasureSpec.AT_MOST))
    }

    private var lastFloorsTop = -1
    private var bottomOut = false
    private var closureOut = false
    private val slides = HashMap<Pair<View, String>, android.animation.ObjectAnimator>()

    /** Own animators (not ViewPropertyAnimator: Fx may cancel that one for alpha / scale at any time). */
    private fun slide(v: View, prop: android.util.Property<View, Float>, to: Float) {
        val k = v to prop.name
        slides[k]?.cancel()
        slides[k] = android.animation.ObjectAnimator.ofFloat(v, prop, to).apply { duration = 220; interpolator = Ease.standard; start() }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        val right = w - insR - m
        var y = insT + m
        if (voice.visibility != GONE) { voice.layout(right - voice.measuredWidth, y, right, y + voice.measuredHeight); y += voice.measuredHeight + m }
        // the floor column glides when the voice button comes or goes (no jump)
        if (lastFloorsTop >= 0 && lastFloorsTop != y) {
            slides[floors to View.TRANSLATION_Y.name]?.cancel()
            floors.translationY += (lastFloorsTop - y).toFloat()
            slide(floors, View.TRANSLATION_Y, 0f)
        }
        lastFloorsTop = y
        floors.layout(right - floors.measuredWidth, y, right, y + floors.measuredHeight); y += floors.measuredHeight
        val base = h - insB - m                       // bottom edge before lifting above the panel (translationY)
        loc.layout(right - loc.measuredWidth, base - loc.measuredHeight, right, base)
        val cr = right - loc.measuredWidth - m
        compass.layout(cr - compass.measuredWidth, base - compass.measuredHeight, cr, base)
        closure.layout(insL + m, base - closure.measuredHeight, insL + m + closure.measuredWidth, base)
        // a tall panel (expanded sheet): the bottom buttons slide out sideways instead of covering the floors / going off-screen
        val out = base - blockR - loc.measuredHeight < y + m
        if (out != bottomOut) {
            bottomOut = out
            val dx = if (out) (insR + m + loc.measuredWidth * 2 + m).toFloat() else 0f
            slide(loc, View.TRANSLATION_X, dx); slide(compass, View.TRANSLATION_X, dx)
        }
        val cOut = base - blockL - closure.measuredHeight < insT + 3 * m + closure.measuredHeight
        if (cOut != closureOut) {
            closureOut = cOut
            slide(closure, View.TRANSLATION_X, if (cOut) -(insL + m + closure.measuredWidth + m).toFloat() else 0f)
        }
        // zoom: around the vertical centre, between the floors and the (lifted) location button; off-screen when no room
        val lo = y + m; val hi = base - blockR - loc.measuredHeight - m
        val zh = zoom.measuredHeight
        zoomFits = hi - lo >= zh
        if (zoomFits) {
            val c = (h / 2).coerceIn(lo + zh / 2, hi - zh / 2)
            zoom.layout(right - zoom.measuredWidth, c - zh / 2, right, c - zh / 2 + zh)
        } else zoom.layout(w + 4 * m, 0, w + 4 * m + zoom.measuredWidth, zh)
    }
}

/** Corner radii (dp) of a top-rounded sheet background. */
fun Context.topRound(color: Int, radiusDp: Number): android.graphics.drawable.GradientDrawable =
    android.graphics.drawable.GradientDrawable().apply {
        setColor(color); val r = dp(radiusDp).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

@Suppress("unused") private fun clampF(v: Float, lo: Float, hi: Float) = min(max(v, lo), hi)
