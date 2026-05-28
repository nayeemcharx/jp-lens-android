package com.example.jp_lens_android

import android.app.Notification
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class OverlayService : Service() {

    companion object {
        const val TAG = "JpLens"
        const val ACTION_START = "com.example.jp_lens_android.START"
        const val ACTION_STOP = "com.example.jp_lens_android.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MODE = "mode"

        const val MODE_MORPHEME = 0
        const val MODE_SENTENCE_LLM = 1
        const val MODE_SENTENCE_DICT = 2

        const val PREFS_NAME = "jp_lens"
        const val PREF_BEDROCK_TOKEN = "aws_bearer_token_bedrock"
        // Last capture mode chosen (start button resumes it; radial menu updates it).
        const val PREF_LAST_MODE = "last_mode"

        // How long to hold the floating button before the radial mode menu appears.
        // Short (standard long-press); a drag past touchSlop cancels it, so a brief
        // hold-in-place is enough and won't interfere with dragging the button.
        private const val MENU_HOLD_MS = 500L

        private const val CHANNEL_ID = "jp_lens_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    // Full-screen radial mode-switch menu (long-press the floating button).
    private var radialMenu: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val mainHandler by lazy { Handler(mainLooper) }
    @Volatile private var isProcessing = false

    private data class PopupHolder(val view: View, val translationView: TextView)
    private var popup: PopupHolder? = null
    // Set by the LLM popup's drag handler; suppresses auto-reposition so a
    // user-positioned popup doesn't jump when the response arrives.
    private var userMovedPopup = false

    private data class MorphemeBox(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val morpheme: JapaneseTokenizer.Morpheme,
        val seq: Int,           // global reading-order sequence number
        val lineKey: Int,       // unique id for the line this morpheme came from
        val lineText: String,   // full text of that line (rebuilt from elements)
        val charStart: Int,     // char offset of surface within lineText
        val charEnd: Int,       // exclusive
    )

    private data class RenderedBox(val view: View, val box: MorphemeBox)
    private val renderedBoxes = mutableListOf<RenderedBox>()

    // Sentence-mode overlays (MODE_SENTENCE_LLM).
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

    private var mode: Int = MODE_MORPHEME

    // Range-selection state: two long-press anchors. Both null = IDLE,
    // anchor1 only = ANCHORED, both set = RANGE.
    private var anchor1: MorphemeBox? = null
    private var anchor2: MorphemeBox? = null

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

        mode = intent.getIntExtra(EXTRA_MODE, MODE_MORPHEME)
        persistMode()
        Log.i(TAG, "Starting mode=$mode (0=morpheme, 1=sentence+LLM, 2=sentence+JMdict)")

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

        // Warm the Kuromoji dictionary off the main thread so the first OCR tap is snappy.
        captureHandler?.post {
            val t0 = System.currentTimeMillis()
            JapaneseTokenizer.warmUp()
            Log.i(TAG, "Kuromoji warm-up: ${System.currentTimeMillis() - t0} ms")
        }
        // Dict mode also copies + opens the offline JMdict SQLite asset up front.
        if (mode == MODE_SENTENCE_DICT) {
            captureHandler?.post {
                val t0 = System.currentTimeMillis()
                JmDict.warmUp(this)
                Log.i(TAG, "JMdict warm-up: ${System.currentTimeMillis() - t0} ms (available=${JmDict.isAvailable()})")
            }
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
        val active = renderedBoxes.isNotEmpty() || renderedSentenceBoxes.isNotEmpty()
        btn.text = when {
            active -> "✕"
            mode == MODE_SENTENCE_LLM -> "LLM"
            mode == MODE_SENTENCE_DICT -> "辞書"
            else -> "OCR"
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
                        MODE_SENTENCE_LLM -> Color.argb(220, 140, 60, 200)
                        MODE_SENTENCE_DICT -> Color.argb(220, 40, 160, 120)
                        else -> Color.argb(220, 30, 100, 220)
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
        var longPressFired = false
        val touchSlop = dp(8)
        // Holding the button (without dragging) opens the radial mode menu.
        val longPress = Runnable {
            longPressFired = true
            showRadialMenu(params)
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    moved = false
                    longPressFired = false
                    mainHandler.postDelayed(longPress, MENU_HOLD_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - touchStartX) > touchSlop ||
                        abs(event.rawY - touchStartY) > touchSlop
                    ) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)  // a drag isn't a long-press
                        params.x = initialX + (event.rawX - touchStartX).toInt()
                        params.y = initialY + (event.rawY - touchStartY).toInt()
                        windowManager.updateViewLayout(v, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    // Plain tap (no drag, no menu) = capture/clear.
                    if (!moved && !longPressFired) v.performClick()
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { captureAndRecognize() }
    }

    private fun persistMode() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(PREF_LAST_MODE, mode).apply()
    }

    /**
     * Switches capture mode live without reopening the app. The MediaProjection is
     * already granted, so this just swaps the `mode` field, drops stale overlays, and
     * (for dict mode) warms the offline dictionary.
     */
    private fun setMode(newMode: Int) {
        if (newMode == mode) return
        clearMorphemeBoxes()
        clearSentenceBoxes()
        mode = newMode
        persistMode()
        if (newMode == MODE_SENTENCE_DICT) captureHandler?.post { JmDict.warmUp(this) }
        updateOcrButtonAppearance()
        val name = when (newMode) {
            MODE_SENTENCE_LLM -> "LLM sentence"
            MODE_SENTENCE_DICT -> "JMdict sentence"
            else -> "word"
        }
        Toast.makeText(this, "Mode: $name", Toast.LENGTH_SHORT).show()
    }

    private data class MenuOption(val label: String, val color: Int, val action: () -> Unit)

    /**
     * Radial mode-switch menu shown on long-press of the floating button. Option
     * buttons (the three modes + Stop) are arranged in a circle around the button;
     * tapping the dimmed background dismisses it.
     */
    private fun showRadialMenu(btnParams: WindowManager.LayoutParams) {
        if (radialMenu != null) return
        val btnSize = dp(64)
        val cx = btnParams.x + btnSize / 2
        val cy = btnParams.y + btnSize / 2
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val options = listOf(
            MenuOption("OCR", Color.argb(230, 30, 100, 220)) { setMode(MODE_MORPHEME) },
            MenuOption("LLM", Color.argb(230, 140, 60, 200)) { setMode(MODE_SENTENCE_LLM) },
            MenuOption("辞書", Color.argb(230, 40, 160, 120)) { setMode(MODE_SENTENCE_DICT) },
            MenuOption("Stop", Color.argb(230, 220, 80, 60)) { stopSelf() },
        )

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            isClickable = true
            setOnClickListener { dismissRadialMenu() }
        }

        val radius = dp(104)
        val size = dp(58)
        val margin = dp(6)
        for ((i, opt) in options.withIndex()) {
            // Start at the top, go clockwise.
            val angle = (2.0 * PI * i / options.size) - PI / 2.0
            val ox = cx + (radius * cos(angle)).toInt()
            val oy = cy + (radius * sin(angle)).toInt()
            val lx = (ox - size / 2).coerceIn(margin, screenW - margin - size)
            val ly = (oy - size / 2).coerceIn(margin, screenH - margin - size)
            val btn = makeOptionButton(opt.label, opt.color) {
                dismissRadialMenu()
                opt.action()
            }
            scrim.addView(btn, FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = lx
                topMargin = ly
            })
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // NO_LIMITS so the scrim shares the floating button's full-screen
            // coordinate space (incl. the status-bar region); otherwise the option
            // buttons would be offset by the top inset.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            windowManager.addView(scrim, params)
            radialMenu = scrim
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add radial menu", e)
        }
    }

    private fun makeOptionButton(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            elevation = dp(4).toFloat()
            setOnClickListener { onClick() }
        }

    private fun dismissRadialMenu() {
        radialMenu?.let { runCatching { windowManager.removeView(it) } }
        radialMenu = null
    }

    private fun captureAndRecognize() {
        // Toggle: if any overlay is showing, just clear it.
        if (renderedBoxes.isNotEmpty() || renderedSentenceBoxes.isNotEmpty()) {
            clearMorphemeBoxes()
            clearSentenceBoxes()
            return
        }
        if (isProcessing) return
        val reader = imageReader ?: return
        isProcessing = true
        // Hide the button so it isn't captured, then capture next frame.
        floatingView?.visibility = View.INVISIBLE

        captureHandler?.postDelayed({
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image == null) {
                    Log.w(TAG, "No image available")
                    return@postDelayed
                }
                val bitmap = imageToBitmap(image)
                runOcr(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
            } finally {
                image?.close()
                Handler(mainLooper).post {
                    floatingView?.visibility = View.VISIBLE
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

    private fun runOcr(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                Log.i(TAG, "── OCR result ──")
                if (result.text.isBlank()) {
                    Log.i(TAG, "(no text)")
                    Log.i(TAG, "────────────────")
                    isProcessing = false
                    return@addOnSuccessListener
                }
                for (block in result.textBlocks) {
                    Log.i(TAG, "[block] ${block.text.replace("\n", " / ")}")
                }
                Log.i(TAG, "[full]\n${result.text}")
                if (isSentenceMode()) {
                    captureHandler?.post {
                        val sboxes = computeSentenceBoxes(result)
                        Log.i(TAG, "Sentence boxes: ${sboxes.size}")
                        mainHandler.post {
                            renderSentenceBoxes(sboxes)
                            isProcessing = false
                        }
                    }
                } else {
                    // Tokenize off the main thread — first call loads ~7MB dictionary.
                    captureHandler?.post {
                        val boxes = computeMorphemeBoxes(result)
                        logMorphemes(boxes)
                        mainHandler.post {
                            renderMorphemeBoxes(boxes)
                            isProcessing = false
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                isProcessing = false
            }
    }

    /** Both sentence modes share the OCR → sentence-box pipeline; only the popup differs. */
    private fun isSentenceMode(): Boolean =
        mode == MODE_SENTENCE_LLM || mode == MODE_SENTENCE_DICT

    private fun computeMorphemeBoxes(visionText: Text): List<MorphemeBox> {
        val out = mutableListOf<MorphemeBox>()
        var seq = 0
        var lineKey = 0
        for (block in visionText.textBlocks) {
            // Reorder lines for reading order: vertical Japanese reads columns right-to-left.
            val orderedLines = if (isBlockVertical(block)) {
                block.lines.sortedByDescending { it.boundingBox?.centerX() ?: 0 }
            } else {
                block.lines
            }
            for (line in orderedLines) {
                val lineBoxes = morphemeBoxesForLine(line, seq, lineKey)
                out += lineBoxes
                seq += lineBoxes.size
                lineKey++
            }
        }
        return out
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
     * Per-element interpolation. We rebuild the line text from elements so the
     * Kuromoji character offsets line up exactly with the elements that own them,
     * then locate each morpheme's pixel rect inside the tight element boxes
     * rather than the looser line box.
     */
    private fun morphemeBoxesForLine(line: Text.Line, seqStart: Int, lineKey: Int): List<MorphemeBox> {
        return if (isLineVertical(line)) verticalLineBoxes(line, seqStart, lineKey)
        else horizontalLineBoxes(line, seqStart, lineKey)
    }

    /**
     * Vertical Japanese (tategaki). Build the line text from element.text (visible
     * chars only — line.text can have hidden separators that throw off char-to-pixel
     * mapping). Sort elements top-to-bottom by box.top first; ML Kit doesn't
     * guarantee element reading order for vertical lines. Then interpolate per
     * element along the Y-axis, just like horizontal does along X.
     */
    private fun verticalLineBoxes(line: Text.Line, seqStart: Int, lineKey: Int): List<MorphemeBox> {
        val elements = line.elements
        if (elements.isEmpty()) return emptyList()

        val sorted = elements.sortedBy { it.boundingBox?.top ?: Int.MAX_VALUE }

        val sb = StringBuilder()
        data class ElementRange(val box: android.graphics.Rect, val start: Int, val end: Int)
        val ranges = ArrayList<ElementRange>(sorted.size)
        for (e in sorted) {
            val eBox = e.boundingBox ?: continue
            val s = sb.length
            sb.append(e.text)
            ranges += ElementRange(eBox, s, sb.length)
        }
        val lineText = sb.toString()
        if (lineText.isEmpty()) return emptyList()

        val morphemes = try {
            JapaneseTokenizer.extract(lineText)
        } catch (e: Exception) {
            Log.e(TAG, "Tokenize failed (vertical)", e)
            return emptyList()
        }

        val result = ArrayList<MorphemeBox>(morphemes.size)
        for (m in morphemes) {
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE

            for (r in ranges) {
                val overlapStart = max(r.start, m.start)
                val overlapEnd = min(r.end, m.end)
                if (overlapEnd <= overlapStart) continue

                val eLen = (r.end - r.start).coerceAtLeast(1)
                val charH = r.box.height().toFloat() / eLen
                val t = r.box.top + ((overlapStart - r.start) * charH).toInt()
                val b = r.box.top + ((overlapEnd - r.start) * charH).toInt()

                if (r.box.left < left) left = r.box.left
                if (r.box.right > right) right = r.box.right
                if (t < top) top = t
                if (b > bottom) bottom = b
            }

            // Fallback to line-level interpolation if no element overlapped.
            if (left == Int.MAX_VALUE) {
                val lb = line.boundingBox ?: continue
                val charH = lb.height().toFloat() / max(lineText.length, 1)
                left = lb.left
                right = lb.right
                top = lb.top + (m.start * charH).toInt()
                bottom = lb.top + (m.end * charH).toInt()
            }

            val width = max(right - left, 1)
            val height = max(bottom - top, 1)
            result += MorphemeBox(
                left = left,
                top = top,
                width = width,
                height = height,
                morpheme = m,
                seq = seqStart + result.size,
                lineKey = lineKey,
                lineText = lineText,
                charStart = m.start,
                charEnd = m.end,
            )
        }
        return result
    }

    /**
     * Horizontal text. Per-element interpolation: we rebuild the line text from
     * elements so Kuromoji's char offsets line up with the elements that own them,
     * then place each morpheme inside the tight element box(es) it overlaps.
     */
    private fun horizontalLineBoxes(line: Text.Line, seqStart: Int, lineKey: Int): List<MorphemeBox> {
        val elements = line.elements
        if (elements.isEmpty()) return emptyList()

        val sb = StringBuilder()
        data class ElementRange(val box: android.graphics.Rect, val start: Int, val end: Int)
        val ranges = ArrayList<ElementRange>(elements.size)
        for (e in elements) {
            val eBox = e.boundingBox ?: continue
            val s = sb.length
            sb.append(e.text)
            ranges += ElementRange(eBox, s, sb.length)
        }
        val lineText = sb.toString()
        if (lineText.isEmpty()) return emptyList()

        val morphemes = try {
            JapaneseTokenizer.extract(lineText)
        } catch (e: Exception) {
            Log.e(TAG, "Tokenize failed (horizontal)", e)
            return emptyList()
        }

        val result = ArrayList<MorphemeBox>(morphemes.size)
        for (m in morphemes) {
            var left = Int.MAX_VALUE
            var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE

            for (r in ranges) {
                val overlapStart = max(r.start, m.start)
                val overlapEnd = min(r.end, m.end)
                if (overlapEnd <= overlapStart) continue
                val eLen = (r.end - r.start).coerceAtLeast(1)
                val charW = r.box.width().toFloat() / eLen
                val l = r.box.left + ((overlapStart - r.start) * charW).toInt()
                val rEdge = r.box.left + ((overlapEnd - r.start) * charW).toInt()
                if (l < left) left = l
                if (rEdge > right) right = rEdge
                if (r.box.top < top) top = r.box.top
                if (r.box.bottom > bottom) bottom = r.box.bottom
            }

            if (left == Int.MAX_VALUE) {
                val lb = line.boundingBox ?: continue
                val charW = lb.width().toFloat() / max(lineText.length, 1)
                left = lb.left + (m.start * charW).toInt()
                right = lb.left + (m.end * charW).toInt()
                top = lb.top
                bottom = lb.bottom
            }

            val width = max(right - left, 1)
            val height = max(bottom - top, 1)
            result += MorphemeBox(
                left = left,
                top = top,
                width = width,
                height = height,
                morpheme = m,
                seq = seqStart + result.size,
                lineKey = lineKey,
                lineText = lineText,
                charStart = m.start,
                charEnd = m.end,
            )
        }
        return result
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

    private fun logMorphemes(boxes: List<MorphemeBox>) {
        Log.i(TAG, "── Morphemes (${boxes.size}) ──")
        if (boxes.isEmpty()) {
            Log.i(TAG, "(none)")
        } else {
            for (b in boxes) {
                val m = b.morpheme
                val rd = if (m.reading.isNotEmpty()) "  [${m.reading}]" else ""
                Log.i(TAG, "${m.pos}  ${m.base}  (surface=${m.surface})$rd")
            }
        }
        Log.i(TAG, "────────────────")
    }

    private fun renderMorphemeBoxes(boxes: List<MorphemeBox>) {
        clearMorphemeBoxes()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val padY = dp(3)
        for (b in boxes) {
            val view = View(this).apply {
                background = makeBoxDrawable(selected = false)
                isClickable = true
                setOnClickListener { onBoxClick(b) }
                setOnLongClickListener {
                    onBoxLongPress(b)
                    true
                }
            }
            val params = WindowManager.LayoutParams(
                b.width, b.height + padY * 2,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = b.left
                y = b.top - padY
            }
            try {
                windowManager.addView(view, params)
                renderedBoxes += RenderedBox(view, b)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add box view", e)
            }
        }
        Log.i(TAG, "Rendered ${renderedBoxes.size} morpheme boxes")
        updateOcrButtonAppearance()
    }

    private fun makeBoxDrawable(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        if (selected) {
            setStroke(dp(2), Color.argb(255, 255, 165, 60))
            setColor(Color.argb(90, 255, 165, 60))
        } else {
            setStroke(dp(2), Color.argb(255, 80, 220, 120))
            setColor(Color.argb(48, 80, 220, 120))
        }
        cornerRadius = dp(3).toFloat()
    }

    private fun clearMorphemeBoxes() {
        dismissPopup()
        anchor1 = null
        anchor2 = null
        for (rb in renderedBoxes) {
            runCatching { windowManager.removeView(rb.view) }
        }
        renderedBoxes.clear()
        updateOcrButtonAppearance()
    }

    private fun onBoxClick(b: MorphemeBox) {
        val a1 = anchor1
        val a2 = anchor2
        if (a1 != null && a2 != null && isInRange(b)) {
            showRangePopup(a1, a2)
        } else {
            val m = b.morpheme
            val rd = if (m.reading.isNotEmpty()) "  [${m.reading}]" else ""
            Log.i(TAG, "tap → ${m.base}  (${m.pos}, surface=${m.surface})$rd")
            showTranslationPopup(b)
        }
    }

    private fun onBoxLongPress(b: MorphemeBox) {
        dismissPopup()
        when {
            anchor1 == null -> {
                anchor1 = b
                Log.i(TAG, "anchor1 set: ${b.morpheme.surface}")
            }
            anchor2 == null -> {
                anchor2 = b
                Log.i(TAG, "anchor2 set: ${b.morpheme.surface} — range committed")
            }
            else -> {
                anchor1 = null
                anchor2 = null
                Log.i(TAG, "selection cancelled")
            }
        }
        refreshBoxHighlights()
    }

    private fun isInRange(b: MorphemeBox): Boolean {
        val a = anchor1 ?: return false
        val c = anchor2 ?: return b.seq == a.seq
        return b.seq in min(a.seq, c.seq)..max(a.seq, c.seq)
    }

    private fun refreshBoxHighlights() {
        for (rb in renderedBoxes) {
            rb.view.background = makeBoxDrawable(selected = isInRange(rb.box))
        }
    }

    private fun showTranslationPopup(box: MorphemeBox) {
        dismissPopup()
        val m = box.morpheme
        val hasKanji = JapaneseTokenizer.containsKanji(m.base)
        val hira = if (hasKanji && m.reading.isNotEmpty())
            JapaneseTokenizer.katakanaToHiragana(m.reading) else ""

        val titleStr = if (hira.isNotEmpty()) "${m.base}  ${hira}" else m.base
        val titleView = TextView(this).apply {
            text = titleStr
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(2))
        }
        val translationView = TextView(this).apply {
            text = "Translating…"
            setTextColor(Color.argb(255, 210, 210, 210))
            textSize = 14f
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(translationView)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), dp(2), dp(4), dp(2))
            isClickable = true
            setOnClickListener { dismissPopup() }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(8), dp(8), dp(8))
            addView(textCol, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(closeBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Measure to compute placement.
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(unspec, unspec)
        val pw = container.measuredWidth
        val ph = container.measuredHeight

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val centerX = box.left + box.width / 2
        var x = centerX - pw / 2
        var y = box.top - ph - dp(6)
        // Clamp horizontally.
        val margin = dp(4)
        if (x < margin) x = margin
        if (x + pw > screenW - margin) x = screenW - margin - pw
        // If the popup would be off the top of the screen, place it below the box.
        if (y < margin) y = box.top + box.height + dp(6)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add popup", e)
            return
        }

        val holder = PopupHolder(container, translationView)
        popup = holder

        // Translate off the main thread.
        val query = m.base
        Thread {
            val result = try {
                Translator.translateJaToEn(query)
            } catch (e: Throwable) {
                Log.e(TAG, "Translation failed for '$query'", e)
                ""
            }
            mainHandler.post {
                if (popup === holder) {
                    translationView.text = if (result.isBlank()) "(no translation)" else result
                }
            }
        }.start()
    }

    private fun dismissPopup() {
        popup?.let { runCatching { windowManager.removeView(it.view) } }
        popup = null
    }

    /** Reconstructs the natural-language text spanned by anchors a and b. */
    private fun buildRangeText(a: MorphemeBox, b: MorphemeBox): String {
        val (lo, hi) = if (a.seq <= b.seq) a to b else b to a
        val inRange = renderedBoxes.map { it.box }.filter { it.seq in lo.seq..hi.seq }
        if (inRange.isEmpty()) return ""

        // Group by line, ordered by reading position.
        val byLine = LinkedHashMap<Int, MutableList<MorphemeBox>>()
        for (mb in inRange.sortedBy { it.seq }) {
            byLine.getOrPut(mb.lineKey) { mutableListOf() } += mb
        }

        val sb = StringBuilder()
        for ((key, boxes) in byLine) {
            val lineText = boxes.first().lineText
            val from = if (key == lo.lineKey) lo.charStart else 0
            val to = if (key == hi.lineKey) hi.charEnd else lineText.length
            if (from in 0..lineText.length && to in from..lineText.length) {
                sb.append(lineText, from, to)
            }
        }
        return sb.toString()
    }

    private fun showRangePopup(a: MorphemeBox, b: MorphemeBox) {
        dismissPopup()
        val rangeText = buildRangeText(a, b)
        if (rangeText.isBlank()) return
        val readingHira = JapaneseTokenizer.fullReadingHiragana(rangeText)

        val titleView = TextView(this).apply {
            text = rangeText
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 0, 0, dp(2))
            maxWidth = resources.displayMetrics.widthPixels - dp(60)
        }
        val readingView = TextView(this).apply {
            text = readingHira
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
            maxWidth = resources.displayMetrics.widthPixels - dp(60)
        }
        val translationView = TextView(this).apply {
            text = "Translating…"
            setTextColor(Color.argb(255, 210, 210, 210))
            textSize = 14f
            maxWidth = resources.displayMetrics.widthPixels - dp(60)
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(readingView)
            addView(translationView)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), dp(2), dp(4), dp(2))
            isClickable = true
            setOnClickListener { dismissPopup() }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(8), dp(8), dp(8))
            addView(textCol, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(closeBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(unspec, unspec)
        val pw = container.measuredWidth
        val ph = container.measuredHeight

        // Position above the topmost box in the range, centered horizontally on its midpoint.
        val (lo, hi) = if (a.seq <= b.seq) a to b else b to a
        val topBox = if (lo.top <= hi.top) lo else hi
        val screenW = resources.displayMetrics.widthPixels
        val centerX = topBox.left + topBox.width / 2
        var x = centerX - pw / 2
        var y = topBox.top - ph - dp(6)
        val margin = dp(4)
        if (x < margin) x = margin
        if (x + pw > screenW - margin) x = screenW - margin - pw
        if (y < margin) y = topBox.top + topBox.height + dp(6)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add range popup", e)
            return
        }

        val holder = PopupHolder(container, translationView)
        popup = holder

        Log.i(TAG, "range = \"$rangeText\"   hira = \"$readingHira\"")

        Thread {
            val result = try {
                Translator.translateJaToEn(rangeText)
            } catch (e: Throwable) {
                Log.e(TAG, "Range translation failed", e)
                ""
            }
            mainHandler.post {
                if (popup === holder) {
                    translationView.text = if (result.isBlank()) "(no translation)" else result
                }
            }
        }.start()
    }

    // ───────────────────────── Sentence mode (LLM) ─────────────────────────

    /**
     * Converts inline `**bold**` markdown to actual bold spans. The LLM sometimes
     * emits emphasis markup which would otherwise show up as literal asterisks
     * in the popup. Anything that isn't a `**…**` pair is passed through verbatim.
     */
    /**
     * Build one row of the Word-by-word section: word text on the left, a small
     * "+" button on the right that adds the word to AnkiDroid as a card.
     */
    private fun buildWordRow(
        entry: BedrockClient.WordEntry,
        textMaxW: Int,
        sentence: String,
        translation: String,
        expandable: Boolean = false,
    ): View {
        val labelText = buildString {
            append(entry.word)
            if (entry.reading.isNotEmpty() && entry.reading != entry.word) {
                append(" (").append(entry.reading).append(')')
            }
            append(" — ").append(entry.meaning)
            if (entry.jlpt.isNotEmpty()) append("  [").append(entry.jlpt).append(']')
        }
        val addBtn = TextView(this).apply {
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
        // Reserve width for the leading "+" (and trailing chevron, if expandable)
        // so long text still wraps inside the popup.
        val label = TextView(this).apply {
            text = labelText
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 13f
            maxWidth = textMaxW - dp(44) - if (expandable) dp(34) else 0
        }
        addBtn.setOnClickListener {
            // Dict mode (expandable): card back = the full JMdict detail blob.
            // LLM mode: card back = the summary (reading/meaning/JLPT/sentence).
            if (expandable) handleAddToAnkiDict(entry, addBtn, sentence, translation)
            else handleAddToAnki(entry, addBtn, sentence, translation)
        }
        // "+" sits at the left, immediately before the word, so it stays close
        // to the word regardless of how wide the popup gets.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(addBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
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
        // Note: we deliberately do NOT reposition the popup on expand/collapse —
        // the popup stays anchored at its top-left and just grows downward, so the
        // sections (incl. Translation) don't jump sideways when content resizes.
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

    /** LLM-mode add: front = word, back = the summary (reading/meaning/JLPT/sentence). */
    private fun handleAddToAnki(
        entry: BedrockClient.WordEntry,
        btn: TextView,
        sentence: String,
        translation: String,
    ) {
        if (!ankiPreflight()) return
        // Disable the button during the (blocking) add so duplicate taps don't double-add.
        btn.isEnabled = false
        btn.alpha = 0.5f
        Thread {
            val result = AnkiDroidHelper.addCard(
                this, entry.word, entry.reading, entry.meaning, entry.jlpt, sentence, translation
            )
            mainHandler.post { applyAnkiResult(result, btn, entry.word) }
        }.start()
    }

    /**
     * Dict-mode add: front = the word (kanji form, no kana reading); back = the full
     * JMdict detail blob rendered as HTML (all senses, POS, tags, xrefs, loanword, …).
     */
    private fun handleAddToAnkiDict(
        entry: BedrockClient.WordEntry,
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
        entry: BedrockClient.WordEntry,
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

    private fun renderInlineMarkdown(s: String): CharSequence {
        if (s.isEmpty() || !s.contains("**")) return s
        val out = SpannableStringBuilder()
        val re = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)
        var i = 0
        for (m in re.findAll(s)) {
            if (m.range.first > i) out.append(s, i, m.range.first)
            val start = out.length
            out.append(m.groupValues[1])
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = m.range.last + 1
        }
        if (i < s.length) out.append(s, i, s.length)
        return out
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
     * triggers the LLM with the complete sentence.
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
                if (full.isNotEmpty()) {
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
    }

    private fun makeSentenceBoxDrawable(): GradientDrawable = GradientDrawable().apply {
        setStroke(dp(2), Color.argb(255, 140, 120, 240))
        setColor(Color.argb(48, 140, 120, 240))
        cornerRadius = dp(3).toFloat()
    }

    private fun clearSentenceBoxes() {
        if (renderedSentenceBoxes.isEmpty() && popup == null) {
            // popup may belong to morpheme mode; only dismiss if we own boxes
        }
        dismissPopup()
        for (rb in renderedSentenceBoxes) {
            runCatching { windowManager.removeView(rb.view) }
        }
        renderedSentenceBoxes.clear()
        updateOcrButtonAppearance()
    }

    private fun onSentenceClick(box: SentenceBox) {
        Log.i(TAG, "sentence tap → ${box.fullText}")
        if (mode == MODE_SENTENCE_DICT) showDictAnalysisPopup(box)
        else showLlmAnalysisPopup(box)
    }

    /**
     * The analysis popup scaffold shared by LLM mode and dict mode. Holds the
     * section views each mode populates differently, plus the [reposition] helper
     * that re-clamps the popup once its content grows.
     */
    private class AnalysisPopup(
        val holder: PopupHolder,
        val container: View,
        val wordBody: TextView,
        val wordList: LinearLayout,
        val kanjiHeader: TextView,
        val kanjiBody: TextView,
        val transHeader: TextView,
        val transBody: TextView,
        val notesHeader: TextView,
        val notesBody: TextView,
        val textMaxW: Int,
        val reposition: () -> Unit,
    )

    /**
     * Builds + shows the empty analysis popup for [box] (header/drag handle,
     * scrollable Word-by-word / Kanji / Translation / Notes sections), positions
     * it, and registers it as the current [popup]. Returns the view handles so the
     * caller can fill them in, or null if the window couldn't be added.
     */
    private fun buildAnalysisPopup(box: SentenceBox): AnalysisPopup? {
        dismissPopup()
        userMovedPopup = false

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val sideMargin = dp(8)
        val maxPopupW = screenW - sideMargin * 2
        val maxPopupH = (screenH * 0.65f).toInt()
        // Cap for text wrapping: popup max width minus container padding minus
        // close-button column. Text wraps before exceeding this.
        val textMaxW = maxPopupW - dp(12) - dp(8) - dp(44)
        // Reserve max scroll height = popup cap minus header row height budget.
        val maxScrollH = maxPopupH - dp(60)

        val titleView = TextView(this).apply {
            text = box.fullText
            setTextColor(Color.WHITE)
            textSize = 15f
            maxWidth = textMaxW
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isClickable = true
            setOnClickListener { dismissPopup() }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(titleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
            addView(closeBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            setPadding(0, 0, 0, dp(6))
        }

        val wordHeader = TextView(this).apply {
            text = "Word-by-word"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(2))
        }
        val wordBody = TextView(this).apply {
            text = "Analyzing…"
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 13f
            maxWidth = textMaxW
        }
        // Structured per-word rows replace `wordBody` once the LLM response is parsed.
        // Each row carries a "+ Anki" button that adds the word as a card.
        val wordList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val kanjiHeader = TextView(this).apply {
            text = "Kanji"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        val kanjiBody = TextView(this).apply {
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 13f
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
        val notesHeader = TextView(this).apply {
            text = "Notes"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        val notesBody = TextView(this).apply {
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 13f
            maxWidth = textMaxW
            visibility = View.GONE
        }

        val sectionsCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(wordHeader)
            addView(wordBody)
            addView(wordList)
            addView(kanjiHeader)
            addView(kanjiBody)
            addView(transHeader)
            addView(transBody)
            addView(notesHeader)
            addView(notesBody)
        }
        // ScrollView that caps its own height. Below the cap it wraps to content
        // (so the popup stays small for short answers); past the cap it scrolls.
        val scroll = object : android.widget.ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val capped = View.MeasureSpec.makeMeasureSpec(maxScrollH, View.MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, capped)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            addView(sectionsCol, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(8), dp(8), dp(8))
            // MATCH_PARENT here so the header row stretches to the popup's
            // natural width (determined by the scroll content), letting the
            // title's weight=1 push the ✕ to the right edge.
            addView(headerRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // Re-clamp the popup's on-screen position whenever its content changes
        // size (e.g. when the LLM response arrives and sections become visible).
        fun reposition() {
            if (userMovedPopup) return
            val wSpec = View.MeasureSpec.makeMeasureSpec(maxPopupW, View.MeasureSpec.AT_MOST)
            val hSpec = View.MeasureSpec.makeMeasureSpec(maxPopupH, View.MeasureSpec.AT_MOST)
            container.measure(wSpec, hSpec)
            val pw = min(container.measuredWidth, maxPopupW)
            val ph = min(container.measuredHeight, maxPopupH)

            val centerX = box.left + box.width / 2
            var x = centerX - pw / 2
            if (x < sideMargin) x = sideMargin
            if (x + pw > screenW - sideMargin) x = screenW - sideMargin - pw
            var y = box.top - ph - dp(6)
            if (y < sideMargin) y = box.top + box.height + dp(6)
            if (y + ph > screenH - sideMargin) y = screenH - sideMargin - ph
            if (y < sideMargin) y = sideMargin

            val lp = container.layoutParams as? WindowManager.LayoutParams ?: return
            if (lp.x != x || lp.y != y) {
                lp.x = x
                lp.y = y
                runCatching { windowManager.updateViewLayout(container, lp) }
            }
        }

        // Initial measure for first placement; popup itself remains WRAP_CONTENT.
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxPopupW, View.MeasureSpec.AT_MOST)
        val hSpec = View.MeasureSpec.makeMeasureSpec(maxPopupH, View.MeasureSpec.AT_MOST)
        container.measure(wSpec, hSpec)
        val pw0 = min(container.measuredWidth, maxPopupW)
        val ph0 = min(container.measuredHeight, maxPopupH)
        val centerX0 = box.left + box.width / 2
        var x0 = centerX0 - pw0 / 2
        if (x0 < sideMargin) x0 = sideMargin
        if (x0 + pw0 > screenW - sideMargin) x0 = screenW - sideMargin - pw0
        var y0 = box.top - ph0 - dp(6)
        if (y0 < sideMargin) y0 = box.top + box.height + dp(6)
        if (y0 + ph0 > screenH - sideMargin) y0 = screenH - sideMargin - ph0
        if (y0 < sideMargin) y0 = sideMargin

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = x0
            y = y0
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add analysis popup", e)
            return null
        }
        val holder = PopupHolder(container, wordBody)
        popup = holder

        // Drag-to-move via the header row. The ✕ button is clickable and
        // consumes its own touches first, so taps on it still close the popup;
        // anything else in the header row (title text + padding) drags.
        // Once dragged, suppress the auto-reposition that runs when content
        // grows — otherwise the popup snaps back when the LLM response arrives.
        var dragInitialX = 0
        var dragInitialY = 0
        var dragStartRawX = 0f
        var dragStartRawY = 0f
        headerRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialX = params.x
                    dragInitialY = params.y
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = dragInitialX + (event.rawX - dragStartRawX).toInt()
                    params.y = dragInitialY + (event.rawY - dragStartRawY).toInt()
                    userMovedPopup = true
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }

        return AnalysisPopup(
            holder = holder,
            container = container,
            wordBody = wordBody,
            wordList = wordList,
            kanjiHeader = kanjiHeader,
            kanjiBody = kanjiBody,
            transHeader = transHeader,
            transBody = transBody,
            notesHeader = notesHeader,
            notesBody = notesBody,
            textMaxW = textMaxW,
            reposition = { reposition() },
        )
    }

    /** Sentence-mode popup backed by AWS Bedrock (MODE_SENTENCE_LLM). */
    private fun showLlmAnalysisPopup(box: SentenceBox) {
        val ui = buildAnalysisPopup(box) ?: return

        val token = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_BEDROCK_TOKEN, "")
            .orEmpty()
            .trim()

        if (token.isEmpty()) {
            ui.wordBody.text = "(missing AWS_BEARER_TOKEN_BEDROCK — set it in the app)"
            ui.container.post { ui.reposition() }
            return
        }

        val sentence = box.fullText
        Thread {
            val result = runCatching { BedrockClient.analyzeJapanese(sentence, token) }
            mainHandler.post {
                if (popup !== ui.holder) return@post
                result.onSuccess { a ->
                    if (a.words.isNotEmpty()) {
                        ui.wordBody.visibility = View.GONE
                        ui.wordList.removeAllViews()
                        for (w in a.words) {
                            ui.wordList.addView(buildWordRow(w, ui.textMaxW, box.fullText, a.translation))
                        }
                        ui.wordList.visibility = View.VISIBLE
                    } else {
                        ui.wordBody.text = if (a.wordByWord.isNotBlank())
                            renderInlineMarkdown(a.wordByWord) else "(no analysis)"
                    }
                    if (a.kanji.isNotBlank() && a.kanji.trim() != "(none)") {
                        ui.kanjiBody.text = renderInlineMarkdown(a.kanji)
                        ui.kanjiHeader.visibility = View.VISIBLE
                        ui.kanjiBody.visibility = View.VISIBLE
                    }
                    if (a.translation.isNotBlank()) {
                        ui.transBody.text = renderInlineMarkdown(a.translation)
                        ui.transHeader.visibility = View.VISIBLE
                        ui.transBody.visibility = View.VISIBLE
                    }
                    if (a.notes.isNotBlank() && a.notes.trim() != "(none)") {
                        ui.notesBody.text = renderInlineMarkdown(a.notes)
                        ui.notesHeader.visibility = View.VISIBLE
                        ui.notesBody.visibility = View.VISIBLE
                    }
                }.onFailure { e ->
                    Log.e(TAG, "LLM analysis failed", e)
                    ui.wordBody.text = "Error: ${e.message ?: e.javaClass.simpleName}"
                }
                ui.container.post { ui.reposition() }
            }
        }.start()
    }

    /**
     * Offline sentence-mode popup (MODE_SENTENCE_DICT). Word-by-word comes from
     * Kuromoji morphemes glossed against JMdict; the Kanji section from KANJIDIC2;
     * the full-sentence translation from Google Translate. No LLM, so no Notes.
     */
    private fun showDictAnalysisPopup(box: SentenceBox) {
        val ui = buildAnalysisPopup(box) ?: return
        ui.wordBody.text = "Looking up…"

        val sentence = box.fullText
        Thread {
            // No-op if handleStart already warmed it; guards against a tap that
            // races the warm-up.
            JmDict.warmUp(this)
            val available = JmDict.isAvailable()

            // Word-by-word: one row per distinct Kuromoji dictionary form, glossed
            // against JMdict. Reading comes from Kuromoji (katakana → hiragana),
            // matching morpheme mode.
            val entries = ArrayList<BedrockClient.WordEntry>()
            if (available) {
                val seen = HashSet<String>()
                val morphemes = runCatching { JapaneseTokenizer.extract(sentence) }
                    .getOrDefault(emptyList())
                for (m in morphemes) {
                    if (!seen.add(m.base)) continue
                    val reading = if (JapaneseTokenizer.containsKanji(m.base) && m.reading.isNotEmpty())
                        JapaneseTokenizer.katakanaToHiragana(m.reading) else ""
                    val gloss = JmDict.lookupWord(m.base)?.gloss ?: "(not in dictionary)"
                    entries += BedrockClient.WordEntry(m.base, reading, gloss, "")
                }
            }

            // No standalone Kanji section in dict mode — each word's own kanji are
            // folded into that word's expandable detail panel (see buildWordRow).
            val translation = runCatching { Translator.translateJaToEn(sentence) }.getOrDefault("")

            mainHandler.post {
                if (popup !== ui.holder) return@post
                if (!available) {
                    ui.wordBody.text =
                        "Dictionary not built — run scripts/build_jmdict_db.py and reinstall."
                } else if (entries.isNotEmpty()) {
                    ui.wordBody.visibility = View.GONE
                    ui.wordList.removeAllViews()
                    for (w in entries) {
                        ui.wordList.addView(
                            buildWordRow(w, ui.textMaxW, sentence, translation, expandable = true)
                        )
                    }
                    ui.wordList.visibility = View.VISIBLE
                } else {
                    ui.wordBody.text = "(no words found)"
                }
                if (translation.isNotBlank()) {
                    ui.transBody.text = translation
                    ui.transHeader.visibility = View.VISIBLE
                    ui.transBody.visibility = View.VISIBLE
                }
                ui.container.post { ui.reposition() }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rebuild capture surface for new orientation. Stale boxes are now wrong, drop them.
        clearMorphemeBoxes()
        clearSentenceBoxes()
        if (mediaProjection != null) setupVirtualDisplay()
    }

    override fun onDestroy() {
        clearMorphemeBoxes()
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
