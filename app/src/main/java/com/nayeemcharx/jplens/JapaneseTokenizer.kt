package com.nayeemcharx.jplens

import android.content.Context
import android.util.Log
import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * Thin wrapper around Kuromoji IPADIC.
 *
 * Kuromoji ships its dictionary *inside the JAR*, so there's no asset to bundle or copy
 * and no `java.nio.file` (so `minSdk` can stay at 24) — that's the reason we use it over
 * Sudachi.
 *
 * A small **user dictionary** ([USER_DICT_ASSET]) is layered on top so ~291 JLPT compound
 * words that plain IPADIC splits (会議室→会議+室, 誕生日→誕生+日) come out as one token — see
 * that constant for the how/why and `scripts/kuromoji_userdict/README.md` for how the set was
 * curated and over-merge-checked.
 *
 * Filter is a "contains a Japanese character" gate plus a small symbol/other POS blacklist,
 * which drops punctuation, whitespace and stray Latin/digits — keeping nouns, verbs,
 * adjectives, adverbs, particles, etc. with their dictionary form.
 *
 * The public surface ([warmUp], [isAvailable], [extract], [fullReadingHiragana],
 * [katakanaToHiragana], [containsKanji], [Morpheme]) matches what the rest of the app
 * calls. [warmUp] takes a [Context] so the user dictionary asset can be read.
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

    private const val TAG = "JpLens.Tokenizer"

    // A Kuromoji user dictionary (shipped as an asset) that forces ~291 JLPT compound
    // words to tokenize *whole* — plain IPADIC splits them (会議室→会議+室, 誕生日→誕生+日,
    // お兄さん→お+兄さん), which meant the JLPT unit could never be looked up or tapped as one
    // word. Each line is a single-segment entry `surface,surface,katakana-reading,名詞`.
    // The set is curated (see scripts/kuromoji_userdict/README.md): only JLPT words that
    // actually split AND that were verified against a 248k-sentence corpus to NOT over-merge
    // any real word (forcing e.g. 一日 whole would wrongly break 十一日→十|一日, so those are
    // deliberately excluded). Loaded once at [warmUp]; a load failure falls back to plain
    // Kuromoji so tokenization still works.
    private const val USER_DICT_ASSET = "kuromoji_userdict.csv"

    private val dropPos = setOf("その他")

    @Volatile private var appContext: Context? = null
    @Volatile private var cached: Tokenizer? = null

    /** Kuromoji's dictionary is bundled in the JAR, so it's always available. */
    fun isAvailable(): Boolean = true

    /**
     * Force the dictionary (JAR-bundled) + the JLPT user dictionary (asset) to load off the
     * hot path, capturing an app [Context] so the user dictionary can be read. Call this off
     * the main thread on first use (the app does, in `OverlayService`, well before any tap).
     */
    fun warmUp(context: Context) {
        appContext = context.applicationContext
        tokenizer().tokenize("起動")
    }

    /**
     * The shared tokenizer, built lazily with the JLPT user dictionary once an app context is
     * available. If [extract] is somehow called before [warmUp] (no context yet), a plain
     * tokenizer is returned but *not* cached, so the user-dictionary-backed one still gets
     * built on the next call once the context is set.
     */
    private fun tokenizer(): Tokenizer {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: buildTokenizer().also { if (appContext != null) cached = it }
        }
    }

    private fun buildTokenizer(): Tokenizer {
        appContext?.let { ctx ->
            try {
                ctx.assets.open(USER_DICT_ASSET).use { input ->
                    return Tokenizer.Builder().userDictionary(input).build()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Kuromoji user dictionary load failed; using plain tokenizer", t)
            }
        }
        return Tokenizer.Builder().build()
    }

    fun extract(text: String): List<Morpheme> {
        if (text.isBlank()) return emptyList()
        return tokenizer().tokenize(text)
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
    fun containsJapanese(s: String): Boolean {
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
        for (t in tokenizer().tokenize(text)) {
            val r = t.reading
            if (r.isNullOrEmpty() || r == "*") sb.append(t.surface)
            else sb.append(katakanaToHiragana(r))
        }
        return sb.toString()
    }

    /**
     * Re-tokenizes [text] and builds a **Hepburn romaji** rendering, one word per
     * token separated by spaces. Each token is transliterated from its IPADIC
     * **pronunciation** (発音) when available — that resolves particles は→wa, へ→e,
     * を→o — falling back to the dictionary reading, then the surface. Punctuation
     * and non-kana pass through. Companion to [fullReadingHiragana] for the popup's
     * Romaji section; needs no dictionary asset (Kuromoji ships its own).
     */
    fun fullRomaji(text: String): String {
        if (text.isBlank()) return ""
        val parts = ArrayList<String>()
        for (t in tokenizer().tokenize(text)) {
            val kana = when {
                !t.pronunciation.isNullOrEmpty() && t.pronunciation != "*" -> t.pronunciation
                !t.reading.isNullOrEmpty() && t.reading != "*" -> t.reading
                else -> t.surface
            }
            val r = kanaToRomaji(kana).trim()
            if (r.isEmpty()) continue
            // Attach pure-punctuation tokens to the previous word (no leading space).
            if (parts.isNotEmpty() && r.all { !it.isLetterOrDigit() })
                parts[parts.size - 1] = parts.last() + r
            else parts.add(r)
        }
        return parts.joinToString(" ")
    }

    // Katakana (and small-kana) → Hepburn. Digraphs (きゃ→kya) are matched before
    // monographs; ッ doubles the next consonant (っち→tchi), ー lengthens the prior
    // vowel, ン→n. Hiragana input is normalized to katakana first.
    private val romajiDigraphs: Map<String, String> = mapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        // Common foreign / extended combos.
        "シェ" to "she", "ジェ" to "je", "チェ" to "che",
        "ティ" to "ti", "ディ" to "di", "トゥ" to "tu", "ドゥ" to "du",
        "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo",
        "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo",
        "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo",
    )
    private val romajiMono: Map<Char, String> = mapOf(
        'ア' to "a", 'イ' to "i", 'ウ' to "u", 'エ' to "e", 'オ' to "o",
        'カ' to "ka", 'キ' to "ki", 'ク' to "ku", 'ケ' to "ke", 'コ' to "ko",
        'ガ' to "ga", 'ギ' to "gi", 'グ' to "gu", 'ゲ' to "ge", 'ゴ' to "go",
        'サ' to "sa", 'シ' to "shi", 'ス' to "su", 'セ' to "se", 'ソ' to "so",
        'ザ' to "za", 'ジ' to "ji", 'ズ' to "zu", 'ゼ' to "ze", 'ゾ' to "zo",
        'タ' to "ta", 'チ' to "chi", 'ツ' to "tsu", 'テ' to "te", 'ト' to "to",
        'ダ' to "da", 'ヂ' to "ji", 'ヅ' to "zu", 'デ' to "de", 'ド' to "do",
        'ナ' to "na", 'ニ' to "ni", 'ヌ' to "nu", 'ネ' to "ne", 'ノ' to "no",
        'ハ' to "ha", 'ヒ' to "hi", 'フ' to "fu", 'ヘ' to "he", 'ホ' to "ho",
        'バ' to "ba", 'ビ' to "bi", 'ブ' to "bu", 'ベ' to "be", 'ボ' to "bo",
        'パ' to "pa", 'ピ' to "pi", 'プ' to "pu", 'ペ' to "pe", 'ポ' to "po",
        'マ' to "ma", 'ミ' to "mi", 'ム' to "mu", 'メ' to "me", 'モ' to "mo",
        'ヤ' to "ya", 'ユ' to "yu", 'ヨ' to "yo",
        'ラ' to "ra", 'リ' to "ri", 'ル' to "ru", 'レ' to "re", 'ロ' to "ro",
        'ワ' to "wa", 'ヰ' to "i", 'ヱ' to "e", 'ヲ' to "o", 'ン' to "n",
        'ヴ' to "vu",
        // Small kana standing alone.
        'ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o",
        'ャ' to "ya", 'ュ' to "yu", 'ョ' to "yo", 'ヮ' to "wa",
        // Japanese punctuation → ASCII so the romaji reads cleanly.
        '。' to ".", '、' to ",", '！' to "!", '？' to "?", '　' to " ",
        '「' to "\"", '」' to "\"", '・' to " ",
    )

    private fun kanaToRomaji(kana: String): String {
        // Normalize hiragana → katakana so one table covers both.
        val kata = StringBuilder(kana.length)
        for (ch in kana)
            kata.append(if (ch.code in 0x3041..0x3096) (ch.code + 0x60).toChar() else ch)

        val sb = StringBuilder()
        var i = 0
        var sokuon = false
        while (i < kata.length) {
            val c = kata[i]
            when {
                c == 'ー' -> {                 // long-vowel mark: lengthen prior vowel
                    val last = sb.lastOrNull()
                    if (last != null && last in "aeiou") sb.append(last)
                    i++
                }
                c == 'ッ' -> { sokuon = true; i++ }  // small tsu: double next consonant
                else -> {
                    var romaji: String? = null
                    var consumed = 1
                    if (i + 1 < kata.length) {
                        val two = romajiDigraphs[kata.substring(i, i + 2)]
                        if (two != null) { romaji = two; consumed = 2 }
                    }
                    if (romaji == null) romaji = romajiMono[c]
                    val r = romaji
                    if (r == null) {               // not kana — pass through untouched
                        sb.append(c); sokuon = false; i++
                    } else {
                        if (sokuon) {
                            sb.append(if (r.startsWith("ch")) "t" else r[0].toString())
                            sokuon = false
                        }
                        sb.append(r)
                        i += consumed
                    }
                }
            }
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
