# FN-77 bounded same-origin LocalAgreement evidence

Experimental, offline-only Kotlin. These files are outside the Android source sets
and are not called by production code, Gradle, CI, a model, MT, a cache, or UI.
There is no performance or accuracy claim and no production integration approval.

## Contract

- Start an episode with `reset(generation, originSample, 16000)`. A later reset
  requires a strictly larger nonnegative generation. Origins are nonnegative
  absolute sample offsets. Only 16 kHz is supported here.
- Feed a **new independent complete ASR decode** of the whole growing same-origin
  PCM prefix to `observe(generation, originSample, endSample, sampleCount, text)`.
  `sampleCount = endSample - originSample`. Both end and count strictly increase.
  No rolling origin, concatenated snippets, token clipping, or overlap stitching.
  The component checks the numeric contract; the caller must prove PCM provenance
  and independent inference, and generation-gate asynchronous results itself.
- The first complete hypothesis is entirely tentative. Consecutive hypotheses
  confirm only their exact longest Unicode-code-point prefix. Whitespace,
  punctuation and Unicode representations are not normalized. Surrogate pairs
  are never split; malformed UTF-16 is an atomic rejection.
- Confirmation can stop in the middle of a Japanese word, grapheme cluster, or
  sentence. `STABLE_TEXT_NOT_FINAL` is a text-agreement label only. Even complete
  agreement **never authorizes final MT, subtitle publication, or coverage**.
- If a new hypothesis does not start with the prior confirmed prefix, status is
  `CONFLICT` and `conflictObserved` becomes sticky. The confirmed prefix freezes
  for the rest of the episode, while valid observations continue updating the full
  latest text. If latest does not start with confirmed, `unconfirmedTail` is null,
  and both incompatible strings remain separately available. If a later hypothesis
  again starts with confirmed, its tail is representable, but conflict stays true
  and confirmation does not resume.
- An accepted fourth hypothesis and/or exactly 192000 samples (12 seconds) ends
  the episode with explicit budget reasons. A source overshoot is rejected
  atomically, never clipped. A fifth observation is rejected as already terminal.
  Rejected inputs do not count toward the four accepted hypotheses; the harness
  is responsible for bounding attempts and preserving rejected raw inputs.
- `finish(generation)` marks EOF without promoting or deleting any tail. EOF can
  follow a cap and adds its reason while preserving text and cap reasons. Repeated
  EOF is idempotent. EOF with zero hypotheses has its own explicit reason.
- Each accepted text is at most 4096 Unicode code points. Overlong input is
  rejected atomically, retaining the last accepted state, not a truncated attempt.
  The object retains only latest and confirmed strings, at most 8192 code points /
  16384 UTF-16 code units combined, plus bounded scalar/list metadata. No accepted
  history or rejected input is retained. Snapshots include a derived tail and are
  not retained by the object; caller-retained inputs/results are outside this bound.
- Reset discards prior transcript state and returns only counts/flags in a reset
  audit. It performs no publication. The class is single-owner, not thread-safe.

## Offline checks

The runners compile the actual component using the already-pinned repository
test-tool bundle (`/tmp/fn70-kotlin`, Kotlin 2.1.20, JUnit 4.13.2 by default). They
never download tools. Override `KOTLIN_TEST_LIB_DIR` and `KOTLIN_TEST_VERSION` for
a previously provisioned compatible bundle. Each run uses a unique temporary
directory and removes its build output on exit, avoiding shared cached-jar races.

```sh
scripts/experimental/local-agreement/run-tests.sh
python3 scripts/experimental/local-agreement/test_cli.py
```

This is host JVM evidence, not Android device, ASR, translation, semantic,
latency, or user acceptance. No Gradle source set was modified.

## Replay protocol

`run-cli.sh` reads UTF-8 TSV from stdin and emits one JSON object per input line:

```text
RESET<TAB>generation<TAB>originSample<TAB>16000
H<TAB>generation<TAB>originSample<TAB>endSample<TAB>sampleCount<TAB>base64(UTF-8 complete hypothesis)
EOF<TAB>generation
```

Text encoding is strict UTF-8 then standard Base64. An empty text has an empty
sixth column. Do not trim the text before encoding. Explicit `EOF` is required;
stdin closure is not implicitly treated as source EOF. TSV syntax, numeric
overflow, Base64, UTF-8, and line-size errors return `INPUT_ERROR` without calling
the component. The CLI line limit is 32768 characters, enough for every permitted
text plus fields. Component validation returns `REJECTED` with a specific error.

The JSON envelope has `line`, `status`, `error`, `evidence`, and `resetAudit`.
`evidence` has generation/source/count metadata, `confirmedPrefix`, full
`latestHypothesis`, nullable `unconfirmedTail`, `conflictObserved`, `evidenceLabel`,
`finalTranslationOrCoverageAllowed` (always false), `terminalReasons`,
`eofObserved`, and conservative retained-text counts. Before the first reset,
evidence is null. No raw rejected text is included; a research harness must retain
each raw input separately, including errors and post-terminal attempts.

No-inference smoke (`abc` then `abcd`):

```sh
printf 'RESET\t1\t0\t16000\nH\t1\t0\t48000\t48000\tYWJj\nH\t1\t0\t96000\t96000\tYWJjZA==\nEOF\t1\n' \
  | scripts/experimental/local-agreement/run-cli.sh
```

Expected: first text fully tentative; second confirms `abc` with tail `d`; EOF
preserves both strings. No word/utterance finality is asserted.

Independent fixed 12-second research episodes are diagnostic sampling only,
not an implementable continuous caption segmentation policy. Reset must never
silently imply that pending speech at an episode boundary was completed.
