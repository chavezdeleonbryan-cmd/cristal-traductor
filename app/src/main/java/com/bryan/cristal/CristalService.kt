package com.bryan.cristal

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * El corazón de la app. Android le avisa cada vez que algo cambia en pantalla
 * (scroll, página nueva, texto nuevo). Lee el texto real que hay debajo del
 * cristal —sin capturas— y dibuja la traducción encima, sin bloquear los toques.
 */
class CristalService : AccessibilityService() {

    companion object {
        var instance: CristalService? = null
            private set
    }

    private lateinit var wm: WindowManager
    private var glass: GlassView? = null
    private var handle: LinearLayout? = null
    private lateinit var handleParams: WindowManager.LayoutParams
    private lateinit var pauseBtn: TextView

    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: TranslateEngine

    private var paused = false
    private var scanScheduled = false
    private var screenW = 0
    private var screenH = 0
    private val glassRect = Rect()
    private val heights = floatArrayOf(0.35f, 0.5f, 0.75f, 1f)
    private var heightIdx = 1
    private var found: List<Pair<String, Rect>> = emptyList()

    private val scanRunnable = Runnable { scanScheduled = false; scan() }
    private val trailingScan = Runnable { scan() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        engine = TranslateEngine { render() }
        measureScreen()

        val h = (screenH * heights[heightIdx]).toInt()
        val top = (screenH - h) / 2
        glassRect.set(0, top, screenW, top + h)

        val gv = GlassView(this)
        val gp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        gp.gravity = Gravity.TOP or Gravity.START
        wm.addView(gv, gp)
        glass = gv

        buildHandle()
        updateGlass()
        scheduleScan()
    }

    @Suppress("DEPRECATION")
    private fun measureScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    // ---------- Barra para mover el cristal ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildHandle() {
        val d = resources.displayMetrics.density
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 18f * d
                setColor(Color.argb(225, 28, 32, 58))
            }
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((12 * d).toInt(), (7 * d).toInt(), (12 * d).toInt(), (7 * d).toInt())
        }

        val grip = label("⠿ Mover")
        var startY = 0f
        var startTop = 0
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startTop = glassRect.top }
                MotionEvent.ACTION_MOVE -> {
                    val hgt = glassRect.height()
                    val newTop = (startTop + (e.rawY - startY)).toInt()
                        .coerceIn(0, max(0, screenH - hgt))
                    glassRect.offsetTo(0, newTop)
                    updateGlass()
                    scheduleScan()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleScan()
            }
            true
        }

        pauseBtn = label("⏸").apply { setOnClickListener { togglePause() } }
        val sizeBtn = label("↕").apply { setOnClickListener { cycleSize() } }
        val closeBtn = label("✕").apply { setOnClickListener { disableSelf() } }

        bar.addView(grip)
        bar.addView(pauseBtn)
        bar.addView(sizeBtn)
        bar.addView(closeBtn)

        handleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        handleParams.gravity = Gravity.TOP or Gravity.END
        handleParams.x = (10 * d).toInt()
        wm.addView(bar, handleParams)
        handle = bar
    }

    private fun updateGlass() {
        val d = resources.displayMetrics.density
        val barH = (40 * d).toInt()
        handleParams.y = if (glassRect.top >= barH) glassRect.top - barH else glassRect.top + (4 * d).toInt()
        handle?.let { wm.updateViewLayout(it, handleParams) }
        glass?.let {
            it.glass = Rect(glassRect)
            it.invalidate()
        }
    }

    private fun togglePause() {
        paused = !paused
        pauseBtn.text = if (paused) "▶" else "⏸"
        glass?.paused = paused
        glass?.invalidate()
        if (!paused) scheduleScan()
    }

    private fun cycleSize() {
        heightIdx = (heightIdx + 1) % heights.size
        val h = (screenH * heights[heightIdx]).toInt()
        val centre = glassRect.centerY()
        var top = centre - h / 2
        top = top.coerceIn(0, max(0, screenH - h))
        glassRect.set(0, top, screenW, top + h)
        updateGlass()
        scheduleScan()
    }

    // ---------- Lectura de la pantalla ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || paused) return
        if (event.packageName == packageName) return
        scheduleScan()
    }

    /** Como mucho ~14 lecturas por segundo mientras haces scroll, y una final al parar. */
    private fun scheduleScan() {
        main.removeCallbacks(trailingScan)
        main.postDelayed(trailingScan, 350)
        if (scanScheduled) return
        scanScheduled = true
        main.postDelayed(scanRunnable, 70)
    }

    private fun scan() {
        if (paused || glass == null) return
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName == packageName) continue
                roots.add(r)
            }
        } catch (_: Exception) { }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName != packageName) roots.add(it) }
        }

        val out = ArrayList<Pair<String, Rect>>()
        val tmp = Rect()
        for (r in roots) collect(r, out, tmp, 0)

        // Primero se piden los textos que están más arriba en el cristal.
        out.sortBy { it.second.top }
        found = out
        for ((text, _) in out) engine.request(text)
        render()
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Pair<String, Rect>>, tmp: Rect, depth: Int) {
        if (out.size >= 150 || depth > 80) return
        n.getBoundsInScreen(tmp)
        if (tmp.width() > 0 && tmp.height() > 0 && !Rect.intersects(tmp, glassRect)) return

        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && n.isVisibleToUser && !n.isEditable && t.length >= 2 && t.any { it.isLetter() }) {
            if (tmp.width() > 0 && tmp.height() > 0) out.add(t to Rect(tmp))
            return
        }
        for (i in 0 until n.childCount) {
            val child = n.getChild(i) ?: continue
            collect(child, out, tmp, depth + 1)
        }
    }

    private fun render() {
        val gv = glass ?: return
        val shown = ArrayList<Shown>(found.size)
        for ((text, rect) in found) {
            val tr = engine.get(text) ?: continue
            shown.add(Shown(rect, tr))
        }
        gv.items = shown
        gv.status = engine.status
        gv.invalidate()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        try { glass?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { handle?.let { wm.removeView(it) } } catch (_: Exception) { }
        glass = null
        handle = null
        if (::engine.isInitialized) engine.close()
        super.onDestroy()
    }
}
