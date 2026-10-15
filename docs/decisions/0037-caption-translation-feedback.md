# 0037 — Honest feedback for default bilingual captions

Status: implemented locally; Android build and phone/tablet execution pending.

The default fixed-window path publishes source text before translation with revision
1. Revision 3 means provisional source in other paths; it is not a general signal
that target text is absent. The presentation must keep these facts separate.

A nonblank source with blank target and differing cue languages is now presented
as “译文尚未就绪” in translated display modes. This means only that target text is
unavailable. It does not claim an active MT operation, predict completion, certify
source accuracy, or replace a reported failure. Source-only and same-language
modes do not show this hint. No stored cue or coverage state changes.

Bilingual mode displays source immediately. Its muted, non-animated hint appears
only after 600 ms for the same media identity, languages, cue bounds/source, display
mode and player reset key. A cue change, target arrival or explicit reset cancels
the old delay. Target-only mode displays the small placeholder immediately rather
than leaving an unexplained blank. Existing provisional-source feedback remains
separate. Expanded captions retain the existing frozen-snapshot behavior and use
“打开时译文尚未就绪”, including when opened before the delayed live hint appears.

Installation progress, compact operational status, missing-target state and errors
move immediately below the enable switch in the existing scrollable panel. Detailed
cache counters and model identifiers remain below the controls. “处理中” replaces
“识别中” because a running job can be waiting for translation. The no-recognized-speech
message no longer incorrectly names Vosk when SenseVoice is selected.

This is a presentation change only. It does not alter 3-second windows, ASR/MT,
model downloads, Hy-MT availability, cache completion, playback, legacy inclusive
cue-end selection, or seek-generation cancellation. It supplies no confidence
percentage and implies no measured accuracy or speed improvement.

Verification is split: pure Kotlin/JUnit logic and syntax/resource checks are run
locally; Gradle Android compilation, lint and actual Compose tests require the
Android CI/device environment. The parameterized tests request 360dp/840dp widths;
they must be executed on phone and tablet hosts because host constraints still
apply. See the FN-75 handoff for results and remaining acceptance.
