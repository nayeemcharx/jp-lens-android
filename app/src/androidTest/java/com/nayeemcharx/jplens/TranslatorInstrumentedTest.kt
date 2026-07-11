package com.nayeemcharx.jplens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for the offline [Translator] (FuguMT via ONNX Runtime Mobile).
 *
 * The `assets/fugumt/` bundle is NOT committed (~80 MB; built by `scripts/build_fugumt.py`).
 * When it's absent [Translator.assetPresent] is false and the translation assertion is
 * **skipped** — the checks that must always hold (asset probe never throws, unavailable
 * until warmed) still run. Translation itself is slow, so it's a single short line.
 */
@RunWith(AndroidJUnit4::class)
class TranslatorInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun assetPresent_isCheapAndSafe() {
        // A boolean home-screen probe; must never throw whether or not the bundle exists.
        Translator.assetPresent(context)
    }

    @Test
    fun unavailableUntilWarmedUp() {
        // Before warmUp the sessions aren't built, so isAvailable() must be false.
        assertFalse("sessions should not be built before warmUp", Translator.isAvailable())
    }

    @Test
    fun translatesShortLineWhenModelPresent() {
        assumeTrue("fugumt assets not built — skipping translation test",
            Translator.assetPresent(context))
        Translator.warmUp(context)
        assumeTrue("model present but sessions failed to build", Translator.isAvailable())

        val out = Translator.translateJaToEn("こんにちは")
        assertTrue("expected a non-empty translation", out.isNotBlank())
    }
}
