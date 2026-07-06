# Privacy Policy — JP Lens

**Last updated: 7 July 2026**

JP Lens ("the app") is a free, open-source Android tool that recognises Japanese
text on your screen and shows dictionary entries, readings, and translations on
top of it. This policy explains what the app does and does not do with your data.

**Short version:** JP Lens does not collect, store, or share any personal data. It
has no accounts, no ads, and no analytics. Almost everything runs on your device.
The only time any of your content leaves your device is if **you** turn on the
optional LLM mode (described below).

---

## Information processed on your device

- **Screen capture.** When you tap the floating button, the app captures the
  current screen image **only** to recognise the text on it. The capture is held
  in memory, processed immediately, and then discarded. It is **never saved to
  storage and never uploaded** anywhere.
- **Text recognition and dictionary lookups.** Optical character recognition
  (Google ML Kit), Japanese word segmentation, and the bundled offline dictionary
  (JMdict / KANJIDIC2) all run entirely on your device.
- **Offline translation.** Japanese→English translation uses the FuguMT model,
  which is bundled inside the app. It runs entirely on your device and **never
  downloads anything or contacts a server**.
- **Your settings.** Your preferences — including any Anthropic API key and your
  AnkiDroid deck name — are stored only in the app's private local storage on your
  device. They are not transmitted to the developer.

## Information that may leave your device (optional features only)

- **LLM mode (off by default).** This mode only works if you choose to enter your
  own Anthropic API key and select LLM mode. When you do, the selected sentence of
  recognised text is sent to **Anthropic's API** to produce a grammar/vocabulary
  breakdown, authenticated with your key. That request is subject to
  [Anthropic's Privacy Policy](https://www.anthropic.com/legal/privacy). If you
  never enter a key and never use LLM mode, **no recognised text ever leaves your
  device.**
- **AnkiDroid (optional).** If you tap the "+" button on a word, that word/card is
  written to the AnkiDroid app already installed on your device, using AnkiDroid's
  local on-device API. JP Lens does not send this data to any server.

## Permissions and why they are used

- **Display over other apps** — to draw the floating button and the overlay boxes
  and pop-ups on top of other apps.
- **Screen capture** (requested each session via the system dialog) — to read the
  text currently on screen.
- **Notifications** — to show the required "screen capture active" notification
  while the overlay service is running.
- **Internet** — only for the optional LLM mode (sending the selected sentence to
  Anthropic). Every other feature, including translation, works fully offline.
- **AnkiDroid database access** (optional) — only if you use the "add to Anki"
  feature.

## Data sharing and selling

JP Lens does **not** sell your data, share it with advertisers, or use it for any
analytics or tracking.

## Children's privacy

JP Lens is not directed at children and does not knowingly collect any personal
information from anyone, including children.

## Open-source and attribution

JP Lens is open source. Dictionary data comes from JMdict / KANJIDIC2 (© the
Electronic Dictionary Research and Development Group, licensed CC BY-SA 4.0) and
JLPT vocabulary lists by Jonathan Waller (CC BY 4.0); offline translation uses the
FuguMT model by Satoshi Takahashi (CC BY-SA 4.0, derived from Marian / OPUS-MT).
Open-source library licenses are listed in the app under
**About & privacy → Open-source licenses**.

## Changes to this policy

If this policy changes, the "Last updated" date above will be revised and the new
version will be published at this URL.

## Contact

Questions, bug reports, or requests? Email **n.nayeem.rm@gmail.com**.
