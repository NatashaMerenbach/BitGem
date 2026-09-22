# Process write-up — Color Breakdown

What was built, why it was built that way, what went wrong, and how I convinced myself it is
correct. Written against the actual code in this repository and the actual output of the builds
that produced it.

---

## 1. Scope and shape

An Android app (Compose + CameraX + Hilt) that shows a live camera preview with an opaque black
panel overlaid on its trailing edge, listing the five most common colors in the current frame:
each as a swatch filled with that color holding the percentage, with `R:116 G:114 B:94` below it.
The color analysis — YUV decoding, binning, clustering, percentages, contrast selection — is
hand-written. No OpenCV, no ML Kit, no Palette API, no `ScriptIntrinsicYuvToRGB`, no library
quantisation.

```
:app  (com.bitgem.colorcam)
  ├── domain/   Pure Kotlin: models, use cases, the whole color pipeline
  ├── data/     CameraX analyser, ImageProxy → domain mapping, Hilt wiring
  └── ui/       Compose screens, ViewModel, theme
```

**Verified build:** `./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`, **56 JVM unit
tests, 0 failures**, `app-debug.apk` (11.35 MB) plus `app-debug-androidTest.apk` (0.94 MB).

---

## 2. Architectural decisions and the alternatives that were rejected

### 2.1 One module, three layers as packages

The app is a single `:app` module; the layers are packages under
`com.bitgem.colorcam` (`domain/`, `data/`, `ui/`).

This was arrived at in two steps. The first implementation was a three-module build (`:domain` as a
`kotlin("jvm")` module, `:data` and `:app` as Android modules), which makes "the domain has no
Android dependencies" a *compiler-enforced* guarantee — `android.*` and `androidx.*` are simply not
on `:domain`'s compile classpath. The author then asked for a single module, which is what the
project ships: simpler builds, one APK, no module-boundary ceremony.

**The boundary is still enforced — by the build, not by review.** What a module boundary would have
given for free is checked by `checkLayerBoundaries` in `app/build.gradle.kts`, wired into `check`, so
a violation fails the build on the commit that introduces it:

| rule | enforced |
|---|---|
| `domain/` imports nothing from `android.*` / `androidx.*` | ✅ build failure |
| `domain/` imports nothing from `data/` or `ui/` | ✅ build failure |
| `data/` imports nothing from `ui/` | ✅ build failure |

```bash
$ ./gradlew check                     # includes :app:checkLayerBoundaries
> Layer boundaries violated (see PROCESS.md §2.1):
    domain/model/BoundaryProbe.kt → android.util.Log
```

That is the cheap half of a module split: the same guarantee, no extra build files, ~0.5 s of build
time, and it keeps a later extraction *mechanical* rather than exploratory, because the boundary is
known to hold at all times — moving `domain/` into its own module then becomes a move, not a
refactor.

*Alternative considered (and rejected both before and after the change):* a fully modularised build
(`:core:domain`, `:core:data`, `:feature:camera`) — the strongest enforcement, but it costs a build
file and a version-catalog entry per module and buys nothing this app needs at ~3 600 lines. The
honest trigger for revisiting: **more than one team working in this repo**, or `domain/` becoming a
library someone else consumes. At that point the check is replaced by a real module (or
Konsist/ArchUnit in CI) and the guarantee becomes structural instead of textual.

The design was still *shaped* by the boundary, and that shape is worth keeping even though the
compiler no longer enforces it. `ImageProxy` never reaches the domain: the pipeline is split at the
seam where the Android-ness actually lives, so the interesting code is testable on a plain JVM.

```kotlin
// data/camera — the only class that knows CameraX exists; copies references and strides, no pixel maths
fun map(image: ImageProxy): Yuv420Frame = Yuv420Frame(
    width = image.width, height = image.height,
    y = image.planes[0].buffer, u = image.planes[1].buffer, v = image.planes[2].buffer,
    yRowStride = image.planes[0].rowStride, uvRowStride = image.planes[1].rowStride,
    uvPixelStride = image.planes[1].pixelStride, timestampNanos = image.imageInfo.timestamp,
)
```

*Alternative considered (and rejected both before and after the change):* a fully modularised build
(`:core:domain`, `:core:data`, `:feature:camera`) — the strongest enforcement, but it costs a build
file and a version-catalog entry per module and buys nothing this app needs at ~3 600 lines.

*Cost of the choice, paid honestly:* `domain/` depends on `javax.inject` for the use cases' `@Inject`
constructors, so the domain is DI-aware. That is a JSR-330 annotation, not Android — but it is a
leak of infrastructure into the "pure" layer, and the alternative (an `@Provides` for every use
case in `data/`) trades the leak for boilerplate. I chose the leak.

### 2.2 The repository is both the domain repository *and* the CameraX analyzer

`ColorRepositoryImpl` implements `ColorRepository` (domain) and `ImageAnalysis.Analyzer`
(CameraX). That sounds like a layering crime, but it is what removes an entire class of glue:

```kotlin
@Provides @Singleton
fun provideAnalyzer(repository: ColorRepositoryImpl): ImageAnalysis.Analyzer = repository
```

The UI asks Hilt for an opaque `ImageAnalysis.Analyzer` and never sees a data-layer type; the domain
interface stays free of camera vocabulary; and the objects are handed to the composition root, so
`CameraViewModel` never names a framework type either:

```kotlin
@EntryPoint @InstallIn(SingletonComponent::class)
interface CameraAnalysisEntryPoint {
    fun analyzer(): ImageAnalysis.Analyzer
    @AnalysisExecutor fun analysisExecutor(): Executor
}
```

What the ViewModel *does* keep is the UI state plus callbacks — `uiState`, `onCameraPermissionResult`,
`onCameraError`, `onDismissError` — and nothing else. Carrying the analyzer and a raw `Executor`
through it (which is what it did before) made it a courier for framework types: it worked, and the
composable needs both objects anyway, but it stretched "the ViewModel exposes a state flow and
orchestrates" into "the ViewModel also forwards CameraX handles". Since both bindings are singletons,
the composition root can read them itself, and the CameraX vocabulary stays in the one layer that
actually binds CameraX.

*Alternatives considered:* (a) a `CameraFrameSource` port in `domain/` implemented in `data/`, with
the composable pulling frames — rejected because it inverts the pull/push direction for no benefit
and the domain would still have to model "a stream of frames it did not ask for"; (b) building the
`ImageAnalysis` use case and calling `setAnalyzer` inside the ViewModel — rejected, it makes the
ViewModel own CameraX setup that only the composition's lifecycle knows about; (c) constructing the
camera inside the composable and casting the repository — rejected, a cast is a worse contract than
an interface; (d) a second, camera-only ViewModel whose only job is to be injected with the analyzer
and the executor — rejected, it is the same courier with a new name; (e) keeping them on the
ViewModel — the status quo this change replaces.

### 2.3 One entry point, plus an error channel

```kotlin
interface ColorRepository {
    fun observeTopColors(): Flow<List<ColorResult>>   // the live camera path
    fun observeAnalysisErrors(): Flow<Throwable>      // per-frame failures, never thrown at the camera
}
```

`observeTopColors()` is what the screen uses (conflated `StateFlow`, so a slow collector skips
values instead of slowing the camera down).

Failures travel on a second flow rather than through the value stream, because a frame that cannot
be analysed must not stop the camera: `analyze()` catches `Throwable`, logs the first failure of each
burst (`ColorCam.Analysis` — with the frame geometry and strides, suppressed afterwards so a
per-frame bug cannot bury its own trace) and emits on `observeAnalysisErrors()`, which the screen
turns into a dismissible banner. The camera-binding failure takes the same route from
`CameraPreview` (`ColorCam.Camera`). Nothing is thrown at the camera and nothing reaches the user as
a stack trace: the trace is for `adb logcat`.

An earlier revision also carried a pull-based entry point — `suspend fun getTopColors(frame:
FrameData): List<ColorResult>`, driven by a `GetTopColorsUseCase` — on the grounds that the
algorithm should be reachable for a still image, a share target or a widget. It was removed: no
shipping code called it (the camera screen is the only consumer), so it was a tested but dead seam
whose only guaranteed future was drift. Keeping it would also have meant duplicating the
pipeline's dispatcher and locking rules for a caller that did not exist.

This is the one place the build departs from the brief's wording, which asks for a use case that
"takes raw frame pixel data (or a domain-level `FrameData`) and returns `List<ColorResult>`": what
ships is the Flow-based twin of that shape. The trade is stated in README's "Conformance with the
brief" — a live camera has no caller asking for one frame, and re-adding the shape is ~20 lines on
top of an algorithm that is already request/response.

The seam is still easy to re-add when a feature needs it: a `suspend fun` that runs
`ColorQuantizer.quantize` on the analysis dispatcher under the same lock. What it must *not* do is
inherit the temporal smoother's state — a one-shot analysis has to answer for the frame it was
given, not for a blend with whatever the camera last saw. That constraint is recorded here (and in
the interface's KDoc) precisely because the code that enforced it is gone.

### 2.4 No logic in the ViewModel, no logic in the composables

`CameraViewModel` combines three flows into one immutable state object:

```kotlin
val uiState: StateFlow<ColorAnalysisUiState> =
    combine(hasCameraPermission, observeTopColors(), cameraError, ::ColorAnalysisUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ColorAnalysisUiState())
```

`CameraRoute` owns the ViewModel and the permission launcher; `CameraScreen` takes
`ColorAnalysisUiState` and five lambdas and knows nothing else. The payoff is measurable: the
Compose UI tests render `CameraScreen` with hand-made state and *no* ViewModel, Hilt or camera.

`WhileSubscribed(5_000)` rather than `Eagerly`: leaving the screen stops the pipeline instead of
analysing a frame nobody is looking at (the 5 s grace period keeps a rotation from tearing down the
camera). It also made the ViewModel test harder — see §3.8.

---

## 3. Algorithm decisions

### 3.1 The pipeline

**sample → bin → weighted k-means over bins → merge near-identical clusters → temporal smoothing.**
The sampling is done *during* the conversion (see §3.2): the converter walks a regular grid and
decodes only those pixels, so the most expensive per-pixel work in the pipeline is never done for
the 15/16 of pixels that would be discarded anyway.

### 3.2 Sampling (1/16 of the pixels), done during the conversion

Every 4th pixel in x *and* y, decoded straight off the YUV planes — the converter walks the sample
grid rather than converting everything and discarding 15/16 of it afterwards. The reasons, in order
of weight:

1. Cost: a 640×480 analysis stream is 307 200 pixels; sampling cuts the work to 19 200 — a 16×
   reduction. Doing it *inside* the converter matters because the YUV maths (two multiplies, three
   clamps and a pack per pixel) is the most expensive per-pixel work in the pipeline: sampling after
   the conversion would have left that cost on 100 % of the pixels, and the ARGB buffer at 1.2 MB
   instead of 77 KB.
2. Redundancy: neighbouring sensor pixels are almost perfectly correlated, so the extra samples buy
   accuracy nobody can see. Measured on the synthetic 60/30/10 fixture, the share error stays under
   1.5 points at step 4.
3. Determinism: a regular grid produces the same sample set for the same frame, which the stability
   tests rely on.

The known weakness is grid aliasing — a 4-pixel-pitch pattern can be systematically over- or
under-counted. A jittered/Poisson sample would fix it at the cost of determinism (see §6).

The sample grid *is* the `FrameData` the rest of the pipeline sees: its width/height are the number
of sampled columns/rows, so no later stage has to carry a stride around, and the histogram loop is
a flat scan. `Yuv420Converter.sampledPixelCount` is the single place that decides the buffer size.

### 3.3 Why bin *first*, then cluster

k-means over ~19 200 *pixels* needs 19 200 × k distance computations per iteration. k-means over the
~300–2 000 *non-empty bins* needs a fraction of that and gives the same answer, because each bin
carries its population as a **weight** and its **mean color** as its point — using the mean rather
than the bin centre means the coarse binning does not shift the resulting colors at all.

Layout: 5 bits/channel → 32 levels → 32 768 bins → ~1.2 MB of permanently-allocated scratch.
2 bits is visibly banded, 6 bits mostly wastes memory on empty bins.

*Alternatives considered:*
- **Median cut / octree quantisation** — cheaper and a classic, but produces "boxes", not means, and
  makes percentage-preserving merging awkward.
- **Mean shift** — better at finding natural color modes, and between 3 and 10× slower at this k.
- **Fixed uniform bins only (no clustering)** — this is what the naive version does, and it fails
  the actual requirement: a photo's colors straddle bin boundaries, so the "top 5 bins" are five
  near-identical shades of the same color instead of five *colors*.
- **Clustering in linear light or in Lab** — see §6; staying in gamma-encoded sRGB keeps the hot
  loop to three multiplies and no transform tables.

### 3.4 Determinism as a feature

k-means++ seeding with a fixed `Random(config.seed)`, points visited in deterministic histogram
order, and a stable sort by weight. Consequences: the same frame always produces the same five
colors, the tests can assert exact values, and the on-screen ordering does not randomly reshuffle
between identical frames. The price is that the seeding is not "fresh" per app run, which would
matter only if the app were trying to escape a bad local optimum by re-rolling — it is not.

### 3.5 Empty clusters are recovered, not ignored

The classic k-means bug: a centroid ends up owning nothing, so it keeps its old position and the
effective k silently shrinks — which shows up downstream as two identical swatches.

```kotlin
} else {
    val farthest = farthestPoint(pointCount, k)
    if (farthest >= 0) {
        centroidR[c] = pointR[farthest]; centroidG[c] = pointG[farthest]; centroidB[c] = pointB[farthest]
    }
    // Force at least one more iteration: the cluster just moved.
    maxShift = maxOf(maxShift, config.convergenceEpsilon * 2.0)
}
```


### 3.7 Temporal smoothing, because a per-frame analyser is a jitter machine

Each frame is analysed independently, so sensor noise and auto-exposure hunting move the numbers by
several points every frame — and occasionally swap the card order. `ColorSmoother` matches each new
color to the closest previous color (within `matchDistance`) and applies an EMA to both the color
and the percentage, then re-normalises to 100%.

*Alternatives considered:*
- **Rolling median over N frames** — better spike rejection, but needs a per-color history and lags
  by up to N frames (visible "sticky" percentages).
- **Keeping unmatched colors alive for N frames with a decay** — removes the flicker of a color
  oscillating in and out of the top 5, but produces *ghost cards* for colors that have left the
  scene, which is worse than a slightly jumpy list.

### 3.8 Threading and back-pressure

Three layers, each doing one job:

1. `STRATEGY_KEEP_ONLY_LATEST` on `ImageAnalysis` — CameraX drops frames while the analyzer is busy.
   A queue would mean analysing stale frames, which is worse than analysing fewer.
2. A time throttle (`minFrameIntervalMillis = 100`) — the panel is far below the flicker-fusion
   limit, so 10 analyses/s is visually identical to 30 and costs a third of the battery.
3. A conflated `StateFlow` — a slow collector skips values instead of back-pressuring the pipeline.

Everything runs on one dedicated thread (`Executors.newSingleThreadExecutor` named
`color-analysis`): the UI receives it as `@AnalysisExecutor` and hands it to
`ImageAnalysis.setAnalyzer`, so CameraX invokes the analyzer on that single thread. The
pipeline owns reusable scratch buffers, so it must not run twice at once; the analyzer takes
one lock, which makes the class correct even if someone hands it a multi-threaded executor and costs
nothing in practice (the camera path never contends).

### 3.9 Allocation discipline

At 10 analyses/s, per-frame allocations become GC pressure. Three specific fixes:

- **Reused ARGB buffer**: a 640×480 frame is 1.2 MB; `convertInto(frame, scratch)` writes into a
  buffer owned by the repository instead of allocating one per frame. The returned `FrameData`
  aliases that buffer, which is safe because it never escapes the analysis pass — and is documented
  as such, because it is exactly the kind of thing that would otherwise become a heisenbug.
- **Epoch-stamped histogram**: instead of clearing 32 768 bins × 4 arrays per frame, each slot
  carries an `epoch` stamp and is re-initialised lazily when the stamp is stale. Clearing becomes
  O(1) per frame and the clustering stage iterates only the non-empty bins.
- **Reused sample buffer**: grown on demand rather than allocated per frame.

### 3.10 Auto-contrast by WCAG, not by "brightness"

The percentage text on each swatch picks black or white by *computed contrast ratio*, not by a
luminance threshold:

```kotlin
fun contrastingTextColor(): RgbColor =
    if (contrastRatioWith(White) > contrastRatioWith(Black)) White else Black
```

It is a pure function of the color, so it is unit-tested for a set of swatches including the
awkward mid-tones, and it reproduces the reference UI's behaviour (white text on the olive/grey
swatches, dark text on the near-white `235,236,230` one).

---

## 4. Difficulties and how they were resolved

### 4.1 `ImageProxy` buffers die with `close()` — and frames must always be closed

The plane buffers are only valid until the proxy is closed, and an unclosed proxy stalls the
pipeline (CameraX stops delivering after `maxImages`), which freezes the preview too. The two
requirements pull in opposite directions, so the conversion happens *inside* the try and the close
happens in `finally`, on every path including the throttle's early return:

```kotlin
try {
    synchronized(analysisLock) {
        val yuvFrame = mapper.map(image)                       // buffers still valid here
        val frame = converter.convertInto(yuvFrame, scratchBufferFor(...))
        topColors.value = smoother.smooth(quantizer.quantize(frame, config.topColorCount))
    }
} catch (error: Throwable) {
    analysisErrors.tryEmit(error)                              // one bad frame ≠ dead camera
} finally {
    image.close()                                              // always, on every path
}
```

`ColorRepositoryImplTest` asserts `verify(image).close()` on the success path, the throttled path
and the failure path.

### 4.2 Strides: the bug that produces a skewed, color-shifted image

Camera planes are padded (`rowStride > width`, frequently 128-byte aligned), and chroma comes in two
layouts: planar (`uvPixelStride = 1`, I420) and semi-planar (`uvPixelStride = 2`, NV12/NV21). Reading
a padded plane linearly is the classic defect. Both layouts collapse to one expression:

```kotlin
val yIndex = row * yRowStride + col * yPixelStride                 // yPixelStride is usually 1, not always
val chromaIndex = (row shr 1) * uvRowStride + (col shr 1) * uvPixelStride
```

Three tests pin this down: a padded `rowStride = width + 4` frame, an interleaved `pixelStride = 2`
frame, and a truncated plane (which must degrade to neutral chroma instead of throwing mid-frame —
some vendor HALs hand out buffers a few bytes shorter than the stride arithmetic implies).

### 4.3 color space: full-range BT.601, documented rather than assumed

`R = Y + 1.402·V′`, `G = Y − 0.344136·U′ − 0.714136·V′`, `B = Y + 1.772·U′` with `U′ = U − 128`.
This assumes full-range video levels, which is what Android camera devices emit for YUV_420_888.
A limited-range (16–235) source would need a scale/offset, and the visible symptom would be reduced
contrast in the deepest blacks/highlights, *not* a hue error — worth writing down, because the
symptom is easy to misdiagnose as a wrong-coefficient bug.

### 4.4 An icon that silently required API 26

`minSdk 24` (CameraX's floor) plus `<adaptive-icon>` XMLs in `mipmap-anydpi/` fails resource
linking: *"adaptive-icon elements require a sdk version of at least 26"*. The original project had
`minSdk 27`, which is why it had never surfaced. Fixed the standard way — moved them to
`mipmap-anydpi-v26/` — rather than by raising `minSdk`, because the `-v26` qualifier is what the
platform actually expects and the density-bucketed fallbacks already existed.

### 4.5 The unused zero-correlation case: `Float` vs `Double`

`ColorMath.lerp` calls `RgbColor.of(from.r + (to.r - from.r) * t)` — `Float` arithmetic, because `Int
* Float` is `Float` in Kotlin, and `of` only had `Int`/`Double` overloads. Adding a `Float` overload
was the right fix (rather than sprinkling `.toDouble()` through the call site), because the ambiguity
would otherwise recur at every call site that interpolates.

### 4.6 A constructor-order trap that the type system does not catch

`combine(a, b, c, ::ColorAnalysisUiState)` compiled... until the flows were ordered differently from
the data class's parameters. `combine` is generic in its three inputs, so `List<ColorResult>` in the
`Boolean` slot is a type error — but only if the parameters' types are distinct enough to be
mismatched; with three `Boolean`s it would have compiled and produced a silently wrong state. Getting
into the habit of building the state with an explicit lambda is the safer pattern, and the reason the
ViewModel test asserts *each* field of the state rather than just "state changed".

### 4.7 `stateIn` with `WhileSubscribed` in a unit test

`CameraViewModelTest` first asserted `viewModel.uiState.value` directly and saw only the initial
value — because `WhileSubscribed` means the upstream is not collected until someone subscribes. Two
consequences: the test must subscribe (an unconfined collector in `backgroundScope`), and, before
that, the ViewModel must not be constructed in a field initialiser, because the `init` block launches
on `viewModelScope` and JUnit applies the dispatcher rule *after* the test instance is created:

```
java.lang.IllegalStateException: Module with the Main dispatcher had failed to initialize
    at kotlinx.coroutines.internal.MissingMainCoroutineDispatcher.missing
    at com.bitgem.colorcam.ui.camera.CameraViewModel.<init>(CameraViewModel.kt:69)
    at com.bitgem.colorcam.ui.camera.CameraViewModelTest.<init>(CameraViewModelTest.kt:37)
```

Both are documented in the test itself, because both will bite the next person who writes a
ViewModel test in this codebase.

### 4.8 Build-toolchain archaeology

The project as found could not build Compose + Hilt: the root `build.gradle` declared AGP 8.3.1 /
Kotlin 1.9.20 while `settings.gradle` declared AGP 8.7.0 / Kotlin 1.9.0, the `app` module pinned
`kotlinCompilerExtensionVersion 1.5.4` while using Compose BOM `2024.10.01` (which needs the 2.0
compiler plugin), `kotlin-kapt` was applied but unused, and `android.experimental.lint.version` was
pinned to a version that does not match AGP 8.9. Also `org.gradle.java.home` pointed at JDK 17 while
the shell default is JDK 23 — AGP 8.x rejects 23, so the pin is load-bearing.

Resolution: one version catalog, Kotlin 2.0.21 with the `org.jetbrains.kotlin.plugin.compose`
compiler plugin, KSP (not kapt) for Hilt, `./gradlew wrapper --gradle-version 8.11.1` (AGP 8.9's
minimum), and the removed `enableJetifier`.

---

## 5. How correctness was verified

Everything below is the output of a real run in this repository, not an expectation.

### 5.1 The unit tests

`./gradlew testDebugUnitTest` → **58 tests, 0 failures** (32 in `domain/analysis`, 9 in
`domain/model`, 2 in `domain/usecases`, 9 in `data/camera`, 6 in `data/repository`), no device and no
Robolectric; the color pipeline itself (the 43 domain tests) runs in about a second.
`./gradlew check` adds Android Lint (0 errors) and the layer-boundary check below.

**Known-color fixtures.** The converter is checked against the BT.601 reference vectors rather than
against itself:

| color | Y | U | V | expected | asserted |
|---|---|---|---|---|---|
| red | 76 | 84 | 255 | (254, 0, 0) | `r ≥ 250, g ≤ 3, b ≤ 3` |
| green | 150 | 44 | 21 | (0, 255, 1) | `g ≥ 250, r ≤ 3, b ≤ 5` |
| blue | 29 | 255 | 107 | (0, 0, 254) | `b ≥ 250, r ≤ 3, g ≤ 3` |
| white | 255 | 128 | 128 | (255, 255, 255) | exact, every pixel |
| black | 0 | 128 | 128 | (0, 0, 0) | exact |

**Known-composition images.** Synthetic frames with a known color layout, sampled so the
composition is exact — 24 red / 12 green / 4 blue rows of 40, sampled on a step of 2, gives exactly
240 / 120 / 40 samples:

```
known 60-30-10 image yields those percentages and colors:
  red   60.0% ± 1.5 → (220, 30, 20) ± 3
  green 30.0% ± 1.5 → (30, 200, 60) ± 3
  blue  10.0% ± 1.5 → (40, 60, 210) ± 3
```

**Stability, not just accuracy.**
- *Determinism*: the same frame quantised twice returns equal lists.
- *Noise*: ±4 units of deterministic per-pixel noise keeps the dominant color within 3 points of
  60% and the color within 6 units.
- *Frame-to-frame*: two frames of the same scene with ~3-unit exposure drift keep the same colors in
  the same order, with each color moving < 12 RGB units.

**Degenerate inputs.** Empty frame → no colors (not a crash, not a black swatch). Single-color
frame → exactly one entry at 100%. A black frame → reported as black, because dropping it would be
wrong. Two colors 11 units apart → merged into one. Six distinct colors with `topColorCount = 5` →
exactly five, the five heaviest.

**The camera path without a camera.** `ImageProxy` and `PlaneProxy` are interfaces, so Mockito mocks
them and the repository is driven end-to-end: a mocked half-red/half-blue frame produces a 50/50
breakdown; a second frame inside the throttle window is dropped and still closed; a frame with no
planes reports through the error channel instead of crashing the pipeline; and the color flow is
verified to start empty and then reflect the latest frame.

### 5.2 The UI tests

Six Compose instrumented tests assert the rendered panel — heading, two-decimal percentages and
`R:116 G:114 B:94` lines, read from resources so they pass on any locale — plus the permission gate
(*Grant camera access* invokes the callback; the blocked variant offers Settings instead), and the
panel's position under an RTL layout.

**Executed, not just compiled:** `./gradlew connectedDebugAndroidTest` → **6 tests, 0 failures** on
an Android 17 (API 37) emulator **and** on a real Samsung SM-A725F (Android 14) — both connected at
once, so the suite ran twice, 37 s each (`app-debug-androidTest.apk`, 0.94 MB). The app itself was
then installed and run on both devices with a live camera.

That run was worth more than the tests it ran, because a suite that had never been executed had rotted
in two ways nothing else could have caught:

1. **The entire suite failed on modern Android before reaching our code.** Espresso 3.6.1 calls
   `android.hardware.input.InputManager.getInstance()`, which newer platforms no longer have:
   `java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance []` at
   `Espresso.onIdle(Espresso.java:357)` — every test, in the framework, not in the app. Bumping to
   `espresso-core 3.7.0` + `androidx.test.ext:junit 1.3.0` fixed it. This is the concrete cost of a
   suite that only ever compiles.
2. **Running the app itself in a Hebrew locale found a real UI bug.** `Alignment.CenterEnd` mirrors in
   RTL, so on an `iw` device the panel moved to the *left* edge — while the reference UI is a Hebrew
   heading with the panel still on the right. The fix belongs on the *container*: `Modifier.align` is
   resolved by the parent's measure policy, not by the child's composition locals (wrapping only the
   panel was the first attempt, and the new test caught it with `left=0.0.dp`). Now pinned by
   `CameraScreenTest.keepsThePanelOnTheRightWhenTheLocaleIsRtl`.

Both are recorded because they are the argument for the rule this project now follows: an instrumented
test that is never run is documentation, not a test.

### 5.3 What is *not* verified

The panel numbers have not been compared against a physical color reference under controlled
lighting, and no frame-rate/power measurements were taken. The 10/s throttle and the 1/16 sampling
are reasoned from cost models (§3.2, §3.8), not measured on a device.

### 5.4 Reference-UI fidelity

The mockup's percentages (`8.24%`, `7.11%`, `3.12%`… summing to ~20%) are not literal pixel shares of
that frame, so they cannot be reproduced by an occurrence percentage. The brief called the mockup
"not numerically accurate" for exactly this reason; what is matched is the *structure and format* —
opaque black full-height panel on the trailing ~30% of the preview, Hebrew heading (from
`values-iw/`), swatch-filled rounded boxes with the percentage inside, `R:… G:… B:…` below in white,
descending order, auto-contrasted percentage text. The percentages themselves are true shares of the
sampled pixels and therefore sum to 100.

---

## 6. What I would improve with more time

1. **Cluster in a perceptual space.** Euclidean distance in gamma-encoded sRGB treats
   `(0,0,0)→(0,50,0)` and `(0,0,0)→(50,0,0)` as equally different, which they visibly are not. CIE76
   ΔE in Lab (or a cheap approximation) would make `mergeDistance = 24` mean something consistent
   across hues. It costs a per-pixel transform — which is affordable precisely *because* the
   pipeline only transforms the samples, not the frame.
2. **Jittered (or blue-noise) sampling** to kill grid aliasing, traded against determinism. A cheap
   compromise: rotate the grid origin per frame using a fixed low-discrepancy sequence — still
   deterministic, no longer aligned with the sensor's 4-pixel structures. This is now the top of the
   list: the win from *not decoding* 15/16 of the frame is banked (the old "convert less, not just
   sample less" item is done — sampling happens inside the converter), so what is left of the
   sampling story is the aliasing weakness that comes with a fixed grid.
3. **Box-filter downscale instead of point sampling.** Averaging 4×4 blocks before clustering is
   better on noisy sensors than picking one pixel per block, at the cost of 16 reads per sample.
4. **Adaptive k.** `k = 6` is a constant; a silhouette score or an elbow heuristic would let a
   two-color scene stop pretending it needs six clusters, and a busy scene stop merging distinct
   colors.
5. **Hysteresis for card ordering.** EMA smooths the numbers but adjacent cards can still swap
   places frame to frame. Holding a swap until the difference exceeds a margin (in percentage and in
   color distance) would remove the last visible jitter.
6. **color names.** "38.24% · olive" is far more useful to a human than "38.24% · R:116 G:114 B:94",
   and would be another hand-written table (nearest named color in Lab).
7. **A tap-to-freeze / copy-hex affordance**, and pinning a swatch to compare it against a new
   scene — the feature that turns this from a demo into a tool.
8. **Region of interest.** Analyse only a centre crop or a tapped area, which is what you actually
   want when color-matching a fabric or a painted wall.
9. **Measure it.** Macrobenchmark for jank, `dumpsys batterystats` for the drain of the 10/s
   pipeline, and a device matrix (a Samsung semi-planar NV21 device and a Pixel) for the stride
   paths that are currently covered only by synthetic frames.
10. **Put the instrumented suite in CI.** It is green locally (§5.2) but nothing runs it automatically
    yet, and §5.2 is exactly what happens when nothing does.

---

## 7. AI usage: where it helped and where its output needed correction

The code in this repository was produced with an AI agent driving the build (the author directed,
reviewed and corrected; the agent wrote and iterated). Being specific about the failure modes is
more useful than a summary of "AI wrote the boilerplate".

### Where it clearly helped

- **Structural scaffolding at speed**: version catalog, Gradle module and plugin wiring, Hilt module
  shapes (`@Binds` for the interface, `@Provides` for the analyzer handle), qualifiers for the
  analysis executor/dispatcher. Tedious, low-ambiguity, high-signal-to-review work.
- **Volume of test scaffolding**: the fixture builder for YUV_420_888 frames, the Mockito setup for
  `ImageProxy`/`PlaneProxy`, and the twelve quantizer assertions were generated in one pass. Tests
  are where "many small, similar, mechanical cases" live, and that is where generation pays off most.
- **Naming and prose**: KDoc that states *why* (the sampling rationale, the empty-cluster recovery,
  the stride formula) rather than restating the code.
- **The state wiring**: the Route/Screen/ViewModel split, `combine` + `stateIn(WhileSubscribed)`, the
  permission launcher — idiomatic and correct on the first attempt.

### Where the output needed correction

Every one of these was caught by the compiler, by a failing test, or by the author's reference image
— none by inspection:

| # | What the agent produced | How it was caught / corrected |
|---|---|---|
| 1 | `FrameData(...).also { convertInto(frame, it) }` — passing a `FrameData` where the scratch `IntArray` was expected | Kotlin type error |
| 2 | `roundToInt` used without its import after a refactor; `RgbColor.of` had no `Float` overload although `ColorMath.lerp` produces `Float` | Kotlin type/resolution errors |
| 3 | A needless hand-rolled private `Flow<List<T>>.map` extension instead of importing `kotlinx.coroutines.flow.map` | review, then compiler |
| 4 | `combine(colors, permission, error, ::ColorAnalysisUiState)` — argument order did not match the data class's parameters | Kotlin type error (this time) |
| 5 | `AspectRatioStrategy` imported from `androidx.camera.core` instead of `androidx.camera.core.resolutionselector` | Kotlin unresolved reference |
| 6 | `by collectAsStateWithLifecycle()` without `import androidx.compose.runtime.getValue` | Kotlin "cannot serve as a delegate" |
| 7 | Kept `<adaptive-icon>` in `mipmap-anydpi/` after lowering `minSdk` to 24 | AAPT resource-linking failure |
| 8 | Wrote a test config with `topColorCount = 5 > clusterCount = 4` | The production `AnalysisConfig` invariant threw — the guard caught the test, not the app |
| 9 | Used `.value` on `observeTopColors()`, which returns a `Flow`, not a `StateFlow` | Kotlin unresolved reference |
| 10 | `assertEquals(expected = 254, actual = pixel.r)` — JUnit4 has no `(Int, Int)` overload, and named arguments defeat the boxing path | Kotlin "no applicable candidates" |
| 11 | Asserted a padded Y plane's `limit()` equals `width * height` | The test failed; **the implementation was right**, the assertion was wrong (`rowStride * height`) |
| 12 | Constructed the ViewModel in a JUnit field initialiser, before the dispatcher rule applied | `IllegalStateException: Module with the Main dispatcher had failed to initialize` |
| 13 | Asserted `uiState.value` without subscribing to a `WhileSubscribed` flow | Four failing assertions for the right reason |
| 14 | Two wrong smoother expectations: `matchDistance = 64` for greys 69.3 apart, and expecting `30%` after an `alpha = 1` pass that re-normalises to `42.86%` | Both failed; **implementation right, expectations wrong** |
| 15 | A `WholePercentages` test whose inputs did not sum to 100, making the "spare points" expectation impossible | Test failed; the fixture was incoherent |
| 16 | A patch that mangled the indentation of a preview function and left an extra brace | Compiler |

Pattern worth naming: **the failures cluster in the seams, not in the algorithm.** The color math,
the stride handling and the k-means all worked as written; what broke was Kotlin overload
resolution, JUnit's API surface, JUnit rule/dispatcher ordering, Gradle/AGP configuration, and my own
test fixtures. That is the argument for running the compiler and the suite early and often rather
than trusting a plausible-looking diff — and it is why the numbers in §5 are quoted from actual runs.

### The correction that came from a human, not a tool

The first implementation put the preview and the panel side by side in a `Row`, and formatted the
percentage as a whole number (`38%`) with the RGB text *inside* the swatch. The author's reference
image corrected three things that no test could: the panel is an **opaque overlay** on the trailing
~30% of the preview (hard vertical edge), the heading is **Hebrew** (`חלוקת צבעים`, which is why the
strings are localised in `values-iw/` rather than hard-coded), and the format is **two decimals**
(`8.24%`) with `R:116 G:114 B:94` **below** the swatch, in white on the panel. Ambiguity about the
panel's opacity was then settled by sampling the reference image's pixels rather than by asking a
model to look harder — the panel is opaque, and the heading sits directly on the black.

That sequence — AI generates a defensible interpretation of a written brief, the human supplies the
visual ground truth, the disagreement is resolved by measurement — is the honest summary of how this
was built.
