# Direct Japanese MT diagnostic evidence

See REPORT.md for findings and limits. These are public fixture text and authored
linguistic controls, never user NAS content. Video attribution/license and the
unmodified input diagnostic JSON are in ../japanese-android-context-2026-10-14/.

The six complete controls improve from 2/6 to 6/6 on this targeted semantic check.
At least three of four truncated ASR contexts still fail. This is not release
acceptance, a general translation accuracy estimate, or phone performance.

Reproduce on Linux x86_64: `bash prepare.sh`, then
`python3 benchmark_hy_cli.py --threads 2`. This explicitly downloads a verified
1,133,080,448-byte model and official pinned runtime; no model ships in this repo.
The retained reproduction path is local CLI only, with no listener.

Results retain public input/output and metrics. Environment-specific absolute
paths were replaced with `$BENCHMARK_ROOT`; evidence-sha256.json describes the
original cloud artifacts before this path-only archival normalization. Omitted
process sampling/log files are not required to replay the translation cases.
Weights, native binaries and source videos are deliberately excluded.
