# blue_sky — Cough Analysis Expansion

A major expansion turning FFTT04M from a general real-time spectrogram tool into a
**cough-analysis instrument**, per the FUTURE VISION in `handoff.md` and the research brief
(classical spectral + ridge geometry + ML training pipeline).

Built on the `blue_sky` branch. Intermediate work is committed/pushed here, not to `main`.

## Why this design

The research brief identifies three methodological tiers:

| Tier | Method | Transparency | Accuracy (AUROC) |
|------|--------|--------------|------------------|
| 1 | Classical FFT spectral (Q-ratio, Fmax, duration) | High | 0.70–0.85 |
| 2 | MFCC + TDNN / logistic regression | High | 0.80–0.92 |
| 3 | Transformer (EAT) / multimodal | Moderate | 0.85–0.97 |

Tier 1 is fully reproducible and runs **on-device in Kotlin today** — it's where this expansion
starts. Tiers 2–3 need Python + large datasets and live in `research/cough/` as a training
scaffold for later, off-device work.

Plus the user's specific object of interest: the **"bronchitis squiggle"** — a short time–frequency
ridge whose instantaneous frequency follows a near-parabolic trajectory between ~300–1000 Hz. We
extract it as a ridge + parabola fit, yielding a compact, comparable feature vector `[a, b, c, …]`.

## On-device engine (Kotlin) — `com.example.FFTT04M.cough`

Pure DSP (no Android deps beyond `FloatArray`), reusing the project's `FFTUtils`.

- **CoughDsp** — primitives: short-time RMS envelope, Hann window, magnitude spectrum (via FFTUtils),
  parabolic sub-bin peak interpolation, 3×3 least-squares solver, autocorrelation pitch strength,
  spectral flatness (Wiener entropy).
- **CoughSegmenter** — energy-envelope event detection: 20 ms window / 10 ms hop RMS, dynamic
  threshold (median × factor), merge gaps < 200 ms, discard < 200 ms or > 2 s.
- **FftFeatureExtractor** — per cough: duration, **Q-ratio** = E(60–600 Hz)/E(600–6000 Hz),
  **Fmax** = peak-energy frequency.
- **RidgeExtractor** — the squiggle: STFT (25 ms/10 ms Hann), band-limit 300–1000 Hz, per-frame
  argmax frequency with sub-bin interpolation → `{(t, f_t)}`, parabola fit
  `f ≈ a·t² + b·t + c` (least squares), features `[a (curvature), b (slope), c, centerFreq,
  durationFrames, energy, bandwidth, R²]`.
- **SpeechRejector** — cough-vs-speech heuristic (not a trained model): spectral flatness
  (cough is noise-like/flat; voiced speech is peaky/harmonic), autocorrelation pitch strength,
  duration. Produces `isLikelyCough` + a `speechLikelihood` score.
- **CoughAnalyzer** — orchestrates segment → FFT features + ridge fit + speech gate → `CoughAnalysis`.
- **CoughSchemaJson** — serializes to the unified `segments.jsonl` training schema (hand-rolled,
  dependency-free, unit-tested).

### UI
- **CoughAnalysisActivity** — load a recording, run the analyzer, list detected coughs with their
  features and a ridge overlay; export the JSON schema for training.

## Off-device ML scaffold — `research/cough/`
Download/preprocess for COUGHVID + ICBHI + Coswara, the unified JSON schema, ICBHI/COUGHVID →
4-class label mapping (bronchitis / pneumonia / croup / habit-cough), and TDNN4 / EAT4 PyTorch
model scaffolds. Croup + habit-cough have no public data — a collection protocol is documented.

## Status
See git log on `blue_sky`. Engine + tests land first (offline-testable via `gradlew test`), then
UI, then the research scaffold.
