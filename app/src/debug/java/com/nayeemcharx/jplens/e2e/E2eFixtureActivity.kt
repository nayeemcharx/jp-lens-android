package com.nayeemcharx.jplens.e2e

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nayeemcharx.jplens.OverlayService

/** Debug-only screenshot fixture. It is never packaged in release builds. */
class E2eFixtureActivity : Activity() {
    companion object {
        const val DEFAULT_ASSET = "e2e/japanese_smoke.png"
        private const val REQUEST_PROJECTION = 901
    }

    private lateinit var startButton: Button
    private var modeForTest = OverlayService.MODE_SENTENCE_DICT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val image = runCatching {
            assets.open(DEFAULT_ASSET).use(BitmapFactory::decodeStream)
        }.getOrNull()

        if (image != null) {
            root.addView(ImageView(this).apply {
                setImageBitmap(image)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "E2E Japanese screenshot"
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            root.addView(TextView(this).apply {
                text = "Missing: app/src/debug/assets/$DEFAULT_ASSET"
                setTextColor(Color.RED)
                textSize = 18f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }

        // Stable high-contrast OCR/tap target for popup E2E checks. The screenshot
        // remains the dense real-world OCR fixture, while this band gives the test a
        // known sentence and a view-reported screen rectangle on every resolution.
        root.addView(TextView(this).apply {
            text = "猫が好きです。"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            contentDescription = "E2E popup target"
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (80 * resources.displayMetrics.density).toInt(),
        ).apply {
            gravity = Gravity.TOP
            topMargin = (180 * resources.displayMetrics.density).toInt()
        })

        startButton = Button(this).apply {
            text = "Start E2E capture"
            isEnabled = image != null
            setOnClickListener { requestProjection() }
        }
        root.addView(startButton, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (24 * resources.displayMetrics.density).toInt()
        })
        setContentView(root)
    }

    private fun requestProjection() {
        startButton.isEnabled = false
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    /** Called by ActivityScenario after it has confirmed this fixture is resumed. */
    fun beginProjectionForTest(mode: Int = OverlayService.MODE_SENTENCE_DICT) {
        modeForTest = mode
        requestProjection()
    }

    @Deprecated("Intentional for this isolated debug fixture")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PROJECTION) return
        if (resultCode != RESULT_OK || data == null) {
            startButton.isEnabled = true
            return
        }
        startButton.visibility = View.GONE
        getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(OverlayService.PREF_LAST_MODE, modeForTest)
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        })
    }
}
