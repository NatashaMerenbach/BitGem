# Color Breakdown (BitGem)

An Android app showing a live CameraX preview next to a panel with the **five most common
colors in the current frame** — each with its percentage share and RGB values. Each card is
painted in the color it describes, with auto-contrasting text.

Color analysis is **hand-written**: binning + weighted k-means, no OpenCV, no ML Kit, no Palette
API, no built-in quantization. Only CameraX (camera), Compose (UI), Hilt (DI) and coroutines.

---

## Screens

```
┌──────────────────────────────┐
│        live camera       ┌───┴──────┐
│        preview           │ Color    │  ← heading (Hebrew on an `iw` device)
│        (full screen)     │ Breakdown│
│                          │ ┌──────┐ │
│                          │ │ 8.24%│ │  ← swatch filled with the color,
│                          │ └──────┘ │     percentage auto-contrasted
│                          │ R:116    │  ← RGB below the swatch
│                          │ G:114    │
│                          │ B:94     │
│                          │   ...    │  ← 5 rows, sorted descending
└──────────────────────────┴──────────┘
   opaque panel, trailing ~30% of the screen
```

Updates ~10x/second, smoothed across frames. Percentages are real shares of the sampled pixels
(sum to 100) — the mockup's numbers are illustrative only.

---

## Requirements

| | |
|---|---|
| Android Studio | Ladybug (2024.2)+ |
| JDK | 17 |
| AGP / Kotlin | 8.9.2 / 2.0.21 |
| CameraX | 1.4.1 |
| Hilt | 2.52 (KSP) |
| `compileSdk` / `targetSdk` | 35 |
| **`minSdk`** | **24** — CameraX 1.4's floor; the YUV_420_888 path is reliable from API 24 up |

### Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.any" android:required="true" />
```

`CAMERA` only, requested at first launch with an in-app rationale; denial shows an explanation
and a retry path instead of a black screen. No storage or network permission — frames never
leave the device.

---

## How to run

```bash
./gradlew installDebug     # build + install on a connected device/emulator
./gradlew assembleDebug    # just the APK -> app/build/outputs/apk/debug/
```

Or open in Android Studio and press ▶. A physical device is recommended (real sensor noise is
what the temporal smoothing is for); the emulator's virtual-scene camera also works.

### Tests

```bash
./gradlew testDebugUnitTest        # 58 JVM tests, 0 failures — no device needed
./gradlew check                    # + Android Lint (0 errors) + the layer-boundary check
./gradlew connectedDebugAndroidTest  # 6 instrumented Compose UI tests — needs a device/emulator
```

### Debugging on a device

Failures are logged (with frame geometry, throttled to one per burst) even though the UI shows
only a fixed string: (Samsung Galaxy A72, Android 16)

```bash
adb logcat -s ColorCam.Analysis ColorCam.Camera
```

---

## Architecture

UI → domain ← data, as layered **packages** inside a single `:app` module:

```
:app  (com.bitgem.colorcam)
  ├── ui/       Compose UI — CameraRoute (stateful) → CameraScreen (pure) → CameraPreview
  │             viewmodel/ CameraViewModel; components/ ColorsPanel, ErrorMessage; theme/
  ├── data/     CameraX/ImageProxy specific + Hilt wiring
  │             camera/ ImageToYuv420Frame; repository/ ColorRepositoryImpl (also the
  │             CameraX ImageAnalysis.Analyzer); di/ AnalysisModule, RepositoryModule,
  │             CameraModule, CameraAnalysisEntryPoint
  └── domain/   Pure Kotlin — no Android, no CameraX, no Compose
              model/ RgbColor, ColorResult, FrameData, Yuv420Frame
              analysis/ Yuv420Converter (converts *and* samples), RgbHistogram,
                        ColorQuantizer, ColorSmoother, ColorMath
              repository/ ColorRepository (interface)
              usecases/ ObserveTopColorsUseCase, ObserveErrorsUseCase
```

The boundary is enforced by the build, not just convention: `./gradlew check` runs
`checkLayerBoundaries`, which fails if `domain/` imports Android/`data/`/`ui/`, or `data/`
imports `ui/`. The one documented exception is `CameraAnalysisEntryPoint`: `CameraRoute` reads
the CameraX analyzer and its executor from the Hilt graph through it, so `CameraViewModel`'s
public surface stays state-plus-callbacks and names no framework type. (Why not a real module
split, and the trigger for revisiting one — more than one team in the repo — is in
PROCESS.md §2.1.)

Hilt wires everything: `@HiltAndroidApp`, `@HiltViewModel`, and `AnalysisModule` /
`RepositoryModule` / `CameraModule` / `CameraAnalysisEntryPoint` in `data/di`.

### Data flow

```
CameraX ─ImageProxy(YUV_420_888)─▶ ColorRepositoryImpl.analyze()  [single background thread]
                                        │ ImageToYuv420Frame → Yuv420Frame (buffers + strides)
                                        │ converter samples + converts: every 4th pixel in x/y
                                        │   (1/16) — YUV math only runs on pixels that get binned
                                        │ throttle: ≤1 analysis / 100 ms
                                        │ histogram → 32³ bins, epoch-stamped, reused
                                        │ weighted k-means (k=6, fixed seed) over bins
                                        │ merge colors within 24 RGB units
                                        │ ColorSmoother (EMA α=0.35) → top 5
                                        ▼
                          MutableStateFlow<List<ColorResult>> (conflated)
                                        ▼
                ObserveTopColorsUseCase → CameraViewModel.uiState → CameraScreen → ColorsPanel
```

Nothing runs on the main thread; composables only ever receive `List<ColorResult>`.

**The algorithm, briefly:** sample on a regular grid while converting YUV→RGB → bin into a
coarse RGB histogram (5 bits/channel, each bin keeping population + mean color) → weighted
k-means over the non-empty bins (k-means++ seeding, fixed seed, empty clusters reseeded) →
greedily merge clusters within 24 RGB units → EMA-smooth against the previous frame → top 5.
`k = 6` (not 5) so merging can't starve the panel. Full rationale in `PROCESS.md` §3.

---

## Project layout

```
BitGem/
├── app/src/main/java/com/bitgem/colorcam/   domain/, data/, ui/ (see Architecture)
├── app/src/test/                            58 JVM unit tests
├── app/src/androidTest/                     Compose UI + permission-gate tests
├── gradle/libs.versions.toml                Version catalog
├── PROCESS.md                               Decisions, difficulties, verification
└── README.md
```

## Design constraints honoured

* **No ready-made image/color library** — YUV→RGB, histogram, clustering and contrast are all
  hand-written (`domain/analysis/`).
* **No business logic in the ViewModel** — combines flows into state, forwards events, nothing else.
* **No pixel/analysis logic in composables** — pure functions of `ColorAnalysisUiState`.
* **No hard-coded UI strings** — all in `res/values/strings.xml`.
* **Background execution** — dedicated single-thread `Executor` for analysis;
  `STRATEGY_KEEP_ONLY_LATEST` + a 100 ms throttle keep it off the camera's critical path.

## Conformance with the brief

Every explicit ask is met as specified — Flow-based `ColorRepository`, `ColorRepositoryImpl` as
both the repository and the CameraX analyzer, `@Binds`/`@Provides` DI, background analysis,
a state-only `CameraViewModel`, pure-function UI, hand-written clustering, and layered packages
with the boundary enforced by the build (`checkLayerBoundaries`) — with one deliberate deviation:

**No request/response use case.** The brief also allows a use case that takes `FrameData` and
returns `List<ColorResult>` directly. This ships only the push-based
`ObserveTopColorsUseCase(): Flow<List<ColorResult>>`, since the live camera is the app's only
frame producer; the request/response pair existed (with tests) and was deleted once nothing
called it. Re-adding it is a ~20-line change — `ColorQuantizer.quantize(frame, topColorCount)`
is already request/response underneath. Details and the one constraint it would need to respect
(no inherited temporal-smoother state) are in PROCESS.md §2.3.