package com.nayeemcharx.jplens.overlay

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nayeemcharx.jplens.AnkiDroidHelper
import com.nayeemcharx.jplens.JapaneseTokenizer
import com.nayeemcharx.jplens.JmDict
import com.nayeemcharx.jplens.MainActivity
import com.nayeemcharx.jplens.OverlayService
import com.nayeemcharx.jplens.Translator
import com.nayeemcharx.jplens.WordEntry
import kotlin.math.abs
import kotlin.math.min

/**
 * The analysis popup — the draggable black card ("breakdown" in the UI) opened
 * by tapping a sentence box or committing a crop. The sentence *is* the
 * dictionary: every Kuromoji morpheme is underlined and tappable
 * ([setupInteractiveSentence] / [SentenceTextView]); tapping one expands its
 * JMdict/KANJIDIC2 entry inline in a rounded detail card, with the "+"
 * Add-to-Anki button in its header. Three home-screen toggles add
 * whole-sentence sections below (Reading / Romaji / Translation). Everything
 * runs on-device — nothing leaves the device.
 */
class AnalysisPopupController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** The popup window was just added — hide the sentence boxes under it. */
        fun onPopupShown()
        /** The popup went away — restore the sentence boxes. */
        fun onPopupDismissed()
    }

    companion object {
        private const val TAG = OverlayService.TAG
    }

    private var popupView: View? = null
    // Set by the popup's drag handler; suppresses auto-reposition so a
    // user-positioned popup doesn't jump when its content fills in.
    private var userMovedPopup = false
    // Popup requests load their data BEFORE the popup is shown; this token (main
    // thread only) lets a newer tap — or a dismissed/cleared overlay — supersede
    // an older lookup that's still in flight, so a stale popup can't appear.
    private var popupRequestSeq = 0

    val isShowing: Boolean get() = popupView != null

    /** Supersedes any popup lookup still in flight (e.g. a new capture started). */
    fun invalidatePending() {
        popupRequestSeq++
    }

    fun dismiss() {
        popupView?.let { runCatching { windowManager.removeView(it) } }
        popupView = null
        // Invalidate any popup lookup still in flight (e.g. overlays were cleared
        // while a tapped sentence was still translating).
        popupRequestSeq++
        // Bring the transparent sentence boxes back (hidden while the popup was up).
        listener.onPopupDismissed()
    }

    /**
     * A small "⧉" copy icon for a popup header. On tap it copies [text] to the
     * clipboard and briefly flashes a green ✓ (with a little scale pop) before
     * reverting. Used only for the main word/sentence — not the detail sections.
     */
    private fun makeCopyIcon(text: () -> CharSequence?): TextView {
        val idleColor = Color.argb(255, 200, 220, 255)
        val icon = TextView(context).apply {
            this.text = "⧉"
            setTextColor(idleColor)
            textSize = 16f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            isClickable = true
            isFocusable = false
            contentDescription = "Copy"
        }
        icon.setOnClickListener {
            val out = text()?.toString()
            if (out.isNullOrEmpty()) return@setOnClickListener
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("JP Lens", out))
            icon.text = "✓"
            icon.setTextColor(Color.argb(255, 90, 220, 120))
            icon.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                .withEndAction { icon.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
                .start()
            mainHandler.postDelayed({
                icon.text = "⧉"
                icon.setTextColor(idleColor)
            }, 1000)
        }
        return icon
    }

    // ─────────────────────── Word detail rendering ──────────────────────────

    /**
     * The header row of a tapped word's detail card: a leading "+" Add-to-Anki
     * button (when AnkiDroid is configured) followed by the word label
     * (`surface (reading) — meaning  [JLPT]`). The full JMdict/KANJIDIC2 detail is
     * rendered separately by [renderWordDetail] and stacked below this row.
     */
    private fun buildWordRow(
        entry: WordEntry,
        sentence: String,
        translation: String,
    ): View {
        // Show the surface (the text as it appears in the sentence) when we have it,
        // falling back to the lookup/word form when no surface was set.
        val display = entry.surface.ifEmpty { entry.word }
        val labelText = buildString {
            append(display)
            if (entry.reading.isNotEmpty() && entry.reading != display) {
                append(" (").append(entry.reading).append(')')
            }
            append(" — ").append(entry.meaning)
            if (entry.jlpt.isNotEmpty()) append("  [").append(entry.jlpt).append(']')
        }
        // The "+" Add-to-Anki button only appears when AnkiDroid is installed, its API
        // permission is granted, and a deck name is set (configured on the home screen).
        val showAnki = AnkiDroidHelper.isConfigured(context)
        // The label is weighted (0dp + weight 1), so it wraps inside whatever space
        // the fixed-width row leaves after the leading "+" button.
        val label = TextView(context).apply {
            text = labelText
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 13f
        }
        val addBtn: TextView? = if (showAnki) {
            val b = TextView(context).apply {
                text = "+"
                setTextColor(Color.argb(255, 200, 240, 200))
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(2), dp(10), dp(2))
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(Color.argb(60, 200, 240, 200))
                    cornerRadius = dp(6).toFloat()
                }
                contentDescription = "Add to AnkiDroid"
            }
            b.setOnClickListener {
                // Card back = the full JMdict detail blob for this word.
                handleAddToAnkiDict(entry, b, sentence, translation)
            }
            b
        } else null
        // "+" sits at the left, immediately before the word, so it stays close
        // to the word regardless of how wide the popup gets.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            if (addBtn != null) addView(addBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })
            addView(label, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
        }
        return row
    }

    // Colors for the tapped-word JMdict detail panel.
    private val cAccent = Color.argb(255, 180, 220, 255)   // labels / sense numbers
    private val cBody = Color.argb(255, 235, 235, 235)     // glosses
    private val cPos = Color.argb(255, 150, 205, 150)      // part of speech
    private val cDim = Color.argb(255, 160, 160, 160)      // tags / notes / forms

    private fun SpannableStringBuilder.styled(
        text: CharSequence,
        color: Int,
        sizeRatio: Float = 1f,
        italic: Boolean = false,
        bold: Boolean = false,
    ): SpannableStringBuilder {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (sizeRatio != 1f)
            setSpan(RelativeSizeSpan(sizeRatio), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic || bold) {
            val style = when {
                italic && bold -> Typeface.BOLD_ITALIC
                italic -> Typeface.ITALIC
                else -> Typeface.BOLD
            }
            setSpan(StyleSpan(style), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return this
    }

    private val circledNumbers = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"
    private fun circled(n: Int): String =
        if (n in 1..circledNumbers.length) circledNumbers[n - 1].toString() else "($n)"

    /** Distinct kanji characters appearing in [word], each with its KANJIDIC2 info. */
    private fun kanjiInfoForWord(word: String): List<Pair<Char, JmDict.KanjiInfo>> {
        val out = ArrayList<Pair<Char, JmDict.KanjiInfo>>()
        val seen = HashSet<Char>()
        for (c in word) {
            if (!JapaneseTokenizer.containsKanji(c.toString()) || !seen.add(c)) continue
            val ki = JmDict.lookupKanji(c) ?: continue
            out += c to ki
        }
        return out
    }

    /**
     * Renders the full JMdict entry/entries for a word into a styled vertical column,
     * followed by a Kanji subsection for the kanji that appear in this word.
     */
    private fun renderWordDetail(
        details: List<JmDict.WordDetail>,
        kanji: List<Pair<Char, JmDict.KanjiInfo>>,
        textMaxW: Int,
    ): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val labelW = textMaxW - dp(12)

        if (details.isEmpty()) {
            col.addView(detailLine(
                SpannableStringBuilder().styled("(not in JMdict)", cDim, 0.9f, italic = true), labelW
            ))
        }
        for ((wi, wd) in details.withIndex()) {
            if (wi > 0) {
                // Thin separator between homograph entries.
                col.addView(View(context).apply {
                    setBackgroundColor(Color.argb(40, 255, 255, 255))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    .apply { topMargin = dp(6); bottomMargin = dp(6) })
            }

            // Writings — each on its own line: text, green ● if common, then any
            // status tags decoded (rarely-used kanji, search-only, irregular, …).
            if (wd.writings.isNotEmpty()) {
                val sb = SpannableStringBuilder()
                sb.styled("Writings", cAccent, 0.85f, bold = true)
                for (w in wd.writings) {
                    sb.append("\n")
                    sb.styled("  " + w.text, cBody)
                    if (w.common) sb.styled("  ●", cPos, 0.8f)
                    if (w.jlpt.isNotEmpty()) sb.styled("  [${w.jlpt}]", cPos, 0.8f, bold = true)
                    for (t in w.tags) sb.styled("  " + JmDict.tagLabel(t), cDim, 0.8f, italic = true)
                }
                col.addView(detailLine(sb, labelW))
            }
            // Readings — text, ● if common, status tags, and (when restricted) which
            // kanji writings the reading applies to.
            if (wd.readings.isNotEmpty()) {
                val sb = SpannableStringBuilder()
                sb.styled("Readings", cAccent, 0.85f, bold = true)
                for (r in wd.readings) {
                    sb.append("\n")
                    sb.styled("  " + r.text, cBody)
                    if (r.common) sb.styled("  ●", cPos, 0.8f)
                    if (r.jlpt.isNotEmpty()) sb.styled("  [${r.jlpt}]", cPos, 0.8f, bold = true)
                    for (t in r.tags) sb.styled("  " + JmDict.tagLabel(t), cDim, 0.8f, italic = true)
                    val ak = r.appliesToKanji.filter { it != "*" }
                    if (ak.isNotEmpty())
                        sb.styled("  → " + ak.joinToString("、"), cDim, 0.8f, italic = true)
                }
                col.addView(detailLine(sb, labelW))
            }

            for ((si, sense) in wd.senses.withIndex()) {
                val sb = SpannableStringBuilder()
                sb.styled(circled(si + 1) + " ", cAccent, bold = true)
                if (sense.partOfSpeech.isNotEmpty()) {
                    sb.styled(
                        sense.partOfSpeech.joinToString(", ") { JmDict.tagLabel(it) } + "  ",
                        cPos, 0.85f, italic = true
                    )
                }
                sb.styled(sense.glosses.joinToString("; "), cBody)

                // Inline tag chips: misc, field, dialect.
                val chips = (sense.misc + sense.fields + sense.dialects).map { JmDict.tagLabel(it) }
                if (chips.isNotEmpty()) {
                    sb.styled("  " + chips.joinToString(" ") { "[$it]" }, cDim, 0.85f, italic = true)
                }
                // Loanword origin.
                for (ls in sense.langSources) {
                    val txt = buildString {
                        append("  from ").append(ls.lang)
                        if (!ls.text.isNullOrEmpty()) append(": ").append(ls.text)
                        if (ls.wasei) append(" (wasei)")
                    }
                    sb.styled(txt, cDim, 0.85f, italic = true)
                }
                if (sense.info.isNotEmpty()) {
                    sb.append("\n")
                    sb.styled("   note: " + sense.info.joinToString("; "), cDim, 0.85f)
                }
                if (sense.related.isNotEmpty()) {
                    sb.append("\n")
                    sb.styled("   → see " + sense.related.joinToString("、"), cDim, 0.85f)
                }
                if (sense.antonyms.isNotEmpty()) {
                    sb.append("\n")
                    sb.styled("   ⇄ " + sense.antonyms.joinToString("、"), cDim, 0.85f)
                }

                col.addView(detailLine(sb, labelW, topPad = dp(1)))
            }
        }

        // Kanji used in this word (KANJIDIC2 meaning + JLPT).
        if (kanji.isNotEmpty()) {
            val sb = SpannableStringBuilder()
            sb.styled("Kanji", cAccent, 0.85f, bold = true)
            for ((ch, ki) in kanji) {
                sb.append("\n")
                sb.styled("  $ch", cBody)
                sb.styled("  " + ki.meaning, cDim, 0.9f)
                if (ki.jlpt.isNotEmpty()) sb.styled("  [${ki.jlpt}]", cPos, 0.8f)
            }
            col.addView(detailLine(sb, labelW, topPad = dp(2)))
        }
        return col
    }

    private fun detailLine(content: CharSequence, maxW: Int, topPad: Int = 0): TextView =
        TextView(context).apply {
            text = content
            textSize = 13f
            maxWidth = maxW
            setPadding(0, topPad, 0, dp(3))
        }

    // ─────────────────────── Interactive sentence ──────────────────────────
    // One tappable morpheme in the popup's sentence. Ranges come straight from
    // Kuromoji ([JapaneseTokenizer.extract]); [lookupKey]/[reading]/[surface]
    // need no dictionary, so they're computed up front. The gloss + full JMdict
    // detail are fetched lazily when the word is tapped.
    private class SentenceWord(
        val start: Int,
        val end: Int,
        val surface: String,
        val lookupKey: String,
        val reading: String,
    )

    // Interactive-sentence colors.
    private val cWordUnderline = Color.argb(150, 150, 195, 240)     // idle morpheme underline
    private val cWordUnderlineSel = Color.argb(255, 120, 205, 255)  // selected morpheme underline
    private val cWordTextSel = Color.rgb(150, 212, 255)             // selected morpheme text
    private val cWordChip = Color.argb(48, 120, 185, 255)           // selected morpheme chip bg

    /**
     * A TextView that draws a short underline under each Kuromoji morpheme with a
     * gap between adjacent ones, so the splits read as separate, tappable chunks (a
     * single continuous underline would hide the divisions). The selected morpheme
     * gets a rounded highlight chip + a brighter underline (its text is recolored
     * by a span the caller manages). Multi-line safe — a morpheme that wraps draws
     * one segment per line. Hit-testing is done by this view's own geometry
     * (onTouchEvent → wordAt), reusing the same per-line spans as the underlines.
     */
    private inner class SentenceTextView(context: Context) : androidx.appcompat.widget.AppCompatTextView(context) {
        var segments: List<SentenceWord> = emptyList()
        var selected: SentenceWord? = null
        // Tap callback — resolved by our own geometry hit-test (see onTouchEvent),
        // not LinkMovementMethod, which misses the last char of a wrapped line.
        var onWordTap: ((SentenceWord) -> Unit)? = null

        private val d = resources.displayMetrics.density
        private val hitPad = d * 4f           // horizontal slack so small end glyphs are tappable
        private val touchSlop = d * 8f
        private var downX = 0f
        private var downY = 0f
        private val gap = d * 3.5f            // half-gap trimmed off each underline end
        private val underlineDrop = d * 3f    // below the baseline
        private val chipRadius = d * 5f
        private val chipPadX = d * 2.5f
        private val chipPadY = d * 2f
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            val lay = layout
            if (lay == null) { super.onDraw(canvas); return }
            val cLeft = totalPaddingLeft.toFloat()
            val cTop = totalPaddingTop.toFloat()
            val fm = paint.fontMetrics

            // Selected chip snug around the glyphs (drawn before the text). Bounds
            // come from the font metrics around each line's baseline, so extra line
            // spacing doesn't stretch the chip.
            selected?.let { seg ->
                chipPaint.color = cWordChip
                forEachLineSpan(lay, seg.start, seg.end) { line, xa, xb ->
                    val baseline = lay.getLineBaseline(line) + cTop
                    val top = baseline + fm.ascent - chipPadY
                    val bottom = baseline + underlineDrop + chipPadY
                    canvas.drawRoundRect(
                        RectF(cLeft + xa - chipPadX, top, cLeft + xb + chipPadX, bottom),
                        chipRadius, chipRadius, chipPaint
                    )
                }
            }

            super.onDraw(canvas)

            // One gapped underline per morpheme (per wrapped line).
            for (seg in segments) {
                val sel = seg === selected
                linePaint.color = if (sel) cWordUnderlineSel else cWordUnderline
                linePaint.strokeWidth = if (sel) d * 2f else d * 1.4f
                forEachLineSpan(lay, seg.start, seg.end) { line, xa, xb ->
                    val left = cLeft + xa + gap
                    val right = cLeft + xb - gap
                    if (right <= left) return@forEachLineSpan
                    val y = lay.getLineBaseline(line) + cTop + underlineDrop
                    canvas.drawLine(left, y, right, y, linePaint)
                }
            }
        }

        // Invokes [block] once per screen line the [start,end) range covers, with
        // the range's left/right x on that line (in layout coords, padding added by
        // the caller).
        private inline fun forEachLineSpan(
            lay: android.text.Layout, start: Int, end: Int,
            block: (line: Int, xa: Float, xb: Float) -> Unit,
        ) {
            if (start >= end) return
            val first = lay.getLineForOffset(start)
            // Anchor the last line on the last *included* char (end is exclusive) so
            // a range ending exactly at a wrap boundary doesn't spill onto the line
            // it merely touches.
            val last = lay.getLineForOffset(end - 1)
            for (line in first..last) {
                val lineStart = lay.getLineStart(line)
                val lineEnd = lay.getLineEnd(line)
                val ls = maxOf(start, lineStart)
                val le = minOf(end, lineEnd)
                if (ls >= le) continue
                val xa = lay.getPrimaryHorizontal(ls)
                // At a wrap boundary getPrimaryHorizontal(lineEnd) points at the
                // START of the next line (x≈0), so for a range that runs to the end
                // of this line use the line's text extent (getLineMax = trailing edge
                // of the last glyph; getLineRight would be the full layout width).
                val xb = if (le >= lineEnd) lay.getLineMax(line)
                         else lay.getPrimaryHorizontal(le)
                block(line, minOf(xa, xb), maxOf(xa, xb))
            }
        }

        // Own hit-testing (replaces LinkMovementMethod): resolve the tapped morpheme
        // from the exact per-line spans the underlines are painted from, so the last
        // glyph of a wrapped line is reachable. Consumes only genuine taps; drags
        // fall through so the enclosing ScrollView can still scroll.
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                        wordAt(event.x, event.y)?.let { onWordTap?.invoke(it); return true }
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        /** The morpheme whose painted span (with a little slack) contains the point, nearest first. */
        private fun wordAt(ex: Float, ey: Float): SentenceWord? {
            val lay = layout ?: return null
            val x = ex - totalPaddingLeft
            val y = (ey - totalPaddingTop).toInt().coerceIn(0, height)
            val line = lay.getLineForVertical(y)
            var best: SentenceWord? = null
            var bestDist = Float.MAX_VALUE
            for (seg in segments) {
                forEachLineSpan(lay, seg.start, seg.end) { l, xa, xb ->
                    if (l != line || x < xa - hitPad || x > xb + hitPad) return@forEachLineSpan
                    val dist = when {
                        x < xa -> xa - x
                        x > xb -> x - xb
                        else -> 0f
                    }
                    if (dist < bestDist) { bestDist = dist; best = seg }
                }
            }
            return best
        }
    }

    /**
     * Turns the popup's sentence into a run of individually-underlined, tappable
     * morphemes (drawn by [SentenceTextView]). Tapping one expands its dictionary
     * entry (reading + JMdict/KANJIDIC2 detail) inline in
     * [PopupUi.wordDetailPanel] with a height animation and marks the word
     * selected (chip + colored text + brighter underline); tapping the same word
     * again collapses it, and tapping a different word swaps the content. The popup
     * width is fixed, so the panel only ever grows/shrinks vertically.
     */
    private fun setupInteractiveSentence(
        ui: PopupUi,
        sentence: String,
        words: List<SentenceWord>,
        translation: String,
    ) {
        val title = ui.titleView
        val panel = ui.wordDetailPanel
        val spannable = SpannableStringBuilder(sentence)
        // Width the detail panel lays out at (fixed popup width minus the panel's
        // own horizontal padding) — used to pre-measure the expand target height.
        val panelContentW = (ui.textMaxW - dp(16)).coerceAtLeast(dp(80))

        // Selection + async-load state, private to this popup instance.
        var selectedStart = -1
        var loadSeq = 0
        var textSpan: ForegroundColorSpan? = null

        // Marks [w] selected (or clears selection when null): recolors that word's
        // text and tells the view which morpheme to chip/brighten.
        fun setSelection(w: SentenceWord?) {
            textSpan?.let { spannable.removeSpan(it) }
            textSpan = null
            if (w != null) {
                val s = ForegroundColorSpan(cWordTextSel)
                spannable.setSpan(s, w.start, w.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                textSpan = s
            }
            title.selected = w
            title.invalidate()
        }

        fun collapse() {
            selectedStart = -1
            loadSeq++
            setSelection(null)
            if (panel.visibility == View.VISIBLE) animateCollapse(panel) { panel.removeAllViews() }
        }

        fun select(w: SentenceWord) {
            selectedStart = w.start
            setSelection(w)
            val mySeq = ++loadSeq
            val fetchAndExpand = {
                Thread {
                    val info = runCatching { JmDict.lookupWord(w.lookupKey) }.getOrNull()
                    val entry = WordEntry(
                        w.lookupKey, w.reading,
                        info?.gloss ?: "(not in dictionary)",
                        info?.jlpt ?: "", surface = w.surface
                    )
                    val details = runCatching { JmDict.lookupWordDetail(w.lookupKey) }
                        .getOrDefault(emptyList())
                    val kanji = kanjiInfoForWord(w.lookupKey)
                    mainHandler.post {
                        // Superseded by a newer tap / collapse while loading.
                        if (mySeq != loadSeq) return@post
                        panel.removeAllViews()
                        panel.addView(buildWordRow(entry, sentence, translation))
                        if (details.isNotEmpty() || kanji.isNotEmpty())
                            panel.addView(renderWordDetail(details, kanji, panelContentW))
                        // Measure at the panel's true outer width (its inner padding
                        // is already accounted for in panelContentW).
                        animateExpand(panel, ui.textMaxW)
                    }
                }.start()
            }
            // Switching words: collapse the open panel first, then load + expand.
            if (panel.visibility == View.VISIBLE) {
                animateCollapse(panel) { panel.removeAllViews(); fetchAndExpand() }
            } else fetchAndExpand()
        }

        // Tap handling is done by SentenceTextView's own geometry hit-test (it owns
        // all the underline/selection painting), so the last glyph of a wrapped line
        // stays reachable — unlike LinkMovementMethod, which resolved a trailing tap
        // to the next line's offset.
        title.onWordTap = { w ->
            if (selectedStart == w.start && panel.visibility == View.VISIBLE) collapse()
            else select(w)
        }

        title.segments = words
        title.setText(spannable, TextView.BufferType.SPANNABLE)
        title.highlightColor = Color.TRANSPARENT
    }

    /**
     * Expands [view] from 0 to its natural height (measured at [widthPx]) with a
     * fade-in, restoring WRAP_CONTENT at the end so later content changes still fit.
     */
    private fun animateExpand(view: View, widthPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val target = view.measuredHeight
        val lp = view.layoutParams
        lp.height = 0
        view.layoutParams = lp
        view.visibility = View.VISIBLE
        view.alpha = 0f
        ValueAnimator.ofInt(0, target).apply {
            duration = 200
            addUpdateListener {
                lp.height = it.animatedValue as Int
                view.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    view.layoutParams = lp
                }
            })
            start()
        }
        view.animate().alpha(1f).setDuration(200).start()
    }

    /** Collapses [view] to 0 height with a fade-out, sets it GONE, then runs [onEnd]. */
    private fun animateCollapse(view: View, onEnd: (() -> Unit)? = null) {
        val from = if (view.height > 0) view.height else 0
        val lp = view.layoutParams
        ValueAnimator.ofInt(from, 0).apply {
            duration = 160
            addUpdateListener {
                lp.height = it.animatedValue as Int
                view.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    view.visibility = View.GONE
                    lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                    view.alpha = 1f
                    view.layoutParams = lp
                    onEnd?.invoke()
                }
            })
            start()
        }
        view.animate().alpha(0f).setDuration(160).start()
    }

    // ─────────────────────────── AnkiDroid ─────────────────────────────────

    /** Shared AnkiDroid guards. Returns false (after toasting / prompting) if not ready. */
    private fun ankiPreflight(): Boolean {
        if (!AnkiDroidHelper.isAnkiInstalled(context)) {
            Toast.makeText(context, "AnkiDroid is not installed", Toast.LENGTH_LONG).show()
            return false
        }
        if (!AnkiDroidHelper.hasPermission(context)) {
            // Permission can only be requested from an Activity. Launch
            // MainActivity with a flag so it prompts on resume.
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_REQUEST_ANKI_PERMISSION, true)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Grant AnkiDroid permission in JP Lens, then tap + again.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    /** Updates the "+" button + toasts based on the add result. Call on the main thread. */
    private fun applyAnkiResult(result: AnkiDroidHelper.AddResult, btn: TextView, word: String) {
        when (result) {
            is AnkiDroidHelper.AddResult.Added -> {
                btn.text = "✓"
                btn.setTextColor(Color.argb(255, 120, 200, 120))
                Toast.makeText(context, "Added \"$word\" to Anki", Toast.LENGTH_SHORT).show()
            }
            is AnkiDroidHelper.AddResult.Duplicate -> {
                btn.text = "✓"
                btn.setTextColor(Color.argb(255, 180, 180, 180))
                Toast.makeText(context, "\"$word\" already in Anki", Toast.LENGTH_SHORT).show()
            }
            is AnkiDroidHelper.AddResult.Failed -> {
                Toast.makeText(context, "Anki: ${result.message}", Toast.LENGTH_LONG).show()
                btn.isEnabled = true
                btn.alpha = 1f
            }
        }
    }

    /**
     * Dict-mode add: front = the word (kanji form, no kana reading); back = the full
     * JMdict detail blob rendered as HTML (all senses, POS, tags, xrefs, loanword, …).
     */
    private fun handleAddToAnkiDict(
        entry: WordEntry,
        btn: TextView,
        sentence: String,
        translation: String,
    ) {
        if (!ankiPreflight()) return
        btn.isEnabled = false
        btn.alpha = 0.5f
        Thread {
            val details = JmDict.lookupWordDetail(entry.word)
            val kanji = kanjiInfoForWord(entry.word)
            val back = buildAnkiBackHtml(entry, details, kanji, sentence, translation)
            val result = AnkiDroidHelper.addCard(context, entry.word, back)
            mainHandler.post { applyAnkiResult(result, btn, entry.word) }
        }.start()
    }

    private fun htmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * Renders the Anki card back as HTML (Anki cards display field content through a
     * WebView). Sections: the full JMdict detail (or a summary fallback when the word
     * isn't in JMdict), the kanji used in this word, and the source sentence + its
     * translation. Colors are chosen to read on Anki's default white background.
     */
    private fun buildAnkiBackHtml(
        entry: WordEntry,
        details: List<JmDict.WordDetail>,
        kanji: List<Pair<Char, JmDict.KanjiInfo>>,
        sentence: String,
        translation: String,
    ): String {
        val accent = "#1565c0"   // headers / sense numbers
        val pos = "#2e7d32"      // part of speech / JLPT
        val dim = "#777777"      // tags / notes / forms-status / translation
        val sb = StringBuilder()
        sb.append("<div style=\"text-align:left\">")

        // JMdict detail, or a minimal fallback when the word isn't in JMdict.
        if (details.isEmpty()) {
            if (entry.reading.isNotEmpty() && entry.reading != entry.word)
                sb.append("<div>").append(htmlEscape(entry.reading)).append("</div>")
            sb.append("<div>").append(htmlEscape(entry.meaning)).append("</div>")
        } else {
            for ((wi, wd) in details.withIndex()) {
                if (wi > 0) sb.append("<hr>")
                if (wd.writings.isNotEmpty()) {
                    sb.append("<div style=\"color:$accent;font-weight:bold\">Writings</div>")
                    for (w in wd.writings) {
                        sb.append("<div>").append(htmlEscape(w.text))
                        if (w.common) sb.append(" <span style=\"color:$pos\">●</span>")
                        if (w.jlpt.isNotEmpty())
                            sb.append(" <span style=\"color:$pos;font-weight:bold\">[")
                                .append(htmlEscape(w.jlpt)).append("]</span>")
                        for (t in w.tags)
                            sb.append(" <span style=\"color:$dim;font-style:italic\">")
                                .append(htmlEscape(JmDict.tagLabel(t))).append("</span>")
                        sb.append("</div>")
                    }
                }
                if (wd.readings.isNotEmpty()) {
                    sb.append("<div style=\"color:$accent;font-weight:bold\">Readings</div>")
                    for (r in wd.readings) {
                        sb.append("<div>").append(htmlEscape(r.text))
                        if (r.common) sb.append(" <span style=\"color:$pos\">●</span>")
                        if (r.jlpt.isNotEmpty())
                            sb.append(" <span style=\"color:$pos;font-weight:bold\">[")
                                .append(htmlEscape(r.jlpt)).append("]</span>")
                        for (t in r.tags)
                            sb.append(" <span style=\"color:$dim;font-style:italic\">")
                                .append(htmlEscape(JmDict.tagLabel(t))).append("</span>")
                        val ak = r.appliesToKanji.filter { it != "*" }
                        if (ak.isNotEmpty())
                            sb.append(" <span style=\"color:$dim\">→ ")
                                .append(htmlEscape(ak.joinToString("、"))).append("</span>")
                        sb.append("</div>")
                    }
                }
                for ((si, sense) in wd.senses.withIndex()) {
                    sb.append("<div style=\"margin-top:4px\">")
                    sb.append("<span style=\"color:$accent;font-weight:bold\">")
                        .append(circled(si + 1)).append("</span> ")
                    if (sense.partOfSpeech.isNotEmpty())
                        sb.append("<span style=\"color:$pos;font-style:italic\">")
                            .append(htmlEscape(sense.partOfSpeech.joinToString(", ") { JmDict.tagLabel(it) }))
                            .append("</span> ")
                    sb.append(htmlEscape(sense.glosses.joinToString("; ")))
                    val chips = (sense.misc + sense.fields + sense.dialects).map { JmDict.tagLabel(it) }
                    if (chips.isNotEmpty())
                        sb.append(" <span style=\"color:$dim;font-style:italic\">")
                            .append(htmlEscape(chips.joinToString(" ") { "[$it]" })).append("</span>")
                    for (ls in sense.langSources) {
                        val txt = buildString {
                            append("from ").append(ls.lang)
                            if (!ls.text.isNullOrEmpty()) append(": ").append(ls.text)
                            if (ls.wasei) append(" (wasei)")
                        }
                        sb.append(" <span style=\"color:$dim;font-style:italic\">")
                            .append(htmlEscape(txt)).append("</span>")
                    }
                    if (sense.info.isNotEmpty())
                        sb.append("<div style=\"color:$dim\">note: ")
                            .append(htmlEscape(sense.info.joinToString("; "))).append("</div>")
                    if (sense.related.isNotEmpty())
                        sb.append("<div style=\"color:$dim\">→ see ")
                            .append(htmlEscape(sense.related.joinToString("、"))).append("</div>")
                    if (sense.antonyms.isNotEmpty())
                        sb.append("<div style=\"color:$dim\">⇄ ")
                            .append(htmlEscape(sense.antonyms.joinToString("、"))).append("</div>")
                    sb.append("</div>")
                }
            }
        }

        // Kanji used in this word.
        if (kanji.isNotEmpty()) {
            sb.append("<div style=\"color:$accent;font-weight:bold;margin-top:6px\">Kanji</div>")
            for ((ch, ki) in kanji) {
                sb.append("<div>").append(htmlEscape(ch.toString()))
                    .append(" — ").append(htmlEscape(ki.meaning))
                if (ki.jlpt.isNotEmpty())
                    sb.append(" <span style=\"color:$pos\">[")
                        .append(htmlEscape(ki.jlpt)).append("]</span>")
                sb.append("</div>")
            }
        }

        // Source sentence + its translation.
        if (sentence.isNotEmpty()) {
            sb.append("<div style=\"color:$accent;font-weight:bold;margin-top:6px\">Sentence</div>")
            sb.append("<div>").append(htmlEscape(sentence)).append("</div>")
            if (translation.isNotEmpty())
                sb.append("<div style=\"color:$dim\">").append(htmlEscape(translation)).append("</div>")
        }

        sb.append("</div>")
        return sb.toString()
    }

    // ─────────────────────────── Popup scaffold ────────────────────────────

    /**
     * The analysis popup scaffold. The caller makes the sentence interactive
     * ([setupInteractiveSentence] fills [titleView] + drives [wordDetailPanel])
     * and fills the whole-sentence sections (Reading, Translation), then calls
     * [show] — the window is only added once fully populated, so it appears in
     * its final position at its final size (no loading placeholder → no flicker).
     */
    private class PopupUi(
        val container: View,
        val titleView: SentenceTextView,
        val wordDetailPanel: LinearLayout,
        val readingHeader: TextView,
        val readingBody: TextView,
        val romajiHeader: TextView,
        val romajiBody: TextView,
        val transHeader: TextView,
        val transBody: TextView,
        val textMaxW: Int,
        val show: () -> Unit,
    )

    /**
     * Builds the analysis popup for [anchor] but does NOT add the window —
     * populate the sections first, then call [PopupUi.show].
     *
     * Layout rules that keep it glitch-free:
     * - The window is only added once fully populated ([PopupUi.show]),
     *   measured at its final size and anchored in one shot — no placeholder,
     *   no post-load reposition, no flicker. While it's up the transparent
     *   sentence boxes are hidden ([Listener.onPopupShown]); they return when
     *   it closes.
     * - The window width is **fixed** (`min(screen − 16dp, 400dp)`, floored so it
     *   stays comfortably wide for short sentences) — content can never change the
     *   popup's width, so text wraps predictably and the tapped-word detail panel
     *   only ever grows/shrinks vertically, never sideways.
     * - Height wraps content but is hard-capped (~65% of screen) by the section
     *   ScrollView, whose cap is computed from what the top bar actually used. A
     *   layout listener re-clamps the window position whenever the height changes
     *   (word-detail expand/collapse), so the popup can never end up past the
     *   bottom edge — even after the user dragged it.
     * - Top bar = centered grip + ✕ at the right. The interactive sentence, the
     *   tapped-word detail panel, and the section bodies all live inside the
     *   scroll area, so a long sentence scrolls instead of being clipped.
     * - Dragging is via the top bar ONLY (the sentence is tappable now — a drag
     *   listener on it would fight the word taps and the scroll gesture).
     */
    private fun buildAnalysisPopup(anchor: Rect, title: String): PopupUi {
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val sideMargin = dp(8)
        // Fixed, content-independent width so the popup never changes size sideways
        // (and stays comfortably wide even for a one-word sentence): the usual
        // 400dp, but never below 320dp unless the screen itself is narrower.
        val maxW = screenW - sideMargin * 2
        val popupW = min(maxW, dp(400)).coerceAtLeast(min(maxW, dp(320)))
        val maxPopupH = (screenH * 0.65f).toInt()
        // Width available to content inside the container padding — labels and
        // detail panels wrap before hitting the popup edge.
        val textMaxW = popupW - dp(12) - dp(12)

        // ── Top bar: drag grip centered, ✕ at the right ──
        val gripBar = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.argb(160, 255, 255, 255))
            }
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(60, 255, 255, 255))
            }
            contentDescription = "Close"
            setOnClickListener { dismiss() }
        }
        val topBar = FrameLayout(context).apply {
            minimumHeight = dp(36)
            addView(gripBar, FrameLayout.LayoutParams(dp(48), dp(5)).apply {
                gravity = Gravity.CENTER
            })
            addView(closeBtn, FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            })
        }

        // ── Sentence row (scrolls with the rest): the sentence, with each Kuromoji
        // morpheme underlined + tappable (spans installed by setupInteractiveSentence),
        // plus a copy icon that copies the whole sentence. Larger text than the
        // sections so the tap targets are comfortable. ──
        val titleView = SentenceTextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            // Extra line spacing leaves room for the per-morpheme underlines below
            // the baseline (drawn by SentenceTextView) and keeps taps comfortable.
            setLineSpacing(dp(6).toFloat(), 1f)
            // Bottom padding so the LAST line's underline/chip (drawn ~5dp below the
            // baseline) isn't clipped by the view bounds — Android drops the last
            // line's spacingAdd from the layout height, and on some fonts (e.g. the
            // Redmi system font) the descent alone is smaller than underlineDrop, so
            // without this the final line's underlines vanish. Small top padding too
            // so the first line's chip has headroom.
            setPadding(0, dp(2), 0, dp(6))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(titleView, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(makeCopyIcon { title }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            setPadding(0, dp(2), 0, dp(6))
        }

        // The tapped word's dictionary entry expands here (below the sentence,
        // above the whole-sentence sections). A subtle rounded card, animated
        // open/closed by setupInteractiveSentence.
        val wordDetailPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.argb(38, 255, 255, 255))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        val readingHeader = TextView(context).apply {
            text = "Reading"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        val readingBody = TextView(context).apply {
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 14f
            maxWidth = textMaxW
            visibility = View.GONE
        }
        val romajiHeader = TextView(context).apply {
            text = "Romaji"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        val romajiBody = TextView(context).apply {
            setTextColor(Color.argb(255, 230, 230, 230))
            textSize = 14f
            maxWidth = textMaxW
            visibility = View.GONE
        }
        val transHeader = TextView(context).apply {
            text = "Translation"
            setTextColor(Color.argb(255, 180, 220, 255))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            visibility = View.GONE
        }
        val transBody = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = textMaxW
            visibility = View.GONE
        }
        val sectionsCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleRow)
            addView(wordDetailPanel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(4) })
            addView(readingHeader)
            addView(readingBody)
            addView(romajiHeader)
            addView(romajiBody)
            addView(transHeader)
            addView(transBody)
        }
        // ScrollView that caps its own height so the top bar + sentence + sections
        // never exceed maxPopupH. Below the cap it wraps to content (the popup stays
        // small for short answers); past the cap it scrolls — so a long sentence
        // scrolls rather than being clipped. The vertical LinearLayout parent
        // measures children top-to-bottom, so the top bar is already measured here.
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val used = topBar.measuredHeight + dp(20)
                val cap = (maxPopupH - used).coerceAtLeast(dp(80))
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            addView(sectionsCol, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(238, 0, 0, 0))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(4), dp(12), dp(10))
            addView(topBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // The window width is exact, so only the height ever needs measuring.
        fun measuredHeight(): Int {
            val wSpec = View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(maxPopupH, View.MeasureSpec.AT_MOST)
            container.measure(wSpec, hSpec)
            return container.measuredHeight
        }
        fun anchoredX(): Int = (anchor.centerX() - popupW / 2)
            .coerceIn(sideMargin, (screenW - sideMargin - popupW).coerceAtLeast(sideMargin))
        fun anchoredY(ph: Int): Int {
            var y = anchor.top - ph - dp(6)               // prefer above the anchor
            if (y < sideMargin) y = anchor.bottom + dp(6) // else below it
            y = y.coerceAtMost(screenH - sideMargin - ph) // never past the bottom
            return y.coerceAtLeast(sideMargin)
        }

        val params = WindowManager.LayoutParams(
            popupW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // Keeps the popup fully on-screen at its *current* position (after drags
        // and content growth) — moves it back just enough, without re-anchoring.
        fun clampToScreen() {
            val lp = container.layoutParams as? WindowManager.LayoutParams ?: return
            val h = if (container.height > 0) container.height else measuredHeight()
            val nx = lp.x.coerceIn(0, (screenW - popupW).coerceAtLeast(0))
            val ny = lp.y.coerceIn(0, (screenH - h).coerceAtLeast(0))
            if (nx != lp.x || ny != lp.y) {
                lp.x = nx
                lp.y = ny
                runCatching { windowManager.updateViewLayout(container, lp) }
            }
        }

        // Adds the window — call only after the sections are populated, so the
        // popup appears once, at its final size and position. The sentence boxes
        // are hidden while it's up (dismiss brings them back).
        fun show() {
            dismiss()
            userMovedPopup = false
            val ph = measuredHeight()
            params.x = anchoredX()
            params.y = anchoredY(ph)
            try {
                windowManager.addView(container, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add analysis popup", e)
                return
            }
            popupView = container
            listener.onPopupShown()
            // Whatever grows the popup later (the tapped-word detail panel), never
            // let it hang past the screen edge — this runs regardless of whether the
            // user has dragged the popup.
            container.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) mainHandler.post { clampToScreen() }
            }
            // Gentle fade-in instead of popping into existence.
            container.alpha = 0f
            container.animate().alpha(1f).setDuration(120).start()
        }

        // Drag-to-move via the top bar only (the sentence row now holds tappable
        // words + scrolls, so it can't double as a drag handle). Once dragged,
        // userMovedPopup is set — clampToScreen still keeps the popup on-screen
        // when it later grows.
        var dragInitialX = 0
        var dragInitialY = 0
        var dragStartRawX = 0f
        var dragStartRawY = 0f
        val dragListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialX = params.x
                    dragInitialY = params.y
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Clamp within the screen so the popup can't be dragged (and
                    // lost) off an edge.
                    val h = if (container.height > 0) container.height else measuredHeight()
                    params.x = (dragInitialX + (event.rawX - dragStartRawX).toInt())
                        .coerceIn(0, (screenW - popupW).coerceAtLeast(0))
                    params.y = (dragInitialY + (event.rawY - dragStartRawY).toInt())
                        .coerceIn(0, (screenH - h).coerceAtLeast(0))
                    userMovedPopup = true
                    runCatching { windowManager.updateViewLayout(container, params) }
                    true
                }
                else -> false
            }
        }
        topBar.setOnTouchListener(dragListener)

        return PopupUi(
            container = container,
            titleView = titleView,
            wordDetailPanel = wordDetailPanel,
            readingHeader = readingHeader,
            readingBody = readingBody,
            romajiHeader = romajiHeader,
            romajiBody = romajiBody,
            transHeader = transHeader,
            transBody = transBody,
            textMaxW = textMaxW,
            show = { show() },
        )
    }

    /**
     * The analysis popup (both modes: sentence-box tap and crop). [anchor] is the
     * screen rect the popup positions itself around (the tapped sentence box, or
     * the crop selection).
     *
     * The whole-sentence lookups (reading/translation) run BEFORE the popup is
     * built and shown — there is no loading placeholder, so the popup appears
     * exactly once, fully populated, at its final size and position (no
     * flicker/jump). Per-word dictionary lookups happen lazily on tap. A newer tap
     * or a dismissed overlay supersedes an in-flight lookup via [popupRequestSeq].
     */
    fun showDictAnalysisPopup(sentence: String, anchor: Rect) {
        val prefs = context.getSharedPreferences(OverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        val showReading = prefs.getBoolean(OverlayService.PREF_SHOW_READING, true)
        val showRomaji = prefs.getBoolean(OverlayService.PREF_SHOW_ROMAJI, true)
        val showTranslation = prefs.getBoolean(OverlayService.PREF_SHOW_TRANSLATION, true)

        val requestId = ++popupRequestSeq
        Thread {
            // No-op if the service already warmed it; guards against a tap that
            // races the warm-up.
            JmDict.warmUp(context)
            val dictAvailable = JmDict.isAvailable()

            // The interactive-sentence words: one tappable span per morpheme, in the
            // order they appear. Only the range + lookup form/reading/surface are
            // computed here (no dictionary hit — the gloss + full detail load lazily
            // when a word is tapped). The *surface* is what's shown in the sentence;
            // the lookup form keys the dictionary.
            val words = if (dictAvailable) {
                runCatching { JapaneseTokenizer.extract(sentence) }.getOrDefault(emptyList())
                    .map { m ->
                        val surface = m.surface
                        // An all-kana surface means the writer chose kana on purpose,
                        // so look the word up by its kana form — that lets JMdict's
                        // kana_pref ranking surface the kana-native entry instead of a
                        // kanji homograph. Words with kanji look up by dictionary form.
                        val lookupKey = if (!JapaneseTokenizer.containsKanji(surface)) {
                            when {
                                m.base.isNotEmpty() && !JapaneseTokenizer.containsKanji(m.base) -> m.base
                                m.reading.isNotEmpty() -> JapaneseTokenizer.katakanaToHiragana(m.reading)
                                else -> surface
                            }
                        } else m.base
                        // Furigana in the header row: only when the surface carries kanji.
                        val reading = if (JapaneseTokenizer.containsKanji(surface) && m.reading.isNotEmpty())
                            JapaneseTokenizer.katakanaToHiragana(m.reading) else ""
                        SentenceWord(m.start, m.end, surface, lookupKey, reading)
                    }
            } else emptyList()

            // Full kana reading of the whole sentence (re-tokenized so particles /
            // auxiliaries get readings too).
            val reading = if (showReading)
                runCatching { JapaneseTokenizer.fullReadingHiragana(sentence) }.getOrDefault("")
            else ""

            // Hepburn romaji of the whole sentence (from token pronunciations).
            val romaji = if (showRomaji)
                runCatching { JapaneseTokenizer.fullRomaji(sentence) }.getOrDefault("")
            else ""

            val translation = if (showTranslation)
                runCatching { Translator.translateJaToEn(sentence) }.getOrDefault("")
            else ""

            mainHandler.post {
                // A newer tap (or a cleared/dismissed overlay) superseded this
                // lookup while it was running — drop it silently.
                if (requestId != popupRequestSeq) return@post

                val ui = buildAnalysisPopup(anchor, sentence)
                if (words.isNotEmpty()) {
                    setupInteractiveSentence(ui, sentence, words, translation)
                }
                if (reading.isNotBlank() && reading != sentence) {
                    ui.readingBody.text = reading
                    ui.readingHeader.visibility = View.VISIBLE
                    ui.readingBody.visibility = View.VISIBLE
                }
                if (romaji.isNotBlank()) {
                    ui.romajiBody.text = romaji
                    ui.romajiHeader.visibility = View.VISIBLE
                    ui.romajiBody.visibility = View.VISIBLE
                }
                if (showTranslation) {
                    // The toggle is on, so surface a failure instead of silently
                    // hiding the section.
                    ui.transBody.text = when {
                        translation.isNotBlank() -> translation
                        Translator.isAvailable() -> "(no translation)"
                        else -> "Translation model not built."
                    }
                    ui.transHeader.visibility = View.VISIBLE
                    ui.transBody.visibility = View.VISIBLE
                }
                ui.show()
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
