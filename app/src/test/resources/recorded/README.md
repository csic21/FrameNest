# Captured public-audio regression inputs

These TSVs retain actual SenseVoice ASR token text/estimated onsets and measured PCM quiet
from the 2026-10-14 full-video acceptance run. They are model-produced derivatives with
recognition errors, not official or golden transcripts. No audio/model bytes are included.
Tests replay these inputs without running ASR/MT or asserting semantic translation quality.

- `kishida-captured-asr.tsv`: Cabinet Public Affairs Office / Prime Minister’s Office of
  Japan, 2023-04-16. Attribute 首相官邸ホームページ. Commons declares GJSTU 2.0 / CC BY 4.0.
  https://commons.wikimedia.org/wiki/File:Fumio_Kishida%27s_press_conference_the_day_after_attacked.webm
  Original media SHA-256: f99bc9087c05ba69ad6b33ac7fbe35856a4c8ca0d261a38b7f76f5b8a1f60de9.
  Captured fixed-hypothesis JSON SHA-256: 65a32280f1f5c7cff33fcc671aa565e6c9b14edd634713fdfe03a4ee0aef7702.
- `emoji-captured-asr.tsv`: Simpleshow Japan, Emoji.webm, CC BY-SA 4.0. Transcript
  derivative retained under CC BY-SA 4.0; no endorsement implied.
  https://commons.wikimedia.org/wiki/File:Emoji.webm
  https://creativecommons.org/licenses/by-sa/4.0/
  Original media SHA-256: ad3af093836cad823ef1793ff4d505139eed92dd7e7402de4c015a2ed50d5956.
  Captured fixed-hypothesis JSON SHA-256: 486f6a74eae57d0fae9188073c28dc35eb1f9959c65f4be14bad3643d03791e3.

ASR: sherpa-onnx 1.12.32 / SenseVoice int8, Japanese hint, ITN, greedy, two threads;
model revision 2365baeacb507f821a0c8120fcee3d484dba7a07. Quiet: actual mono 16 kHz PCM,
20 ms RMS frames, -35 dBFS, at least 160 ms. H blocks use 15-second windows / 9-second
grid; X blocks preserve the two union hypotheses captured in the original Kishida run.
No additional union decode may be fabricated by a regression test.
