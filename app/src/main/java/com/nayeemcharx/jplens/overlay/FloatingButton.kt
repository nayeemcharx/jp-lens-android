package com.nayeemcharx.jplens.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.nayeemcharx.jplens.OverlayService
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Blends [color] toward white by [t] (0..1), keeping its alpha. */
private fun lighten(color: Int, t: Float): Int = Color.argb(
    Color.alpha(color),
    Color.red(color) + ((255 - Color.red(color)) * t).toInt(),
    Color.green(color) + ((255 - Color.green(color)) * t).toInt(),
    Color.blue(color) + ((255 - Color.blue(color)) * t).toInt(),
)

/** Scales [color] toward black by [t] (0..1), keeping its alpha. */
private fun darken(color: Int, t: Float): Int = Color.argb(
    Color.alpha(color),
    (Color.red(color) * (1 - t)).toInt(),
    (Color.green(color) * (1 - t)).toInt(),
    (Color.blue(color) * (1 - t)).toInt(),
)

/**
 * The "full screen" glyph: four bent corner brackets (an L at each corner) with
 * a rounded bend — a viewfinder/expand look, replacing the old 🔍 emoji. Strokes
 * within its bounds; used on the floating button and in the radial menu.
 */
private class CornerBracketDrawable(
    color: Int,
    private val strokeW: Float,
    private val sizePx: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = strokeW
        val left = b.left + inset
        val top = b.top + inset
        val right = b.right - inset
        val bottom = b.bottom - inset
        val side = (right - left)
        val arm = side * 0.34f   // length of each corner leg
        val r = side * 0.20f     // radius of the corner bend
        path.reset()
        // top-left
        path.moveTo(left, top + arm)
        path.lineTo(left, top + r)
        path.quadTo(left, top, left + r, top)
        path.lineTo(left + arm, top)
        // top-right
        path.moveTo(right - arm, top)
        path.lineTo(right - r, top)
        path.quadTo(right, top, right, top + r)
        path.lineTo(right, top + arm)
        // bottom-right
        path.moveTo(right, bottom - arm)
        path.lineTo(right, bottom - r)
        path.quadTo(right, bottom, right - r, bottom)
        path.lineTo(right - arm, bottom)
        // bottom-left
        path.moveTo(left + arm, bottom)
        path.lineTo(left + r, bottom)
        path.quadTo(left, bottom, left, bottom - r)
        path.lineTo(left, bottom - arm)
        canvas.drawPath(path, paint)
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Owns the circular floating capture button and its hold-to-open half-circle
 * mode menu (drag toward an option, release to pick).
 *
 * Robustness rules (each one covers a way the menu used to get stuck):
 * - **Never remove the button window mid-gesture.** [raise] (called when a new
 *   overlay window is added above the button) defers the remove+re-add until the
 *   finger lifts — a mid-gesture removeView orphans the touch stream, so the
 *   button never hears ACTION_UP and the menu stays on screen forever. While
 *   deferred, the *menu* window is re-raised instead (safe: the gesture lives on
 *   the button window, not the scrim), so the options stay visible above the
 *   fresh boxes.
 * - **A fresh ACTION_DOWN always starts clean**: any leftover menu is dropped.
 * - **Detach = abort**: if the button view is detached from its window for any
 *   reason while a gesture/menu is live, all gesture state resets and the menu
 *   is dismissed ([FloatingButtonView.onDetachedFromWindow]).
 * - **The scrim is a fallback dismisser**: during a live gesture the button
 *   window owns the whole touch stream and the scrim hears nothing, but if the
 *   menu is ever orphaned, the next tap anywhere lands on the scrim and closes
 *   it.
 */
class FloatingButtonController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** Plain tap: capture (or clear the showing overlay). */
        fun onButtonTap()
        /** Tap while processing: cancel pending work and restore the idle state. */
        fun onLoadingTap()
        /** A mode was picked from the hold menu. */
        fun onModeSelected(mode: Int)
        /** "Stop" was picked from the hold menu. */
        fun onStopRequested()
    }

    companion object {
        private const val TAG = OverlayService.TAG
        // How long to hold the floating button before the radial mode menu appears.
        // Short (standard long-press); a drag past touchSlop cancels it, so a brief
        // hold-in-place is enough and won't interfere with dragging the button.
        private const val MENU_HOLD_MS = 500L
    }

    private var buttonView: FloatingButtonView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var appearanceActive = false
    private var appearanceMode = OverlayService.MODE_SENTENCE_DICT

    // Gesture state (main thread only). gestureActive = finger currently down on
    // the button; menuSteering = this gesture opened the menu and now drives its
    // hover selection.
    private var gestureActive = false
    private var menuSteering = false
    private var moved = false
    // Set when [raise] was requested mid-gesture; honored when the finger lifts.
    private var pendingRaise = false

    // Radial menu state. The scrim window is passive during a live gesture (the
    // button owns the touch stream); its own touch listener only matters for the
    // orphaned-menu fallback.
    private var menuWindow: FrameLayout? = null
    private var menuOptions: List<RadialOption> = emptyList()
    private var menuCenterX = 0f  // floating-button center, in screen px
    private var menuCenterY = 0f
    private var menuActivation = 0 // dead-zone radius around center (release here = cancel)
    private var menuSelected = -1  // index into menuOptions, or -1 for none
    private var dimView: View? = null
    private var descView: TextView? = null
    private var connectorView: ConnectorLineView? = null

    val isMenuOpen: Boolean get() = menuWindow != null

    private data class MenuAction(
        val label: String,
        val color: Int,
        val desc: String,
        val useIcon: Boolean = false,
        val action: () -> Unit,
    )

    /** A placed half-circle option: its view, fan angle, tint, and on-screen center. */
    private data class RadialOption(
        val view: TextView,
        val color: Int,
        val angleRad: Double,
        val desc: String,
        val cx: Float,
        val cy: Float,
        val action: () -> Unit,
    )

    /**
     * Button view with a teardown hook: if the view is yanked out of the window
     * manager while a gesture or the menu is live (anything calling removeView
     * on it — the exact race that used to strand the menu), the rest of the
     * touch stream is lost, so treat it as a cancelled gesture and clean up.
     */
    // This is a raw WindowManager overlay with fully custom text/background/spinner
    // drawing, not a theme-inflated widget. AppCompatButton would incorrectly add
    // an AppCompat-theme requirement to the service overlay context.
    @SuppressLint("AppCompatCustomView")
    private inner class FloatingButtonView(context: Context) : Button(context) {
        var loading = false
        private var spinnerAngle = 0f
        private val spinnerBounds = RectF()
        private val spinnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 760L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                spinnerAngle = it.animatedValue as Float
                invalidate()
            }
        }

        fun setLoadingState(value: Boolean) {
            if (loading == value) return
            loading = value
            if (value) spinnerAnimator.start() else spinnerAnimator.cancel()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!loading) return
            val radius = dp(11).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            spinnerBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(spinnerBounds, spinnerAngle, 275f, false, spinnerPaint)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            spinnerAnimator.cancel()
            if (gestureActive || menuWindow != null) abortGesture()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (loading && !spinnerAnimator.isStarted) spinnerAnimator.start()
        }
    }

    private val longPressRunnable = Runnable {
        val btn = buttonView
        if (!gestureActive || btn?.isAttachedToWindow != true ||
            btn.loading || appearanceActive
        ) return@Runnable
        menuSteering = true
        buttonView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showMenu()
    }

    /** Cancels any live gesture/menu without committing anything. */
    private fun abortGesture() {
        mainHandler.removeCallbacks(longPressRunnable)
        gestureActive = false
        menuSteering = false
        dismissMenu(animated = false)
    }

    /** Adds the button window (no-op if already shown). */
    fun show() {
        if (buttonView != null) return
        val button = FloatingButtonView(context).apply { setTextColor(Color.WHITE) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dp(64), dp(64),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }

        buttonView = button
        buttonParams = params
        attachDragAndClick(button, params)
        try {
            windowManager.addView(button, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating button", e)
            buttonView = null
            buttonParams = null
        }
    }

    /** INVISIBLE while capturing (so the button isn't in the snapshot) / while the crop selector is up. */
    fun setButtonVisible(visible: Boolean) {
        val btn = buttonView ?: return
        btn.animate().cancel()
        if (visible) {
            btn.visibility = View.VISIBLE
            if (btn.alpha < 1f) btn.animate().alpha(1f).setDuration(90L).start()
        } else {
            btn.visibility = View.INVISIBLE
            btn.alpha = 1f
        }
    }

    /** Smoothly removes the button layer, then signals that a clean frame is safe. */
    fun fadeOutForCapture(onHidden: () -> Unit) {
        val btn = buttonView ?: run { onHidden(); return }
        btn.animate().cancel()
        btn.animate()
            .alpha(0f)
            .setDuration(80L)
            .withEndAction {
                if (buttonView === btn && btn.isAttachedToWindow) onHidden()
            }
            .start()
    }

    /** Keeps the button present and replaces its action glyph with a spinner. */
    fun setLoading(loading: Boolean) {
        val btn = buttonView ?: return
        btn.setLoadingState(loading)
        updateAppearance(appearanceActive, appearanceMode)
        btn.contentDescription = if (loading) "Processing Japanese text" else "Capture Japanese text"
    }

    /**
     * Re-adds the button window so it z-orders above a just-added overlay window
     * (same window type — later add wins). If a gesture is in flight the re-add
     * is DEFERRED to the gesture's end: removing the view mid-gesture kills the
     * touch stream and used to strand the radial menu on screen. Meanwhile the
     * menu window (if open) is re-raised instead so the options stay visible
     * above the new overlay — that window carries no gesture, so it's safe.
     */
    fun raise() {
        if (gestureActive) {
            pendingRaise = true
            raiseMenuWindow()
            return
        }
        reAddButton()
    }

    private fun reAddButton() {
        val btn = buttonView ?: return
        val lp = buttonParams ?: return
        runCatching { windowManager.removeView(btn) }
        runCatching { windowManager.addView(btn, lp) }
    }

    private fun raiseMenuWindow() {
        val menu = menuWindow ?: return
        val lp = menu.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { windowManager.removeView(menu) }
        runCatching { windowManager.addView(menu, lp) }
        // Detaching cancels any in-flight entrance animation — snap the options
        // (and the dim/desc layers) to their settled state so nothing is left
        // frozen half-faded or mid-flight.
        for ((i, o) in menuOptions.withIndex()) {
            val on = i == menuSelected
            o.view.animate().cancel()
            o.view.translationX = 0f
            o.view.translationY = 0f
            o.view.scaleX = if (on) 1.18f else 1f
            o.view.scaleY = if (on) 1.18f else 1f
            o.view.alpha = if (on) 1f else 0.9f
        }
        dimView?.let { it.animate().cancel(); it.alpha = 1f }
        descView?.let { it.animate().cancel(); it.alpha = if (menuSelected >= 0) 1f else 0f }
    }

    private fun performPendingRaise() {
        if (!pendingRaise) return
        pendingRaise = false
        // Next frame, not synchronously from inside this view's own touch
        // dispatch; if a new gesture starts before it runs, keep it pending.
        mainHandler.post {
            if (gestureActive) pendingRaise = true else reAddButton()
        }
    }

    /** Removes the button + menu immediately (service teardown). */
    fun destroy() {
        mainHandler.removeCallbacks(longPressRunnable)
        dismissMenu(animated = false)
        gestureActive = false
        menuSteering = false
        pendingRaise = false
        buttonView?.let { runCatching { windowManager.removeView(it) } }
        buttonView = null
        buttonParams = null
    }

    /** Restyles the button: ✕ (tap clears) when [active], else the mode's glyph. */
    fun updateAppearance(active: Boolean, mode: Int) {
        val btn = buttonView ?: return
        appearanceActive = active
        appearanceMode = mode
        // Full-screen mode uses a drawn bracket icon (no glyph); the other states
        // keep their text glyphs.
        val fullScreen = !btn.loading && !active && mode != OverlayService.MODE_CROP
        btn.text = when {
            btn.loading -> ""
            active -> "✕"
            mode == OverlayService.MODE_CROP -> "✂"
            else -> ""
        }
        btn.foreground = if (fullScreen) {
            CornerBracketDrawable(Color.WHITE, dp(2).toFloat() + 0.4f, dp(22)).also {
                btn.foregroundGravity = Gravity.CENTER
            }
        } else null
        btn.textSize = if (active) 18f else 20f
        val base = when {
            btn.loading && mode == OverlayService.MODE_CROP -> Color.argb(230, 230, 140, 40)
            btn.loading -> Color.argb(230, 40, 160, 120)
            active -> Color.argb(235, 220, 80, 60)
            mode == OverlayService.MODE_CROP -> Color.argb(230, 230, 140, 40)
            else -> Color.argb(230, 40, 160, 120)
        }
        // Soft top-lit gradient + hairline ring so the button reads as a floating
        // "lens" instead of a flat disc.
        btn.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(base, 0.30f), darken(base, 0.18f))
        ).apply {
            if (active && !btn.loading) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
            } else {
                shape = GradientDrawable.OVAL
            }
            setStroke(dp(2), Color.argb(90, 255, 255, 255))
        }
    }

    private fun attachDragAndClick(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        val touchSlop = dp(8)

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // A leftover menu from a broken gesture (its UP was never
                    // delivered) — a fresh touch always starts clean.
                    if (menuWindow != null) dismissMenu(animated = false)
                    gestureActive = true
                    menuSteering = false
                    moved = false
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    // Loading and ✕ are single-purpose states. Keep tap/drag
                    // handling intact, but never let a hold open the radial menu.
                    if (!(buttonView?.loading ?: false) && !appearanceActive) {
                        mainHandler.postDelayed(longPressRunnable, MENU_HOLD_MS)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (menuSteering) {
                        // Gesture now steers the menu, not the button.
                        updateMenuSelection(event.rawX, event.rawY)
                    } else if (abs(event.rawX - touchStartX) > touchSlop ||
                        abs(event.rawY - touchStartY) > touchSlop
                    ) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)  // a drag isn't a long-press
                        // Clamp within the screen so the button can't be lost off an edge.
                        val metrics = context.resources.displayMetrics
                        params.x = (initialX + (event.rawX - touchStartX).toInt())
                            .coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0))
                        params.y = (initialY + (event.rawY - touchStartY).toInt())
                            .coerceIn(0, (metrics.heightPixels - v.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    gestureActive = false
                    val wasSteering = menuSteering
                    menuSteering = false
                    if (wasSteering) {
                        // Commit the highlighted option (if any) and tear the menu down.
                        val sel = if (event.action == MotionEvent.ACTION_UP) menuSelected else -1
                        val opts = menuOptions
                        dismissMenu(animated = true, committedIndex = sel)
                        performPendingRaise()
                        if (sel in opts.indices) opts[sel].action()
                    } else {
                        performPendingRaise()
                        if (!moved && event.action == MotionEvent.ACTION_UP) {
                            // Plain tap (no drag, no menu) = capture/clear.
                            v.performClick()
                        } else if (moved) {
                            // End of a drag: glide to the nearer horizontal edge.
                            snapToEdge(v, params)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener {
            if (buttonView?.loading == true) listener.onLoadingTap()
            else listener.onButtonTap()
        }
    }

    /**
     * After a drag, animate the floating button to whichever horizontal screen edge
     * it's closer to (a subtle "docking" feel), keeping it fully on-screen.
     */
    private fun snapToEdge(v: View, params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - v.width).coerceAtLeast(0)
        val margin = dp(8)
        val targetX = if (params.x + v.width / 2 < metrics.widthPixels / 2) margin
        else (maxX - margin).coerceAtLeast(0)
        val startX = params.x
        if (startX == targetX) return
        val anim = ValueAnimator.ofInt(startX, targetX).apply { duration = 180 }
        anim.addUpdateListener { a ->
            if (buttonView !== v) { a.cancel(); return@addUpdateListener }
            params.x = a.animatedValue as Int
            runCatching { windowManager.updateViewLayout(v, params) }
        }
        anim.start()
    }

    // ───────────────────────── Radial menu ─────────────────────────

    /**
     * Half-circle mode-switch menu shown after holding the floating button. The
     * options (two modes + Stop) fan out on a semicircle **away from the screen
     * edge** — button on the left → options open to the right, and vice-versa, so
     * they never run off-screen. The same finger-down that opened it keeps
     * steering [updateMenuSelection], and releasing commits the highlighted
     * option (see [attachDragAndClick]). A glowing connector line runs from the
     * button to whichever option the finger is pointing at.
     */
    private fun showMenu() {
        if (menuWindow != null) return
        val btnSize = dp(64)
        // Fan out from where the button *actually* is on screen (the system can
        // inset a window away from a display cutout, so the requested params.x/y
        // aren't guaranteed to be its real position).
        val btnView = buttonView
        val btnParams = buttonParams
        val cx: Int
        val cy: Int
        if (btnView != null && btnView.isAttachedToWindow) {
            val loc = IntArray(2)
            btnView.getLocationOnScreen(loc)
            cx = loc[0] + btnView.width / 2
            cy = loc[1] + btnView.height / 2
        } else {
            cx = (btnParams?.x ?: 0) + btnSize / 2
            cy = (btnParams?.y ?: 0) + btnSize / 2
        }
        // Real (full physical) metrics — resources.displayMetrics can exclude the
        // status/navigation-bar areas on some OEMs, which skewed the option
        // clamping and made the circles bunch up near screen edges.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val actions = buildList {
            add(MenuAction("", Color.argb(235, 40, 160, 120),
                "Full Screen Mode\nTap the floating button to detect Japanese text anywhere on screen, then tap a detected text box to view a full analysis.",
                useIcon = true) { listener.onModeSelected(OverlayService.MODE_SENTENCE_DICT) })
            add(MenuAction("✂", Color.argb(235, 230, 140, 40),
                "Crop Mode\nTap the floating button, then drag a box around the area you want to scan. Only the selected area will be processed.") { listener.onModeSelected(OverlayService.MODE_CROP) })
            add(MenuAction("Stop", Color.argb(235, 220, 80, 60),
                "Stop\nClose the overlay and stop screen capture.") { listener.onStopRequested() })
        }

        val scrim = FrameLayout(context)
        // Dim layer as a child (not the scrim's own background) so it can fade
        // independently of the option circles.
        val dim = View(context).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            alpha = 0f
        }
        scrim.addView(dim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
        dim.animate().alpha(1f).setDuration(160).start()

        // Connector line (button → hovered option), drawn under the circles.
        val connector = ConnectorLineView(context, cx.toFloat(), cy.toFloat())
        scrim.addView(connector, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        // Semicircle opening away from the nearer horizontal edge.
        val faceRight = cx < screenW / 2
        val centerDeg = if (faceRight) 0.0 else 180.0
        val halfSweepDeg = 80.0   // 160° total spread
        val n = actions.size
        val startDeg = centerDeg - halfSweepDeg
        val stepDeg = if (n > 1) (2 * halfSweepDeg) / (n - 1) else 0.0

        val radius = dp(120)
        val size = dp(56)
        val margin = dp(6)
        val placed = ArrayList<RadialOption>(n)
        for ((i, opt) in actions.withIndex()) {
            val angle = Math.toRadians(startDeg + i * stepDeg)
            val ox = cx + (radius * cos(angle)).toInt()
            val oy = cy + (radius * sin(angle)).toInt()
            val view = TextView(context).apply {
                text = opt.label
                setTextColor(Color.WHITE)
                // Glyph option (✂) reads best big; the "Stop" word needs to fit
                // inside the same circle. The full-screen option draws a bracket icon.
                textSize = if (opt.label.length <= 2) 19f else 13f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = optionDrawable(opt.color, selected = false)
                if (opt.useIcon) {
                    foreground = CornerBracketDrawable(Color.WHITE, dp(2).toFloat() + 0.2f, dp(20))
                    foregroundGravity = Gravity.CENTER
                }
                elevation = dp(4).toFloat()
            }
            val leftM = (ox - size / 2).coerceIn(margin, screenW - margin - size)
            val topM = (oy - size / 2).coerceIn(margin, screenH - margin - size)
            scrim.addView(view, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = leftM
                topMargin = topM
            })
            val ocx = leftM + size / 2f
            val ocy = topM + size / 2f
            // Fan-out entrance: each circle flies from the button to its spot,
            // scaling/fading in, lightly staggered.
            view.translationX = cx - ocx
            view.translationY = cy - ocy
            view.scaleX = 0.3f
            view.scaleY = 0.3f
            view.alpha = 0f
            view.animate()
                .translationX(0f).translationY(0f)
                .scaleX(1f).scaleY(1f)
                .alpha(0.9f)
                .setStartDelay(i * 24L)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
            placed += RadialOption(view, opt.color, angle, opt.desc, ocx, ocy, opt.action)
        }

        // Hover-description label: describes the option the finger is pointing at.
        // Shown centered in the middle of the screen (so it's noticeable and never
        // hidden behind the floating button) — nudged a bit below centre when the
        // button is up top, a bit above centre when the button is down low, so the
        // button/mode glyph and this text never overlap.
        val descW = dp(280)
        val desc = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(235, 20, 20, 20))
            }
            text = "Point to a mode"
            alpha = 0f
        }
        val buttonInTopHalf = cy < screenH / 2
        val descTop = if (buttonInTopHalf) (screenH * 0.56f).toInt() else (screenH * 0.34f).toInt()
        scrim.addView(desc, FrameLayout.LayoutParams(descW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = ((screenW - descW) / 2).coerceAtLeast(margin)
            topMargin = descTop.coerceIn(margin, screenH - margin - dp(96))
        })

        // Orphan fallback: during a live gesture the button window owns the whole
        // touch stream, so this never fires; but if the menu is ever left behind
        // without a finger down (a lost gesture), the first tap anywhere closes it.
        scrim.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !gestureActive) {
                dismissMenu(animated = true)
            }
            true
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // Sized to the real metrics at the physical origin (like the crop selector
        // and box overlay) — NOT MATCH_PARENT. A MATCH_PARENT window without a
        // cutout mode gets inset below a punch-hole cutout (e.g. Redmi/Xiaomi),
        // which shifted every option circle down relative to the button and made
        // the fan look lopsided. NO_LIMITS so it shares the button's full-screen
        // coordinate space (incl. the status-bar region), otherwise the options
        // would be offset by the top inset.
        val params = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(scrim, params)
            menuWindow = scrim
            menuOptions = placed
            menuCenterX = cx.toFloat()
            menuCenterY = cy.toFloat()
            menuActivation = dp(36)
            menuSelected = -1
            dimView = dim
            descView = desc
            connectorView = connector
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add radial menu", e)
        }
    }

    /** Oval option background; gains a white ring when it's the hovered option. */
    private fun optionDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(color, 0.28f), darken(color, 0.15f))
        ).apply {
            shape = GradientDrawable.OVAL
            if (selected) setStroke(dp(3), Color.WHITE)
            else setStroke(dp(1), Color.argb(70, 255, 255, 255))
        }

    /**
     * Hover-select while the menu is open: pick the option whose fan direction is
     * closest to the finger (so you only have to point roughly toward it), unless
     * the finger is still inside the center dead-zone (→ no selection / cancel).
     */
    private fun updateMenuSelection(rawX: Float, rawY: Float) {
        val opts = menuOptions
        if (opts.isEmpty()) return
        val dx = (rawX - menuCenterX).toDouble()
        val dy = (rawY - menuCenterY).toDouble()
        val sel = if (hypot(dx, dy) < menuActivation) {
            -1
        } else {
            val touchAngle = atan2(dy, dx)
            var best = -1
            var bestDiff = Double.MAX_VALUE
            for ((i, o) in opts.withIndex()) {
                // Shortest angular distance, correct for any angle ranges: normalize
                // the raw delta into [0, 2π) first, then fold into [0, π]. (A single
                // `2π - d` broke when the raw delta exceeded 2π — e.g. right-edge
                // options at 100°–260° vs atan2's −180°..180° — producing a negative
                // "distance" that stole the selection.)
                var d = ((touchAngle - o.angleRad) % (2 * PI) + 2 * PI) % (2 * PI)
                if (d > PI) d = 2 * PI - d
                if (d < bestDiff) { bestDiff = d; best = i }
            }
            best
        }
        if (sel == menuSelected) return
        menuSelected = sel
        for ((i, o) in opts.withIndex()) {
            val on = i == sel
            o.view.background = optionDrawable(o.color, on)
            // setStartDelay(0): ViewPropertyAnimator keeps the last start delay,
            // and the entrance animation staggered these views.
            o.view.animate().setStartDelay(0)
                .scaleX(if (on) 1.18f else 1f).scaleY(if (on) 1.18f else 1f)
                .alpha(if (on) 1f else 0.9f)
                .setDuration(110).setInterpolator(DecelerateInterpolator())
                .start()
        }
        descView?.let { dv ->
            if (sel in opts.indices) {
                dv.text = opts[sel].desc
                dv.animate().alpha(1f).setDuration(90).start()
            } else {
                dv.animate().alpha(0f).setDuration(90).start()
            }
        }
        connectorView?.let { cv ->
            if (sel in opts.indices) cv.pointTo(opts[sel].cx, opts[sel].cy, opts[sel].color)
            else cv.hide()
        }
        if (sel >= 0) opts[sel].view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /**
     * Tears the menu down. [animated] plays a quick fan-in (the committed option,
     * if any, pops instead) and removes the window slightly later; the dying
     * scrim is made NOT_TOUCHABLE immediately so it can't eat taps meanwhile.
     * State fields are cleared synchronously either way, so the controller is
     * ready for a new gesture the moment this returns.
     */
    private fun dismissMenu(animated: Boolean, committedIndex: Int = -1) {
        val scrim = menuWindow
        menuWindow = null
        val opts = menuOptions
        menuOptions = emptyList()
        val dim = dimView
        dimView = null
        val desc = descView
        descView = null
        val connector = connectorView
        connectorView = null
        menuSelected = -1
        if (scrim == null) return
        if (!animated) {
            runCatching { windowManager.removeView(scrim) }
            return
        }
        (scrim.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(scrim, lp) }
        }
        for ((i, o) in opts.withIndex()) {
            if (i == committedIndex) {
                // The chosen option pops outward as release feedback.
                o.view.animate().setStartDelay(0)
                    .scaleX(1.45f).scaleY(1.45f).alpha(0f)
                    .setDuration(180).setInterpolator(AccelerateInterpolator())
                    .start()
            } else {
                o.view.animate().setStartDelay(0)
                    .translationX(menuCenterX - o.cx).translationY(menuCenterY - o.cy)
                    .scaleX(0.3f).scaleY(0.3f).alpha(0f)
                    .setDuration(150).setInterpolator(AccelerateInterpolator())
                    .start()
            }
        }
        dim?.animate()?.alpha(0f)?.setDuration(180)?.start()
        desc?.animate()?.setStartDelay(0)?.alpha(0f)?.setDuration(120)?.start()
        connector?.hide()
        mainHandler.postDelayed({ runCatching { windowManager.removeView(scrim) } }, 210)
    }

    /**
     * The animated line between the button and the hovered option: a soft glow in
     * the option's tint under a bright core. It grows out of the button on first
     * hover, swings between options, and fades when the finger returns to the
     * dead-zone. Trimmed at both ends so it meets the circles' rims instead of
     * crossing them.
     */
    private inner class ConnectorLineView(
        context: Context,
        private val originX: Float,
        private val originY: Float,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 6f
            strokeCap = Paint.Cap.ROUND
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 2.2f
            strokeCap = Paint.Cap.ROUND
        }
        private var endX = originX
        private var endY = originY
        private var lineAlpha = 0f
        private var lineColor = Color.WHITE
        private var anim: ValueAnimator? = null

        fun pointTo(tx: Float, ty: Float, color: Int) {
            anim?.cancel()
            lineColor = color
            // Grow out from the button when hidden; otherwise swing from where
            // it currently points.
            val fromX = if (lineAlpha <= 0.05f) originX else endX
            val fromY = if (lineAlpha <= 0.05f) originY else endY
            val fromA = lineAlpha
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 140
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    endX = fromX + (tx - fromX) * f
                    endY = fromY + (ty - fromY) * f
                    lineAlpha = fromA + (1f - fromA) * f
                    invalidate()
                }
                start()
            }
        }

        fun hide() {
            anim?.cancel()
            val fromA = lineAlpha
            if (fromA <= 0f) return
            anim = ValueAnimator.ofFloat(fromA, 0f).apply {
                duration = 120
                addUpdateListener {
                    lineAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            if (lineAlpha <= 0f) return
            val dx = endX - originX
            val dy = endY - originY
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            // Run from the button's rim to the option's rim, not across the circles.
            val startTrim = density * 36f  // button radius (32dp) + gap
            val endTrim = density * 30f    // option radius (28dp) + gap
            if (len <= startTrim + endTrim + density * 2f) return
            val ux = dx / len
            val uy = dy / len
            val sx = originX + ux * startTrim
            val sy = originY + uy * startTrim
            val ex = endX - ux * endTrim
            val ey = endY - uy * endTrim
            glowPaint.color = lineColor
            glowPaint.alpha = (70 * lineAlpha).toInt()
            corePaint.color = Color.WHITE
            corePaint.alpha = (220 * lineAlpha).toInt()
            canvas.drawLine(sx, sy, ex, ey, glowPaint)
            canvas.drawLine(sx, sy, ex, ey, corePaint)
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
