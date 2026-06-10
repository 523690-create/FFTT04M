"""
Merge sources into one training-ready segments.jsonl under the unified schema:
  - public datasets (parsed by download_preprocess.py), and
  - on-device exports: the app's <recording>.cough.json files (the same schema).

Run after download_preprocess.py has produced per-dataset JSONL, and after copying any
<recording>.cough.json exports off the phone.

Usage:
  python dataset_builder.py --ondevice ./ondevice_exports --out data/segments.jsonl
"""
from __future__ import annotations
import argparse
import glob
import json
import os

# ICBHI / COUGHVID native label → 4-class taxonomy (see label_mapping.md).
LABEL_MAP = {
    "urti": "bronchitis", "lrti": "bronchitis", "bronchiolitis": "bronchitis",
    "pneumonia": "pneumonia",
    "copd": "other", "asthma": "other", "covid-19": "other", "symptomatic": "other",
    "healthy": "control",
}


def map_label(native: str) -> str:
    return LABEL_MAP.get((native or "").strip().lower(), "unknown")


def load_ondevice(folder: str):
    """Read the app's <recording>.cough.json exports (each wraps {recording, segments[]})."""
    rows = []
    for path in glob.glob(os.path.join(folder, "*.cough.json")):
        with open(path, "r", encoding="utf-8") as f:
            obj = json.load(f)
        for seg in obj.get("segments", []):
            seg.setdefault("dataset", "ondevice")
            rows.append(seg)
    return rows


def load_jsonl(path: str):
    rows = []
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    rows.append(json.loads(line))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ondevice", default=None, help="folder of <rec>.cough.json exports")
    ap.add_argument("--jsonl", nargs="*", default=[], help="per-dataset segments.jsonl files")
    ap.add_argument("--out", default="data/segments.jsonl")
    args = ap.parse_args()

    rows = []
    for j in args.jsonl:
        rows += load_jsonl(j)
    if args.ondevice:
        rows += load_ondevice(args.ondevice)

    # Normalize 4-class labels from native labels where present.
    counts: dict[str, int] = {}
    for r in rows:
        labels = r.setdefault("labels", {})
        if labels.get("diagnosis_4class", "unknown") in (None, "", "unknown") and labels.get("native_label"):
            labels["diagnosis_4class"] = map_label(labels["native_label"])
        counts[labels.get("diagnosis_4class", "unknown")] = counts.get(labels.get("diagnosis_4class", "unknown"), 0) + 1

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")
    print(f"Wrote {len(rows)} segments → {args.out}")
    print("Class distribution:", dict(sorted(counts.items())))


if __name__ == "__main__":
    main()
