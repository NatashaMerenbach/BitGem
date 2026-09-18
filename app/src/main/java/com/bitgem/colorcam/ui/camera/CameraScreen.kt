package com.bitgem.colorcam.ui.camera

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitgem.colorcam.R
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.ui.camera.components.CameraPermissionRequest
import com.bitgem.colorcam.ui.camera.components.ColorsPanel
import com.bitgem.colorcam.ui.camera.components.ErrorMessage
import com.bitgem.colorcam.ui.theme.ColorCamTheme
import java.util.concurrent.Executor

/**
 * The whole screen, as a pure function of [state].
 *
 * Layout (matching the reference UI): the camera preview fills the screen and the colour panel
 * is an **opaque overlay** on the trailing edge — roughly the rightmost 30% — with a hard
 * vertical edge over the live image rather than sitting beside it in a row.
 *
 * The panel's width is derived from the available width instead of being a constant, so the
 * proportion holds on a tablet or in landscape.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun CameraScreen(
    state: ColorAnalysisUiState,
    analyzer: ImageAnalysis.Analyzer,
    analysisExecutor: Executor,
    onRequestPermission: () -> Unit,
    onCameraError: (Throwable) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (state.hasCameraPermission) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val panelWidth = maxOf(MIN_PANEL_WIDTH, maxWidth * PANEL_WIDTH_FRACTION)

                CameraPreview(
                    analyzer = analyzer,
                    analysisExecutor = analysisExecutor,
                    onCameraError = onCameraError,
                    modifier = Modifier.fillMaxSize(),
                )

                ColorsPanel(
                    colorResults = state.topColors,
                    isWaitingForFrames = state.isWaitingForFrames,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(panelWidth)
                        .fillMaxHeight(),
                )
            }
        } else {
            CameraPermissionRequest(
                onRequestPermission = onRequestPermission,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
        }

        state.cameraError?.let { error ->
            ErrorMessage(
                message = stringResource(error.messageRes),
                onDismiss = onDismissError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

private val CameraError.messageRes: Int
    get() = when (this) {
        CameraError.CameraUnavailable -> R.string.error_camera_unavailable
        CameraError.AnalysisFailed -> R.string.error_analysis_failed
    }

private val MIN_PANEL_WIDTH = 132.dp

/** The reference UI's panel is a little under a third of the screen width. */
private const val PANEL_WIDTH_FRACTION = 0.30f

@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun CameraScreenPreview() {
    ColorCamTheme {
        CameraScreen(
            state = ColorAnalysisUiState(
                hasCameraPermission = true,
                topColors = listOf(
                    ColorResult(RgbColor(116, 114, 94), 38.24f),
                    ColorResult(RgbColor(101, 99, 77), 24.11f),
                    ColorResult(RgbColor(119, 120, 116), 18.03f),
                    ColorResult(RgbColor(235, 236, 230), 12.29f),
                    ColorResult(RgbColor(119, 120, 115), 7.33f),
                ),
            ),
            analyzer = ImageAnalysis.Analyzer { },
            analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(),
            onRequestPermission = {},
            onCameraError = {},
            onDismissError = {},
        )
    }
}
