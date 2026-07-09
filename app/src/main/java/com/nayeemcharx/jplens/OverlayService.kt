package com.nayeemcharx.jplens

import android.animation.ValueAnimator
import android.app.Notification
import android.content.ClipData
import android.content.ClipboardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class OverlayService : Service() {

    companion object {
        const val TAG = "JpLens"
        const val ACTION_START = "com.nayeemcharx.jplens.START"
        const val ACTION_STOP = "com.nayeemcharx.jplens.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MODE = "mode"

        // (0 was word/morpheme mode, 1 the LLM sentence mode, 3 the translate-only
        // sentence mode — all removed; their integer ids are unused.)
        const val MODE_SENTENCE_DICT = 2
        // Crop mode: same sentence pipeline, but the user first drags a rectangle
        // over a frozen snapshot and only that region is sent to OCR.
        const val MODE_CROP = 4

        const val PREFS_NAME = "jp_lens"
        // AnkiDroid deck name the "+" button adds cards to (set on the home screen).
        const val PREF_ANKI_DECK = "anki_deck_name"
        // Last capture mode chosen (start button resumes it; radial menu updates it).
        const val PREF_LAST_MODE = "last_mode"
        // Sentence-popup section toggles (set on the home screen; all default on).
        const val PREF_SHOW_READING = "show_reading"
        const val PREF_SHOW_TRANSLATION = "show_translation"
        const val PREF_SHOW_DICTIONARY = "show_dictionary"

        // How long to hold the floating button before the radial mode menu appears.
        // Short (standard long-press); a drag past touchSlop cancels it, so a brief
        // hold-in-place is enough and won't interfere with dragging the button.
        private const val MENU_HOLD_MS = 500L

        private const val CHANNEL_ID = "jp_lens_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    // Half-circle radial mode-switch menu (hold the floating button, then drag
    // toward an option and release). The menu window is passive (NOT_TOUCHABLE);
    // the floating button's own touch gesture drives hover + selection.
    private var radialMenu: View? = null
    private var radialOptions: List<RadialOption> = emptyList()
    private var radialCenterX = 0   // floating-button center, in screen px
    private var radialCenterY = 0
    private var radialActivation = 0 // dead-zone radius around center (release here = cancel)
    private var radialSelected = -1  // index into radialOptions, or -1 for none

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val mainHandler by lazy { Handler(mainLooper) }
    @Volatile private var isProcessing = false

    // Frame-change watcher: while overlays are showing, periodically sample the
    // captured screen and drop the (now-stale) overlays when the underlying content
    // changes a lot — e.g. the user switches app/window while sharing the whole
    // screen. Runs on captureHandler; baseline is a coarse grayscale signature.
    @Volatile private var changeWatchActive = false
    private var frameBaseline: IntArray? = null
    private val changeWatchRunnable = Runnable { sampleFrameForChange() }
    // Grid size for the signature (GRID×GRID cells) and per-cell change threshold.
    private val changeGrid = 12
    private val changeThreshold = 48

    private data class PopupHolder(val view: View, val translationView: TextView)
    private var popup: PopupHolder? = null
    // Set by the analysis popup's drag handler; suppresses auto-reposition so a
    // user-positioned popup doesn't jump when its content fills in.
    private var userMovedPopup = false
    // Popup requests load their data BEFORE the popup is shown; this token (main
    // thread only) lets a newer tap — or a dismissed/cleared overlay — supersede
    // an older lookup that's still in flight, so a stale popup can't appear.
    private var popupRequestSeq = 0

    // Sentence-mode overlays (MODE_SENTENCE_DICT / MODE_CROP).
    private data class SentenceBox(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val sentenceId: Int,    // shared across all box pieces of the same sentence
        val fullText: String,   // full sentence text (may span multiple line-pieces)
    )
    private data class RenderedSentenceBox(val view: View, val box: SentenceBox)
    private val renderedSentenceBoxes = mutableListOf<RenderedSentenceBox>()

    private var mode: Int = MODE_SENTENCE_DICT

    // Fullscreen crop-selection overlay (MODE_CROP): shows the frozen snapshot;
    // the user drags a rectangle and only that region is OCR'd.
    private var cropSelector: View? = null

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        startForegroundCompat()

        // A restart while a crop selection was pending would leave the button
        // hidden and isProcessing stuck — drop the stale selector first.
        cancelCropSelector()

        // A fresh capture session always starts in sentence (文) mode, regardless
        // of the last-used mode; the radial menu switches live after.
        mode = MODE_SENTENCE_DICT
        persistMode()
        Log.i(TAG, "Starting mode=$mode (2=sentence, 4=crop)")

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            Log.e(TAG, "Missing MediaProjection result data")
            stopSelf()
            return
        }

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data).also {
            it?.registerCallback(projectionCallback, Handler(mainLooper))
        }

        captureThread = HandlerThread("JpLensCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        // Warm the Sudachi dictionary off the main thread so the first OCR tap is snappy.
        captureHandler?.post {
            val t0 = System.currentTimeMillis()
            JapaneseTokenizer.warmUp(this)
            Log.i(TAG, "Sudachi warm-up: ${System.currentTimeMillis() - t0} ms (available=${JapaneseTokenizer.isAvailable()})")
        }
        // Both modes use the offline JMdict SQLite asset (word-by-word section)
        // and the offline FuguMT translator (Translation section), so warm both
        // up front.
        captureHandler?.post {
            val t0 = System.currentTimeMillis()
            JmDict.warmUp(this)
            Log.i(TAG, "JMdict warm-up: ${System.currentTimeMillis() - t0} ms (available=${JmDict.isAvailable()})")
            val t1 = System.currentTimeMillis()
            Translator.warmUp(this)
            Log.i(TAG, "FuguMT warm-up: ${System.currentTimeMillis() - t1} ms (available=${Translator.isAvailable()})")
        }

        setupVirtualDisplay()
        showFloatingButton()
    }

    /**
     * Android 14+ allows only one createVirtualDisplay() per MediaProjection instance.
     * For rotation/size changes we resize the existing VirtualDisplay and swap to a
     * freshly sized ImageReader instead of recreating the display.
     */
    private fun setupVirtualDisplay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val existing = virtualDisplay
        if (existing == null) {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "JpLensCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                captureHandler
            )
        } else {
            existing.resize(width, height, density)
            existing.surface = newReader.surface
        }

        val oldReader = imageReader
        imageReader = newReader
        oldReader?.close()
        Log.i(TAG, "VirtualDisplay sized ${width}x${height} @$density dpi")
    }

    private fun showFloatingButton() {
        if (floatingView != null) return

        val button = Button(this).apply {
            setTextColor(Color.WHITE)
        }
        floatingView = button
        updateOcrButtonAppearance()

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

        attachDragAndClick(button, params)
        windowManager.addView(button, params)
    }

    private fun updateOcrButtonAppearance() {
        val btn = floatingView as? Button ?: return
        // "Active" (tap clears) when boxes are showing, or when a crop-mode popup
        // is open without boxes.
        val active = renderedSentenceBoxes.isNotEmpty() || popup != null
        btn.text = when {
            active -> "✕"
            mode == MODE_CROP -> "✂"
            else -> "文"
        }
        btn.background = GradientDrawable().apply {
            if (active) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(230, 220, 80, 60))
            } else {
                shape = GradientDrawable.OVAL
                setColor(
                    when (mode) {
                        MODE_CROP -> Color.argb(220, 230, 140, 40)
                        else -> Color.argb(220, 40, 160, 120)
                    }
                )
            }
        }
    }

    private fun attachDragAndClick(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var moved = false
        var menuOpen = false
        var v0: View? = null  // the touched view, captured for haptics in [longPress]
        val touchSlop = dp(8)
        // Holding the button (without first dragging) opens the half-circle menu.
        // From then on the *same* gesture drives hover-selection — release over an
        // option to pick it, release in the center dead-zone to cancel.
        val longPress = Runnable {
            menuOpen = true
            v0?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showRadialMenu(params)
        }

        view.setOnTouchListener { v, event ->
            v0 = v
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    menuOpen = false
                    mainHandler.postDelayed(longPress, MENU_HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (menuOpen) {
                        // Gesture now steers the menu, not the button.
                        updateRadialSelection(event.rawX, event.rawY)
                    } else if (abs(event.rawX - touchStartX) > touchSlop ||
                        abs(event.rawY - touchStartY) > touchSlop
                    ) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)  // a drag isn't a long-press
                        // Clamp within the screen so the button can't be lost off an edge.
                        val metrics = resources.displayMetrics
                        params.x = (initialX + (event.rawX - touchStartX).toInt())
                            .coerceIn(0, (metrics.widthPixels - v.width).coerceAtLeast(0))
                        params.y = (initialY + (event.rawY - touchStartY).toInt())
                            .coerceIn(0, (metrics.heightPixels - v.height).coerceAtLeast(0))
                        windowManager.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    if (menuOpen) {
                        // Commit the highlighted option (if any) and tear the menu down.
                        val sel = radialSelected
                        val opts = radialOptions
                        dismissRadialMenu()
                        if (event.action == MotionEvent.ACTION_UP && sel in opts.indices)
                            opts[sel].action()
                    } else if (!moved) {
                        // Plain tap (no drag, no menu) = capture/clear.
                        v.performClick()
                    } else {
                        // End of a drag: glide to the nearer horizontal edge.
                        snapToEdge(v, params)
                    }
                    menuOpen = false
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { captureAndRecognize() }
    }

    /**
     * After a drag, animate the floating button to whichever horizontal screen edge
     * it's closer to (a subtle "docking" feel), keeping it fully on-screen.
     */
    private fun snapToEdge(v: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - v.width).coerceAtLeast(0)
        val margin = dp(8)
        val targetX = if (params.x + v.width / 2 < metrics.widthPixels / 2) margin
        else (maxX - margin).coerceAtLeast(0)
        val startX = params.x
        if (startX == targetX) return
        val anim = ValueAnimator.ofInt(startX, targetX).apply { duration = 180 }
        anim.addUpdateListener { a ->
            if (floatingView !== v) { a.cancel(); return@addUpdateListener }
            params.x = a.animatedValue as Int
            runCatching { windowManager.updateViewLayout(v, params) }
        }
        anim.start()
    }

    private fun persistMode() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(PREF_LAST_MODE, mode).apply()
    }

    /**
     * Switches capture mode live without reopening the app. The MediaProjection is
     * already granted, so this just swaps the `mode` field, drops stale overlays, and
     * warms the offline dictionary + translator.
     */
    private fun setMode(newMode: Int) {
        if (newMode == mode) return
        clearSentenceBoxes()
        mode = newMode
        persistMode()
        // Both modes look words up in JMdict and use the offline FuguMT translator
        // for the popup sections.
        captureHandler?.post {
            JmDict.warmUp(this)
            Translator.warmUp(this)
        }
        updateOcrButtonAppearance()
        val name = if (newMode == MODE_CROP) "crop" else "sentence"
        Toast.makeText(this, "Mode: $name", Toast.LENGTH_SHORT).show()
    }

    private data class MenuOption(
        val label: String,
        val color: Int,
        val desc: String,
        val action: () -> Unit,
    )

    /** A placed half-circle option: its view, fan angle, and tint, for hover styling. */
    private data class RadialOption(
        val view: TextView,
        val color: Int,
        val angleRad: Double,
        val desc: String,
        val action: () -> Unit,
    )

    // Hover-description label shown while the radial menu is open (task 4).
    private var radialDescView: TextView? = null

    /**
     * Half-circle mode-switch menu shown after holding the floating button. The
     * options (two modes + Stop) fan out on a semicircle **away from the screen
     * edge** — button on the left → options open to the right, and vice-versa, so
     * they never run off-screen. The window itself is passive (`NOT_TOUCHABLE`);
     * the same finger-down that opened it keeps steering [updateRadialSelection],
     * and releasing commits the highlighted option (see [attachDragAndClick]).
     */
    private fun showRadialMenu(btnParams: WindowManager.LayoutParams) {
        if (radialMenu != null) return
        val btnSize = dp(64)
        val cx = btnParams.x + btnSize / 2
        val cy = btnParams.y + btnSize / 2
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val options = buildList {
            add(MenuOption("文", Color.argb(235, 40, 160, 120),
                "Sentence mode\nClick on the floating button to detect Japanese sentences, then click on the boxes to get the full analysis.") { setMode(MODE_SENTENCE_DICT) })
            add(MenuOption("✂", Color.argb(235, 230, 140, 40),
                "Crop mode\nClick on the floating button, then drag a box over the area you want — only that area is scanned for Japanese text.") { setMode(MODE_CROP) })
            add(MenuOption("Stop", Color.argb(235, 220, 80, 60),
                "Stop\nClose the overlay and stop capturing.") { stopSelf() })
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(110, 0, 0, 0))
        }

        // Semicircle opening away from the nearer horizontal edge.
        val faceRight = cx < screenW / 2
        val centerDeg = if (faceRight) 0.0 else 180.0
        val halfSweepDeg = 80.0   // 160° total spread
        val n = options.size
        val startDeg = centerDeg - halfSweepDeg
        val stepDeg = if (n > 1) (2 * halfSweepDeg) / (n - 1) else 0.0

        val radius = dp(120)
        val size = dp(56)
        val margin = dp(6)
        val placed = ArrayList<RadialOption>(n)
        for ((i, opt) in options.withIndex()) {
            val angle = Math.toRadians(startDeg + i * stepDeg)
            val ox = cx + (radius * cos(angle)).toInt()
            val oy = cy + (radius * sin(angle)).toInt()
            val view = TextView(this).apply {
                text = opt.label
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                background = optionDrawable(opt.color, selected = false)
                elevation = dp(4).toFloat()
                alpha = 0.9f
            }
            scrim.addView(view, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = (ox - size / 2).coerceIn(margin, screenW - margin - size)
                topMargin = (oy - size / 2).coerceIn(margin, screenH - margin - size)
            })
            placed += RadialOption(view, opt.color, angle, opt.desc, opt.action)
        }

        // Hover-description label: describes the option the finger is pointing at.
        // Shown centered in the middle of the screen (so it's noticeable and never
        // hidden behind the floating button) — nudged a bit below centre when the
        // button is up top, a bit above centre when the button is down low, so the
        // button/mode glyph and this text never overlap.
        val descW = dp(280)
        val desc = TextView(this).apply {
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
        radialDescView = desc

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // NOT_TOUCHABLE — the floating button owns the gesture and drives
            // selection; the menu is purely visual. NO_LIMITS so it shares the
            // button's full-screen coordinate space (incl. the status-bar region),
            // otherwise the options would be offset by the top inset.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            windowManager.addView(scrim, params)
            radialMenu = scrim
            radialOptions = placed
            radialCenterX = cx
            radialCenterY = cy
            radialActivation = dp(36)
            radialSelected = -1
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add radial menu", e)
        }
    }

    /** Oval option background; gains a white ring when it's the hovered option. */
    private fun optionDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) setStroke(dp(3), Color.WHITE)
        }

    /**
     * Hover-select while the menu is open: pick the option whose fan direction is
     * closest to the finger (so you only have to point roughly toward it), unless
     * the finger is still inside the center dead-zone (→ no selection / cancel).
     */
    private fun updateRadialSelection(rawX: Float, rawY: Float) {
        val opts = radialOptions
        if (opts.isEmpty()) return
        val dx = (rawX - radialCenterX).toDouble()
        val dy = (rawY - radialCenterY).toDouble()
        val sel = if (hypot(dx, dy) < radialActivation) {
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
        if (sel == radialSelected) return
        radialSelected = sel
        for ((i, o) in opts.withIndex()) {
            val on = i == sel
            o.view.background = optionDrawable(o.color, on)
            o.view.alpha = if (on) 1f else 0.9f
            o.view.animate().scaleX(if (on) 1.2f else 1f).scaleY(if (on) 1.2f else 1f)
                .setDuration(90).start()
        }
        radialDescView?.let { dv ->
            if (sel in opts.indices) {
                dv.text = opts[sel].desc
                dv.animate().alpha(1f).setDuration(90).start()
            } else {
                dv.animate().alpha(0f).setDuration(90).start()
            }
        }
        if (sel >= 0) opts[sel].view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun dismissRadialMenu() {
        radialMenu?.let { runCatching { windowManager.removeView(it) } }
        radialMenu = null
        radialOptions = emptyList()
        radialDescView = null
        radialSelected = -1
    }

    /** Begin watching for a big screen-content change (app/window switch). */
    private fun startChangeWatch() {
        changeWatchActive = true
        frameBaseline = null
        captureHandler?.removeCallbacks(changeWatchRunnable)
        // Delay the first sample so our freshly-drawn boxes are composited into the
        // mirror before we snapshot the baseline (otherwise the baseline lacks them
        // and their appearance would look like a "change").
        captureHandler?.postDelayed(changeWatchRunnable, 700)
    }

    private fun stopChangeWatch() {
        changeWatchActive = false
        frameBaseline = null
        captureHandler?.removeCallbacks(changeWatchRunnable)
    }

    private fun sampleFrameForChange() {
        if (!changeWatchActive) return
        // Don't sample while capturing, or while a popup / radial menu / crop
        // selector is on screen (those overlays change the frame themselves and
        // would cause a false clear).
        if (isProcessing || popup != null || radialMenu != null || cropSelector != null) {
            captureHandler?.postDelayed(changeWatchRunnable, 500)
            return
        }
        var image: Image? = null
        try {
            image = imageReader?.acquireLatestImage()
            if (image != null) {
                val sig = frameSignature(image)
                val base = frameBaseline
                if (base == null) {
                    frameBaseline = sig
                } else if (signatureChanged(base, sig)) {
                    Log.i(TAG, "Screen content changed — clearing overlays")
                    mainHandler.post { clearSentenceBoxes() }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame-change sample failed", e)
        } finally {
            image?.close()
        }
        if (changeWatchActive) captureHandler?.postDelayed(changeWatchRunnable, 500)
    }

    /** Coarse grayscale signature: mean luma over a changeGrid×changeGrid grid. */
    private fun frameSignature(image: Image): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val g = changeGrid
        val out = IntArray(g * g)
        for (gy in 0 until g) {
            val py = ((gy + 0.5f) / g * h).toInt().coerceIn(0, h - 1)
            for (gx in 0 until g) {
                val px = ((gx + 0.5f) / g * w).toInt().coerceIn(0, w - 1)
                val idx = py * rowStride + px * pixelStride
                val r = buffer.get(idx).toInt() and 0xFF
                val gg = buffer.get(idx + 1).toInt() and 0xFF
                val b = buffer.get(idx + 2).toInt() and 0xFF
                out[gy * g + gx] = (r * 30 + gg * 59 + b * 11) / 100
            }
        }
        return out
    }

    /** True when enough grid cells differ enough — a substantial content change. */
    private fun signatureChanged(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return true
        var changedCells = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > changeThreshold) changedCells++
        // Require ~35% of the screen to change so scrolling a line doesn't nuke boxes,
        // but an app/window switch (near-total change) does.
        return changedCells > a.size * 35 / 100
    }

    private fun captureAndRecognize() {
        // Toggle: if any overlay is showing, just clear it. (A crop-mode popup has
        // no boxes, so check it separately — it must also be gone before the next
        // snapshot, or it would be captured into it.)
        if (renderedSentenceBoxes.isNotEmpty()) {
            clearSentenceBoxes()
            return
        }
        if (popup != null) {
            dismissPopup()
            return
        }
        if (isProcessing || cropSelector != null) return
        val reader = imageReader ?: return
        // A new capture supersedes any popup lookup still in flight — otherwise a
        // stale popup could surface on top of the new snapshot/selector.
        popupRequestSeq++
        isProcessing = true
        // Hide the button so it isn't captured, then capture next frame.
        floatingView?.visibility = View.INVISIBLE

        captureHandler?.postDelayed({
            var image: Image? = null
            // In crop mode the snapshot is handed to the crop selector, which keeps
            // the button hidden and isProcessing set until the user commits/cancels;
            // otherwise OCR takes over and resets isProcessing from its listeners.
            var handedOff = false
            try {
                image = reader.acquireLatestImage()
                if (image == null) {
                    Log.w(TAG, "No image available")
                } else {
                    val bitmap = imageToBitmap(image)
                    if (mode == MODE_CROP) {
                        handedOff = true
                        mainHandler.post { showCropSelector(bitmap) }
                    } else {
                        runOcr(bitmap)
                        handedOff = true
                        mainHandler.post { floatingView?.visibility = View.VISIBLE }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
            } finally {
                image?.close()
                if (!handedOff) {
                    isProcessing = false
                    mainHandler.post { floatingView?.visibility = View.VISIBLE }
                }
            }
        }, 120)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        // Crop off the padding columns.
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    /** Sentence mode: OCR the full screen and overlay clickable sentence boxes. */
    private fun runOcr(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                Log.i(TAG, "── OCR result ──")
                if (result.text.isBlank()) {
                    Log.i(TAG, "(no text)")
                    Log.i(TAG, "────────────────")
                    Toast.makeText(this, "No Japanese text found", Toast.LENGTH_SHORT).show()
                    isProcessing = false
                    return@addOnSuccessListener
                }
                for (block in result.textBlocks) {
                    Log.i(TAG, "[block] ${block.text.replace("\n", " / ")}")
                }
                Log.i(TAG, "[full]\n${result.text}")
                captureHandler?.post {
                    val sboxes = computeSentenceBoxes(result)
                    Log.i(TAG, "Sentence boxes: ${sboxes.size}")
                    mainHandler.post {
                        if (sboxes.isEmpty()) {
                            Toast.makeText(this, "No Japanese text found", Toast.LENGTH_SHORT).show()
                        }
                        renderSentenceBoxes(sboxes)
                        isProcessing = false
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                isProcessing = false
            }
    }

    // ───────────────────────── Crop mode ─────────────────────────

    /**
     * Crop mode: OCR the cropped region and — instead of rendering boxes — open
     * the analysis popup directly with **all** the Japanese text found in the
     * crop, assembled in reading order (vertical blocks read right-to-left,
     * columns top-to-bottom). Blocks with no Japanese at all are dropped, but a
     * Japanese block keeps its digits/Latin fragments intact.
     */
    private fun runCropOcr(bitmap: Bitmap, cropRect: android.graphics.Rect) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = assembleCropText(result)
                Log.i(TAG, "Crop OCR text → \"$text\"")
                isProcessing = false
                if (text.isEmpty()) {
                    Toast.makeText(this, "No Japanese text found in the selection", Toast.LENGTH_SHORT).show()
                } else {
                    showDictAnalysisPopup(text, cropRect)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Crop OCR failed", e)
                isProcessing = false
            }
    }

    /**
     * Joins the OCR result into one string in reading order. Within a vertical
     * (tategaki) block, lines are sorted right-to-left; line text is rebuilt
     * from elements (see [buildLineRanges]). Blocks containing no Japanese
     * characters are skipped entirely.
     *
     * Block order: ML Kit emits blocks roughly left-to-right / top-to-bottom,
     * which scrambles multi-column vertical text that it split into several
     * blocks (a manga bubble can come out left column → right → middle). So
     * when the crop is predominantly vertical, the blocks themselves are
     * re-sorted right-to-left (ties top-to-bottom) — proper tategaki reading
     * order. Horizontal crops keep ML Kit's order.
     */
    private fun assembleCropText(result: Text): String {
        data class BlockPiece(val text: String, val box: android.graphics.Rect?, val vertical: Boolean)

        val pieces = ArrayList<BlockPiece>()
        for (block in result.textBlocks) {
            val vert = isBlockVertical(block)
            val orderedLines = if (vert) {
                block.lines.sortedByDescending { it.boundingBox?.centerX() ?: 0 }
            } else block.lines
            val blockText = StringBuilder()
            for (line in orderedLines) {
                val (lineText, _) = buildLineRanges(line, isLineVertical(line))
                blockText.append(lineText)
            }
            if (JapaneseTokenizer.containsJapanese(blockText.toString())) {
                pieces += BlockPiece(blockText.toString(), block.boundingBox, vert)
            }
        }

        val verticalMajority = pieces.count { it.vertical } * 2 > pieces.size
        val ordered = if (verticalMajority) {
            pieces.sortedWith(
                compareByDescending<BlockPiece> { it.box?.centerX() ?: 0 }
                    .thenBy { it.box?.top ?: 0 }
            )
        } else pieces

        val sb = StringBuilder()
        for (p in ordered) {
            // Space between distinct blocks (speech bubbles, panels) so their
            // edge words don't fuse; lines *within* a block join directly
            // because they're usually one wrapped sentence.
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p.text)
        }
        return sb.toString().trim()
    }

    /**
     * Fullscreen crop selector (MODE_CROP): shows the frozen [bitmap] snapshot,
     * dims it, and lets the user drag a rectangle; releasing sends only that
     * region to OCR ([runCropOcr]), which opens the analysis popup directly with
     * all the text found — no intermediate boxes. The floating button stays
     * hidden and [isProcessing] stays true until the user commits or cancels,
     * so a stray button tap can't start a second capture.
     */
    private fun showCropSelector(bitmap: Bitmap) {
        if (cropSelector != null) return

        val dimPaint = android.graphics.Paint().apply { color = Color.argb(110, 0, 0, 0) }
        val borderPaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.argb(255, 230, 140, 40)
        }

        var selecting = false
        var startX = 0f
        var startY = 0f
        var curX = 0f
        var curY = 0f

        // Dim everything except the live selection (four rects around it) — no
        // xfermode/layer tricks needed.
        val selection = object : View(this) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                if (!selecting) {
                    canvas.drawRect(0f, 0f, w, h, dimPaint)
                    return
                }
                val l = min(startX, curX)
                val r = max(startX, curX)
                val t = min(startY, curY)
                val b = max(startY, curY)
                canvas.drawRect(0f, 0f, w, t, dimPaint)
                canvas.drawRect(0f, t, l, b, dimPaint)
                canvas.drawRect(r, t, w, b, dimPaint)
                canvas.drawRect(0f, b, w, h, dimPaint)
                canvas.drawRect(l, t, r, b, borderPaint)
            }
        }

        val hint = TextView(this).apply {
            text = "Drag to select the area to scan"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(235, 20, 20, 20))
            }
        }
        val cancelBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 220, 80, 60))
            }
            contentDescription = "Cancel crop"
            setOnClickListener { cancelCropSelector() }
        }

        val frame = FrameLayout(this).apply {
            addView(android.widget.ImageView(this@OverlayService).apply {
                setImageBitmap(bitmap)
                // The bitmap is exactly screen-sized; stretch-fit keeps 1:1 pixels.
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            addView(selection, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(48)
            })
            addView(cancelBtn, FrameLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(44)
                rightMargin = dp(16)
            })
        }

        selection.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    selecting = true
                    startX = e.x
                    startY = e.y
                    curX = e.x
                    curY = e.y
                    hint.visibility = View.GONE
                    selection.invalidate()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    curX = e.x
                    curY = e.y
                    selection.invalidate()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    curX = e.x
                    curY = e.y
                    // Map view-local coords to snapshot pixels (the window normally
                    // sits at the physical origin, but add its actual offset in case
                    // the system inset it).
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    val l = (min(startX, curX).toInt() + loc[0]).coerceIn(0, bitmap.width - 1)
                    val t = (min(startY, curY).toInt() + loc[1]).coerceIn(0, bitmap.height - 1)
                    val r = (max(startX, curX).toInt() + loc[0]).coerceIn(l + 1, bitmap.width)
                    val b = (max(startY, curY).toInt() + loc[1]).coerceIn(t + 1, bitmap.height)
                    if (r - l < dp(24) || b - t < dp(24)) {
                        // Too small to be a deliberate selection — let the user retry.
                        selecting = false
                        hint.visibility = View.VISIBLE
                        selection.invalidate()
                    } else {
                        dismissCropSelector()
                        floatingView?.visibility = View.VISIBLE
                        captureHandler?.post {
                            try {
                                val cropped = Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
                                Log.i(TAG, "Crop OCR: ($l,$t) ${r - l}x${b - t}")
                                runCropOcr(cropped, android.graphics.Rect(l, t, r, b))
                            } catch (ex: Exception) {
                                Log.e(TAG, "Crop OCR failed", ex)
                                isProcessing = false
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // Sized to the real metrics at the physical origin (IN_SCREEN + NO_LIMITS)
        // so view coordinates line up 1:1 with the snapshot's OCR pixel coords.
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
            windowManager.addView(frame, params)
            cropSelector = frame
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add crop selector", e)
            isProcessing = false
            floatingView?.visibility = View.VISIBLE
        }
    }

    /** Cancels a pending crop selection: removes the overlay and re-enables capture. */
    private fun cancelCropSelector() {
        if (cropSelector == null) return
        dismissCropSelector()
        isProcessing = false
        floatingView?.visibility = View.VISIBLE
    }

    private fun dismissCropSelector() {
        cropSelector?.let { runCatching { windowManager.removeView(it) } }
        cropSelector = null
    }

    private fun isBlockVertical(block: Text.TextBlock): Boolean {
        if (block.lines.isEmpty()) return false
        var v = 0
        var h = 0
        for (line in block.lines) {
            val box = line.boundingBox ?: continue
            if (box.height() > box.width()) v++ else h++
        }
        return v > h
    }

    /**
     * Per-line vertical detection. Element-centroid drift is more reliable than
     * aspect ratio when the line has multiple elements; falls back to aspect ratio
     * for single-element lines.
     */
    private fun isLineVertical(line: Text.Line): Boolean {
        val elements = line.elements
        if (elements.size >= 2) {
            val first = elements.first().boundingBox
            val last = elements.last().boundingBox
            if (first != null && last != null) {
                val dx = abs(last.centerX() - first.centerX())
                val dy = abs(last.centerY() - first.centerY())
                if (dx != dy) return dy > dx
            }
        }
        val box = line.boundingBox ?: return false
        return box.height() > box.width() * 1.3f
    }

    private fun dismissPopup() {
        popup?.let { runCatching { windowManager.removeView(it.view) } }
        popup = null
        // Invalidate any popup lookup still in flight (e.g. overlays were cleared
        // while a tapped sentence was still translating).
        popupRequestSeq++
        // Bring the transparent sentence boxes back (hidden while the popup was up).
        setSentenceBoxesVisible(true)
        updateOcrButtonAppearance()
    }

    /**
     * Hides/shows the transparent sentence boxes while the analysis popup is
     * open, so the popup is the only overlay on screen. The box windows are kept
     * alive — this only flips view visibility, so it costs nothing (no re-OCR,
     * no re-layout) and the boxes come back instantly when the popup closes.
     */
    private fun setSentenceBoxesVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        for (rb in renderedSentenceBoxes) rb.view.visibility = v
    }

    /**
     * A small "⧉" copy icon for a popup header. On tap it copies [text] to the
     * clipboard and briefly flashes a green ✓ (with a little scale pop) before
     * reverting. Used only for the main word/sentence — not the detail sections.
     */
    private fun makeCopyIcon(text: () -> CharSequence?): TextView {
        val idleColor = Color.argb(255, 200, 220, 255)
        val icon = TextView(this).apply {
            this.text = "⧉"
            setTextColor(idleColor)
            textSize = 16f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            isClickable = true
            isFocusable = false
            contentDescription = "Copy"
        }
        icon.setOnClickListener {
            val out = text()?.toString()
            if (out.isNullOrEmpty()) return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("JP Lens", out))
            icon.text = "✓"
            icon.setTextColor(Color.argb(255, 90, 220, 120))
            icon.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                .withEndAction { icon.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
                .start()
            mainHandler.postDelayed({
                icon.text = "⧉"
                icon.setTextColor(idleColor)
            }, 1000)
        }
        return icon
    }

    // ───────────────────────── Sentence mode (dict) ─────────────────────────

    /**
     * Build one row of the Word-by-word section: word text on the left, a small
     * "+" button on the right that adds the word to AnkiDroid as a card.
     */
    private fun buildWordRow(
        entry: WordEntry,
        textMaxW: Int,
        sentence: String,
        translation: String,
        expandable: Boolean = false,
    ): View {
        // Show the surface (the text as it appears in the sentence) when we have it,
        // falling back to the lookup/word form when no surface was set.
        val display = entry.surface.ifEmpty { entry.word }
        val labelText = buildString {
            append(display)
            if (entry.reading.isNotEmpty() && entry.reading != display) {
                append(" (").append(entry.reading).append(')')
            }
            append(" — ").append(entry.meaning)
            if (entry.jlpt.isNotEmpty()) append("  [").append(entry.jlpt).append(']')
        }
        // The "+" Add-to-Anki button only appears when AnkiDroid is installed, its API
        // permission is granted, and a deck name is set (configured on the home screen).
        val showAnki = AnkiDroidHelper.isConfigured(this)
        // The label is weighted (0dp + weight 1), so it wraps inside whatever space
        // the fixed-width row leaves after the "+" and the chevron — and the chevron
        // stays pinned at the row's right edge on every row.
        val label = TextView(this).apply {
            text = labelText
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 13f
        }
        val addBtn: TextView? = if (showAnki) {
            val b = TextView(this).apply {
                text = "+"
                setTextColor(Color.argb(255, 200, 240, 200))
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(Color.argb(60, 200, 240, 200))
                    cornerRadius = dp(6).toFloat()
                }
                contentDescription = "Add to AnkiDroid"
            }
            b.setOnClickListener {
                // Card back = the full JMdict detail blob for this word.
                handleAddToAnkiDict(entry, b, sentence, translation)
            }
            b
        } else null
        // "+" sits at the left, immediately before the word, so it stays close
        // to the word regardless of how wide the popup gets.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            if (addBtn != null) addView(addBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
            addView(label, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        }

        if (!expandable) return row

        // Expandable variant (dict mode): a chevron toggles a detail panel that
        // lazily loads the full JMdict entry for this word.
        val chevron = TextView(this).apply {
            text = "▸"
            setTextColor(Color.argb(255, 150, 200, 255))
            textSize = 16f
            setPadding(dp(10), dp(2), dp(6), dp(2))
            isClickable = true
            contentDescription = "Show dictionary details"
        }
        row.addView(chevron, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(4) })

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // Indent under the word, leaving a touch of left padding.
            setPadding(dp(12), 0, 0, dp(4))
        }
        // Note: expand/collapse doesn't re-anchor the popup — it grows downward in
        // place (no sideways jumps; width is fixed). If the growth would push it
        // past the bottom edge, the popup window's layout listener clamps it back
        // on-screen (see buildAnalysisPopup).
        var loaded = false
        chevron.setOnClickListener {
            if (detail.visibility == View.VISIBLE) {
                detail.visibility = View.GONE
                chevron.text = "▸"
                return@setOnClickListener
            }
            detail.visibility = View.VISIBLE
            chevron.text = "▾"
            if (!loaded) {
                loaded = true
                detail.removeAllViews()
                detail.addView(TextView(this).apply {
                    text = "Loading…"
                    setTextColor(Color.argb(255, 160, 160, 160))
                    textSize = 12f
                })
                Thread {
                    val details = JmDict.lookupWordDetail(entry.word)
                    val kanji = kanjiInfoForWord(entry.word)
                    mainHandler.post {
                        detail.removeAllViews()
                        if (details.isEmpty() && kanji.isEmpty()) {
                            detail.addView(TextView(this).apply {
                                text = "(no dictionary entry)"
                                setTextColor(Color.argb(255, 160, 160, 160))
                                textSize = 12f
                            })
                        } else {
                            detail.addView(renderWordDetail(details, kanji, textMaxW))
                        }
                    }
                }.start()
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(detail, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    // Colors for the expandable JMdict detail panel.
    private val cAccent = Color.argb(255, 180, 220, 255)   // labels / sense numbers
    private val cBody = Color.argb(255, 235, 235, 235)     // glosses
    private val cPos = Color.argb(255, 150, 205, 150)      // part of speech
    private val cDim = Color.argb(255, 160, 160, 160)      // tags / notes / forms

    private fun SpannableStringBuilder.styled(
        text: CharSequence,
        color: Int,
        sizeRatio: Float = 1f,
        italic: Boolean = false,
        bold: Boolean = false,
    ): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sizeRatio != 1f)
            setSpan(RelativeSizeSpan(sizeRatio), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic || bold) {
            val style = when {
                italic && bold -> Typeface.BOLD_ITALIC
                italic -> Typeface.ITALIC
                else -> Typeface.BOLD
            }
            setSpan(StyleSpan(style), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return this
    }

    private val circledNumbers = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"
    private fun circled(n: Int): String =
        if (n in 1..circledNumbers.length) circledNumbers[n - 1].toString() else "($n)"

    /** Distinct kanji characters appearing in [word], each with its KANJIDIC2 info. */
    private fun kanjiInfoForWord(word: String): List<Pair<Char, JmDict.KanjiInfo>> {
        val out = ArrayList<Pair<Char, JmDict.KanjiInfo>>()
        val seen = HashSet<Char>()
        for (c in word) {
            if (!JapaneseTokenizer.containsKanji(c.toString()) || !seen.add(c)) continue
            val ki = JmDict.lookupKanji(c) ?: continue
            out += c to ki
        }
        return out
    }

    /**
     * Renders the full JMdict entry/entries for a word into a styled vertical column,
     * followed by a Kanji subsection for the kanji that appear in this word.
     */
    private fun renderWordDetail(
        details: List<JmDict.WordDetail>,
        kanji: List<Pair<Char, JmDict.KanjiInfo>>,
        textMaxW: Int,
    ): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val labelW = textMaxW - dp(12)

        if (details.isEmpty()) {
            col.addView(detailLine(
                SpannableStringBuilder().styled("(not in JMdict)", cDim, 0.9f, italic = true), labelW
            ))
        }
        for ((wi, wd) in details.withIndex()) {
            if (wi > 0) {
                // Thin separator between homograph entries.
                col.addView(View(this).apply {
                    setBackgroundColor(Color.argb(40, 255, 255, 255))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    .apply { topMargin = dp(6); bottomMargin = dp(6) })
            }

            // Writings — each on its own line: text, green ● if common, then any
            // status tags decoded (rarely-used kanji, search-only, irregular, …).
            if (wd.writings.isNotEmpty()) {
                val sb = SpannableStringBuilder()
                sb.styled("Writings", cAccent, 0.85f, bold = true)
                for (w in wd.writings) {
                    sb.append("\n")
                    sb.styled("  " + w.text, cBody)
                    if (w.common) sb.styled("  ●", cPos, 0.8f)
                    if (w.jlpt.isNotEmpty()) sb.styled("  [${w.jlpt}]", cPos, 0.8f, bold = true)
                    for (t in w.tags) sb.styled("  " + JmDict.tagLabel(t), cDim, 0.8f, italic = true)
                }
                col.addView(detailLine(sb, labelW))
            }
            // Readings — text, ● if common, status tags, and (when restricted) which
            // kanji writings the reading applies to.
            if (wd.readings.isNotEmpty()) {
                val sb = SpannableStringBuilder()
                sb.styled("Readings", cAccent, 0.85f, bold = true)
                for (r in wd.readings) {
                    sb.append("\n")
                    sb.styled("  " + r.text, cBody)
                    if (r.common) sb.styled("  ●", cPos, 0.8f)
                    if (r.jlpt.isNotEmpty()) sb.styled("  [${r.jlpt}]", cPos, 0.8f, bold = true)
                    for (t in r.tags) sb.styled("  " + JmDict.tagLabel(t), cDim, 0.8f, italic = true)
                    val ak = r.appliesToKanji.filter { it != "*" }
                    if (ak.isNotEmpty())
                        sb.styled("  → " + ak.joinToString("、"), cDim, 0.8f, italic = true)
                }
                col.addView(detailLine(sb, labelW))
            }

            for ((si, sense) in wd.senses.withIndex()) {
                val sb = SpannableStringBuilder()
                sb.styled(circled(si + 1) + " ", cAccent, bold = true)
                if (sense.partOfSpeech.isNotEmpty()) {
                    sb.styled(
                        sense.partOfSpeech.joinToString(", ") { JmDict.tagLabel(it) } + "  ",
                        cPos, 0.85f, italic = true
                    )
                }
                sb.styled(sense.glosses.joinToString("; "), cBody)

                // Inline tag chips: misc, field, dialect.
                val chips = (sense.misc + sense.fields + sense.dialects).map { JmDict.tagLabel(it) }
                if (chips.isNotEmpty()) {
                    sb.styled("  " + chips.joinToString(" ") { "[$it]" }, cDim, 0.85f, italic = true)
                }
                // Loanword origin.
                for (ls in sense.langSources) {
                    val txt = buildString {
                        append("  from ").append(ls.lang)
                        if (!ls.text.isNullOrEmpty()) append(": ").append(ls.text)
                        if (ls.wasei) append(" (wasei)")
                    }
                    sb.styled(txt, cDim, 0.85f, italic = true)
                }
                if (sense.info.isNotEmpty()) {
                    sb.append("\n")
                    sb.styled("   note: " + sense.info.joinToString("; "), cDim, 0.85f)
                }
                if (sense.related.isNotEmpty()) {
                    sb.append("\n")
                    sb.styled("   → see " + sense.related.joinToString("、"), cDim, 0.85f)
                }
                if (sense.antonyms.isNotEmpty()) {
                    sb.append("\n")
                    sb.styled("   ⇄ " + sense.antonyms.joinToString("、"), cDim, 0.85f)
                }

                col.addView(detailLine(sb, labelW, topPad = dp(1)))
            }
        }

        // Kanji used in this word (KANJIDIC2 meaning + JLPT).
        if (kanji.isNotEmpty()) {
            val sb = SpannableStringBuilder()
            sb.styled("Kanji", cAccent, 0.85f, bold = true)
            for ((ch, ki) in kanji) {
                sb.append("\n")
                sb.styled("  $ch", cBody)
                sb.styled("  " + ki.meaning, cDim, 0.9f)
                if (ki.jlpt.isNotEmpty()) sb.styled("  [${ki.jlpt}]", cPos, 0.8f)
            }
            col.addView(detailLine(sb, labelW, topPad = dp(2)))
        }
        return col
    }

    private fun detailLine(content: CharSequence, maxW: Int, topPad: Int = 0): TextView =
        TextView(this).apply {
            text = content
            textSize = 13f
            maxWidth = maxW
            setPadding(0, topPad, 0, dp(3))
        }

    /** Shared AnkiDroid guards. Returns false (after toasting / prompting) if not ready. */
    private fun ankiPreflight(): Boolean {
        if (!AnkiDroidHelper.isAnkiInstalled(this)) {
            Toast.makeText(this, "AnkiDroid is not installed", Toast.LENGTH_LONG).show()
            return false
        }
        if (!AnkiDroidHelper.hasPermission(this)) {
            // Permission can only be requested from an Activity. Launch
            // MainActivity with a flag so it prompts on resume.
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_REQUEST_ANKI_PERMISSION, true)
            }
            startActivity(intent)
            Toast.makeText(
                this,
                "Grant AnkiDroid permission in JP Lens, then tap + again.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    /** Updates the "+" button + toasts based on the add result. Call on the main thread. */
    private fun applyAnkiResult(result: AnkiDroidHelper.AddResult, btn: TextView, word: String) {
        when (result) {
            is AnkiDroidHelper.AddResult.Added -> {
                btn.text = "✓"
                btn.setTextColor(Color.argb(255, 120, 200, 120))
                Toast.makeText(this, "Added \"$word\" to Anki", Toast.LENGTH_SHORT).show()
            }
            is AnkiDroidHelper.AddResult.Duplicate -> {
                btn.text = "✓"
                btn.setTextColor(Color.argb(255, 180, 180, 180))
                Toast.makeText(this, "\"$word\" already in Anki", Toast.LENGTH_SHORT).show()
            }
            is AnkiDroidHelper.AddResult.Failed -> {
                Toast.makeText(this, "Anki: ${result.message}", Toast.LENGTH_LONG).show()
                btn.isEnabled = true
                btn.alpha = 1f
            }
        }
    }

    /**
     * Dict-mode add: front = the word (kanji form, no kana reading); back = the full
     * JMdict detail blob rendered as HTML (all senses, POS, tags, xrefs, loanword, …).
     */
    private fun handleAddToAnkiDict(
        entry: WordEntry,
        btn: TextView,
        sentence: String,
        translation: String,
    ) {
        if (!ankiPreflight()) return
        btn.isEnabled = false
        btn.alpha = 0.5f
        Thread {
            val details = JmDict.lookupWordDetail(entry.word)
            val kanji = kanjiInfoForWord(entry.word)
            val back = buildAnkiBackHtml(entry, details, kanji, sentence, translation)
            val result = AnkiDroidHelper.addCard(this, entry.word, back)
            mainHandler.post { applyAnkiResult(result, btn, entry.word) }
        }.start()
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Renders the Anki card back as HTML (Anki cards display field content through a
     * WebView). Sections: the full JMdict detail (or a summary fallback when the word
     * isn't in JMdict), the kanji used in this word, and the source sentence + its
     * translation. Colors are chosen to read on Anki's default white background.
     */
    private fun buildAnkiBackHtml(
        entry: WordEntry,
        details: List<JmDict.WordDetail>,
        kanji: List<Pair<Char, JmDict.KanjiInfo>>,
        sentence: String,
        translation: String,
    ): String {
        val accent = "#1565c0"   // headers / sense numbers
        val pos = "#2e7d32"      // part of speech / JLPT
        val dim = "#777777"      // tags / notes / forms-status / translation
        val sb = StringBuilder()
        sb.append("<div style=\"text-align:left\">")

        // JMdict detail, or a minimal fallback when the word isn't in JMdict.
        if (details.isEmpty()) {
            if (entry.reading.isNotEmpty() && entry.reading != entry.word)
                sb.append("<div>").append(htmlEscape(entry.reading)).append("</div>")
            sb.append("<div>").append(htmlEscape(entry.meaning)).append("</div>")
        } else {
            for ((wi, wd) in details.withIndex()) {
                if (wi > 0) sb.append("<hr>")
                if (wd.writings.isNotEmpty()) {
                    sb.append("<div style=\"color:$accent;font-weight:bold\">Writings</div>")
                    for (w in wd.writings) {
                        sb.append("<div>").append(htmlEscape(w.text))
                        if (w.common) sb.append(" <span style=\"color:$pos\">●</span>")
                        if (w.jlpt.isNotEmpty())
                            sb.append(" <span style=\"color:$pos;font-weight:bold\">[")
                                .append(htmlEscape(w.jlpt)).append("]</span>")
                        for (t in w.tags)
                            sb.append(" <span style=\"color:$dim;font-style:italic\">")
                                .append(htmlEscape(JmDict.tagLabel(t))).append("</span>")
                        sb.append("</div>")
                    }
                }
                if (wd.readings.isNotEmpty()) {
                    sb.append("<div style=\"color:$accent;font-weight:bold\">Readings</div>")
                    for (r in wd.readings) {
                        sb.append("<div>").append(htmlEscape(r.text))
                        if (r.common) sb.append(" <span style=\"color:$pos\">●</span>")
                        if (r.jlpt.isNotEmpty())
                            sb.append(" <span style=\"color:$pos;font-weight:bold\">[")
                                .append(htmlEscape(r.jlpt)).append("]</span>")
                        for (t in r.tags)
                            sb.append(" <span style=\"color:$dim;font-style:italic\">")
                                .append(htmlEscape(JmDict.tagLabel(t))).append("</span>")
                        val ak = r.appliesToKanji.filter { it != "*" }
                        if (ak.isNotEmpty())
                            sb.append(" <span style=\"color:$dim\">→ ")
                                .append(htmlEscape(ak.joinToString("、"))).append("</span>")
                        sb.append("</div>")
                    }
                }
                for ((si, sense) in wd.senses.withIndex()) {
                    sb.append("<div style=\"margin-top:4px\">")
                    sb.append("<span style=\"color:$accent;font-weight:bold\">")
                        .append(circled(si + 1)).append("</span> ")
                    if (sense.partOfSpeech.isNotEmpty())
                        sb.append("<span style=\"color:$pos;font-style:italic\">")
                            .append(htmlEscape(sense.partOfSpeech.joinToString(", ") { JmDict.tagLabel(it) }))
                            .append("</span> ")
                    sb.append(htmlEscape(sense.glosses.joinToString("; ")))
                    val chips = (sense.misc + sense.fields + sense.dialects).map { JmDict.tagLabel(it) }
                    if (chips.isNotEmpty())
                        sb.append(" <span style=\"color:$dim;font-style:italic\">")
                            .append(htmlEscape(chips.joinToString(" ") { "[$it]" })).append("</span>")
                    for (ls in sense.langSources) {
                        val txt = buildString {
                            append("from ").append(ls.lang)
                            if (!ls.text.isNullOrEmpty()) append(": ").append(ls.text)
                            if (ls.wasei) append(" (wasei)")
                        }
                        sb.append(" <span style=\"color:$dim;font-style:italic\">")
                            .append(htmlEscape(txt)).append("</span>")
                    }
                    if (sense.info.isNotEmpty())
                        sb.append("<div style=\"color:$dim\">note: ")
                            .append(htmlEscape(sense.info.joinToString("; "))).append("</div>")
                    if (sense.related.isNotEmpty())
                        sb.append("<div style=\"color:$dim\">→ see ")
                            .append(htmlEscape(sense.related.joinToString("、"))).append("</div>")
                    if (sense.antonyms.isNotEmpty())
                        sb.append("<div style=\"color:$dim\">⇄ ")
                            .append(htmlEscape(sense.antonyms.joinToString("、"))).append("</div>")
                    sb.append("</div>")
                }
            }
        }

        // Kanji used in this word.
        if (kanji.isNotEmpty()) {
            sb.append("<div style=\"color:$accent;font-weight:bold;margin-top:6px\">Kanji</div>")
            for ((ch, ki) in kanji) {
                sb.append("<div>").append(htmlEscape(ch.toString()))
                    .append(" — ").append(htmlEscape(ki.meaning))
                if (ki.jlpt.isNotEmpty())
                    sb.append(" <span style=\"color:$pos\">[")
                        .append(htmlEscape(ki.jlpt)).append("]</span>")
                sb.append("</div>")
            }
        }

        // Source sentence + its translation.
        if (sentence.isNotEmpty()) {
            sb.append("<div style=\"color:$accent;font-weight:bold;margin-top:6px\">Sentence</div>")
            sb.append("<div>").append(htmlEscape(sentence)).append("</div>")
            if (translation.isNotEmpty())
                sb.append("<div style=\"color:$dim\">").append(htmlEscape(translation)).append("</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }

    private data class ElementRange(val box: android.graphics.Rect, val start: Int, val end: Int)

    private fun buildLineRanges(line: Text.Line, vertical: Boolean): Pair<String, List<ElementRange>> {
        val elements = line.elements
        if (elements.isEmpty()) return "" to emptyList()
        val sorted = if (vertical) elements.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE } else elements
        val sb = StringBuilder()
        val ranges = ArrayList<ElementRange>(sorted.size)
        for (e in sorted) {
            val eBox = e.boundingBox ?: continue
            val s = sb.length
            sb.append(e.text)
            ranges += ElementRange(eBox, s, sb.length)
        }
        return sb.toString() to ranges
    }

    /** Bounding box for chars [from, to) on a line, given element ranges. */
    private fun rectForRange(
        ranges: List<ElementRange>,
        from: Int,
        to: Int,
        vertical: Boolean,
        lineBox: android.graphics.Rect?,
        lineTextLen: Int,
    ): android.graphics.Rect? {
        if (to <= from) return null
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (r in ranges) {
            val overlapStart = max(r.start, from)
            val overlapEnd = min(r.end, to)
            if (overlapEnd <= overlapStart) continue
            val eLen = (r.end - r.start).coerceAtLeast(1)
            if (vertical) {
                val charH = r.box.height().toFloat() / eLen
                val t = r.box.top + ((overlapStart - r.start) * charH).toInt()
                val b = r.box.top + ((overlapEnd - r.start) * charH).toInt()
                if (r.box.left < left) left = r.box.left
                if (r.box.right > right) right = r.box.right
                if (t < top) top = t
                if (b > bottom) bottom = b
            } else {
                val charW = r.box.width().toFloat() / eLen
                val l = r.box.left + ((overlapStart - r.start) * charW).toInt()
                val rEdge = r.box.left + ((overlapEnd - r.start) * charW).toInt()
                if (l < left) left = l
                if (rEdge > right) right = rEdge
                if (r.box.top < top) top = r.box.top
                if (r.box.bottom > bottom) bottom = r.box.bottom
            }
        }
        if (left == Int.MAX_VALUE) {
            // Fallback to line box interpolation.
            val lb = lineBox ?: return null
            return if (vertical) {
                val charH = lb.height().toFloat() / max(lineTextLen, 1)
                android.graphics.Rect(
                    lb.left,
                    lb.top + (from * charH).toInt(),
                    lb.right,
                    lb.top + (to * charH).toInt()
                )
            } else {
                val charW = lb.width().toFloat() / max(lineTextLen, 1)
                android.graphics.Rect(
                    lb.left + (from * charW).toInt(),
                    lb.top,
                    lb.left + (to * charW).toInt(),
                    lb.bottom
                )
            }
        }
        return android.graphics.Rect(left, top, right, bottom)
    }

    /**
     * Splits OCR output into sentence-level boxes. Lines are walked in reading
     * order within each block; pieces between Japanese terminators (。！？!?) get
     * grouped into one logical sentence even when split across lines. Each
     * physical line-piece becomes its own clickable rectangle; all pieces of the
     * same sentence carry the same fullText (and same sentenceId) so any click
     * opens the analysis popup for the complete sentence.
     */
    private fun computeSentenceBoxes(visionText: Text): List<SentenceBox> {
        val terminators = setOf('。', '！', '？', '!', '?', '．', '.')
        val out = mutableListOf<SentenceBox>()
        var sid = 0

        for (block in visionText.textBlocks) {
            val vert = isBlockVertical(block)
            val orderedLines = if (vert) {
                block.lines.sortedByDescending { it.boundingBox?.centerX() ?: 0 }
            } else block.lines

            data class Piece(val rect: android.graphics.Rect, val text: String)
            var pendingPieces = mutableListOf<Piece>()
            val pendingText = StringBuilder()

            fun flushSentence() {
                if (pendingPieces.isEmpty()) return
                val full = pendingText.toString().trim()
                // Only render sentences that actually contain Japanese — OCR picks up
                // plenty of Latin/digit UI text that just clutters the overlay.
                if (full.isNotEmpty() && JapaneseTokenizer.containsJapanese(full)) {
                    for (p in pendingPieces) {
                        out += SentenceBox(
                            left = p.rect.left,
                            top = p.rect.top,
                            width = max(p.rect.width(), 1),
                            height = max(p.rect.height(), 1),
                            sentenceId = sid,
                            fullText = full,
                        )
                    }
                    sid++
                }
                pendingPieces = mutableListOf()
                pendingText.setLength(0)
            }

            for (line in orderedLines) {
                val lineVert = isLineVertical(line)
                val (lineText, ranges) = buildLineRanges(line, lineVert)
                if (lineText.isEmpty()) continue
                val lineBox = line.boundingBox

                var segStart = 0
                var i = 0
                while (i < lineText.length) {
                    if (lineText[i] in terminators) {
                        val segEnd = i + 1
                        val rect = rectForRange(ranges, segStart, segEnd, lineVert, lineBox, lineText.length)
                        if (rect != null) {
                            pendingPieces += Piece(rect, lineText.substring(segStart, segEnd))
                            pendingText.append(lineText, segStart, segEnd)
                        }
                        flushSentence()
                        segStart = segEnd
                    }
                    i++
                }
                if (segStart < lineText.length) {
                    val rect = rectForRange(ranges, segStart, lineText.length, lineVert, lineBox, lineText.length)
                    if (rect != null) {
                        pendingPieces += Piece(rect, lineText.substring(segStart, lineText.length))
                        pendingText.append(lineText, segStart, lineText.length)
                    }
                }
            }
            // End of block — flush any sentence that lacked a terminator.
            flushSentence()
        }
        return out
    }

    private fun renderSentenceBoxes(boxes: List<SentenceBox>) {
        clearSentenceBoxes()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val padY = dp(3)
        for (b in boxes) {
            val view = View(this).apply {
                background = makeSentenceBoxDrawable()
                isClickable = true
                setOnClickListener { onSentenceClick(b) }
            }
            val params = WindowManager.LayoutParams(
                b.width, b.height + padY * 2,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = b.left
                y = b.top - padY
            }
            try {
                windowManager.addView(view, params)
                renderedSentenceBoxes += RenderedSentenceBox(view, b)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add sentence box view", e)
            }
        }
        Log.i(TAG, "Rendered ${renderedSentenceBoxes.size} sentence boxes")
        updateOcrButtonAppearance()
        if (renderedSentenceBoxes.isNotEmpty()) startChangeWatch()
    }

    private fun makeSentenceBoxDrawable(): GradientDrawable = GradientDrawable().apply {
        setStroke(dp(2), Color.argb(255, 140, 120, 240))
        setColor(Color.argb(48, 140, 120, 240))
        cornerRadius = dp(3).toFloat()
    }

    private fun clearSentenceBoxes() {
        stopChangeWatch()
        dismissPopup()
        for (rb in renderedSentenceBoxes) {
            runCatching { windowManager.removeView(rb.view) }
        }
        renderedSentenceBoxes.clear()
        updateOcrButtonAppearance()
    }

    private fun onSentenceClick(box: SentenceBox) {
        Log.i(TAG, "sentence tap → ${box.fullText}")
        showDictAnalysisPopup(
            box.fullText,
            android.graphics.Rect(box.left, box.top, box.left + box.width, box.top + box.height),
        )
    }

    /**
     * The analysis popup scaffold. The caller fills the section views
     * (Word-by-word list, Reading, Translation) and then calls [show] — the
     * window is only added once fully populated, so it appears in its final
     * position at its final size (no loading placeholder → no flicker or jump).
     */
    private class AnalysisPopup(
        val holder: PopupHolder,
        val container: View,
        val wordHeader: TextView,
        val wordBody: TextView,
        val wordList: LinearLayout,
        val readingHeader: TextView,
        val readingBody: TextView,
        val transHeader: TextView,
        val transBody: TextView,
        val textMaxW: Int,
        val show: () -> Unit,
    )

    /**
     * Builds the analysis popup for [anchor] but does NOT add the window —
     * populate the sections first, then call [AnalysisPopup.show].
     *
     * Layout rules that keep it glitch-free:
     * - The window is only added once fully populated ([AnalysisPopup.show]),
     *   measured at its final size and anchored in one shot — no placeholder,
     *   no post-load reposition, no flicker. While it's up the transparent
     *   sentence boxes are hidden ([setSentenceBoxesVisible]); they return when
     *   it closes.
     * - The window width is **fixed** (`min(screen − 16dp, 400dp)`) — content
     *   can never change the popup's width, so text wraps predictably and
     *   expand panels can't push it off-screen sideways.
     * - Height wraps content but is hard-capped (~65% of screen) by the section
     *   ScrollView, whose cap is computed from what the top bar + title actually
     *   used. A layout listener re-clamps the window position whenever the
     *   height changes (chevron expand/collapse), so the popup can never end up
     *   past the bottom edge — even after the user dragged it.
     * - Top bar = centered grip + ✕ at the right; the title sits on its own line
     *   below (weighted width so it wraps fully, max 4 lines ellipsized — the
     *   copy icon copies the complete text) — nothing overlaps or clips.
     * - Dragging is via the top bar or title row ONLY — a container-wide drag
     *   listener was tried and reverted (it fought the scroll/click gestures).
     */
    private fun buildAnalysisPopup(anchor: android.graphics.Rect, title: String): AnalysisPopup {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val sideMargin = dp(8)
        val popupW = min(screenW - sideMargin * 2, dp(400))
        val maxPopupH = (screenH * 0.65f).toInt()
        // Width available to content inside the container padding — labels and
        // detail panels wrap before hitting the popup edge.
        val textMaxW = popupW - dp(12) - dp(12)

        // ── Top bar: drag grip centered, ✕ at the right ──
        val gripBar = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.argb(160, 255, 255, 255))
            }
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(60, 255, 255, 255))
            }
            contentDescription = "Close"
            setOnClickListener { dismissPopup() }
        }
        val topBar = FrameLayout(this).apply {
            minimumHeight = dp(36)
            addView(gripBar, FrameLayout.LayoutParams(dp(48), dp(5)).apply {
                gravity = Gravity.CENTER
            })
            addView(closeBtn, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            })
        }

        // ── Title row: the sentence (weighted → wraps fully, never clipped) + copy ──
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(titleView, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(makeCopyIcon { title }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            setPadding(0, dp(2), 0, dp(6))
        }

        val wordHeader = TextView(this).apply {
            text = "Word-by-word"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(2))
        }
        val wordBody = TextView(this).apply {
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 13f
            maxWidth = textMaxW
        }
        // Structured per-word rows replace `wordBody` once the dictionary lookup
        // completes. Each row carries a "+ Anki" button that adds the word as a card.
        val wordList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val readingHeader = TextView(this).apply {
            text = "Reading"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        val readingBody = TextView(this).apply {
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 14f
            maxWidth = textMaxW
            visibility = View.GONE
        }
        val transHeader = TextView(this).apply {
            text = "Translation"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        val transBody = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = textMaxW
            visibility = View.GONE
        }
        val sectionsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(wordHeader)
            addView(wordBody)
            addView(wordList)
            addView(readingHeader)
            addView(readingBody)
            addView(transHeader)
            addView(transBody)
        }
        // ScrollView that caps its own height so top bar + title + sections never
        // exceed maxPopupH. Below the cap it wraps to content (the popup stays
        // small for short answers); past the cap it scrolls. The vertical
        // LinearLayout parent measures children top-to-bottom, so the bars above
        // are already measured when this runs.
        val scroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val used = topBar.measuredHeight + titleRow.measuredHeight + dp(24)
                val cap = (maxPopupH - used).coerceAtLeast(dp(80))
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            addView(sectionsCol, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(4), dp(12), dp(10))
            addView(topBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(titleRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // The window width is exact, so only the height ever needs measuring.
        fun measuredHeight(): Int {
            val wSpec = View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(maxPopupH, View.MeasureSpec.AT_MOST)
            container.measure(wSpec, hSpec)
            return container.measuredHeight
        }
        fun anchoredX(): Int = (anchor.centerX() - popupW / 2)
            .coerceIn(sideMargin, (screenW - sideMargin - popupW).coerceAtLeast(sideMargin))
        fun anchoredY(ph: Int): Int {
            var y = anchor.top - ph - dp(6)               // prefer above the anchor
            if (y < sideMargin) y = anchor.bottom + dp(6) // else below it
            y = y.coerceAtMost(screenH - sideMargin - ph) // never past the bottom
            return y.coerceAtLeast(sideMargin)
        }

        val params = WindowManager.LayoutParams(
            popupW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // Keeps the popup fully on-screen at its *current* position (after drags
        // and content growth) — moves it back just enough, without re-anchoring.
        fun clampToScreen() {
            val lp = container.layoutParams as? WindowManager.LayoutParams ?: return
            val h = if (container.height > 0) container.height else measuredHeight()
            val nx = lp.x.coerceIn(0, (screenW - popupW).coerceAtLeast(0))
            val ny = lp.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
            if (nx != lp.x || ny != lp.y) {
                lp.x = nx
                lp.y = ny
                runCatching { windowManager.updateViewLayout(container, lp) }
            }
        }

        val holder = PopupHolder(container, wordBody)

        // Adds the window — call only after the sections are populated, so the
        // popup appears once, at its final size and position. The sentence boxes
        // are hidden while it's up (dismissPopup brings them back).
        fun show() {
            dismissPopup()
            userMovedPopup = false
            val ph = measuredHeight()
            params.x = anchoredX()
            params.y = anchoredY(ph)
            try {
                windowManager.addView(container, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add analysis popup", e)
                return
            }
            setSentenceBoxesVisible(false)
            // Whatever grows the popup later (chevron expand panels), never let
            // it hang past the screen edge — this runs regardless of whether the
            // user has dragged the popup.
            container.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) mainHandler.post { clampToScreen() }
            }
            // Gentle fade-in instead of popping into existence.
            container.alpha = 0f
            container.animate().alpha(1f).setDuration(120).start()
            popup = holder
            updateOcrButtonAppearance()
        }

        // Drag-to-move via the top bar or the title row (the ✕ / copy icons are
        // clickable and consume their own touches first). Once dragged,
        // userMovedPopup is set — clampToScreen still keeps the popup on-screen
        // when it later grows.
        var dragInitialX = 0
        var dragInitialY = 0
        var dragStartRawX = 0f
        var dragStartRawY = 0f
        val dragListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialX = params.x
                    dragInitialY = params.y
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Clamp within the screen so the popup can't be dragged (and
                    // lost) off an edge.
                    val h = if (container.height > 0) container.height else measuredHeight()
                    params.x = (dragInitialX + (event.rawX - dragStartRawX).toInt())
                        .coerceIn(0, (screenW - popupW).coerceAtLeast(0))
                    params.y = (dragInitialY + (event.rawY - dragStartRawY).toInt())
                        .coerceIn(0, (screenH - h).coerceAtLeast(0))
                    userMovedPopup = true
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }
        topBar.setOnTouchListener(dragListener)
        titleRow.setOnTouchListener(dragListener)

        return AnalysisPopup(
            holder = holder,
            container = container,
            wordHeader = wordHeader,
            wordBody = wordBody,
            wordList = wordList,
            readingHeader = readingHeader,
            readingBody = readingBody,
            transHeader = transHeader,
            transBody = transBody,
            textMaxW = textMaxW,
            show = { show() },
        )
    }

    /**
     * The analysis popup (both modes: sentence-box tap and crop). Which sections
     * appear is controlled by the home-screen toggles: Dictionary = word-by-word
     * rows (morphemes glossed against JMdict, each expandable to the full JMdict
     * + KANJIDIC2 detail), Reading = the full kana reading, Translation = the
     * offline FuguMT translation. Everything runs on-device — nothing leaves the
     * device. [anchor] is the screen rect the popup positions itself around (the
     * tapped sentence box, or the crop selection).
     *
     * All lookups run BEFORE the popup is built and shown — there is no loading
     * placeholder, so the popup appears exactly once, fully populated, at its
     * final size and position (no flicker/jump when data arrives). A newer tap
     * or a dismissed overlay supersedes an in-flight lookup via [popupRequestSeq].
     */
    private fun showDictAnalysisPopup(sentence: String, anchor: android.graphics.Rect) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val showDictionary = prefs.getBoolean(PREF_SHOW_DICTIONARY, true)
        val showReading = prefs.getBoolean(PREF_SHOW_READING, true)
        val showTranslation = prefs.getBoolean(PREF_SHOW_TRANSLATION, true)

        if (!showDictionary && !showReading && !showTranslation) {
            // Nothing to fetch — show the hint immediately.
            val ui = buildAnalysisPopup(anchor, sentence)
            ui.wordHeader.visibility = View.GONE
            ui.wordBody.text =
                "All sections are off — enable Reading, Translation or Dictionary on the JP Lens home screen."
            ui.show()
            return
        }

        val requestId = ++popupRequestSeq
        Thread {
            // No-op if handleStart already warmed it; guards against a tap that
            // races the warm-up.
            JmDict.warmUp(this)
            val available = JmDict.isAvailable()

            // Word-by-word: one row per morpheme, in the exact order they appear in
            // the sentence (no dedup — the list mirrors the sentence). Each row's
            // *label* is the morpheme's surface (the text actually shown), not
            // Sudachi's normalized form, which can canonicalize a kana word into a
            // rarely-used kanji. The gloss/detail/Anki still key off a dictionary
            // lookup form (see lookupKey below).
            val entries = ArrayList<WordEntry>()
            if (showDictionary && available) {
                val morphemes = runCatching { JapaneseTokenizer.extract(sentence) }
                    .getOrDefault(emptyList())
                for (m in morphemes) {
                    val surface = m.surface
                    // Lookup key: an all-kana surface means the writer chose kana on
                    // purpose, so look the word up by its kana form — that lets
                    // JMdict's kana_pref ranking surface the kana-native entry (and its
                    // reading-only / rarely-used-kanji writings) instead of a kanji
                    // homograph. Words written with kanji look up by the dictionary form.
                    val lookupKey = if (!JapaneseTokenizer.containsKanji(surface)) {
                        when {
                            m.base.isNotEmpty() && !JapaneseTokenizer.containsKanji(m.base) -> m.base
                            m.reading.isNotEmpty() -> JapaneseTokenizer.katakanaToHiragana(m.reading)
                            else -> surface
                        }
                    } else m.base
                    // Furigana column: only when the displayed surface carries kanji.
                    val reading = if (JapaneseTokenizer.containsKanji(surface) && m.reading.isNotEmpty())
                        JapaneseTokenizer.katakanaToHiragana(m.reading) else ""
                    val info = JmDict.lookupWord(lookupKey)
                    val gloss = info?.gloss ?: "(not in dictionary)"
                    entries += WordEntry(
                        lookupKey, reading, gloss, info?.jlpt ?: "", surface = surface
                    )
                }
            }

            // Full kana reading of the whole sentence (mode-A re-tokenization, so
            // particles/auxiliaries get readings too), built from Sudachi.
            val reading = if (showReading)
                runCatching { JapaneseTokenizer.fullReadingHiragana(sentence) }.getOrDefault("")
            else ""

            // No standalone Kanji section — each word's own kanji are folded into
            // that word's expandable detail panel (see buildWordRow).
            val translation = if (showTranslation)
                runCatching { Translator.translateJaToEn(sentence) }.getOrDefault("")
            else ""

            mainHandler.post {
                // A newer tap (or a cleared/dismissed overlay) superseded this
                // lookup while it was running — drop it silently.
                if (requestId != popupRequestSeq) return@post

                val ui = buildAnalysisPopup(anchor, sentence)
                if (!showDictionary) {
                    ui.wordHeader.visibility = View.GONE
                    ui.wordBody.visibility = View.GONE
                } else if (!available) {
                    ui.wordBody.text =
                        "Dictionary not built — run scripts/build_jmdict_db.py and reinstall."
                } else if (entries.isNotEmpty()) {
                    ui.wordBody.visibility = View.GONE
                    for (w in entries) {
                        ui.wordList.addView(
                            buildWordRow(w, ui.textMaxW, sentence, translation, expandable = true)
                        )
                    }
                    ui.wordList.visibility = View.VISIBLE
                } else {
                    ui.wordBody.text = "(no words found)"
                }
                if (reading.isNotBlank() && reading != sentence) {
                    ui.readingBody.text = reading
                    ui.readingHeader.visibility = View.VISIBLE
                    ui.readingBody.visibility = View.VISIBLE
                }
                if (showTranslation) {
                    // The toggle is on, so surface a failure instead of silently
                    // hiding the section.
                    ui.transBody.text = when {
                        translation.isNotBlank() -> translation
                        Translator.isAvailable() -> "(no translation)"
                        else -> "Translation model not built."
                    }
                    ui.transHeader.visibility = View.VISIBLE
                    ui.transBody.visibility = View.VISIBLE
                }
                ui.show()
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rebuild capture surface for new orientation. Stale boxes are now wrong,
        // drop them; a pending crop selection is against the old snapshot, cancel it.
        cancelCropSelector()
        clearSentenceBoxes()
        if (mediaProjection != null) setupVirtualDisplay()
    }

    override fun onDestroy() {
        cancelCropSelector()
        clearSentenceBoxes()
        dismissRadialMenu()
        floatingView?.let { runCatching { windowManager.removeView(it) } }
        floatingView = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        recognizer.close()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JP Lens")
            .setContentText("Floating OCR is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "JP Lens Overlay",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
