package com.nayeemcharx.jplens.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.google.mlkit.vision.text.Text
import com.nayeemcharx.jplens.JapaneseTokenizer
import com.nayeemcharx.jplens.OverlayService
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen mode (MODE_SENTENCE_DICT): sentence-box computation from an OCR
 * result plus the single fullscreen overlay window that renders every box and
 * hit-tests taps. The line-render geometry it builds on lives in [OcrGeometry].
 */

// One clickable line-piece of a sentence.
data class SentenceBox(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val sentenceId: Int,    // shared across all box pieces of the same sentence
    val fullText: String,   // full sentence text (may span multiple line-pieces)
)

/**
 * Splits OCR output into sentence-level boxes. Lines are walked in reading
 * order within each reading unit (see [readingUnits] — a horizontal block,
 * or a right-to-left run of adjacent vertical columns that ML Kit split
 * into separate blocks); pieces between Japanese terminators (。！？!?) get
 * grouped into one logical sentence even when split across lines. Each
 * physical line-piece becomes its own clickable rectangle; all pieces of the
 * same sentence carry the same fullText (and same sentenceId) so any click
 * opens the analysis popup for the complete sentence.
 */
internal fun computeSentenceBoxes(visionText: Text): List<SentenceBox> {
    val terminators = setOf('。', '！', '？', '!', '?', '．', '.')
    val out = mutableListOf<SentenceBox>()
    var sid = 0

    for (orderedLines in readingUnits(visionText)) {
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
        // End of reading unit — flush any sentence that lacked a terminator.
        flushSentence()
    }
    return out
}

/**
 * Groups ML Kit blocks into "reading units" — line lists a sentence is
 * allowed to flow through. A horizontal block maps to one unit as-is, but
 * multi-column tategaki usually comes back as *one block per column* (tall
 * narrow blocks separated by clean gaps read as separate paragraphs to
 * ML Kit), and its block order is ~left-to-right — the reverse of vertical
 * reading order. So adjacent vertical blocks (columns with substantial
 * vertical overlap, separated by no more than ~1.5 column widths) are
 * merged into one unit with all their lines re-sorted right-to-left (ties
 * top-to-bottom), letting [computeSentenceBoxes] continue a sentence
 * across columns instead of flushing at every block edge. The gap guard
 * keeps unrelated vertical text (a neighboring bubble/panel) from being
 * glued into the same sentence.
 */
private fun readingUnits(visionText: Text): List<List<Text.Line>> {
    val units = mutableListOf<List<Text.Line>>()
    val vertBlocks = mutableListOf<Text.TextBlock>()
    for (block in visionText.textBlocks) {
        if (isBlockVertical(block)) vertBlocks += block else units += block.lines
    }
    if (vertBlocks.isEmpty()) return units

    // Median line width ≈ column width — the yardstick for how far apart
    // two columns of the same paragraph can plausibly be.
    val colWidth = vertBlocks.map { block ->
        val widths = block.lines.mapNotNull { it.boundingBox?.width() }.sorted()
        if (widths.isEmpty()) 0 else widths[widths.size / 2]
    }
    val boxes = vertBlocks.map { it.boundingBox }

    fun sameColumnRun(i: Int, j: Int): Boolean {
        val a = boxes[i] ?: return false
        val b = boxes[j] ?: return false
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlap < 0.5f * min(a.height(), b.height())) return false
        val gap = max(a.left, b.left) - min(a.right, b.right)
        val w = min(colWidth[i], colWidth[j])
        return w > 0 && gap <= 1.5f * w
    }

    // Union-find over the (few) vertical blocks; transitive merging chains
    // a whole run even when only neighboring columns are pairwise close.
    val parent = IntArray(vertBlocks.size) { it }
    fun find(x: Int): Int {
        var r = x
        while (parent[r] != r) r = parent[r]
        return r
    }
    for (i in vertBlocks.indices) {
        for (j in i + 1 until vertBlocks.size) {
            if (sameColumnRun(i, j)) parent[find(j)] = find(i)
        }
    }

    for (members in vertBlocks.indices.groupBy { find(it) }.values) {
        units += members.flatMap { vertBlocks[it].lines }
            .sortedWith(
                compareByDescending<Text.Line> { it.boundingBox?.centerX() ?: 0 }
                    .thenBy { it.boundingBox?.top ?: 0 }
            )
    }
    return units
}

/**
 * Owns the ONE fullscreen overlay window that draws ALL sentence boxes and
 * hit-tests taps itself. One window total, no matter how much text is on
 * screen — one window *per box* meant one surface + ViewRootImpl + synchronous
 * WindowManager IPC each, which crashed graphics-memory-starved devices on
 * dense pages and made boxes visibly appear/disappear one at a time.
 *
 * Touch semantics: tap a box → [onSentenceTap]; tap or swipe anywhere else →
 * [onDismissRequest] (the fullscreen window necessarily consumes the touch, so
 * it can't be passed through to the app below — but the boxes are stale the
 * moment the underlying content moves anyway, and the change-watcher would
 * have cleared them).
 */
class SentenceBoxOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onSentenceTap: (SentenceBox) -> Unit,
    private val onDismissRequest: () -> Unit,
) {
    private var overlayView: BoxOverlayView? = null

    val isShowing: Boolean get() = overlayView != null

    /**
     * Adds the fullscreen box window, sized to the real metrics at the physical
     * origin (IN_SCREEN + NO_LIMITS) so canvas coords line up 1:1 with the OCR
     * pixel coords. Returns true when the window was added.
     */
    fun render(boxes: List<SentenceBox>): Boolean {
        clear()
        if (boxes.isEmpty()) return false
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val view = BoxOverlayView(context).apply { setBoxes(boxes) }
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
        return try {
            windowManager.addView(view, params)
            overlayView = view
            Log.i(OverlayService.TAG, "Rendered ${boxes.size} sentence boxes in one overlay window")
            true
        } catch (e: Exception) {
            Log.e(OverlayService.TAG, "Failed to add sentence box overlay", e)
            false
        }
    }

    fun clear() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }

    /**
     * Hides/shows the box window while the analysis popup is open. The window is
     * kept alive — this only flips view visibility, so it costs nothing (no
     * re-OCR, no re-layout) and the boxes come back instantly when the popup
     * closes. An INVISIBLE window also receives no input, so touches outside the
     * popup pass through to the app below while it's hidden.
     */
    fun setVisible(visible: Boolean) {
        overlayView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private inner class BoxOverlayView(context: Context) : View(context) {
        private var boxes: List<SentenceBox> = emptyList()
        private var rects: List<RectF> = emptyList()  // padded, in screen px
        private val padY = 0f
        private val corner = dp(3).toFloat()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(48, 140, 120, 240)
        }
        private val pressedFillPaint = Paint(fillPaint).apply {
            color = Color.argb(110, 140, 120, 240)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.argb(255, 140, 120, 240)
        }
        private val touchSlop = dp(8)
        private var pressedIndex = -1
        private var downX = 0f
        private var downY = 0f

        fun setBoxes(newBoxes: List<SentenceBox>) {
            boxes = newBoxes
            rects = newBoxes.map {
                RectF(
                    it.left.toFloat(), it.top - padY,
                    (it.left + it.width).toFloat(), it.top + it.height + padY
                )
            }
            pressedIndex = -1
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            for (i in rects.indices) {
                val r = rects[i]
                canvas.drawRoundRect(r, corner, corner,
                    if (i == pressedIndex) pressedFillPaint else fillPaint)
                canvas.drawRoundRect(r, corner, corner, strokePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            // The window normally sits at the physical origin, so view coords ==
            // screen coords; add the actual offset in case the system inset the
            // window (same defensive mapping as the crop selector).
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val x = event.x + loc[0]
            val y = event.y + loc[1]
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    pressedIndex = rects.indexOfFirst { it.contains(x, y) }
                    if (pressedIndex < 0) {
                        // Empty area — the tap dismisses the boxes.
                        onDismissRequest()
                    } else {
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressedIndex >= 0 &&
                        (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                    ) {
                        // A drag, not a tap — treat like an empty-area touch.
                        pressedIndex = -1
                        onDismissRequest()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val i = pressedIndex
                    pressedIndex = -1
                    invalidate()
                    if (i >= 0 && i < boxes.size) {
                        performClick()
                        onSentenceTap(boxes[i])
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedIndex = -1
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
