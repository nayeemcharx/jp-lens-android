package com.nayeemcharx.jplens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM unit tests for [JapaneseTokenizer].
 *
 * These run with `./gradlew :app:testDebugUnitTest` — no device needed. Kuromoji IPADIC
 * ships its dictionary inside the JAR (a plain `implementation` dependency), so the real
 * tokenizer runs on the host JVM; nothing here touches Android framework classes
 * ([JapaneseTokenizer.warmUp]'s Context param is never invoked).
 *
 * Private helpers ([JapaneseTokenizer] `kanaToRomaji` / `keep`) are covered indirectly
 * through the public entry points, so the tests require no changes to the main code.
 *
 * Assertions favour *invariants* and substring/property checks over brittle full-string
 * equality wherever Kuromoji's exact token boundaries could shift between dictionary
 * revisions; the intent (particle は→wa, sokuon doubling, long-vowel, digraphs, offset
 * correctness, punctuation/Latin filtering) is what's pinned.
 */
class JapaneseTokenizerTest {

    // --- containsJapanese ---------------------------------------------------

    @Test
    fun containsJapanese_detectsEachScript() {
        assertTrue("hiragana", JapaneseTokenizer.containsJapanese("あ"))
        assertTrue("katakana", JapaneseTokenizer.containsJapanese("ア"))
        assertTrue("kanji", JapaneseTokenizer.containsJapanese("猫"))
        assertTrue("half-width katakana", JapaneseTokenizer.containsJapanese("ｱ"))
        assertTrue("mixed with Latin", JapaneseTokenizer.containsJapanese("HP回復"))
    }

    @Test
    fun containsJapanese_falseForNonJapanese() {
        assertFalse(JapaneseTokenizer.containsJapanese("Hello, world!"))
        assertFalse(JapaneseTokenizer.containsJapanese("12345"))
        assertFalse(JapaneseTokenizer.containsJapanese("!?.,"))
        assertFalse(JapaneseTokenizer.containsJapanese(""))
    }

    // --- containsKanji ------------------------------------------------------

    @Test
    fun containsKanji_onlyForIdeographs() {
        assertTrue(JapaneseTokenizer.containsKanji("日本語"))
        assertTrue(JapaneseTokenizer.containsKanji("食べる")) // 食 is kanji
        assertFalse("kana only", JapaneseTokenizer.containsKanji("ひらがな"))
        assertFalse("katakana only", JapaneseTokenizer.containsKanji("カタカナ"))
        assertFalse(JapaneseTokenizer.containsKanji("abc"))
    }

    // --- katakanaToHiragana -------------------------------------------------

    @Test
    fun katakanaToHiragana_convertsAndLeavesOthers() {
        assertEquals("にほん", JapaneseTokenizer.katakanaToHiragana("ニホン"))
        // The long-vowel mark ー is outside the katakana block and must pass through.
        assertEquals("こーひー", JapaneseTokenizer.katakanaToHiragana("コーヒー"))
        // Non-katakana characters are untouched.
        assertEquals("ひらがなabc", JapaneseTokenizer.katakanaToHiragana("ひらがなabc"))
    }

    // --- extract: filtering -------------------------------------------------

    @Test
    fun extract_blankOrPunctuationOnly_isEmpty() {
        assertTrue(JapaneseTokenizer.extract("").isEmpty())
        assertTrue(JapaneseTokenizer.extract("   ").isEmpty())
        assertTrue("pure punctuation", JapaneseTokenizer.extract("。、！？「」").isEmpty())
    }

    @Test
    fun extract_dropsPureLatinAndDigits() {
        assertTrue(JapaneseTokenizer.extract("ABC 123 xyz").isEmpty())
    }

    @Test
    fun extract_singleKanjiNoun() {
        val morphemes = JapaneseTokenizer.extract("猫")
        assertEquals(1, morphemes.size)
        val m = morphemes[0]
        assertEquals("猫", m.surface)
        assertEquals("猫", m.base)
        assertEquals(0, m.start)
        assertEquals(1, m.end)
        assertTrue("expected a 名詞 POS but was ${m.pos}", m.pos.contains("名詞"))
    }

    /**
     * The char offsets are the load-bearing contract — the overlay maps each morpheme's
     * [start,end) back onto pixel rects. This pins the invariant (surface == the slice it
     * points at; ascending, non-overlapping, in-bounds) without hard-coding Kuromoji's
     * exact split of the sentence.
     */
    @Test
    fun extract_offsetsAreConsistentAndOrdered() {
        val text = "猫が好きです"
        val morphemes = JapaneseTokenizer.extract(text)
        assertTrue("expected some morphemes", morphemes.isNotEmpty())
        var prevEnd = 0
        for (m in morphemes) {
            assertTrue("start in bounds", m.start in 0..text.length)
            assertTrue("end in bounds", m.end in 0..text.length)
            assertTrue("start < end", m.start < m.end)
            assertEquals("surface must equal its slice", text.substring(m.start, m.end), m.surface)
            assertTrue("morphemes must not overlap / must be ordered", m.start >= prevEnd)
            prevEnd = m.end
        }
    }

    @Test
    fun extract_keptMorphemesAllContainJapanese() {
        for (m in JapaneseTokenizer.extract("彼はUIを見て、OKと言った。")) {
            assertTrue("kept morpheme '${m.surface}' should contain Japanese",
                JapaneseTokenizer.containsJapanese(m.surface))
        }
    }

    // --- fullReadingHiragana ------------------------------------------------

    @Test
    fun fullReadingHiragana_isAllKana() {
        // 猫 has an unambiguous reading (ネコ); avoid 日本, which IPADIC may read にっぽん.
        assertEquals("ねこ", JapaneseTokenizer.fullReadingHiragana("猫"))
        val reading = JapaneseTokenizer.fullReadingHiragana("猫が好き")
        assertTrue(reading.isNotEmpty())
        // Result should carry no katakana (readings are normalised to hiragana).
        assertFalse("reading should not contain katakana: $reading",
            reading.any { it.code in 0x30A1..0x30F6 })
    }

    @Test
    fun fullReadingHiragana_blankIsEmpty() {
        assertEquals("", JapaneseTokenizer.fullReadingHiragana(""))
    }

    // --- fullRomaji ---------------------------------------------------------

    @Test
    fun fullRomaji_particleWaUsesPronunciation() {
        // は as a topic particle is pronounced わ → must romanise to "wa", not "ha".
        val r = JapaneseTokenizer.fullRomaji("これは")
        assertTrue("expected romaji to start with 'kore' but was '$r'", r.startsWith("kore"))
        assertTrue("expected 'wa' from the topic particle but was '$r'", r.contains("wa"))
        assertFalse("particle は must not romanise to 'ha' here: '$r'", r.contains("ha"))
    }

    @Test
    fun fullRomaji_longVowelMarkLengthensVowel() {
        // サッカー → sa + (sokuon)kka + (long a) => contains a doubled consonant and a long vowel.
        val r = JapaneseTokenizer.fullRomaji("サッカー")
        assertTrue("sokuon should double the consonant: '$r'", r.contains("kk"))
        assertTrue("ー should lengthen the vowel: '$r'", r.contains("aa"))
    }

    @Test
    fun fullRomaji_digraph() {
        // キャンプ → kya n pu
        val r = JapaneseTokenizer.fullRomaji("キャンプ")
        assertTrue("digraph キャ should be 'kya': '$r'", r.contains("kya"))
    }

    @Test
    fun fullRomaji_blankIsEmpty() {
        assertEquals("", JapaneseTokenizer.fullRomaji(""))
    }

    // --- isAvailable --------------------------------------------------------

    @Test
    fun isAvailable_alwaysTrue() {
        // Kuromoji's dictionary is bundled in the JAR — the tokenizer never needs an asset.
        assertTrue(JapaneseTokenizer.isAvailable())
    }
}
