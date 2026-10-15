# 0033: Fixed-grid audio, Japanese source units, and separate completion

Date: 2026-10-14. Status: opt-in implementation, real-audio semantic acceptance pending.

## Decision

Keep 15-second PCM reads on a 9-second grid. The recorded coordinator joins original
transcript ranges, then selects text boundaries independently. Neither a token PTS nor a
stitcher's `coarseLabel*` value moves future PCM reads or directly determines subtitle
rendering. This supersedes the failed owned-prefix restart strategy for this optional
path; existing production defaults are not switched by these files.

Use Kuromoji/IPADIC 0.9.0 NORMAL-mode morphology plus measured PCM quiet evidence.
ASR-inserted spaces are omitted only in an analysis view with raw UTF-16 mapping.
Predicate basic/imperative forms, optionally followed by a terminal particle, are
candidates. A following particle, auxiliary, or dependent/suffix component blocks the
candidate. Commas are retained but looked past to the actual next lexical morpheme;
they do not independently establish or veto completion. Plain predicates before a
noun/adjective phrase remain joined unless a terminal particle, sentence punctuation,
or polite auxiliary chain supports an independent clause. Noun-only fragments and
conjunctions do not become final from punctuation alone. This is a conservative, auditable heuristic, not a grammar proof.
No fixture vocabulary exceptions or expanding word-ending lists are used.

A final candidate normally needs a real quiet run of at least 160 ms near its estimated
terminal onset and the next lexical onset, with at least 750 ms of decoded right context.
A bounded 250 ms tolerance covers estimated terminal onsets slightly inside real quiet
and the maximum distance from quiet to the following estimated onset. The selected
point remains inside measured quiet; token gaps alone are never used.
A complete isolated utterance can instead use at least 750 ms of measured trailing
quiet through the decoded edge. A reliable physical EOF plus complete morphology may
finish a sentence; an incomplete EOF remains provisional. All display timestamps are
explicitly estimates. The quiet evidence is actual PCM; a recognizer token gap cannot
stand in for measured silence.

## Failure and resource contract

- `readThroughMs` and `nextWindowStartMs` advance independently of translation/cache
  completion. Coverage is emitted only when a FINAL source unit receives nonempty MT.
- Alignment uses the existing bounded overlap matcher. There is at most one 24-second
  union request for a conflict. Failed recovery retains old source as UNCONFIRMED and
  continues at the newer fixed-grid window; it does not stop every later read.
- Normal/union ASR windows remain at most 15/24 seconds and the assembled text has a
  hard 1,024 UTF-16 code-unit cap. The 24-second retained-tail time budget is applied
  after inspecting one incoming fixed-grid hypothesis and releasing credible boundaries.
  This permits bounded text lookahead across the time budget (normally at most one
  9-second grid step) without a longer PCM decode. A source unit may span that lookahead.
  If the remaining tail still exceeds the time budget, its whole bounded text is saved
  as PROVISIONAL and its exact raw ownership is consumed. The next window must recover
  the immutable source anchor instead of starting again at raw zero. Character-cap
  fallback also retains a verified new splice offset. A forced incomplete left edge
  remains provisional until a supported boundary; it never earns completed coverage.
- A provisional snapshot may be superseded. Persist `SUPERSEDED` events and source IDs;
  do not let an older provisional row mask a revised/final source.
- Translation queue defaults to four units; one explicit retry is available, bounded
  to four remembered retry tickets. Failed/empty MT never claims coverage. A caller may
  deliberately decline MT for uncertain units while preserving their source records.
- Callers must persist records and handle persistence failures before dropping the
  result. These data contracts do not implement Room transactions or durable I/O.
- Seek, track, source, language, and model changes require a new generation. Reset clears
  all working tails, queue, retry tickets and union requests; stale responses are rejected.
- All nonterminal states expose an explicit decode, union, or translation action; no
  `NONE` action invites a tight poll loop.

## Dependency and license verification

Primary project documentation:
- https://github.com/atilika/kuromoji
- https://www.atilika.com/en/kuromoji/
- https://raw.githubusercontent.com/atilika/kuromoji/master/kuromoji-ipadic/NOTICE.md

The official project documents standalone Java use and the Maven coordinate:
`implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")`.

Maven Central artifacts inspected and executed on the JVM:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| kuromoji-ipadic-0.9.0.jar | 13,343,016 | 24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6 |
| kuromoji-core-0.9.0.jar | 99,147 | 9dec317cab2879474d39bffea4f3f481288d87952c9b5e19d6897b0d5e0d3613 |

URLs are under `https://repo.maven.apache.org/maven2/com/atilika/kuromoji/`.
The JARs contain Java 7 classfiles and no native libraries. IPADIC uncompressed JAR
entries total 33,467,827 bytes; core entries total 192,399 bytes. Actual Android heap,
startup time, APK impact after packaging, and minSdk-26 device behavior still need an
Android build/device check. The artifact size is acceptable for this optional local
caption path; no claim is made that it is the smallest Japanese tokenizer.

Code is Apache-2.0. IPADIC data also carries NAIST/ICOT notices. License/notice text
extracted from the shipped IPADIC JAR (trailing whitespace normalized) is retained under
`app/src/main/assets/listen_models/kuromoji/`, even if packaging excludes META-INF
notices. Preserve these assets when packaging. No new account agreement was accepted.

## Verification scope

The pure JVM tests and CLI use the real Kuromoji dictionary. Their timing fixtures are
synthetic logic tests unless a caller supplies actual ASR token timestamps and measured
PCM quiet runs. This decision does not establish Japanese ASR accuracy, MT accuracy,
first-bilingual latency, NAS throughput, Android compatibility, or release readiness.
Independent real public recordings and manual source/translation review are the next
acceptance step; perfect universal accuracy is not the acceptance target.

## v2 correction after the full-video failure

`recorded-source-units-kuromoji-v2` invalidates cached decisions from v1. The failed
full-video run exposed premature cap rotation, strict token/quiet comparisons, a blanket
comma veto, and a false split in a plain-verb attributive phrase. The v2 tests replay
captured real ASR/PCM quiet inputs for these cases; no source was changed to a fixture
word list. The old capture lacks union hypotheses newly requested by the changed state
sequence, so its source-only regression explicitly fails those missing requests and
retains UNCONFIRMED text. Fresh union/MT execution and semantic review remain required.

An ASR comma inside a conjugation (for example the captured `でしょ、うか`) can still
change morphological analysis. No punctuation-elision parser or no-quiet stability
heuristic was introduced in this scoped correction. Exact-zero PCM silence also needs
an explicit future input contract; arbitrary blank ASR continues to mean unknown audio.

## v3 hard-character-cap ownership repair

The matched splice lies inside the overlap, not at the old hypothesis end. v3 archives
only stable text plus the old prefix through that splice, then installs the newer tail.
An earlier whole-context snapshot is superseded. Tentative intervals share one estimated
seam PTS, without certifying semantic or word alignment. The archived-prefix anchor
remains available for the next bounded union. Exact reconstruction tests cover repeated
caps, stable prefixes, supersession, EOF and later recovery. Morphology is unchanged.

## Temporary PROVISIONAL translation

For the opt-in recorded adapter, bounded whole PROVISIONAL units may receive actual MT
while remaining explicitly uncertain. UNCONFIRMED weak-alignment text remains source-only.
Only FINAL MT success completes coverage. Nonfinal targets are presentation-only in a
bounded generation-local Session memory map; Room retains source only. Reset, seek, exit,
playback expiry, supersession and accepted final spans discard temporary targets. This
improves translation availability without claiming semantic correctness or changing the
ASR, MT prompt, morphology, PCM grid, source status or EOF coverage rules.
