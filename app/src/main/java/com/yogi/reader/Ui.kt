package com.yogi.reader

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    const val BG = 0xFF121212.toInt()
    const val CARD = 0xFF1E1E1E.toInt()
    const val CARD2 = 0xFF2A2A2A.toInt()
    const val TEXT = 0xFFF3EFE7.toInt()
    const val SUB = 0xFF9C9690.toInt()
    const val ACCENT = 0xFFFF9F43.toInt()
    const val BLUE = 0xFF4FA3FF.toInt()
    const val RED = 0xFFFF5A5F.toInt()
    const val GREEN = 0xFF4CD48A.toInt()
}

object Store {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) {
        if (!::sp.isInitialized) sp = ctx.applicationContext.getSharedPreferences("yogireader", Context.MODE_PRIVATE)
    }
    fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun setStr(k: String, v: String) { sp.edit().putString(k, v).apply() }
    fun bool(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    fun setBool(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }
    fun int(k: String, d: Int): Int = sp.getInt(k, d)
    fun setInt(k: String, v: Int) { sp.edit().putInt(k, v).apply() }
    fun obj(k: String): JSONObject = try { JSONObject(str(k, "{}")) } catch (_: Exception) { JSONObject() }
    fun setObj(k: String, o: JSONObject) { sp.edit().putString(k, o.toString()).commit() }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeW: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, strokeColor)
    }

fun Context.ripple(color: Int, radiusDp: Float): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, dpf(radiusDp)), null)

fun Context.label(s: CharSequence, size: Float = 15f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun <T : View> T.lin(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): T {
    layoutParams = LinearLayout.LayoutParams(w, h, weight).also { it.setMargins(l, t, r, b) }
    return this
}

fun Context.card(color: Int = C.CARD): LinearLayout = vbox().apply {
    background = rounded(color, dpf(20f))
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.setMargins(dp(14), dp(6), dp(14), dp(6)) }
}

fun Context.chip(s: String, selected: Boolean, onClick: () -> Unit): TextView =
    label(s, 14f, if (selected) Color.BLACK else C.TEXT, selected).apply {
        gravity = Gravity.CENTER
        background = ripple(if (selected) C.ACCENT else C.CARD2, 14f)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        setOnClickListener { onClick() }
    }.lin(WRAP, WRAP, r = dp(8), b = dp(8))

fun Context.button(s: String, color: Int = C.ACCENT, textColor: Int = Color.BLACK, onClick: () -> Unit): TextView =
    label(s, 15f, textColor, true).apply {
        gravity = Gravity.CENTER
        background = ripple(color, 16f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

fun Context.toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

/** "1.4 GB" */
fun size(b: Long): String {
    if (b < 1024) return "$b B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
fun date(ms: Long): String = synchronized(dateFmt) { dateFmt.format(Date(ms)) }
