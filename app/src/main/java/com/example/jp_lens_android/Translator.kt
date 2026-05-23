package com.example.jp_lens_android

import android.util.Log
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin wrapper around Google Translate's unofficial public endpoint.
 *
 * It's an unauthenticated endpoint that has been used by mobile/web tools for years.
 * Not an official Google product API — it can be rate-limited if hit aggressively,
 * but word-level lookups are fine.
 */
object Translator {

    private const val TAG = "JpLens.Translator"

    /** Blocking call. MUST be invoked off the main thread. */
    @Throws(IOException::class)
    fun translateJaToEn(text: String): String {
        if (text.isBlank()) return ""
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = URL(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=ja&tl=en&dt=t&q=$encoded"
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) JpLens/0.1")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Response shape: [ [ ["translation","source",null,null,1], ... ], null, "ja", ... ]
     * Concatenate every segment's [0] element.
     */
    private fun parse(body: String): String {
        return try {
            val root = JSONArray(body)
            if (root.length() == 0 || root.isNull(0)) return ""
            val segments = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i) ?: continue
                if (seg.length() > 0 && !seg.isNull(0)) sb.append(seg.getString(0))
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${body.take(200)}", e)
            ""
        }
    }
}
