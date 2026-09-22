package com.bitgem.colorcam.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.usecases.ObserveErrorsUseCase
import com.bitgem.colorcam.domain.usecases.ObserveTopColorsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the camera screen looks like. The screen is a pure function of this object — every
 * value it needs is here, so the composables never reach for anything themselves.
 */
data class ColorAnalysisUiState(
    val cameraPermission: CameraPermissionState = CameraPermissionState.Requestable,
    val topColors: List<ColorResult> = emptyList(),
    val cameraError: CameraError? = null,
) {
    /** True until the first frame has been analysed. */
    val isWaitingForFrames: Boolean get() = topColors.isEmpty()
}

/**
 * How the camera permission stands, and therefore what the gate can do about it.
 *
 * [Blocked] is the state Android gives no direct signal for: once the user has denied the
 * request and chosen "don't ask again" (or denied it twice), launching the request again
 * resolves instantly with a denial and *no dialog*, so a "grant" button silently does nothing.
 * Regaining the permission from that point means sending the user to the app's Settings page,
 * which is what the third state exists to represent.
 */
enum class CameraPermissionState {
    /** Granted: the camera can be bound. */
    Granted,

    /** Not granted, but asking again still shows the system dialog. */
    Requestable,

    /** Not granted, and Android will not show the dialog again: Settings is the only route. */
    Blocked,
}

/** Errors the screen can show. UI-agnostic (no strings) — the screen maps them to resources. */
sealed interface CameraError {
    /** Binding the camera to the lifecycle failed (no camera, permission revoked, in use). */
    data object CameraUnavailable : CameraError

    /** A frame could not be analysed. Transient: the pipeline keeps consuming frames. */
    data object AnalysisFailed : CameraError
}

/**
 * Orchestration only: it wires the repository's flows to the UI state. No pixel math, no
 * clustering, no percentages — all of that lives behind [ObserveTopColorsUseCase].
 *
 * Note what is *not* here: no `ImageAnalysis.Analyzer` and no `Executor`. The preview needs both
 * to bind CameraX, but they are read from the Hilt graph by [com.bitgem.colorcam.ui.screens.CameraRoute]
 * (`CameraAnalysisEntryPoint`) instead of being couriered through this class, so the ViewModel's
 * public surface is exactly "UI state plus callbacks" and it names no framework type.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    observeTopColors: ObserveTopColorsUseCase,
    private val observeErrorsUseCase: ObserveErrorsUseCase,
) : ViewModel() {

    private val cameraPermission = MutableStateFlow(CameraPermissionState.Requestable)
    private val cameraError = MutableStateFlow<CameraError?>(null)

    val uiState: StateFlow<ColorAnalysisUiState> =
        combine(cameraPermission, observeTopColors(), cameraError, ::ColorAnalysisUiState)
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
        viewModelScope.launch {
            // Frames are flowing again, so an earlier analysis failure is stale information.
            // Leaving it up until the user dismisses it is what made an error look like it
            // appeared every time the camera opened; a failure that repeats on every frame keeps
            // the banner, because this only fires when the pipeline actually produces output.
            observeTopColors().collect { colors ->
                if (colors.isNotEmpty() && cameraError.value == CameraError.AnalysisFailed) {
                    cameraError.value = null
                }
            }
        }
    }

    /**
     * Reports the permission state after a check or a request.
     *
     * @param granted whether the CAMERA permission is currently held.
     * @param canRequestAgain whether launching the request *now* would still show the system
     * dialog — i.e. either we have never asked, or the user denied it while the rationale flag
     * is still set. When it is false and the permission is not granted, Android has stopped
     * offering the dialog and only Settings can grant it.
     */
    fun onCameraPermissionResult(granted: Boolean, canRequestAgain: Boolean) {
        cameraPermission.value = when {
            granted -> CameraPermissionState.Granted
            canRequestAgain -> CameraPermissionState.Requestable
            else -> CameraPermissionState.Blocked
        }
    }

    fun onCameraError(error: Throwable) {
        Log.e("CameraViewModel", "Camera error", error)
        cameraError.value = CameraError.CameraUnavailable
    }

    /**
     * The camera bound successfully, so a "camera unavailable" from an earlier attempt is stale.
     *
     * This is what stops a *transient* bind failure (the camera was busy for a moment, or the
     * permission had just been granted in Settings) from leaving an error on screen for the rest
     * of the session — and the retry in `CameraPreview` is what gives it a chance to recover.
     */
    fun onCameraStarted() {
        if (cameraError.value == CameraError.CameraUnavailable) cameraError.value = null
    }

    fun onDismissError() {
        cameraError.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
