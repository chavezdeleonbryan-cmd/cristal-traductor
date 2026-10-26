package com.bryan.cristal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Un texto encontrado en pantalla, con su posición y su traducción (si ya está). */
data class Shown(val bounds: Rect, val translated: String)

/**
 * Capa transparente que no recibe toques: todo lo que tocas pasa a la app de
 * abajo. Solo dibuja el borde del cristal y las traducciones encima del texto.
 */
class GlassView(context: Context) : View(context) {

    var glass = Rect()
    var items: List<Shown> = emptyList()
    var paused = false
    var status = ""

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val loc = IntArray(2)

    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        color = Color.argb(200, 79, 195, 247)
    }
    private val tint = Paint().apply { color = Color.argb(14, 79, 195, 247) }
    private val bg = Paint().apply { color = Color.argb(245, 255, 252, 244) }
    private val statusBg = Paint().apply { color = Color.argb(220, 30, 30, 50) }
    private val statusText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * sp
    }

    private val layoutCache = object : LinkedHashMap<String, StaticLayout>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StaticLayout>?): Boolean =
            size > 400
    }

    override fun onDraw(c: Canvas) {
        if (paused) return
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())

        c.drawRect(glass, tint)

        c.save()
        c.clipRect(glass)
        for (item in items) drawItem(c, item)
        c.restore()

        val r = 14f * d
        c.drawRoundRect(RectF(glass), r, r, border)

        if (status.isNotEmpty()) {
            val pad = 8f * d
            val w = statusText.measureText(status)
            val y = glass.bottom - 12f * d
            c.drawRoundRect(
                glass.left + 12f * d, y - statusText.textSize - pad,
                glass.left + 12f * d + w + pad * 2, y + pad / 2,
                r, r, statusBg
            )
            c.drawText(status, glass.left + 12f * d + pad, y - pad / 3, statusText)
        }
        c.restore()
    }

    private fun drawItem(c: Canvas, item: Shown) {
        val b = item.bounds
        val w = max(b.width(), (48 * d).toInt())
        val h = max(b.height(), (12 * d).toInt())
        val layout = layoutFor(item.translated, w, h)
        val pad = 2f * d
        val boxH = max(h, layout.height)
        c.drawRect(b.left - pad, b.top - pad, b.left + w + pad, b.top + boxH + pad, bg)
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        layout.draw(c)
        c.restore()
    }

    /** Busca el tamaño de letra más grande que quepa en el espacio del texto original. */
    private fun layoutFor(text: String, w: Int, h: Int): StaticLayout {
        val key = "$w|$h|$text"
        layoutCache[key]?.let { return it }

        val maxSize = 18f * sp
        val minSize = 9f * sp
        var size = min(maxSize, max(minSize, h * 0.8f))
        var layout: StaticLayout
        while (true) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(25, 25, 30)
                textSize = size
            }
            layout = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
            if (layout.height <= h || size <= minSize) break
            size = max(minSize, size - 1f * sp)
        }
        layoutCache[key] = layout
        return layout
    }
}
