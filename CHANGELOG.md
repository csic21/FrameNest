# Changelog

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
