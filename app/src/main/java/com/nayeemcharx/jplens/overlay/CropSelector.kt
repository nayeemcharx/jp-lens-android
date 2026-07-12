package com.nayeemcharx.jplens.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.mlkit.vision.text.Text
import com.nayeemcharx.jplens.JapaneseTokenizer
import com.nayeemcharx.jplens.OverlayService
import kotlin.math.max
import kotlin.math.min

/**
 * Crop mode (MODE_CROP): the fullscreen crop selector over a frozen snapshot,
 * plus [assembleCropText], which joins the crop's OCR result into one string in
 * reading order.
 */

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
internal fun assembleCropText(result: Text): String {
    val pieces = ArrayList<CropBlockPiece>()
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
            pieces += CropBlockPiece(
                text = blockText.toString(),
                centerX = block.boundingBox?.centerX() ?: 0,
                top = block.boundingBox?.top ?: 0,
                vertical = vert,
            )
        }
    }

    return assembleCropPieces(pieces)
}

/** Pure block-ordering half of [assembleCropText], separated for host tests. */
internal data class CropBlockPiece(
    val text: String,
    val centerX: Int,
    val top: Int,
    val vertical: Boolean,
)

internal fun assembleCropPieces(pieces: List<CropBlockPiece>): String {
    val verticalMajority = pieces.count { it.vertical } * 2 > pieces.size
    val ordered = if (verticalMajority) {
        pieces.sortedWith(
            compareByDescending<CropBlockPiece> { it.centerX }
                .thenBy { it.top }
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
 * Owns the fullscreen crop-selection overlay: shows the frozen snapshot, dims
 * it, and lets the user drag a rectangle. Releasing a big-enough drag commits
 * the selection ([Listener.onCropCommitted] — the service crops + OCRs it); the
 * ✕ button (or an external [cancel]) aborts ([Listener.onCropCancelled] — the
 * service re-enables capture). A selection smaller than ~24dp is treated as
 * accidental and the user can retry. While the selector is up the service keeps
 * the floating button hidden and `isProcessing` set, so a stray button tap
 * can't start a second capture.
 */
class CropSelectorController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val listener: Listener,
) {
    interface Listener {
        /** The user committed a selection: [crop] in snapshot-pixel coords of [bitmap]. */
        fun onCropCommitted(bitmap: Bitmap, crop: Rect)
        /** The selector went away without a commit (✕, restart, rotation, destroy). */
        fun onCropCancelled()
    }

    private var selectorView: View? = null

    val isActive: Boolean get() = selectorView != null

    /** Cancels a pending crop selection (no-op when none is up). */
    fun cancel() {
        if (selectorView == null) return
        dismiss()
        listener.onCropCancelled()
    }

    private fun dismiss() {
        selectorView?.let { runCatching { windowManager.removeView(it) } }
        selectorView = null
    }

    /**
     * Shows the selector over the frozen [bitmap] snapshot. The window is sized
     * to the real metrics at the physical origin (IN_SCREEN + NO_LIMITS, cutout
     * SHORT_EDGES) so view coords line up 1:1 with snapshot pixels; the commit
     * still adds `getLocationOnScreen` offsets defensively. Returns false (after
     * notifying [Listener.onCropCancelled]) when the window couldn't be added.
     */
    fun show(bitmap: Bitmap): Boolean {
        if (selectorView != null) return true

        val dimPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
        val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
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
        val selection = object : View(context) {
            override fun onDraw(canvas: Canvas) {
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

        val hint = TextView(context).apply {
            text = "Drag to select the area to scan"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(235, 20, 20, 20))
            }
        }
        val cancelBtn = TextView(context).apply {
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
            setOnClickListener { cancel() }
        }

        val frame = FrameLayout(context).apply {
            addView(ImageView(context).apply {
                setImageBitmap(bitmap)
                // The bitmap is exactly screen-sized; stretch-fit keeps 1:1 pixels.
                scaleType = ImageView.ScaleType.FIT_XY
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
                        dismiss()
                        listener.onCropCommitted(bitmap, Rect(l, t, r, b))
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
            windowManager.addView(frame, params)
            selectorView = frame
            true
        } catch (e: Exception) {
            Log.e(OverlayService.TAG, "Failed to add crop selector", e)
            listener.onCropCancelled()
            false
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
