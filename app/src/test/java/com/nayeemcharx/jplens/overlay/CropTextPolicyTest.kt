package com.nayeemcharx.jplens.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class CropTextPolicyTest {

    @Test
    fun verticalMajoritySortsBlocksRightToLeft() {
        val left = CropBlockPiece("左", centerX = 20, top = 10, vertical = true)
        val right = CropBlockPiece("右", centerX = 100, top = 20, vertical = true)
        val middle = CropBlockPiece("中", centerX = 60, top = 30, vertical = true)

        assertEquals("右 中 左", assembleCropPieces(listOf(left, right, middle)))
    }

    @Test
    fun equalVerticalColumnsUseTopToBottomTieBreak() {
        val lower = CropBlockPiece("下", centerX = 100, top = 80, vertical = true)
        val upper = CropBlockPiece("上", centerX = 100, top = 10, vertical = true)

        assertEquals("上 下", assembleCropPieces(listOf(lower, upper)))
    }

    @Test
    fun exactlyHalfVerticalKeepsMlKitOrder() {
        val first = CropBlockPiece("first", centerX = 10, top = 0, vertical = true)
        val second = CropBlockPiece("second", centerX = 100, top = 0, vertical = false)

        assertEquals("first second", assembleCropPieces(listOf(first, second)))
    }

    @Test
    fun horizontalMajorityKeepsMlKitOrder() {
        val pieces = listOf(
            CropBlockPiece("A", 5, 30, false),
            CropBlockPiece("B", 100, 10, true),
            CropBlockPiece("C", 50, 20, false),
        )

        assertEquals("A B C", assembleCropPieces(pieces))
    }

    @Test
    fun distinctBlocksAreSpaceSeparatedAndTrimmed() {
        val pieces = listOf(
            CropBlockPiece("  猫", 0, 0, false),
            CropBlockPiece("犬  ", 0, 0, false),
        )

        assertEquals("猫 犬", assembleCropPieces(pieces))
    }

    @Test
    fun emptyInputProducesEmptyText() {
        assertEquals("", assembleCropPieces(emptyList()))
    }
}
