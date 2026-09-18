package com.bitgem.colorcam.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitgem.colorcam.ui.viewmodel.CameraViewModel

/**
 * Stateful entry point: owns the ViewModel, the runtime-permission launcher and the
 * one-shot checks, and hands plain values/callbacks to the stateless [CameraScreen].
 *
 * This split (Route = stateful + effects, Screen = pure) is what keeps the screen previewable
 * and testable: `CameraScreenTest` renders [CameraScreen] with hand-made state and no
 * ViewModel or camera at all.
 */
@Composable
fun CameraRoute(viewModel: CameraViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = viewModel::onCameraPermissionResult,
    )

    LaunchedEffect(Unit) {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onCameraPermissionResult(alreadyGranted)
        if (!alreadyGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    CameraScreen(
        state = uiState,
        analyzer = viewModel.analyzer,
        analysisExecutor = viewModel.analysisExecutor,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onCameraError = viewModel::onCameraError,
        onDismissError = viewModel::onDismissError,
    )
}
