# FN-76 actual Android diagnosis, 2026-10-16

[Run 38837762864](https://github.com/csic21/FrameNest/actions/runs/38837762864) used the exact retained app/test pair from build 38770936718, source 54849318ccaaf8788f6cd3cd25d683407f3fec60. Runtime 1.13.8 source profile, existing SenseVoice and ML Kit; no Hy.

The real diagnosis collected all 4 reference-to-MT, 4 subtitle-span ASR-to-MT and 16 production 3-second rows. Collection passed; semantic acceptance has not passed. Phone/tablet UI suites each had two failures in the same new switch visibility test; investigation identified merged-semantics test-tag ownership. The original ACL was restored exactly. Full raw evidence stays in the original Actions artifact and local retained analysis, not in this commit.

## Findings

- ML Kit mistranslates the complete corrected reference too: the interpreter/listener roles become confused, and Japanese language is rendered as a Japanese person. Recognition/windowing cannot explain all inaccuracies.
- Independent 3-second decodes duplicate a character at three adjacent boundaries (12, 15 and 21 seconds). Token-selection replay matched the actual published source; this is not an invented second recognition.
- Breaking a conditional instruction into short independently translated pieces loses the relationship between condition, negation and requested action.
- Published subtitle timecodes are not exact acoustic sentence boundaries. In particular, speech begins before the third reference unit's timestamp. Do not count every subtitle-span ASR difference as a model error or report authoritative CER.
- The next bounded comparison is fixed 3 versus 6 seconds on this held-out clip and the previously used complete Emoji video. No default behavior changes based on this diagnosis alone; no character deletion or post-hoc correction.

## Source and limits

[Public MCDC Japanese video](https://commons.wikimedia.org/wiki/File:MCDC_Onboarding_%22Ask_me_Anything%22_session_-_Japanese_language.webm), TCappelletto (WMF), CC BY-SA 4.0. The 47.720-second excerpt and human-corrected published subtitle revision are pinned in the parent fixture manifest/provenance. Reference text has not been independently listened to as verbatim gold.

This is API35 x86_64 emulator evidence with predecoded PCM. It does not measure OPPO, NAS transport, MediaCodec, playback rendering, or cold-storage performance. Fixed reference/raw/production execution order and shared model preparation limit timing comparisons. No accuracy or speedup claim follows from successful collection.
