#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# CC BY-SA 4.0 source; see manifest.json attribution and reference.json limitations.
# Exact original file SHA-256:
# fd5f4acad07dce6c67a0e200e79b535300452b3fdb5c1f6929ddd2d3d29ee90a
# Original public URL:
# https://upload.wikimedia.org/wikipedia/commons/0/0c/MCDC_Onboarding_%22Ask_me_Anything%22_session_-_Japanese_language.webm
ffmpeg -hide_banner -v error -ss 113.460 -i mcdc-ja-original.webm -t 47.720 -map 0:v:0 -map 0:a:0 -vf 'scale=640:-2' -c:v libx264 -preset fast -crf 26 -c:a aac -b:a 96k -movflags +faststart -y mcdc-ja-113460-161180.mp4
ffmpeg -hide_banner -v error -ss 113.460 -i mcdc-ja-original.webm -t 47.720 -vn -ac 1 -ar 16000 -c:a pcm_s16le -y mcdc-ja-113460-161180.wav
ffmpeg -hide_banner -v error -ss 113.460 -i mcdc-ja-original.webm -t 47.720 -vn -ac 1 -ar 16000 -c:a pcm_s16le -f s16le -y mcdc-ja-113460-161180.pcm
ffmpeg -hide_banner -v error -i mcdc-ja-113460-161180.mp4 -f null -
