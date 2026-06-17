package com.example.jp_lens_android

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translator as MlKitTranslator

/**
 * On-device Japanese→English translation via Google ML Kit's Translation API.
 *
 * ML Kit translation models are download-only (they can't be bundled into the APK), so the
 * JA + EN models are fetched once via [downloadModel] — triggered explicitly from the home
 * screen, not silently on first use. Until they're present [translateJaToEn] returns "" and
 * the overlay simply omits the translation; once downloaded it works fully offline.
 *
 * All three calls block on ML Kit Tasks, so they MUST be invoked off the main thread.
 */
object Translator {

    private const val TAG = "JpLens.Translator"

    private val translator: MlKitTranslator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }

    // Cached "both models present" flag. Set true once a check or download confirms it,
    // so the hot path (and the overlay's gate) doesn't re-query every time.
    @Volatile private var modelReady = false

    /**
     * Whether the JA + EN models are already on the device. Cheap once cached; the first
     * call does a local (no-network) lookup. Blocking — call off the main thread.
     */
    fun isDownloaded(): Boolean {
        if (modelReady) return true
        return try {
            val mgr = RemoteModelManager.getInstance()
            val ja = TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()
            val en = TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build()
            val ok = Tasks.await(mgr.isModelDownloaded(ja)) && Tasks.await(mgr.isModelDownloaded(en))
            if (ok) modelReady = true
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "Model state check failed", t)
            false
        }
    }

    /**
     * Download the JA→EN models (no-op if already present). Returns true on success.
     * Needs network the first time; blocking — call off the main thread.
     */
    fun downloadModel(requireWifi: Boolean = false): Boolean {
        return try {
            val conditions = DownloadConditions.Builder()
                .apply { if (requireWifi) requireWifi() }
                .build()
            Tasks.await(translator.downloadModelIfNeeded(conditions))
            modelReady = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Model download failed", t)
            false
        }
    }

    /**
     * Translate [text] JA→EN. Returns "" if the input is blank or the model isn't
     * downloaded yet (it does NOT trigger a download — the home screen does that).
     * Blocking — call off the main thread.
     */
    fun translateJaToEn(text: String): String {
        if (text.isBlank()) return ""
        if (!isDownloaded()) return ""
        return try {
            Tasks.await(translator.translate(text)).trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Translate failed", t)
            ""
        }
    }
}
