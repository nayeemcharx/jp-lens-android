package com.nayeemcharx.jplens

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * On-device Japanese→English translation via **FuguMT** (`staka/fugumt-ja-en`), a
 * MarianMT ja→en model exported to ONNX and run with ONNX Runtime Mobile. Fully
 * offline once the `assets/fugumt/` bundle has been built (`scripts/build_fugumt.py`)
 * and shipped in the APK — no download, no network.
 *
 * Replaces the old Google ML Kit translator (tiny multilingual model, poor on JP).
 *
 * Bundle (see the build script):
 *   - encoder.onnx / decoder.onnx   int8-quantized Marian encoder/decoder (no KV cache)
 *   - source_spm.json               SentencePiece pieces+scores → the Viterbi tokenizer
 *   - vocab.json                    Marian shared vocab (piece ↔ id), encode + decode
 *   - config.json                   special-token ids + sizes
 *
 * We reimplement SentencePiece **Unigram** (best-path Viterbi) in pure Kotlin so there's
 * no native tokenizer dependency; decode is the shared-vocab reverse + `▁`→space.
 * Greedy decoding runs the decoder without past-key-values (re-feeding the growing
 * sequence each step) — simpler and plenty fast for short on-screen lines.
 *
 * Everything here blocks (session build + encode/decode loop) → call OFF the main thread.
 * The `.onnx` files are copied to internal storage and opened by path (ORT mmaps them,
 * keeping peak memory low); the small JSON is read straight from assets.
 */
object Translator {

    private const val TAG = "JpLens.Translator"
    private const val ASSET_DIR = "fugumt"
    // Bump when a regenerated fugumt bundle should replace an already-copied one across
    // an in-place app update (a fresh install copies regardless). Mirrors JmDict's
    // DB_ASSET_VERSION / JapaneseTokenizer's DICT_ASSET_VERSION.
    private const val MODEL_ASSET_VERSION = 1
    private const val PREF_COPIED_VERSION = "fugumt_copied_version"

    private const val SPACE_MARK = '▁'   // ▁ SentencePiece meta-space
    private const val UNK_MARK = "  UNK"  // sentinel piece → unkId
    private const val MAX_SRC_TOKENS = 256
    private const val MAX_NEW_TOKENS = 256

    @Volatile private var triedInit = false
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var encoder: OrtSession? = null
    @Volatile private var decoder: OrtSession? = null

    // ── Tokenizer state (read-only after warmUp) ─────────────────────────
    private var pieceToId: HashMap<String, Int> = HashMap()
    private var idToPiece: Array<String> = emptyArray()
    private var spmScore: HashMap<String, Double> = HashMap()  // source piece → unigram score
    private var maxPieceLen = 1
    private var unkScore = -1e6
    private var eosId = 0
    private var padId = 0
    private var unkId = 0
    private var decStartId = 0
    // Token ids the model must never emit (FuguMT's generation_config bad_words_ids —
    // token 32000 = pad; it's the top logit at most steps and must be masked, or greedy
    // stalls immediately). padId is always included. Suppressed before argmax.
    private var badWords: IntArray = IntArray(0)

    /** True once the ONNX sessions + tokenizer are loaded. */
    fun isAvailable(): Boolean = encoder != null && decoder != null

    /** Cheap pre-flight for the home screen: is the asset bundle present in the APK? */
    fun assetPresent(context: Context): Boolean =
        runCatching { context.assets.open("$ASSET_DIR/config.json").close(); true }.getOrDefault(false)

    /**
     * Copy (if needed) + open the model. Idempotent; safe to call repeatedly. Call off
     * the main thread — the first call does file I/O and builds native sessions.
     */
    @Synchronized
    fun warmUp(context: Context) {
        if (triedInit) return
        triedInit = true
        try {
            loadTokenizer(context)
            val dir = ensureCopied(context)
            val e = OrtEnvironment.getEnvironment()
            val enc = e.createSession(File(dir, "encoder.onnx").absolutePath, OrtSession.SessionOptions())
            val dec = e.createSession(File(dir, "decoder.onnx").absolutePath, OrtSession.SessionOptions())
            env = e
            encoder = enc
            decoder = dec
            Log.i(TAG, "FuguMT ready (vocab=${idToPiece.size}, spm=${spmScore.size})")
        } catch (t: Throwable) {
            Log.e(TAG, "FuguMT unavailable — run scripts/build_fugumt.py to create assets/$ASSET_DIR", t)
            encoder = null
            decoder = null
        }
    }

    /**
     * Translate [text] JA→EN. Returns "" if blank or the model isn't loaded. Blocking —
     * call off the main thread.
     */
    fun translateJaToEn(text: String): String {
        if (text.isBlank()) return ""
        val e = env ?: return ""
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        return try {
            runTranslate(e, enc, dec, text)
        } catch (t: Throwable) {
            Log.e(TAG, "Translate failed", t)
            ""
        }
    }

    // ── Asset copy / load ────────────────────────────────────────────────

    private fun ensureCopied(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val copied = prefs.getInt(PREF_COPIED_VERSION, -1)
        val enc = File(dir, "encoder.onnx")
        val dec = File(dir, "decoder.onnx")
        if (enc.exists() && dec.exists() && copied == MODEL_ASSET_VERSION) return dir

        dir.mkdirs()
        copyAsset(context, "$ASSET_DIR/encoder.onnx", enc)
        copyAsset(context, "$ASSET_DIR/decoder.onnx", dec)
        prefs.edit().putInt(PREF_COPIED_VERSION, MODEL_ASSET_VERSION).apply()
        Log.i(TAG, "copied $ASSET_DIR onnx -> ${dir.absolutePath} (${enc.length() + dec.length()} bytes)")
        return dir
    }

    private fun copyAsset(context: Context, name: String, target: File) {
        context.assets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun loadTokenizer(context: Context) {
        val cfg = JSONObject(readAsset(context, "$ASSET_DIR/config.json"))
        eosId = cfg.getInt("eos_id")
        padId = cfg.getInt("pad_id")
        unkId = cfg.optInt("unk_id", padId)
        decStartId = cfg.optInt("decoder_start_id", padId)
        // bad_word_ids to mask during decoding; always suppress pad too.
        val bad = linkedSetOf(padId)
        cfg.optJSONArray("bad_word_ids")?.let { for (i in 0 until it.length()) bad.add(it.getInt(i)) }
        badWords = bad.toIntArray()

        // Shared vocab: piece → id, and the reverse for decoding.
        val vocab = JSONObject(readAsset(context, "$ASSET_DIR/vocab.json"))
        val p2i = HashMap<String, Int>(vocab.length() * 2)
        var maxId = 0
        val keys = vocab.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = vocab.getInt(k)
            p2i[k] = id
            if (id > maxId) maxId = id
        }
        val i2p = Array(maxId + 1) { "" }
        for ((k, v) in p2i) if (v in 0..maxId) i2p[v] = k
        pieceToId = p2i
        idToPiece = i2p

        // Source SentencePiece unigram: piece → score, for the Viterbi lattice.
        val spm = JSONObject(readAsset(context, "$ASSET_DIR/source_spm.json"))
        val arr = spm.getJSONArray("pieces")
        val scores = HashMap<String, Double>(arr.length() * 2)
        var maxLen = 1
        var minScore = 0.0
        for (i in 0 until arr.length()) {
            val row = arr.getJSONArray(i)
            val piece = row.getString(0)
            val score = row.getDouble(1)
            scores[piece] = score
            if (piece.length > maxLen) maxLen = piece.length
            if (score < minScore) minScore = score
        }
        spmScore = scores
        maxPieceLen = maxLen
        // Unknown-char fallback cost: strictly worse than any real piece so it's only
        // used when nothing else covers a character (keeps the lattice connected).
        unkScore = minScore - 10.0
    }

    // ── Tokenization (SentencePiece Unigram, best-path Viterbi) ───────────

    /** SentencePiece-style normalization: NFKC, spaces→▁, add a leading ▁ (dummy prefix). */
    private fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        if (nfkc.isEmpty()) return ""
        return SPACE_MARK + nfkc.replace(' ', SPACE_MARK)
    }

    /** Best-path unigram segmentation → token ids, with a trailing eos. */
    private fun encodeIds(text: String): LongArray {
        val s = normalize(text)
        val n = s.length
        if (n == 0) return longArrayOf(eosId.toLong())

        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val backStart = IntArray(n + 1) { -1 }
        val backPiece = arrayOfNulls<String>(n + 1)
        best[0] = 0.0

        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            val maxEnd = minOf(n, i + maxPieceLen)
            for (j in i + 1..maxEnd) {
                val sub = s.substring(i, j)
                val sc = spmScore[sub] ?: continue
                val cand = best[i] + sc
                if (cand > best[j]) {
                    best[j] = cand
                    backStart[j] = i
                    backPiece[j] = sub
                }
            }
            // Unknown single-codepoint step so every position stays reachable.
            val step = Character.charCount(s.codePointAt(i))
            val j = i + step
            val cand = best[i] + unkScore
            if (cand > best[j]) {
                best[j] = cand
                backStart[j] = i
                backPiece[j] = UNK_MARK
            }
        }

        val piecesRev = ArrayList<String>()
        var pos = n
        while (pos > 0) {
            val pc = backPiece[pos] ?: break
            piecesRev.add(pc)
            val prev = backStart[pos]
            if (prev < 0) break
            pos = prev
        }
        piecesRev.reverse()

        val ids = ArrayList<Long>(piecesRev.size + 1)
        for (pc in piecesRev) {
            val id = if (pc === UNK_MARK) unkId else (pieceToId[pc] ?: unkId)
            ids.add(id.toLong())
        }
        // Truncate over-long input, keeping room for eos.
        while (ids.size > MAX_SRC_TOKENS - 1) ids.removeAt(ids.size - 1)
        ids.add(eosId.toLong())
        return ids.toLongArray()
    }

    private fun detokenize(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val pc = if (id in idToPiece.indices) idToPiece[id] else ""
            if (pc.isNotEmpty()) sb.append(pc)
        }
        return sb.toString().replace(SPACE_MARK, ' ').trim()
    }

    // ── Encoder + greedy decoder (no KV cache) ────────────────────────────

    private fun runTranslate(env: OrtEnvironment, enc: OrtSession, dec: OrtSession, text: String): String {
        val srcIds = encodeIds(text)
        val mask = LongArray(srcIds.size) { 1L }

        // Encoder: input_ids + attention_mask → last_hidden_state.
        val srcTensor = OnnxTensor.createTensor(env, arrayOf(srcIds))
        val srcMaskTensor = OnnxTensor.createTensor(env, arrayOf(mask))
        val encInputs = HashMap<String, OnnxTensor>()
        for (name in enc.inputNames) {
            encInputs[name] = if (name.contains("mask")) srcMaskTensor else srcTensor
        }
        @Suppress("UNCHECKED_CAST")
        val encHidden: Array<Array<FloatArray>> = enc.run(encInputs).use { r ->
            (r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
        }
        srcTensor.close()
        srcMaskTensor.close()

        // Reusable decoder inputs: encoder hidden states + encoder mask (constant per call).
        val hiddenTensor = OnnxTensor.createTensor(env, encHidden)
        val encMaskTensor = OnnxTensor.createTensor(env, arrayOf(mask))
        val (nHidden, nEncMask, nDecIds) = resolveDecoderInputs(dec)

        val out = ArrayList<Int>()
        var cur = longArrayOf(decStartId.toLong())
        try {
            for (step in 0 until MAX_NEW_TOKENS) {
                val decIds = OnnxTensor.createTensor(env, arrayOf(cur))
                val inputs = HashMap<String, OnnxTensor>()
                inputs[nDecIds] = decIds
                inputs[nHidden] = hiddenTensor
                if (nEncMask.isNotEmpty()) inputs[nEncMask] = encMaskTensor
                val next: Int = dec.run(inputs).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                    val row = logits[0][cur.size - 1]
                    // FuguMT ranks pad (32000) top at most steps; mask bad words or greedy
                    // stalls immediately. Then stop only on eos — never on pad.
                    for (b in badWords) if (b in row.indices) row[b] = Float.NEGATIVE_INFINITY
                    argmax(row)
                }
                decIds.close()
                if (next == eosId) break
                out.add(next)
                cur += next.toLong()
            }
        } finally {
            hiddenTensor.close()
            encMaskTensor.close()
        }
        return detokenize(out)
    }

    /** Map the decoder's input tensor names (order-independent across export versions). */
    private fun resolveDecoderInputs(dec: OrtSession): Triple<String, String, String> {
        var hidden = ""
        var encMask = ""
        var decIds = ""
        for (name in dec.inputNames) {
            when {
                name.contains("encoder_hidden") || name.contains("hidden") -> hidden = name
                name.contains("encoder") && name.contains("mask") -> encMask = name
                name.contains("input_ids") -> decIds = name
                else -> if (decIds.isEmpty()) decIds = name
            }
        }
        return Triple(hidden, encMask, decIds)
    }

    private fun argmax(v: FloatArray): Int {
        var bi = 0
        var bv = v[0]
        for (i in 1 until v.size) if (v[i] > bv) {
            bv = v[i]
            bi = i
        }
        return bi
    }
}
