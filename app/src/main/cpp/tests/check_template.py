#!/usr/bin/env python3
"""Host-only template parity test; needs g++ and Jinja2, never downloads a model."""
import json
from pathlib import Path
import subprocess
import tempfile
import jinja2

root = Path(__file__).resolve().parent
fixture = json.loads((root / "official-template.json").read_text())
template = jinja2.Environment(undefined=jinja2.StrictUndefined).from_string(fixture["template"])
with tempfile.TemporaryDirectory() as directory:
    binary = Path(directory) / "prompt-test"
    subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", "-Werror", str(root / "hy_mt_prompt_test.cpp"), "-o", str(binary)], check=True)
    for content in ["", "日本語", "絵文字😀", "第一行\n第二行", "  whitespace  ", "{{not_jinja}}", "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n日本語"]:
        expected = template.render(messages=[{"role": "user", "content": content}], add_generation_prompt=True)
        actual = subprocess.run([str(binary)], input=content, text=True, capture_output=True, check=True).stdout
        assert actual == expected, "Official template parity failure"
print("Official pinned Jinja template parity: 7 cases passed; changed/missing templates rejected")
