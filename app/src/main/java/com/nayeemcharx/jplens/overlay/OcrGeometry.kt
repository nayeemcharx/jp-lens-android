package com.nayeemcharx.jplens.overlay

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OCR text-geometry helpers shared by the full-screen sentence-box pipeline
 * ([SentenceBoxes]) and crop mode ([CropSelector]/[assembleCropText]): rebuilding
 * a line's text from its elements, mapping char ranges back to pixel rects, and
 * detecting vertical (tategaki) lines/blocks.
 */

internal data class ElementRange(val box: Rect, val start: Int, val end: Int)

/**
 * Rebuilds a line's text by concatenating `element.text` (NOT `line.text`, which
 * can contain hidden separators that break the char→pixel mapping), returning the
 * text plus each element's char range. Vertical lines sort elements top-to-bottom
 * first so the ranges follow reading order.
 */
internal fun buildLineRanges(line: Text.Line, vertical: Boolean): Pair<String, List<ElementRange>> {
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
internal fun rectForRange(
    ranges: List<ElementRange>,
    from: Int,
    to: Int,
    vertical: Boolean,
    lineBox: Rect?,
    lineTextLen: Int,
): Rect? {
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
            Rect(
                lb.left,
                lb.top + (from * charH).toInt(),
                lb.right,
                lb.top + (to * charH).toInt()
            )
        } else {
            val charW = lb.width().toFloat() / max(lineTextLen, 1)
            Rect(
                lb.left + (from * charW).toInt(),
                lb.top,
                lb.left + (to * charW).toInt(),
                lb.bottom
            )
        }
    }
    return Rect(left, top, right, bottom)
}

internal fun isBlockVertical(block: Text.TextBlock): Boolean {
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
internal fun isLineVertical(line: Text.Line): Boolean {
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
