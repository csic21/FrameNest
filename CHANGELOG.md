# Changelog

## Unreleased

- 拖动进度条或左右滑动时，先显示目标时刻的预览图；松手后才跳转，播放中的画面不再跟着远程定位。
- 文件夹列表里的视频封面改为看得出画面的 16:9，并标出文件大小和时长。较长的片子避开片头，纯色帧会换成后面的画面。

## 0.4.2-internal

Main playback path fixes, no behavior change on the happy path:

### Fixes

- **Replay from Ended** keeps an in-Ended seek: reopen from zero, then apply
  the target on the fresh input instead of dropping it
- **Play during Preparing** latches the intent (symmetric with pause), so the
  first frame continues into Playing without a second tap
- **Native hardening**: all `MediaPlayer` getters/calls guarded — a teardown
  race can no longer throw onto the main thread; a failed native `play()`
  surfaces a retryable error instead of crashing
- **Seek preview**: `isBuffering` is owned by Buffering events again (preview
  completion no longer hides real stalls); a preview that cannot be muted is
  cancelled instead of leaking audio
- **Audio track switch**: listen-translate cache is re-keyed only after the
  native switch succeeds
- Removed the unused `ResumeSeekGate.shouldFire` natural-fire path

### Known limitations

- Same as 0.4.1-internal; real-NAS E2E still depends on user environment

## 0.4.1-internal

Decode-path performance, no behavior change:

### Performance

- **Thumbnails**: try embedded cover art first (zero video decode); on API 27+
  decode directly at 320px via `getScaledFrameAtTime` instead of full-res
  decode + `createScaledBitmap`
- **Listen-translate PCM**: cache `MediaCodecList.findDecoderForFormat` result
  per MIME (was once per 3s window); resample 44.1/48 kHz → 16 kHz with a
  single incremental accumulator instead of per-sample multiply+divide

### Known limitations

- Same as 0.4.0-internal; real-NAS E2E still depends on user environment

## 0.4.0-internal (2026-08-24)

Internal test build after listen-translate, player product controls, brand, and
performance work (FN-10 … FN-49).

### Features

- **Listen-translate (Beta)**: on-device PCM → Vosk small ASR → ML Kit MT;
  Settings download / resume / verify; adaptive prefetch while playing
- **Player controls**: audio tracks, speed, lock, orientation lock, prev/next
  and autoplay, buffering, scrub preview, swipe seek, landscape fullscreen,
  brightness / volume gestures
- **LAN discovery**: user-initiated mDNS (`_smb._tcp`); optional deep scan
  probes subnet TCP 445; fills host only (no credentials)
- **Browse layout**: list / multi-column grid toggle (phone ≈2 cols, tablet
  ≈3–4); preference persisted
- **Brand**: confirmed logo and indigo / violet theme; full-bleed adaptive
  launcher icon

### Fixes

- Release R8 keep rules so SMBJ, Vosk/JNA, and ML Kit survive minify
- First-frame hold, rotation surface, non-blocking player exit
- Browse listing hang after playback; visible-first thumbnail work
- Listen empty-result recovery and SMB audio reconnect after lock screen
- Critical interaction / accessibility polish on phone and tablet

### Performance

- SMB session reuse, Room write reduction, PCM allocation cuts, thumbnail
  disk accounting, bounded LAN scan, cache maintenance off the main thread

### Known limitations

- Listen-translate is Beta (Vosk small + ML Kit); not claimed as general
  accurate captions
- Real NAS end-to-end still depends on the user environment
- Release APK is debug-signed for overlay install on the existing test device

## 0.3.1-internal (2026-07-14)

Post-MVP batch: size, share probe, settings, debug isolation.

### Improvements

- **APK size**: ABI splits (`arm64-v8a`, `x86_64` + universal); release minify + shrinkResources
- **Share list**: probe common home-NAS share names when MS-SRVS unavailable
- **Thumbnails**: configurable concurrency 1–2 in Settings (default 1)
- **Spikes**: Player/SMB spike activities moved to **debug** source set only
- **Security**: `allowBackup=false`
- **Docs**: `docs/NAS-ACCEPTANCE-CHECKLIST.md` for real-NAS sign-off

## 0.3.0-internal (2026-07-14)

Internal test build after Waves 0–3 (FN-00 … FN-09).

### Features

- Add / edit / delete / test SMB servers; credentials encrypted (Keystore-backed)
- Browse shares and directories; video/subtitle filtering; list thumbnails (single-concurrency)
- Product player: first-frame ready before play, seek, progress history / resume
- Embedded + sidecar subtitles (SRT/ASS/SSA/VTT), delay & font size
- Adaptive phone/tablet shell; settings for cache clear, subtitle language, diagnostic export

### Known limitations

- Real NAS E2E depends on user environment (see `docs/RELEASE-ACCEPTANCE.md`)
- Share enumeration is candidate/default-share based (no full MS-SRVS)
- Thumbnail extract uses MediaMetadataRetriever (not all codecs/containers)
- Spike activities exist for debug but are not exported

### Security notes

- Passwords never stored in Room or URLs
- Diagnostic export is redacted via `CredentialRedactor`
- No real NAS host/password should appear in git or APK assets
