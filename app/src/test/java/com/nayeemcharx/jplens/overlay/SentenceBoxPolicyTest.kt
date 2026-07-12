package com.nayeemcharx.jplens.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SentenceBoxPolicyTest {

    private fun box(
        name: String,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        vertical: Boolean,
    ) = SentenceBox(left, top, width, height, name.hashCode(), name, vertical)

    @Test
    fun threeVerticalBoxesSurviveOneCrossingHorizontalBox() {
        val v1 = box("v1", 80, 10, 12, 100, true)
        val v2 = box("v2", 50, 10, 12, 100, true)
        val v3 = box("v3", 20, 10, 12, 100, true)
        val horizontal = box("horizontal", 0, 50, 110, 12, false)

        assertEquals(listOf(v1, v2, v3),
            preferVerticalSentenceBoxes(listOf(v1, v2, v3, horizontal)))
    }

    @Test
    fun onlyIntersectingHorizontalBoxesAreRemoved() {
        val vertical = box("vertical", 40, 20, 10, 80, true)
        val crossing = box("crossing", 0, 50, 100, 10, false)
        val separate = box("separate", 60, 110, 30, 10, false)

        assertEquals(listOf(vertical, separate),
            preferVerticalSentenceBoxes(listOf(vertical, crossing, separate)))
    }

    @Test
    fun verticalBoxesNeverRemoveEachOther() {
        val a = box("a", 10, 10, 20, 100, true)
        val b = box("b", 15, 40, 20, 100, true)

        assertEquals(listOf(a, b), preferVerticalSentenceBoxes(listOf(a, b)))
    }

    @Test
    fun horizontalBoxesRemainWhenThereAreNoVerticalBoxes() {
        val a = box("a", 0, 0, 100, 20, false)
        val b = box("b", 10, 10, 100, 20, false)

        assertEquals(listOf(a, b), preferVerticalSentenceBoxes(listOf(a, b)))
    }

    @Test
    fun touchingEdgesAreNotAnIntersection() {
        val vertical = box("vertical", 50, 20, 10, 80, true)
        val touchesLeftEdge = box("horizontal", 0, 50, 50, 10, false)

        assertEquals(listOf(vertical, touchesLeftEdge),
            preferVerticalSentenceBoxes(listOf(vertical, touchesLeftEdge)))
    }

    @Test
    fun resultPreservesIdentityAndInputOrder() {
        val horizontalBefore = box("before", 0, 0, 20, 5, false)
        val vertical = box("vertical", 50, 20, 10, 80, true)
        val horizontalAfter = box("after", 80, 100, 20, 5, false)

        val result = preferVerticalSentenceBoxes(
            listOf(horizontalBefore, vertical, horizontalAfter))

        assertSame(horizontalBefore, result[0])
        assertSame(vertical, result[1])
        assertSame(horizontalAfter, result[2])
    }

    @Test
    fun japaneseRatioAboveThirtyPercentIsAccepted() {
        // 3 Japanese / 9 total = 33.3%.
        assertEquals(true, hasEnoughJapaneseForSentenceBox("猫犬鳥abcdef"))
    }

    @Test
    fun japaneseRatioExactlyThirtyPercentIsRejected() {
        // 3 Japanese / 10 total = exactly 30%.
        assertEquals(false, hasEnoughJapaneseForSentenceBox("猫犬鳥abcdefg"))
    }

    @Test
    fun japaneseRatioBelowThirtyPercentIsRejected() {
        assertEquals(false, hasEnoughJapaneseForSentenceBox("猫abcdef"))
        assertEquals(false, hasEnoughJapaneseForSentenceBox(""))
    }

    @Test
    fun kanaAndKanjiAllCountAsJapaneseCharacters() {
        assertEquals(true, hasEnoughJapaneseForSentenceBox("猫あアabcdef"))
    }

    @Test
    fun punctuationWhitespaceAndDigitsCountInTotalLength() {
        // Only one of these four characters is Japanese: 25%.
        assertEquals(false, hasEnoughJapaneseForSentenceBox("猫 1!"))
    }
}
