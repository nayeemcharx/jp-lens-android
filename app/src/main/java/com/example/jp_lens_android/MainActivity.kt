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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.jp_lens_android.ui.theme.JplensandroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JplensandroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PermissionFlow(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PermissionFlow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Idle") }

    val prefs = remember {
        context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var apiKey by remember {
        mutableStateOf(prefs.getString(OverlayService.PREF_BEDROCK_TOKEN, "") ?: "")
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
            status = if (pendingMode == OverlayService.MODE_SENTENCE_LLM)
                "Overlay running (LLM sentence mode) — switch apps and tap the floating button."
            else
                "Overlay running — switch to your game and tap the floating button."
        } else {
            status = "Screen capture permission denied."
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — FGS still starts */ }

    fun launchCapture(mode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (!Settings.canDrawOverlays(context)) {
            status = "Overlay permission not granted yet."
            return
        }
        if (mode == OverlayService.MODE_SENTENCE_LLM && apiKey.isBlank()) {
            status = "Enter AWS_BEARER_TOKEN_BEDROCK above before starting LLM mode."
            return
        }
        pendingMode = mode
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
                prefs.edit().putString(OverlayService.PREF_BEDROCK_TOKEN, it).apply()
            },
            label = { Text("AWS_BEARER_TOKEN_BEDROCK") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(onClick = {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }) { Text("1. Grant overlay permission") }

        Button(onClick = { launchCapture(OverlayService.MODE_MORPHEME) }) {
            Text("2. Start capture (word tokenizer + Google Translate)")
        }

        Button(onClick = {
            val svc = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP
            }
            context.startService(svc)
            status = "Stopped."
        }) { Text("3. Stop") }

        Button(onClick = { launchCapture(OverlayService.MODE_SENTENCE_LLM) }) {
            Text("4. Start capture (sentence boxes + Bedrock DeepSeek LLM)")
        }
    }
}
