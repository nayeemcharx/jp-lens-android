package com.nayeemcharx.jplens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI smoke test for the home screen. Confirms [MainActivity] inflates its content
 * without crashing and that the primary controls render. It deliberately does NOT tap
 * Start — that launches the system MediaProjection consent dialog (system UI, outside the
 * app's compose tree) and can't be driven headlessly.
 *
 * Text is matched with `assertExists` where an item may be below the fold (the screen
 * scrolls), and `assertIsDisplayed` only for the always-visible header/actions.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_showsTitleAndPrimaryActions() {
        // Match the tagline by substring — "JP Lens" alone appears more than once (headline
        // + the default deck-name field), which would make onNodeWithText ambiguous.
        composeRule.onNodeWithText("Floating Japanese OCR", substring = true).assertIsDisplayed()
        // "Stop" is always present; "Start"/"Restart" toggles on running state.
        composeRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun homeScreen_showsSectionCards() {
        // These live further down the scroll; assertExists doesn't require them on-screen.
        composeRule.onNodeWithText("Permissions").assertExists()
        composeRule.onNodeWithText("Breakdown").assertExists()
        composeRule.onNodeWithText("About & privacy").assertExists()
        composeRule.onNodeWithText("Reading (kana)").assertExists()
        composeRule.onNodeWithText("Romaji").assertExists()
        composeRule.onNodeWithText("Offline translation").assertExists()
        composeRule.onAllNodes(isToggleable()).assertCountEquals(3)
    }

    @Test
    fun breakdownSwitchPersistsReadingPreference() {
        val prefs = composeRule.activity.getSharedPreferences(
            OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val before = prefs.getBoolean(OverlayService.PREF_SHOW_READING, true)
        val readingSwitch = composeRule.onAllNodes(isToggleable())[0]

        if (before) readingSwitch.assertIsOn() else readingSwitch.assertIsOff()
        readingSwitch.performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(
            !before,
            prefs.getBoolean(OverlayService.PREF_SHOW_READING, before),
        )

        // Restore device state so this test does not influence later launches.
        readingSwitch.performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun everyBreakdownSwitchPersistsAndCanBeRestored() {
        val prefs = composeRule.activity.getSharedPreferences(
            OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val keys = listOf(
            OverlayService.PREF_SHOW_READING,
            OverlayService.PREF_SHOW_ROMAJI,
            OverlayService.PREF_SHOW_TRANSLATION,
        )
        val old = keys.map { prefs.getBoolean(it, true) }

        try {
            keys.indices.forEach { index ->
                val toggle = composeRule.onAllNodes(isToggleable())[index]
                toggle.performScrollTo().performClick()
                composeRule.waitForIdle()
                assertEquals(!old[index], prefs.getBoolean(keys[index], old[index]))
            }
        } finally {
            prefs.edit().apply {
                keys.indices.forEach { putBoolean(keys[it], old[it]) }
            }.commit()
        }
    }

    @Test
    fun breakdownPreferencesSurviveActivityRecreation() {
        val prefs = composeRule.activity.getSharedPreferences(
            OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val keys = listOf(
            OverlayService.PREF_SHOW_READING,
            OverlayService.PREF_SHOW_ROMAJI,
            OverlayService.PREF_SHOW_TRANSLATION,
        )
        val old = keys.map { prefs.getBoolean(it, true) }

        try {
            prefs.edit()
                .putBoolean(keys[0], false)
                .putBoolean(keys[1], true)
                .putBoolean(keys[2], false)
                .commit()
            composeRule.activityRule.scenario.recreate()

            composeRule.onAllNodes(isToggleable())[0].assertIsOff()
            composeRule.onAllNodes(isToggleable())[1].assertIsOn()
            composeRule.onAllNodes(isToggleable())[2].assertIsOff()
        } finally {
            prefs.edit().apply {
                keys.indices.forEach { putBoolean(keys[it], old[it]) }
            }.commit()
        }
    }

    @Test
    fun deckNameSaveTrimsInputAndCancelDoesNotOverwriteIt() {
        val prefs = composeRule.activity.getSharedPreferences(
            OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val original = prefs.getString(OverlayService.PREF_ANKI_DECK, "JP Lens")

        try {
            // Do not depend on whatever deck name the developer currently has saved.
            prefs.edit().putString(OverlayService.PREF_ANKI_DECK, "JP Lens").commit()
            composeRule.activityRule.scenario.recreate()
            composeRule.onNodeWithText("Deck name").performScrollTo()
            composeRule.onNodeWithText("Edit").performClick()
            composeRule.onNode(hasSetTextAction())
                .performTextReplacement("  E2E Test Deck  ")
            composeRule.onNodeWithText("Save").performClick()
            assertEquals("E2E Test Deck",
                prefs.getString(OverlayService.PREF_ANKI_DECK, null))
            composeRule.onNodeWithText("E2E Test Deck").assertExists()

            composeRule.onNodeWithText("Edit").performClick()
            composeRule.onNode(hasSetTextAction())
                .performTextReplacement("must not be saved")
            composeRule.onNodeWithText("Cancel").performClick()
            composeRule.onNodeWithText("E2E Test Deck").assertExists()
            assertEquals("E2E Test Deck",
                prefs.getString(OverlayService.PREF_ANKI_DECK, null))
        } finally {
            prefs.edit().putString(OverlayService.PREF_ANKI_DECK, original).commit()
        }
    }

    @Test
    fun aboutScreenOpensAndReturnsWithoutRecreatingActivity() {
        composeRule.onNodeWithText("About & privacy")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Nothing is sent off your device").assertExists()

        composeRule.onNodeWithText("← Back").performClick()
        composeRule.onNodeWithText("Breakdown").assertExists()
    }

    @Test
    fun licensesExpandCollapseAndBackNavigationRemainResponsive() {
        composeRule.onNodeWithText("About & privacy")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Open-source licenses")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("ONNX Runtime", substring = true).assertExists()
        composeRule.onNodeWithText("Hide open-source licenses").performClick()
        composeRule.onNodeWithText("Hide open-source licenses").assertDoesNotExist()
        composeRule.onNodeWithText("← Back").performClick()
        composeRule.onNodeWithText("Start").assertExists()
    }

    @Test(timeout = 30_000)
    fun repeatedTogglesAndStopCommandsDoNotFreezeTheUi() {
        val toggles = composeRule.onAllNodes(isToggleable())
        repeat(10) {
            for (index in 0 until 3) toggles[index].performScrollTo().performClick()
        }
        repeat(5) {
            composeRule.onNodeWithText("Stop").performScrollTo().performClick()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Start").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Floating Japanese OCR", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Start").performScrollTo().assertIsDisplayed()
    }

    @Test(timeout = 30_000)
    fun tutorialCanAdvanceBackAndCloseWithoutLeakingTheDialog() {
        composeRule.onNodeWithText("How to use JP Lens", substring = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Welcome to JP Lens").assertExists()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("2 / 7").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Back").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("1 / 7").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Skip").performClick()
        composeRule.onNodeWithText("How to use JP Lens", substring = true).assertExists()
    }
}
