# Bounded software probe: boot timeout

[Run 38733262176](https://github.com/csic21/FrameNest/actions/runs/38733262176) used the retained exact source APK pair with `-accel off`, API35 x86_64, 4 GiB and 2 cores. SDK/runtime dependencies and input verification passed. The boot phase started at 12:16:34 UTC and timed out with exit124 at12:31:50 on 2026-10-15; the 900-second boot deadline was reached. Later ASR/UI phases were not attempted.

The emulator reached kernel/userspace logging but did not report `sys.boot_completed=1`. Its TCG log warns about unavailable AVX/F16C and unaccelerated x86 compatibility; those warnings alone do not prove the exact causal mechanism. Cleanup acknowledged emulator shutdown. No KVM permission was changed. This is not evidence that the app or native library failed, nor a product timing measurement. Do not repeat speculative software tuning; source runtime acceptance remains open.

The complete immutable run artifact contains the full logcat and exact input pair; this small record retains the failure summary and emulator log.
