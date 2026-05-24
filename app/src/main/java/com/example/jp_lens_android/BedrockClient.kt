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

    data class Analysis(
        val wordByWord: String,
        val phrases: String,
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
List each meaningful word on its own line. For any word containing kanji,
include the hiragana reading in parentheses immediately after the word.
Format: word (hiragana) —> English meaning
If a word has no kanji, omit the parentheses: word —> English meaning

## Phrases
Explain notable combined phrases, grammar patterns, or idioms in the sentence with examples.
If nothing notable, write exactly: (none)

## Translation
A single natural English translation of the full sentence.

## Notes
Any nuances, cultural context, register/politeness level, common-mistake warnings,
or other things a Japanese learner should know about this specific sentence.
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
        if (raw.isBlank()) return Analysis("", "", "", "", raw)
        val wbw = extractSection(raw, "Word-by-word", listOf("Phrases", "Translation", "Notes"))
        val phr = extractSection(raw, "Phrases", listOf("Translation", "Notes"))
        val tr = extractSection(raw, "Translation", listOf("Notes"))
        val nt = extractSection(raw, "Notes", emptyList())
        return Analysis(
            wordByWord = wbw.ifBlank { raw },
            phrases = phr,
            translation = tr,
            notes = nt,
            raw = raw,
        )
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
