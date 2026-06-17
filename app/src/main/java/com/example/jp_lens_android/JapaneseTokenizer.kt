package com.example.jp_lens_android

import android.content.Context
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Thin wrapper around Kuromoji IPADIC.
 *
 * Kuromoji ships its dictionary *inside the JAR*, so there's no asset to bundle or copy
 * and no `java.nio.file` (so `minSdk` can stay at 24) — that's the reason we use it over
 * Sudachi.
 *
 * Filter is a "contains a Japanese character" gate plus a small symbol/other POS blacklist,
 * which drops punctuation, whitespace and stray Latin/digits — keeping nouns, verbs,
 * adjectives, adverbs, particles, etc. with their dictionary form.
 *
 * The public surface ([warmUp], [isAvailable], [extract], [fullReadingHiragana],
 * [katakanaToHiragana], [containsKanji], [Morpheme]) matches what the rest of the app
 * calls. [warmUp] takes a [Context] only for call-site symmetry with [JmDict.warmUp];
 * Kuromoji ignores it.
 */
object JapaneseTokenizer {

    data class Morpheme(
        val surface: String,    // as it appeared in the text
        val base: String,       // dictionary form
        val pos: String,        // top-level part of speech (e.g. 名詞)
        val reading: String,    // katakana reading, or "" if unknown
        val start: Int,         // char offset in the input string
        val end: Int            // exclusive char offset
    )

    private val tokenizer: Tokenizer by lazy { Tokenizer() }

    private val dropPos = setOf("その他")

    /** Kuromoji's dictionary is bundled in the JAR, so it's always available. */
    fun isAvailable(): Boolean = true

    /**
     * Force the dictionary to load off the hot path. The [context] is unused (kept so the
     * call site matches [JmDict.warmUp]); call this off the main thread on first use.
     */
    @Suppress("UNUSED_PARAMETER")
    fun warmUp(context: Context) {
        tokenizer.tokenize("起動")
    }

    fun extract(text: String): List<Morpheme> {
        if (text.isBlank()) return emptyList()
        return tokenizer.tokenize(text)
            .filter { keep(it) }
            .map { it.toMorpheme() }
    }

    private fun keep(t: Token): Boolean {
        if (t.partOfSpeechLevel1 in dropPos) return false
        // Skip lone ASCII (spaces, punctuation that slip through, single Latin letters).
        if (t.surface.length == 1 && t.surface[0].code < 0x80) return false
        // Drop tokens that contain no Japanese characters at all (pure digits / latin / etc.).
        if (!containsJapanese(t.surface)) return false
        return true
    }

    /** True if [s] contains at least one hiragana, katakana, or kanji character. */
    private fun containsJapanese(s: String): Boolean {
        for (c in s) {
            val code = c.code
            if (code in 0x3040..0x309F) return true   // Hiragana
            if (code in 0x30A0..0x30FF) return true   // Katakana
            if (code in 0x4E00..0x9FFF) return true   // CJK Unified Ideographs (kanji)
            if (code in 0x3400..0x4DBF) return true   // CJK Extension A
            if (code in 0xFF66..0xFF9F) return true   // Half-width katakana
        }
        return false
    }

    /** Returns true if the string contains at least one CJK ideograph (kanji). */
    fun containsKanji(s: String): Boolean {
        for (c in s) {
            val code = c.code
            if (code in 0x4E00..0x9FFF) return true   // CJK Unified Ideographs
            if (code in 0x3400..0x4DBF) return true   // CJK Extension A
        }
        return false
    }

    /**
     * Re-tokenizes [text] without filtering and concatenates each token's reading
     * (converted to hiragana). Used for the range popup, where we want a full
     * furigana-like rendering including particles and auxiliaries.
     */
    fun fullReadingHiragana(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder()
        for (t in tokenizer.tokenize(text)) {
            val r = t.reading
            if (r.isNullOrEmpty() || r == "*") sb.append(t.surface)
            else sb.append(katakanaToHiragana(r))
        }
        return sb.toString()
    }

    /** Converts katakana characters to hiragana, leaves other chars untouched. */
    fun katakanaToHiragana(katakana: String): String {
        val sb = StringBuilder(katakana.length)
        for (c in katakana) {
            val code = c.code
            if (code in 0x30A1..0x30F6) sb.append((code - 0x60).toChar())
            else sb.append(c)
        }
        return sb.toString()
    }

    private fun Token.toMorpheme(): Morpheme {
        val base = if (baseForm.isNullOrEmpty() || baseForm == "*") surface else baseForm
        val rd = if (reading.isNullOrEmpty() || reading == "*") "" else reading
        return Morpheme(
            surface = surface,
            base = base,
            pos = partOfSpeechLevel1,
            reading = rd,
            start = position,
            end = position + surface.length
        )
    }
}
