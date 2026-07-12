package com.nayeemcharx.jplens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the tutorial against missing, empty, or compressed videos that cannot play via openFd. */
@RunWith(AndroidJUnit4::class)
class TutorialAssetsInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyTutorialVideoIsPresentNonEmptyAndSeekable() {
        val videos = listOf(
            "tutorial/start.mp4",
            "tutorial/change_mode.mp4",
            "tutorial/crop.mp4",
            "tutorial/word_clicking.mp4",
            "tutorial/fullscreen.mp4",
        )

        for (path in videos) {
            context.assets.openFd(path).use { descriptor ->
                assertTrue("tutorial video is empty: $path", descriptor.length > 0L)
            }
        }
    }
}
