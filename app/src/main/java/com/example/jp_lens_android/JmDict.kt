package com.example.jp_lens_android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Offline dictionary lookups for [OverlayService.MODE_SENTENCE_DICT].
 *
 * Backed by `assets/jmdict.db`, a SQLite file built from scriptin/jmdict-simplified
 * (full English JMdict + English KANJIDIC2) by `scripts/build_jmdict_db.py`. That
 * asset is NOT committed — the app won't return any results in dict mode until the
 * script has been run once. See CLAUDE.md.
 *
 * Schema (see the build script):
 *   entries(key TEXT, reading TEXT, gloss TEXT, common INTEGER)   -- indexed on key
 *   kanji(literal TEXT PRIMARY KEY, meanings TEXT, jlpt TEXT)
 *   meta(version TEXT)
 *
 * SQLite can't open a file straight out of the APK's compressed assets, so on first
 * use we copy it to internal storage and open it read-only.
 */
object JmDict {

    private const val TAG = "JpLens.JmDict"
    private const val ASSET_NAME = "jmdict.db"
    private const val DB_NAME = "jmdict.db"
    // Bump this whenever a regenerated jmdict.db should replace an already-copied
    // one across an in-place app update (a fresh install copies regardless).
    // v2 added the detail/tags tables + entries.word_id for the expandable word view.
    private const val DB_ASSET_VERSION = 2
    private const val PREF_COPIED_VERSION = "jmdict_db_copied_version"

    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var triedOpen = false
    // code -> human label (e.g. "v1" -> "Ichidan verb"); loaded once in warmUp.
    @Volatile private var tags: Map<String, String> = emptyMap()

    data class WordInfo(val gloss: String)
    data class KanjiInfo(val meaning: String, val jlpt: String)

    /** Full JMdict entry for the expandable word panel. */
    data class WordDetail(
        val writings: List<Writing>,
        val readings: List<Reading>,
        val senses: List<Sense>,
    )
    data class Writing(val text: String, val common: Boolean, val tags: List<String>)
    data class Reading(
        val text: String,
        val common: Boolean,
        val tags: List<String>,
        val appliesToKanji: List<String>,
    )
    data class Sense(
        val partOfSpeech: List<String>,
        val fields: List<String>,
        val misc: List<String>,
        val dialects: List<String>,
        val info: List<String>,
        val related: List<String>,
        val antonyms: List<String>,
        val langSources: List<LangSource>,
        val glosses: List<String>,
    )
    data class LangSource(val lang: String, val text: String?, val wasei: Boolean)

    /** True once the DB asset has been copied and opened successfully. */
    fun isAvailable(): Boolean = db != null

    /**
     * Copy (if needed) + open the DB. Idempotent; safe to call repeatedly. Call off
     * the main thread (like [JapaneseTokenizer.warmUp]) — the first call does file I/O.
     */
    @Synchronized
    fun warmUp(context: Context) {
        if (db != null || triedOpen) return
        triedOpen = true
        try {
            val target = ensureCopied(context)
            val opened = SQLiteDatabase.openDatabase(
                target.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            tags = loadTags(opened)
            db = opened
            Log.i(TAG, "opened ${target.absolutePath} (${tags.size} tag labels)")
        } catch (t: Throwable) {
            Log.e(TAG, "DB unavailable — run scripts/build_jmdict_db.py to create assets/$ASSET_NAME", t)
            db = null
        }
    }

    private fun ensureCopied(context: Context): File {
        val target = context.getDatabasePath(DB_NAME)
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val copiedVersion = prefs.getInt(PREF_COPIED_VERSION, -1)
        if (target.exists() && copiedVersion == DB_ASSET_VERSION) return target

        target.parentFile?.mkdirs()
        context.assets.open(ASSET_NAME).use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        prefs.edit().putInt(PREF_COPIED_VERSION, DB_ASSET_VERSION).apply()
        Log.i(TAG, "copied asset $ASSET_NAME -> ${target.absolutePath} (${target.length()} bytes)")
        return target
    }

    /** Best entry for a Kuromoji dictionary-form word, preferring "common" entries. */
    fun lookupWord(base: String): WordInfo? {
        val d = db ?: return null
        if (base.isBlank()) return null
        return d.rawQuery(
            "SELECT gloss FROM entries WHERE key = ? ORDER BY common DESC LIMIT 1",
            arrayOf(base)
        ).use { c ->
            if (c.moveToFirst()) WordInfo(c.getString(0)) else null
        }
    }

    /** KANJIDIC2 meaning + (legacy-mapped) JLPT for a single kanji character. */
    fun lookupKanji(ch: Char): KanjiInfo? {
        val d = db ?: return null
        return d.rawQuery(
            "SELECT meanings, jlpt FROM kanji WHERE literal = ?",
            arrayOf(ch.toString())
        ).use { c ->
            if (c.moveToFirst()) KanjiInfo(c.getString(0), c.getString(1) ?: "") else null
        }
    }

    /** Human-readable label for a JMdict tag code, falling back to the code itself. */
    fun tagLabel(code: String): String = tags[code] ?: code

    /**
     * Every full JMdict entry whose kanji/kana headword equals [base] (homographs
     * included), common entries first. Each blob is gzip-compressed JSON; decompress,
     * parse and map. Returns empty when [base] isn't in JMdict or the DB is missing.
     */
    fun lookupWordDetail(base: String): List<WordDetail> {
        val d = db ?: return emptyList()
        if (base.isBlank()) return emptyList()
        val out = ArrayList<WordDetail>()
        d.rawQuery(
            "SELECT DISTINCT d.json FROM entries e JOIN detail d ON e.word_id = d.word_id " +
                "WHERE e.key = ? ORDER BY e.common DESC",
            arrayOf(base)
        ).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(0) ?: continue
                val json = runCatching { gunzip(blob) }.getOrNull() ?: continue
                runCatching { parseDetail(json) }.getOrNull()?.let { out += it }
            }
        }
        return out
    }

    private fun loadTags(d: SQLiteDatabase): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            d.rawQuery("SELECT code, label FROM tags", null).use { c ->
                while (c.moveToNext()) map[c.getString(0)] = c.getString(1) ?: ""
            }
        }
        return map
    }

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    private fun parseDetail(json: String): WordDetail {
        val o = JSONObject(json)
        val writings = o.optJSONArray("k").mapObjects { obj ->
            Writing(obj.optString("t"), obj.optInt("c") == 1, obj.strList("tags"))
        }
        val readings = o.optJSONArray("r").mapObjects { obj ->
            Reading(
                obj.optString("t"), obj.optInt("c") == 1,
                obj.strList("tags"), obj.strList("ak")
            )
        }
        val senses = o.optJSONArray("s").mapObjects { obj ->
            val ls = obj.optJSONArray("ls").mapObjects { l ->
                LangSource(
                    l.optString("lang"),
                    if (l.isNull("text")) null else l.optString("text"),
                    l.optBoolean("wasei"),
                )
            }
            Sense(
                partOfSpeech = obj.strList("pos"),
                fields = obj.strList("field"),
                misc = obj.strList("misc"),
                dialects = obj.strList("dialect"),
                info = obj.strList("info"),
                related = obj.strList("xref"),
                antonyms = obj.strList("ant"),
                langSources = ls,
                glosses = obj.strList("g"),
            )
        }
        return WordDetail(writings, readings, senses)
    }

    private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out += f(it) }
        return out
    }

    private fun JSONObject.strList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }
}
