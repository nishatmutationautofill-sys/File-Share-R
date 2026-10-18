package com.rishat.fileshare

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

class Palette(val dark: Boolean) {
    private fun c(d: Long, l: Long): Int = (if (dark) d else l).toInt()

    val bg = c(0xFF0E1318, 0xFFF3F5F9)
    val card = c(0xFF18202A, 0xFFFFFFFF)
    val stroke = c(0xFF27323F, 0xFFE2E7EF)
    val text = c(0xFFE8EDF3, 0xFF14233A)
    val sub = c(0xFF93A1B3, 0xFF5B6878)
    val accent = c(0xFF5B9DFF, 0xFF1769E0)
    val onAccent = c(0xFF08121F, 0xFFFFFFFF)
    val danger = c(0xFFFF6B6B, 0xFFD93025)
    val ok = c(0xFF4CD08A, 0xFF1E9E5A)
    val warn = c(0xFFFFB84D, 0xFFE08A00)
    val ripple = c(0x33FFFFFF, 0x22000000)
    val track = c(0xFF2A3644, 0xFFE3E9F2)
}

class Ui(private val ctx: Context) {
    val p = Palette(
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    )
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (stroke != 0) d.setStroke(dp(1), stroke)
        return d
    }

    fun dot(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.OVAL)
        d.setColor(color)
        return d
    }

    fun ripple(base: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(p.ripple), base, null)

    fun text(t: String, sp: Float, color: Int, bold: Boolean = false): TextView {
        val v = TextView(ctx)
        v.text = t
        v.textSize = sp
        v.setTextColor(color)
        if (bold) v.setTypeface(v.typeface, Typeface.BOLD)
        return v
    }

    fun card(): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = rounded(p.card, 14, p.stroke)
        return c
    }

    fun button(label: String, style: Int = SECONDARY, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.textSize = 15f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.minHeight = dp(48)
        b.minimumHeight = dp(48)
        b.stateListAnimator = null
        b.setPadding(dp(16), 0, dp(16), 0)
        applyStyle(b, style)
        b.setOnClickListener { onClick() }
        return b
    }

    fun applyStyle(b: Button, style: Int) {
        when (style) {
            PRIMARY -> {
                b.setTextColor(p.onAccent)
                b.background = ripple(rounded(p.accent, 12))
            }
            DANGER -> {
                b.setTextColor(p.danger)
                b.background = ripple(rounded(p.card, 12, p.danger))
            }
            TEXT -> {
                b.setTextColor(p.accent)
                b.background = ripple(rounded(0x00000000, 12))
            }
            else -> {
                b.setTextColor(p.text)
                b.background = ripple(rounded(p.card, 12, p.stroke))
            }
        }
    }

    fun progressBar(): ProgressBar {
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(p.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(p.track)
        return bar
    }

    fun lpMW(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(top)
        lp.bottomMargin = dp(bottom)
        return lp
    }

    companion object {
        const val PRIMARY = 0
        const val SECONDARY = 1
        const val DANGER = 2
        const val TEXT = 3
    }
}

fun fmtSize(b: Long): String = when {
    b < 1024L -> "$b B"
    b < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
}

fun fmtSpeed(bytesPerSec: Double): String = fmtSize(bytesPerSec.toLong()) + "/s"

fun fmtEta(sec: Long): String = when {
    sec < 60 -> "$sec সেকেন্ড"
    sec < 3600 -> "${sec / 60} মিনিট ${sec % 60} সেকেন্ড"
    else -> "${sec / 3600} ঘণ্টা ${(sec % 3600) / 60} মিনিট"
}
