package com.bitgem.colorcam.ui.camera

import androidx.camera.core.ImageAnalysis
import com.bitgem.colorcam.MainDispatcherRule
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.domain.repository.ColorRepository
import com.bitgem.colorcam.domain.usecases.ObserveErrorsUseCase
import com.bitgem.colorcam.domain.usecases.ObserveTopColorsUseCase
import com.bitgem.colorcam.ui.viewmodel.CameraError
import com.bitgem.colorcam.ui.viewmodel.CameraViewModel
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
 * The ViewModel is orchestration only, so its test is about *state wiring*, not colour
 * science: does the UI state follow the repository, the permission and the error channel?
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val colors = MutableStateFlow<List<ColorResult>>(emptyList())
    private val analysisErrors = MutableSharedFlow<Throwable>()
    private val repository = FakeRepository(colors, analysisErrors)
    private val analysisExecutor: Executor = Executors.newSingleThreadExecutor()

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
            analyzer = ImageAnalysis.Analyzer { },
            analysisExecutor = analysisExecutor,
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
    fun `starts with permission denied, no colours and no error`() {
        assertFalse(viewModel.uiState.value.hasCameraPermission)
        assertTrue(viewModel.uiState.value.topColors.isEmpty())
        assertTrue(viewModel.uiState.value.isWaitingForFrames)
        assertNull(viewModel.uiState.value.cameraError)
    }

    @Test
    fun `permission result is reflected in the state`() = runTest {
        observeUiState()

        viewModel.onCameraPermissionResult(true)
        assertTrue(viewModel.uiState.value.hasCameraPermission)

        viewModel.onCameraPermissionResult(false)
        assertFalse(viewModel.uiState.value.hasCameraPermission)
    }

    @Test
    fun `colours from the repository reach the state`() = runTest {
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
    fun `an analysis failure is surfaced`() = runTest {
        observeUiState()

        analysisErrors.emit(IllegalArgumentException("bad frame"))

        assertEquals(CameraError.AnalysisFailed, viewModel.uiState.value.cameraError)
    }

    private class FakeRepository(
        private val colors: MutableStateFlow<List<ColorResult>>,
        private val errors: MutableSharedFlow<Throwable>,
    ) : ColorRepository {
        override fun observeTopColors(): Flow<List<ColorResult>> = colors
        override fun observeAnalysisErrors(): Flow<Throwable> = errors
    }
}
