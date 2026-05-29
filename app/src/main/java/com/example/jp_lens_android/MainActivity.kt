package com.example.jp_lens_android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.jp_lens_android.ui.theme.JplensandroidTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by [OverlayService] when the user taps "+" but AnkiDroid permission isn't granted. */
        const val EXTRA_REQUEST_ANKI_PERMISSION = "request_anki_permission"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requestAnkiPerm = intent?.getBooleanExtra(EXTRA_REQUEST_ANKI_PERMISSION, false) ?: false
        setContent {
            JplensandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PermissionFlow(
                        modifier = Modifier.padding(innerPadding),
                        autoRequestAnkiPermission = requestAnkiPerm,
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionFlow(
    modifier: Modifier = Modifier,
    autoRequestAnkiPermission: Boolean = false,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Idle") }

    val ankiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status = if (granted) "AnkiDroid permission granted — tap + again in the overlay."
        else "AnkiDroid permission denied."
        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
    }

    LaunchedEffect(autoRequestAnkiPermission) {
        if (autoRequestAnkiPermission &&
            ContextCompat.checkSelfPermission(context, AnkiDroidHelper.PERMISSION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            ankiPermLauncher.launch(AnkiDroidHelper.PERMISSION)
        }
    }

    val prefs = remember {
        context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var apiKey by remember {
        mutableStateOf(prefs.getString(OverlayService.PREF_ANTHROPIC_KEY, "") ?: "")
    }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // Tracks which mode the next projection-permission result should launch with.
    var pendingMode by remember { mutableStateOf(OverlayService.MODE_MORPHEME) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
                putExtra(OverlayService.EXTRA_MODE, pendingMode)
            }
            ContextCompat.startForegroundService(context, svc)
            status = "Overlay running — tap the floating button to capture; " +
                "hold it to switch mode or stop."
        } else {
            status = "Screen capture permission denied."
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — FGS still starts */ }

    fun startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                status = "Allow notifications, then press Start again."
                return
            }
        }
        if (!Settings.canDrawOverlays(context)) {
            status = "Grant overlay permission, then press Start again."
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
            return
        }
        // Resume the last mode used; switchable live via the floating button's hold menu.
        pendingMode = prefs.getInt(OverlayService.PREF_LAST_MODE, OverlayService.MODE_MORPHEME)
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("JP Lens — Floating OCR")
        Text(status)

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                prefs.edit().putString(OverlayService.PREF_ANTHROPIC_KEY, it).apply()
            },
            label = { Text("Anthropic API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = { startCapture() }) { Text("Start") }

        Button(onClick = {
            val svc = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            }
            context.startService(svc)
            status = "Stopped."
        }) { Text("Stop") }

        Text(
            "Hold the floating button to switch mode (word / LLM / 辞書) or stop, " +
                "without reopening this screen."
        )
    }
}
