# Label Mapping & Data Landscape

Target taxonomy: **bronchitis | pneumonia | croup | habit_cough** (+ `control`, `other`).

**No public dataset contains all four classes.** Bronchitis/pneumonia are partly covered by ICBHI +
COUGHVID; **croup and habit cough have no public audio** and must be collected clinically.

## Public datasets

| Dataset | Size | Native labels | Access |
|---------|------|---------------|--------|
| COUGHVID (EPFL) | ~25k coughs | healthy, symptomatic, COVID-19, self-reported pneumonia | https://zenodo.org/record/4048312 |
| ICBHI 2017 | 6,898 recordings | URTI, LRTI, pneumonia, bronchiolitis, COPD, asthma, healthy | https://zenodo.org/record/1203745 |
| Coswara (IISc) | ~2,000 subjects | healthy, symptomatic, COVID-19 | https://github.com/iiscleap/Coswara-Data |
| COVID-19 Sounds (Cambridge) | ~30k | COVID vs non-COVID | https://www.covid-19-sounds.org/ |
| NoCoCoDa | small | non-COVID coughs | https://zenodo.org/record/5528552 |
| ESC-50 / AudioSet | — | "cough" category | cough-vs-non-cough pretraining only |

## ICBHI → 4-class

| ICBHI label | → | Notes |
|-------------|---|-------|
| URTI | bronchitis | viral URTI overlaps acute bronchitis |
| LRTI | bronchitis | lower-respiratory infection w/o consolidation |
| Bronchiolitis | bronchitis | pediatric viral bronchitis equivalent |
| Pneumonia | pneumonia | direct |
| COPD, Asthma | other | avoid contaminating bronchitis |
| Healthy | control | QC reference |

## COUGHVID → 4-class

| COUGHVID label | → | Notes |
|----------------|---|-------|
| pneumonia (self-reported) | pneumonia | low-quality labels — high-confidence only |
| healthy | control | |
| symptomatic, COVID-19 | other / exclude | not bronchitis/pneumonia |

## Missing classes — collection protocol (IRB-friendly)

General: smartphone mic, WAV 48 kHz/16-bit mono, 15–20 cm from mouth, quiet room (<40 dB),
10–20 coughs/subject, metadata {age, sex, diagnosis source, device, environment}.

- **Croup** (pediatric, 6 mo – 6 y, clinician-confirmed viral croup; barking cough ± inspiratory
  stridor): record spontaneous coughs; label `cough only` / `cough + stridor` / `stridor only`.
  Reject crying/talking/handling noise. The stridor signature is distinctive → easy to classify.
- **Habit cough** (school-age 6–16, clinician-diagnosed somatic cough syndrome, absent during sleep,
  dry/repetitive): record 1–2 min of natural coughing; label `habit cough` / `pause` / `other`.

## Class balancing (augmentation)

Croup + habit cough are tiny → augment ×3–5. Pneumonia: keep all high-confidence. Bronchitis:
downsample if over-represented. Use only diagnosis-preserving augmentations:
time-stretch 0.9–1.1×, pitch ±1 semitone (never >2), gain ±3 dB, real room noise at 20–30 dB SNR,
light SpecAugment (1–2 mel masks, 20–40 ms time masks), small-room reverb.
