package com.nayeemcharx.jplens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [AnkiDroidHelper]'s guard/config logic. These need a real Context
 * (package-manager queries, SharedPreferences, permission checks) but do NOT require
 * AnkiDroid to be installed — the point is that the guards degrade gracefully.
 *
 * Note: adding an actual card is not exercised here — it depends on AnkiDroid being
 * installed and its runtime permission granted, which a headless CI device won't have.
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidHelperInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun guardsNeverThrow() {
        // Each of these just reads system/pref state; none may throw regardless of install state.
        AnkiDroidHelper.isAnkiInstalled(context)
        AnkiDroidHelper.hasPermission(context)
        assertNotNull(AnkiDroidHelper.configuredDeckName(context))
        AnkiDroidHelper.isConfigured(context)
    }

    @Test
    fun isConfigured_impliesInstalledAndPermitted() {
        // Logical invariant: the "+" button is only shown when fully set up.
        if (AnkiDroidHelper.isConfigured(context)) {
            assertTrue(AnkiDroidHelper.isAnkiInstalled(context))
            assertTrue(AnkiDroidHelper.hasPermission(context))
            assertTrue(AnkiDroidHelper.configuredDeckName(context).isNotEmpty())
        }
    }

    @Test
    fun notConfigured_whenAnkiAbsent() {
        // The common CI case: AnkiDroid not installed => not configured, and addCard fails cleanly.
        if (!AnkiDroidHelper.isAnkiInstalled(context)) {
            assertFalse(AnkiDroidHelper.isConfigured(context))
            val result = AnkiDroidHelper.addCard(context, front = "猫", back = "cat")
            assertTrue("expected a Failed result when Anki is absent",
                result is AnkiDroidHelper.AddResult.Failed)
        }
    }
}
