package com.bitgem.colorcam.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitgem.colorcam.data.di.CameraAnalysisEntryPoint
import com.bitgem.colorcam.ui.viewmodel.CameraViewModel
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.Executor

/**
 * Stateful entry point: owns the ViewModel, the runtime-permission launcher and the
 * one-shot checks, and hands plain values/callbacks to the stateless [CameraScreen].
 *
 * This split (Route = stateful + effects, Screen = pure) is what keeps the screen previewable
 * and testable: `CameraScreenTest` renders [CameraScreen] with hand-made state and no
 * ViewModel or camera at all.
 *
 * The permission flow has three outcomes, not two, because Android makes the third one
 * invisible: after a permanent denial ("don't ask again") the request resolves immediately
 * with no dialog, so the screen would look broken if it kept offering to ask. Detecting it
 * needs two things — the rationale flag and the knowledge that we have already asked once.
 */
@Composable
fun CameraRoute(viewModel: CameraViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // The CameraX objects come from the composition root rather than from the ViewModel: they are
    // the one part of the pipeline that genuinely belongs to the UI, and both bindings are
    // singletons, so this is the same analyzer the repository claims to be and the same single
    // analysis thread.
    val cameraAnalysis = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CameraAnalysisEntryPoint::class.java,
        )
    }
    val analyzer: ImageAnalysis.Analyzer = remember(cameraAnalysis) { cameraAnalysis.analyzer() }
    val analysisExecutor: Executor = remember(cameraAnalysis) { cameraAnalysis.analysisExecutor() }

    /**
     * Whether the system dialog has been shown at least once. Android reports
     * `shouldShowRequestPermissionRationale` as false both *before* the first request and
     * *after* a permanent denial; this flag is the only thing that tells the two apart.
     */
    var hasAsked by rememberSaveable { mutableStateOf(false) }

    fun refreshPermissionState() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val rationaleWouldShow =
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
        viewModel.onCameraPermissionResult(
            granted = granted,
            canRequestAgain = !hasAsked || rationaleWouldShow,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        // Re-derive rather than trusting the `granted` boolean alone: it is false both for
        // "denied, may ask again" and for "denied for good", and the rationale flag is the
        // authority on which one just happened.
        onResult = { refreshPermissionState() },
    )

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            refreshPermissionState()
        } else {
            hasAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Granting (or revoking) the permission in Settings happens outside this process, so the
    // state has to be re-read when the user comes back — otherwise the gate would stay on
    // screen until the app is restarted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshPermissionState()
    }

    CameraScreen(
        state = uiState,
        analyzer = analyzer,
        analysisExecutor = analysisExecutor,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onOpenAppSettings = {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).apply {
                // The app's details page is where the permission can be flipped back on; from a
                // non-Activity context (a preview, a test host) it needs its own task.
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        },
        onCameraStarted = viewModel::onCameraStarted,
        onCameraError = viewModel::onCameraError,
        onDismissError = viewModel::onDismissError,
    )
}

/** The Activity behind a `Context`, which may be wrapped (Compose hosts often hand one over). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
