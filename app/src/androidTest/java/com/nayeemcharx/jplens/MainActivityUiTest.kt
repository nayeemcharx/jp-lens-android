package com.nayeemcharx.jplens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    }
}
