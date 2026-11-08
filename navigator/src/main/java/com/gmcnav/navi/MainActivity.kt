package com.gmcnav.navi

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.Fix
import com.gmcnav.navi.core.Guidance
import com.gmcnav.navi.core.GuidanceState
import com.gmcnav.navi.core.MType
import com.gmcnav.navi.core.Maneuver
import com.gmcnav.navi.core.Orientation
import com.gmcnav.navi.core.Place
import com.gmcnav.navi.core.PlanGate
import com.gmcnav.navi.core.Route
import com.gmcnav.navi.core.SearchItem
import com.gmcnav.navi.core.Sites
import com.gmcnav.navi.core.VoiceMode
import com.gmcnav.navi.core.VoicePlanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * ГМЦ Навигатор: indoor turn-by-turn navigation for Sirius University (former Main Media Center, Olimpiyskiy pr. 1)
 * modelled on Yandex Navigator. One activity, views built in code (no androidx). Edge-to-edge: every control is placed
 * from the system bar / cutout / keyboard insets; bottom panels are draggable sheets ([DragSheet]); floating buttons are
 * laid out by [HudLayout] so that nothing overlaps on small screens or in landscape.
 */
class MainActivity : Activity() {
    private enum class Mode { BROWSE, PLAN, GUIDE }

    companion object {
        private const val TAG = "GmcNavi"
        /** Positioning source given by launch extras («source»): for this process only, never saved. */
        @Volatile var sessionSource: String? = null
        private var devOpen = false
        // developer screen emulation (launch extras «screen» = WxH dp, «font» = scale): density / font override
        private var emuDpi = 0
        private var emuFont = 0f
        private var emuH = 0
        /** The activity is being recreated for another site: start with the loading overlay instead of the splash. */
        private var switchingTo: String? = null
        /** Map ground colour (splash, loading screens, GL clear colour). */
        private const val MAP_BG = 0xFFECEFEA.toInt()
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var gl: MapGLView
    private lateinit var map: NaviMapView
    private val vp = Viewport()
    private var data: MapData? = null
    private lateinit var pos: Positioning
    private lateinit var voice: VoicePlayer
    private val vplan = VoicePlanner()

    private var mode = Mode.BROWSE
    private var floor = 2
    private var follow = true
    private var lastFix: Fix? = null
    private var lastUserFloor = 0
    /** When the user last chose a floor by hand (elapsedRealtime ms). */
    private var floorPickedMs = 0L
    private var fixT = 0L
    private var from: Place? = null      // null = my location
    private var to: Place? = null
    private var routes: List<Route> = emptyList()
    private var active = 0
    @Volatile private var guidance: Guidance? = null
    @Volatile private var gstate: GuidanceState? = null
    private var accessible = false
    private var rerouting = false
    private var noRouteToastT = 0L

    /** Route panel session: closing it or a newer plan drops late results and cancels the search (router.gen). */
    private val planGate = PlanGate { if (Core.ready) Core.router.gen++ }
    /** Guidance session: reroute results of a finished guidance are dropped. */
    private var guideSeq = 0
    private var pendingGo = false
    private var arrivedShown = false

    // system bars / cutout / keyboard (px)
    private var insT = 0; private var insB = 0; private var insL = 0; private var insR = 0; private var imeB = 0

    // views
    private lateinit var status: TextView
    private lateinit var hud: HudLayout
    private lateinit var floorBox: LinearLayout
    private lateinit var floorsScroll: ScrollView
    private val floorBtns = HashMap<Int, TextView>()
    private lateinit var plate: LinearLayout
    private lateinit var plateMain: LinearLayout
    private lateinit var plateIcon: ImageView
    private lateinit var plateDist: TextView
    private lateinit var plateHint: TextView
    private lateinit var plateNext: LinearLayout
    private lateinit var plateNextIcon: ImageView
    private lateinit var browseBar: LinearLayout
    private lateinit var planSheet: DragSheet
    private lateinit var etaSheet: DragSheet
    private lateinit var etaRow: LinearLayout
    private lateinit var etaDist: TextView
    private lateinit var etaTime: TextView
    private lateinit var etaMin: TextView
    private lateinit var etaProgress: ProgressBar
    private lateinit var etaSoundIcon: ImageView
    private lateinit var etaSoundLabel: TextView
    private lateinit var voiceBtn: FrameLayout
    private lateinit var locBtn: FrameLayout
    private lateinit var compassBtn: FrameLayout
    private lateinit var closureBtn: FrameLayout
    private lateinit var zoomBox: LinearLayout
    private var overlay: View? = null
    private lateinit var loadingView: FrameLayout
    private lateinit var loadingTitle: TextView
    private lateinit var loadingText: TextView
    private var loader: SiteLoader? = null
    private var mapShown = false
    private var footprintFromCache = false
    private var userMovedMap = false
    /** An action asked for before places / search / routes were loaded: runs as soon as they are. */
    private var pendingReady: (() -> Unit)? = null
    private var holdDraw = false
    private var splashUp = false

    private class SheetH(val holder: FrameLayout, val card: DragSheet, val dim: View?, val modal: Boolean, val onDismiss: (() -> Unit)?)
    private var sheetH: SheetH? = null

    private var camAnim: ValueAnimator? = null
    private var camGoal: Cam? = null
    private class Cam(val cx: Float, val cy: Float, val scale: Float, val rot: Float)

    // ================================================================== lifecycle
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (emuDpi > 0 || emuFont > 0f) applyOverrideConfiguration(Configuration().apply {
            if (emuDpi > 0) densityDpi = emuDpi
            if (emuFont > 0f) fontScale = emuFont
        })
    }

    private lateinit var sbsBack: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("navi", MODE_PRIVATE)
        // the old version saved a demo source given by launch extras for good; start from the live position once
        if (!prefs.getBoolean("srcV2", false)) prefs.edit().putString("source", "live").putBoolean("srcV2", true).apply()
        accessible = prefs.getBoolean("accessible", false)
        vplan.mode = VoiceMode.valueOf(prefs.getString("voice", "ALL")!!)
        root = FrameLayout(this).apply { noClip() }
        if (emuH > 0) setContentView(root, FrameLayout.LayoutParams(MATCH, dp(emuH), Gravity.CENTER)) else setContentView(root)
        edgeToEdge()
        root.setOnApplyWindowInsetsListener { _, wi -> readInsets(wi); applyInsets(); wi }
        buildLoadingView()
        root.addView(loadingView, lp(MATCH, MATCH))
        sbsBack = text("В меню SBS", 14, Ya.TEXT, true).apply {
            gravity = Gravity.CENTER; background = ripple(Color.WHITE, 12)
            elevation = dp(80).toFloat(); contentDescription = "Вернуться в главное меню SBS"
            setOnClickListener {
                startActivity(Intent().setClassName(this@MainActivity, "org.sirius.biotech.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        root.addView(sbsBack, lp(dp(116), dp(48), Gravity.TOP or Gravity.START))
        val switching = switchingTo != null; switchingTo = null
        if (!switching && savedInstanceState == null) holdFirstDraw()
        voice = VoicePlayer(applicationContext)
        voice.onSpoken = { p -> android.util.Log.i(TAG, "voice: «${p.text}» [${p.parts.joinToString(" ") { "${it.key}/${it.file}" }}]") }
        // weighted voice variants (assets/voice_variants.json); the rare arrival joke is at most once per day (persisted)
        runCatching { com.gmcnav.navi.core.VoiceVariants(assets.open("voice_variants.json").bufferedReader().readText(), System.nanoTime()) }.getOrNull()?.let { vv ->
            for (op in vv.events.values.flatten()) if (op.rare != null) prefs.getString("rare_${op.id}", null)?.let { vv.rareDays[op.id] = it }
            vv.onRareUsed = { id, day -> prefs.edit().putString("rare_$id", day).apply() }
            vplan.variants = vv
        }
        voice.muted = vplan.mode == VoiceMode.OFF
        // floor recordings present in the pack (a floor without one is announced without the floor)
        val recCache = HashMap<String, Boolean>()
        com.gmcnav.navi.core.Phrases.hasRec = { k -> recCache.getOrPut(k) { runCatching { assets.openFd("sounds/ru_alice/$k/0.mp3").close(); true }.getOrDefault(false) } }
        pos = Positioning(this) { onFix(it) }
        intent.getStringExtra("source")?.let { s -> if (s in listOf("live", "replay", "sim")) sessionSource = s }   // before the source starts
        LiveSource.sysScans = intent.getBooleanExtra("sysscan", true)
        val t = android.os.SystemClock.elapsedRealtime()
        loader = Core.load(this, loadListener)        // stages already in memory (site switch) arrive right here
        loadingTitle.text = Core.site.short
        android.util.Log.i(TAG, "start: onCreate done at process t+${t - android.os.Process.getStartElapsedRealtime()} ms")
    }

    // ------------------------------------------------------------------ staged loading
    private val loadListener = object : Core.LoadListener {
        override fun onMap(d: MapData) { if (!isDestroyed) setupMap(d) }
        override fun onFootprint(fp: DoubleArray) { if (!isDestroyed) onFootprintReady(fp) }
        override fun onModel() { if (!isDestroyed) onModelReady() }
        override fun onRadio() { if (!isDestroyed) onRadioReady() }
        override fun onError(msg: String) { if (!isDestroyed) { releaseFirstDraw(); Fx.show(loadingView); loadingText.text = "Ошибка загрузки: $msg" } }
    }

    private fun since() = android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()

    /** Loading screen = the splash look (map ground + logo) + the site name and a thin progress bar; the map fades in over it. */
    private fun buildLoadingView() {
        loadingView = FrameLayout(this).apply { setBackgroundColor(MAP_BG); isClickable = true; elevation = dp(70).toFloat() }
        loadingView.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) }, lp(dp(96), dp(96), Gravity.CENTER))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        loadingTitle = text("", 20, Ya.TEXT, true).apply { gravity = Gravity.CENTER }
        col.addView(loadingTitle, llp(MATCH, WRAP))
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Ya.BLUE) }, llp(dp(140), dp(6)).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        loadingText = text("", 14, Ya.TEXT2).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(10), dp(24), 0) }
        col.addView(loadingText, llp(MATCH, WRAP))
        loadingView.addView(col, lp(MATCH, WRAP, Gravity.CENTER).apply { topMargin = dp(150) })
    }

    /** Android 12+: the system splash (same colour and logo) stays until the map can be drawn (at most 2.5 s), then fades out. */
    private val drawGate = ViewTreeObserver.OnPreDrawListener { !holdDraw }
    private fun holdFirstDraw() {
        if (Build.VERSION.SDK_INT < 31) return
        holdDraw = true; splashUp = true
        root.viewTreeObserver.addOnPreDrawListener(drawGate)
        main.postDelayed({ releaseFirstDraw() }, 2500)
        splashScreen.setOnExitAnimationListener { v ->
            v.iconView?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(280)?.start()
            v.animate().alpha(0f).setDuration(280).setInterpolator(Ease.standard).withEndAction { v.remove(); splashUp = false }.start()
        }
    }
    private fun releaseFirstDraw() {
        if (!holdDraw) return
        holdDraw = false
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(drawGate)
        root.invalidate()
    }

    /** The map is laid out: hand over from the splash / loading screen (fade), plain window background from now on. */
    private fun revealMap() {
        val underSplash = holdDraw
        releaseFirstDraw()
        if (underSplash) root.removeView(loadingView)                 // the system splash fades out over the map
        else loadingView.animate().alpha(0f).setDuration(300).setInterpolator(Ease.standard).withEndAction { root.removeView(loadingView) }.start()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MAP_BG))
        android.util.Log.i(TAG, "start: map on screen at process t+${since()} ms")
    }

    override fun onDestroy() {
        camAnim?.cancel(); arrowAnim?.cancel()
        loader?.unlisten(loadListener)
        main.removeCallbacksAndMessages(null)
        if (::hud.isInitialized && root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
        stopService(Intent(this, NavigationService::class.java))
        stopGeo(); pos.quit(); voice.release(); super.onDestroy()
    }
    override fun onResume() {
        super.onResume(); visible = true
        if (::gl.isInitialized) gl.onResume()
        if (Core.radioReady) startSource()
    }
    override fun onPause() {
        visible = false
        if (mode != Mode.GUIDE) { pos.stop(); stopGeo(); voice.stopAll(); main.removeCallbacks(statusTick) }
        camAnim?.cancel(); arrowAnim?.cancel()
        if (::gl.isInitialized) gl.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::hud.isInitialized) root.afterLayout { applyAdaptive() }
    }

    @Deprecated("") override fun onBackPressed() {
        when {
            sheetH != null -> closeSheet()
            overlay != null -> closeOverlay()
            !::planSheet.isInitialized -> @Suppress("DEPRECATION") super.onBackPressed()
            mode == Mode.GUIDE -> if (etaSheet.state == DragSheet.State.EXPANDED) etaSheet.setState(DragSheet.State.COLLAPSED) else askFinish()
            mode == Mode.PLAN -> if (planSheet.state == DragSheet.State.EXPANDED && planSheet.hasCollapsed()) planSheet.setState(DragSheet.State.COLLAPSED) else closePlan()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ edge-to-edge, insets, screen sizes
    private fun edgeToEdge() {
        val w = window
        w.statusBarColor = Color.TRANSPARENT; w.navigationBarColor = Color.TRANSPARENT
        w.isStatusBarContrastEnforced = false; w.isNavigationBarContrastEnforced = false
        w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(light, light)
        } else @Suppress("DEPRECATION") {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun readInsets(wi: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val s = wi.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insT = s.top; insB = s.bottom; insL = s.left; insR = s.right
            imeB = wi.getInsets(WindowInsets.Type.ime()).bottom
        } else @Suppress("DEPRECATION") {
            val c = wi.displayCutout
            insT = max(wi.stableInsetTop, c?.safeInsetTop ?: 0); insB = wi.stableInsetBottom
            insL = max(wi.stableInsetLeft, c?.safeInsetLeft ?: 0); insR = max(wi.stableInsetRight, c?.safeInsetRight ?: 0)
            imeB = wi.systemWindowInsetBottom
        }
        if (emuH > 0) { insT = max(insT, dp(24)); insB = max(insB, dp(16)) }
        insT += dp(56) // Reserve a toolbar row above navigation controls.
    }

    /** Positions every inset-dependent control (called on inset changes: rotation, keyboard, cutout). */
    private fun applyInsets() {
        if (::sbsBack.isInitialized) {
            (sbsBack.layoutParams as FrameLayout.LayoutParams).apply { topMargin = insT - dp(48); leftMargin = insL + dp(8) }
            sbsBack.requestLayout()
        }
        overlay?.setPadding(insL, insT, insR, max(insB, imeB))
        if (!::hud.isInitialized) return
        (status.layoutParams as FrameLayout.LayoutParams).topMargin = insT + dp(8)
        (plate.layoutParams as FrameLayout.LayoutParams).apply { topMargin = insT + dp(8); leftMargin = insL + dp(12); rightMargin = insR + dp(84) }
        hud.insT = insT; hud.insB = insB; hud.insL = insL; hud.insR = insR
        planSheet.getChildAt(0).let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, dp(14) + insB) }
        etaSheet.getChildAt(0).let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, dp(12) + insB) }
        for (s in listOf(planSheet, etaSheet)) s.topGap = insT + dp(8)
        sheetH?.card?.let { it.setPadding(0, dp(10), 0, dp(16) + insB); it.topGap = insT + dp(8) }
        applyAdaptive()
        hud.requestLayout(); root.requestLayout()
    }

    private fun landscape() = root.width > root.height && root.height > 0

    /** Width cap of the panels: none on phones in portrait; ≤ 440 dp on wide screens and in landscape. */
    private fun sideWidth(): Int {
        val w = root.width; if (w == 0) return 0
        return if (w >= dp(600) || landscape()) min(dp(440), (w * 0.62f).toInt()) else 0
    }

    private fun panelGravity() = if (landscape()) Gravity.BOTTOM or Gravity.START else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL

    /** Short screens (landscape phones): the route panel drops secondary lines so «Поехали» always fits. */
    private var compactH = false

    /** Re-fits the panels to the screen: narrow side panels in landscape / on tablets, full width on phones. */
    private fun applyAdaptive() {
        if (!::hud.isInitialized) return
        val compact = root.height in 1 until dp(520)
        if (compact != compactH) {
            compactH = compact
            planFrom.visibility = if (compact) View.GONE else View.VISIBLE
            if (mode == Mode.PLAN) fillPlanSheet()
        }
        val sw = sideWidth(); val g = panelGravity(); val left = if (landscape()) insL else 0
        for (s in listOfNotNull(planSheet, etaSheet, sheetH?.card)) {
            s.maxWidthPx = sw
            (s.layoutParams as FrameLayout.LayoutParams).apply { gravity = g; leftMargin = left }
            s.requestLayout()
        }
        (browseBar.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (sw > 0) sw else MATCH; gravity = g
            leftMargin = insL + dp(12); rightMargin = insR + dp(12); bottomMargin = insB + dp(14)
        }
        browseBar.requestLayout()
    }

    private var lastW = 0; private var lastH = 0
    private val preDraw = ViewTreeObserver.OnPreDrawListener { trackPanels(); true }

    /**
     * Every frame: how much of the screen bottom the panels cover right now (they move while dragged / animated), so the
     * floating buttons ride on top of them; the status pill goes under the maneuver plate while guiding.
     */
    private fun trackPanels() {
        if (!::hud.isInitialized) return
        val H = root.height; val W = root.width
        if (H == 0) return
        if (W != lastW || H != lastH) { lastW = W; lastH = H; main.post { applyAdaptive() } }
        var bR = 0; var bL = 0
        val colL = W - insR - dp(80); val colR = insL + dp(80)
        fun acc(v: View, visible: Int) {
            if (visible <= 0) return
            val cover = max(0, visible - insB)
            if (v.right > colL) bR = max(bR, cover)
            if (v.left < colR) bL = max(bL, cover)
        }
        if (browseBar.visibility == View.VISIBLE) acc(browseBar, (H - browseBar.top - browseBar.translationY).toInt() + dp(4))
        acc(planSheet, planSheet.visibleNow()); acc(etaSheet, etaSheet.visibleNow())
        sheetH?.let { if (!it.modal) acc(it.card, it.card.visibleNow()) }
        hud.setBlocks(bR, bL)
        val st = if (mode == Mode.GUIDE && plate.visibility == View.VISIBLE) max(0f, plate.bottom + dp(8f) - status.top) else 0f
        if (status.translationY != st) status.translationY = st
    }

    /** Map area not covered by the panels at rest (screen px): where fitted content is centred. */
    private fun freeRect(): RectF {
        val W = root.width.toFloat(); val H = root.height.toFloat()
        var left = insL + dp(16f); val right = W - insR - dp(76f); val top = insT + dp(52f); var bottom = H - insB - dp(12f)
        val p: View = when (mode) { Mode.PLAN -> planSheet; Mode.GUIDE -> etaSheet; else -> browseBar }
        val vis = when (p) { is DragSheet -> p.restingVisible(); else -> if (p.visibility == View.VISIBLE) (H - p.top).toInt() else 0 }
        if (vis > 0) { if (landscape() && p.width < W * 0.7f) left = max(left, p.right + dp(16f)) else bottom = H - vis - dp(12f) }
        if (bottom - top < dp(120f)) bottom = top + dp(120f)
        return RectF(left, top, max(left + dp(80f), right), bottom)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ================================================================== setup
    /** First stage: floor geometry is loaded — the map and the controls are shown (places / search / routes follow in [onModelReady]). */
    private fun setupMap(d: MapData) {
        if (mapShown) return
        mapShown = true
        // per site: a new map version clears that site's search history (room ids change)
        runCatching { assets.openFd("${Core.site.prefix}map/places.json").use { it.length } }.getOrNull()?.let { stamp ->
            if (prefs.getLong("mapStamp_${Core.site.id}", -1) != stamp) prefs.edit().putLong("mapStamp_${Core.site.id}", stamp).remove(histKey()).apply()
        }
        data = d
        gl = MapGLView(this)
        map = NaviMapView(this).apply { data = d; vp = this@MainActivity.vp }
        gl.bind(d, vp)
        root.addView(gl, 0, lp(MATCH, MATCH)); root.addView(map, 1, lp(MATCH, MATCH))
        map.onViewChanged = { gl.requestRender(); updateCompass() }
        map.onUserGesture = { userMovedMap = true; if (follow) { follow = false; updateLocBtn() } }
        map.onTouchDown = { stopCam() }
        map.onTap = { f, x, y -> onMapTap(f, x, y, false) }
        map.onLongPress = { f, x, y -> onMapTap(f, x, y, true) }
        map.onClosureTap = null
        map.onRouteTap = { i -> if (mode == Mode.PLAN) selectRoute(i) }
        map.debug = prefs.getBoolean("debug", false)
        map.accessLevel = 0
        map.showAccess = false
        buildChrome()
        root.requestApplyInsets()
        setMode(Mode.BROWSE)
        root.afterLayout {
            vp.w = root.width; vp.h = root.height
            // initial camera: the building footprint saved by an earlier start (computed from the grid in the background
            // otherwise); until then the ground floor's drawing
            val fp = Core.savedFootprint(this, Core.site) ?: loader?.footprint
            footprintFromCache = fp != null
            applyCam(fitCam(fp ?: floorBox(d), northRot))
            setFloor(d.floors.firstOrNull { it.n == 2 }?.n ?: d.floors.first().n, animate = false); map.changed()
            revealMap()
        }
    }

    private fun floorBox(d: MapData): DoubleArray {
        val bb = (d.floors.firstOrNull { it.n == 2 } ?: d.floors.first()).bbox
        return doubleArrayOf(bb[0] + d.cx, bb[1] + d.cy, bb[2] + d.cx, bb[3] + d.cy)
    }

    /** First start of this map version: the footprint was not cached — move to it if the user has not touched the map yet. */
    private fun onFootprintReady(fp: DoubleArray) {
        if (!mapShown || footprintFromCache || userMovedMap || mode != Mode.BROWSE || lastFix != null) return
        footprintFromCache = true
        root.afterLayout { camAnimate(fitCam(fp, northRot), 600) }
    }

    /** Second stage: building, routing and search are loaded — places, search, routes, closures and СКУД work. */
    private fun onModelReady() {
        if (!mapShown) return
        map.b = Core.b
        Core.planner.clearClosures()
        map.closures = emptyList()
        Core.router.userLevel = 0; map.accessLevel = Core.router.userLevel
        if (prefs.getBoolean("testAccess", false)) setTestAccess(true)
        map.invalidate()
        android.util.Log.i(TAG, "start: places / search / routes ready at process t+${since()} ms (north: map ${"%.4f".format(data?.northHeading)}, building ${"%.4f".format(Core.b.northHeading)})")
        handleExtras(intent)
        pendingReady?.let { pendingReady = null; it() }
        updateStatus()
    }

    /** Third stage: the radio model is loaded — positioning starts; the other site is preloaded in the background. */
    private fun onRadioReady() {
        android.util.Log.i(TAG, "start: radio model ready at process t+${since()} ms")
        startSource()
        main.postDelayed({ if (!isDestroyed) for (s in Core.sites) if (s.id != Core.site.id) Core.preload(this, s.id) }, 5000)
    }

    /** Runs [action] now, or as soon as places / search / routes are loaded (a second at most after start). */
    private fun whenReady(action: () -> Unit) {
        if (Core.ready) { action(); return }
        pendingReady = action
        toast("Загружаем поиск и маршруты…")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) Core.trim()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null || !::hud.isInitialized) return
        if (!Core.ready) { setIntent(intent); return }          // handled when the model is ready
        val launcher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && (intent.extras?.isEmpty != false)
        val before = source()
        if (launcher) sessionSource = null           // opened from the launcher: the saved source, no demo override
        handleExtras(intent)
        if (source() != before) { resetPosition(); startSource() }   // only a real change restarts positioning
    }

    /**
     * Launch extras (for demos/tests), consumed once (a recreate on a site switch does not replay them):
     * source=live|replay|sim (this process only), from=<query>, to=<query>, go=true (start guidance as soon as the route is
     * built), search=true, focus=floor,x,y[,scale], accessLevel, testAccess; developer screen checks: screen=WxH (dp) | off,
     * font=<scale>, orient=land|port|auto. E.g.
     * adb shell am start -n com.gmcnav.navi/.MainActivity --es source sim --es from 1626 --es to 2295 --ez go true
     */
    private fun handleExtras(i: Intent) {
        val ex = i.extras ?: return
        if (ex.isEmpty) return
        i.getStringExtra("source")?.let { s -> if (s in listOf("live", "replay", "sim")) sessionSource = s }
        if (i.hasExtra("testAccess")) { val on = i.getBooleanExtra("testAccess", false); prefs.edit().putBoolean("testAccess", on).apply(); setTestAccess(on) }
        Core.router.userLevel = 0; map.accessLevel = 0
        i.getStringExtra("orient")?.let { o ->
            requestedOrientation = when (o) { "land" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; "port" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        var emuChanged = false
        i.getStringExtra("screen")?.let { s ->
            val wh = s.split("x").mapNotNull { it.trim().toIntOrNull() }
            if (wh.size == 2 && wh[0] > 0) {
                val px = resources.displayMetrics.widthPixels
                emuDpi = px * 160 / wh[0]
                val hDp = resources.displayMetrics.heightPixels * 160 / emuDpi
                emuH = if (wh[1] < hDp) wh[1] else 0
            } else { emuDpi = 0; emuH = 0 }
            emuChanged = true
        }
        i.getStringExtra("font")?.toFloatOrNull()?.let { emuFont = if (it in 0.5f..3f) it else 0f; emuChanged = true }
        i.getStringExtra("site")?.let { id ->              // dev / tests: choose the site like in the settings (auto | gmc | rekordov15)
            if (id == "auto" || Core.sites.any { it.id == id }) {
                prefs.edit().putString("site", id).apply()
                if (id != "auto" && id != Core.site.id) { i.replaceExtras(Bundle()); switchSite(id, auto = false); return }
            }
        }
        if (i.getBooleanExtra("search", false)) main.postDelayed({ openSearch(false) }, 300)
        i.getStringExtra("focus")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size >= 3 }?.let { a ->
            main.postDelayed({ synchronized(vp) { vp.rot = northRot }; focusOn(a[0].toInt(), a[1], a[2], if (a.size >= 4) a[3].toFloat() else null) }, 600)
        }
        val fq = i.getStringExtra("from"); val tq = i.getStringExtra("to")
        val go = i.getBooleanExtra("go", false)
        i.replaceExtras(Bundle())
        if (emuChanged) { recreate(); return }
        if (fq != null) from = Core.search.query(fq).firstOrNull()?.mk?.invoke()
        if (tq != null) to = Core.search.query(tq).firstOrNull()?.mk?.invoke()
        if (to != null) { plan(); pendingGo = go }
    }

    // ================================================================== positioning source
    private fun source(): String = sessionSource ?: prefs.getString("source", "live")!!

    // No calibrated event positioning provider is shipped in this verification build.
    private val manualOnly = true
    private fun startSource() {
        if (manualOnly || !visible) {
            pos.stop(); stopGeo()
            posMsg = "Позиционирование недоступно. Выберите начало вручную: поиск или карта → «Отсюда»"
            updateStatus()
            return
        }
        when (source()) {
            "replay" -> {
                if (Core.site.id != "gmc") { pos.stop(); toast("Демо-запись есть только для НТУ"); return }
                val file = prefs.getString("replayFile", null) ?: Core.replayIndex.parts.firstOrNull()?.file
                if (file != null) pos.startReplay(file, prefs.getFloat("speed", 2f).toDouble())
            }
            "sim" -> pos.startSim { if (mode == Mode.GUIDE) guidance?.route else if (mode == Mode.PLAN) routes.getOrNull(active) else null }
            else -> {
                val need = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else { pos.startLive(); startGeo() }
            }
        }
        main.removeCallbacks(statusTick); main.post(statusTick)
    }

    /**
     * Forgets the position completely (site or source switch, end of a simulated walk): no cursor until a fresh valid fix of
     * the current source in the current site arrives; the camera follows that first fix again.
     */
    private fun resetPosition() {
        arrowAnim?.cancel(); arrowAnim = null
        lastFix = null; lastRawFix = null; posMsg = null; fixT = 0L; lastUserFloor = 0
        follow = true; updateLocBtn()
        if (::map.isInitialized) {
            map.fix = null; map.arrowX = Double.NaN; map.arrowY = Double.NaN; map.arrowHeading = Double.NaN; map.arrowFloor = 0
            map.outside = false; map.truth = null; map.invalidate()
        }
        updateStatus()
    }

    /** On screen (between onStart and onStop): position-lost / found phrases are said only then. */
    @Volatile private var visible = false
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (manualOnly || !visible) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { if (source() == "live") { pos.startLive(); startGeo() } }
        else toast("Без доступа к геолокации Wi-Fi-сканы недоступны. Можно включить демо в настройках (для разработчика).")
    }

    private val statusTick = object : Runnable {
        override fun run() {
            lastRawFix?.let { f ->
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                if (liveLike() && posMsg == null && source() == "live" && (f.radioOkNs == 0L || now - f.radioOkNs > lostAfterNs()))
                    hidePosition("Местоположение не определено")
            }
            updateStatus(); main.postDelayed(this, 1000)
        }
    }

    private fun updateStatus() {
        if (!::status.isInitialized) return
        val st = pos.status; val f = lastFix; val src = source()
        val age = (android.os.SystemClock.elapsedRealtimeNanos() - fixT) / 1e9
        val demo = when (src) { "sim" -> "Демо: симуляция"; "replay" -> "Демо: запись" + (if (st.replayPos.isNotEmpty()) " ${st.replayPos}" else ""); else -> "" }
        val s = StringBuilder()
        var problem = true
        when {
            posMsg != null -> s.append(posMsg)
            f == null && geoAway && geoFresh() -> s.append(awayMsg())
            f == null -> { if (manualOnly) s.append("Без позиционирования · выберите начало вручную") else { if (demo.isNotEmpty()) s.append(demo).append(" · "); s.append(if (src == "sim") "ждём маршрут" else "определяем местоположение…") } }
            else -> {
                if (demo.isNotEmpty()) s.append(demo).append(" · ")
                s.append(Building.floorName(f.floor)).append(" · ±${f.acc.toInt()} м")
                if (f.outside) s.append(" · вы снаружи здания")
                val stale = if (scansLimited()) 45 else 15
                if (age > stale) s.append(" · нет обновлений")
                problem = demo.isNotEmpty() || age > stale
            }
        }
        if (map.debug && src == "live") {
            s.append(" · AP ${st.aps}" + if (st.cells > 0) " · соты ${st.cells}" else "")
            if (st.limited) s.append(" · Wi-Fi 4/2 мин: ${st.scansMin}/мин (системных ${st.sysMin})")
        }
        status.text = s.toString()
        // while guiding the pill shows only problems (the plate and the panel say the rest)
        Fx.set(status, mode != Mode.GUIDE || problem, scale = 0.9f)
        map.truth = st.truth; if (map.debug) map.invalidate()
    }

    // ================================================================== fixes
    private var arrowAnim: ValueAnimator? = null

    // ------------------------------------------------------------------ is the user at the active site at all?
    private var geoAwayM = Double.NaN    // distance to the active site's edge (m), from GPS / network location
    private var geoAway = false          // certainly not at the active site (beyond its radius + 250 m + the fix accuracy)
    private var geoAt = ""               // short name of the site the user is at by GPS ("" = none)
    private var geoT = 0L
    private var lastGeo: android.location.Location? = null
    private var lastRawFix: Fix? = null
    private var posMsg: String? = null
    private val geoListener = android.location.LocationListener { onGeo(it) }

    private fun startGeo() {
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        for (p in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (!lm.isProviderEnabled(p)) return@runCatching
                lm.getLastKnownLocation(p)?.let { onGeo(it) }
                lm.requestLocationUpdates(p, 5000L, 10f, geoListener, Looper.getMainLooper())
            }
        }
    }

    private fun stopGeo() { runCatching { getSystemService(android.location.LocationManager::class.java)?.removeUpdates(geoListener) } }

    private fun geoFresh() = source() == "live" && System.currentTimeMillis() - geoT < 10 * 60_000L

    /**
     * GPS/network fix -> where the user is relative to the ACTIVE site (auto-detected or chosen by hand) and which site they
     * are at (sites.json: centre + radius + 250 m + the fix accuracy). Away from the active site = no cursor, «вне объекта».
     * Auto mode switches the active site (map, radio model, search, routing).
     */
    private var geoSiteVotes = 0; private var geoSiteCand: String? = null
    private fun onGeo(loc: android.location.Location) {
        if (System.currentTimeMillis() - loc.time > 15 * 60_000L) return
        if (Core.model == null) return
        lastGeo = loc
        val acc = loc.accuracy.toDouble()
        val det = Sites.detect(Core.sites, loc.latitude, loc.longitude, acc)
        val act = Core.site
        val dAct = max(0.0, Sites.dist(loc.latitude, loc.longitude, act.lat, act.lon) - act.radiusM)
        geoAway = dAct - acc > Sites.MARGIN_M
        geoAwayM = dAct
        geoAt = det.site?.short ?: ""
        geoT = System.currentTimeMillis()
        android.util.Log.i(TAG, "geo: ${loc.provider} ±${loc.accuracy.toInt()} m, site=${det.site?.id} active=${act.id} ${dAct.toInt()} m from it${if (geoAway) " (away)" else ""}")
        // automatic switching: two consecutive fixes at another site, not while guiding
        val want = det.site?.id
        if (prefs.getString("site", "auto") == "auto" && want != null && want != Core.site.id && mode != Mode.GUIDE) {
            if (geoSiteCand == want) geoSiteVotes++ else { geoSiteCand = want; geoSiteVotes = 1 }
            if (geoSiteVotes >= 2 || loc.accuracy < 100) { switchSite(want, auto = true); return }
        } else { geoSiteCand = null; geoSiteVotes = 0 }
        if (geoAway && geoFresh() && posMsg == null && lastFix != null) hidePosition(awayMsg())
        updateStatus()
    }

    private fun awayMsg(): String {
        val here = Core.site.short
        if (geoAt.isNotEmpty() && geoAt != here) return "Вы на объекте «$geoAt», а выбран «$here» — сменить в настройках"
        val d = if (geoAwayM >= 1000) "%.1f км".format(geoAwayM / 1000) else "${geoAwayM.toInt()} м"
        return "Вы вне объекта «$here» · ≈$d"
    }

    /** Makes another site active: position state is dropped, the activity is rebuilt for it (map, floors, radio model, search, routing). */
    private fun switchSite(id: String, auto: Boolean) {
        if (id == Core.site.id) return
        val title = Core.sites.firstOrNull { it.id == id }?.short ?: id
        if (mode == Mode.GUIDE) stopGuidance() else if (mode == Mode.PLAN) closePlan()
        closeSheet(); closeOverlay(false)
        pos.stop(); stopGeo()
        resetPosition()
        geoAway = false; geoAt = ""; geoT = 0L
        Core.setActiveSite(this, id)
        if (auto) toast("Объект: $title (по местоположению)")
        android.util.Log.i(TAG, "site -> $id (${if (auto) "auto" else "manual"}), position reset, in memory: ${Core.isLoaded(id)}")
        Core.preload(this, id, force = true)                 // starts loading now if it is not in memory yet
        // loading state: the overlay with the site name fades in, then the activity is rebuilt for the site behind it
        switchingTo = id
        buildLoadingView(); loadingTitle.text = title; loadingText.text = if (Core.isLoaded(id)) "" else "Загружаем план, поиск и радиокарту…"
        loadingView.alpha = 0f; root.addView(loadingView, lp(MATCH, MATCH))
        loadingView.animate().alpha(1f).setDuration(180).withEndAction { if (!isDestroyed) recreate() }.start()
    }

    private fun liveLike() = source() != "sim"

    /** Android limits Wi-Fi scans (4 per 2 min, no developer option): a scan every 30 s, so a few unrecognised ones in a row
     *  are normal — «Местоположение не определено» only after 5 min without a recognised scan (else 1 min). */
    private fun scansLimited() = source() == "live" && pos.status.limited
    private fun lostAfterNs() = if (scansLimited()) 300_000_000_000L else 60_000_000_000L

    /** null = position usable; otherwise why the cursor is hidden. */
    private fun invalidReason(f: Fix): String? {
        if (!liveLike()) return null
        if (geoAway && geoFresh()) return awayMsg()
        if (f.radioOkNs == 0L || (f.tNs - f.radioOkNs) > lostAfterNs()) return "Местоположение не определено"
        return null
    }

    /** No cursor, no «my location», guidance paused. */
    private fun hidePosition(why: String) {
        posMsg = why; lastFix = null
        arrowAnim?.cancel(); arrowAnim = null
        map.fix = null; map.arrowX = Double.NaN; map.invalidate(); updateStatus()
    }

    private fun onFix(raw: Fix) {
        if (!::hud.isInitialized) return
        lastRawFix = raw
        val why = invalidReason(raw)
        if (why != null) { hidePosition(why); return }
        posMsg = null
        val f = raw
        val prev = lastFix
        lastFix = f; fixT = android.os.SystemClock.elapsedRealtimeNanos()
        map.fix = f; map.outside = f.outside
        val g = guidance
        var tx = f.x; var ty = f.y; var tf = f.floor; val th = f.heading
        if (g != null && mode == Mode.GUIDE) {
            val st = g.update(f)
            gstate = st
            // arrow: snapped onto the route only while clearly on it (≤ 3.5 m, same corridor, walking along it);
            // otherwise the fused position itself — never pulled back onto the old line. Rotation = real heading.
            tx = st.dispX; ty = st.dispY; tf = st.dispFloor
            map.progress = st.s
            onGuidance(st)
        }
        // smooth arrow motion between fixes
        val sx = if (map.arrowX.isNaN() || map.arrowFloor != tf) tx else map.arrowX
        val sy = if (map.arrowY.isNaN() || map.arrowFloor != tf) ty else map.arrowY
        val sh = map.arrowHeading
        arrowAnim?.cancel()
        map.arrowFloor = tf
        arrowAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                map.arrowX = sx + (tx - sx) * t; map.arrowY = sy + (ty - sy) * t
                map.arrowHeading = if (th.isNaN()) th else if (sh.isNaN()) th else sh + Orientation.wrap(th - sh) * t
                if (follow) camFollow()
                map.invalidate()
            }
            start()
        }
        // the user's floor: shown at the first fix (the map opens on floor 1 before the position is known) and whenever it
        // changes (stairs / lift) — unless the user picked another floor by hand in the last 20 s; while following, kept on it
        val handPick = android.os.SystemClock.elapsedRealtime() - floorPickedMs < 20_000
        if (tf != lastUserFloor) { if (floor != tf && (mode == Mode.GUIDE || follow || !handPick)) setFloor(tf); lastUserFloor = tf }
        else if (follow && floor != tf) setFloor(tf)
        if (prev == null && follow) camFollow(true)
        if (mode == Mode.BROWSE && from == null) map.start = null
        if (prev == null) updateStatus()
    }

    // ================================================================== camera
    /** Camera that puts the world point (wx, wy) (relative to the plan centre) at screen point (sx, sy). */
    private fun camAt(wx: Float, wy: Float, scale: Float, rot: Float, sx: Float, sy: Float): Cam {
        val t = Viewport().apply { w = vp.w; h = vp.h; this.scale = scale; this.rot = rot; cx = wx; cy = wy }
        t.pan(sx - vp.w / 2f, sy - vp.h / 2f)
        return Cam(t.cx, t.cy, scale, rot)
    }

    private fun applyCam(c: Cam) { stopCam(); synchronized(vp) { vp.cx = c.cx; vp.cy = c.cy; vp.scale = c.scale; vp.rot = c.rot }; map.changed() }

    private fun stopCam() { camAnim?.cancel(); camAnim = null; camGoal = null; if (::map.isInitialized) map.stopFling() }

    /** Smooth camera move (centre, log-zoom, shortest rotation) with a fast-out-slow-in curve; a touch stops it. */
    private fun camAnimate(to: Cam, dur: Long = 420) {
        stopCam()
        val f0 = synchronized(vp) { Cam(vp.cx, vp.cy, vp.scale, vp.rot) }
        val dRot = Orientation.wrap((to.rot - f0.rot).toDouble()).toFloat()
        val lk = ln(to.scale / f0.scale)
        val an = ValueAnimator.ofFloat(0f, 1f)
        an.duration = dur; an.interpolator = Ease.standard
        an.addUpdateListener {
            val t = it.animatedValue as Float
            synchronized(vp) {
                vp.cx = f0.cx + (to.cx - f0.cx) * t; vp.cy = f0.cy + (to.cy - f0.cy) * t
                vp.scale = f0.scale * exp(lk * t); vp.rot = f0.rot + dRot * t
            }
            map.changed()
        }
        an.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) { if (camAnim === an) { camAnim = null; camGoal = null } }
        })
        camAnim = an; camGoal = to; an.start()
    }

    /** Map rotation that puts true north up (plan north is ~91° off, from the geo affine). */
    private val northRot: Float get() = (-(data?.northHeading ?: 0.0)).toFloat()

    /** North-up camera showing a plan box (absolute x0, y0, x1, y1) in the free part of the screen. */
    private fun fitCam(bb: DoubleArray, rot: Float): Cam {
        val d = data!!; val fr = freeRect()
        val c = abs(kotlin.math.cos(rot)); val sn = abs(kotlin.math.sin(rot))
        val bw = (bb[2] - bb[0]).toFloat(); val bh = (bb[3] - bb[1]).toFloat()
        val w = c * bw + sn * bh; val h = sn * bw + c * bh
        val scale = (min(fr.width() / w, fr.height() / h) * 0.92f).coerceIn(0.4f, 40f)
        return camAt(((bb[0] + bb[2]) / 2 - d.cx).toFloat(), ((bb[1] + bb[3]) / 2 - d.cy).toFloat(), scale, rot, fr.centerX(), fr.centerY())
    }

    /** Follow camera: the arrow in the free area (its centre; ~72 % down while guiding, heading-up). */
    private fun followCam(rot: Float? = null, scale: Float? = null): Cam? {
        val d = data ?: return null
        if (map.arrowX.isNaN()) return null
        val head = if (!map.arrowHeading.isNaN()) map.arrowHeading else gstate?.heading ?: Double.NaN
        val r = rot ?: if (mode == Mode.GUIDE && !head.isNaN()) (-head).toFloat() else vp.rot
        val s = scale ?: if (mode == Mode.GUIDE) maxOf(vp.scale, 9f).coerceAtMost(14f) else vp.scale
        val fr = freeRect()
        val sy = if (mode == Mode.GUIDE) fr.top + fr.height() * 0.72f else fr.centerY()
        return camAt((map.arrowX - d.cx).toFloat(), (map.arrowY - d.cy).toFloat(), s, r, fr.centerX(), sy)
    }

    /** Camera follows the arrow: a smooth move on the first fix / the location button, then soft per-frame tracking. */
    private fun camFollow(jump: Boolean = false) {
        if (camAnim != null) return                  // a camera move is running; tracking resumes after it
        val t = followCam() ?: return
        if (jump) { camAnimate(t, 450); return }
        synchronized(vp) {
            val k = 0.25f
            vp.rot += Orientation.wrap((t.rot - vp.rot).toDouble()).toFloat() * k
            vp.scale += (t.scale - vp.scale) * k
        }
        val c = followCam(vp.rot, vp.scale) ?: return
        synchronized(vp) { vp.cx += (c.cx - vp.cx) * 0.35f; vp.cy += (c.cy - vp.cy) * 0.35f }
        gl.requestRender(); map.invalidate(); updateCompass()
    }

    private fun focusOn(f: Int, x: Double, y: Double, scale: Float? = null) {
        val d = data ?: return
        follow = false; updateLocBtn()
        setFloor(f)
        val fr = freeRect()
        camAnimate(camAt((x - d.cx).toFloat(), (y - d.cy).toFloat(), scale ?: maxOf(vp.scale, 6f), vp.rot, fr.centerX(), fr.centerY()))
    }

    /** Overview of a route's first floor, north up, above the panel. */
    private fun fitRoute(r: Route) {
        val fl = r.legs.first().floor
        val pts = r.legs.filter { it.floor == fl }.flatMap { L -> L.xs.indices.map { L.xs[it] to L.ys[it] } }
        if (pts.isEmpty()) return
        follow = false; updateLocBtn()
        setFloor(fl)
        val bb = doubleArrayOf(pts.minOf { it.first } - 10, pts.minOf { it.second } - 10, pts.maxOf { it.first } + 10, pts.maxOf { it.second } + 10)
        camAnimate(fitCam(bb, northRot), 520)
    }

    private fun zoomBy(k: Float) {
        val g = camGoal ?: synchronized(vp) { Cam(vp.cx, vp.cy, vp.scale, vp.rot) }
        camAnimate(Cam(g.cx, g.cy, (g.scale * k).coerceIn(0.4f, 200f), g.rot), 260)
    }

    // ================================================================== guidance
    private fun onGuidance(st: GuidanceState) {
        val g = guidance ?: return
        if (st.needReroute && !rerouting) { reroute("off:" + st.offReason); return }
        for (p in vplan.update(g.route, st)) voice.say(p)
        updatePlate(st)
        updateEta(st)
        if (st.arrived && !arrivedShown) { arrivedShown = true; arrivedUi() }
    }

    private var plateKey: String? = null
    private var plateBusy = false
    private var plateLatest: GuidanceState? = null

    /** Maneuver plate: the distance updates in place; a new maneuver slides in (old one out to the left). */
    private fun updatePlate(st: GuidanceState) {
        val m = st.next ?: return
        plateLatest = st
        val key = m.key + m.type
        if (!Fx.shown(plate)) { plateKey = key; fillPlate(st); Fx.show(plate, dy = -dp(20f)); return }
        if (plateBusy) return
        if (key != plateKey) {
            plateKey = key; plateBusy = true
            plateMain.animate().cancel()
            plateMain.animate().translationX(-dp(32f)).alpha(0f).setDuration(120).setInterpolator(Ease.accel).withEndAction {
                plateLatest?.let { fillPlate(it) }
                plateMain.translationX = dp(40f)
                plateMain.animate().translationX(0f).alpha(1f).setDuration(260).setInterpolator(Ease.decel).withEndAction { plateBusy = false }.start()
            }.start()
            return
        }
        fillPlate(st)
    }

    private fun resetPlate() {
        plateBusy = false; plateKey = null; plateLatest = null
        if (::plateMain.isInitialized) { plateMain.animate().cancel(); plateMain.alpha = 1f; plateMain.translationX = 0f }
    }

    private fun fillPlate(st: GuidanceState) {
        val m = st.next ?: return
        plateIcon.setImageResource(plateIconRes(m))
        val txt = if (m.type == MType.FINISH && st.dist < 6) "Вы пришли" else fmtDist(st.dist)
        val avail = root.width - insL - insR - dp(84) - dp(12) - dp(14 + 12 + 20) - dp(46)
        plateDist.text = txt
        plateDist.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, plateDist.fitSize(txt, avail, if (txt.endsWith(" м")) 38f else 26f, 16f))
        val hint = when (m.type) {
            MType.CONNECTOR -> "${m.conn?.shortName ?: ""} → ${m.toFloor} этаж"
            MType.FINISH -> to?.title ?: m.hint
            else -> m.hint
        }
        plateHint.text = hint; plateHint.visibility = if (hint.isNotEmpty()) View.VISIBLE else View.GONE
        val a = st.after
        if (a != null && a.type != MType.FINISH && a.s - m.s < 40) { plateNextIcon.setImageResource(plateIconRes(a)); Fx.show(plateNext, scale = 0.7f) }
        else Fx.hide(plateNext, scale = 0.7f)
    }

    /** White glyphs on the blue plate (the extracted connector icons carry their own dark tile). */
    private fun plateIconRes(m: Maneuver): Int = if (m.type == MType.CONNECTOR) when (m.connKind) {
        "lift" -> R.drawable.ya_elevator_small; "escalator" -> R.drawable.ya_escalator_small; else -> R.drawable.ya_stairs_glyph } else iconRes(m)

    private fun iconRes(m: Maneuver): Int = resources.getIdentifier(m.icon, "drawable", packageName).takeIf { it != 0 } ?: R.drawable.ya_forward

    private fun updateEta(st: GuidanceState) {
        etaDist.text = fmtDist(st.remaining)
        etaTime.text = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(System.currentTimeMillis() + (st.timeLeft * 1000).toLong()))
        etaMin.text = fmtMin(st.timeLeft)
        val r = guidance?.route ?: return
        etaProgress.setProgress(((st.s / r.length) * 1000).toInt().coerceIn(0, 1000), true)
    }

    private fun startGuidance() {
        val r = routes.getOrNull(active) ?: return
        val g = Guidance(Core.b, r)
        guideSeq++; rerouting = false; arrivedShown = false; pendingGo = false
        startForegroundService(Intent(this, NavigationService::class.java))
        guidance = g
        resetPlate()
        etaProgress.progress = 0
        setMode(Mode.GUIDE)
        follow = true; updateLocBtn()
        map.routes = listOf(r); map.active = 0; map.progress = 0.0; map.guiding = true
        for (p in vplan.onStart(r, System.nanoTime())) voice.say(p)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveHistory(to)
        android.util.Log.i(TAG, "guidance #$guideSeq: ${r.label}, ${r.length.toInt()} m")
        lastFix?.let { onFix(it) }
        if (lastFix != null) camFollow(true)
        if (!manualOnly && visible && source() == "sim") pos.startSim { guidance?.route }
    }

    private fun stopGuidance() {
        stopService(Intent(this, NavigationService::class.java))
        guideSeq++
        guidance = null; gstate = null; rerouting = false; arrivedShown = false
        voice.stopAll()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        map.progress = -1.0; map.guiding = false
        setMode(Mode.BROWSE)            // clears routes, goal, start; cancels any computation
        resetPlate()
        if (::map.isInitialized) camAnimate(Cam(vp.cx, vp.cy, vp.scale, northRot), 380)
    }

    /** Re-plan from the FUSED position (never the route-snapped one): same destination, accessibility, closures, СКУД level. */
    private fun reroute(why: String) {
        val f = lastFix ?: return; val z = to ?: return
        if (rerouting) return
        rerouting = true
        val gs = guideSeq; val planner = Core.planner; val acc = accessible
        thread(name = "reroute") {
            val me = planner.placeFromFix(f.floor, f.x, f.y)
            val rs = if (me != null) runCatching { planner.plan(me, z, acc) }.getOrDefault(emptyList()) else emptyList()
            main.post {
                if (gs != guideSeq || mode != Mode.GUIDE || isDestroyed) { android.util.Log.i(TAG, "reroute ($why): dropped, guidance ended"); return@post }
                rerouting = false
                val nr = rs.firstOrNull() ?: run {
                    if (why != "open" && System.currentTimeMillis() - noRouteToastT > 30_000) { noRouteToastT = System.currentTimeMillis(); toast("Маршрут не найден") }
                    return@post
                }
                val g = guidance ?: return@post
                if (why == "open" && nr.time > g.route.timeLeft(gstate?.s ?: 0.0) - 10) return@post   // current one is as good
                // the same room / door sequence as the rest of the current one: replaced silently (no «Маршрут перестроен»)
                val same = Core.planner.samePath(nr, g.route, gstate?.s ?: 0.0)
                g.replaceRoute(nr)
                map.routes = listOf(nr); map.progress = 0.0
                android.util.Log.i(TAG, "reroute ($why): ${nr.length.toInt()} m${if (same) ", same path — silent" else ""}")
                if (!same) for (p in vplan.onReroute(System.nanoTime(), faster = why == "open", closure = why == "closure")) voice.say(p)
                if (why == "closure") toast("Маршрут перестроен в обход перекрытия")
            }
        }
    }

    /** «Вы пришли»: closing the card in any way (button, swipe, tap outside, back) finishes the route. */
    private fun arrivedUi() {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        v.addView(text("Вы пришли", 24, Ya.TEXT, true))
        v.addView(text(to?.title ?: "", 16, Ya.TEXT2).apply { setPadding(0, dp(6), 0, dp(16)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        v.addView(textButton("Завершить", Ya.BLUE, Color.WHITE) { closeSheet() }.single(), llp(MATCH, WRAP))
        openSheet(v, onDismiss = { if (mode == Mode.GUIDE) stopGuidance() })
    }

    private fun askFinish() {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        v.addView(text("Завершить маршрут?", 18, Ya.TEXT2).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(16)) })
        v.addView(textButton("Да, завершить", 0xFFFFEBEE.toInt(), Ya.RED, 16, 18) { closeSheet(); stopGuidance() }.single(), llp(MATCH, WRAP))
        v.addView(textButton("Продолжить", Ya.CARD, Ya.TEXT, 16, 17) { closeSheet() }.single(), llp(MATCH, WRAP).apply { topMargin = dp(10) })
        openSheet(v)
    }

    // ================================================================== planning
    private var planMsg = ""            // shown in the route panel instead of cards (progress / what is missing / error)

    /** «Сюда» / «Отсюда» / search result: open the route panel right away and compute the routes. */
    private fun setTo(p: Place) { to = p; plan() }
    private fun setFrom(p: Place?) { from = p; plan() }

    private fun plan() {
        val token = planGate.begin()             // results of every older plan are dropped; its search is cancelled
        val z = to
        val a = from ?: lastFix?.let { Core.planner.placeFromFix(it.floor, it.x, it.y) }
        routes = emptyList(); active = 0
        map.routes = emptyList(); map.active = 0; map.start = a; map.goal = z; map.guiding = false; map.progress = -1.0
        if (mode != Mode.PLAN) setMode(Mode.PLAN)
        when {
            z == null -> { planMsg = "Выберите, куда идти: поиск или точка на карте → «Сюда»"; fillPlanSheet(); return }
            a == null -> { planMsg = "Местоположение не определено. Укажите, откуда идти: поиск или точка на карте → «Отсюда»"; fillPlanSheet()
                root.afterLayout { if (planGate.current(token)) focusOn(z.floor, z.x, z.y) }; return }
        }
        planMsg = "Строим маршрут…"; fillPlanSheet()
        android.util.Log.i(TAG, "plan #$token: ${a!!.title} → ${z!!.title}")
        val planner = Core.planner; val acc = accessible
        thread(name = "plan") {
            val rs = runCatching { planner.plan(a, z, acc) }
            main.post {
                if (!planGate.current(token) || mode != Mode.PLAN || isDestroyed) { android.util.Log.i(TAG, "plan #$token: result dropped (panel closed or a newer plan)"); return@post }
                val list = rs.getOrNull()
                android.util.Log.i(TAG, "plan #$token: ${list?.size ?: 0} routes")
                if (list.isNullOrEmpty()) {
                    pendingGo = false
                    planMsg = rs.exceptionOrNull()?.let { "Ошибка построения маршрута: ${it.message}" }
                        ?: if (accessible) "Маршрут без лестниц и эскалаторов не найден" else "Маршрут не найден (проход перекрыт или не распознан на плане)"
                    fillPlanSheet(); return@post
                }
                planMsg = ""
                routes = list; active = 0
                map.routes = list; map.active = 0
                fillPlanSheet()
                if (pendingGo) { pendingGo = false; startGuidance(); return@post }
                root.afterLayout { if (planGate.current(token) && mode == Mode.PLAN) routes.getOrNull(0)?.let { fitRoute(it) } }
            }
        }
    }

    /** Closes the route panel: nothing of it stays on the map, the search stops, late results are ignored. */
    private fun closePlan() {
        if (mode != Mode.PLAN) return
        android.util.Log.i(TAG, "route panel closed (plan #${planGate.token})")
        setMode(Mode.BROWSE)
    }

    /** Leaving the route panel (closed, or guidance ended): deterministic cleanup, idempotent. */
    private fun clearPlanState() {
        planGate.close()
        pendingGo = false
        routes = emptyList(); active = 0; planMsg = ""
        to = null; from = null
        if (::map.isInitialized) { map.routes = emptyList(); map.active = 0; map.goal = null; map.start = null; map.invalidate() }
        // a simulated position exists only along a route
        if (source() == "sim" && lastFix != null) resetPosition()
    }

    // ================================================================== map interaction
    private fun onMapTap(f: Int, x: Double, y: Double, long: Boolean) {
        if (!Core.ready) return
        if (mode == Mode.GUIDE && !long) return
        val b = Core.b; val fi = b.fi(f)
        val id = b.roomIdAt(fi, x, y)
        val q = b.rooms[id]
        if (!long && q == null) { closeSheet(); return }
        val conn = b.connNear(f, x, y, 3.0)
        val place = if (conn != null) Core.search.placeFromConn(conn) else Core.search.placeFromPoint(f, x, y) ?: return
        if (intent.getBooleanExtra("select_meeting_place", false)) { returnMeetingPlace(place); return }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        v.addView(text(place.title, 20, Ya.TEXT, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        val sub = Building.floorName(f) + (q?.let { " · ${it.area.toInt()} м²" + (it.zone?.let { z -> " · зона $z" } ?: "") + (it.func.firstOrNull()?.let { fn -> " · $fn" } ?: "") } ?: "") +
            (lastFix?.takeIf { it.floor == f }?.let { " · ${fmtDist(hypot(it.x - x, it.y - y))} от вас" } ?: "")
        v.addView(text(sub, 14, Ya.TEXT2).apply { setPadding(0, dp(6), 0, dp(14)); maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        if (mode != Mode.GUIDE) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(textButton("Отсюда", Ya.CARD, Ya.TEXT) { closeSheet(); setFrom(place) }.single(), llp(0, WRAP, 1f))
            row.addView(View(this), llp(dp(10), 1))
            row.addView(textButton("Сюда", Ya.BLUE, Color.WHITE) { closeSheet(); setTo(place) }.single(), llp(0, WRAP, 1f))
            v.addView(row, llp(MATCH, WRAP))
        }
        openSheet(v, modal = false)
    }

    private fun returnMeetingPlace(place: com.gmcnav.navi.core.Place) {
        val name = listOf(place.title, place.sub).filter { it.isNotBlank() }.joinToString(" · ").take(300)
        setResult(RESULT_OK, Intent().putExtra("meeting_location", name))
        finish()
    }

    private fun addClosure(f: Int, x: Double, y: Double) {
        val (cx, cy, rc) = Core.planner.closureAt(f, x, y)
        val c = rc.second
        if (c != null) Core.planner.addClosure(f, cx, cy, 0.0, c.id, "${c.shortName}: не работает")
        else Core.planner.addClosure(f, cx, cy, rc.first, 0, "Проход перекрыт")
        map.closures = Core.planner.closures.toList(); map.invalidate()
        toast(if (c != null) "${c.shortName} отмечен как неработающий" else "Проход отмечен как перекрытый")
        when (mode) {
            Mode.GUIDE -> { val g = guidance ?: return
                // reroute only if the current route is affected
                val affected = g.route.connSteps.any { it.conn?.id == c?.id && c != null } ||
                    (c == null && g.route.project(Core.b.fi(f), cx, cy, gstate?.s ?: 0.0)?.let { it.dist < rc.first + 1.0 } == true)
                if (affected) reroute("closure") }
            Mode.PLAN -> plan()
            else -> {}
        }
    }

    /** Tap on a closure marker: what it is and «Убрать перекрытие». */
    private fun onClosureTap(c: com.gmcnav.navi.core.Closure) {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        v.addView(text(c.title.ifEmpty { "Перекрытие" }, 20, Ya.TEXT, true))
        v.addView(text(Building.floorName(c.floor) + if (c.connId != 0) " · связь между этажами исключена из маршрутов" else " · маршруты обходят это место",
            14, Ya.TEXT2).apply { setPadding(0, dp(6), 0, dp(14)) })
        v.addView(textButton(if (c.connId != 0) "Снова работает — убрать отметку" else "Убрать перекрытие", Ya.BLUE, Color.WHITE) {
            closeSheet(); removeClosures(listOf(c.id)) }.single(), llp(MATCH, WRAP))
        openSheet(v, modal = false)
    }

    /** Removes closures and recomputes: route cards in the panel, or a possibly faster route while guiding. */
    private fun removeClosures(ids: List<Int>?) {
        if (ids == null) Core.planner.clearClosures() else ids.forEach { Core.planner.removeClosure(it) }
        map.closures = Core.planner.closures.toList(); map.invalidate()
        toast(if (ids == null || ids.size > 1) "Перекрытия убраны" else "Перекрытие убрано")
        when (mode) {
            Mode.PLAN -> plan()
            Mode.GUIDE -> reroute("open")
            else -> {}
        }
    }

    // ================================================================== floors, buttons, modes
    private fun setFloor(requestedFloor: Int, animate: Boolean = true) {
        if (requestedFloor != 2) toast("На карте показан только 2 этаж. Переходы между этажами указаны в шагах маршрута.")
        val n = 2 // SBS event presentation only; routing retains every floor.
        val old = floor
        floor = n; map.floor = n
        if (animate && old != n) {
            gl.setFloor(n, animate = true)
            map.animate().cancel(); map.alpha = 0.15f
            map.animate().alpha(1f).setDuration(260).setInterpolator(Ease.decel).start()
        } else gl.setFloor(n)
        map.invalidate()
        for ((k, b) in floorBtns) {
            val on = k == n
            b.setTextColor(if (on) Color.WHITE else Ya.TEXT); b.background = if (on) round(Ya.BLUE, 12) else null
        }
        floorBtns[n]?.let { b -> floorsScroll.post {
            val y0 = floorsScroll.scrollY; val h = floorsScroll.height
            if (b.top < y0 || b.bottom > y0 + h) floorsScroll.smoothScrollTo(0, (b.top - (h - b.height) / 2).coerceAtLeast(0))
        } }
    }

    private fun updateLocBtn() {
        if (!::locBtn.isInitialized) return
        (locBtn.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (follow) Ya.BLUE else Ya.ICON)
    }

    private fun updateCompass() {
        if (!::compassBtn.isInitialized) return
        val off = Orientation.wrap((vp.rot - northRot).toDouble())
        Fx.set(compassBtn, abs(off) > 0.05, scale = 0.6f)
        compassBtn.getChildAt(0).rotation = Math.toDegrees(off).toFloat()
    }

    private fun setMode(m: Mode) {
        val old = mode
        mode = m
        if (m == Mode.BROWSE) clearPlanState()
        Fx.set(browseBar, m == Mode.BROWSE, dy = dp(28f))
        if (m == Mode.PLAN) planSheet.show(if (old == Mode.PLAN && planSheet.state != DragSheet.State.HIDDEN) planSheet.state else DragSheet.State.COLLAPSED)
        else planSheet.hide()
        if (m == Mode.GUIDE) etaSheet.show(DragSheet.State.COLLAPSED) else etaSheet.hide()
        if (m != Mode.GUIDE) { Fx.hide(plate, dy = -dp(20f)); Fx.hide(plateNext); resetPlate() }
        Fx.set(voiceBtn, m == Mode.GUIDE, scale = 0.6f)
        Fx.set(closureBtn, false, scale = 0.6f)
        if (m == Mode.PLAN) fillPlanSheet()
        if (old != m) android.util.Log.i(TAG, "mode $old -> $m")
        updateStatus()
        map.invalidate()
    }

    // ================================================================== chrome
    private fun buildChrome() {
        // status pill (tap: settings)
        status = text("", 12, Ya.TEXT2).apply { background = round(0xF0FFFFFF.toInt(), 14); setPadding(dp(12), dp(6), dp(12), dp(6)); elevation = dp(2).toFloat()
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
        root.addView(status, lp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { leftMargin = dp(76); rightMargin = dp(76) })
        status.setOnClickListener { openSettings() }

        // maneuver plate (top-left)
        plate = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; noClip() }
        plateMain = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(Ya.PLATE, 18)
            setPadding(dp(14), dp(12), dp(20), dp(12)); elevation = dp(4).toFloat() }
        plateIcon = icon(R.drawable.ya_turn_left, 46, Color.WHITE)
        plateDist = text("", 38, Color.WHITE, true).apply { setPadding(dp(12), 0, 0, 0); maxLines = 1 }
        plateMain.addView(plateIcon); plateMain.addView(plateDist)
        plate.addView(plateMain, llp(WRAP, WRAP))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; noClip() }
        plateNext = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(Ya.PLATE_DARK, 12)
            setPadding(dp(8), dp(6), dp(10), dp(6)); visibility = View.GONE; elevation = dp(3).toFloat() }
        plateNext.addView(text("затем", 13, Color.WHITE).apply { setPadding(0, 0, dp(6), 0) })
        plateNextIcon = icon(R.drawable.ya_turn_right, 22, Color.WHITE); plateNext.addView(plateNextIcon)
        plateHint = text("", 15, Ya.TEXT, true).apply { background = round(0xF2FFFFFF.toInt(), 12); setPadding(dp(10), dp(6), dp(10), dp(6)); elevation = dp(3).toFloat()
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        row2.addView(plateNext); row2.addView(View(this), llp(dp(6), 1)); row2.addView(plateHint, llp(WRAP, WRAP).apply { weight = 1f })
        plate.addView(row2, llp(WRAP, WRAP).apply { topMargin = dp(6) })
        root.addView(plate, lp(WRAP, WRAP, Gravity.TOP or Gravity.START))

        // floating buttons
        hud = HudLayout(this)
        voiceBtn = mapButton(R.drawable.ya_pedestrian) { cycleVoice() }.apply { contentDescription = "Голосовые подсказки"; visibility = View.GONE }
        refreshVoiceBtn()
        floorBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for (fl in listOf(2)) {
            val t = text(floorLabel(fl), 18, Ya.TEXT, true).apply { gravity = Gravity.CENTER; contentDescription = Building.floorName(fl)
                setOnClickListener { follow = false; floorPickedMs = android.os.SystemClock.elapsedRealtime(); updateLocBtn(); setFloor(fl) } }
            floorBtns[fl] = t; floorBox.addView(t, llp(dp(48), dp(44)))
        }
        floorsScroll = ScrollView(this).apply { background = round(Color.WHITE, 16); elevation = dp(4).toFloat(); clipToOutline = true
            isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(18)); overScrollMode = View.OVER_SCROLL_NEVER
            addView(floorBox) }
        zoomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; noClip() }
        zoomBox.addView(mapButton(R.drawable.ya_plus) { zoomBy(1.6f) }.apply { contentDescription = "Приблизить" }, llp(dp(56), dp(56)).apply { bottomMargin = dp(8) })
        zoomBox.addView(mapButton(R.drawable.ya_minus) { zoomBy(1 / 1.6f) }.apply { contentDescription = "Отдалить" }, llp(dp(56), dp(56)))
        // closure "!" (like the road-event button)
        closureBtn = mapButton(R.drawable.ya_warning, Ya.TEXT) { }.apply { visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        compassBtn = mapButton(R.drawable.ya_compass, Color.WHITE, true) { camAnimate(Cam(vp.cx, vp.cy, vp.scale, northRot), 380) }.apply { contentDescription = "На север" }
        (compassBtn.getChildAt(0) as ImageView).imageTintList = null
        (compassBtn.getChildAt(0).layoutParams as FrameLayout.LayoutParams).apply { width = dp(44); height = dp(44) }
        compassBtn.visibility = View.GONE
        locBtn = mapButton(R.drawable.ya_nav_arrow, Ya.BLUE, true) {
            follow = true; updateLocBtn()
            if (lastFix == null) toast(posMsg ?: "Позиция ещё не определена")
            else { setFloor(map.arrowFloor.takeIf { f -> f > 0 } ?: lastFix!!.floor); followCam()?.let { camAnimate(it, 450) } }
        }.apply { contentDescription = "Моё местоположение" }
        hud.addView(voiceBtn, lp(dp(56), dp(56))); hud.addView(floorsScroll, lp(dp(56), WRAP)); hud.addView(zoomBox, lp(dp(56), dp(120)))
        hud.addView(compassBtn, lp(dp(56), dp(56))); hud.addView(locBtn, lp(dp(56), dp(56))); hud.addView(closureBtn, lp(dp(56), dp(56)))
        hud.voice = voiceBtn; hud.floors = floorsScroll; hud.zoom = zoomBox; hud.compass = compassBtn; hud.loc = locBtn; hud.closure = closureBtn
        root.addView(hud, lp(MATCH, MATCH))

        buildBrowseBar(); buildPlanSheet(); buildEta()
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
        applyInsets()
    }

    /** Synthetic СКУД lines for testing until real ones arrive: базовый across the corridor пом. 13 → К20, расширенный loop around К20. */
    private fun setTestAccess(on: Boolean) {
        Core.b.access.removeAll { it.id == -77 || it.id == -78 }
        if (on) {
            val a = Core.search.query("пом. 13").firstOrNull()?.mk?.invoke(); val z = Core.search.query("К20").firstOrNull()?.mk?.invoke()
            // базовый line across the corridor, расширенный loop around К20
            if (a != null && z != null) {
                Core.planner.syntheticAccessLine(a, z, 0.45, 1, 6.0, -77)?.let { Core.b.access.add(it) }
                Core.b.access.add(Core.planner.syntheticAccessBox(z, 6.0, 2, -78))
            }
        }
        Core.router.rebuildAccess(); if (::map.isInitialized) map.invalidate()
    }

    private fun setAccessLevel(l: Int) {
        Core.router.userLevel = l; map.accessLevel = l; prefs.edit().putInt("accessLevel", l).apply(); map.invalidate()
        if (mode == Mode.PLAN && to != null) plan()
    }

    private fun closureHere() {
        if (!Core.ready) return
        val f = lastFix
        if (f != null && f.floor == floor) addClosure(f.floor, map.arrowX, map.arrowY)
        else { val d = data ?: return; addClosure(floor, vp.cx + d.cx, vp.cy + d.cy) }
    }

    private fun cycleVoice() {
        val next = when (vplan.mode) { VoiceMode.ALL -> VoiceMode.IMPORTANT; VoiceMode.IMPORTANT -> VoiceMode.OFF; VoiceMode.OFF -> VoiceMode.ALL }
        setVoiceMode(next)
        toast(when (next) { VoiceMode.ALL -> "Все подсказки"; VoiceMode.IMPORTANT -> "Только важные подсказки"; VoiceMode.OFF -> "Звук выключен" })
    }

    private fun setVoiceMode(m: VoiceMode) {
        vplan.mode = m; voice.muted = m == VoiceMode.OFF; if (voice.muted) voice.stopAll()
        prefs.edit().putString("voice", m.name).apply(); refreshVoiceBtn()
    }

    private fun voiceIcon() = when (vplan.mode) { VoiceMode.OFF -> R.drawable.ya_sound_off; VoiceMode.IMPORTANT -> R.drawable.ya_sound_important; VoiceMode.ALL -> R.drawable.ya_sound_on }

    private fun refreshVoiceBtn() {
        if (::voiceBtn.isInitialized) (voiceBtn.getChildAt(0) as ImageView).setImageResource(voiceIcon())
        if (::etaSoundIcon.isInitialized) {
            etaSoundIcon.setImageResource(voiceIcon())
            etaSoundLabel.text = when (vplan.mode) { VoiceMode.OFF -> "Звук выкл"; VoiceMode.IMPORTANT -> "Важные"; VoiceMode.ALL -> "Все подсказки" }
        }
    }

    private fun handle(onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        setOnClickListener { onClick() }
        addView(View(context).apply { background = round(0xFFDDDDDD.toInt(), 3) }, lp(dp(40), dp(5), Gravity.CENTER))
    }

    private fun buildBrowseBar() {
        browseBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; noClip() }
        val where = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = ripple(0xFFEFF1F0.toInt(), 22)
            setPadding(dp(18), dp(14), dp(24), dp(14)); elevation = dp(3).toFloat(); setOnClickListener { openSearch(false) } }
        where.addView(icon(R.drawable.ya_route, 26, Ya.ICON)); where.addView(text("Куда?", 20, Ya.TEXT).apply { setPadding(dp(12), 0, 0, 0); maxLines = 1 })
        browseBar.addView(where, llp(WRAP, WRAP).apply { bottomMargin = dp(10) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = round(Color.WHITE, 22)
            elevation = dp(6).toFloat(); setPadding(dp(8), dp(10), dp(8), dp(10)) }
        fun item(res: Int, label: String, on: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = ripple(Color.WHITE, 16); setOnClickListener { on() }
            setPadding(0, dp(4), 0, dp(4))
            addView(icon(res, 28, Ya.ICON), llp(dp(28), dp(28)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(text(label, 12, Ya.TEXT2).apply { gravity = Gravity.CENTER; shrinkToFit(9f) }, llp(MATCH, WRAP).apply { topMargin = dp(4) })
        }
        bar.addView(item(R.drawable.ya_search, "Поиск") { openSearch(false) }, llp(0, WRAP, 1f))
        bar.addView(item(R.drawable.ya_bookmark, "История") { openSearch(false) }, llp(0, WRAP, 1f))
        bar.addView(item(R.drawable.ya_gear, "Настройки") { openSettings() }, llp(0, WRAP, 1f))
        browseBar.addView(bar, llp(MATCH, WRAP))
        root.addView(browseBar, lp(MATCH, WRAP, Gravity.BOTTOM))
    }

    // ---------------------------------------------------------------- route choice sheet
    private lateinit var planHead: TextView
    private lateinit var planFrom: TextView
    private lateinit var planCardsScroll: HorizontalScrollView
    private lateinit var planCards: LinearLayout
    private lateinit var planInfo: TextView
    private lateinit var planRow: LinearLayout
    private lateinit var planStepsScroll: ScrollView
    private lateinit var planSteps: LinearLayout
    private lateinit var accBtn: FrameLayout
    private lateinit var goBtn: TextView

    private class CardV(val v: LinearLayout, val label: TextView, val time: TextView, val dist: TextView, val via: TextView) { var color = 0 }
    private val cards = ArrayList<CardV>()

    /**
     * Route choice panel. Collapsed (default): destination, route cards, «Поехали». Swipe up: the route step by step.
     * Swipe down / ✕ / back: the route is cancelled (see [closePlan]).
     */
    private fun buildPlanSheet() {
        planSheet = DragSheet(this).apply { background = topRound(Color.WHITE, 26); elevation = dp(8).toFloat(); collapsible = true; dismissible = true
            maxHeightFrac = 0.92f; onDismiss = { closePlan() } }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(14)) }
        c.addView(handle { planSheet.toggle() }, llp(MATCH, dp(22)))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val close = FrameLayout(this).apply { background = ripple(Ya.CARD, 22); contentDescription = "Отменить маршрут"; setOnClickListener { closePlan() } }
        close.addView(ImageView(this).apply { setImageResource(R.drawable.ya_close); imageTintList = ColorStateList.valueOf(Ya.ICON) }, lp(dp(22), dp(22), Gravity.CENTER))
        head.addView(close, llp(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = ripple(Color.WHITE, 12)
            setPadding(0, dp(2), 0, dp(2)); setOnClickListener { openSearch(false) } }
        planFrom = text("", 14, Ya.TEXT2).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        planHead = text("", 17, Ya.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        titles.addView(planFrom, llp(MATCH, WRAP)); titles.addView(planHead, llp(MATCH, WRAP))
        head.addView(titles, llp(0, WRAP, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        head.addView(View(this), llp(dp(44), dp(1)))
        c.addView(head, llp(MATCH, WRAP))
        planCardsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        planCards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        planCardsScroll.addView(planCards)
        c.addView(planCardsScroll, llp(MATCH, WRAP).apply { topMargin = dp(12) })
        planInfo = text("", 13, Ya.TEXT2).apply { setPadding(dp(4), dp(8), dp(4), 0); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        c.addView(planInfo, llp(MATCH, WRAP))
        planRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        accBtn = mapButton(R.drawable.ya_accessible) { accessible = !accessible; prefs.edit().putBoolean("accessible", accessible).apply(); refreshAcc(); if (to != null) plan() }
        accBtn.elevation = 0f; accBtn.contentDescription = "Без лестниц и эскалаторов"
        planRow.addView(accBtn, llp(dp(52), dp(52)))
        goBtn = textButton("Поехали", Ya.BLUE, Color.WHITE, 18, 18) { if (routes.isNotEmpty() && mode == Mode.PLAN) startGuidance() }.single()
        planRow.addView(goBtn, llp(0, dp(56), 1f).apply { leftMargin = dp(12); rightMargin = dp(12) })
        planRow.addView(mapButton(R.drawable.ya_gear) { openSettings() }.apply { elevation = 0f; contentDescription = "Настройки" }, llp(dp(52), dp(52)))
        c.addView(planRow, llp(MATCH, WRAP).apply { topMargin = dp(12) })
        planSteps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        planStepsScroll = ScrollView(this).apply { addView(planSteps); isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; visibility = View.GONE }
        c.addView(planStepsScroll, llp(MATCH, WRAP, 1f))
        planSheet.addView(c, lp(MATCH, WRAP))
        planSheet.peek = { if (planRow.height > 0) planRow.bottomIn(planSheet) + dp(14) + insB else 0 }
        planSheet.scrollTarget = planStepsScroll
        root.addView(planSheet, lp(MATCH, WRAP, Gravity.BOTTOM))
        refreshAcc()
    }

    private fun refreshAcc() {
        if (!::accBtn.isInitialized) return
        (accBtn.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (accessible) Ya.BLUE else Ya.ICON)
        accBtn.background = ripple(if (accessible) 0xFFE3EDFF.toInt() else Ya.CARD, 16)
    }

    private fun fillPlanSheet() {
        planFrom.text = "Откуда: " + (from?.title ?: if (lastFix != null) "Моё местоположение" else "не выбрано")
        planHead.text = "Куда: " + (to?.title ?: "не выбрано") + " ▾"
        planCards.removeAllViews(); cards.clear()
        val has = routes.isNotEmpty()
        goBtn.isEnabled = has; goBtn.animate().alpha(if (has) 1f else 0.4f).setDuration(150).start()
        planStepsScroll.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(Ya.CARD, 20); setPadding(dp(16), dp(14), dp(16), dp(14)) }
            val avail = (sideWidth().takeIf { it > 0 } ?: root.width) - dp(64)
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val busy = planMsg == "Строим маршрут…"
            if (busy) line.addView(ProgressBar(this).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(Ya.BLUE) }, llp(dp(22), dp(22)).apply { rightMargin = dp(10) })
            line.addView(text(planMsg.ifEmpty { "Строим маршрут…" }, 16, Ya.TEXT).apply { maxWidth = avail - if (busy) dp(32) else 0 })
            box.addView(line)
            if (to == null || (from == null && lastFix == null)) {
                val pickFrom = to != null
                box.addView(textButton(if (pickFrom) "Выбрать, откуда" else "Выбрать, куда", Ya.BLUE, Color.WHITE, 14, 15) { openSearch(pickFrom) }.single(),
                    llp(WRAP, WRAP).apply { topMargin = dp(10) })
            }
            planCards.addView(box, llp(WRAP, WRAP))
            planInfo.text = ""; planInfo.visibility = View.GONE
            planSteps.removeAllViews()
            return
        }
        planInfo.visibility = if (compactH) View.GONE else View.VISIBLE
        for ((i, r) in routes.withIndex()) {
            val cv = makeCard(r, i)
            planCards.addView(cv.v, llp(WRAP, WRAP).apply { rightMargin = dp(10) })
            cards.add(cv)
            cv.v.alpha = 0f; cv.v.translationY = dp(14f)
            cv.v.animate().alpha(1f).translationY(0f).setStartDelay(45L * i).setDuration(240).setInterpolator(Ease.decel).start()
        }
        planCardsScroll.scrollTo(0, 0)
        styleCards(false); updatePlanInfo(); fillSteps()
        if (routes.first().connSteps.isNotEmpty()) setFloor(routes[active].legs.first().floor)
    }

    private fun makeCard(r: Route, i: Int): CardV {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(Ya.CARD, 20); minimumWidth = dp(128)
            setPadding(dp(16), dp(12), dp(18), dp(14)); setOnClickListener { selectRoute(i) } }
        val label = text(r.label, 15, Ya.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(200) }
        val time = text(fmtMin(r.time), 22, Ya.TEXT, true).apply { setPadding(0, dp(8), 0, dp(4)); maxLines = 1 }
        val dist = text(fmtDist(r.walk), 18, Ya.TEXT2).apply { maxLines = 1 }
        val viaS = if (r.connSteps.isEmpty()) "без смены этажа" else r.connSteps.joinToString(" → ") { it.conn?.shortName ?: it.kind }
        val via = text(viaS, 12, Ya.TEXT2).apply { setPadding(0, dp(6), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(200) }
        v.addView(label); v.addView(time); v.addView(dist); v.addView(via)
        if (compactH) via.visibility = View.GONE
        return CardV(v, label, time, dist, via).also { it.color = Ya.CARD }
    }

    /** Selected card blue (colour cross-fade), the others grey; the selected one scrolled into view. */
    private fun styleCards(animate: Boolean) {
        for ((i, c) in cards.withIndex()) {
            val on = i == active
            val want = if (on) Ya.BLUE else Ya.CARD
            val bg = c.v.background as GradientDrawable
            if (animate && c.color != want) {
                ValueAnimator.ofArgb(c.color, want).apply { duration = 180; addUpdateListener { bg.setColor(it.animatedValue as Int) }; start() }
            } else bg.setColor(want)
            c.color = want
            c.label.setTextColor(if (on) 0xCCFFFFFF.toInt() else Ya.TEXT2)
            c.time.setTextColor(if (on) Color.WHITE else Ya.TEXT)
            c.dist.setTextColor(if (on) 0xCCFFFFFF.toInt() else Ya.TEXT2)
            c.via.setTextColor(if (on) 0xCCFFFFFF.toInt() else Ya.TEXT2)
        }
    }

    /** Another route: in place (no rebuild, the cards row keeps its scroll), map + steps updated. */
    private fun selectRoute(i: Int) {
        if (i == active || i !in routes.indices) return
        active = i; map.active = i; map.invalidate()
        styleCards(true); updatePlanInfo(); fillSteps()
        cards.getOrNull(i)?.v?.let { v -> planCardsScroll.post {
            val x0 = planCardsScroll.scrollX; val w = planCardsScroll.width
            if (v.left < x0 || v.right > x0 + w) planCardsScroll.smoothScrollTo((v.left - dp(16)).coerceAtLeast(0), 0)
        } }
        routes[i].legs.firstOrNull()?.let { if (it.floor != floor && routes[i].connSteps.isNotEmpty()) setFloor(it.floor) }
    }

    private fun updatePlanInfo() {
        val r = routes.getOrNull(active) ?: return
        val floorNotice = if (r.legs.any { it.floor != 2 }) " · Маршрут проходит по другим этажам; карта показывает только 2 этаж" else ""
        if (r.unreachable) { planInfo.text = "Прохода к цели нет — маршрут ведёт как можно ближе" + floorNotice; planInfo.setTextColor(0xFFE65100.toInt()); return }
        r.accessStop?.let { line -> planInfo.text = line.note() + " — маршрут ведёт до прохода" + floorNotice; planInfo.setTextColor(0xFFE65100.toInt()); return }
        planInfo.setTextColor(Ya.TEXT2)
        val ap = to?.approx == true || from?.approx == true
        val nm = r.maneuvers.count { it.type != MType.START && it.type != MType.FINISH }
        planInfo.text = (if (accessible) "Без лестниц и эскалаторов · " else "") + (if (nm == 0) "без поворотов" else "$nm " + com.gmcnav.navi.core.Search.plural(nm, "манёвр", "манёвра", "манёвров")) +
            (if (ap) " · вход в помещение на плане не распознан, ведём к ближайшей точке" else "") +
            (if (Core.planner.closures.isNotEmpty()) " · учтены перекрытия: ${Core.planner.closures.size}" else "") + " · ↑ по шагам" + floorNotice
    }

    /** The selected route step by step (revealed by swiping the panel up); a tap shows the step on the map. */
    private fun fillSteps() {
        planSteps.removeAllViews()
        val r = routes.getOrNull(active) ?: return
        planSteps.addView(View(this).apply { setBackgroundColor(0xFFEDEDED.toInt()) }, llp(MATCH, dp(1)).apply { topMargin = dp(14) })
        planSteps.addView(text("Маршрут по шагам", 15, Ya.TEXT2, true).apply { setPadding(dp(4), dp(12), 0, dp(4)) })
        var prevS = 0.0
        for (m in r.maneuvers.filter { it.type != MType.START }) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = ripple(Color.WHITE, 12)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setOnClickListener { planSheet.setState(DragSheet.State.COLLAPSED); root.afterLayout { focusOn(m.floor, m.x, m.y, maxOf(vp.scale, 10f)) } } }
            row.addView(icon(plateIconRes(m), 26, Ya.ICON), llp(dp(26), dp(26)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
            val title = if (m.type == MType.FINISH) "Прибытие" + (to?.title?.let { " · $it" } ?: "") else m.text
            col.addView(text(title, 16, Ya.TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            val hint = if (m.type == MType.CONNECTOR) m.conn?.shortName ?: "" else if (m.type == MType.FINISH) "" else m.hint
            if (hint.isNotEmpty()) col.addView(text(hint, 13, Ya.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0) })
            row.addView(col, llp(0, WRAP, 1f))
            row.addView(text(fmtDist(max(1.0, m.s - prevS)), 14, Ya.TEXT2).apply { maxLines = 1 })
            planSteps.addView(row, llp(MATCH, WRAP))
            prevS = m.s
        }
    }

    // ---------------------------------------------------------------- guidance panel
    /** ETA panel: remaining distance / arrival / minutes + progress; swipe up for overview, sound, closure, finish. */
    private fun buildEta() {
        etaSheet = DragSheet(this).apply { background = topRound(Color.WHITE, 22); elevation = dp(8).toFloat(); collapsible = true; dismissible = false
            maxHeightFrac = 0.8f }
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12)) }
        c.addView(handle { etaSheet.toggle() }, llp(MATCH, dp(18)))
        etaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ov = FrameLayout(this).apply { background = ripple(Ya.CARD, 16); contentDescription = "Обзор маршрута"
            setOnClickListener { guidance?.route?.let { fitRoute(it) } } }
        ov.addView(ImageView(this).apply { setImageResource(R.drawable.ya_route); imageTintList = ColorStateList.valueOf(Ya.ICON) }, lp(dp(28), dp(28), Gravity.CENTER))
        etaRow.addView(ov, llp(dp(52), dp(52)))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        val nums = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun num(g: Int) = text("", 24, Ya.TEXT, true).apply { gravity = g or Gravity.CENTER_VERTICAL; maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(12, 24, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
        etaDist = num(Gravity.START); etaTime = num(Gravity.CENTER_HORIZONTAL); etaMin = num(Gravity.END)
        nums.addView(etaDist, llp(0, dp(34), 1f)); nums.addView(etaTime, llp(0, dp(34), 1f)); nums.addView(etaMin, llp(0, dp(34), 1f))
        mid.addView(nums)
        etaProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(Ya.GREEN); progressBackgroundTintList = ColorStateList.valueOf(0xFFB5B4AF.toInt()) }
        mid.addView(etaProgress, llp(MATCH, dp(8)).apply { topMargin = dp(6) })
        etaRow.addView(mid, llp(0, WRAP, 1f))
        val x = FrameLayout(this).apply { background = ripple(Ya.CARD, 16); contentDescription = "Завершить маршрут"; setOnClickListener { askFinish() } }
        x.addView(ImageView(this).apply { setImageResource(R.drawable.ya_close); imageTintList = ColorStateList.valueOf(Ya.ICON) }, lp(dp(26), dp(26), Gravity.CENTER))
        etaRow.addView(x, llp(dp(52), dp(52)))
        c.addView(etaRow, llp(MATCH, WRAP))
        // expanded part
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(res: Int, label: String, on: () -> Unit): Pair<ImageView, TextView> {
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = ripple(Ya.CARD, 16)
                setPadding(dp(4), dp(12), dp(4), dp(10)); setOnClickListener { on() } }
            val ic = icon(res, 26, Ya.ICON); t.addView(ic, llp(dp(26), dp(26)))
            val tx = text(label, 13, Ya.TEXT).apply { gravity = Gravity.CENTER; shrinkToFit(10f) }
            t.addView(tx, llp(MATCH, WRAP).apply { topMargin = dp(6) })
            tiles.addView(t, llp(0, WRAP, 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
            return ic to tx
        }
        tile(R.drawable.ya_route, "Обзор") { guidance?.route?.let { etaSheet.setState(DragSheet.State.COLLAPSED); root.afterLayout { fitRoute(it) } } }
        val snd = tile(voiceIcon(), "") { cycleVoice() }
        etaSoundIcon = snd.first; etaSoundLabel = snd.second
        c.addView(tiles, llp(MATCH, WRAP).apply { topMargin = dp(16) })
        c.addView(textButton("Завершить маршрут", 0xFFFFEBEE.toInt(), Ya.RED, 16, 17) { askFinish() }.single(), llp(MATCH, WRAP).apply { topMargin = dp(12) })
        etaSheet.addView(c, lp(MATCH, WRAP))
        etaSheet.peek = { if (etaRow.height > 0) etaRow.bottomIn(etaSheet) + dp(12) + insB else 0 }
        root.addView(etaSheet, lp(MATCH, WRAP, Gravity.BOTTOM))
        refreshVoiceBtn()
    }

    // ================================================================== search / directions screen
    private var editFrom = false

    private fun openSearch(fromField: Boolean) {
        if (!Core.ready) { whenReady { openSearch(fromField) }; return }
        closeSheet()
        editFrom = fromField
        val scr = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); isClickable = true }
        // header: back · "Маршрут"
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val back = FrameLayout(this).apply { background = ripple(Ya.CARD, 22); contentDescription = "Назад"; setOnClickListener { closeOverlay() } }
        back.addView(ImageView(this).apply { setImageResource(R.drawable.ya_back); imageTintList = ColorStateList.valueOf(Ya.ICON) }, lp(dp(22), dp(22), Gravity.CENTER))
        head.addView(back, llp(dp(44), dp(44)))
        head.addView(text("Маршрут", 24, Ya.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 }, llp(0, WRAP, 1f))
        head.addView(View(this), llp(dp(44), dp(44)))
        scr.addView(head)
        // from / to rows
        fun fieldRow(dot: Int, label: String, active: Boolean, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)); setOnClickListener { onClick() }
            addView(ImageView(context).apply { setImageResource(dot) }, llp(dp(26), dp(26)))
            addView(text(label, 18, if (active) Ya.TEXT else Ya.TEXT2, active).apply { setPadding(dp(16), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, llp(0, WRAP, 1f))
        }
        val fromLabel = from?.title ?: if (lastFix != null) "Моё местоположение" else "Откуда"
        scr.addView(fieldRow(R.drawable.ya_dot_from, fromLabel, editFrom) { openSearch(true) })
        scr.addView(View(this).apply { setBackgroundColor(0xFFE6E6E6.toInt()) }, llp(MATCH, dp(1)).apply { leftMargin = dp(60); rightMargin = dp(16) })
        scr.addView(fieldRow(R.drawable.ya_dot_to, to?.title ?: "Куда", !editFrom) { openSearch(false) })
        // query
        val q = EditText(this).apply {
            hint = if (editFrom) "Откуда: номер, название, лифт…" else "Куда: номер, название, лифт…"
            textSize = 18f; setSingleLine(); background = round(0xFFF0F0F0.toInt(), 14); setPadding(dp(16), dp(12), dp(16), dp(12))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        scr.addView(q, llp(MATCH, WRAP).apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(10) })
        if (editFrom && lastFix != null) scr.addView(text("📍  Моё местоположение", 17, Ya.BLUE).apply { setPadding(dp(22), dp(14), dp(16), dp(6))
            setOnClickListener { closeOverlay(); setFrom(null) } })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(this).apply { addView(body) }
        scr.addView(sv, llp(MATCH, 0, 1f).apply { topMargin = dp(8) })
        fun pick(it: SearchItem) {
            val p = it.mk()
            hideKeyboard(q)
            closeOverlay()
            if (intent.getBooleanExtra("select_meeting_place", false)) { returnMeetingPlace(p); return }
            if (editFrom) setFrom(p) else setTo(p)
        }
        fun showResults(items: List<SearchItem>, empty: String) {
            body.removeAllViews()
            if (items.isEmpty()) { body.addView(text(empty, 15, Ya.TEXT2).apply { setPadding(dp(22), dp(16), dp(16), dp(16)) }); return }
            val f = lastFix
            for (it in items) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(12), dp(18), dp(12)); background = ripple(Color.WHITE, 0); setOnClickListener { _ -> pick(it) } }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(text(it.title, 17, Ya.TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                col.addView(text(it.sub, 13, Ya.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
                row.addView(col, llp(0, WRAP, 1f))
                if (f != null) row.addView(text(fmtDist(Core.search.dist(it, f.floor, f.x, f.y)), 14, Ya.TEXT2).apply { setPadding(dp(10), 0, 0, 0); maxLines = 1 })
                body.addView(row)
            }
        }
        fun showHome() {
            body.removeAllViews()
            // category chips like the Yandex categories grid (4 per row on phones, 8 when wide); only those this site has
            val icons = mapOf("wc" to R.drawable.ya_cat_wc, "lift" to R.drawable.ya_elevator_small, "stair" to R.drawable.ya_stairs_glyph,
                "escalator" to R.drawable.ya_escalator_small, "entrance" to R.drawable.ya_cat_door, "food" to R.drawable.ya_cat_food,
                "hall" to R.drawable.ya_cat_hall, "meet" to R.drawable.ya_cat_meet, "home" to R.drawable.ya_cat_home)
            val cats = com.gmcnav.navi.core.Categories.CHIPS.filter { (id, _) -> Core.search.category(id, limit = 1).isNotEmpty() }
                .map { (id, t) -> Triple(t, icons.getValue(id), id) }
            val perRow = if (root.width >= dp(640)) 8 else 4
            var row: LinearLayout? = null
            for ((i, c) in cats.withIndex()) {
                if (i % perRow == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(10), dp(8), 0) }; body.addView(row) }
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setOnClickListener { val f = lastFix; showResults(if (f != null) Core.search.category(c.third, f.floor, f.x, f.y) else Core.search.category(c.third), "Ничего не найдено") } }
                val circ = FrameLayout(this).apply { background = ripple(0xFFF2F2F2.toInt(), 32) }
                circ.addView(ImageView(this).apply { setImageResource(c.second); imageTintList = ColorStateList.valueOf(Ya.ICON) }, lp(dp(30), dp(30), Gravity.CENTER))
                cell.addView(circ, llp(dp(64), dp(64)))
                cell.addView(text(c.first, 13, Ya.TEXT).apply { gravity = Gravity.CENTER; setPadding(dp(2), dp(6), dp(2), dp(6)); shrinkToFit(9f) }, llp(MATCH, WRAP))
                row!!.addView(cell, llp(0, WRAP, 1f))
            }
            val hist = history()
            if (hist.isNotEmpty()) {
                body.addView(text("История", 15, Ya.TEXT2).apply { setPadding(dp(22), dp(18), dp(16), dp(4)) })
                val items = hist.mapNotNull { h -> Core.search.query(h).firstOrNull { it.title == h } }
                val f = lastFix
                for (it in items) {
                    body.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(18), dp(10)); background = ripple(Color.WHITE, 0)
                        setOnClickListener { _ -> pick(it) }
                        addView(text(it.title, 17, Ya.TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                        addView(text(it.sub + (f?.let { ff -> " · " + fmtDist(Core.search.dist(it, ff.floor, ff.x, ff.y)) } ?: ""), 13, Ya.TEXT2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) })
                }
            }
        }
        showHome()
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s.toString()
                if (t.isBlank()) showHome() else { val f = lastFix; showResults(if (f != null) Core.search.query(t, f.floor, f.x, f.y) else Core.search.query(t), "Ничего не найдено") }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        q.setOnEditorActionListener { _, a, ev -> if (a == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { hideKeyboard(q); true } else false }
        openOverlay(scr)
        q.requestFocus()
        q.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(q, 0) }, 200)
    }

    private fun hideKeyboard(v: View) { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0) }

    private fun histKey() = if (Core.site.id == "gmc") "history" else "history_${Core.site.id}"
    private fun history(): List<String> = prefs.getString(histKey(), "")!!.split('\n').filter { it.isNotBlank() }
    /** Floor button text: 0 = «П» (подвал), 9 = «Ч» (чердак). */
    private fun floorLabel(n: Int) = when (n) { 0 -> "П"; 9 -> "Ч"; else -> "$n" }
    private fun saveHistory(p: Place?) {
        if (p == null || p.kind == "point" || p.isMe) return
        val l = (listOf(p.title) + history().filter { it != p.title }).take(10)
        prefs.edit().putString(histKey(), l.joinToString("\n")).apply()
    }

    // ================================================================== closures sheet
    private fun openClosures() {
        if (!Core.ready) { whenReady { openClosures() }; return }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        v.addView(text("Перекрытия", 22, Ya.TEXT, true))
        v.addView(text("Отметить: долгое нажатие на карте → «Перекрыто здесь», или кнопка «!» слева — у вас. Убрать: нажмите на знак на карте или «Убрать» здесь.",
            14, Ya.TEXT2).apply { setPadding(0, dp(6), 0, dp(12)) })
        val cl = Core.planner.closures.toList()
        if (cl.isEmpty()) v.addView(text("Сейчас перекрытий нет", 16, Ya.TEXT).apply { setPadding(0, dp(4), 0, dp(4)) })
        for (c in cl) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { closeSheet(); focusOn(c.floor, c.x, c.y) } }
            row.addView(ImageView(this).apply { setImageResource(R.drawable.ya_no_entry) }, llp(dp(24), dp(24)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            col.addView(text(c.title, 16, Ya.TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            col.addView(text(Building.floorName(c.floor) + if (c.connId != 0) " · не работает" else " · проход", 13, Ya.TEXT2))
            row.addView(col, llp(0, WRAP, 1f))
            row.addView(textButton("Убрать", Ya.CARD, Ya.BLUE, 12, 15) { closeSheet(); removeClosures(listOf(c.id)); main.postDelayed({ openClosures() }, 200) }.single(), llp(WRAP, WRAP))
            v.addView(row, llp(MATCH, WRAP))
        }
        if (cl.size > 1) v.addView(textButton("Убрать все", Ya.CARD, Ya.RED) { closeSheet(); removeClosures(null) }.single(),
            llp(MATCH, WRAP).apply { topMargin = dp(12) })
        openSheet(ScrollView(this).apply { addView(v); isVerticalScrollBarEnabled = false })
    }

    // ================================================================== settings
    /**
     * What a user needs: site, voice, СКУД pass, СКУД passages on the map, no stairs. Everything diagnostic (position source,
     * demo replay / simulation, test СКУД lines, Wi-Fi debug overlay, radio map import and stats) is in the collapsed
     * «Для разработчика» section.
     */
    private fun openSettings() {
        if (!Core.ready) { whenReady { openSettings() }; return }
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        v.addView(text("Настройки", 22, Ya.TEXT, true))
        // live Wi-Fi under Android's scan limit: how to get more scans (the user changes it; the app never does)
        if (source() == "live" && pos.status.limited) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(0xFFFFF4E0.toInt(), 16); setPadding(dp(14), dp(12), dp(14), dp(12)) }
            box.addView(text("Android ограничивает сканирование Wi-Fi (4 раза за 2 мин) — позиция точнее, если отключить: " +
                "Настройки → Для разработчиков → «Ограничение запросов на сканирование Wi-Fi»", 14, Ya.TEXT))
            box.addView(textButton("Открыть настройки", Color.WHITE, Ya.BLUE, 12, 15) { openScanThrottleSettings() }.single(),
                llp(WRAP, WRAP).apply { topMargin = dp(10) })
            v.addView(box, llp(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        fun header(s: String) = text(s, 17, Ya.TEXT, true).apply { setPadding(0, dp(18), 0, 0) }
        fun note(s: String) = text(s, 13, Ya.TEXT2).apply { setPadding(0, dp(6), 0, 0) }
        v.addView(header("Голосовые подсказки"))
        v.addView(segmented(listOf("Выкл", "Важные", "Все"), when (vplan.mode) { VoiceMode.OFF -> 0; VoiceMode.IMPORTANT -> 1; VoiceMode.ALL -> 2 }) { i ->
            setVoiceMode(listOf(VoiceMode.OFF, VoiceMode.IMPORTANT, VoiceMode.ALL)[i]) })
        v.addView(switchRow("Без лестниц и эскалаторов (маломобильным)", accessible) { accessible = it; prefs.edit().putBoolean("accessible", it).apply(); refreshAcc(); if (mode == Mode.PLAN && to != null) plan() })
        val sv = ScrollView(this).apply { addView(v); isVerticalScrollBarEnabled = false }
        openSheet(sv)
    }

    /** «Для разработчика»: position source (live / demo replay / simulation), test СКУД, Wi-Fi debug, radio map. Refilled in place. */
    private fun fillDev(v: LinearLayout, onSource: () -> Unit) {
        v.removeAllViews()
        fun note(s: String) = text(s, 13, Ya.TEXT2).apply { setPadding(0, dp(6), 0, 0) }
        v.addView(text("Источник позиции", 16, Ya.TEXT).apply { setPadding(0, dp(8), 0, 0) })
        val src = source()
        v.addView(segmented(listOf("Wi-Fi + шаги", "Демо-запись", "Симуляция"), when (src) { "replay" -> 1; "sim" -> 2; else -> 0 }) { i ->
            val s = listOf("live", "replay", "sim")[i]
            if (s == source()) return@segmented
            prefs.edit().putString("source", s).apply(); sessionSource = null
            resetPosition(); startSource(); onSource(); fillDev(v, onSource)
        })
        if (sessionSource != null) v.addView(note("Сейчас задан параметром запуска (до перезапуска приложения)."))
        if (src == "replay") {
            for (p in Core.replayIndex.parts) {
                val sel = prefs.getString("replayFile", Core.replayIndex.parts.first().file) == p.file
                v.addView(text("${if (sel) "● " else "○ "}Часть ${p.part}: ${p.durS / 60} мин, этажи ${p.floors.joinToString("–")}, ${p.marks} отметок" + if (p.cells > 0) ", соты" else "",
                    15, if (sel) Ya.BLUE else Ya.TEXT).apply { setPadding(dp(4), dp(8), 0, dp(4)); setOnClickListener {
                        prefs.edit().putString("replayFile", p.file).apply(); resetPosition(); startSource(); fillDev(v, onSource) } })
            }
            val sp = prefs.getFloat("speed", 2f)
            v.addView(segmented(listOf("×1", "×2", "×4"), when (sp) { 1f -> 0; 4f -> 2; else -> 1 }) { i ->
                val s = listOf(1f, 2f, 4f)[i]; prefs.edit().putFloat("speed", s).apply(); pos.setReplaySpeed(s.toDouble()) })
            v.addView(note("Повтор части записи 27.10 (только НТУ): позиция на карте — из записи, не ваша."))
        }
        if (src == "sim") v.addView(note("Симуляция идёт по выбранному маршруту со скоростью 1.25 м/с — для проверки интерфейса и голоса за столом."))
        v.addView(switchRow("Тестовые линии СКУД у К20 (до реальных данных)", prefs.getBoolean("testAccess", false)) {
            prefs.edit().putBoolean("testAccess", it).apply(); setTestAccess(it); if (mode == Mode.PLAN && to != null) plan() })
        v.addView(switchRow("Отладка: Wi-Fi-оценка, эталон записи, AP в строке статуса", map.debug) { map.debug = it; prefs.edit().putBoolean("debug", it).apply(); map.invalidate(); updateStatus() })
        v.addView(textButton("Импорт радиокарты (ZIP из «Загрузок»)", Ya.CARD, Ya.TEXT) { importZip() }.single(), llp(MATCH, WRAP).apply { topMargin = dp(14) })
        val r = Core.radio
        v.addView(note("Радиокарта: ${r.points.size} отпечатков Wi-Fi (${r.indoorCount} в здании), ${Core.cells.points.size} сотовых, сессии: ${r.sessions.distinct().joinToString()}"))
        v.addView(note("Линий СКУД на плане: ${Core.b.access.size}"))
    }

    private fun segmented(items: List<String>, sel: Int, on: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = round(0xFFF2F2F2.toInt(), 16); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for ((i, s) in items.withIndex()) {
            val t = text(s, 15, Ya.TEXT, i == sel).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(12), dp(4), dp(12)); shrinkToFit(10f)
                if (i == sel) { background = round(Color.WHITE, 13); elevation = dp(2).toFloat() }
                setOnClickListener {
                    for (k in 0 until box.childCount) { val c = box.getChildAt(k) as TextView; c.background = if (k == i) round(Color.WHITE, 13) else null; c.elevation = if (k == i) dp(2).toFloat() else 0f }
                    on(i)
                } }
            box.addView(t, llp(0, WRAP, 1f))
        }
        box.layoutParams = llp(MATCH, WRAP).apply { topMargin = dp(10) }
        return box
    }

    private fun switchRow(label: String, on: Boolean, cb: (Boolean) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, dp(4))
        val sw = Switch(context).apply { isChecked = on; setOnCheckedChangeListener { _, c -> cb(c) } }
        addView(text(label, 16, Ya.TEXT).apply { setPadding(0, 0, dp(8), 0) }, llp(0, WRAP, 1f))
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    /**
     * Opens the system developer options (where «Ограничение запросов на сканирование Wi-Fi» is); if they are not enabled,
     * «О телефоне» (where they are turned on by tapping the build number). Nothing is changed by the app.
     */
    private fun openScanThrottleSettings() {
        val devOn = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val actions = (if (devOn) listOf(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) else emptyList()) +
            listOf(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS, android.provider.Settings.ACTION_SETTINGS)
        for (a in actions) {
            try {
                startActivity(Intent(a))
                if (a == android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)
                    toast("Режим разработчика выключен: включите его (7 раз нажмите «Номер сборки» в «Сведениях о ПО»), затем найдите «Ограничение запросов на сканирование Wi-Fi»")
                return
            } catch (_: android.content.ActivityNotFoundException) { }
        }
        toast("Не удалось открыть настройки")
    }

    private fun importZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        @Suppress("DEPRECATION") startActivityForResult(i, 7)
    }

    @Deprecated("") override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != 7 || res != RESULT_OK) return
        toast("Импорт…")
        thread {
            val msg = runCatching { contentResolver.openInputStream(uri)!!.use { Core.importZip(this, it) } }.getOrElse { "Ошибка импорта: ${it.message}" }
            main.post { toast(msg); if (source() == "live") { resetPosition(); startSource() } }
        }
    }

    // ================================================================== sheets & overlays
    /**
     * Bottom card ([DragSheet]): springs in, swipe down / back / tap outside (modal) closes it. A non-modal card (place card)
     * keeps the map usable; [onDismiss] runs however it is closed.
     */
    private fun openSheet(content: View, modal: Boolean = true, onDismiss: (() -> Unit)? = null) {
        closeSheet()
        val holder = FrameLayout(this).apply { elevation = dp(60).toFloat() }
        var dim: View? = null
        if (modal) {
            dim = View(this).apply { setBackgroundColor(0x55000000); alpha = 0f; setOnClickListener { closeSheet() } }
            holder.addView(dim, lp(MATCH, MATCH)); dim.animate().alpha(1f).setDuration(200).start()
        }
        val card = DragSheet(this).apply {
            background = topRound(Color.WHITE, 26); elevation = dp(12).toFloat(); dismissible = true; maxHeightFrac = 0.88f; topGap = insT + dp(8)
            setPadding(0, dp(10), 0, dp(16) + insB); maxWidthPx = sideWidth()
            scrollTarget = content as? ScrollView
        }
        card.addView(handle { }, lp(MATCH, dp(14), Gravity.TOP))
        card.addView(content, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        holder.addView(card, lp(MATCH, WRAP, panelGravity()).apply { if (landscape()) leftMargin = insL })
        root.addView(holder, lp(MATCH, MATCH))
        val h = SheetH(holder, card, dim, modal, onDismiss)
        sheetH = h
        card.onDismiss = { if (sheetH === h) closeSheet() }
        card.show(DragSheet.State.EXPANDED)
    }

    private fun closeSheet() {
        val h = sheetH ?: return
        sheetH = null
        h.dim?.let { it.isClickable = false; it.animate().alpha(0f).setDuration(180).start() }
        h.card.hide { root.removeView(h.holder) }
        h.onDismiss?.invoke()
    }

    private fun openOverlay(v: View) {
        closeOverlay(false)
        v.elevation = dp(50).toFloat(); root.addView(v, lp(MATCH, MATCH)); overlay = v
        v.setPadding(insL, insT, insR, max(insB, imeB))
        v.alpha = 0f; v.translationY = dp(28f)
        v.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(Ease.decel).start()
    }

    private fun closeOverlay(animated: Boolean = true) {
        val v = overlay ?: return
        overlay = null; hideKeyboard(v)
        if (!animated) { v.animate().cancel(); root.removeView(v); return }
        v.animate().alpha(0f).translationY(dp(20f)).setDuration(160).setInterpolator(Ease.accel).withEndAction { root.removeView(v) }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

/** Bottom edge of this view in the coordinates of [ancestor]. */
fun View.bottomIn(ancestor: View): Int {
    var y = bottom; var p: View? = parent as? View
    while (p != null && p !== ancestor) { y += p.top; p = p.parent as? View }
    return y
}
