package com.nayeemcharx.jplens.overlay

/** Result of a plain floating-button tap, ordered by visible UI priority. */
internal enum class CaptureTapAction {
    CLEAR_SENTENCE_BOXES,
    DISMISS_POPUP,
    IGNORE_WHILE_BUSY,
    START_CAPTURE,
}

/** Pure policy used by OverlayService before performing Android window operations. */
internal fun captureTapAction(
    sentenceBoxesShowing: Boolean,
    popupShowing: Boolean,
    processing: Boolean,
    cropSelectorActive: Boolean,
): CaptureTapAction = when {
    sentenceBoxesShowing -> CaptureTapAction.CLEAR_SENTENCE_BOXES
    popupShowing -> CaptureTapAction.DISMISS_POPUP
    processing || cropSelectorActive -> CaptureTapAction.IGNORE_WHILE_BUSY
    else -> CaptureTapAction.START_CAPTURE
}

/** Pure frame-watcher policy: enough cells must exceed the luma threshold. */
internal fun hasSignificantFrameChange(
    baseline: IntArray,
    current: IntArray,
    cellThreshold: Int,
    changedPercent: Int = 35,
): Boolean {
    if (baseline.size != current.size) return true
    if (baseline.isEmpty()) return false
    var changedCells = 0
    for (i in baseline.indices) {
        if (kotlin.math.abs(baseline[i] - current[i]) > cellThreshold) changedCells++
    }
    return changedCells > baseline.size * changedPercent / 100
}
