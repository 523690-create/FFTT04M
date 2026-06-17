# Session notes — 2026-06-17 (FFTT04M / FFTT04L)

Handoff document so context survives a restart / directory move. Applies to **both** M (`blue_sky`,
minSdk 32) and L (`main`, minSdk 23) unless noted. All edit/build work is in
`D:\AndroidProjects\{FFTT04M,FFTT04L,FFTT04D}` (the H:\ copies are stale — see
`three_project_split` memory).

End-of-session debug build letters: **M = `k`**, **L = `M`** (launcher-icon stamp).

---

## 1. Bluetooth removed from the mic spinner
BT (SCO/BLE) mic capture was proven useless for spectral analysis: the SCO uplink AGC on the test
headset (soundcore Life Q20) delivers hard-clipped, over-gained 16 kHz audio (solid/flat FFT). All
three AudioSources were exhausted (VOICE_COMMUNICATION → silence; MIC → ~5% clipped; VOICE_RECOGNITION
→ ~9% clipped). **Decision: exclude BT entirely.** `enumerateInputs()` now filters out
`TYPE_BLUETOOTH_SCO`/`TYPE_BLE_HEADSET`; the spinner only offers wired / USB / built-in mics. BT is
still fine for file transfer. (The old BT-routing block in `startRecording` is now dead code.)

## 2. Equalizer: AUTO | MANUAL toggle (Listen screen, portrait)
New button **"EQ: AUTO | MANUAL"** above the LATENCY/COLOR corner (`btnEqMode`, persisted in prefs key
`eq_mode_auto`, default AUTO). The pre-existing 5-band biquad EQ (100/300/1k/3k/8k Hz, ±40 dB) is the
MANUAL path.

- **MANUAL**: the 5 biquad sliders shape the waterfall (the old behavior); AGC is bypassed; a fixed
  −80…0 dB display mapping so the user's curve is what they see. Sliders full opacity.
- **AUTO**: the band-AGC de-tilt drives the scroll; the 5 sliders are disabled + faded (alpha 0.2);
  biquads bypassed; a **10-bar yellow overlay** (`AgcBarsOverlay.kt`, new file) draws over the sliders
  (2 bars per slider = the 5↔10 mapping) showing the live per-band de-tilt boost.

**IMPORTANT invariant (verified):** recordings save the **raw, unequalized** mic input. In the capture
loop the raw mic is copied to `audioCircularBuffer` and fed to the cough detector BEFORE the biquads
run, and the biquads + AGC only touch the FFT/display copy. EQ (manual or AUTO) is **display-only**.

### Band-AGC de-tilt algorithm (`MainActivity` capture loop, AUTO branch)
Goal: flatten the natural low-loud / high-quiet tilt so every band reads predominantly **green**
(Turbo), while silence stays black.
- Spectrum split into `AGC_BANDS=10` log-spaced bands; each band tracks the **max over a ~500 ms
  trailing window** (`AGC_WINDOW_MS`), eased by a one-pole smoother (`AGC_SMOOTH_MS=150`). The window
  forgets transient pops (a plug/unplug pop no longer pins the gain → fixes the post-mic-change "wall").
- **Global signal gate**: `sig` ramps 0→1 between `AGC_SILENCE_DB=-58` and `AGC_SIGNAL_DB=-25`
  (global loudest band). `sig=0` (silent) → fixed dark window (`AGC_SILENCE_CEIL_DB=-15`) keeps noise
  black; `sig=1` → full per-band untilt. Smoothly blended (no pop when a cough starts).
- **Green-centered mapping**: each band's ceiling maps to `AGC_GREEN_TARGET=0.5`; `AGC_SPREAD_DB=50`
  sets fall-to-blue below / rise-to-red above. `AGC_MAX_TILT_DB=48` caps per-band boost so a dead band
  doesn't light up mid-signal.
- Per-bin ceilings interpolated between band centers (no horizontal banding).
- Overlay levels = `((gMax - bandCeil)/AGC_MAX_TILT_DB) * sig`.

All the `AGC_*` consts are in `MainActivity`'s `companion object` — tune there.

## 3. USB transfer reliability (`GalleryActivity.offerToDesktop` + `promptDeleteAfterTransfer`)
The desktop handshake (offer manifest → desktop pulls → pushes `fftt_usb_ack.json` beside the offer)
was fine; the **phone side** was broken:
- Poll window was 120 s then died silently with the dialog stuck on "Waiting…". Now **20 min**, watching
  **both** the app-private and public ack paths, with a **live verbose dialog** (folder, storage mode,
  elapsed seconds) and `Log.i("FFTT04M", …)` at publish / ack-seen / timeout.
- The poll now runs on `!isFinishing` alone (NOT gated on the dialog), so dismissing the dialog (e.g.
  via a button) doesn't kill the watcher.
- **Delete safety (critical):** deletion is gated strictly on the ack. The "Desktop got them → Delete"
  button, if tapped before confirmation, **ARMS a deferred delete** — nothing is removed until the ack
  proves receipt; if it never comes, nothing is deleted. `pendingDeleteOnAck` + `performDeletion()`.

### Known follow-up (NOT yet done): per-file delete-as-acknowledged
User wants each clip deleted as its transfer is individually confirmed, not one batch at the end.
Requires a desktop change: pull file-by-file and append each received basename to an incremental
manifest; phone watches the manifest and deletes per entry. (Desktop currently does one bulk
`adb pull -a` + a single end-of-batch ack — see `FFTT04D/desktop/.../UsbImporter.kt`.)

### Known bug (flagged, NOT fixed): empty metadata sidecars
The `.json` sidecars written to public storage are 2 bytes (`{}`) — `GalleryTransfer.writeMetaSidecar`
/ `metaJsonFor` produces empty metadata, so the desktop receives blank sidecars. Worth fixing
(relevant to analysis quality).

## 4. Files touched (M and L identical)
- `MainActivity.kt` — enumerateInputs (BT strip), EQ mode (`eqAuto`, `setupEqModeToggle`,
  `applyEqMode`), capture-loop biquad gate + band-AGC normalization.
- `GalleryActivity.kt` — verbose USB offer dialog, 20-min dual-path poll, arm-and-defer delete.
- `res/layout/activity_main.xml` — `eqStack` FrameLayout wrapping `eqSlidersLayout` + `agcOverlay`;
  `btnEqMode` button.
- `AgcBarsOverlay.kt` — NEW: yellow per-band de-tilt overlay (self-refreshes ~15 fps while visible).

## 5. Environment / tooling notes
- adb: `C:\Users\belil\AppData\Local\Android\Sdk\platform-tools\adb.exe` (full path; not on PATH).
- Deploy convention: M → all connected Tier-1 devices (minSdk 32: P10, P3, A10); L → all connected
  (incl. legacy T380 / other sdk<32). Report version letters each rebuild.
- Permissions: `defaultMode` set to `bypassPermissions` in `H:\FFTT04M\.claude\settings.json` and
  mirrored to `D:\AndroidProjects\.claude\settings.json` (repointed to D:). Next launch should be from
  `D:\AndroidProjects` so the client is co-located with the code.

## 6. Research direction (see FFTT04D/SOUND_FRACTIONATION.md)
Analysis improvement = **unsupervised acoustic-unit discovery** ("cough phonemes"), NOT MFA. Build on
the existing clip-level k-means codebook (`AcousticUnitDiscovery.kt`) by adding **sub-clip
fractionation** → per-segment features → unit codebook → token sequences. Desktop will get multiple
fractionation methods under buttons + a recommended-workflows popup.
