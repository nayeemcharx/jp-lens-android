package com.nayeemcharx.jplens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.nayeemcharx.jplens.overlay.AnalysisPopupController
import com.nayeemcharx.jplens.overlay.CropSelectorController
import com.nayeemcharx.jplens.overlay.FloatingButtonController
import com.nayeemcharx.jplens.overlay.SentenceBox
import com.nayeemcharx.jplens.overlay.SentenceBoxOverlayController
import com.nayeemcharx.jplens.overlay.assembleCropText
import com.nayeemcharx.jplens.overlay.computeSentenceBoxes
import com.nayeemcharx.jplens.overlay.CaptureTapAction
import com.nayeemcharx.jplens.overlay.captureTapAction
import com.nayeemcharx.jplens.overlay.hasSignificantFrameChange

/**
 * The capture runtime: a foreground service that owns the MediaProjection +
 * VirtualDisplay + ImageReader, runs ML Kit Japanese OCR, and coordinates the
 * overlay components in [com.nayeemcharx.jplens.overlay]:
 *
 * - [FloatingButtonController] — the floating capture button + its hold-to-open
 *   radial mode menu (mode switch / stop).
 * - [SentenceBoxOverlayController] — full-screen mode's single box-overlay
 *   window ([computeSentenceBoxes] turns an OCR result into boxes).
 * - [CropSelectorController] — crop mode's drag-a-rectangle selector over a
 *   frozen snapshot ([assembleCropText] joins the crop's OCR text).
 * - [AnalysisPopupController] — the analysis popup ("breakdown") with the
 *   tappable interactive sentence, word detail cards, Anki, and the
 *   Reading/Romaji/Translation sections.
 */
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
        // Whole-sentence popup section toggles (set on the home screen; both default
        // on). The per-word dictionary is always available via the tappable sentence,
        // so it has no toggle. ("show_dictionary" is a retired key — the old
        // Word-by-word toggle — deliberately not reused.)
        const val PREF_SHOW_READING = "show_reading"
        const val PREF_SHOW_ROMAJI = "show_romaji"
        const val PREF_SHOW_TRANSLATION = "show_translation"

        // True while a capture session is actually live (projection acquired,
        // floating button up). Read by the home screen so its "Running" banner
        // reflects reality — not merely that the consent dialog returned OK.
        @Volatile var isRunning = false
            private set

        private const val CHANNEL_ID = "jp_lens_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager

    // Overlay components (created in onCreate, torn down in onDestroy).
    private lateinit var floatingButton: FloatingButtonController
    private lateinit var sentenceBoxes: SentenceBoxOverlayController
    private lateinit var cropSelector: CropSelectorController
    private lateinit var analysisPopup: AnalysisPopupController

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val mainHandler by lazy { Handler(mainLooper) }
    @Volatile private var isProcessing = false
    // Supersede token for capture/OCR callbacks. Resetting while loading bumps it,
    // so work already queued on ML Kit/captureHandler cannot revive stale UI.
    @Volatile private var captureRequestSeq = 0

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

    private var mode: Int = MODE_SENTENCE_DICT

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

        floatingButton = FloatingButtonController(
            this, windowManager, mainHandler,
            object : FloatingButtonController.Listener {
                override fun onButtonTap() = captureAndRecognize()
                override fun onLoadingTap() = resetPendingWork()
                override fun onModeSelected(mode: Int) = setMode(mode)
                override fun onStopRequested() { stopSelf() }
            })
        sentenceBoxes = SentenceBoxOverlayController(
            this, windowManager,
            onSentenceTap = { onSentenceClick(it) },
            onDismissRequest = { clearSentenceBoxes() })
        analysisPopup = AnalysisPopupController(
            this, windowManager, mainHandler,
            object : AnalysisPopupController.Listener {
                // While the popup is up the transparent sentence boxes are hidden
                // (visibility only — the box window stays alive and, being
                // INVISIBLE, receives no input).
                override fun onPopupShown() {
                    sentenceBoxes.setVisible(false)
                    // Store the active appearance while the spinner still owns the
                    // pixels, then switch directly spinner -> ✕ in one redraw.
                    updateOcrButtonAppearance()
                    floatingButton.setLoading(false)
                }
                override fun onPopupDismissed() {
                    sentenceBoxes.setVisible(true)
                    updateOcrButtonAppearance()
                }
            })
        cropSelector = CropSelectorController(
            this, windowManager,
            object : CropSelectorController.Listener {
                override fun onCropCommitted(bitmap: Bitmap, crop: Rect) {
                    floatingButton.setButtonVisible(true)
                    floatingButton.setLoading(true)
                    val requestId = captureRequestSeq
                    captureHandler?.post {
                        if (requestId != captureRequestSeq) return@post
                        try {
                            var cropped = Bitmap.createBitmap(
                                bitmap, crop.left, crop.top, crop.width(), crop.height())
                            // A full-screen crop may return the original bitmap.
                            // Always give OCR an independent bitmap because the
                            // selector snapshot was recently owned by ImageView's
                            // render thread and must never be explicitly recycled.
                            if (cropped === bitmap) {
                                cropped = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            }
                            Log.i(TAG, "Crop OCR: (${crop.left},${crop.top}) ${crop.width()}x${crop.height()}")
                            runCropOcr(cropped, crop, requestId)
                        } catch (ex: Exception) {
                            Log.e(TAG, "Crop OCR failed", ex)
                            isProcessing = false
                            mainHandler.post { floatingButton.setLoading(false) }
                        }
                    }
                }
                override fun onCropCancelled() {
                    isProcessing = false
                    floatingButton.setLoading(false)
                    floatingButton.setButtonVisible(true)
                }
            })

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
        cropSelector.cancel()

        // Resume whatever mode the user last used (persisted in PREF_LAST_MODE);
        // the radial menu still switches live after. Full-screen is only the
        // fallback for a first-ever run.
        mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_LAST_MODE, MODE_SENTENCE_DICT)
        Log.i(TAG, "Starting mode=$mode (2=full screen, 4=crop)")

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
        if (mediaProjection == null) {
            // Fail fast — otherwise we'd show a floating button that can never
            // capture, while the home screen claims the session is running.
            Log.e(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }

        captureThread = HandlerThread("JpLensCapture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)

        // Warm the Kuromoji dictionary off the main thread so the first OCR tap is snappy.
        captureHandler?.post {
            val t0 = System.currentTimeMillis()
            JapaneseTokenizer.warmUp(this)
            Log.i(TAG, "Kuromoji warm-up: ${System.currentTimeMillis() - t0} ms (available=${JapaneseTokenizer.isAvailable()})")
        }
        // Both modes use the offline JMdict SQLite asset (the tappable-word
        // dictionary) and the offline FuguMT translator (Translation section), so
        // warm both up front.
        captureHandler?.post {
            val t0 = System.currentTimeMillis()
            JmDict.warmUp(this)
            Log.i(TAG, "JMdict warm-up: ${System.currentTimeMillis() - t0} ms (available=${JmDict.isAvailable()})")
            val t1 = System.currentTimeMillis()
            Translator.warmUp(this)
            Log.i(TAG, "FuguMT warm-up: ${System.currentTimeMillis() - t1} ms (available=${Translator.isAvailable()})")
        }

        setupVirtualDisplay()
        floatingButton.show()
        updateOcrButtonAppearance()
        isRunning = true
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

    /** Restyles the floating button for the current mode / overlay state. */
    private fun updateOcrButtonAppearance() {
        // "Active" (tap clears) when boxes are showing, or when a crop-mode popup
        // is open without boxes.
        floatingButton.updateAppearance(
            active = sentenceBoxes.isShowing || analysisPopup.isShowing,
            mode = mode,
        )
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
        val name = if (newMode == MODE_CROP) "Crop" else "Full screen"
        Toast.makeText(this, "Mode: $name", Toast.LENGTH_SHORT).show()
    }

    // ───────────────────────── Change watcher ─────────────────────────

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
        if (isProcessing || analysisPopup.isShowing || floatingButton.isMenuOpen ||
            cropSelector.isActive
        ) {
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
        // Require ~35% of the screen to change so scrolling a line doesn't nuke boxes,
        // but an app/window switch (near-total change) does.
        return hasSignificantFrameChange(a, b, changeThreshold)
    }

    // ───────────────────────── Capture + OCR ─────────────────────────

    private fun captureAndRecognize() {
        when (captureTapAction(
            sentenceBoxesShowing = sentenceBoxes.isShowing,
            popupShowing = analysisPopup.isShowing,
            processing = isProcessing,
            cropSelectorActive = cropSelector.isActive,
        )) {
            CaptureTapAction.CLEAR_SENTENCE_BOXES -> { clearSentenceBoxes(); return }
            CaptureTapAction.DISMISS_POPUP -> { analysisPopup.dismiss(); return }
            CaptureTapAction.IGNORE_WHILE_BUSY -> return
            CaptureTapAction.START_CAPTURE -> Unit
        }
        val reader = imageReader ?: return
        // A new capture supersedes any popup lookup still in flight — otherwise a
        // stale popup could surface on top of the new snapshot/selector.
        analysisPopup.invalidatePending()
        isProcessing = true
        val requestId = ++captureRequestSeq
        // Acknowledge the tap immediately, then smoothly fade the overlay layer out
        // for the clean MediaProjection frame (FLAG_SECURE creates a black patch).
        floatingButton.setLoading(true)

        mainHandler.postDelayed({
            if (requestId != captureRequestSeq) return@postDelayed
            floatingButton.fadeOutForCapture {
                captureHandler?.postDelayed({
                    if (requestId == captureRequestSeq) captureFrame(reader, requestId)
                }, 32L)
            }
        }, 140L)
    }

    private fun captureFrame(reader: ImageReader, requestId: Int) {
        if (requestId != captureRequestSeq) return
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
                    if (requestId != captureRequestSeq) return
                    if (mode == MODE_CROP) {
                        handedOff = true
                        mainHandler.post {
                            if (requestId != captureRequestSeq) return@post
                            // Both operations happen in one main-loop turn, so no
                            // button-less frame is composited. Hide first so a failed
                            // selector add can safely restore it via onCropCancelled.
                            floatingButton.setButtonVisible(false)
                            cropSelector.show(bitmap)
                        }
                    } else {
                        runOcr(bitmap, requestId)
                        handedOff = true
                        mainHandler.post { floatingButton.setButtonVisible(true) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
            } finally {
                image?.close()
                if (!handedOff) {
                    isProcessing = false
                    mainHandler.post {
                        floatingButton.setButtonVisible(true)
                        floatingButton.setLoading(false)
                    }
                }
            }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        // Crop off the padding columns.
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (result !== padded) padded.recycle()
        return result
    }

    /** Sentence mode: OCR the full screen and overlay clickable sentence boxes. */
    private fun runOcr(bitmap: Bitmap, requestId: Int) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                if (requestId != captureRequestSeq) return@addOnSuccessListener
                Log.i(TAG, "── OCR result ──")
                if (result.text.isBlank()) {
                    Log.i(TAG, "(no text)")
                    Log.i(TAG, "────────────────")
                    Toast.makeText(this, "No Japanese text found", Toast.LENGTH_SHORT).show()
                    isProcessing = false
                    floatingButton.setLoading(false)
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
                        if (requestId != captureRequestSeq) return@post
                        if (sboxes.isEmpty()) {
                            Toast.makeText(this, "No Japanese text found", Toast.LENGTH_SHORT).show()
                        }
                        renderSentenceBoxes(sboxes)
                        isProcessing = false
                        floatingButton.setLoading(false)
                    }
                }
            }
            .addOnFailureListener { e ->
                if (requestId != captureRequestSeq) return@addOnFailureListener
                Log.e(TAG, "OCR failed", e)
                isProcessing = false
                mainHandler.post { floatingButton.setLoading(false) }
            }
            .addOnCompleteListener { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    /**
     * Crop mode: OCR the cropped region and — instead of rendering boxes — open
     * the analysis popup directly with **all** the Japanese text found in the
     * crop, assembled in reading order ([assembleCropText]).
     */
    private fun runCropOcr(bitmap: Bitmap, cropRect: Rect, requestId: Int) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                if (requestId != captureRequestSeq) return@addOnSuccessListener
                val text = assembleCropText(result)
                Log.i(TAG, "Crop OCR text → \"$text\"")
                isProcessing = false
                if (text.isEmpty()) {
                    floatingButton.setLoading(false)
                    Toast.makeText(this, "No Japanese text found in the selection", Toast.LENGTH_SHORT).show()
                } else {
                    analysisPopup.showDictAnalysisPopup(text, cropRect)
                }
            }
            .addOnFailureListener { e ->
                if (requestId != captureRequestSeq) return@addOnFailureListener
                Log.e(TAG, "Crop OCR failed", e)
                isProcessing = false
                mainHandler.post { floatingButton.setLoading(false) }
            }
            .addOnCompleteListener { if (!bitmap.isRecycled) bitmap.recycle() }
    }

    // ───────────────────────── Sentence boxes ─────────────────────────

    private fun renderSentenceBoxes(boxes: List<SentenceBox>) {
        clearSentenceBoxes(resetLoading = false)
        if (boxes.isEmpty()) return
        if (sentenceBoxes.render(boxes)) {
            // The fullscreen overlay was just added ABOVE the floating button
            // (same window type, later add = higher z) and would swallow its
            // touches — raise the button so it stays on top and keeps its
            // tap / drag / hold-menu gestures while boxes are showing. (The
            // raise is gesture-safe: mid-hold it's deferred to the release.)
            floatingButton.raise()
            startChangeWatch()
        }
        updateOcrButtonAppearance()
        // updateOcrButtonAppearance stored active=true while the spinner remained
        // drawn, so this is a direct spinner -> ✕ transition without an icon flash.
        floatingButton.setLoading(false)
    }

    private fun clearSentenceBoxes(resetLoading: Boolean = true) {
        stopChangeWatch()
        analysisPopup.dismiss()
        sentenceBoxes.clear()
        if (resetLoading) floatingButton.setLoading(false)
        updateOcrButtonAppearance()
    }

    private fun onSentenceClick(box: SentenceBox) {
        Log.i(TAG, "sentence tap → ${box.fullText}")
        floatingButton.setLoading(true)
        analysisPopup.showDictAnalysisPopup(
            box.fullText,
            Rect(box.left, box.top, box.left + box.width, box.top + box.height),
        )
    }

    /** User tapped the spinner: cancel all pending work and return to idle. */
    private fun resetPendingWork() {
        captureRequestSeq++
        isProcessing = false
        analysisPopup.invalidatePending()
        cropSelector.cancel()
        clearSentenceBoxes()
        floatingButton.setButtonVisible(true)
        floatingButton.setLoading(false)
        updateOcrButtonAppearance()
    }

    // ─────────────────────────────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rebuild capture surface for new orientation. Stale boxes are now wrong,
        // drop them; a pending crop selection is against the old snapshot, cancel it.
        cropSelector.cancel()
        clearSentenceBoxes()
        if (mediaProjection != null) setupVirtualDisplay()
    }

    override fun onDestroy() {
        isRunning = false
        cropSelector.cancel()
        clearSentenceBoxes()
        analysisPopup.destroy()
        floatingButton.destroy()
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
        // A Stop action on the notification — a way out that works even if the
        // floating button somehow becomes unreachable.
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JP Lens")
            .setContentText("Floating OCR is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
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
}
