package com.nayeemcharx.jplens.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPoliciesTest {

    @Test
    fun visibleSentenceBoxesTakeTapPriority() {
        assertEquals(CaptureTapAction.CLEAR_SENTENCE_BOXES,
            captureTapAction(true, true, true, true))
    }

    @Test
    fun popupIsDismissedWhenThereAreNoSentenceBoxes() {
        assertEquals(CaptureTapAction.DISMISS_POPUP,
            captureTapAction(false, true, true, true))
    }

    @Test
    fun processingAndCropSelectionIgnoreNormalCaptureTap() {
        assertEquals(CaptureTapAction.IGNORE_WHILE_BUSY,
            captureTapAction(false, false, true, false))
        assertEquals(CaptureTapAction.IGNORE_WHILE_BUSY,
            captureTapAction(false, false, false, true))
    }

    @Test
    fun idleTapStartsCapture() {
        assertEquals(CaptureTapAction.START_CAPTURE,
            captureTapAction(false, false, false, false))
    }

    @Test
    fun frameWatcherRequiresMoreThanThirtyFivePercentChanged() {
        val baseline = IntArray(100)
        val exactly35 = IntArray(100) { if (it < 35) 100 else 0 }
        val thirtySix = IntArray(100) { if (it < 36) 100 else 0 }

        assertFalse(hasSignificantFrameChange(baseline, exactly35, 48))
        assertTrue(hasSignificantFrameChange(baseline, thirtySix, 48))
    }

    @Test
    fun frameWatcherUsesStrictPerCellThreshold() {
        assertFalse(hasSignificantFrameChange(IntArray(10), IntArray(10) { 48 }, 48))
        assertTrue(hasSignificantFrameChange(IntArray(10), IntArray(10) { 49 }, 48))
    }

    @Test
    fun frameWatcherTreatsSizeMismatchAsChangeAndEmptyAsStable() {
        assertTrue(hasSignificantFrameChange(IntArray(4), IntArray(5), 48))
        assertFalse(hasSignificantFrameChange(intArrayOf(), intArrayOf(), 48))
    }
}
