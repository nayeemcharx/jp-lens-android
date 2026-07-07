package com.nayeemcharx.jplens

/**
 * One row of the sentence-mode Word-by-word section, shared by the dict-mode popup
 * and the Anki "+" button. (Formerly nested in AnthropicClient, which was removed
 * along with the optional LLM mode.)
 */
data class WordEntry(
    val word: String,    // dictionary/lookup form — keys JMdict detail + Anki front
    val reading: String, // hiragana reading; empty when no kanji or none provided
    val meaning: String,
    val jlpt: String,    // e.g. "N5"; empty when unknown
    val surface: String = "", // exact text as it appeared in the sentence; "" -> use [word] for display
)
