# CLAUDE.md

Notes for future-Claude working on this repo. Keep concise; update when something here goes stale.

## What this app is

**JP Lens** — an Android floating-overlay OCR tool for reading Japanese in other apps (games, manga readers, etc.). User grants screen-capture + draw-over-other-apps permissions, then a circular floating button captures the current screen, runs Japanese OCR, and overlays clickable boxes on top of detected text.

Two capture modes:
- **MODE_MORPHEME (0)** — boxes per Kuromoji morpheme; tap for a tooltip with dictionary form + hiragana reading + Google Translate; long-press two boxes to define a range (popup shows joined text, full furigana, Google Translate of the span).
- **MODE_SENTENCE_LLM (1)** — boxes per OCR-line-piece, grouped into sentences by Japanese terminators (`。！？!?．.`). Tap any piece to send the full sentence to AWS Bedrock for a structured tutor-style breakdown (Word-by-word / Phrases / Translation / Notes).

Package: `com.example.jp_lens_android`. App label is `@string/app_name`.

## Build / run

- Gradle Kotlin DSL. AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2026.02.01, `compileSdk = 36 (minorApiLevel 1)`, `minSdk = 24`, JDK target 11.
- Standard build: `./gradlew :app:assembleDebug` (use `gradlew.bat` on Windows host; in WSL the WSL `./gradlew` works).
- Install + run requires a real device or emulator with **screen-capture permission** — the projection prompt is system UI; can't be fully exercised by unit tests.
- `local.properties` holds the SDK path; not committed.

## Key files (all under `app/src/main/java/com/example/jp_lens_android/`)

| File | Role |
|---|---|
| `MainActivity.kt` | Compose UI: text field for Bedrock bearer token, three permission-flow buttons (grant overlay, start morpheme, start LLM), stop button. Launches `OverlayService` as a foreground service after the `MediaProjectionManager` consent dialog. |
| `OverlayService.kt` | The whole runtime. Foreground service, owns `MediaProjection` + `VirtualDisplay` + `ImageReader`, runs ML Kit Japanese text recognition, computes morpheme/sentence boxes, draws overlay views via `WindowManager`, manages popups. **Large file (~1500 lines)** — most logic lives here. |
| `JapaneseTokenizer.kt` | Wrapper around Kuromoji IPADIC: `extract` returns filtered morphemes with char offsets; `fullReadingHiragana` produces furigana for the range popup; `katakanaToHiragana`/`containsKanji` helpers. `warmUp()` is called once on the capture thread to preload the ~7MB dictionary. |
| `BedrockClient.kt` | LLM client. Talks to AWS Bedrock Converse API at `bedrock-runtime.us-east-1.amazonaws.com`, authenticated by a long-lived `AWS_BEARER_TOKEN_BEDROCK` (no SigV4). **Note:** model id is `openai.gpt-oss-120b-1:0` despite the MainActivity button labelled "DeepSeek" — UI text is stale, the actual model is gpt-oss-120b. `reasoning_effort=low` and `temperature=1.0` are intentional (gpt-oss is trained for temp 1.0; low reasoning keeps responses fast). 20-entry LRU cache on `sentence` keys. |
| `Translator.kt` | Unauthenticated Google Translate `translate_a/single` endpoint. Used only in morpheme mode for tap/range translations. Can rate-limit if hammered. |
| `ui/theme/*` | Default Compose theme scaffolding from the AS template; not interesting. |

## Architecture details worth knowing

- **Single VirtualDisplay rule** (Android 14+): can only call `createVirtualDisplay()` once per `MediaProjection`. On rotation we `resize()` + swap surface, not recreate. See `setupVirtualDisplay()`.
- **Image -> Bitmap** has row-padding; we crop to `image.width` after `copyPixelsFromBuffer`. Don't reintroduce naive ARGB conversion.
- **Reading order**: vertical Japanese (tategaki) blocks have lines sorted right-to-left by centerX; vertical lines have elements sorted top-to-bottom by `box.top`. `isLineVertical` uses element centroid drift first, falls back to aspect ratio.
- **Morpheme positioning**: line text is rebuilt by concatenating `element.text` (NOT `line.text`, which can contain hidden separators that break the char→pixel mapping). Each morpheme's box is interpolated inside the tightest overlapping element rect, with a line-box fallback only when no element overlaps.
- **Sentence grouping** (`computeSentenceBoxes`): each terminator flushes a sentence. Pieces of one sentence across lines share `sentenceId` + `fullText` so tapping any piece sends the same full sentence to the LLM.
- **Range selection** (morpheme mode): long-press = anchor; two anchors = range; third long-press = cancel. `buildRangeText` walks lines in reading order, slicing each line's `lineText` between the anchor `charStart`/`charEnd`.
- **Popup positioning**: try above the box first, fall back below if clipped, horizontal clamp to screen margins. LLM popup additionally caps height at ~65% of screen and re-measures + reflows after the response arrives (see `reposition()` closure). The LLM popup's header row is a drag handle — once the user has dragged it, `userMovedPopup` flips and auto-reposition is suppressed so the popup doesn't snap back when the response inflates it.
- **Threading**: capture/tokenize/OCR/HTTP all run off the main thread. UI updates (`renderXxxBoxes`, popup `text =` writes) post back to `mainHandler`. The `isProcessing` flag debounces taps on the floating button.
- **Cleanup**: `onDestroy` and `clearMorphemeBoxes`/`clearSentenceBoxes` always `runCatching { windowManager.removeView(...) }` because Android can race a view out from under us during teardown.

## Conventions

- Logs all use `Log.i/e(TAG, ...)` with `TAG = "JpLens"` (or `"JpLens.<sub>"` for sub-modules). Grep by `JpLens` in `adb logcat`.
- Bedrock token is persisted in `SharedPreferences("jp_lens")` under key `aws_bearer_token_bedrock`. The user pastes it into the text field on `MainActivity`.
- Packaging excludes `META-INF/{CONTRIBUTORS,LICENSE,NOTICE}.md` because Kuromoji ipadic + core JARs collide on those.

## Things that look weird but are intentional

- `BedrockClient.MODEL_ID` is `openai.gpt-oss-120b-1:0` (switched from DeepSeek in commit `26f24dc`). The MainActivity button is now labelled "LLM+full sentence block" — keep that generic phrasing if the model changes again.
- `temperature = 1.0` and `reasoning_effort = "low"` in the Bedrock body: deliberate for gpt-oss.
- `JapaneseTokenizer` drops single ASCII tokens and anything with no Japanese chars — keeps morpheme boxes from cluttering with stray Latin/digits. POS blacklist is intentionally minimal (`その他` only); the original "grammatical glue" filter mentioned in the file's kdoc was removed (commit `de4ebce no tokenizer filter`) — kdoc is slightly outdated.
- `BedrockClient.parseSections` falls back to `raw` for the word-by-word section if header parsing finds nothing — keeps the popup useful when the LLM ignores the section format.

## Testing

- `app/src/test/java/.../ExampleUnitTest.kt` and `app/src/androidTest/.../ExampleInstrumentedTest.kt` are the AS template defaults — no real tests yet. Anything OCR/projection-related is fundamentally device-only.

## Common tasks

- **Add a new LLM model**: change `BedrockClient.MODEL_ID`; check whether `additionalModelRequestFields` keys still apply.
- **Change overlay box color/style**: `makeBoxDrawable` (morpheme), `makeSentenceBoxDrawable` (sentence), `updateOcrButtonAppearance` (floating button).
- **Change OCR engine**: swap `JapaneseTextRecognizerOptions` in `recognizer` lazy. Box layout assumes ML Kit's `TextBlock`/`Line`/`Element` hierarchy — switching to a different OCR shape means rewriting `morphemeBoxesForLine` and `computeSentenceBoxes`.
- **Adjust sentence terminators**: edit the `terminators` set in `computeSentenceBoxes`.
