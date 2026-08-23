# Changelog

## Unreleased

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
