"""Measures every render in <eval>/out against its input, and checks the release gates.

Usage: python measure.py <eval folder> [--check]

Gates (from the code-review consensus), all on the phone's own processor output:
  1. Off is bit-exact: every *__off.wav has the input's PCM, sample for sample.
  2. True peak ≤ −1.0 dBTP (16× oversampled) on every active render.
  3. Each style is within 0.5 LU of Natural (without normalization, so the style alone is judged).
  4. With normalization on, Natural reduces gain on fewer than 1 % of 10 ms windows.
With --check, exits 1 if any gate fails.
"""

import glob
import json
import os
import re
import subprocess
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly

E = sys.argv[1]
CHECK = "--check" in sys.argv


def read(path):
    _, x = wavfile.read(path)
    return x


def lufs(path):
    err = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "ebur128", "-f", "null", "-"],
                         capture_output=True, text=True).stderr
    return float(re.findall(r"I:\s+(-?[\d.]+) LUFS", err)[-1])


def true_peak_db(x):
    y = resample_poly(x.astype(np.float64) / 32768.0, 16, 1, axis=0)
    return 20 * np.log10(np.abs(y).max() + 1e-12)


def align(y, x, max_lag=400):
    """Lag (frames) that best lines y up with x, from the first 4 s."""
    a = y[: 192000].astype(np.float64).mean(axis=1)
    b = x[: 192000].astype(np.float64).mean(axis=1)
    seg = b[max_lag: -max_lag]
    best, lag = -1e300, 0
    for k in range(0, max_lag):
        v = float(np.dot(a[max_lag + k: max_lag + k + len(seg)], seg))
        if v > best:
            best, lag = v, k
    return lag


def gain_reduction_fraction(y, x, gain_db):
    """Share of 10 ms windows where the output sits >0.1 dB under the input × the static gain."""
    lag = align(y, x)
    n = min(len(y) - lag, len(x))
    yd = y[lag: lag + n].astype(np.float64)
    xd = x[:n].astype(np.float64) * 10 ** (gain_db / 20)
    w = 480
    m = n // w
    ry = np.sqrt((yd[: m * w].reshape(m, w, -1) ** 2).mean(axis=(1, 2)))
    rx = np.sqrt((xd[: m * w].reshape(m, w, -1) ** 2).mean(axis=(1, 2)))
    live = rx > 32768 * 10 ** (-50 / 20)
    gr = 20 * np.log10((ry[live] + 1e-9) / (rx[live] + 1e-9))
    return float(np.mean(gr < -0.1)) if len(gr) else 0.0


loud = json.load(open(os.path.join(E, "in", "loudness.json")))
failures = []
rows = {}
for path in sorted(glob.glob(os.path.join(E, "out", "*__*.wav"))):
    clip, variant = os.path.basename(path)[:-4].split("__")
    y = read(path)
    x = read(os.path.join(E, "in", clip + ".wav"))
    r = {"lufs": lufs(path)}
    if variant == "off":
        if not np.array_equal(y, x):
            failures.append(f"{clip}: off is not bit-exact")
    else:
        r["tp"] = true_peak_db(y)
        if r["tp"] > -1.0:
            failures.append(f"{clip} {variant}: true peak {r['tp']:.2f} dBTP > −1.0")
    if variant == "natural_norm":
        g = -max(0.0, loud[clip])
        r["gr"] = gain_reduction_fraction(y, x, g)
        if r["gr"] >= 0.01:
            failures.append(f"{clip} natural+normalize: limiter working on {r['gr'] * 100:.1f} % of windows")
    rows[(clip, variant)] = r

clips = sorted({c for c, _ in rows})
variants = sorted({v for _, v in rows})
print(f"{'variant':14} {'dLUFS vs nat':>12} {'worst':>6} {'TP max':>7} {'GR %':>6}")
for v in variants:
    d = [rows[(c, v)]["lufs"] - rows[(c, "natural")]["lufs"] for c in clips if (c, v) in rows and (c, "natural") in rows]
    tps = [rows[(c, v)].get("tp", float("nan")) for c in clips if (c, v) in rows]
    grs = [rows[(c, v)]["gr"] * 100 for c in clips if (c, v) in rows and "gr" in rows[(c, v)]]
    print(f"{v:14} {np.mean(d):12.2f} {max(map(abs, d)):6.2f} {np.nanmax(tps) if tps else float('nan'):7.2f} "
          f"{(max(grs) if grs else float('nan')):6.2f}")
    if v in ("bass", "vocal") and max(map(abs, d)) > 0.5:
        failures.append(f"{v}: {max(map(abs, d)):.2f} LU from Natural (> 0.5)")

if failures:
    print("\nGATE FAILURES:")
    for f in failures:
        print("  " + f)
else:
    print("\nAll gates pass.")
if CHECK and failures:
    sys.exit(1)
