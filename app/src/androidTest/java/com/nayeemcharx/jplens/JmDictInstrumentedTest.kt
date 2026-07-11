package com.nayeemcharx.jplens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [JmDict] — it needs SQLite + a real Context to copy `assets/jmdict.db`
 * to internal storage, so it can't run as a host unit test.
 *
 * The `jmdict.db` asset is NOT committed (built by `scripts/build_jmdict_db.py`). When it's
 * absent [JmDict.isAvailable] stays false and the lookup assertions are **skipped**
 * (`assumeTrue`) rather than failed — so this file is green on a checkout that hasn't built
 * the dictionary, and actually exercises lookups when it has.
 */
@RunWith(AndroidJUnit4::class)
class JmDictInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun warmUp_isIdempotentAndNeverThrows() {
        JmDict.warmUp(context)
        JmDict.warmUp(context) // second call must be a no-op, not a re-copy/re-open
        // No assertion on availability — the asset is optional. We only assert it doesn't crash.
    }

    @Test
    fun lookupWord_knownCommonWord() {
        JmDict.warmUp(context)
        assumeTrue("jmdict.db asset not built — skipping lookup test", JmDict.isAvailable())

        val info = JmDict.lookupWord("猫")
        assertNotNull("common word 猫 should resolve", info)
        assertTrue("gloss should be non-blank", info!!.gloss.isNotBlank())
    }

    @Test
    fun lookupWord_blankReturnsNull() {
        JmDict.warmUp(context)
        assumeTrue(JmDict.isAvailable())
        assertNull("blank base has no entry", JmDict.lookupWord("   "))
    }

    @Test
    fun lookupKanji_knownCharacter() {
        JmDict.warmUp(context)
        assumeTrue("jmdict.db asset not built — skipping kanji test", JmDict.isAvailable())

        val kanji = JmDict.lookupKanji('日')
        assertNotNull("KANJIDIC2 should know 日", kanji)
        assertTrue("meaning should be non-blank", kanji!!.meaning.isNotBlank())
    }

    @Test
    fun lookupWordDetail_returnsSensesForKnownWord() {
        JmDict.warmUp(context)
        assumeTrue(JmDict.isAvailable())

        val details = JmDict.lookupWordDetail("食べる")
        assumeTrue("食べる not present in this (possibly pruned) db", details.isNotEmpty())
        val hasGloss = details.any { d -> d.senses.any { it.glosses.isNotEmpty() } }
        assertTrue("at least one sense should carry a gloss", hasGloss)
    }

    @Test
    fun tagLabel_fallsBackToCode() {
        JmDict.warmUp(context)
        // Unknown codes fall back to themselves regardless of whether the db loaded.
        assertEquals("definitely-not-a-real-tag", JmDict.tagLabel("definitely-not-a-real-tag"))
    }

    @Test
    fun lookupsAreNullSafeWhenDbMissing() {
        // When the asset isn't built, every lookup API must be null-safe, not throwing.
        JmDict.warmUp(context)
        assumeTrue("db is available — this test only covers the missing-asset path",
            !JmDict.isAvailable())
        assertNull(JmDict.lookupWord("猫"))
        assertNull(JmDict.lookupKanji('日'))
        assertTrue(JmDict.lookupWordDetail("猫").isEmpty())
    }
}
