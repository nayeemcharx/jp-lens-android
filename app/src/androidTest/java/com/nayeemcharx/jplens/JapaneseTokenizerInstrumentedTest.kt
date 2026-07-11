package com.nayeemcharx.jplens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for [JapaneseTokenizer]. The detailed logic lives in the host
 * unit test ([JapaneseTokenizerTest]); this just confirms the same code path — including
 * [JapaneseTokenizer.warmUp], which takes a real Context — works inside the packaged app
 * on a device/emulator (JAR-bundled dictionary loads, no missing native bits).
 */
@RunWith(AndroidJUnit4::class)
class JapaneseTokenizerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun warmUpThenExtract_worksOnDevice() {
        JapaneseTokenizer.warmUp(context)
        assertTrue(JapaneseTokenizer.isAvailable())

        val morphemes = JapaneseTokenizer.extract("猫が好きです")
        assertTrue("expected morphemes on device", morphemes.isNotEmpty())
        for (m in morphemes) {
            assertEquals("猫が好きです".substring(m.start, m.end), m.surface)
        }
    }
}
