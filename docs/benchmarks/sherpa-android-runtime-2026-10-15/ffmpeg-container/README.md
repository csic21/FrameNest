# FN-74 WAV container portability check

On 2026-10-15, the official Ubuntu archive FFmpeg 6.1.1-3ubuntu5 binary and its libav packages decoded both pinned public videos with the unchanged preparation command: both whole-WAV hashes differed from the Debian FFmpeg 7.1.5 references, while the original sample counts, signed-PCM16 hashes, float32 hashes and all 33 window hashes matched exactly; the updated full preparation also passed (see `verification.json`).

Packages in `packages.json` and `dependencies.json` were extracted into a temporary directory and run with a process-local `LD_LIBRARY_PATH`, without system installation or Android/KVM execution; remaining libraries came from the existing Debian host, so this is a direct official-binary decode check rather than reproduction of the entire Ubuntu CI image, and measured download hashes are provenance rather than separately authenticated package signatures.

The manifest pins remain unchanged. Only structurally valid, bounded RIFF LIST/INFO metadata may differ; format, frames or sample differences still fail closed and report expected/actual hashes. Existing corrupt WAVs are neither replaced nor re-decoded. The `--report` option retains success provenance or failure diagnostics for CI artifacts.
