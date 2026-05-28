package com.example.jp_lens_android

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.ichi2.anki.api.AddContentApi

/**
 * Thin wrapper around AnkiDroid's [AddContentApi]. Handles the find-or-create
 * lifecycle for our deck + note type so callers just say "add this card".
 *
 * Requires the host app to declare the [PERMISSION] dangerous permission in
 * the manifest and have it granted at runtime. AnkiDroid itself must be
 * installed — guarded by [isAnkiInstalled] (manifest `<queries>` makes the
 * package visible on Android 11+).
 */
object AnkiDroidHelper {

    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    private const val TAG = "JpLens.Anki"
    private const val DECK_NAME = "JP Lens"
    private const val MODEL_NAME = "JP Lens Basic"
    private val MODEL_FIELDS = arrayOf("Front", "Back")
    private val CARD_NAMES = arrayOf("Card 1")
    private val QFMT = arrayOf("{{Front}}")
    private val AFMT = arrayOf("{{FrontSide}}<hr id=answer>{{Back}}")
    private const val TAG_VALUE = "jp-lens"

    fun isAnkiInstalled(context: Context): Boolean =
        AddContentApi.getAnkiDroidPackageName(context) != null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /** Result of an add attempt — distinguishes "already there" from a real failure. */
    sealed class AddResult {
        data class Added(val noteId: Long) : AddResult()
        /** A note with the same front field (word) already exists. */
        object Duplicate : AddResult()
        data class Failed(val message: String) : AddResult()
    }

    /** Summary card: back built from the structured fields. */
    fun addCard(
        context: Context,
        word: String,
        reading: String,
        meaning: String,
        jlpt: String = "",
        sentence: String = "",
        translation: String = "",
    ): AddResult = addCard(context, word, buildBack(reading, meaning, jlpt, word, sentence, translation))

    /**
     * Core add: front + an already-formatted back (plain text or HTML — Anki renders
     * the field through a WebView). Find-or-creates the deck/model and dedupes on front.
     */
    fun addCard(context: Context, front: String, back: String): AddResult {
        if (!isAnkiInstalled(context)) return AddResult.Failed("AnkiDroid is not installed")
        if (!hasPermission(context)) return AddResult.Failed("AnkiDroid permission not granted")
        return try {
            val api = AddContentApi(context)
            val deckId = findDeckId(api, DECK_NAME) ?: api.addNewDeck(DECK_NAME)
                ?: return AddResult.Failed("Failed to create deck \"$DECK_NAME\"")
            val modelId = findModelId(api, MODEL_NAME)
                ?: api.addNewCustomModel(MODEL_NAME, MODEL_FIELDS, CARD_NAMES, QFMT, AFMT, null, deckId, null)
                ?: return AddResult.Failed("Failed to create model \"$MODEL_NAME\"")

            // findDuplicateNotes matches on the first field of `modelId` across
            // ALL decks (not just ours), which is what we want — a "word"
            // shouldn't be re-added even if the previous card lives elsewhere.
            val existing = api.findDuplicateNotes(modelId, front)
            if (!existing.isNullOrEmpty()) return AddResult.Duplicate

            val fields = arrayOf(front, back)
            val tags = setOf(TAG_VALUE)
            val id = api.addNote(modelId, deckId, fields, tags)
                ?: return AddResult.Failed("AnkiDroid rejected the note")
            AddResult.Added(id)
        } catch (t: Throwable) {
            Log.e(TAG, "addCard failed", t)
            AddResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun buildBack(
        reading: String,
        meaning: String,
        jlpt: String,
        word: String,
        sentence: String,
        translation: String,
    ): String {
        val sb = StringBuilder()
        if (reading.isNotEmpty() && reading != word) sb.append(reading).append("\n\n")
        sb.append(meaning)
        if (jlpt.isNotEmpty()) sb.append("\n\n[").append(jlpt).append(']')
        if (sentence.isNotEmpty()) sb.append("\n\n").append(sentence)
        if (translation.isNotEmpty()) sb.append("\n").append(translation)
        return sb.toString()
    }

    private fun findDeckId(api: AddContentApi, name: String): Long? =
        api.deckList?.entries?.firstOrNull { it.value == name }?.key

    private fun findModelId(api: AddContentApi, name: String): Long? =
        api.modelList?.entries?.firstOrNull { it.value == name }?.key
}
