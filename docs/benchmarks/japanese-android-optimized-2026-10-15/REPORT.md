# Optimized Android runtime: functional pass, semantic limitations remain

Exact source: `da49192aafdae6b53d1006abe6b7b36a08e3d831`.
[CI111](https://github.com/csic21/FrameNest/actions/runs/38687396615) passed;
[runtime24, attempt1](https://github.com/csic21/FrameNest/actions/runs/38687396735)
passed all functional tests. The explicitly approved current-UID KVM ACL was
restored. Artifact11951240284 ZIP SHA256:
`ce3de4198daf7c6a4530625fd6206c25456cf2278160c0141bf11c482eef3a89`.

## Measured result

API35 x86_64 emulator, two CPUs, actual inference-target compile commands audited
as O3 for both shipped ABIs. Java/Kotlin remains debuggable. This is a cloud
emulator result, not OPPO, SMB/NAS, thermals or physical overlay latency.

- Hy model download/verified installation90.148s; fresh native load421ms.
- Six complete authored controls:3.860–9.666s each. All six preserve the specified
  main meaning, entities, scope and negation in a textual review.
- Actual native cancellation47ms; close during generation82ms. No late result,
  one model open/close, same seeded output after cancellation and reuse.
- Recorded public fixture, model already downloaded: first finalized bilingual
  callback13.347s from test entry,7.280s from prepared producer. The failed
  unoptimized run21 measured279.513s/271.391s respectively. These are different
  ephemeral runners, not a controlled phone speedup. Same-host O0/O3 evidence is
  recorded separately in the native-build report.
- First15s PCM513ms and ASR1.083s. Four MT calls5.283,6.696,16.416,12.087s.
- Producer read through51s in48.908s and finalized four cues through42.220s;
  the remaining tail was retained, not falsely marked final. This limited test
  is not complete-video coverage or sustained real-time-playback certification.
- Both-model total PSS snapshot1,706,603KiB; after close339,873KiB. Not peak RSS
  or a certified phone memory budget.
- Existing ML Kit path: first-install51.055s, downloaded-model fresh session3.796s.
- Startup and all ten overlay tests at360/840dp passed, including full-text
  expansion and reset/exit/lock behavior.

## All four recorded translations, not selected successes

The first two question translations are faithful and readable. The two longer
units retain known meaning errors:

1. The language guesses and following emoji explanation are combined. The target
   says English/Spanish/Chinese are not business-document vocabulary, attaching
   the negation to the wrong referent. A complete-word source does not guarantee
   correct sentence relationships.
2. The Hollywood sentence becomes a temporal/causal claim that making the movie
   led to emoji becoming a new expression style. The original describes its
   popularity and Japanese origin; the added relationship is not established.

Thus functional pass does not mean every recorded translation passed semantics.
The raw reports correctly leave semantic acceptance unset. No perfect-accuracy,
fastest-engine, zero-delay or full Japanese-video acceptance claim is supported.
This evidence does not approve exposing the new mode as a semantically validated
feature. A separate release-scope decision is required; the original fast Japanese
bilingual-caption goal remains open.

## Provenance

The accompanying fixture manifest gives the fixed public Simpleshow Japan Emoji
video hash and CC-BY-SA4.0 attribution. Machine-derived source/target excerpts
remain under that license and may contain errors. No user NAS media, subtitle
content, credentials or private databases were collected.
