# CLAUDE.md

Notes for future-Claude working on this repo. Keep concise; update when something here goes stale.

## What this app is

**JP Lens** — an Android floating-overlay OCR tool for reading Japanese in other apps (games, manga readers, etc.). User grants screen-capture + draw-over-other-apps permissions, then a circular floating button captures the current screen, runs Japanese OCR, and overlays clickable boxes on top of detected text.

Three capture modes:
- **MODE_MORPHEME (0)** — boxes per Kuromoji morpheme; tap for a tooltip with dictionary form + hiragana reading + Google Translate; long-press two boxes to define a range (popup shows joined text, full furigana, Google Translate of the span).
- **MODE_SENTENCE_LLM (1)** — boxes per OCR-line-piece, grouped into sentences by Japanese terminators (`。！？!?．.`). Tap any piece to send the full sentence to AWS Bedrock for a structured tutor-style breakdown (Word-by-word / Kanji / Translation / Notes). Each word row has a "+" button that adds it to AnkiDroid as a flashcard.
- **MODE_SENTENCE_DICT (2)** — same sentence boxes/popup as LLM mode but **fully offline, no LLM**. Word-by-word = Kuromoji morphemes glossed against the bundled JMdict (`JmDict.lookupWord`); Kanji section = per-kanji meaning + JLPT from KANJIDIC2 (`JmDict.lookupKanji`); Translation = Google Translate of the full sentence (`Translator`); no Notes section. Same "+" Anki buttons. Each word row has a **▸ chevron** that lazily expands a full JMdict detail panel below it (all senses with part-of-speech, misc/field/dialect tags, notes, cross-refs, loanword origin, alternate writings/readings) via `JmDict.lookupWordDetail` + `OverlayService.renderWordDetail`; tap again to collapse (expand/collapse does **not** reposition the popup — it stays anchored top-left and grows). The "+" Anki card here uses **front = the word (kanji form, no kana)** and **back = the full JMdict detail rendered as HTML** (`buildAnkiBackHtml`), not the summary. Requires `assets/jmdict.db` to have been built first (see Build / run).

Package: `com.example.jp_lens_android`. App label is `@string/app_name`.

## Build / run

- Gradle Kotlin DSL. AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2026.02.01, `compileSdk = 36 (minorApiLevel 1)`, `minSdk = 24`, JDK target 11.
- Standard build: `./gradlew :app:assembleDebug` (use `gradlew.bat` on Windows host; in WSL the WSL `./gradlew` works).
- Install + run requires a real device or emulator with **screen-capture permission** — the projection prompt is system UI; can't be fully exercised by unit tests.
- `local.properties` holds the SDK path; not committed.
- **Offline dictionary (dict mode)**: `MODE_SENTENCE_DICT` needs `app/src/main/assets/jmdict.db` (~95 MB), which is **not committed** (too large — full JMdict). Build it once with `python3 scripts/build_jmdict_db.py` (downloads the latest scriptin/jmdict-simplified release — full English JMdict + KANJIDIC2 — and bakes a SQLite file; stdlib-only). DB schema: `entries(key, reading, gloss, common, word_id)` (one row per headword spelling, summary gloss) → `detail(word_id, json BLOB)` (one **gzip-compressed** trimmed JSON per word, all senses/fields, for the expand panel) + `tags(code, label)` (JMdict tag-code → human label) + `kanji(literal, meanings, jlpt)` + `meta(version)`. The app copies the asset to internal storage on first dict-mode launch; **bump `JmDict.DB_ASSET_VERSION` whenever the schema changes** so devices re-copy across in-place updates (it's `2` now). Until the script is run, dict mode shows "Dictionary not built". JMdict/KANJIDIC2 are EDRDG-licensed (CC BY-SA 4.0); the derived `.db` carries the same attribution.

## Key files (all under `app/src/main/java/com/example/jp_lens_android/`)

| File | Role |
|---|---|
| `MainActivity.kt` | Compose UI: text field for Bedrock bearer token, permission-flow buttons (grant overlay, start morpheme, start LLM, start offline-JMdict), stop button. Launches `OverlayService` as a foreground service after the `MediaProjectionManager` consent dialog. Also handles the AnkiDroid runtime-permission prompt: when launched with extra `EXTRA_REQUEST_ANKI_PERMISSION`, a `LaunchedEffect` fires `RequestPermission` for `AnkiDroidHelper.PERMISSION` (the overlay Service can't request dangerous permissions itself). |
| `OverlayService.kt` | The whole runtime. Foreground service, owns `MediaProjection` + `VirtualDisplay` + `ImageReader`, runs ML Kit Japanese text recognition, computes morpheme/sentence boxes, draws overlay views via `WindowManager`, manages popups. `buildAnalysisPopup` builds the shared sentence-popup scaffold; `showLlmAnalysisPopup` (Bedrock) and `showDictAnalysisPopup` (offline JMdict) fill it. Also builds the per-word rows + "+" Anki buttons (`buildWordRow`, `handleAddToAnki`); in dict mode each row is expandable (`buildWordRow(expandable=true)` adds a chevron + lazy detail panel rendered by `renderWordDetail` with styled `SpannableStringBuilder` spans). **Large file (~1900 lines)** — most logic lives here. |
| `JapaneseTokenizer.kt` | Wrapper around Kuromoji IPADIC: `extract` returns filtered morphemes with char offsets; `fullReadingHiragana` produces furigana for the range popup; `katakanaToHiragana`/`containsKanji` helpers. `warmUp()` is called once on the capture thread to preload the ~7MB dictionary. |
| `BedrockClient.kt` | LLM client. Talks to AWS Bedrock Converse API at `bedrock-runtime.us-east-1.amazonaws.com`, authenticated by a long-lived `AWS_BEARER_TOKEN_BEDROCK` (no SigV4). Model id is `openai.gpt-oss-120b-1:0` (gpt-oss-120b). `reasoning_effort=low` and `temperature=1.0` are intentional (gpt-oss is trained for temp 1.0; low reasoning keeps responses fast). 20-entry LRU cache on `sentence` keys. Prompt asks for four sections (Word-by-word / Kanji / Translation / Notes); `Analysis` exposes parsed `words: List<WordEntry>` (word/reading/meaning/jlpt) for the Anki buttons. |
| `AnkiDroidHelper.kt` | Thin wrapper around AnkiDroid's `AddContentApi` (`com.github.ankidroid:Anki-Android`, via JitPack). Find-or-creates the "JP Lens" deck + "JP Lens Basic" 2-field (Front/Back) note type, dedupes on the front field (`findDuplicateNotes`), and adds the card. Two `addCard` overloads: the structured one (reading/meaning/JLPT/sentence/translation → `buildBack`) used by LLM mode, and a core `addCard(context, front, back)` taking an already-formatted back (used by dict mode with HTML). Returns a sealed `AddResult` (`Added`/`Duplicate`/`Failed`). `isAnkiInstalled`/`hasPermission` guards. |
| `Translator.kt` | Unauthenticated Google Translate `translate_a/single` endpoint. Used in morpheme mode for tap/range translations and in dict mode for the full-sentence Translation section. Can rate-limit if hammered. |
| `JmDict.kt` | Offline dictionary for dict mode. Copies `assets/jmdict.db` to internal storage on first use, opens read-only, loads the tag-label map, exposes `warmUp`/`isAvailable`/`lookupWord`(JMdict gloss summary)/`lookupKanji`(KANJIDIC2 meaning + JLPT)/`lookupWordDetail`(full entry — gunzips + parses the `detail` blob into `WordDetail`/`Sense`/… for the expand panel)/`tagLabel`. The `.db` is built by `scripts/build_jmdict_db.py` and not committed. |
| `ui/theme/*` | Default Compose theme scaffolding from the AS template; not interesting. |

## Architecture details worth knowing

- **Single VirtualDisplay rule** (Android 14+): can only call `createVirtualDisplay()` once per `MediaProjection`. On rotation we `resize()` + swap surface, not recreate. See `setupVirtualDisplay()`.
- **Image -> Bitmap** has row-padding; we crop to `image.width` after `copyPixelsFromBuffer`. Don't reintroduce naive ARGB conversion.
- **Reading order**: vertical Japanese (tategaki) blocks have lines sorted right-to-left by centerX; vertical lines have elements sorted top-to-bottom by `box.top`. `isLineVertical` uses element centroid drift first, falls back to aspect ratio.
- **Morpheme positioning**: line text is rebuilt by concatenating `element.text` (NOT `line.text`, which can contain hidden separators that break the char→pixel mapping). Each morpheme's box is interpolated inside the tightest overlapping element rect, with a line-box fallback only when no element overlaps.
- **Sentence grouping** (`computeSentenceBoxes`): each terminator flushes a sentence. Pieces of one sentence across lines share `sentenceId` + `fullText` so tapping any piece sends the same full sentence to the LLM.
- **AnkiDroid flow**: the "+" button sits *leading* (left of the word label) in each word row — deliberately, so it stays glued near the word even when the popup is wide (an earlier `weight=1f` on the label pushed it to the far right). Tap → if Anki not installed, toast; if permission missing, launch `MainActivity` with `EXTRA_REQUEST_ANKI_PERMISSION` to prompt; else add on a background thread and update the button (`✓` green = added, gray = duplicate) + toast. Dedup is keyed on the word (front field) only across all decks of the model.
- **Range selection** (morpheme mode): long-press = anchor; two anchors = range; third long-press = cancel. `buildRangeText` walks lines in reading order, slicing each line's `lineText` between the anchor `charStart`/`charEnd`.
- **Popup positioning**: try above the box first, fall back below if clipped, horizontal clamp to screen margins. LLM popup additionally caps height at ~65% of screen and re-measures + reflows after the response arrives (see `reposition()` closure). The LLM popup's header row is a drag handle — once the user has dragged it, `userMovedPopup` flips and auto-reposition is suppressed so the popup doesn't snap back when the response inflates it.
- **Threading**: capture/tokenize/OCR/HTTP all run off the main thread. UI updates (`renderXxxBoxes`, popup `text =` writes) post back to `mainHandler`. The `isProcessing` flag debounces taps on the floating button.
- **Cleanup**: `onDestroy` and `clearMorphemeBoxes`/`clearSentenceBoxes` always `runCatching { windowManager.removeView(...) }` because Android can race a view out from under us during teardown.

## Conventions

- Logs all use `Log.i/e(TAG, ...)` with `TAG = "JpLens"` (or `"JpLens.<sub>"` for sub-modules). Grep by `JpLens` in `adb logcat`.
- Bedrock token is persisted in `SharedPreferences("jp_lens")` under key `aws_bearer_token_bedrock`. The user pastes it into the text field on `MainActivity`.
- Packaging excludes `META-INF/{CONTRIBUTORS,LICENSE,NOTICE}.md` because Kuromoji ipadic + core JARs collide on those.
- AnkiDroid integration needs three manifest entries: the `com.ichi2.anki.permission.READ_WRITE_DATABASE` dangerous permission, a `<queries><package android:name="com.ichi2.anki" /></queries>` block (so the package is visible on Android 11+), and the JitPack repo in `settings.gradle.kts` (the only place `com.github.ankidroid:Anki-Android` is published).

## Things that look weird but are intentional

- `BedrockClient.MODEL_ID` is `openai.gpt-oss-120b-1:0` (switched from DeepSeek in commit `26f24dc`). The MainActivity button is now labelled "LLM+full sentence block" — keep that generic phrasing if the model changes again.
- `temperature = 1.0` and `reasoning_effort = "low"` in the Bedrock body: deliberate for gpt-oss.
- `JapaneseTokenizer` drops single ASCII tokens and anything with no Japanese chars — keeps morpheme boxes from cluttering with stray Latin/digits. POS blacklist is intentionally minimal (`その他` only); the original "grammatical glue" filter mentioned in the file's kdoc was removed (commit `de4ebce no tokenizer filter`) — kdoc is slightly outdated.
- `BedrockClient.parseSections` falls back to `raw` for the word-by-word section if header parsing finds nothing — keeps the popup useful when the LLM ignores the section format.
- Word-by-word lines use an em dash `—` (U+2014) separator: `word — hiragana — meaning — [JLPT]`. `parseWordEntries` just splits on `—` and trims (no regex) — deliberately replaced the old regex parser. A bare `-` in the hiragana slot means "kana-only word, no separate reading". The prompt has no "Phrases" section anymore (folded into Notes); "Kanji" replaced it.

## Testing

- `app/src/test/java/.../ExampleUnitTest.kt` and `app/src/androidTest/.../ExampleInstrumentedTest.kt` are the AS template defaults — no real tests yet. Anything OCR/projection-related is fundamentally device-only.

## Common tasks

- **Add a new LLM model**: change `BedrockClient.MODEL_ID`; check whether `additionalModelRequestFields` keys still apply.
- **Change LLM sections / word format**: edit `buildPrompt`, then keep `parseSections`/`parseWordEntries` and the `Analysis`/`WordEntry` data classes in sync, plus the section views in `OverlayService.showLlmAnalysisPopup`.
- **Change the Anki card layout**: `AnkiDroidHelper.buildBack` (back-side content), `MODEL_FIELDS`/`QFMT`/`AFMT` (note type), `DECK_NAME`/`MODEL_NAME`.
- **Change overlay box color/style**: `makeBoxDrawable` (morpheme), `makeSentenceBoxDrawable` (sentence), `updateOcrButtonAppearance` (floating button).
- **Change OCR engine**: swap `JapaneseTextRecognizerOptions` in `recognizer` lazy. Box layout assumes ML Kit's `TextBlock`/`Line`/`Element` hierarchy — switching to a different OCR shape means rewriting `morphemeBoxesForLine` and `computeSentenceBoxes`.
- **Adjust sentence terminators**: edit the `terminators` set in `computeSentenceBoxes`.
