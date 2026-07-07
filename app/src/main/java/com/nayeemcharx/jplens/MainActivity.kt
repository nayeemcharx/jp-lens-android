package com.nayeemcharx.jplens

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
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nayeemcharx.jplens.ui.theme.JplensandroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by [OverlayService] when the user taps "+" but AnkiDroid permission isn't granted. */
        const val EXTRA_REQUEST_ANKI_PERMISSION = "request_anki_permission"
    }

    // Bumped on each onResume so the UI re-reads permission/install state after the user
    // returns from a system settings screen (overlay permission, AnkiDroid, etc.).
    private val resumeTick = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val requestAnkiPerm = intent?.getBooleanExtra(EXTRA_REQUEST_ANKI_PERMISSION, false) ?: false
        setContent {
            JplensandroidTheme {
                var showAbout by remember { mutableStateOf(false) }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showAbout) {
                        AboutScreen(
                            modifier = Modifier.padding(innerPadding),
                            onBack = { showAbout = false },
                        )
                    } else {
                        PermissionFlow(
                            modifier = Modifier.padding(innerPadding),
                            autoRequestAnkiPermission = requestAnkiPerm,
                            resumeTick = resumeTick.value,
                            onAbout = { showAbout = true },
                        )
                    }
                }
            }
        }
    }
}

private val OK_COLOR = Color(0xFF2E7D32)
private val PENDING_COLOR = Color(0xFFB26A00)

/** A titled card section. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** A check/dot + label row showing whether something is set up. */
@Composable
private fun StatusLine(ok: Boolean, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (ok) "✓" else "•", color = if (ok) OK_COLOR else PENDING_COLOR, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Small muted helper/hint text. */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A read-only value with an "Edit" (or "Set", when empty) button; tapping it reveals the
 * text field with Save / Cancel. The field is only shown while editing. [masked] hides the
 * value (for sensitive fields) in both display and edit modes.
 */
@Composable
private fun EditableField(
    label: String,
    value: String,
    onSave: (String) -> Unit,
    masked: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value) { mutableStateOf(value) }

    if (editing) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(draft.trim()); editing = false }) { Text("Save") }
            OutlinedButton(onClick = { draft = value; editing = false }) { Text("Cancel") }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        value.isBlank() -> "Not set"
                        masked -> "••••••••"
                        else -> value
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedButton(onClick = { draft = value; editing = true }) {
                Text(if (value.isBlank()) "Set" else "Edit")
            }
        }
    }
}

@Composable
fun PermissionFlow(
    modifier: Modifier = Modifier,
    autoRequestAnkiPermission: Boolean = false,
    resumeTick: Int = 0,
    onAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }

    val prefs = remember {
        context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    var deckName by remember {
        mutableStateOf(prefs.getString(OverlayService.PREF_ANKI_DECK, "JP Lens") ?: "JP Lens")
    }

    // Re-read on each resume so returning from settings reflects the new state.
    val overlayOk = remember(resumeTick) { Settings.canDrawOverlays(context) }
    val notifOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
    val ankiInstalled = remember(resumeTick) { AnkiDroidHelper.isAnkiInstalled(context) }
    val ankiPermission = remember(resumeTick) { AnkiDroidHelper.hasPermission(context) }

    // Offline translation model (FuguMT, bundled in the APK): checking | present | missing.
    var modelState by remember { mutableStateOf("checking") }
    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) { Translator.assetPresent(context) }
        modelState = if (ok) "present" else "missing"
    }

    val ankiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        status = if (granted) "AnkiDroid access granted." else "AnkiDroid access denied."
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

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
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
            status = "Overlay running — tap the floating button to capture; hold it to switch mode or stop."
        } else {
            status = "Screen-capture permission denied."
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — the foreground service still starts */ }

    fun openOverlaySettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun openPlayStore(pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        runCatching { context.startActivity(market) }.onFailure {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            )
        }
    }

    fun startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOk) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            status = "Allow notifications, then press Start again."
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            status = "Grant the overlay permission, then press Start again."
            openOverlaySettings()
            return
        }
        // Resume the last mode used; switchable live via the floating button's hold menu.
        pendingMode = prefs.getInt(OverlayService.PREF_LAST_MODE, OverlayService.MODE_MORPHEME)
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("JP Lens", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Floating Japanese OCR — tap text on screen for dictionary, readings & translation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── Permissions ──────────────────────────────────────────────
        SectionCard("1 · Permissions") {
            StatusLine(overlayOk, if (overlayOk) "Draw over other apps — granted" else "Draw over other apps — required")
            if (!overlayOk) {
                OutlinedButton(onClick = { openOverlaySettings() }) { Text("Grant overlay permission") }
            }
            StatusLine(notifOk, if (notifOk) "Notifications — granted" else "Notifications — recommended")
            if (!notifOk) {
                OutlinedButton(onClick = {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Allow notifications") }
            }
            Hint("Screen-capture access is requested each time you press Start (system prompt).")
        }

        // ── Offline translation model ────────────────────────────────
        SectionCard("2 · Offline translation") {
            when (modelState) {
                "present" -> {
                    StatusLine(true, "FuguMT translation model bundled — works fully offline, on-device.")
                    Hint("Used for full-sentence translation in dict mode and range translation in word mode.")
                }
                "missing" -> {
                    StatusLine(false, "Translation model not built into this APK.")
                    Hint("Run scripts/build_fugumt.py to generate app/src/main/assets/fugumt/, then rebuild. Until then, full-sentence translations are unavailable.")
                }
                else -> Hint("Checking translation model…")
            }
        }

        // ── AnkiDroid ────────────────────────────────────────────────
        SectionCard("3 · Add to AnkiDroid (optional)") {
            EditableField(
                label = "Deck name",
                value = deckName,
                onSave = {
                    deckName = it
                    prefs.edit().putString(OverlayService.PREF_ANKI_DECK, it).apply()
                },
            )
            when {
                !ankiInstalled -> {
                    StatusLine(false, "AnkiDroid isn't installed")
                    OutlinedButton(onClick = { openPlayStore("com.ichi2.anki") }) { Text("Get AnkiDroid") }
                }
                !ankiPermission -> {
                    StatusLine(false, "AnkiDroid access not enabled")
                    OutlinedButton(onClick = { ankiPermLauncher.launch(AnkiDroidHelper.PERMISSION) }) {
                        Text("Enable AnkiDroid access")
                    }
                    Hint("If no dialog appears, open AnkiDroid → Settings → Advanced → “Enable AnkiDroid API”, then tap again.")
                }
                deckName.isBlank() -> StatusLine(false, "Enter a deck name above")
                else -> StatusLine(true, "Ready — cards go to deck “${deckName.trim()}”")
            }
            Hint("The “+” (add card) button shows on overlay words only when this is fully set up.")
        }

        // ── Start / Stop ─────────────────────────────────────────────
        Button(onClick = { startCapture() }, modifier = Modifier.fillMaxWidth()) {
            Text("Start")
        }
        OutlinedButton(
            onClick = {
                context.startService(
                    Intent(context, OverlayService::class.java).apply { action = OverlayService.ACTION_STOP }
                )
                status = "Stopped."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Stop") }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
        Hint("Tip: hold the floating button to switch mode or stop without reopening this screen.")
        Hint("Tip: manga panels that mix horizontal & vertical text can throw off OCR — if detection looks off, just zoom in on the panel and try again :)")

        TextButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
            Text("About & privacy")
        }
    }
}

/**
 * About screen: what the app is + how to reach the author, and a privacy/permissions
 * notice (the app uses Google ML Kit and screen capture, all on-device). The
 * "Open-source licenses" button toggles a static attribution list ([LICENSES_TEXT]).
 */
@Composable
private fun AboutScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    fun sendEmail() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:n.nayeem.rm@gmail.com")).apply {
            putExtra(Intent.EXTRA_SUBJECT, "JP Lens — feedback")
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "No email app found. Reach me at n.nayeem.rm@gmail.com", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        TextButton(onClick = onBack) { Text("← Back") }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // ImageView (not painterResource) so the adaptive launcher icon renders/masks correctly.
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher) }
                },
                modifier = Modifier.size(64.dp),
            )
            Column {
                Text("JP Lens", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                if (versionName.isNotBlank()) {
                    Text("Version $versionName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ── About ────────────────────────────────────────────────────
        SectionCard("About") {
            Text(
                "This app was created out of a wish to learn Japanese while playing games — " +
                    "read the text where it appears, look it up, and keep going. It is fully open source.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Found a bug, or have a suggestion or question? I'd love to hear it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { sendEmail() }) { Text("Email the author") }
            Hint("n.nayeem.rm@gmail.com")
        }

        // ── Privacy & permissions ────────────────────────────────────
        SectionCard("Privacy & permissions") {
            Text(
                "JP Lens does not collect, store, or share any personal data, and has no ads or analytics. " +
                    "Everything runs on your device — the app has no servers and makes no network requests.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("What stays on your device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "• Screen capture: when you press the floating button, the current screen is captured only " +
                    "to recognise text on it. It is processed in memory on your device and is never saved or uploaded.\n" +
                    "• Text recognition, word tokenisation, and the offline dictionary all run on-device.\n" +
                    "• Offline translation uses the FuguMT model bundled inside the app. It runs fully " +
                    "on-device and never downloads anything or contacts a server.\n" +
                    "• Your settings are stored only in this app's local storage.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Nothing is sent off your device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "• JP Lens makes no network requests. No screen text, image, or lookup ever leaves your device.\n" +
                    "• AnkiDroid (optional): if you tap “+”, the chosen word/card is written to the AnkiDroid app " +
                    "already on your device via its local API — nothing is sent to any server.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Permissions used", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "• Draw over other apps — to show the floating button and overlay boxes/popups.\n" +
                    "• Screen capture (asked each session) — to read text on screen.\n" +
                    "• Notifications — for the required “capture active” foreground-service notice.\n" +
                    "• AnkiDroid access (optional) — to add flashcards.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { showLicenses = !showLicenses }) {
                Text(if (showLicenses) "Hide open-source licenses" else "Open-source licenses")
            }
            if (showLicenses) {
                Hint(LICENSES_TEXT)
            }
        }
    }
}

/** Attribution for the third-party code and data JP Lens uses. Kept as plain text so it
 *  needs no build-time plugin (the OSS-licenses Gradle plugin isn't AGP 9 compatible). */
private const val LICENSES_TEXT =
    "Code libraries\n" +
        "• Jetpack Compose & AndroidX (Google) — Apache License 2.0\n" +
        "• Kotlin & kotlinx.coroutines (JetBrains) — Apache License 2.0\n" +
        "• Google ML Kit: Text Recognition — Google ML Kit Terms; components under Apache License 2.0\n" +
        "• ONNX Runtime (Microsoft) — MIT License\n" +
        "• Kuromoji IPADIC (com.atilika.kuromoji) — Apache License 2.0\n" +
        "• AnkiDroid API (com.github.ankidroid) — GNU GPL v3.0\n\n" +
        "Dictionary & language data\n" +
        "• JMdict / KANJIDIC2 © Electronic Dictionary Research and Development Group — CC BY-SA 4.0\n" +
        "• JLPT vocabulary lists by Jonathan Waller — CC BY 4.0\n" +
        "• FuguMT ja→en translation model by Satoshi Takahashi — CC BY-SA 4.0 (derives from Marian/OPUS-MT, Helsinki-NLP)\n\n" +
        "Full license texts are available from each project. JP Lens itself is open source."
