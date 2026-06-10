"""
Download + preprocess public cough datasets to a common 48 kHz mono WAV form.

Datasets: COUGHVID (EPFL), ICBHI 2017, Coswara (IISc). Per-dataset metadata parsers map native
labels → the unified schema (see label_mapping.md) and emit one segments.jsonl per dataset.

This is a scaffold: the download URLs are correct, the resample path is complete, and the parser
stubs document exactly what each dataset needs. Fill the stubs to produce training-ready JSONL.
"""
from __future__ import annotations
import os
import tarfile
import zipfile
import requests
import librosa
import soundfile as sf

DATA_ROOT = "data"
SR = 48000

DATASETS = {
    "coughvid": {"url": "https://zenodo.org/record/4048312/files/public_dataset.zip", "kind": "zip"},
    "icbhi": {"url": "https://zenodo.org/record/1203745/files/Respiratory_Sound_Database.zip", "kind": "zip"},
    "coswara": {"url": "https://github.com/iiscleap/Coswara-Data/archive/refs/heads/master.zip", "kind": "zip"},
}


def download(url: str, out_path: str):
    if os.path.exists(out_path):
        return
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with requests.get(url, stream=True, timeout=60) as r:
        r.raise_for_status()
        with open(out_path, "wb") as f:
            for chunk in r.iter_content(1 << 20):
                f.write(chunk)


def extract(path: str, dest: str, kind: str):
    os.makedirs(dest, exist_ok=True)
    if kind == "tar":
        with tarfile.open(path, "r:*") as t:
            t.extractall(dest)
    else:
        with zipfile.ZipFile(path, "r") as z:
            z.extractall(dest)


def resample_to(in_path: str, out_path: str, sr: int = SR):
    y, orig = librosa.load(in_path, sr=None, mono=True)
    if orig != sr:
        y = librosa.resample(y, orig_sr=orig, target_sr=sr)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    sf.write(out_path, y, sr)


# ---- per-dataset parsers (stubs to fill) --------------------------------------------------------

def parse_coughvid(root: str):
    """COUGHVID: metadata CSV + .webm/.ogg audio. Map status/pneumonia → diagnosis_4class.
    Emit segments.jsonl rows (whole file = one segment unless you run segmentation)."""
    raise NotImplementedError("Fill: read metadata CSV, resample audio, map labels per label_mapping.md")


def parse_icbhi(root: str):
    """ICBHI: <recording>.wav + <recording>.txt (cycle annotations) + diagnosis CSV.
    Map URTI/LRTI/Bronchiolitis → bronchitis, Pneumonia → pneumonia, etc."""
    raise NotImplementedError("Fill: read .txt cycles + diagnosis CSV, resample, map labels")


def parse_coswara(root: str):
    """Coswara: per-participant folders with cough-heavy/shallow WAVs + metadata.csv.
    Only control / other are derivable (no bronchitis/pneumonia/croup/habit)."""
    raise NotImplementedError("Fill: iterate participant folders, resample cough WAVs, map labels")


def main():
    os.makedirs(DATA_ROOT, exist_ok=True)
    for name, cfg in DATASETS.items():
        archive = os.path.join(DATA_ROOT, f"{name}.archive")
        print(f"[{name}] downloading…")
        download(cfg["url"], archive)
        print(f"[{name}] extracting…")
        extract(archive, os.path.join(DATA_ROOT, name), cfg["kind"])
    print("Downloaded + extracted. Now run the per-dataset parsers to build segments.jsonl.")


if __name__ == "__main__":
    main()
