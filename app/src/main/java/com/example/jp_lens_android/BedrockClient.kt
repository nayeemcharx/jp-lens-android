package com.example.jp_lens_android

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal AWS Bedrock Converse client authenticated with a long-lived
 * AWS_BEARER_TOKEN_BEDROCK (no SigV4 signing required).
 */
object BedrockClient {

    private const val TAG = "JpLens.Bedrock"
    private const val REGION = "us-east-1"
    // OpenAI gpt-oss-120b on Bedrock. Reasoning effort is forced to "low"
    // below via additionalModelRequestFields so we get a quick direct answer
    // instead of long chain-of-thought tokens.
    private const val MODEL_ID = "openai.gpt-oss-120b-1:0"

    private const val CACHE_CAP = 20

    /** One row of the Word-by-word section, parsed for downstream UI (e.g. Anki "+" buttons). */
    data class WordEntry(
        val word: String,
        val reading: String, // hiragana reading; empty when no kanji or none provided
        val meaning: String,
        val jlpt: String,    // e.g. "N5"; empty when unknown
    )

    data class Analysis(
        val wordByWord: String,
        val words: List<WordEntry>,
        val kanji: String,
        val translation: String,
        val notes: String,
        val raw: String,
    )

    // Access-ordered LRU keyed by the input sentence. `removeEldestEntry`
    // trims to CACHE_CAP on each insert; only successful responses are cached.
    private val cache = object : LinkedHashMap<String, Analysis>(CACHE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Analysis>): Boolean =
            size > CACHE_CAP
    }

    /** Blocking; call off the main thread. */
    @Throws(IOException::class)
    fun analyzeJapanese(sentence: String, bearerToken: String): Analysis {
        require(sentence.isNotBlank()) { "sentence is blank" }
        require(bearerToken.isNotBlank()) { "missing AWS_BEARER_TOKEN_BEDROCK" }

        val cacheKey = sentence.trim()
        synchronized(cache) {
            cache[cacheKey]?.let {
                Log.i(TAG, "cache hit for \"${cacheKey.take(40)}\"")
                return it
            }
        }

        val prompt = buildPrompt(sentence)
        val body = JSONObject().apply {
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().put("text", prompt)))
                }
            ))
            put("inferenceConfig", JSONObject().apply {
                // gpt-oss is designed for temperature=1.0; lower values can
                // degrade quality. maxTokens budget covers both the hidden
                // reasoning channel and the visible answer.
                put("maxTokens", 3000)
                put("temperature", 1.0)
            })
            // gpt-oss accepts a reasoning_effort knob; "low" keeps the model
            // from spending most of its tokens on hidden chain-of-thought.
            put("additionalModelRequestFields", JSONObject().apply {
                put("reasoning_effort", "low")
            })
        }.toString()

        // Path-safe encoding: colon in model id stays as-is (RFC 3986 sub-delim ok in path).
        val url = URL("https://bedrock-runtime.$REGION.amazonaws.com/model/$MODEL_ID/converse")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.e(TAG, "HTTP $code: ${text.take(400)}")
                throw IOException("Bedrock HTTP $code: ${text.take(200)}")
            }
            val raw = extractAssistantText(text)
            val analysis = parseSections(raw)
            synchronized(cache) { cache[cacheKey] = analysis }
            return analysis
        } finally {
            conn.disconnect()
        }
    }

    private fun buildPrompt(sentence: String): String = """
You are a Japanese language tutor. Analyze the Japanese sentence below.
Respond in plain text using EXACTLY these four section headers (and nothing before "## Word-by-word"):

## Word-by-word
List each meaningful word on its own line in EXACTLY this format, using the em dash "—" (U+2014) as the separator:

word — hiragana — English meaning — [JLPT level]

Rules:
- The hiragana field is the reading of the word. If the word has no kanji (already kana-only), put a single dash "-" in the hiragana field.
- JLPT level is one of N5, N4, N3, N2, N1. If you are not confident, put "?". Always wrap it in square brackets.
- Do not add bullets, numbers, bold, or any extra punctuation around the fields.

Examples:
食べる — たべる — to eat — [N5]
これ — - — this — [N5]

## Kanji
List every distinct kanji character that appears in the sentence, one per line, in EXACTLY this format using the em dash "—" (U+2014) as the separator:

kanji — brief meaning/explanation — [JLPT level]

JLPT level is one of N5, N4, N3, N2, N1; if unsure put "?". Always wrap it in square brackets.
If the sentence contains no kanji, write exactly: (none)

## Translation
A single natural English translation of the full sentence.

## Notes
Any nuances, notable phrases, grammar patterns, idioms, cultural context,
register/politeness level, common-mistake warnings, or other things a Japanese
learner should know about this specific sentence. Include examples where helpful.
If nothing notable, write exactly: (none)

Sentence: $sentence
""".trimIndent()

    /** Pulls concatenated assistant text out of a Converse response, skipping reasoning blocks. */
    private fun extractAssistantText(body: String): String {
        return try {
            val root = JSONObject(body)
            val msg = root.optJSONObject("output")?.optJSONObject("message") ?: return ""
            val content = msg.optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                val t = block.optString("text", "")
                if (t.isNotEmpty()) sb.append(t)
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${body.take(200)}", e)
            ""
        }
    }

    private fun parseSections(raw: String): Analysis {
        if (raw.isBlank()) return Analysis("", emptyList(), "", "", "", raw)
        val wbw = extractSection(raw, "Word-by-word", listOf("Kanji", "Translation", "Notes"))
        val kanji = extractSection(raw, "Kanji", listOf("Translation", "Notes"))
        val tr = extractSection(raw, "Translation", listOf("Notes"))
        val nt = extractSection(raw, "Notes", emptyList())
        val wbwText = wbw.ifBlank { raw }
        return Analysis(
            wordByWord = wbwText,
            words = parseWordEntries(wbwText),
            kanji = kanji,
            translation = tr,
            notes = nt,
            raw = raw,
        )
    }

    /**
     * Splits each line of the Word-by-word section on em dashes:
     *   word — hiragana — meaning — [JLPT]
     * Lines that don't have at least the first three fields are skipped.
     */
    fun parseWordEntries(section: String): List<WordEntry> {
        if (section.isBlank()) return emptyList()
        val out = ArrayList<WordEntry>()
        for (rawLine in section.lineSequence()) {
            val line = rawLine.trim().trimStart('-', '*', '•', ' ').trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('—').map { it.trim() }
            if (parts.size < 3) continue
            val word = parts[0]
            // "-" in the reading slot means "no kanji, no separate reading".
            val reading = parts[1].let { if (it == "-") "" else it }
            val meaning = parts[2]
            val jlpt = if (parts.size >= 4) parts[3].trim('[', ']', ' ') else ""
            if (word.isEmpty() || meaning.isEmpty()) continue
            out += WordEntry(word, reading, meaning, jlpt)
        }
        return out
    }

    private fun extractSection(raw: String, header: String, nextHeaders: List<String>): String {
        val startMarker = Regex("(?m)^\\s*##\\s*$header\\s*$")
        val startMatch = startMarker.find(raw) ?: return ""
        val from = startMatch.range.last + 1
        var to = raw.length
        for (next in nextHeaders) {
            val nextMatch = Regex("(?m)^\\s*##\\s*$next\\s*$").find(raw, from)
            if (nextMatch != null && nextMatch.range.first < to) {
                to = nextMatch.range.first
            }
        }
        return raw.substring(from, to).trim()
    }
}
