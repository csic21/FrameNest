#!/usr/bin/env python3
"""Bounded CPU-only, local Hy-MT2 benchmark. No online inference or prompt edits."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import resource
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent
MODEL = ROOT / 'models/Hy-MT2-1.8B-Q4_K_M.gguf'
FIXTURE = ROOT.parent / 'japanese-android-context-2026-10-14/japanese-fixture-review.json'
EXPECTED_SHA = 'dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699'
PROMPT = '将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n{source}'

def sha256(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''):
            h.update(b)
    return h.hexdigest()

def write_json(p, obj):
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n')

def proc_state(pid):
    try:
        lines = Path(f'/proc/{pid}/status').read_text().splitlines()
        vals = dict(line.split(':', 1) for line in lines if ':' in line)
        return {k: vals[k].strip() for k in ('VmRSS','VmHWM','Threads','Cpus_allowed_list') if k in vals}
    except FileNotFoundError:
        return {}

