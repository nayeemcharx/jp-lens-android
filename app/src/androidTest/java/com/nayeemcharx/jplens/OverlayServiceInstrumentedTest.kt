package com.nayeemcharx.jplens

import android.content.ComponentName
import android.content.Context
import android.Manifest
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android contracts surrounding OverlayService that do not require projection consent. */
@RunWith(AndroidJUnit4::class)
class OverlayServiceInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceIsRegisteredPrivateAndForMediaProjection() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, OverlayService::class.java),
            0,
        )

        assertFalse("overlay service must not be callable by other apps", info.exported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(
                "service must declare mediaProjection foreground type",
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0,
            )
        }
    }

    @Test
    fun modesAndServiceActionsAreDistinctStableContracts() {
        assertEquals(2, OverlayService.MODE_SENTENCE_DICT)
        assertEquals(4, OverlayService.MODE_CROP)
        assertNotEquals(OverlayService.MODE_SENTENCE_DICT, OverlayService.MODE_CROP)
        assertNotEquals(OverlayService.ACTION_START, OverlayService.ACTION_STOP)
        assertTrue(OverlayService.ACTION_START.startsWith(context.packageName))
        assertTrue(OverlayService.ACTION_STOP.startsWith(context.packageName))
        assertEquals(4, setOf(
            OverlayService.PREF_LAST_MODE,
            OverlayService.PREF_SHOW_READING,
            OverlayService.PREF_SHOW_ROMAJI,
            OverlayService.PREF_SHOW_TRANSLATION,
        ).size)
    }

    @Test
    fun manifestHasRequiredOverlayPermissionsButNoInternetPermission() {
        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS,
        ).requestedPermissions?.toSet().orEmpty()

        assertTrue(Manifest.permission.SYSTEM_ALERT_WINDOW in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requested)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION in requested)
        assertFalse("the offline overlay must not gain network access",
            Manifest.permission.INTERNET in requested)
        assertFalse("the offline overlay does not need network-state access",
            Manifest.permission.ACCESS_NETWORK_STATE in requested)
    }

    @Test
    fun overlayPreferencesRoundTripUsingServiceKeys() {
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val oldMode = prefs.getInt(OverlayService.PREF_LAST_MODE, OverlayService.MODE_SENTENCE_DICT)
        val oldReading = prefs.getBoolean(OverlayService.PREF_SHOW_READING, true)
        val oldRomaji = prefs.getBoolean(OverlayService.PREF_SHOW_ROMAJI, true)
        val oldTranslation = prefs.getBoolean(OverlayService.PREF_SHOW_TRANSLATION, true)
        try {
            assertTrue(prefs.edit()
                .putInt(OverlayService.PREF_LAST_MODE, OverlayService.MODE_CROP)
                .putBoolean(OverlayService.PREF_SHOW_READING, false)
                .putBoolean(OverlayService.PREF_SHOW_ROMAJI, false)
                .putBoolean(OverlayService.PREF_SHOW_TRANSLATION, false)
                .commit())

            assertEquals(OverlayService.MODE_CROP,
                prefs.getInt(OverlayService.PREF_LAST_MODE, -1))
            assertFalse(prefs.getBoolean(OverlayService.PREF_SHOW_READING, true))
            assertFalse(prefs.getBoolean(OverlayService.PREF_SHOW_ROMAJI, true))
            assertFalse(prefs.getBoolean(OverlayService.PREF_SHOW_TRANSLATION, true))
        } finally {
            prefs.edit()
                .putInt(OverlayService.PREF_LAST_MODE, oldMode)
                .putBoolean(OverlayService.PREF_SHOW_READING, oldReading)
                .putBoolean(OverlayService.PREF_SHOW_ROMAJI, oldRomaji)
                .putBoolean(OverlayService.PREF_SHOW_TRANSLATION, oldTranslation)
                .commit()
        }
    }
}
