package com.bitgem.colorcam.ui.camera

import com.bitgem.colorcam.MainDispatcherRule
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.domain.repository.ColorRepository
import com.bitgem.colorcam.domain.usecases.ObserveErrorsUseCase
import com.bitgem.colorcam.domain.usecases.ObserveTopColorsUseCase
import com.bitgem.colorcam.ui.viewmodel.CameraError
import com.bitgem.colorcam.ui.viewmodel.CameraPermissionState
import com.bitgem.colorcam.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The ViewModel is orchestration only, so its test is about *state wiring*, not color
 * science: does the UI state follow the repository, the permission and the error channel?
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val colors = MutableStateFlow<List<ColorResult>>(emptyList())
    private val analysisErrors = MutableSharedFlow<Throwable>()
    private val repository = FakeRepository(colors, analysisErrors)

    /**
     * Built in `@Before`, not as a field initialiser: the ViewModel's `init` block launches on
     * `viewModelScope`, and a field initialiser runs before JUnit applies [MainDispatcherRule]
     * — which would fail with "Module with the Main dispatcher had failed to initialize".
     */
    private lateinit var viewModel: CameraViewModel

    @Before
    fun setUp() {
        viewModel = CameraViewModel(
            observeTopColors = ObserveTopColorsUseCase(repository),
            observeErrorsUseCase = ObserveErrorsUseCase(repository),
        )
    }

    /**
     * `uiState` is a `stateIn(WhileSubscribed)` — exactly what the screen needs (it stops the
     * work when the UI goes away). That means a test has to subscribe before the flow is live,
     * which is what this does.
     */
    private fun TestScope.observeUiState() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    @Test
    fun `starts with the permission still requestable`() {
        assertEquals(CameraPermissionState.Requestable, viewModel.uiState.value.cameraPermission)
        assertTrue(viewModel.uiState.value.topColors.isEmpty())
        assertTrue(viewModel.uiState.value.isWaitingForFrames)
        assertNull(viewModel.uiState.value.cameraError)
    }

    @Test
    fun `permission results map to the gate state`() = runTest {
        observeUiState()

        viewModel.onCameraPermissionResult(granted = true, canRequestAgain = false)
        assertEquals(CameraPermissionState.Granted, viewModel.uiState.value.cameraPermission)

        // Denied, but the system dialog is still available.
        viewModel.onCameraPermissionResult(granted = false, canRequestAgain = true)
        assertEquals(CameraPermissionState.Requestable, viewModel.uiState.value.cameraPermission)

        // Denied with no dialog left: only Settings can grant it, so the gate must say so
        // rather than keep offering a button that does nothing.
        viewModel.onCameraPermissionResult(granted = false, canRequestAgain = false)
        assertEquals(CameraPermissionState.Blocked, viewModel.uiState.value.cameraPermission)

        // And granting from Settings flips it back.
        viewModel.onCameraPermissionResult(granted = true, canRequestAgain = false)
        assertEquals(CameraPermissionState.Granted, viewModel.uiState.value.cameraPermission)
    }

    @Test
    fun `colors from the repository reach the state`() = runTest {
        observeUiState()
        val expected = listOf(
            ColorResult(RgbColor(220, 30, 20), 60f),
            ColorResult(RgbColor(30, 200, 60), 40f),
        )

        colors.value = expected

        assertEquals(expected, viewModel.uiState.value.topColors)
        assertFalse(viewModel.uiState.value.isWaitingForFrames)
    }

    @Test
    fun `a camera binding failure is surfaced`() = runTest {
        observeUiState()

        viewModel.onCameraError(IllegalStateException("no camera"))
        assertEquals(CameraError.CameraUnavailable, viewModel.uiState.value.cameraError)

        viewModel.onDismissError()
        assertNull(viewModel.uiState.value.cameraError)
    }

    @Test
    fun `a stale camera error clears itself once the camera binds`() = runTest {
        observeUiState()

        // The camera was busy for a moment, or the permission had just been granted in Settings.
        viewModel.onCameraError(IllegalStateException("camera in use"))
        assertEquals(CameraError.CameraUnavailable, viewModel.uiState.value.cameraError)

        // The retry in CameraPreview succeeds, so the banner must not stay on screen: an error
        // that survives the condition it describes reads as "this fails every time".
        viewModel.onCameraStarted()
        assertNull(viewModel.uiState.value.cameraError)
    }

    @Test
    fun `an analysis failure is surfaced`() = runTest {
        observeUiState()

        analysisErrors.emit(IllegalArgumentException("bad frame"))

        assertEquals(CameraError.AnalysisFailed, viewModel.uiState.value.cameraError)
    }

    @Test
    fun `a stale analysis error clears itself when frames flow again`() = runTest {
        observeUiState()
        analysisErrors.emit(IllegalArgumentException("bad frame"))
        assertEquals(CameraError.AnalysisFailed, viewModel.uiState.value.cameraError)

        colors.value = listOf(ColorResult(RgbColor(10, 20, 30), 100f))

        assertNull(viewModel.uiState.value.cameraError)
    }

    @Test
    fun `an analysis error survives while frames keep failing`() = runTest {
        observeUiState()
        analysisErrors.emit(IllegalArgumentException("bad frame"))

        // No colours arrive (the failure repeats every frame), so the banner stays — the clearing
        // above must not turn a real, ongoing failure into a silent one.
        assertEquals(CameraError.AnalysisFailed, viewModel.uiState.value.cameraError)
        assertEquals(emptyList<ColorResult>(), viewModel.uiState.value.topColors)
    }

    private class FakeRepository(
        private val colors: MutableStateFlow<List<ColorResult>>,
        private val errors: MutableSharedFlow<Throwable>,
    ) : ColorRepository {
        override fun observeTopColors(): Flow<List<ColorResult>> = colors
        override fun observeAnalysisErrors(): Flow<Throwable> = errors
    }
}
