# FFTT04M

[![Android CI](https://github.com/523690-create/FFTT04M/actions/workflows/android.yml/badge.svg)](https://github.com/523690-create/FFTT04M/actions/workflows/android.yml)

A real-time audio spectrogram and wavelet analysis tool for Android. Listen live, freeze and
crop interesting moments, save them, and analyse them with FFT or wavelet transforms.

**Latest debug APK:** built on every push — grab it from the
[**Nightly** release](https://github.com/523690-create/FFTT04M/releases/tag/nightly) or the
artifacts of the latest [Actions run](https://github.com/523690-create/FFTT04M/actions).

> The manual section below is **auto-synced once a day** from the in-app manual
> (`app/src/main/assets/user_manual.md`). Edit the manual, not the text between the markers.

<!-- MANUAL:START -->
# FFTT04M — User Manual

A real-time audio spectrogram and wavelet analysis tool. Listen live, freeze and crop
interesting moments, save them, and analyse them with FFT or wavelet transforms.

*This manual is bundled with the app and opened from the **HELP** button in the Gallery.*

---

## Quick start

1. On the **Listen** screen, grant microphone access when prompted.
2. Watch the live spectrogram scroll. Tap it once to **freeze**; tap again to resume.
3. While frozen, drag a box to **crop** a region, then **Save** it.
4. Open the **Gallery** to see saved recordings.
5. Tap a recording to open the **FFT analysis** viewer; from there you can switch to
   **Wavelet** analysis or play the audio.

---

## Screens

### Listen (live)
The home screen shows the live microphone spectrogram (frequency vs. time, colour = intensity).

- **Tap** the spectrogram to freeze / unfreeze.
- **Freeze, then drag** a rectangle to select a time/frequency region.
- **Save** writes the crop as a `.wav` recording (16-bit PCM) plus a thumbnail.
- **COLOR** opens the colour-scheme picker (see *Colour schemes*). In Listen mode your
  choice is global and remembered across sessions.
- **GALLERY** opens saved recordings. **LATENCY** helps measure audio round-trip delay.
- The EQ sliders (100 Hz … 8 kHz) shape **only the waterfall display** — the recording you
  save and play back is always the **raw, unprocessed mic feed**.

### Gallery
A grid/list of saved recordings, each with a thumbnail and filename.

- **Tap** a recording to open it in the **FFT analysis** viewer.
- Under each name is the clip's **auto classification** — the inferred class (e.g.
  *dry hacking (DH)*) and the phoneme sequence it was decoded into (see
  *On-device classification*).
- The grid/list toggle (top-right) switches layout.
- **CLOUD** opens the **Clip Cloud** — an acoustic map of your recordings (see *Clip Cloud*).
- **SHARE** sends/receives recordings between devices (see *Sharing*).
- **HELP** opens this manual. **LISTEN** returns to the live screen.

### FFT analysis (Viewer)
Detailed FFT spectrogram of a saved recording, with three tabs:

- **EQ** — per-band gain sliders (display-only, like Listen).
- **FILTER** — noise filter %, plus attack (Rise) and release (Fall) times.
- **DISPLAY** — FFT **Size** and **Step** (overlap), **ENHANCE**, **COLOR**, **PROCESSED
  PLAYBACK**, and **TIME GRID**.

Top bar: **GALLERY**, **LISTEN**, **WAVELET** (analysis of the same file), **NOTE**
(add a comment / refresh the thumbnail), **PLAY** (raw audio playback).

- **PROCESSED PLAYBACK** plays the recording **as you see it** — EQ, FILTER, and the ENHANCE
  post-processors are all applied to the sound (reconstructed from the displayed spectrogram with
  the original phase). The *engine* modes (Reassignment/Synchrosqueeze/Constant-Q/Multitaper) and
  very old/low-memory devices fall back to EQ + filter only. Experimental.
- **TIME GRID** overlays vertical time markers — **1 s** (thick) and/or **100 ms** (thin);
  multi-select, with Clear. Independent of BLUR.

In analysis screens the colour choice is **tied to the recording**, so each recording
remembers its own scheme. The first time, it inherits your last-used (global) scheme.

### Wavelet analysis
A continuous/discrete wavelet view of the recording, with two tabs:

- **SETUP** — choose the **MODE**, then the **FAMILY** (its choices change with the mode),
  the boundary handling (**BND**), soft/hard **Threshold**, and **View** options (LOG,
  L-NORM). A safety note warns when the sampling rate is above the safe limit for the mode.
- **SLIDERS** — **LEVEL** (decomposition depth), **ORDER**, **SAMPLE** (rate), **THRESH**.

New recordings default to **CWT, max level/order, zero threshold, max safe sample rate**.
All settings persist per recording.

---

## On-device classification (phoneme decode)

Every recording is analysed on the phone and given a best-guess **class** — cough type
(dry, dry-hacking, typical-bronchitis, croup…), snoring, sneeze, voice, or noise. It appears
under each clip in the Gallery, e.g. `= dry hacking (DH): DH1 D8 N37 …`.

How it works: the clip is split into short overlapping windows; each window is matched to the
nearest entry in a learned **codebook** of acoustic units ("phonemes"), producing a sequence
of codes (a "word"). The dominant code's class becomes the clip's label. On capable phones
(≈3 GB+ RAM) the windows are described with **HuBERT** speech-model features for much higher
accuracy; older phones fall back to a lighter signal-processing version automatically.

**Your labels train it.** Add a comment with the **NOTE** button in the Viewer — e.g.
"dry hacking", "snoring" — and it becomes a training label the next time the codebook is
rebuilt. You can label a whole clip by its main sound even if it also contains noise or
voice; the rebuild routes those background stretches back to noise/voice on its own.

## Clip Cloud

The **CLOUD** button (Gallery) plots every recording as a point on an **acoustic map**: clips
that sound alike sit close together. It uses the same HuBERT features as the classifier,
projected from hundreds of dimensions down to a rotatable 3-D view (principal-component
analysis).

- **Colour = class** (your manual comment if present, otherwise the auto-decode). The legend
  top-left lists each class and its count.
- **Coloured outlines** bound each cough/respiratory class; where two outlines overlap, those
  classes sound similar.
- **One-finger drag rotates** the cloud in 3-D. The flat view shows the two biggest axes of
  variation; rotating brings in the **third** axis (depth), which can pull apart clusters that
  overlap head-on.
- **Two-finger drag pans**, **pinch zooms**, **tap a point** opens that clip in the Viewer.

Available on HuBERT-capable phones only. The first open computes and caches each clip's
position (with a progress count); later opens are quick.

---

## Analysis modes (Wavelet)

| Mode | What it does | FAMILY choices |
|------|--------------|----------------|
| **DWT** | Discrete wavelet transform | Daubechies / Symlet / Coiflet |
| **WPT** | Wavelet packet transform | Daubechies / Symlet / Coiflet |
| **CWT** | Continuous wavelet transform (best frequency detail) | Morlet / Mexican Hat |
| **Reconstruct** | Inverse transform (denoise preview) | Daubechies / Symlet / Coiflet |

- **Morlet** — the default CWT wavelet; excellent for tonal, "squiggly" pitch tracks.
- **Mexican Hat (Ricker)** — a second-derivative shape that isolates ridge peaks and
  transients.

---

## Colour schemes

Eight perceptually-designed colour maps (256-level gradients):

**Turbo** (default), **Viridis**, **Magma**, **Inferno**, **Plasma**, **Cividis**
(colour-vision-deficiency friendly), and **Gray**.

Open the picker from the **COLOR** button on any screen. Tap a swatch to apply it
immediately. Listen remembers your choice globally; analysis screens remember it per
recording.

---

## Enhancements (FFT analysis → DISPLAY → ENHANCE)

Pick one **engine** (or none) and stack any number of **post-processors**:

- **Gaussian / Bilateral / TV Denoise / Butterworth** — general smoothing/denoise.
- **Anisotropic** — edge-preserving diffusion; smooths along ridges, keeps edges sharp.
- **Gabor ridges** — boosts oriented "squiggly" spectral lines of any slope.
- **Frangi ridges** — multi-scale ridge (vesselness) detector for continuous lines.

Heavier filters (Gabor, Frangi) are disabled on older/low-memory devices and labelled
"(needs newer device)".

---

## Sharing recordings between devices

Two devices running this app can transfer recordings — **with all their analysis settings,
comments, and thumbnails** — directly, no internet or account needed. Recordings the receiver
already has are **skipped** (only the missing ones transfer), so re-sharing is safe.

**Bluetooth (most reliable — no Wi-Fi needed):**
1. **One-time:** pair the two devices in Android's Bluetooth settings.
2. Sender: **Gallery → SHARE → Send via Bluetooth**. A QR appears.
3. Receiver: **Gallery → SHARE → Receive (scan QR)**, then scan it. The transfer runs over
   Bluetooth — works even with no Wi-Fi or on mobile data.

**Wi-Fi QR (faster, same network):**
1. Both devices on the **same Wi-Fi**.
2. Sender: **SHARE → Send via Wi-Fi QR**. Receiver: **SHARE → Receive (scan QR)**, scan it.
   (If it times out, the Wi-Fi is likely blocking device-to-device — use Bluetooth or file.)

The QR only carries the connection handshake; the recordings stream over the chosen link.
**Receiving runs in the background** — once you scan, you can leave the Gallery; a notification
pops up as each recording arrives, and a final one summarises how many were imported/skipped.
Tap any of them to return to the Gallery. (Allow the notification permission when asked.)

**On different networks (mobile data, different Wi-Fi)?** Use **SHARE → Export / share to
file…** on the sending device — it packages the gallery into a `.zip` and opens the system
share sheet (Quick Share, Bluetooth, email, Drive, …), which works across any network. On the
receiving device, **tap the received file and choose FFTT** to import it, or use **SHARE →
Import from file…** and pick it.

## Tips

- If a recording looks empty, check the COLOR scheme and the LOG/L-NORM view toggles.
- On older devices, wavelet analysis automatically eases its settings to avoid running
  out of memory; a brief message appears when it does.
- Playback uses the raw PCM audio of the recording.

---

*Draft manual — updated as features change.*
<!-- MANUAL:END -->

## Building

```
./gradlew :app:assembleDebug      # Linux/macOS
gradlew.bat :app:assembleDebug    # Windows
```
Requires JDK 17+ (CI uses 21). Min SDK 23, target/compile SDK 36.
