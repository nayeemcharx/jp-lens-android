package com.nayeemcharx.jplens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.nayeemcharx.jplens.e2e.E2eFixtureActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/** Short real-device smoke: screenshot -> MediaProjection -> real OCR sentence boxes. */
@RunWith(AndroidJUnit4::class)
class OverlayE2eSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    @Before
    fun cleanSession() {
        grantDevicePermissions()
        context.stopService(Intent(context, OverlayService::class.java))
        context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(OverlayService.PREF_LAST_MODE, OverlayService.MODE_SENTENCE_DICT)
            .putBoolean(OverlayService.PREF_SHOW_READING, true)
            .putBoolean(OverlayService.PREF_SHOW_ROMAJI, true)
            .putBoolean(OverlayService.PREF_SHOW_TRANSLATION, true)
            .commit()
    }

    @Test
    fun screenshotProducesJapaneseSentenceBoxes() {
        assertTrue("E2E setup could not grant Display over other apps",
            Settings.canDrawOverlays(context))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            assertTrue("E2E setup could not grant notification permission",
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED)
        }
        context.assets.open(E2eFixtureActivity.DEFAULT_ASSET).close()

        var scenario: ActivityScenario<E2eFixtureActivity>? = null
        try {
            scenario = ActivityScenario.launch(E2eFixtureActivity::class.java)
            scenario.onActivity { it.beginProjectionForTest() }
            awaitManualProjectionConsent()

            val popupTarget = device.wait(
                Until.findObject(By.desc("E2E popup target")), 5_000)
            assertNotNull("fixture popup target was not accessible", popupTarget)
            val popupTargetBounds = popupTarget.visibleBounds

            val capture = device.wait(
                Until.findObject(By.desc("Capture Japanese text")), 15_000)
            assertNotNull("floating capture button did not appear", capture)
            capture.click()

            val detected = device.wait(
                Until.findObject(By.descStartsWith("Japanese text detected:")), 30_000)
            assertNotNull("real OCR produced no Japanese sentence boxes", detected)

            // Tap the known OCR band through the custom-drawn fullscreen box window.
            // This exercises its real hit testing and opens the same breakdown used
            // for arbitrary detected sentence boxes.
            device.click(popupTargetBounds.centerX(), popupTargetBounds.centerY())
            assertPopupSectionsAndWordDetails()

            // Active-button tap must clear the one fullscreen overlay window and return
            // to idle. A second scan catches stale isProcessing/callback state that only
            // appears after the first successful OCR pass.
            val clear = device.wait(
                Until.findObject(By.desc("Clear Japanese text overlay")), 5_000)
            assertNotNull("capture button did not enter its active/clear state", clear)
            clear.click()
            assertTrue(
                "sentence overlay remained after Clear",
                device.wait(Until.gone(By.descStartsWith("Japanese text detected:")), 5_000),
            )

            val captureAgain = device.wait(
                Until.findObject(By.desc("Capture Japanese text")), 5_000)
            assertNotNull("capture button did not recover after Clear", captureAgain)
            captureAgain.click()
            assertNotNull(
                "second OCR pass produced no Japanese sentence boxes",
                device.wait(
                    Until.findObject(By.descStartsWith("Japanese text detected:")), 30_000),
            )
        } finally {
            context.stopService(Intent(context, OverlayService::class.java))
            scenario?.close()
        }
    }

    @Test
    fun cropSelectorRecoversAndOpensInteractivePopup() {
        context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(OverlayService.PREF_LAST_MODE, OverlayService.MODE_CROP)
            .commit()

        var scenario: ActivityScenario<E2eFixtureActivity>? = null
        try {
            scenario = ActivityScenario.launch(E2eFixtureActivity::class.java)
            scenario.onActivity {
                it.beginProjectionForTest(OverlayService.MODE_CROP)
            }
            awaitManualProjectionConsent()

            val popupTarget = device.wait(
                Until.findObject(By.desc("E2E popup target")), 5_000)
            assertNotNull("fixture popup target was not accessible", popupTarget)
            val popupTargetBounds = popupTarget.visibleBounds

            repeat(2) { attempt ->
                val capture = device.wait(
                    Until.findObject(By.desc("Capture Japanese text")), 15_000)
                assertNotNull("crop capture button missing on attempt ${attempt + 1}", capture)
                capture.click()

                val cancel = device.wait(
                    Until.findObject(By.desc("Cancel crop")), 15_000)
                assertNotNull("crop selector did not appear on attempt ${attempt + 1}", cancel)
                cancel.click()
                assertTrue(
                    "crop selector did not close on attempt ${attempt + 1}",
                    device.wait(Until.gone(By.desc("Cancel crop")), 5_000),
                )
            }

            assertNotNull(
                "capture button stayed hidden/busy after repeated crop cancellation",
                device.wait(Until.findObject(By.desc("Capture Japanese text")), 5_000),
            )

            // Third crop commits the deterministic Japanese band and must open the
            // same interactive breakdown directly (crop mode has no sentence boxes).
            device.findObject(By.desc("Capture Japanese text")).click()
            assertNotNull(
                "crop selector did not appear for popup test",
                device.wait(Until.findObject(By.desc("Cancel crop")), 15_000),
            )
            val pad = (12 * context.resources.displayMetrics.density).toInt()
            device.swipe(
                (popupTargetBounds.left - pad).coerceAtLeast(0),
                (popupTargetBounds.top - pad).coerceAtLeast(0),
                (popupTargetBounds.right + pad).coerceAtMost(device.displayWidth - 1),
                (popupTargetBounds.bottom + pad).coerceAtMost(device.displayHeight - 1),
                20,
            )
            assertPopupSectionsAndWordDetails()
            assertNotNull(
                "crop mode did not recover after closing the breakdown",
                device.wait(Until.findObject(By.desc("Capture Japanese text")), 5_000),
            )
        } finally {
            context.stopService(Intent(context, OverlayService::class.java))
            scenario?.close()
        }
    }

    private fun assertPopupSectionsAndWordDetails() {
        val close = device.wait(Until.findObject(By.desc("Close")), 60_000)
        assertNotNull("breakdown did not open", close)
        assertNotNull("breakdown has no Reading section",
            device.wait(Until.findObject(By.text("Reading")), 5_000))
        assertNotNull("breakdown has no Romaji section",
            device.wait(Until.findObject(By.text("Romaji")), 5_000))
        assertNotNull("breakdown has no Offline translation section",
            device.wait(Until.findObject(By.text("Offline translation")), 5_000))

        // The optional JMdict asset controls whether words are interactive. When it
        // is shipped (the real release/device configuration), tap the known sentence
        // and require the lazily-loaded word-detail panel to expand.
        if (JmDict.isAvailable()) {
            val sentence = device.wait(
                Until.findObject(By.textContains("猫が好きです")), 5_000)
            assertNotNull("interactive popup sentence was not found", sentence)
            tapFirstPopupWord(sentence)
            assertNotNull(
                "tapping a popup word did not expand dictionary details",
                device.wait(
                    Until.findObject(By.descStartsWith("Dictionary word details for ")),
                    20_000,
                ),
            )

            // The same word toggles closed and can be opened again without leaving
            // stale selection/load state behind.
            tapFirstPopupWord(device.findObject(By.textContains("猫が好きです")))
            assertTrue(
                "tapping the selected popup word did not collapse dictionary details",
                device.wait(
                    Until.gone(By.descStartsWith("Dictionary word details for ")),
                    5_000,
                ),
            )
            tapFirstPopupWord(device.findObject(By.textContains("猫が好きです")))
            assertNotNull(
                "dictionary details did not reopen after collapse",
                device.wait(
                    Until.findObject(By.descStartsWith("Dictionary word details for ")),
                    20_000,
                ),
            )
        }

        device.findObject(By.desc("Close")).click()
        assertTrue(
            "breakdown did not close",
            device.wait(Until.gone(By.desc("Close")), 5_000),
        )
    }

    private fun tapFirstPopupWord(sentence: androidx.test.uiautomator.UiObject2) {
        // SentenceTextView uses weight=1 and therefore spans most of the popup even
        // when its text is short and left-aligned. UiObject2.click() targets the view
        // center, which is blank space for "猫が好きです。". Tap one small text-size
        // inset from the left edge so the view's real morpheme hit-test receives 猫.
        val bounds = sentence.visibleBounds
        val textInset = (12 * context.resources.displayMetrics.density).toInt()
        device.click(
            (bounds.left + textInset).coerceAtMost(bounds.right - 1),
            bounds.centerY(),
        )
    }

    private fun grantDevicePermissions() {
        // Android Studio can reinstall the debug APK before instrumentation,
        // clearing special app-ops. UiAutomation executes these as adb shell.
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        device.waitForIdle()
    }

    private fun shell(command: String) {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        try {
            FileInputStream(output.fileDescriptor).use { input ->
                val buffer = ByteArray(1024)
                while (input.read(buffer) >= 0) Unit
            }
        } finally {
            output.close()
        }
    }

    private fun awaitManualProjectionConsent() {
        // Deliberately leave the Android 14+ source picker to the tester. OEMs expose
        // this as a spinner, radio rows, or a dropdown with different resource IDs, and
        // this real-device smoke is already interactive because every run needs fresh
        // MediaProjection consent. Select "entire screen" and press the confirmation
        // button. Waiting for our overlay instead of matching localized SystemUI text
        // also proves that the permission result reached and started the real service.
        Log.i(
            "JpLens.E2E",
            "Select the entire-screen option and confirm sharing in the system dialog",
        )
        val captureButton = device.wait(
            Until.findObject(By.desc("Capture Japanese text")),
            60_000,
        )
        assertNotNull(
            "Select the entire-screen option and confirm sharing (timed out after 60s)",
            captureButton,
        )
    }
}
