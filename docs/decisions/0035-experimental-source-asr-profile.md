# 0035: isolate the reviewed source-ASR app profile

Status: experimental, 2026-10-15. Task: FN-74.

The reviewed no-TTS source AAR must be compiled into the actual app before device
acceptance can begin. Importing it as the default dependency would silently
replace the stable runtime and conflate stock 1.13.8 measurements with another
binary.

Use an explicit `-PsherpaAsrSource=true` profile on its own experiment branch.
Pin the exact reviewed AAR hash independently of Maven metadata, preserve the
stock default, and give the source variant a full-hash cache identity separate
from its upstream `VersionInfo` version. Preserve the model and MT choices.
Disallow source-profile release tasks. Retain exact debug APKs after ordinary
compile/test/lint/static-audit CI; do not run devices as part of this step.

This is an additional opt-in experiment, not a revision to accepted stable ASR or
MT decisions. Artifact expiry fails closed. Device/accuracy/performance,
reproducibility and complete licensing gates remain open.
