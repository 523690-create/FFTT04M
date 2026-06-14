# HANDOFF — FFTT04M (for Claude Code)

Read this file first, then continue the work. This captures context that is NOT in the
codebase or the other .md files — it lives only in the chat session that produced it.
Date: 2026-06-12 (latest session below; older 2026-06-05 notes follow).

## SESSION 2026-06-13 — always-on background capture + capture-quality decisions (M)
Workspace moved to **D:\AndroidProjects** (fresh clones from GitHub; datasets alongside). Five user
decisions, all implemented in M **and** L, both `assembleDebug` green and pushed:
1. **Always-on**: ported `CoughCaptureService` (foreground-mic service, wake lock, notifications,
   live gallery refresh) to M + a **BACKGROUND** toggle on Listen (portrait+landscape) with mic
   mutual-exclusivity (starting the live view pauses the service). Manifest gained
   FOREGROUND_SERVICE_MICROPHONE / WAKE_LOCK / VIBRATE + the microphone-typed service.
2. **Retain until dumped**: coughs are never auto-deleted (no rotation); the existing Gallery →
   "Offer recordings to desktop" USB path dumps them. Clear-after-dump is a desktop-phase item.
3. **Battery**: capture continuously while charging OR battery > 20%; at **≤20% on battery** pause
   background capture (release mic + wake lock — only the Listen screen captures), auto-resume when
   charged or back above 20%. `LOW_PCT = 20` in the service.
4. **UNPROCESSED**: new `MicSource` picks `AudioSource.UNPROCESSED` (truest waveform, matches desktop)
   with a **proactive** MIC fallback — API<24 / `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` / a
   known-bad denylist — not try-and-fail. Mic **spinner** (`setPreferredDevice`) is unaffected.
5. **Precision**: `CoughClassifier.PRECISION_THRESHOLD = 0.65` used at every real-time capture site.
NOT yet device-tested. Per user, next: discuss desktop (D), then circle back to mobile (deploy/test).
Background-service mic *selection* (re-resolving the spinner's device) is a follow-up; default routing for now.

## SESSION 2026-06-12 — cough detector, Listen-screen auto-capture, launcher roundel
- **New cough/not-cough detector**: ported `WholeClipFeatures` + `CoughForest` + `CoughClassifier`
  (+ bundled `cough_forest.txt.gz`, ~2.1 MB) into `cough/`. The trained random forest (AUC 0.92,
  ~91 % sens / 83 % spec on the desktop ALLDATA corpus; cough vs speech/crying/non-human, with
  sneezing/breathing/snoring as "don't care") replaces the old low-specificity `SpeechRejector` verdict.
  `CoughDetector.endEvent` now uses `CoughClassifier.isCough(pcm, sr)` (falls back to the old verdict if
  the model is absent); the streaming noise-floor onset capture is unchanged. FFT is byte-identical to
  the desktop via the shared module, so the model applies directly. (Detector study + taxonomy live in
  the FFTT04D memory `cough-detection-redesign`.)
- **Hands-free auto-capture on the Listen screen** (`MainActivity`): the live mic (pre-EQ) is fed to
  `CoughDetector`; each detected cough is auto-saved as a Gallery WAV (`cough_<ts>.wav`), with **white
  vertical borders** drawn into the scrolling spectrogram at its start/end columns and the
  **between-borders region saved as the recording's 256×256 `.png` icon**
  (`FFTHeatMapView.markCoughAndSnapshot`), plus a centred green "✓ cough saved (N)" flash. File I/O runs
  off the audio thread. (The long-press-Gallery `CoughCaptureActivity` still exists as a dedicated screen.)
- **Launcher-icon roundel** on the FFT Listening / GALLERY titles (portrait + landscape): a sized
  `drawableStart` of `@drawable/ic_launcher_foreground` (the round magenta-letter icon) via
  `ViewUtils.setVersionRoundelStart()` — uses the app's own per-build icon, so colours track the build.
- Deployed to the Pixels (Tier-1 fleet). The J7 / legacy devices run the FFTT04L variant.

- ✅ **Gallery Transfer Comment Sync Fix** (`HEAD`): Fixed a logic error in `GalleryTransfer.importBundle()` where `.txt` and `.png` files were skipped on modern devices if the audio recording was already present. Now, critical metadata (comments/thumbnails) always syncs, ensuring that edits made after a prior transfer are propagated correctly.

## COMPLETED IN THIS SESSION (2026-06-05) — verified end-to-end on Nexus 7 (API 23)
- ✅ **FLAC removed, WAV-only** (`a31505e`): 16-bit PCM WAV everywhere; dropped the invalid FLAC container.
- ✅ **REAL root cause of "empty Gallery" found & fixed** (`4fab9d7`): GalleryActivity only
  listed `.flac`, so WAV recordings never showed. The "save regression" was a red herring —
  saves were succeeding, just invisible. Fixes:
  - GalleryActivity now lists `.wav` (+ `.flac` legacy).
  - New `WavReader.kt` parses raw PCM WAV (no MediaCodec decoder exists for audio/raw).
  - ViewerActivity.loadAndDecode + WaveletActivity.loadAndDecode branch on extension:
    WAV → WavReader, FLAC → MediaExtractor/MediaCodec. PLAY uses decoded PCM, unchanged.
- ✅ **Band-label corruption ACTUALLY fixed** (`4fab9d7`): real culprit was
  `updateAllLabelPositions()` calling `setMaxTextSizeToFit()` on the header labels (blew them
  up from stale parent dims → clipped to one glyph). Removed header labels from that fit list;
  they keep fixed XML textSize. (Earlier `301082a` autosize-skip alone was NOT sufficient.)
- ✅ **UI_CHANGELIST.md**: already fully reconciled.

### Verified end-to-end on Nexus 7
Gallery lists the WAV → tap opens Viewer → spectrogram renders → EQ ("100/300/1k/3k/8k Hz"),
FILTER ("Filter %/Rise Time/Fall Time"), and DISPLAY labels all render correctly.

### Superseded notes (kept for history)
- `28dd073` (WAV fallback in legacy save path) and `301082a` (autosize skip) were partial; the
  `a31505e` + `4fab9d7` changes are the complete fix. The legacy save fallback in `28dd073` was
  later replaced by WAV-only in `a31505e`.

Next (optional polish): push a matching `.png` so gallery thumbnails aren't placeholders for
adb-pushed test files (real recordings already write the PNG); minor top-bar truncation tuning.

## How to work on this project (conventions)
See `agents.md` for full rules. Key points: don't apologize; terse technical tone; apply
only the most-recently-requested change; after each change do a Gradle sync + build and do
NOT present work as done if the build fails; keep Activities small/modular; name each built
APK `FFTT04M-[date-time].apk` in the project root. In `strings.xml`, `%%` is a literal
`%` escape (not a bug). Playback uses raw PCM only.

## Build / install environment
- Local repo: H:\FFTT04M (Windows, PowerShell). User runs git, gradlew, adb here.
- Android Studio was updated to "Quail 1"; targetSdk 36; package com.example.FFTT04M.
- Test devices: Pixel 10 (modern, API 36) and Nexus 7 (API 23, Android 6).

### CRITICAL install gotcha
The Quail Studio update changed the signing key. Installing a new APK over the old one
fails with: `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. The install is
silently rejected and the device keeps running OLD code. ALWAYS uninstall first:
`adb uninstall com.example.FFTT04M` on BOTH devices before installing a fresh build.
Many "bugs" this session were actually stale APKs from this issue.

## Commits made via GitHub web editor this session (already on origin/main)
- `a9e39dc` Wavelet landscape crash fix — in layout-land/activity_wavelet.xml the
  `btnWaveletStop` stub was `<View>`; code does findViewById with an inferred Button cast
  that runs before the `?.` null-check, so landscape threw
  `ClassCastException: View cannot be cast to Button` at WaveletActivity.kt:586 (from
  onCreate:129). Fixed by changing the stub to a zero-size `<Button ... visibility="gone">`.
  NOTE: the landscape wavelet layout actually HAS all 23 control IDs (an earlier
  "missing controls" diagnosis was WRONG). Only the stub type was the defect.
- `a3429af` Legacy audio path (API < 26) — in MainActivity.kt `saveFragmentAudio()` added a
  guard at the top: if SDK_INT < O, call encodeWav() directly and return the .wav (return
  null on exception), skipping the FLAC attempt. >>> SEE REGRESSION BELOW — THIS IS SUSPECT. <<<
- `2291122` Hide Wavelet below API 26 — in ViewerActivity.kt the WAVELET button
  (R.id.btnViewerWavelet) is set to View.GONE when SDK_INT < O, else wired to launch
  WaveletActivity. Rationale: old devices can't run wavelet analysis fast enough.

API cutoff chosen for legacy behavior = 26 (Android 8.0 / VERSION_CODES.O). Nexus 7 (API 23)
is below the cutoff.

## OPEN REGRESSION — fix this first
After `a3429af`, the Nexus 7 now saves NOTHING — the Gallery is empty after a Save (it used
to save a file, just silent/garbled). Latest Nexus 7 logcat shows:
  `Started recording: rate=44100, enc=4, src=1`  (repeated, no crash, no save log line)
Interpretation:
- enc=4 = ENCODING_PCM_FLOAT, rate=44100. So the Nexus 7 does NOT fall back to 16kHz/16-bit
  at all — it opens a 44.1k FLOAT stream fine. The earlier "needs 16kHz fallback" premise was
  WRONG. Capture works.
- No exception is logged, but no file appears. The new legacy branch returns null silently on
  any encodeWav() failure, so the user gets nothing instead of the previous (present-but-bad)
  FLAC file.
Likely cause: the `a3429af` legacy WAV branch in saveFragmentAudio() either throws inside
encodeWav() on this device/buffer and returns null, or writes a file the Gallery's scan
doesn't pick up. Recommended action: in Claude Code, REVERT or repair `a3429af`:
  - Quick: revert a3429af to restore the FLAC->WAV fallback (at least files reappear).
  - Better: keep WAV-first but (a) don't swallow the failure — log e and still attempt the
    other path; (b) verify the saved file is registered the same way the FLAC path was so it
    shows in the Gallery; (c) confirm encodeWav() works on API 23 with the captured FloatArray.
  Then build, uninstall, install on Nexus 7, Save a clip, confirm it appears in Gallery and is
  audible. Capture logcat (tag FFTT04M) if it still fails.

## Audio architecture (so you don't re-derive it)
- Live mic -> 3-second circular buffer (audioCircularBuffer, FloatArray, size = sampleRate*3),
  filled in the recording thread read loop in MainActivity.kt (~lines 750-780). Both FLOAT and
  16-bit read branches exist and are correct (16-bit path divides by 32768f).
- A "fragment" = the user crops a region of the live spectrogram; saveFragment() (~241) maps
  the crop rect to indices in the circular buffer, copies them into a FloatArray, and calls
  saveFragmentAudio(dir, timestamp, data) (~278).
- saveFragmentAudio: tries encodeFlac() (~298) then falls back to encodeWav() (~357).
  encodeWav() is a correct 44-byte-header 16-bit PCM WAV writer and is reliable everywhere.
- KNOWN DEFECT (not yet fixed): encodeFlac() writes RAW MediaCodec FLAC output bytes straight
  to a .flac file with NO MediaMuxer and NO BUFFER_FLAG_CODEC_CONFIG handling — that is not a
  valid FLAC container. Lenient decoders (Pixel) tolerate it; stricter paths produce silence.
  Proper fix (do in Code where you can build/test): either wrap output in a MediaMuxer, or
  just make WAV the default for ALL devices and drop the broken FLAC writer. Keep encodeFlac in
  history for reference / revert.

## Enhance dialog model (CORRECT — not a bug)
The FFT "Enhance" dialog is intentionally: radio buttons to pick ONE engine
(None / Sweep+ / Constant-Q / Reassignment / Synchrosqueeze / Multitaper) PLUS checkboxes to
stack post-processors (Gaussian / Bilateral / TV Denoise / Butterworth). The old additive
"Sweep" was removed on purpose. Do not "fix" this into a single multi-select.

## OPEN UI ITEMS (from latest landscape + portrait screenshots)
1. BAND-LABEL CORRUPTION (highest-value cosmetic bug, both orientations):
   - EQ tab frequency labels render as "Hz Hz" then "Z Z Z" instead of "100 Hz / 300 Hz /
     1k Hz / 3k Hz / 8k Hz". Filter tab shows "%% e e" instead of "Filter % / Rise Time /
     Fall Time". The labels are being shrunk/wrapped to almost nothing.
   - Prime suspect: `applyAutoSizeText` in ViewUtils.kt over-shrinking these narrow-column
     TextViews until the text wraps to one glyph. REDESIGN_NOTES says slider VALUE labels and
     TabLayout subtrees are already in an auto-size skip list; the EQ/Filter COLUMN HEADER
     labels are apparently NOT skipped. Fix = add those header TextViews (the 100Hz/300Hz/...
     and Filter%/Rise/Fall labels) to the auto-size skip list, OR give them a fixed textSize
     and disable autosize for them. Verify in both portrait and landscape.
2. Top-bar buttons truncate to "GA... LIS... WA... NO... PL..." in landscape (and "GALL/LIST/
   WAV/NOTE/PLAY" in portrait). Acceptable but could be tuned; low priority. Landscape Wavelet
   top bar shows "COL..." truncated COLOR button — minor.
3. Wavelet landscape now WORKS (post a9e39dc): SETUP tab (FAM/BND/MODE spinners, THRESH SOFT,
   VIEW LOG/L-NORM, "safe FS" status) and SLIDERS tab (LVL/ORD/FS/THR) render fine. Confirm on
   device after the rebuild. Portrait Wavelet SLIDERS header labels are partly cut at the top
   (the "LVL ORD FS THR" row overlaps the slider tops slightly) — minor polish.
4. DISPLAY tab order/labels look good (Size / Step / ENHANCE:n / Blur:n / COLOR). No action
   unless user requests.

## UI_CHANGELIST.md is STALE
It still shows items 1.1/1.2/2.1-2.4/3.1 as unchecked (⬜) even though REDESIGN_NOTES confirms
they are done. Reconcile: mark completed items ✅. (Low priority, do when convenient.)

## Suggested order of work in Claude Code
1. Fix the Nexus 7 save regression (revert/repair a3429af). Build, uninstall, install, test.
2. Decide FLAC vs WAV-default properly (MediaMuxer or WAV-for-all) and implement+build+test.
3. Fix band-label corruption (ViewUtils.kt auto-size skip list). Build, screenshot both orientations.
4. Reconcile UI_CHANGELIST.md.
5. Minor landscape/portrait label polish as desired.
ALWAYS: build with gradlew before claiming done; uninstall on devices before installing;
commit + push each green change as its own commit; keep this HANDOFF.md updated at end of session.

---

- ✅ **Gallery Transfer Comment Sync Fix** (`HEAD`): Fixed a logic error in `GalleryTransfer.importBundle()` where `.txt` and `.png` files were skipped on modern devices if the audio recording was already present. Now, critical metadata (comments/thumbnails) always syncs, ensuring that edits made after a prior transfer are propagated correctly.

## COMPLETED IN THIS SESSION (2026-06-05) — verified end-to-end on Nexus 7 (API 23)

All items from "Suggested order of work" completed:

1. ✅ **Real root cause of "empty Gallery"**: GalleryActivity filtered to `.flac` only.
   When saves became WAV, recordings were written successfully but invisible to Gallery.
   - Fix: Gallery now lists `.wav` (and `.flac` legacy files) (`4fab9d7`)
   - Add WavReader.kt to parse raw PCM WAV (no MediaCodec decoder for audio/raw)
   - ViewerActivity.loadAndDecode + WaveletActivity.loadAndDecode branch on extension

2. ✅ **Audio format decision**: WAV-only (dropped invalid FLAC container)
   - MediaCodec FLAC encoder writes raw bytes with no proper framing
   - WAV (16-bit PCM) is reliable, proven everywhere (`a31505e`)

3. ✅ **Band-label corruption fixed**: Complete solution
   - Layout restructure: band labels moved to dedicated header row ABOVE sliders
   - Previously: narrow columns beside sliders, auto-sized and clipped to one glyph ("Hz Hz" → "Z Z Z")
   - Now: header row with 35% height allocation, dynamic sizing maximizes fonts
   - EQ labels: "100 Hz", "300 Hz", "1k Hz", "3k Hz", "8k Hz" — all readable
   - Filter labels: "Filter %", "Rise Time", "Fall Time" — all readable

4. ✅ **Portrait UI Layout improvements**
   - EQ/FILTER pages restructured: header row (35%) above sliders (65%)
   - Dynamic sizing for ALL labels: headers, value labels (dB/ms/%), top-bar buttons
   - Landscape layout unchanged (horizontal still works, readable but compact)

5. ✅ **UI_CHANGELIST.md**: Already fully reconciled

### End-to-End Flow Verified (Nexus 7, API 23)
Record → Freeze/Crop → Save (.wav + .png) → Gallery lists it → Open in Viewer →
Decode WAV → Render spectrogram with EQ/Filter/Display tabs → All labels readable →
PLAY audio via AudioTrack

### Commits in this session
- `a31505e` WAV-only audio format
- `4fab9d7` Gallery filter fix + WavReader + band-label fix (real root cause)
- `cb8cc87` Portrait layout restructure + dynamic label sizing

### SESSION 2 (2026-06-05 cont.) — Font/label overhaul, verified on BOTH devices
Devices: Nexus 7 (API 23, razor, native landscape) + Pixel 3a XL (API 32, bonito).
(The "Pixel 10" in older notes is actually this Pixel 3a XL.)

Central fix in ViewUtils.setMaxTextSizeToFit (`8b51e4b`, `893dbdb`):
1. Size text to the view's OWN allocated box when its dimensions are determinate
   (match_parent / 0dp+weight); only fall back to parent for wrap_content. Fixes
   Viewer-landscape EQ "Hz Hz Z Z Z" (label parent was the tall column → font ballooned
   → clipped) AND top-bar button truncation (sized to whole toolbar → ellipsis).
2. Subtract content padding so text fits the drawable area, not the raw box.
3. measureText (not getTextBounds) so Material-button letterSpacing is counted.
4. Pin maxLines to the \n line count so text never spills to an extra line / per-char wrap.

Layout/code (`5688c14`):
- MainActivity band labels: maxLines="2" (both orientations); dB value labels now
  dynamic (was fixed 8sp).

VERIFIED:
- Pixel 3a landscape MainActivity ("Listen"): band labels + "0 dB" readable. ✓
- Pixel 3a landscape Viewer ("FFT analysis"): EQ labels readable, top bar
  "GALLERY LISTEN WAVELET NOTE PLAY" full (no truncation). ✓
- Nexus 7 landscape Viewer: EQ labels readable, "GALLERY LISTEN NOTE PLAY" full. ✓

### Wavelet status (re-verified on Pixel 3a landscape this session)
The central font fix (`8b51e4b`) already resolved the previously-flagged Wavelet issues:
- SETUP "DWT · safe FS ≤ … kHz" status now renders in FULL (was truncated).
- SLIDERS header row "LVL ORD FS THR" renders cleanly (was clipped at top).
- Top bar "GALLERY LISTEN COLOR" full.
Remaining MINOR edge case (not yet fixed): the ORD slider's value number is clipped
when the thumb is at the slider MINIMUM (value label translated below the FrameLayout).
Same value-label-positioning logic as the EQ value labels. Low priority. Asked the user
which Wavelet fix they wanted (#4) but got no answer — left untouched to avoid regressing
a screen that now looks fine. Confirm intent next session.

### Next (optional)
- Wavelet ORD value-label clipping at slider minimum (see above) — needs careful
  updateLabelPosition clamp + on-device test.
- API downgrade analysis (deferred per user): can reach API 15 with no functional loss;
  TextViewCompat autosize already guarded for <26.
- dB value labels in Nexus 7 viewer sliders render small — could enlarge (cap is 14sp).
- Portrait Pixel 3a not re-screenshotted this session (only landscape); spot-check next time.

### API Downgrade Analysis (API 23 → 15)
**Current min: API 23 (Nexus 7)**

- API 22–16: AudioRecord, Material Slider, all key components work fine. No loss.
- API 15: Material Slider (requires 14+) still works. **TextViewCompat.setAutoSizeText()** requires API 26+ fallback (code has guards for < 26).
- **Bottom line**: Can drop to API 15 with no functional loss if Material library supports it. Core audio recording works on API 16+.

### Devices & Known Issues
- **Nexus 7 (API 23)**: ✅ Working end-to-end (record → save → gallery → viewer)
- **Pixel 10 (API 36)**: ✅ Working
- **Pixel 3 (API 29)**: ⚠️ **Label readability issues** (not currently attached; verify next session):
  - Listen tab: labels unreadable
  - FFT analysis landscape: all labels unreadable
  - Likely: parent dimensions wrong, or font sizing algorithm under-estimating needed size
  - Investigation needed: take landscape screenshot, compare with Nexus 7/Pixel 10

### UI Fixes Pending
1. **Wavelet UI**: Quick fixes needed (details TBD; check current state on Nexus 7)
2. **Slider value font logic**: Review edge cases (where values overflow or squeeze)
3. **Button/control font logic**: Review with attention to text overflows ("GA...", "LIS..." truncations)

### For Future Sessions (Different Computer, Same Account)
- Same git workflow: clone from origin/main, branch, commit, PR
- Build environment: Android Studio Quail 1, targetSdk 36, gradlew available
- Test devices: Nexus 7 (API 23, 7" tablet), Pixel 10 (API 36, modern phone), Pixel 3 (API 29, phone) — all attached via adb
- Critical install gotcha: ALWAYS `adb uninstall com.example.FFTT04M` BEFORE installing new APK (signing key mismatch from Studio update)
- Audio: uses raw PCM only (FloatArray), 44.1kHz, mono. WAV format (16-bit PCM header + samples) is the only save format now
- Key files to understand:
  - MainActivity.kt: live recording + crop → save
  - ViewerActivity.kt: load .wav/.flac → decode → display spectrogram + EQ/Filter/Display tabs
  - WaveletActivity.kt: load .wav/.flac → wavelet analysis
  - WavReader.kt: parses raw PCM WAV (no MediaCodec, no container specs)
  - ViewUtils.kt: applyAutoSizeText() walks layout tree, skips EQ/Filter headers; updateAllLabelPositions() dynamically sizes all labels
- Current state: portrait EQ/Filter working well (headers above sliders with dynamic sizing); landscape still side-by-side (readable but compact); Pixel 3 has label readability issues

---

## SESSION 3 (2026-06-06, new dev machine)

**Context:** User moved to new Windows dev machine with 6 USB debug devices (API 23–36, 720×1280 to 1080×2160). All devices had old APK signature from prior machine. User asked to continue UI improvements on new hardware.

**Critical limitation:** New dev machine has different APK signing key than old one. Devices retain old signatures. Most installs fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Pixel 3a XL verified before most devices hit the barrier.

### Fixes Applied

#### 1. Wavelet Control Height Inconsistency (RESOLVED)
**Observation:** Pixel 3a Wavelet top bar (GALLERY/LISTEN/COLOR) had visibly different heights.

**Root cause:** 
- Toolbar was `wrap_content` with `minHeight="48dp"` → children could center at different heights.
- Spinner rendering differs from Material buttons even at same declared height (40dp).

**Fixes:**
- **activity_wavelet.xml (portrait):**
  - Changed toolbar from `wrap_content` + `minHeight="48dp"` → fixed `height="48dp"`
  - Wrapped COLOR Spinner in FrameLayout to control its rendering
- **activity_wavelet.xml-land (landscape):**
  - Same FrameLayout wrapper for COLOR Spinner

**Verification:** Pixel screenshots before/after show buttons now evenly sized.

#### 2. Wavelet Landscape Slider Width Distribution (RESOLVED)
**Observation:** Landscape sliders left unused space on right (room for one more slider).

**Root cause:** `pageWaveletSliders` had `android:weightSum="5"` but only 4 slider columns with `layout_weight="1"` each → sliders got 4/5 ≈ 80% of width.

**Fix:** Changed `android:weightSum="5"` → `android:weightSum="4"` in landscape layout.

**Impact:** Sliders now fill full width; visual balance consistent with portrait.

#### 3. Samsung Tablet COLOR Spinner Sizing (DIAGNOSTIC)
**Report:** Galaxy Tab A (API 27, landscape) had COLOR spinner larger than GALLERY/LISTEN buttons.

**Fix Applied:** Same as #1 above (FrameLayout wrapper ensures consistent sizing).

**Verification Status:** Samsung Tab A dropped out of USB debug mid-session. Fix is structural (toolbar height control + Spinner containment) so confidence is high for next session when USB is restored.

### Commits in this session
- `5f14fd5` "Fix uneven Wavelet controls: fixed toolbar height and wrap Spinner in FrameLayout"
  - Toolbar 48dp, FrameLayout wrappers, landscape weightSum fix

### Test Coverage (This Session)
- **Pixel 3a XL (API 32, portrait):** ✓ Top buttons verified evenly sized
- **Galaxy J7 (API 25, landscape):** Install attempted, old signature key blocked re-verification
- **Galaxy Tab A (API 27, landscape):** USB debug dropped; fix applied structurally
- **Others (Nexus 7, CP81, T65):** Old signature keys pending resolution

### Remaining Known Issues

1. **Wavelet ORD slider value clipping at minimum** (deferred from prior session)
   - Minor: numeric value ("1") clips below FrameLayout when thumb at minimum
   - Same `updateLabelPosition` logic as EQ sliders
   - Deferred: low priority, already flagged

2. **APK Signing Key Mismatch**
   - New machine signing key differs from old machine
   - Most devices fail install with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
   - Resolution: either sync key or ask user for key file

### Next Session Checklist
1. **Resolve signing key:** Ask user for old key or re-provision devices
2. **Re-verify on all 6 devices:** Landscape controls (especially COLOR button), slider widths
3. **Wavelet ORD value clip:** Fix if time permits (low priority)
4. **Full portrait sweep on Pixel 3a:** Not done this session (only landscape top buttons)
5. **API downgrade analysis:** Deferred from prior session, still pending

### Build Status
- APK: `app-debug.apk` builds clean
- Git: Commits on main, in sync with origin/main
- Gradle: Configuration cache reused, builds in ~3–5 seconds

---

## FEATURE ROADMAP (assessed 2026-06-06, grounded in the actual codebase)

Realism ratings reflect *this* codebase, not generic difficulty. Read the "already exists"
notes carefully — two requested features are partly built already.

### Architecture facts that drive these estimates
- **Colour maps are now centralised** in `ColorMaps.kt` (256-entry LUTs, append-only index
  order, shared by `FFTHeatMapView` + `WaveletView`). Adding a scheme = add one anchor-stop
  array; every call site (spinners, Wavelet COLOR dialog, control theming) picks it up via
  `ColorMaps.names` / `.count` / `.lut()`. **DONE this session.**
- **The CWT engine is already a complex Morlet.** `WaveletActivity.runCwt()` convolves in the
  frequency domain with `exp(-0.5·(scale·ω − w0)²)`, `w0 = 6.0`, separate Re/Im buffers,
  100 scales, FFT-based, off-UI-thread with progress + stop. A second Morlet (Constant-Q)
  lives in `ViewerActivity` (~line 1167). So "Complex Morlet for pitch tracking" = expose the
  existing engine, optionally make `w0`/cycles tunable.
- **DWT filter banks** live in `WaveletActivity.filterMap` (Daubechies db2/4/6, Symlets
  sym2/4/6, Coiflets coif1/2). Higher orders = pure published-coefficient data entry.
- **The Enhance dialog** (`ViewerActivity.showEnhanceDialog`) is the proven pattern for
  one-exclusive-engine + stacked-post-processors. New enhancement filters slot in as either a
  radio engine or a checkbox post-processor; `applyEnhancements()` is the single hook.

### Tier 1 — trivial, do anytime (data/wiring only)
1. **Higher wavelet orders** (db8/db10, sym8, coif3–5): add coefficient arrays to `filterMap`.
   `updateOrderSliderRange()` already adapts the ORDER slider to the family's available keys,
   so the UI auto-expands. ~½ day, no new algorithms. **Realism 10/10.**
2. **Label the existing Morlet** as a selectable CWT family; optionally add a "cycles" (`w0`)
   control. ~½ day. **Realism 9/10.**

### Tier 2 — easy-moderate (one new kernel/function each)
3. **Mexican Hat (Ricker) CWT**: same `runCwt` loop, swap kernel to `ω²·exp(−ω²/2)`. Inherits
   the 60k cap, threading, progress, stop. ~1 day. **Realism 8/10.**
4. **Anisotropic Diffusion (Perona–Malik)** as the first new enhancement: iterative,
   edge-preserving; TV-Denoise already ships (same family) so there's reusable plumbing.
   ~1–2 days. **Realism 8/10.** Best ratio of payoff to risk among the "squiggle" filters.
5. **Gabor filter bank** (oriented ridge enhancement): N oriented 2D convolutions, combined.
   Bounded cost. ~2 days. **Realism 6/10.**

### Tier 3 — moderate-hard (correctness + performance risk)
6. **Frangi vesselness** (multi-scale Hessian eigen-analysis): genuinely suits spectral
   ridges, but heaviest-but-one and fiddly to tune. ~3–4 days. **Realism 5/10.**
7. **Ridge skeletonization**: thinning (Zhang–Suen) is moderate (~2 days). NOTE: "track
   instantaneous frequency" is a SEPARATE, larger feature (connected-component linking +
   path extraction) — do not bundle it into the checkbox. **Realism 5/10 (image op) / 3/10
   (full tracking).**
8. **Non-Local Means denoising**: naive NLM is minutes on a Nexus 7. Needs integral-image
   optimisation or downscaling; can smear genuinely-distinct harmonics. Lowest priority.
   **Realism 3/10.**

### Cross-cutting prerequisite for Tier 2–3
Decide the grid the enhancement filters run on. The full CWT coefficient buffer is up to
**100 scales × 60k samples ≈ 6M cells** — too slow on the two oldest devices. Run filters on
the **display-resolution** grid (what's actually drawn), or downsample first. This single
decision determines whether these are usable on legacy hardware.

### Optional one-time polish
- The 256-LUT anchors approximate matplotlib. For exact CVD fidelity (Cividis's whole point),
  paste the full 256-sample table into `ColorMaps.anchors` later — no call sites change.

---

## LEGACY-DEVICE GATING STRATEGY (how to hide high-demand features on old hardware)

Test fleet spans **API 23 → 36** and **720×1280 → 1080×2160**, including a Nexus 7 (2012) and
Galaxy J7. Several roadmap items are too heavy for the oldest devices. Recommended approach:

### 1. Reuse the existing capability cutoff
There is already a precedent: the WAVELET button is hidden below API 26 in `ViewerActivity`
(`R.id.btnViewerWavelet → View.GONE when SDK_INT < O`). Generalise this rather than scatter
new `if` checks.

### 2. Add a single `DeviceTier` helper (recommended)
Create `DeviceCaps.kt`:
```kotlin
object DeviceCaps {
    // Tier by a blend of API level and RAM; cache once.
    fun tier(ctx: Context): Int {
        val mem = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .let { ActivityManager.MemoryInfo().apply { it.getMemoryInfo(this) } }
        val lowRam = mem.totalMem < 2L * 1024 * 1024 * 1024   // < 2 GB
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || lowRam -> 0  // legacy
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> 1            // mid
            else -> 2                                                     // modern
        }
    }
    val heavyEnhancementsAllowed get() = { c: Context -> tier(c) >= 1 }
    val cwtAllowed              get() = { c: Context -> tier(c) >= 1 }
}
```

### 3. Gate at three levels (cheapest → most graceful)
- **Hide the control** (current WAVELET pattern): set the radio row / checkbox / button to
  `GONE` on tier 0. Simplest; the user never sees the heavy option. Good for NLM, Frangi.
- **Disable + annotate**: keep the row visible but `isEnabled = false` with a "(needs newer
  device)" suffix, so users understand the feature exists. Better UX than silent hiding.
- **Auto-degrade the parameters**: keep the feature but cap cost on tier 0 — e.g. fewer CWT
  scales (50 not 100), fewer diffusion iterations, run on a downsampled grid, lower max
  decomposition level. This is what `applyFailsafe()` / the 60k CWT cap already do; extend the
  same idea per-feature.

### 4. Concrete gating recommendations per roadmap item
| Feature | Tier 0 (legacy) | Tier 1 (mid) | Tier 2 (modern) |
|---|---|---|---|
| New colour maps | ✅ all (cheap LUT) | ✅ | ✅ |
| Higher wavelet orders | ✅ | ✅ | ✅ |
| Mexican Hat / Morlet CWT | ⚠ degrade: 50 scales | ✅ | ✅ |
| Anisotropic Diffusion | ⚠ fewer iters + downsampled grid | ✅ | ✅ |
| Gabor bank | ❌ hide | ✅ | ✅ |
| Frangi vesselness | ❌ hide | ⚠ downsampled | ✅ |
| Ridge skeleton / tracking | ❌ hide | ⚠ | ✅ |
| Non-Local Means | ❌ hide | ⚠ downsampled | ✅ |

### 5. Always log silent caps
If a feature silently degrades on a device (fewer scales, downsampled grid), surface it — a
small Toast or a "(eased)" suffix on the control — so behaviour differences across the fleet
aren't mistaken for bugs. The current `updateSafetyStatus()` "⚠ … will auto-ease" text is the
right precedent to follow.

---

## SESSION 3 cont. — Colour overhaul + Wavelet defaults (2026-06-06)

### Wavelet analysis defaults (per-recording)
New recordings default to: **CWT, level 8, order 10, threshold 0 (off), 44.1 kHz**. Stored in
the per-recording `rec_<name>` prefs namespace (shared with the FFT Viewer), so user overrides
persist per recording. Rationale: CWT + max level/order gives the richest detail; zero
threshold hides nothing; max safe FS uses full bandwidth.

### Colour system (see FEATURE ROADMAP for details)
- `ColorMaps.kt`: 8 schemes as 256-entry LUTs (Default/Viridis/Magma/Gray + Inferno/Plasma/
  Turbo/Cividis). Append-only indices keep old `color_scheme` prefs valid.
- Both heat-map views share it; duplicate palette arrays removed.
- Wavelet COLOR is now a **button → radio dialog** (Enhance-style), not a spinner — fixes the
  long-running spinner-sizing problem on Pixel/T65/CP81 by eliminating the Spinner entirely.

### Launcher icon
Added `mipmap-anydpi-v21/` layer-list icons so API 21–25 devices show the custom icon instead
of the stock-green fallback (adaptive icons are v26+ only).


---

## SESSION 4 (2026-06-07) — Roadmap execution begins (easiest-first, independent)

### Shipped
- **Colour picker = tap-to-apply.** Removed Apply/Cancel from the shared
  `showColorSchemeDialog`; tapping a gradient row applies + dismisses immediately, current
  scheme marked ✓. Affects Listen, FFT, Wavelet (shared dialog).
- **Anisotropic Diffusion (Perona–Malik)** — FFT-viewer post-processor (enhance index 9).
  Edge-preserving: smooths along ridges, keeps cross-edges sharp. Formula-based, no tables.
  Light enough to run on all tiers.
- **DeviceCaps.kt** — the tier helper from the gating plan is now REAL (tier 0/1/2 by
  API + RAM, `heavyEnhancementsAllowed()`). Roadmap section above is now implemented, not
  just proposed.
- **Gabor ridge bank** — FFT-viewer post-processor (enhance index 10). Zero-mean even Gabor
  kernels × 4 orientations; per-pixel MAX response boosts oriented "squiggle" energy. HEAVY,
  so it is **gated**: Enhance dialog disables + annotates it "(needs newer device)" on tier 0
  (Nexus 7 / J7), and `applyEnhancements` skips it there even with a stale mask bit.

All enhancement post-processors are opt-in (default off), appended at the end of
`enhanceModeNames` so saved `enhance_mask_v3` values stay valid. The Enhance dialog
auto-generates each new checkbox.

### Roadmap status now
- Tier 1 #colour-maps: ✅ (prior session). Higher wavelet orders: NOT done — needs exact
  published coefficients (db8/db10/sym8/coif3–5); deferred because transcribing 16–30-tap
  filters from memory risks silently corrupting analysis. Do with a verified table + a
  sum-of-squares==1 runtime self-check.
- Tier 2: Anisotropic ✅, Gabor ✅ (gated). Mexican-Hat CWT: still pending — needs a
  CWT-kernel selector; the family spinner is DWT-coupled, so add a small CWT-only control
  (a checkbox in SETUP) rather than overloading the family spinner. The CWT engine is already
  Morlet (`runCwt`, w0=6.0), so Mexican Hat is just an alternate kernel `(sω)²·exp(−(sω)²/2)`.
- Tier 3 (Frangi, Ridge skeleton, NLM): pending; gate all to tier ≥ 1 (NLM tier 2 only).

### Next easiest-first
1. Mexican-Hat CWT kernel + a small CWT-wavelet selector (formula-safe, high value for ridges).
2. Frangi vesselness as a gated post-processor (heaviest-but-one; tier ≥ 1).
3. Higher wavelet orders once a trusted coefficient table is available.

### Verify on-device
FFT viewer → DISPLAY → ENHANCE: "Anisotropic" (all devices) and "Gabor ridges" (greyed on
Nexus 7 / J7, active on CP81 / T65 / Pixel). Colour picker: one tap applies + closes.

### Session 4 update — Frangi shipped
- **Frangi vesselness** — FFT post-processor (enhance index 11), gated to tier ≥ 1
  (`heavyEnhanceModes = {10,11}`). Multi-scale (σ 1/2/3) Hessian-eigenvalue vesselness; exact
  2×2 symmetric eigenvalues; added a separable `gaussianBlur(src, sigma)` helper for scale-space.
  Greyed "(needs newer device)" on Nexus 7 / J7.

### Roadmap remaining (the hard, lower-value tail)
- **Ridge skeletonization** (Zhang–Suen thinning): image-op only, 5/10. "Instantaneous-frequency
  tracking" is a SEPARATE, larger feature (CC linking + path extraction) — don't bundle.
- **Non-Local Means**: 3/10. Naive NLM is minutes on legacy; needs integral-image optimisation
  or downscaling, and can smear genuinely-distinct harmonics. Gate to tier 2 only if done.
- **Higher wavelet orders** (db8/db10, sym8, coif3–5): still deferred pending a TRUSTED
  coefficient table + a sum-of-squares==1 runtime self-check (transcription errors silently
  corrupt analysis).

Recommendation: the high-value ridge/denoise enhancements (Anisotropic, Gabor, Frangi) and
Mexican-Hat CWT are all done. The remaining three are the risky/low-value tail — worth a
deliberate go/no-go rather than shipping blind.

### Tuning knobs (if on-device review wants adjustment)
- Gabor: `gain` 1.2, `sigma` 1.8, `lambda` 4.0, 4 orientations (enhGabor).
- Frangi: `gain` 0.8, scales {1,2,3}, β 0.5 (enhFrangi).
- Mexican-Hat: shares the CWT scale range (`maxScale = 2^(level+3)`); switch via SETUP→CWT.
- Anisotropic: `lambda` 0.20, `k` 0.08, 8 iterations (enhAnisotropic).

---

## SESSION 5 (2026-06-07) — Colour reorg, persistence, Wavelet FAMILY merge, user manual

### Colour
- **Turbo is the new default**; the old crude 7-colour "Default/Heat" map (a rough Turbo)
  was removed. New order: 0 Turbo 1 Viridis 2 Magma 3 Inferno 4 Plasma 5 Cividis 6 Gray.
- One-time **v1→v2 index migration** in ColorMaps keeps saved `color_scheme` prefs valid
  (`REMAP_V1_TO_V2`, gated by `color_scheme_ver`).
- **Persistence centralised** in ColorMaps:
  - `loadGlobal/saveGlobal` → Listen (app_settings, across sessions).
  - `loadForRecording/saveForRecording` → analysis (per-recording, falls back to global on
    first open, and also writes global so the choice carries across screens).
  All three screens now call these instead of reading `color_scheme` directly.

### Wavelet SETUP
- Row order is now **MODE, FAMILY, BND** (was FAM, BND, MODE).
- **FAMILY is mode-aware** (`refreshFamilySpinner`): CWT → Morlet/Mexican Hat; DWT/WPT/RECON
  → Daubechies/Symlet/Coiflet. The separate CWT-wavelet spinner was removed/merged. Label is
  the full word "FAMILY". `updateUIFromSettings` routes through the helper so failsafe resets
  stay mode-correct.

### User manual
- `app/src/main/assets/user_manual.md` — draft manual (all screens, modes, colours,
  enhancements). Bundled in the APK AND visible in the repo. **Keep it updated with features.**
- `ManualActivity` renders it (lightweight Markdown→HTML: headings, bold/italic, bullets,
  rules, simple tables) in a scrollable dark TextView. Registered in the manifest.
- **Gallery → HELP** button (portrait + landscape) opens it.

### Gotcha fixed during this session
- A `*/` inside a KDoc comment ("load*/save*") prematurely closed the block comment in
  ColorMaps.kt → cascade of "Expecting a top level declaration". Avoid literal `*/` in
  comments.

### Verify on-device
- Colour: default recordings/Listen now show **Turbo**. Pick a scheme in Listen → open a new
  recording → it inherits that scheme; each recording then remembers its own.
- Wavelet SETUP: MODE on top; switching to/from CWT swaps FAMILY between Morlet/Mexican Hat
  and the DWT families.
- Gallery → HELP → manual renders and scrolls.

---

## SESSION 6 (2026-06-07) — Higher-order wavelets, versioning, device-to-device transfer

### Versioning
- App version is now **`2.<yyMMdd.HHmm>`** (build timestamp); `versionCode` = minutes since
  epoch (monotonic, fits Int). Computed in `app/build.gradle.kts` (needs `import java.util.Locale`).
  First build: `2.260607.0750`.

### Higher-order wavelets (DONE — were deferred)
- Added **db8, db10, sym8, coif3, coif4, coif5** to `WaveletActivity.filterMap`.
- Generated with **PyWavelets 1.8.0** (installed on the dev machine) in the code's `rec_lo`
  convention, validated: Σh=√2, Σh²=1, double-shift orthogonality ≤1e-13. To regenerate:
  `python -c "import pywt; print(pywt.Wavelet('db8').rec_lo)"`.
- `validateFilterBanks()` runtime self-check warns in logcat on any invalid filter.
- ORDER slider now **snaps to the nearest available order** per family (orders are
  non-contiguous: db/sym are even-only).

### Device-to-device gallery transfer (NEW — QR + Wi-Fi)
- **GalleryTransfer**: ZIP "bundle" = per recording `.wav` (+ `.png`/`.txt`) + `<base>.json`
  of its `rec_` prefs + `manifest.json`. Streams both ways (low-RAM safe). Import de-dupes
  collisions (`_imp` suffix) across the whole recording incl. prefs namespace. Float/Bool/String
  keys restored via explicit key-type sets (`FLOAT_KEYS`/`BOOL_KEYS`/`STRING_KEYS`) — **add new
  non-Int per-recording keys there** or they'll be restored as Int.
- **ImportActivity** (receiver): TCP `ServerSocket` on the Wi-Fi LAN, shows a QR
  `FFTT1:ip:port:token`; on connect+token it imports the streamed bundle.
- **GalleryActivity SHARE** → Send (ZXing scan → connect → stream bundle) or Receive
  (launch ImportActivity).
- Dependency: `com.journeyapps:zxing-android-embedded:4.3.0` (pure Java, no Play Services).
  Permissions: INTERNET + CAMERA (camera `required=false`). ImportActivity registered.
- **Transport choice (per user): QR-handshake direct, not SAF/Sharesheet.** Requires both
  devices on the same Wi-Fi LAN. NFC was rejected (bandwidth; Android Beam removed in API 29+;
  no NFC radio on some fleet devices).

### Manual
- `user_manual.md` updated with a "Sharing recordings between devices" section + the SHARE button.

### Can't self-test
The actual transfer needs two devices on one Wi-Fi + a camera scan — that's a user test. I
verified it builds, installs, and the dependency resolves; UI (SHARE dialog, QR render, scanner
launch) should be checked on-device.

### Wishlist status
Per user: NLM, ridge skeletonization, and instantaneous-frequency tracking are **dropped**
("no need for the first 3"). Higher-order wavelets: **done**. Enhancement filters considered
complete (Anisotropic/Gabor/Frangi cover the space).

### testing notes:
1. share, send to another device, 6 items in Gallery, app response "no recordings to send"
2. the label "FFT Gallery" overflows. Put the grid/list switch under the Listen button and shorten to "GALLERY"
3. top system info bar (time, battery, etc) is hidden. can we make it visible?
4. in listen, equalizer attenuates recording volume. I want to have it affect only the FFT waterfall display; the recording should be raw mic feed unaffected by equalizer or filter. Audio playback should also be the raw recording.
5. tell me what you think about this idea: put a "PROCESSED PLAYBACK" button in the DISPLAY tab under the COLOR control; have it look at the data array you used to render the display, run a reverse FFT on it (effectively regenerating an audio file) and play that back. (it may produce unexpected results in reassignment and synchrosqueeze modes but would like to play with taht too.) (no neeed to do that in wavelet right now but tell me if you think that would be workable.)
6. I think even on smaller screens there is room for another control in DISPLAY tab? I would like to take vertical lines out of BLUR and give them their own control, a multi-pick (like enhancement stack) that includes: 2dp thick vertical lines every second, 1dp lines every 100 msec, and CLEAR if none.
7. 

---

## FUTURE VISION — DO NOT TOUCH UNTIL PROMPTED

> This section is a forward-looking roadmap only. **Do not design, implement, or modify anything
> here unless the user explicitly asks.** It exists so the intent isn't lost.

### Context
The current push for downward compatibility (API 23 / Nexus 7 / AOSP tablets) is **deliberate
stress-testing** — exercising old/low-end hardware to surface weak points. The product will
**ship to more modern devices for beta testing**; legacy support is a robustness exercise, not
the shipping target.

### Possible future "fully modern" edition
A later, modern-only version may raise the analysis ceiling well beyond today's limits:
- **96 kHz** sample rate (vs current 44.1 kHz).
- **FFT size up to 8192** (vs current smaller sizes).
- **High-fidelity FLAC** capture (vs the current 16-bit PCM WAV; note the old FLAC container
  attempt was abandoned — a proper modern encoder/muxer would be needed).
- **Automatic recording capture** (hands-free triggering, vs today's manual freeze-and-crop).

### Domain (the actual subject)
What the app captures and maps is **cough**. Plausible future functionality:
- **Auto-capture of cough-like events** (detect and record sounds resembling a cough).
- **Speech rejection** (suppress/ignore speech so it isn't captured or mistaken for a cough).

These are research-grade features (event detection + classification) and would pair naturally
with the higher sample rate / FFT size and auto-capture above.
