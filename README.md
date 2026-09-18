# Color Breakdown (BitGem)

An Android app that shows a live CameraX preview next to a continuously updating panel with the
**five most common colours in the current frame** — each with the percentage of pixels it covers
and its RGB values. Each card is painted in the colour it describes, and the text on it flips
between black and white automatically to keep enough contrast.

The colour analysis is **hand-written** — binning + weighted k-means, no OpenCV, no ML Kit, no
Palette API, no `ScriptIntrinsicYuvToRGB`, no built-in quantisation. The only libraries involved are
CameraX (camera plumbing), Compose (UI), Hilt (DI) and coroutines.

---

## Screens

```
┌──────────────────────────────┐
│                              │
│                              │
│        live camera       ┌───┴──────┐
│        preview           │ חלוקת    │  ← heading (Hebrew on an `iw` device,
│        (fills the        │ צבעים    │     "Color Breakdown" otherwise)
│         whole screen)    │ ┌──────┐ │
│                          │ │ 8.24%│ │  ← rounded swatch filled with that row's
│                          │ └──────┘ │     colour, percentage auto-contrasted
│                          │ R:116    │  ← RGB line below the swatch, in white
│                          │ G:114    │
│                          │ B:94     │
│                          │   ...    │  ← 5 rows, sorted by percentage descending
└──────────────────────────┴──────────┘
        the panel is an opaque overlay on the trailing ~30%,
        with a hard vertical edge over the preview
```

Updates ~10x per second (throttled), smoothed across frames so the numbers do not twitch.

Colours are reported with two decimals (`8.24%`) and as `R:116 G:114 B:94`, matching the reference
UI. The percentages are real shares of the sampled pixels, so they sum to 100 — the values in the
mockup sum to ~20% and are illustrative only.

---

## Requirements

| | |
|---|---|
| Android Studio | Ladybug (2024.2) or newer |
| JDK | 17 |
| Gradle | 8.11.1 (via the wrapper) |
| AGP / Kotlin | 8.9.2 / 2.0.21 |
| Compose BOM | 2024.10.01 |
| CameraX | 1.4.1 |
| Hilt | 2.52 (KSP) |
| `compileSdk` / `targetSdk` | 35 |
| **`minSdk`** | **24** (Android 7.0) — CameraX 1.4's floor |

`minSdk 24` matters for one implementation detail: `ImageAnalysis` is available from CameraX
1.0, but `ResolutionSelector`-style configuration and `ImageProxy` guarantees are much more
consistent from API 21+ with Camera2; on 24+ the YUV_420_888 path is reliable on essentially every
device in the wild.

### Permissions

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.any" android:required="true" />
```

* **`CAMERA`** — runtime permission, requested on first launch with an in-app rationale screen.
  If it is denied, the app explains why and offers a retry button instead of showing a black
  preview.
* Nothing else. **No storage permission** and no network permission: frames are analysed in memory
  and never written or uploaded.

---

## How to run

```bash
# Debug build + install on a connected device/emulator
./gradlew installDebug

# Or just build the APK
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and press ▶. The first Gradle sync downloads AGP, KSP, Hilt
and CameraX.

A physical device is recommended (a real sensor produces real noise, which is exactly what the
temporal smoothing exists for); the emulator's virtual-scene camera works too.

### Tests

```bash
# All JVM unit tests, no device needed — the interesting ones.
# Verified: 56 tests, 0 failures (31 analysis / 9 camera / 9 model / 5 repository / 2 usecases)
./gradlew testDebugUnitTest

# Instrumented Compose UI tests (needs a device/emulator) — NOT yet executed here
./gradlew connectedDebugAndroidTest
```

---

## Architecture

Google's recommended app architecture (UI → domain → data), as layered **packages** inside a single
`:app` module:

```
:app  (com.bitgem.colorcam)
  ├── ui/                  Compose UI — presentation only
  │   ├── screens/         CameraRoute → CameraScreen → CameraPreview
  │   │                    Route: stateful, owns the ViewModel + permission launcher
  │   │                    Screen: pure function of ColorAnalysisUiState
  │   │                    Preview: the AndroidView/PreviewView CameraX binding
  │   ├── viewmodel/       CameraViewModel (@HiltViewModel), ColorAnalysisUiState, CameraError
  │   ├── components/      ColorsPanel (+ ColorRow), ErrorMessage, CameraPermissionRequest
  │   └── theme/           ColorCamTheme, colours, typography
  ├── data/                Everything CameraX/ImageProxy specific + Hilt wiring
  │   ├── camera/          ImageToYuv420Frame (ImageProxy → domain Yuv420Frame)
  │   │                    ElapsedTimeSource
  │   ├── repository/      ColorRepositoryImpl  ← implements domain ColorRepository
  │   │                                            AND CameraX ImageAnalysis.Analyzer
  │   └── di/              AnalysisModule, RepositoryModule, CameraModule, @AnalysisExecutor
  └── domain/              Pure Kotlin: no Android, no CameraX, no Compose
      ├── model/           RgbColor, ColorResult, FrameData, Yuv420Frame
      ├── analysis/        Yuv420Converter, PixelSampler, RgbHistogram,
      │                    KMeansColorQuantizer, ColorSmoother, ColorMath
      ├── repository/      ColorRepository (interface)
      └── usecases/        ObserveTopColorsUseCase, ObserveErrorsUseCase
```

The dependency rule is `ui → domain ← data`, and it is stated here as something you can check rather
than something you have to remember:

| package | may import | check |
|---|---|---|
| `domain/` | Kotlin/JVM only — stdlib (`kotlin.math`, `kotlin.random`), `java.nio.ByteBuffer`, coroutines, `javax.inject`. No `android.*`, no `androidx.*` | `grep -rn "^import android" domain/` → **empty** ✅ |
| `data/` | `domain/` + CameraX, Hilt, `android.os` (through `ElapsedTimeSource`) | `grep -rn "^import com.bitgem.colorcam.ui" data/` → **empty** ✅ |
| `ui/` | `domain/`, Compose, CameraX view types | `grep -rn "^import com.bitgem.colorcam.data" ui/` → **one hit**, see below |

So the domain knows nothing about Android, the camera or the screen, and the UI never touches the
analysis pipeline: it receives an opaque `ImageAnalysis.Analyzer` from `CameraModule` and renders
`List<ColorResult>`.

The single upward import is `com.bitgem.colorcam.data.di.AnalysisExecutor`, injected into
`CameraViewModel` so the composition can attach the analyzer to the executor the camera pipeline
actually runs on. That qualifier lives next to the code that creates the executor (`data/di`), which
is why the leak exists and why it is documented rather than hidden: moving the qualifier into a
neutral package (or into `domain/` as a plain annotation) would close the hole.

Keeping this a single Gradle module keeps the build simple and ships one APK; the cost is that these
rules are conventions rather than compile errors — which is exactly why each one has a one-line
command that verifies it.

Everything is DI-wired with Hilt: `@HiltAndroidApp` on `ColorCamApplication`, `@HiltViewModel` on
`CameraViewModel`, and three modules in `data/di` — `AnalysisModule` (algorithm objects + the
analysis executor), `RepositoryModule` (`@Binds` interface → implementation) and `CameraModule`
(the analyzer the UI binds).

### Data flow

```
CameraX  ──ImageProxy(YUV_420_888)──▶  ColorRepositoryImpl.analyze()   [single background thread]
                                            │  ImageToYuv420Frame → Yuv420Frame (buffers + strides)
                                            │  converter → FrameData (ARGB, reused scratch buffer)
                                            │  throttle: ≤1 analysis / 100 ms
                                            │  sampler → every 4th pixel in x and y (1/16)
                                            │  histogram → 32³ bins, epoch-stamped, reused
                                            │  weighted k-means (k=8, fixed seed) over bins
                                            │  merge colours within 24 RGB units
                                            │  ColorSmoother (EMA α=0.35)  → top 5
                                            ▼
                              MutableStateFlow<List<ColorResult>>   (conflated)
                                            ▼
                    ObserveTopColorsUseCase (domain/usecases) → CameraViewModel.uiState
                                            ▼
            CameraRoute → collectAsStateWithLifecycle → CameraScreen → ColorsPanel (pure)
```

Nothing in the pipeline runs on the main thread, and the UI never touches a pixel: the composables
only receive `List<ColorResult>`.

### The algorithm in one paragraph

Sample the frame on a regular grid (every 4th pixel in both axes = 1/16 of the pixels), then drop
the samples into a coarse RGB histogram (5 bits per channel = 32 levels = 32 768 bins; each bin
keeps its population *and* the mean of the pixels that landed in it). Cluster the **non-empty
bins** — not the pixels — with weighted k-means (Lloyd's algorithm, k-means++ seeding, fixed seed
for reproducibility, empty clusters reseeded onto the worst-represented bin), then greedily merge
clusters whose colours are within 24 RGB units of each other, and finally exponentiate-smooth the
result against the previous frame. Percentages are bin populations divided by the number of sampled
pixels, so they are true occurrence rates of the sampled set. k is 8 rather than 5 so that merging
cannot starve the panel.

---

## Project layout

```
BitGem/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/com/bitgem/colorcam/
│       │   ├── ColorCamApplication.kt      @HiltAndroidApp
│       │   ├── MainActivity.kt             @AndroidEntryPoint, edge-to-edge, hosts CameraRoute
│       │   ├── domain/                     pure Kotlin: model, analysis, repository, usecases
│       │   ├── data/                       camera (CameraX), repository impl, di
│       │   └── ui/                         screens, viewmodel, components, theme
│       ├── main/res/                       strings (incl. values-iw/ Hebrew), theme, icons
│       ├── test/java/com/bitgem/colorcam/  56 JVM unit tests / 9 test classes
│       └── androidTest/java/com/bitgem/colorcam/  Compose UI + permission-gate tests
├── gradle/libs.versions.toml  Version catalog
├── PROCESS.md                 Architecture/algorithm write-up: decisions, difficulties, verification
└── README.md
```

## Design constraints honoured

* **No ready-made image/colour library.** The YUV→RGB conversion, the histogram, the clustering and
  the contrast calculation are all hand-written (`app/src/main/java/com/bitgem/colorcam/domain/analysis/`).
* **No business logic in the ViewModel** — it combines flows into `ColorAnalysisUiState` and
  forwards permission/error events. No arithmetic, no pixel access.
* **No pixel or analysis logic in composables** — `CameraScreen`, `ColorsPanel` and their children
  are pure functions of `ColorAnalysisUiState`.
* **No hard-coded UI strings** — everything is in `res/values/strings.xml` and read via
  `stringResource`.
* **Background execution** — CameraX invokes the analyzer on a dedicated single-thread `Executor`
  (`@AnalysisExecutor`, the same one handed to `setAnalyzer`), so no pixel work ever touches the main
  thread; `STRATEGY_KEEP_ONLY_LATEST` plus a 100 ms throttle keep the pipeline from becoming the
  bottleneck.
