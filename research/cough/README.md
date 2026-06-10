# Cough Analysis — Research / Training Scaffold

Off-device companion to the on-device Kotlin engine (`app/.../cough/`). This directory holds the
**ML tier** (Tiers 2–3): dataset download/preprocessing, the unified data schema, label mapping to a
4-class clinical taxonomy, feature extraction that mirrors the on-device DSP, and PyTorch model
scaffolds (TDNN, EAT-style Transformer).

Nothing here runs on the phone. The on-device app produces `<recording>.cough.json` (the unified
schema); those exports plus public datasets feed the training pipeline here.

## Methodological tiers

| Tier | Method | Where | Accuracy (AUROC) |
|------|--------|-------|------------------|
| 1 | Classical FFT spectral (Q-ratio, Fmax, duration) + ridge geometry | on-device (Kotlin) | 0.70–0.85 |
| 2 | MFCC + TDNN / logistic regression | here (PyTorch) | 0.80–0.92 |
| 3 | Transformer (EAT) / multimodal | here (PyTorch) | 0.85–0.97 |

Key insight from the literature review: **FFT alone is insufficient** for high accuracy;
**MFCC + classical ML is the practical sweet spot**; transformers have the highest ceiling but need
large, diverse, carefully-QC'd datasets.

## Files

- `requirements.txt` — Python deps.
- `schema.json` — the unified `segments.jsonl` record schema (shared with the Kotlin exporter).
- `label_mapping.md` — ICBHI / COUGHVID → {bronchitis, pneumonia, croup, habit_cough} mapping, the
  data-availability landscape, and the collection protocol for the two classes with no public data.
- `download_preprocess.py` — fetch + resample COUGHVID / ICBHI / Coswara to 48 kHz mono WAV.
- `features.py` — FFT features (Q-ratio, Fmax, duration) and the ridge parabola fit, mirroring the
  on-device Kotlin so features are consistent across the boundary.
- `dataset_builder.py` — merge public datasets + on-device `.cough.json` exports into one
  `segments.jsonl` using the unified schema and label mapping.
- `models.py` — `TDNN4` (MFCC input) and `EAT4` (mel-patch Transformer) 4-class scaffolds.

## Target taxonomy

`bronchitis | pneumonia | croup | habit_cough` (+ `control`, `other`). No public dataset contains all
four; croup and habit cough must be collected clinically (see `label_mapping.md`).

## Pipeline

```
raw audio ─▶ bandpass 60–6000 Hz ─▶ segment (RMS envelope) ─▶ per-cough features
   FFT: duration, Q-ratio, Fmax        ridge: a,b,c,centerFreq,bandwidth,R²
   MFCC: 13 coeff + Δ + ΔΔ              mel patches: 64–128 bins, 150–200 ms
        └────────────┬───────────────────────────┘
                     ▼
         Stage 1: cough vs non-cough (TDNN / small CNN)
         Stage 2: 4-class diagnosis (TDNN / EAT / logistic regression)
```
