"""
Cough feature extraction — FFT spectral features + the 300-1000 Hz ridge parabola fit.

Mirrors the on-device Kotlin engine (app/.../cough/) so features are consistent across the
phone/server boundary. Pure numpy/scipy; no torch needed.

Features per cough segment:
  FFT:   duration_s, q_ratio = E(60-600)/E(600-6000), fmax_hz
  Ridge: a (curvature), b (slope), c (intercept), center_freq_hz, frame_count,
         energy, bandwidth_hz, r_squared
"""
from __future__ import annotations
import numpy as np
import scipy.signal as signal


# ---- segmentation (energy envelope) -------------------------------------------------------------

def rms_envelope(x: np.ndarray, sr: int, win_ms=25.0, hop_ms=10.0):
    win = max(1, int(win_ms / 1000 * sr))
    hop = max(1, int(hop_ms / 1000 * sr))
    frames = max(0, (len(x) - win) // hop + 1)
    env = np.empty(frames, dtype=np.float64)
    for f in range(frames):
        s = f * hop
        env[f] = np.sqrt(np.mean(x[s:s + win] ** 2))
    return env, win, hop


def segment(x: np.ndarray, sr: int, threshold_factor=4.0, merge_gap_ms=200.0,
            min_ms=200.0, max_ms=2000.0):
    """Return list of (start_sample, end_sample) candidate cough events."""
    env, win, hop = rms_envelope(x, sr)
    if len(env) == 0:
        return []
    thr = np.median(env) * threshold_factor
    active = env > thr
    runs, start = [], -1
    for i, a in enumerate(active):
        if a and start < 0:
            start = i
        if not a and start >= 0:
            runs.append((start, i - 1)); start = -1
    if start >= 0:
        runs.append((start, len(env) - 1))
    # merge gaps (convert ms → frames; one hop is hop/sr*1000 ms)
    merge_frames = int(merge_gap_ms / (hop / sr * 1000))
    merged, cur = [], None
    for r in runs:
        if cur is None:
            cur = r
        elif r[0] - cur[1] <= merge_frames:
            cur = (cur[0], r[1])
        else:
            merged.append(cur); cur = r
    if cur is not None:
        merged.append(cur)
    out = []
    for s, e in merged:
        s_samp, e_samp = s * hop, min(e * hop + win, len(x))
        dur_ms = (e_samp - s_samp) / sr * 1000
        if min_ms <= dur_ms <= max_ms:
            out.append((s_samp, e_samp))
    return out


# ---- FFT spectral features ----------------------------------------------------------------------

def fft_features(seg: np.ndarray, sr: int):
    n = len(seg)
    seg = seg - np.mean(seg)
    mag = np.abs(np.fft.rfft(seg))
    freqs = np.fft.rfftfreq(n, 1 / sr)
    low = (freqs >= 60) & (freqs <= 600)
    high = (freqs > 600) & (freqs <= 6000)
    low_e, high_e = mag[low].sum(), mag[high].sum()
    band = (freqs >= 60) & (freqs <= 6000)
    fmax = float(freqs[band][np.argmax(mag[band])]) if band.any() else 0.0
    return {
        "duration_s": n / sr,
        "q_ratio": float(low_e / high_e) if high_e > 1e-9 else 0.0,
        "fmax_hz": fmax,
    }


# ---- ridge: the bronchitis squiggle -------------------------------------------------------------

def ridge_features(seg: np.ndarray, sr: int, lo_hz=300.0, hi_hz=1000.0,
                   win_ms=25.0, hop_ms=10.0, prominence=0.15):
    """STFT peak-track in [lo,hi] Hz, then fit f = a t^2 + b t + c by least squares."""
    win = max(8, int(win_ms / 1000 * sr))
    hop = max(1, int(hop_ms / 1000 * sr))
    nfft = 1 << (win - 1).bit_length()
    w = np.hanning(win)
    ts, fs, es = [], [], []
    pos = 0
    while pos + win <= len(seg):
        frame = seg[pos:pos + win] * w
        mag = np.abs(np.fft.rfft(frame, nfft))
        freqs = np.fft.rfftfreq(nfft, 1 / sr)
        band = (freqs >= lo_hz) & (freqs <= hi_hz)
        if band.any() and mag.sum() > 1e-12:
            band_mag = mag[band]
            k = np.argmax(band_mag)
            prom = band_mag.sum() / mag.sum()
            if prom >= prominence and band_mag[k] > 0:
                kf = np.flatnonzero(band)[k]
                # parabolic sub-bin interpolation
                if 0 < kf < len(mag) - 1:
                    a0, b0, c0 = mag[kf - 1], mag[kf], mag[kf + 1]
                    denom = a0 - 2 * b0 + c0
                    off = 0.5 * (a0 - c0) / denom if abs(denom) > 1e-12 else 0.0
                else:
                    off = 0.0
                freq = (kf + off) * sr / nfft
                ts.append((pos + win / 2) / sr)
                fs.append(freq)
                es.append(float(mag[kf]))
        pos += hop

    if len(ts) < 3:
        return {"valid": False, "curvature_a": 0, "slope_b": 0, "intercept_c": 0,
                "center_freq_hz": 0, "frame_count": len(ts), "energy": 0,
                "bandwidth_hz": 0, "r_squared": 0}, list(zip(ts, fs))

    t = np.asarray(ts); f = np.asarray(fs)
    coeffs = np.polyfit(t, f, 2)          # [a, b, c]
    a, b, c = map(float, coeffs)
    pred = np.polyval(coeffs, t)
    ss_res = np.sum((f - pred) ** 2)
    ss_tot = np.sum((f - f.mean()) ** 2)
    r2 = float(1 - ss_res / ss_tot) if ss_tot > 1e-12 else 1.0
    mid = (t[0] + t[-1]) / 2
    return {
        "valid": True, "curvature_a": a, "slope_b": b, "intercept_c": c,
        "center_freq_hz": float(np.polyval(coeffs, mid)),
        "frame_count": len(t), "energy": float(np.mean(es)),
        "bandwidth_hz": float(np.std(f)), "r_squared": r2,
    }, list(zip(ts, fs))


def analyze(x: np.ndarray, sr: int):
    """Full pipeline → list of per-cough feature dicts."""
    out = []
    for i, (s, e) in enumerate(segment(x, sr)):
        seg = x[s:e]
        out.append({
            "index": i, "start_sample": int(s), "end_sample": int(e),
            "fft": fft_features(seg, sr),
            "ridge": ridge_features(seg, sr)[0],
        })
    return out


if __name__ == "__main__":
    # self-check: synthetic parabolic chirp 300->1000 Hz should recover positive curvature.
    sr = 44100
    t = np.arange(int(0.4 * sr)) / sr
    a_true, c_true = 4375.0, 300.0
    phase = 2 * np.pi * (a_true * t ** 3 / 3 + c_true * t)
    x = np.sin(phase).astype(np.float32)
    rf, _ = ridge_features(x, sr)
    print("recovered ridge:", {k: round(v, 1) if isinstance(v, float) else v for k, v in rf.items()})
    assert rf["valid"] and rf["r_squared"] > 0.85 and 2500 < rf["curvature_a"] < 6500
    print("OK")
