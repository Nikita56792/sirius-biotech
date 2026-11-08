package com.gmcnav.navi

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** План этажа в OpenGL ES 3: полы помещений (цвет по категории), стены, лестничные марши, двери. */
class MapGLView(ctx: Context, attrs: AttributeSet? = null) : GLSurfaceView(ctx, attrs) {
    val renderer = MapRenderer()

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MsaaChooser())
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun bind(data: MapData, vp: Viewport) { renderer.data = data; renderer.vp = vp; requestRender() }

    private var fade: android.animation.ValueAnimator? = null

    /** Shows floor [n]; with [animate] the previous floor cross-fades into it (~0.26 s). */
    fun setFloor(n: Int, animate: Boolean = false) {
        val old = renderer.floor
        fade?.cancel(); fade = null
        if (animate && old != n) {
            renderer.prevFloor = old; renderer.mix = 0f; renderer.floor = n
            fade = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260; interpolator = Ease.standard
                addUpdateListener { renderer.mix = it.animatedValue as Float; requestRender() }
                start()
            }
        } else { renderer.mix = 1f; renderer.floor = n }
        requestRender()
    }
}

private class MsaaChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, d: EGLDisplay): EGLConfig {
        fun pick(samples: Int): EGLConfig? {
            val a = mutableListOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 0, EGL10.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */)
            if (samples > 0) a += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
            a += EGL10.EGL_NONE
            val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            return if (egl.eglChooseConfig(d, a.toIntArray(), cfg, 1, n) && n[0] > 0) cfg[0] else null
        }
        return pick(4) ?: pick(0) ?: throw IllegalStateException("нет конфигурации EGL")
    }
}

class MapRenderer : GLSurfaceView.Renderer {
    @Volatile var data: MapData? = null
    @Volatile var floor = 1
    @Volatile var vp: Viewport? = null
    /** Floor switch cross-fade: [prevFloor] drawn with 1 − [mix], [floor] with [mix]. */
    @Volatile var prevFloor = Int.MIN_VALUE
    @Volatile var mix = 1f

    private class Gpu(val vao: IntArray, val counts: IntArray, val extra: Map<String, Pair<Int, Int>> = emptyMap())

    private val gpu = HashMap<Int, Gpu>()
    private var prog = 0
    private var uM = 0
    private var uColor = 0
    private var uUseCol = 0
    private var uAlpha = 0
    /** Floors drawn faintly under another one (floors + walls only): floor 3 shows floor 2 (as in the calibration app). */
    private val underlay = mapOf(3 to 2)
    private val m = FloatArray(9)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        gpu.clear()
        prog = program(
            """#version 300 es
            uniform mat3 uM; uniform vec4 uColor; uniform int uUseCol; uniform float uAlpha;
            layout(location=0) in vec2 aPos; layout(location=1) in vec3 aCol;
            out vec4 vCol;
            void main(){ vec3 p = uM * vec3(aPos, 1.0); gl_Position = vec4(p.xy, 0.0, 1.0);
              vCol = uUseCol == 1 ? vec4(aCol, 1.0) : uColor; vCol.a *= uAlpha; }""",
            """#version 300 es
            precision mediump float; in vec4 vCol; out vec4 o; void main(){ o = vCol; }""",
        )
        uM = GLES30.glGetUniformLocation(prog, "uM")
        uColor = GLES30.glGetUniformLocation(prog, "uColor")
        uUseCol = GLES30.glGetUniformLocation(prog, "uUseCol")
        uAlpha = GLES30.glGetUniformLocation(prog, "uAlpha")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { GLES30.glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0.925f, 0.937f, 0.918f, 1f)   // outdoor ground: light neutral-green
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val d = data ?: return
        val v = vp ?: return
        v.ndc(m)
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix3fv(uM, 1, false, m, 0)
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        val f = floor; val pf = prevFloor; val mx = mix
        if (mx < 0.999f && pf != Int.MIN_VALUE && pf != f) {
            runCatching { gpu.getOrPut(pf) { upload(d.floor(pf)) } }.getOrNull()?.let { drawFloor(it, pf, v, 1f - mx) }
            drawFloor(gpu.getOrPut(f) { upload(d.floor(f)) }, f, v, mx)
        } else drawFloor(gpu.getOrPut(f) { upload(d.floor(f)) }, f, v, 1f)
        GLES30.glBindVertexArray(0)
    }

    private fun drawFloor(g: Gpu, fl: Int, v: Viewport, a: Float) {
        val d = data ?: return
        // the floor below, faint (room floors 0.28, walls 0.25), no labels / icons
        underlay[fl]?.let { un ->
            val u = try { gpu.getOrPut(un) { upload(d.floor(un)) } } catch (e: Exception) { null }
            if (u != null) {
                GLES30.glUniform1f(uAlpha, 0.28f * a); GLES30.glUniform1i(uUseCol, 1)
                GLES30.glBindVertexArray(u.vao[0]); GLES30.glDrawElements(GLES30.GL_TRIANGLES, u.counts[0], GLES30.GL_UNSIGNED_INT, 0)
                GLES30.glUniform1f(uAlpha, 0.25f * a); GLES30.glUniform1i(uUseCol, 0)
                GLES30.glUniform4f(uColor, 0.62f, 0.65f, 0.70f, 1f)
                GLES30.glBindVertexArray(u.vao[1]); GLES30.glDrawElements(GLES30.GL_TRIANGLES, u.counts[1], GLES30.GL_UNSIGNED_INT, 0)
            }
        }
        GLES30.glUniform1f(uAlpha, a)
        // полы помещений
        GLES30.glUniform1i(uUseCol, 1)
        GLES30.glBindVertexArray(g.vao[0]); GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.counts[0], GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glUniform1i(uUseCol, 0)
        // outdoor paths (light) under everything else
        drawExtra(g, "path_xy", if (v.scale > 6f) 4f else 2.5f, 1f, 1f, 0.98f)
        // лестницы, двери
        GLES30.glLineWidth(if (v.scale > 15f) 2.5f else 1.5f)
        GLES30.glUniform4f(uColor, 0.55f, 0.62f, 0.70f, 1f)
        GLES30.glBindVertexArray(g.vao[2]); GLES30.glDrawArrays(GLES30.GL_LINES, 0, g.counts[2])
        GLES30.glUniform4f(uColor, 0.80f, 0.82f, 0.85f, 1f)
        GLES30.glBindVertexArray(g.vao[3]); GLES30.glDrawArrays(GLES30.GL_LINES, 0, g.counts[3])
        // мебель (трибуны, ряды кресел) — тонко, светло-коричневым
        if (g.counts.size > 4 && g.counts[4] > 0) {
            GLES30.glLineWidth(if (v.scale > 15f) 1.5f else 1f)
            GLES30.glUniform4f(uColor, 0.78f, 0.66f, 0.52f, 1f)
            GLES30.glBindVertexArray(g.vao[4]); GLES30.glDrawArrays(GLES30.GL_LINES, 0, g.counts[4])
        }
        // стены поверх
        GLES30.glUniform4f(uColor, 0.62f, 0.65f, 0.70f, 1f)
        GLES30.glBindVertexArray(g.vao[1]); GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.counts[1], GLES30.GL_UNSIGNED_INT, 0)
        // territory fence (dark gray) and roof outline (thin solid gray) on top
        drawExtra(g, "fence_xy", 2.5f, 0.30f, 0.32f, 0.35f)
        drawExtra(g, "edit_xy", 3f, 0.36f, 0.40f, 0.45f)          // walls drawn by the user in the calibration app
        drawExtra(g, "roof_xy", 1.5f, 0.58f, 0.60f, 0.63f)
    }

    private fun drawExtra(g: Gpu, key: String, width: Float, r: Float, gg: Float, b: Float) {
        val (vao, count) = g.extra[key] ?: return
        if (count <= 0) return
        GLES30.glLineWidth(width)
        GLES30.glUniform4f(uColor, r, gg, b, 1f)
        GLES30.glBindVertexArray(vao); GLES30.glDrawArrays(GLES30.GL_LINES, 0, count)
    }

    private fun upload(f: FloorGeom): Gpu {
        val vao = IntArray(5); GLES30.glGenVertexArrays(5, vao, 0)
        val vbo = IntArray(8); GLES30.glGenBuffers(8, vbo, 0)
        fun arr(target: Int, buf: Int, b: java.nio.ByteBuffer) {
            GLES30.glBindBuffer(target, buf); b.position(0)
            GLES30.glBufferData(target, b.capacity(), b, GLES30.GL_STATIC_DRAW)
        }
        // 0: помещения (позиции + цвет + индексы)
        GLES30.glBindVertexArray(vao[0])
        arr(GLES30.GL_ARRAY_BUFFER, vbo[0], f.roomXY)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        arr(GLES30.GL_ARRAY_BUFFER, vbo[1], f.roomRGB)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_UNSIGNED_BYTE, true, 3, 0)
        arr(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[2], f.roomIdx)
        // 1: стены
        GLES30.glBindVertexArray(vao[1])
        arr(GLES30.GL_ARRAY_BUFFER, vbo[3], f.wallXY)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        arr(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[4], f.wallIdx)
        // 2, 3: линии
        GLES30.glBindVertexArray(vao[2])
        arr(GLES30.GL_ARRAY_BUFFER, vbo[5], f.stairXY)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(vao[3])
        arr(GLES30.GL_ARRAY_BUFFER, vbo[6], f.doorXY)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        f.furnXY?.let { fb ->
            GLES30.glBindVertexArray(vao[4])
            arr(GLES30.GL_ARRAY_BUFFER, vbo[7], fb)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        }
        GLES30.glBindVertexArray(0)
        val extra = HashMap<String, Pair<Int, Int>>()
        for ((k, v) in f.extra) {
            val ea = IntArray(1); GLES30.glGenVertexArrays(1, ea, 0)
            val eb = IntArray(1); GLES30.glGenBuffers(1, eb, 0)
            GLES30.glBindVertexArray(ea[0]); arr(GLES30.GL_ARRAY_BUFFER, eb[0], v.first)
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            extra[k] = ea[0] to v.second
        }
        GLES30.glBindVertexArray(0)
        return Gpu(vao, intArrayOf(f.roomIdxCount, f.wallIdxCount, f.stairCount * 2, f.doorCount * 2, f.furnCount), extra)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw IllegalStateException("shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }
}
