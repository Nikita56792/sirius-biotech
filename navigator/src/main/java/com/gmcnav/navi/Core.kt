package com.gmcnav.navi

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.gmcnav.navi.core.AssetSource
import com.gmcnav.navi.core.Building
import com.gmcnav.navi.core.CellMap
import com.gmcnav.navi.core.Planner
import com.gmcnav.navi.core.RadioMap
import com.gmcnav.navi.core.ReplayIndex
import com.gmcnav.navi.core.Router
import com.gmcnav.navi.core.Search
import com.gmcnav.navi.core.SiteInfo
import com.gmcnav.navi.core.Sites
import com.gmcnav.navi.core.PrefixAssets
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class NaviApp : Application() {
    override fun onCreate() { super.onCreate(); app = this }
    companion object { lateinit var app: NaviApp }
}

/** One site's models: building, routing, search and radio. */
class SiteModel(val info: SiteInfo, val b: Building, val router: Router, val planner: Planner, val search: Search) {
    @Volatile var radio = RadioMap()
    @Volatile var cells = CellMap()
}

/**
 * Loads one site in stages, in parallel, off the main thread, and keeps the result:
 * MAP (floor geometry, tens of ms: the map can be drawn) → MODEL (building, router + planner, search: places, search,
 * routes) → RADIO (Wi-Fi / cell radio model: positioning). The building footprint (initial camera) is computed alongside.
 */
class SiteLoader(val info: SiteInfo, private val app: Context) {
    @Volatile var map: MapData? = null; private set
    @Volatile var model: SiteModel? = null; private set
    @Volatile var radioDone = false; private set
    @Volatile var footprint: DoubleArray? = null; private set
    @Volatile var error: String? = null; private set
    private val main = Handler(Looper.getMainLooper())
    private class Sub(val l: Core.LoadListener) { var map = false; var model = false; var radio = false; var fp = false; var err = false }
    private val subs = ArrayList<Sub>()                       // main thread only
    private val tids = java.util.Collections.synchronizedList(ArrayList<Int>())

    /** Adds a listener (main thread): stages already loaded are delivered right away, always in order. */
    fun listen(l: Core.LoadListener) { subs.add(Sub(l)); dispatch() }
    fun unlisten(l: Core.LoadListener) { subs.removeAll { it.l === l } }

    private fun dispatch() {
        for (s in subs.toList()) {
            if (s !in subs) continue
            val e = error
            if (e != null) { if (!s.err) { s.err = true; s.l.onError(e) }; continue }
            map?.let { if (!s.map) { s.map = true; s.l.onMap(it) } }
            if (s.map && !s.fp) footprint?.let { s.fp = true; s.l.onFootprint(it) }
            if (s.map && model != null && !s.model) { s.model = true; s.l.onModel() }
            if (s.model && radioDone && !s.radio) { s.radio = true; s.l.onRadio() }
        }
    }

    private fun post() = main.post { dispatch() }

    /** The active site loads at normal priority; a preloaded one in the background until it becomes active. */
    fun boost() { synchronized(tids) { for (t in tids) runCatching { Process.setThreadPriority(t, Process.THREAD_PRIORITY_DEFAULT) } } }

    fun start(background: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val prio = if (background) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT
        fun mark(w: String) {
            val n = SystemClock.elapsedRealtime()
            android.util.Log.i("GmcNavi", "load ${info.id}: $w at +${n - t0} ms (process t+${n - Process.getStartElapsedRealtime()} ms)${if (background) " [preload]" else ""}")
        }
        fun fail(t: Throwable) { if (error == null) { error = t.toString(); android.util.Log.e("GmcNavi", "load ${info.id} failed", t); post() } }
        fun th(name: String, body: () -> Unit) = thread(name = name) {
            tids.add(Process.myTid()); Process.setThreadPriority(prio)
            try { body() } catch (t: Throwable) { fail(t) }
        }
        val buildingLatch = CountDownLatch(1); val modelLatch = CountDownLatch(1)
        var building: Building? = null
        // the map first and alone (asset reads serialise on the AssetManager: in parallel its 30 ms became 500+ ms), then one
        // reader fetches the other files in the order the parsers need them while they parse in parallel
        th("load-map") {
            val d = MapData(app, info.prefix); map = d; mark("map geometry"); post()
            val io = Prefetch(app, listOf("${info.prefix}map/places.json", "${info.prefix}radio/wifi.json", "${info.prefix}radio/cell.json") +
                d.floors.map { "${info.prefix}map/nav${it.n}.u16" } + listOf("${info.prefix}map/walked.json", "${info.prefix}map/stairs.json"), prio)
            th("load-model") {
                try {
                    val b = Building(PrefixAssets(AssetSource { io.open(it) }, info.prefix)); building = b; buildingLatch.countDown(); mark("building")
                    // two CPU chains only (model here, radio there): more parallel parsers just slowed each other down on the phone
                    val router = Router(b); val planner = Planner(b, router); mark("router + planner")
                    val search = Search(b); EventPresentation.filter(search); mark("search")
                    model = SiteModel(info, b, router, planner, search)
                    post()
                } finally { buildingLatch.countDown(); modelLatch.countDown() }
            }
            th("load-radio") {
                val r = RadioMap(); val c = CellMap()
                r.loadPacked(io.open("${info.prefix}radio/wifi.json").bufferedReader().readText())
                runCatching { c.loadPacked(io.open("${info.prefix}radio/cell.json").bufferedReader().readText()) }
                Core.ensureReplayIndex(app)
                mark("radio model (packed)")
                buildingLatch.await()
                val b = building ?: return@th
                Core.addImported(app, info, b, r, c)
                // the initial camera of the next start: the building footprint (BFS over the ground floor grid), only when not
                // cached for this map version yet
                if (Core.savedFootprint(app, info) == null) { footprint = b.footprint.also { Core.saveFootprint(app, info, it) }; mark("footprint"); post() }
                modelLatch.await()
                val m = model ?: return@th
                m.radio = r; m.cells = c; radioDone = true; mark("radio model ready"); post()
            }
        }
    }
}

/** One reader thread fetches asset files in the given order; [open] waits for its file (no lock contention between parsers). */
class Prefetch(private val app: Context, files: List<String>, prio: Int) {
    private val data = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val ready = files.associateWith { CountDownLatch(1) }

    init {
        thread(name = "load-io") {
            Process.setThreadPriority(prio)
            for (f in files) { runCatching { data[f] = app.assets.open(f).use { it.readBytes() } }; ready.getValue(f).countDown() }
        }
    }

    /** The prefetched bytes (each file once), or a direct read (missing files throw as before). */
    fun open(path: String): java.io.InputStream {
        val l = ready[path] ?: return app.assets.open(path)
        l.await()
        return data.remove(path)?.let { java.io.ByteArrayInputStream(it) } ?: app.assets.open(path)
    }
}

/**
 * Sites (assets/sites.json) and their models. The active site is loaded in stages ([SiteLoader]); loaded sites stay in memory
 * (the active one and the other one, preloaded in the background when RAM allows), so switching sites is immediate.
 * Core.b / router / planner / search / radio / cells refer to the active site (valid once [ready]); signals never mix.
 */
object Core {
    interface LoadListener {
        fun onMap(d: MapData)
        fun onFootprint(fp: DoubleArray) {}
        fun onModel()
        fun onRadio()
        fun onError(msg: String)
    }

    @Volatile var sites: List<SiteInfo> = emptyList(); private set
    @Volatile private var active: SiteLoader? = null
    private val loaders = LinkedHashMap<String, SiteLoader>()           // main thread only

    val site: SiteInfo get() = active!!.info
    val model: SiteModel? get() = active?.model
    val b: Building get() = model!!.b
    val router: Router get() = model!!.router
    val planner: Planner get() = model!!.planner
    val search: Search get() = model!!.search
    val radio: RadioMap get() = model!!.radio
    val cells: CellMap get() = model!!.cells
    /** Places, search and routes of the active site are usable. */
    val ready: Boolean get() = active?.model != null
    /** The active site's radio model is loaded (positioning can start). */
    val radioReady: Boolean get() = active?.radioDone == true
    val error: String? get() = active?.error
    lateinit var replayIndex: ReplayIndex; private set

    fun assets(ctx: Context) = AssetSource { ctx.assets.open(it) }

    /** The site to use now: the saved active site (auto-detected or chosen in settings), ГМЦ by default. */
    fun wantedSite(ctx: Context): String = ctx.getSharedPreferences("navi", Context.MODE_PRIVATE).getString("activeSite", "gmc")!!

    private fun ensureSites(app: Context) {
        if (sites.isEmpty()) sites = runCatching { Sites.parse(app.assets.open("sites.json").bufferedReader().readText()) }.getOrNull()
            ?: listOf(SiteInfo("gmc", "НТУ", "", Building.GEO_LON, Building.GEO_LAT, 300.0))
    }

    /** Makes the wanted site active (main thread) and delivers its stages to [l] (immediately for what is already loaded). */
    fun load(ctx: Context, l: LoadListener): SiteLoader {
        val app = ctx.applicationContext
        ensureSites(app)
        val info = sites.firstOrNull { it.id == wantedSite(app) } ?: sites.first()
        val ld = loaders.remove(info.id)?.takeIf { it.error == null }?.also { it.boost() } ?: SiteLoader(info, app).also { it.start(background = false) }
        loaders[info.id] = ld                                            // most recent last
        active = ld
        ld.listen(l)
        return ld
    }

    fun isLoaded(id: String) = loaders[id]?.model != null

    /** Loads another site in the background (low priority) if there is enough free heap for it next to the active one. */
    fun preload(ctx: Context, id: String, force: Boolean = false) {
        if (id in loaders) return
        val info = sites.firstOrNull { it.id == id } ?: return
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (!force && free < 220L * 1024 * 1024) { android.util.Log.i("GmcNavi", "preload $id skipped: ${free / 1048576} MB free"); return }
        if (force && free < 220L * 1024 * 1024) trim()               // switching: the new site wins
        loaders[id] = SiteLoader(info, ctx.applicationContext).also { it.start(background = !force) }
        android.util.Log.i("GmcNavi", "preload $id started (${free / 1048576} MB free)")
    }

    /** Low memory: only the active site stays. */
    fun trim() { val a = active ?: return; loaders.keys.filter { it != a.info.id }.forEach { loaders.remove(it) } }

    /** Makes [id] the active site (the activity is recreated and loads it — immediately when it is already in memory). */
    fun setActiveSite(ctx: Context, id: String) {
        ctx.getSharedPreferences("navi", Context.MODE_PRIVATE).edit().putString("activeSite", id).apply()
    }

    @Synchronized fun ensureReplayIndex(app: Context) {
        if (!::replayIndex.isInitialized) replayIndex = ReplayIndex(app.assets.open("replay/index.json").bufferedReader().readText())
    }

    // ------------------------------------------------------------------ cached footprint (initial camera before the model)
    /** Map version stamp: the APK install time (assets change only with an update; openFd does not work on compressed assets). */
    @Suppress("UNUSED_PARAMETER")
    private fun stamp(app: Context, info: SiteInfo) = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    fun saveFootprint(app: Context, info: SiteInfo, fp: DoubleArray) {
        app.getSharedPreferences("navi", Context.MODE_PRIVATE).edit()
            .putString("fp_${info.id}", "${stamp(app, info)};" + fp.joinToString(",")).apply()
    }

    /** Footprint saved by an earlier start for the current map version, or null. */
    fun savedFootprint(ctx: Context, info: SiteInfo): DoubleArray? {
        val s = ctx.getSharedPreferences("navi", Context.MODE_PRIVATE).getString("fp_${info.id}", null) ?: return null
        val (st, v) = s.split(";").takeIf { it.size == 2 } ?: return null
        if (st.toLongOrNull() != stamp(ctx.applicationContext, info)) return null
        return v.split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 4 }?.toDoubleArray()
    }

    // ------------------------------------------------------------------ radio
    fun importedDir(ctx: Context) = File(ctx.filesDir, "sessions").also { it.mkdirs() }

    /**
     * Sessions imported from ZIPs for this site (session.json "site", ГМЦ when absent) added to the packed radio map; an
     * imported session replaces the packed one of the same id. Other sites' fingerprints are never loaded.
     */
    fun addImported(ctx: Context, info: SiteInfo, b: Building, r: RadioMap, c: CellMap) {
        val imported = (importedDir(ctx).listFiles()?.filter { File(it, "marks.json").exists() && File(it, "fp_marks.jsonl").exists() } ?: emptyList())
            .filter { d -> (runCatching { (com.gmcnav.navi.core.Json.parse(File(d, "session.json").readText()) as Map<*, *>)["site"] as? String }.getOrNull() ?: "gmc") == info.id }
        if (imported.isEmpty()) return
        val importedIds = imported.map { it.name }.toSet()
        val drop = r.sessions.withIndex().filter { it.value in importedIds }.map { it.index }.toSet()
        r.points.removeAll { it.session in drop }
        for (d in imported) {
            r.addSession(d.name, File(d, "marks.json").readText(), File(d, "fp_marks.jsonl").readText()) { f, x, y ->
                val fi = b.fi(f); fi < 0 || b.snapXY(fi, x, y, 3.0) < 0
            }
            File(d, "cell_fp.jsonl").takeIf { it.exists() }?.let { c.addJsonl(it.readText()) }
        }
    }

    /** Rebuilds the active site's radio map: packed + imported sessions (after an import). */
    fun reloadRadio(ctx: Context) {
        val m = model ?: return
        val r = RadioMap(); val c = CellMap()
        r.loadPacked(ctx.assets.open("${m.info.prefix}radio/wifi.json").bufferedReader().readText())
        runCatching { c.loadPacked(ctx.assets.open("${m.info.prefix}radio/cell.json").bufferedReader().readText()) }
        addImported(ctx, m.info, m.b, r, c)
        m.radio = r; m.cells = c
    }

    /**
     * Imports a calibration export ZIP (GMC_calib): every directory holding marks.json + fp_marks.jsonl (+ cell_fp.jsonl)
     * becomes a session. Returns a short report.
     */
    fun importZip(ctx: Context, input: java.io.InputStream): String {
        val files = HashMap<String, HashMap<String, ByteArray>>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                val base = name.substringAfterLast('/')
                if (base !in setOf("marks.json", "fp_marks.jsonl", "cell_fp.jsonl", "session.json")) continue
                val dir = name.substringBeforeLast('/', "")
                files.getOrPut(dir) { HashMap() }[base] = z.readBytes()
            }
        }
        var n = 0; val ids = ArrayList<String>()
        for ((dir, m) in files) {
            if ("marks.json" !in m || "fp_marks.jsonl" !in m) continue
            val id = runCatching { com.gmcnav.navi.core.Json.parse(String(m.getValue("marks.json"))).let { (it as Map<*, *>)["session"] as? String } }.getOrNull()
                ?: dir.substringAfterLast('/').ifEmpty { "import_${System.currentTimeMillis()}" }
            val out = File(importedDir(ctx), id.replace(Regex("[^A-Za-z0-9_.-]"), "_")).also { it.mkdirs() }
            for ((k, v) in m) File(out, k).writeBytes(v)
            n++; ids.add(id)
        }
        if (n == 0) return "В архиве нет сессий калибровки (marks.json + fp_marks.jsonl)"
        reloadRadio(ctx)
        return "Импортировано сессий: $n (${ids.joinToString()}); отпечатков Wi-Fi в радиокарте: ${radio.points.size}"
    }
}
