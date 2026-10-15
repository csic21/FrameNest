#!/usr/bin/env python3
"""Audit generated commands, not CMake intent. No Android/device execution.

Usage: check_build_flags.py path/to/compile_commands.json [--optimization=-O0]
       [--debug] [--require-flag=-fsanitize=address]
"""
import argparse
import collections
import hashlib
import json
import re
import shlex
from pathlib import Path

if not __debug__:
    raise RuntimeError("Build flag auditing requires assertions; do not run Python with -O")

TARGETS = {"ggml-base", "ggml-cpu", "ggml", "llama", "framenest_hymt"}
OPTIMIZATION = re.compile(r"-O(?:[0-3sgz]|fast)?$")


def audit(commands, optimization="-O3", debug=False, require_flags=()):
    counts = collections.Counter()
    for entry in commands:
        args = entry.get("arguments") or shlex.split(entry["command"])
        match = re.search(r"CMakeFiles/([^/]+)\.dir/", " ".join(args))
        if not match or match[1] not in TARGETS:
            continue
        target = match[1]
        context = f"{target}: {Path(entry['file']).name}"
        flags = [arg for arg in args if OPTIMIZATION.fullmatch(arg)]
        effective = flags[-1] if flags else "-O0"
        assert effective == optimization, f"{context}: effective {effective}, expected {optimization}"
        # Neither the build host nor a benchmark emulator sets the shipped ISA.
        forbidden = [arg for arg in args if (
            re.match(r"-m(?:avx|amx|fma|f16c|bmi)", arg)
            or arg.startswith(("-march=", "-mcpu=", "-mtune="))
            and arg not in {"-march=x86-64", "-march=x86-64-v2", "-march=armv8-a"}
        )]
        assert not forbidden, f"{context}: non-baseline ISA flags {forbidden}"
        if debug:
            symbol_flags = [arg for arg in args if re.fullmatch(r"-g(?:[0-3]|gdb[0-3]?)?", arg)]
            assert symbol_flags and symbol_flags[-1] not in {"-g0", "-ggdb0"}, f"{context}: missing debug symbols"
            assert not any(arg == "-DNDEBUG" or arg.startswith("-DNDEBUG=") for arg in args), f"{context}: Debug assertions disabled"
        for flag in require_flags:
            assert flag in args, f"{context}: expected flag lost: {flag}"
        counts[target] += 1
    assert set(counts) == TARGETS, f"Missing inference targets: {sorted(TARGETS - set(counts))}"
    return {"effective_optimization": optimization, "debug_checks": debug,
            "required_flags": list(require_flags), "commands_per_target": dict(sorted(counts.items())),
            "portable_isa_check_passed": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("compile_commands", type=Path)
    parser.add_argument("--optimization", default="-O3", choices=["-O0", "-O1", "-O2", "-O3", "-Os", "-Oz"])
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--require-flag", action="append", default=[])
    options = parser.parse_args()
    result = audit(json.loads(options.compile_commands.read_text()), options.optimization,
                   options.debug, options.require_flag)
    result["compile_commands_sha256"] = hashlib.sha256(options.compile_commands.read_bytes()).hexdigest()
    print(json.dumps(result, sort_keys=True))
