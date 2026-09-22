package com.bitgem.colorcam.ui.screens

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts the CameraX preview inside Compose and attaches the analysis analyzer.
 *
 * This is the *only* place that knows how to talk to CameraX from the UI, and it deliberately
 * does nothing with pixels: [analyzer] is an opaque object produced by the data layer, and
 * the actual work happens on [analysisExecutor] (a single background thread), never here.
 *
 * The binding is bound to the composition *and* the lifecycle:
 *  - inside [AndroidView] we hand Compose the `PreviewView`;
 *  - in a [DisposableEffect] we bind `Preview` + `ImageAnalysis` to the current
 *    `LifecycleOwner`, so the camera stops when the screen goes away;
 *  - on dispose we unbind, and a [disposed] flag protects against the (real) race where the
 *    provider future completes *after* the composable has left the composition.
 */
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    analysisExecutor: Executor,
    onCameraStarted: () -> Unit,
    onCameraError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE renders through a TextureView: it composes correctly inside Compose
            // hierarchies and stays consistent across devices, at a small performance cost
            // versus SurfaceView that does not matter here.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // A failed bind is not the end of the story: the camera may have been busy for a moment, or
    // the permission may have just been granted in Settings. Without this, one failure left the
    // error on screen until the app was restarted — every time the camera was opened. So we retry
    // whenever the app comes back to the foreground, and report a successful bind so a stale
    // error can be cleared.
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Only re-attempt after a completed failure: at first composition ON_RESUME fires while
        // the bind is still in flight, and bumping the key then would bind twice.
        if (failed) attempt++
    }

    DisposableEffect(lifecycleOwner, analyzer, analysisExecutor, attempt) {
        val disposed = AtomicBoolean(false)
        val cameraProvider = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProvider.addListener(
            {
                try {
                    val provider = cameraProvider.get()
                    if (disposed.get()) return@addListener

                    val preview = Preview.Builder()
                        .build()
                        .apply { surfaceProvider = previewView.surfaceProvider }

                    val imageAnalysis = ImageAnalysis.Builder()
                        // The camera keeps producing frames; we drop the ones we cannot keep up
                        // with instead of queueing them (analysing stale frames is useless).
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        // 640x480 is plenty for a color histogram and keeps the
                                        // conversion + clustering cost per frame tiny.
                                        Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    ),
                                )
                                .build(),
                        )
                        .build()
                        .apply { setAnalyzer(analysisExecutor, analyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                    if (failed) {
                        Log.i(LOG_TAG, "Camera bound on retry attempt ${attempt + 1}")
                    }
                    failed = false
                    onCameraStarted()
                } catch (error: Throwable) {
                    // The UI shows a fixed string; the trace goes to logcat, which is the only
                    // place a bug report can get it from (a revoked permission, a camera held by
                    // another app, or a device that cannot satisfy the resolution request).
                    Log.e(LOG_TAG, "Binding the camera to the lifecycle failed", error)
                    failed = true
                    onCameraError(error)
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed.set(true)
            if (cameraProvider.isDone) {
                runCatching { cameraProvider.get().unbindAll() }
            }
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private const val ANALYSIS_WIDTH = 640
private const val ANALYSIS_HEIGHT = 480

/** Filterable with `adb logcat -s ColorCam.Camera` (see README, "Debugging on a device"). */
private const val LOG_TAG = "ColorCam.Camera"
