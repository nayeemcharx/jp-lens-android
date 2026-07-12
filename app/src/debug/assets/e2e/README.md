# JP Lens E2E screenshots

Place the initial portrait smoke screenshot here as `japanese_smoke.png`.

Use large, high-contrast Japanese text. At least one OCR line must contain strictly
more than 30% Japanese characters. This directory is debug-only and is not packaged
in release builds.

The smoke test grants overlay and notification permission through the instrumentation
shell identity on every run. MediaProjection consent is still handled through UI
Automator because Android requires a fresh projection token for each session.
