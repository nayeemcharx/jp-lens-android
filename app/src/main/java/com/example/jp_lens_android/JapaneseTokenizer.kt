package com.example.jp_lens_android

import android.content.Context
import android.util.Log
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Tokenizer as SudachiTokenizer
import com.worksap.nlp.sudachi.Morpheme as SudachiMorpheme
import java.io.File
import java.nio.file.Paths

/**
 * Thin wrapper around the Sudachi morphological analyzer (replacing Kuromoji IPADIC).
 *
 * Sudachi offers three split granularities — A (shortest), B (middle), C (longest /
 * named-entity). We segment morpheme boxes with [SudachiTokenizer.SplitMode.C] so each
 * box is the *biggest meaningful unit* (e.g. 東京都, 関西国際空港 stay whole), and produce
 * furigana with the finest mode A so every kana/particle gets its own reading.
 *
 * Filter is a small blacklist of grammatical-glue / symbol POS plus a "contains a
 * Japanese character" gate, which together drop punctuation, whitespace and stray
 * Latin/digits — keeping nouns, verbs, adjectives, adverbs, etc. with their dictionary
 * form.
 *
 * Sudachi needs its system dictionary (`system_full.dic`) on the filesystem, so — like
 * [JmDict] — [warmUp] copies `assets/system_full.dic` to internal storage on first use.
 * That asset is NOT committed (~140 MB); build it once with
 * `python3 scripts/build_sudachi_dict.py`. Until then [extract] returns nothing and
 * morpheme mode shows no boxes (see CLAUDE.md).
 */
object JapaneseTokenizer {

    private const val TAG = "JpLens.Tokenizer"
    private const val ASSET_NAME = "system_full.dic"
    private const val DICT_NAME = "system_full.dic"
    // Bump when a regenerated dictionary should replace an already-copied one across an
    // in-place app update (a fresh install copies regardless).
    // v2: switched the bundled dict to the SudachiDict *small* edition (~123 MB vs
    // ~360 MB full) to keep the APK small — see scripts/build_sudachi_dict.py --lite.
    private const val DICT_ASSET_VERSION = 2
    private const val PREF_COPIED_VERSION = "sudachi_dict_copied_version"

    data class Morpheme(
        val surface: String,    // as it appeared in the text
        val base: String,       // normalized dictionary form (Sudachi normalizedForm)
        val pos: String,        // top-level part of speech (e.g. 名詞)
        val reading: String,    // katakana reading, or "" if unknown
        val start: Int,         // char offset in the input string
        val end: Int            // exclusive char offset
    )

    @Volatile private var dictionary: Dictionary? = null
    @Volatile private var tokenizer: SudachiTokenizer? = null
    @Volatile private var triedOpen = false

    // POS level-1 categories that are never useful as their own box.
    private val dropPos = setOf("補助記号", "空白", "記号", "その他")

    /** True once the dictionary asset has been copied and opened successfully. */
    fun isAvailable(): Boolean = tokenizer != null

    /**
     * Copy (if needed) + open the Sudachi system dictionary. Idempotent; safe to call
     * repeatedly. Call off the main thread — the first call copies a ~140 MB asset.
     */
    @Synchronized
    fun warmUp(context: Context) {
        if (tokenizer != null || triedOpen) return
        triedOpen = true
        try {
            val dic = ensureCopied(context)
            val config = Config.defaultConfig().systemDictionary(Paths.get(dic.absolutePath))
            val dict = DictionaryFactory().create(config)
            dictionary = dict
            tokenizer = dict.create()
            // Force the lattice/plugins to initialize off the hot path.
            tokenizer?.tokenize(SudachiTokenizer.SplitMode.C, "起動")
            Log.i(TAG, "opened ${dic.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Sudachi unavailable — run scripts/build_sudachi_dict.py to create assets/$ASSET_NAME", t)
            tokenizer = null
            dictionary = null
        }
    }

    private fun ensureCopied(context: Context): File {
        val target = File(context.filesDir, DICT_NAME)
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val copiedVersion = prefs.getInt(PREF_COPIED_VERSION, -1)
        if (target.exists() && copiedVersion == DICT_ASSET_VERSION) return target

        context.assets.open(ASSET_NAME).use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        prefs.edit().putInt(PREF_COPIED_VERSION, DICT_ASSET_VERSION).apply()
        Log.i(TAG, "copied asset $ASSET_NAME -> ${target.absolutePath} (${target.length()} bytes)")
        return target
    }

    fun extract(text: String): List<Morpheme> {
        if (text.isBlank()) return emptyList()
        val tk = tokenizer ?: return emptyList()
        return tk.tokenize(SudachiTokenizer.SplitMode.C, text)
            .filter { keep(it) }
            .map { it.toMorpheme() }
    }

    private fun keep(m: SudachiMorpheme): Boolean {
        if (m.partOfSpeech().firstOrNull() in dropPos) return false
        val surface = m.surface()
        // Skip lone ASCII (spaces, punctuation that slip through, single Latin letters).
        if (surface.length == 1 && surface[0].code < 0x80) return false
        // Drop tokens that contain no Japanese characters at all (pure digits / latin / etc.).
        if (!containsJapanese(surface)) return false
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
     * Re-tokenizes [text] with the finest split (mode A) and concatenates each token's
     * reading (converted to hiragana). Used for the range popup, where we want a full
     * furigana-like rendering including particles and auxiliaries.
     */
    fun fullReadingHiragana(text: String): String {
        if (text.isBlank()) return ""
        val tk = tokenizer ?: return text
        val sb = StringBuilder()
        for (m in tk.tokenize(SudachiTokenizer.SplitMode.A, text)) {
            val r = m.readingForm()
            if (r.isNullOrEmpty()) sb.append(m.surface())
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

    private fun SudachiMorpheme.toMorpheme(): Morpheme {
        // normalizedForm() canonicalizes orthographic variants (附属→付属, ヴァイオリン→
        // バイオリン, kana/okurigana variants), which raises the JMdict hit rate vs the
        // plain dictionaryForm(). Fall back to dictionary form then surface if empty.
        val norm = normalizedForm()
        val base = when {
            !norm.isNullOrEmpty() -> norm
            !dictionaryForm().isNullOrEmpty() -> dictionaryForm()
            else -> surface()
        }
        val rd = readingForm() ?: ""
        return Morpheme(
            surface = surface(),
            base = base,
            pos = partOfSpeech().firstOrNull() ?: "",
            reading = rd,
            start = begin(),
            end = end()
        )
    }
}
