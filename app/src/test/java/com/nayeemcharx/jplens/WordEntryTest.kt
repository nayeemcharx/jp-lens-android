package com.nayeemcharx.jplens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Host-JVM unit tests for the [WordEntry] data class — the payload behind a tapped
 * word's detail-card header and the Anki "+" button. Pure Kotlin, no Android.
 */
class WordEntryTest {

    @Test
    fun surface_defaultsToEmpty() {
        val e = WordEntry(word = "猫", reading = "ねこ", meaning = "cat", jlpt = "N5")
        assertEquals("", e.surface)
    }

    @Test
    fun equality_and_copy() {
        val a = WordEntry("食べる", "たべる", "to eat", "N5", surface = "食べた")
        val b = a.copy()
        assertEquals(a, b)

        val c = a.copy(surface = "食べます")
        assertNotEquals(a, c)
        // copy() leaves the other fields intact.
        assertEquals(a.word, c.word)
        assertEquals(a.reading, c.reading)
        assertEquals(a.meaning, c.meaning)
        assertEquals(a.jlpt, c.jlpt)
    }

    @Test
    fun holdsAllFields() {
        val e = WordEntry("行く", "いく", "to go", "N5", surface = "行った")
        assertEquals("行く", e.word)
        assertEquals("いく", e.reading)
        assertEquals("to go", e.meaning)
        assertEquals("N5", e.jlpt)
        assertEquals("行った", e.surface)
    }
}
