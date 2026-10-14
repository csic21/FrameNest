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
candidates. A following particle, auxiliary, dependent/suffix component, or list comma
blocks the candidate. Noun-only fragments and conjunctions do not become final from
punctuation alone. This is a conservative, auditable heuristic, not a grammar proof.
No fixture vocabulary exceptions or expanding word-ending lists are used.

A final candidate normally needs a real quiet run of at least 160 ms near its estimated
terminal onset and the next lexical onset, with at least 750 ms of decoded right context.
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
- The active context is bounded to 24 seconds / 1,024 UTF-16 code units. A whole-context
  PROVISIONAL snapshot permits a caller to surface an incomplete source. Rollover keeps
  the old persisted snapshot and overlaps the next fixed window. It never deletes a
  text suffix to satisfy a cap. The restarted context's left edge is provisional until
  a supported boundary; only subsequent complete units can become final.
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
