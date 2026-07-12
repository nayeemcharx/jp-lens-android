package com.nayeemcharx.jplens

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * The in-app "how to use JP Lens" tour — a 7-step carousel shown as a near-fullscreen
 * dialog. Steps 2–6 loop a short screen-recording (in assets/tutorial, played muted via
 * [LoopingVideo]); steps 1 & 7 are hand-built animated welcome / finish screens.
 *
 * Only the *settled* page's video is instantiated (see [pagerState.settledPage]) so at most
 * one [MediaPlayer]/hardware decoder is alive at a time.
 */

private sealed interface TutorialStep {
    data object Welcome : TutorialStep
    data class Demo(val asset: String, val title: String, val body: String) : TutorialStep
    data object Finish : TutorialStep
}

private val TUTORIAL_STEPS: List<TutorialStep> = listOf(
    TutorialStep.Welcome,
    TutorialStep.Demo(
        asset = "tutorial/start.mp4",
        title = "Start a session",
        body = "Press Start, and JP Lens asks for two permissions:\n\n" +
            "•  Draw over other apps — to show the floating button and results on top of your " +
            "game or reader.\n" +
            "•  Screen capture — to read the text on screen. It is processed on your device and " +
            "never saved or uploaded.\n\n" +
            "A floating button then appears over every app.",
    ),
    TutorialStep.Demo(
        asset = "tutorial/change_mode.mp4",
        title = "Switch modes",
        body = "Press and hold the floating button to open the mode menu, then slide to an option " +
            "and release.\n\n" +
            "There are two capture modes — Crop and Full screen — plus Stop. You can switch " +
            "any time.",
    ),
    TutorialStep.Demo(
        asset = "tutorial/crop.mp4",
        title = "Crop mode",
        body = "Tap the floating button, then drag a box around the text you want. JP Lens reads " +
            "just that area and opens the breakdown right away.\n\n" +
            "Perfect for a single manga speech bubble or one line of dialogue.",
    ),
    TutorialStep.Demo(
        asset = "tutorial/word_clicking.mp4",
        title = "Tap any word",
        body = "In the breakdown, every word is underlined. Tap one to see its dictionary " +
            "meaning, kanji, reading and JLPT level.\n\n" +
            "Tap “+” to save the word to AnkiDroid as a flashcard.",
    ),
    TutorialStep.Demo(
        asset = "tutorial/fullscreen.mp4",
        title = "Full screen mode",
        body = "Tap the floating button to lay clickable boxes over all the Japanese text on " +
            "screen. Tap any box to open its breakdown.\n\n" +
            "Best when you want to read a whole page at once.",
    ),
    TutorialStep.Finish,
)

@Composable
fun TutorialDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            TutorialCarousel(onDismiss)
        }
    }
}

@Composable
private fun TutorialCarousel(onDismiss: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { TUTORIAL_STEPS.size })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == TUTORIAL_STEPS.lastIndex

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp, bottom = 12.dp)) {

        // ── Top bar: step counter + close ────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${pagerState.currentPage + 1} / ${TUTORIAL_STEPS.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── Pages ────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val active = page == pagerState.settledPage
            when (val step = TUTORIAL_STEPS[page]) {
                is TutorialStep.Welcome -> WelcomePage(active)
                is TutorialStep.Finish -> FinishPage(active, onDismiss)
                is TutorialStep.Demo -> DemoPage(step, active)
            }
        }

        // ── Dots ─────────────────────────────────────────────────────
        PageDots(
            count = TUTORIAL_STEPS.size,
            current = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )

        // ── Navigation ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pagerState.currentPage > 0) {
                TextButton(onClick = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Skip") }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (isLast) onDismiss()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (isLast) "Get started" else "Next") }
        }
    }
}

/** Shared page scaffold: a title, a flexible visual area, and a body paragraph. */
@Composable
private fun DemoPage(step: TutorialStep.Demo, active: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            step.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                LoopingVideo(step.asset, Modifier.fillMaxSize().padding(6.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            step.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        )
    }
}

/** Step 1 — animated welcome: pulsing rings behind the app icon + fading-in copy. */
@Composable
private fun WelcomePage(active: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val infinite = rememberInfiniteTransition(label = "welcome")
    val pulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val appear = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) appear.animateTo(1f, tween(650)) else appear.snapTo(0f)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val base = size.minDimension / 2f * 0.42f
                for (i in 0..2) {
                    val p = (pulse + i / 3f) % 1f
                    val radius = base + p * base * 1.7f
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.30f),
                        radius = radius,
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx -> ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher) } },
                    modifier = Modifier.size(72.dp),
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Welcome to JP Lens",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(appear.value),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Read Japanese anywhere on your screen. This quick tour shows you how to capture " +
                "text, look up words, and build your vocabulary — all fully offline.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(appear.value),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Swipe or tap Next to begin →",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(appear.value * 0.9f),
        )
    }
}

/** Step 7 — animated finish: a springing check badge + a "get started" nudge. */
@Composable
private fun FinishPage(active: Boolean, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val scale = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        else scale.snapTo(0f)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale.value)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", fontWeight = FontWeight.Bold, color = accent, style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "You're all set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "Press Start whenever you're ready. Everything runs on your device — nothing leaves " +
                "your phone. Happy reading! 🎉",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp)) {
            Text("Get started")
        }
    }
}

/** Row of dots; the active one is wider and accent-coloured (animated). */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until count) {
            val selected = i == current
            val width by animateDpAsState(if (selected) 22.dp else 8.dp, label = "dotW")
            val color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Plays a bundled asset video on a loop, muted, letterboxed (fit-center) inside its bounds.
 * Backed by [LoopingVideoView] (a [TextureView] + [MediaPlayer]); the player is released when
 * the composable leaves composition. `.mp4` assets are stored uncompressed in the APK by
 * default, so [android.content.res.AssetManager.openFd] yields a seekable descriptor.
 */
@Composable
private fun LoopingVideo(assetPath: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx -> LoopingVideoView(ctx).also { it.setAsset(assetPath) } },
        onRelease = { it.release() },
    )
}

private class LoopingVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var assetPath: String? = null
    private var videoW = 0
    private var videoH = 0

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun setAsset(path: String) {
        assetPath = path
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        openPlayer(Surface(surface))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyAspect()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun openPlayer(surface: Surface) {
        val path = assetPath ?: return
        release()
        runCatching {
            val afd = context.assets.openFd(path)
            player = MediaPlayer().apply {
                setSurface(surface)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(0f, 0f)
                setOnVideoSizeChangedListener { _, w, h ->
                    videoW = w
                    videoH = h
                    applyAspect()
                }
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    /** Fit-center transform: scale the (stretched-to-fill) texture back to the video aspect. */
    private fun applyAspect() {
        val vw = videoW
        val vh = videoH
        val w = width
        val h = height
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return
        val f = minOf(w.toFloat() / vw, h.toFloat() / vh)
        val dw = vw * f
        val dh = vh * f
        val m = Matrix()
        m.setScale(dw / w, dh / h)
        m.postTranslate((w - dw) / 2f, (h - dh) / 2f)
        setTransform(m)
    }

    fun release() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
    }
}
