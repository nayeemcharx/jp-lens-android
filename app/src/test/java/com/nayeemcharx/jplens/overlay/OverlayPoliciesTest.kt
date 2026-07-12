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
    fun tapPolicyTruthTableAlwaysHonorsPriority() {
        for (mask in 0 until 16) {
            val boxes = mask and 1 != 0
            val popup = mask and 2 != 0
            val processing = mask and 4 != 0
            val crop = mask and 8 != 0
            val expected = when {
                boxes -> CaptureTapAction.CLEAR_SENTENCE_BOXES
                popup -> CaptureTapAction.DISMISS_POPUP
                processing || crop -> CaptureTapAction.IGNORE_WHILE_BUSY
                else -> CaptureTapAction.START_CAPTURE
            }
            assertEquals(expected, captureTapAction(boxes, popup, processing, crop))
        }
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

    @Test
    fun frameWatcherUsesStrictChangedCellPercentageForNonRoundGrid() {
        val baseline = IntArray(7)
        // Integer cutoff is 7*35/100 = 2, so two cells are stable and three change.
        assertFalse(hasSignificantFrameChange(
            baseline, IntArray(7) { if (it < 2) 100 else 0 }, 48))
        assertTrue(hasSignificantFrameChange(
            baseline, IntArray(7) { if (it < 3) 100 else 0 }, 48))
    }

    @Test
    fun frameWatcherHandlesBothLumaDirectionsAndCustomPercentage() {
        assertTrue(hasSignificantFrameChange(
            intArrayOf(100, 100, 100, 100),
            intArrayOf(0, 0, 100, 100),
            cellThreshold = 48,
            changedPercent = 25,
        ))
        assertFalse(hasSignificantFrameChange(
            intArrayOf(0, 0, 0, 0),
            intArrayOf(100, 0, 0, 0),
            cellThreshold = 48,
            changedPercent = 25,
        ))
    }
}
