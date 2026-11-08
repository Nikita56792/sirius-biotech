package com.gmcnav.navi

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Yandex Navigator palette (sampled from the screenshots) and small view builders. */
object Ya {
    const val BLUE = 0xFF196EFF.toInt()          // buttons, selected route card
    const val PLATE = 0xFF1666FD.toInt()         // maneuver plate
    const val PLATE_DARK = 0xFF0F4FCC.toInt()
    const val GREEN = 0xFF4DD219.toInt()         // progress
    const val RED = 0xFFFF4433.toInt()
    const val TEXT = 0xFF212121.toInt()
    const val TEXT2 = 0xFF8C8C8C.toInt()
    const val CARD = 0xFFF6F6F6.toInt()
    const val BG = 0xFFF4F4F4.toInt()
    const val ICON = 0xFF4D4D4D.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun Context.round(color: Int, radiusDp: Number, stroke: Int = 0, strokeDp: Number = 0): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != 0) setStroke(dp(strokeDp), stroke) }

fun Context.ripple(color: Int, radiusDp: Number): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x22000000), round(color, radiusDp), null)

fun Context.text(s: String, sp: Number, color: Int = Ya.TEXT, bold: Boolean = false): TextView = TextView(this).apply {
    text = s; textSize = sp.toFloat(); setTextColor(color)
    typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
    includeFontPadding = false
}

fun Context.icon(res: Int, sizeDp: Int, tint: Int? = Ya.ICON): ImageView = ImageView(this).apply {
    setImageResource(res); scaleType = ImageView.ScaleType.FIT_CENTER
    if (tint != null) imageTintList = ColorStateList.valueOf(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** White rounded floating map button (56 dp) like the Yandex map controls. */
fun Context.mapButton(res: Int, tint: Int = Ya.ICON, round: Boolean = false, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
    background = ripple(Color.WHITE, if (round) 28 else 16)
    elevation = dp(4).toFloat()
    addView(ImageView(context).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(tint) },
        FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    setOnClickListener { onClick() }
}

fun Context.textButton(s: String, bg: Int, fg: Int, radius: Int = 16, sp: Number = 17, onClick: () -> Unit): TextView = text(s, sp, fg, true).apply {
    gravity = Gravity.CENTER; background = ripple(bg, radius); setPadding(dp(16), dp(12), dp(16), dp(12))
    setOnClickListener { onClick() }
}

fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)
fun llp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(l, t, r, b); return this
}

fun fmtDist(m: Double): String = when {
    m < 10 -> "${m.toInt().coerceAtLeast(1)} м"
    m < 1000 -> "${(Math.round(m / 5.0) * 5).toInt()} м"
    else -> "%.1f км".format(m / 1000)
}

fun fmtMin(s: Double): String { val m = Math.round(s / 60.0).toInt(); return "${m.coerceAtLeast(1)} мин" }

/** Let elevation shadows of children fade out instead of being cut at this container's edges. */
fun android.view.ViewGroup.noClip() { clipChildren = false; clipToPadding = false }

/** One-line button text that shrinks a little instead of wrapping. */
fun TextView.single(): TextView = apply { maxLines = 1; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
    setAutoSizeTextTypeUniformWithConfiguration(12, textSize.let { (it / resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(13) }, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
