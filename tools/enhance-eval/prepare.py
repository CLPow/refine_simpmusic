"""Builds the "Enhance audio" evaluation set from lossless files.

Usage: python prepare.py <folder of FLAC/WAV files> <eval folder>

For each track it cuts a 60 s excerpt (from 30 s in, or from the start if the track is short),
writes it as 48 kHz 16-bit stereo, and makes Opus 128 kbps and 66 kbps encode/decode copies, the
way YouTube's Medium and Low tiers reach the player. It also writes loudness.json: each clip's
YouTube-style loudnessDb (integrated loudness relative to −14 LUFS, positive = louder), which the
render harness hands to the normalization variant.

Needs ffmpeg (with libopus) on PATH.
"""

import json
import os
import re
import subprocess
import sys

SRC, EVAL = sys.argv[1], sys.argv[2]
IN = os.path.join(EVAL, "in")
os.makedirs(IN, exist_ok=True)


def ff(*args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", *args], check=True)


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True).stdout
    return float(out.strip())


def lufs(path):
    err = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "ebur128", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    return float(re.findall(r"I:\s+(-?[\d.]+) LUFS", err)[-1])


loudness = {}
tracks = sorted(f for f in os.listdir(SRC) if f.lower().endswith((".flac", ".wav")))
for i, name in enumerate(tracks, 1):
    src = os.path.join(SRC, name)
    start = 30 if duration(src) >= 95 else 0
    ref = os.path.join(IN, f"t{i}_ref.wav")
    ff("-ss", str(start), "-t", "60", "-i", src, "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le", ref)
    for kbps in (128, 66):
        tmp = os.path.join(EVAL, "tmp.opus")
        ff("-i", ref, "-c:a", "libopus", "-b:a", f"{kbps}k", tmp)
        ff("-i", tmp, "-ar", "48000", "-ac", "2", "-c:a", "pcm_s16le", os.path.join(IN, f"t{i}_o{kbps}.wav"))
        os.remove(tmp)
    for clip in (f"t{i}_ref", f"t{i}_o128", f"t{i}_o66"):
        loudness[clip] = round(lufs(os.path.join(IN, clip + ".wav")) + 14.0, 2)
    print(f"t{i}: {name} (from {start} s), loudnessDb {loudness[f't{i}_ref']}")

with open(os.path.join(IN, "loudness.json"), "w") as fh:
    json.dump(loudness, fh, indent=1)
