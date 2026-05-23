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
import android.graphics.drawable.GradientDrawable
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {

    companion object {
        const val TAG = "JpLens"
        const val ACTION_START = "com.example.jp_lens_android.START"
        const val ACTION_STOP = "com.example.jp_lens_android.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "jp_lens_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val mainHandler by lazy { Handler(mainLooper) }
    @Volatile private var isProcessing = false

    private data class PopupHolder(val view: View, val translationView: TextView)
    private var popup: PopupHolder? = null

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
        val active = renderedBoxes.isNotEmpty()
        btn.text = if (active) "✕" else "OCR"
        btn.background = GradientDrawable().apply {
            if (active) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(230, 220, 80, 60))
            } else {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 30, 100, 220))
            }
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
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchStartX).toInt()
                    params.y = initialY + (event.rawY - touchStartY).toInt()
                    windowManager.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - touchStartX) > touchSlop ||
                        abs(event.rawY - touchStartY) > touchSlop
                    if (!moved) v.performClick()
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { captureAndRecognize() }
    }

    private fun captureAndRecognize() {
        // Toggle: if boxes are showing, just clear them.
        if (renderedBoxes.isNotEmpty()) {
            clearMorphemeBoxes()
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
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                isProcessing = false
            }
    }

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
                b.width, b.height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = b.left
                y = b.top
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rebuild capture surface for new orientation. Stale boxes are now wrong, drop them.
        clearMorphemeBoxes()
        if (mediaProjection != null) setupVirtualDisplay()
    }

    override fun onDestroy() {
        clearMorphemeBoxes()
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
