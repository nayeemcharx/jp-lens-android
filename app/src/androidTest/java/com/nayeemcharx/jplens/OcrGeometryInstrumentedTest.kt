package com.nayeemcharx.jplens

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nayeemcharx.jplens.overlay.ElementRange
import com.nayeemcharx.jplens.overlay.rectForRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Device coverage for the character-to-screen geometry used by both OCR modes. */
@RunWith(AndroidJUnit4::class)
class OcrGeometryInstrumentedTest {

    @Test
    fun horizontalPartialElementIsInterpolated() {
        val ranges = listOf(ElementRange(Rect(10, 20, 110, 40), 0, 10))

        assertEquals(
            Rect(30, 20, 70, 40),
            rectForRange(ranges, 2, 6, vertical = false, lineBox = null, lineTextLen = 10),
        )
    }

    @Test
    fun verticalPartialElementIsInterpolated() {
        val ranges = listOf(ElementRange(Rect(20, 10, 50, 110), 0, 10))

        assertEquals(
            Rect(20, 40, 50, 90),
            rectForRange(ranges, 3, 8, vertical = true, lineBox = null, lineTextLen = 10),
        )
    }

    @Test
    fun rangeAcrossElementsUnionsOnlyTheOverlappingSlices() {
        val ranges = listOf(
            ElementRange(Rect(0, 10, 40, 30), 0, 4),
            ElementRange(Rect(50, 8, 110, 32), 4, 10),
        )

        assertEquals(
            Rect(20, 8, 80, 32),
            rectForRange(ranges, 2, 7, vertical = false, lineBox = null, lineTextLen = 10),
        )
    }

    @Test
    fun missingElementBoxesFallBackToHorizontalLineBox() {
        assertEquals(
            Rect(30, 20, 70, 50),
            rectForRange(
                emptyList(), 3, 7, vertical = false,
                lineBox = Rect(0, 20, 100, 50), lineTextLen = 10,
            ),
        )
    }

    @Test
    fun missingElementBoxesFallBackToVerticalLineBox() {
        assertEquals(
            Rect(20, 30, 50, 70),
            rectForRange(
                emptyList(), 3, 7, vertical = true,
                lineBox = Rect(20, 0, 50, 100), lineTextLen = 10,
            ),
        )
    }

    @Test
    fun invalidOrUnmappableRangesReturnNull() {
        assertNull(rectForRange(emptyList(), 4, 4, false, Rect(0, 0, 10, 10), 10))
        assertNull(rectForRange(emptyList(), 0, 2, false, null, 2))
    }

    @Test
    fun zeroLengthElementStillProducesFiniteBounds() {
        val ranges = listOf(ElementRange(Rect(5, 6, 25, 16), 2, 2))
        // It cannot overlap, so the valid line fallback is used without division by zero.
        assertEquals(
            Rect(0, 0, 20, 10),
            rectForRange(ranges, 0, 2, false, Rect(0, 0, 20, 10), 2),
        )
    }
}
