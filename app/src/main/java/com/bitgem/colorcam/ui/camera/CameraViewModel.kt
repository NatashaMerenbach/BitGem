package com.bitgem.colorcam.ui.camera

import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitgem.colorcam.data.di.AnalysisExecutor
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.usecase.ObserveErrorsUseCase
import com.bitgem.colorcam.domain.usecase.ObserveTopColorsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * What the camera screen looks like. The screen is a pure function of this object — every
 * value it needs is here, so the composables never reach for anything themselves.
 */
data class ColorAnalysisUiState(
    val hasCameraPermission: Boolean = false,
    val topColors: List<ColorResult> = emptyList(),
    val cameraError: CameraError? = null,
) {
    /** True until the first frame has been analysed. */
    val isWaitingForFrames: Boolean get() = topColors.isEmpty()
}

/** Errors the screen can show. UI-agnostic (no strings) — the screen maps them to resources. */
sealed interface CameraError {
    /** Binding the camera to the lifecycle failed (no camera, permission revoked, in use). */
    data object CameraUnavailable : CameraError

    /** A frame could not be analysed. Transient: the pipeline keeps consuming frames. */
    data object AnalysisFailed : CameraError
}

/**
 * Orchestration only: it wires the repository's flows to the UI state and exposes the two
 * camera-pipeline objects the composable needs to bind CameraX. No pixel math, no clustering,
 * no percentages — all of that lives behind [ObserveTopColorsUseCase].
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    observeTopColors: ObserveTopColorsUseCase,
    val observeErrorsUseCase: ObserveErrorsUseCase,
    val analyzer: ImageAnalysis.Analyzer,
    @AnalysisExecutor val analysisExecutor: Executor,
) : ViewModel() {

    private val hasCameraPermission = MutableStateFlow(false)
    private val cameraError = MutableStateFlow<CameraError?>(null)

    val uiState: StateFlow<ColorAnalysisUiState> =
        combine(hasCameraPermission, observeTopColors(), cameraError, ::ColorAnalysisUiState)
            .stateIn(
                scope = viewModelScope,
                // Survives a configuration change without tearing the camera down, but stops
                // consuming frames shortly after the app goes to the background.
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ColorAnalysisUiState(),
            )

    init {
        viewModelScope.launch {
            observeErrorsUseCase().collect { cameraError.value = CameraError.AnalysisFailed }
        }
    }

    fun onCameraPermissionResult(granted: Boolean) {
        hasCameraPermission.value = granted
    }

    fun onCameraError(error: Throwable) {
        cameraError.value = CameraError.CameraUnavailable
        viewModelScope.launch {//NM - was fixed here, but I think we should still log the error for debugging purposes
            observeErrorsUseCase().collect { cameraError.value = CameraError.CameraUnavailable }
        }
    }

    fun onDismissError() {
        cameraError.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
