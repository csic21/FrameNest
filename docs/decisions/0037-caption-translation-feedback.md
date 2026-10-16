# 0037 — Honest feedback for default bilingual captions

Status: implemented; first phone/tablet run completed, with a targeted test-selector
correction awaiting Android rebuild and rerun.

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

## API 35 feedback visibility regression

Runtime run `38837762864` exercised both phone and tablet hosts. Each host reported
33 passes, 4 Hy-native skips and 2 failures: the 360dp/840dp instances of the
feedback visibility test failed at the first enable-switch visibility assertion.
All 20 overlay instances passed per host. Preparation screenshots are from another
test state and are not evidence of the failing installation state.

The enable Row owns `toggleable` semantics and merges descendants. Its decorative
`Switch(onCheckedChange = null)` previously owned the test tag, which Compose does
not propagate to the merged parent. `assertIsDisplayed` also returns false when
the selector finds no node, so this failure alone does not establish clipping or
automatic scrolling. This follows the AndroidX definitions of
[`TestTag`](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/semantics/SemanticsProperties.kt)
and [`isDisplayed`](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-test/src/commonMain/kotlin/androidx/compose/ui/test/Assertions.kt).

The stable enable tag now belongs to the semantic Row. A separate indicator tag
allows an unmerged-tree assertion that the actual switch glyph remains visible.
The regression retains the small viewport and feedback visibility checks, checks
disabled/on and enabled/off states on the real control, and additionally verifies
that the panel remains at scroll position zero after every state transition.
There is no layout, scrolling, callback or caption behavior change. Syntax checks
pass locally; the corrected selector and stricter assertions require a fresh
Android phone/tablet execution before acceptance.

## Corrected runtime acceptance, 2026-10-16

Run 38841597151 on the retained source 0a15874d APK passed all 35 applicable tests on each API35 phone/tablet-sized viewport, with four explicit Hy-only skips per viewport. Both formerly failing feedback cases passed with the stricter assertions. The exact original KVM ACL was restored. This accepts the UI feedback change, not caption accuracy or real-phone/NAS performance.
