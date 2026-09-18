package com.bitgem.colorcam

import androidx.camera.core.ImageAnalysis
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitgem.colorcam.domain.model.ColorResult
import com.bitgem.colorcam.domain.model.RgbColor
import com.bitgem.colorcam.ui.screens.COLORS_PANEL_TEST_TAG
import com.bitgem.colorcam.ui.screens.CameraScreen
import com.bitgem.colorcam.ui.viewmodel.CameraPermissionState
import com.bitgem.colorcam.ui.viewmodel.ColorAnalysisUiState
import com.bitgem.colorcam.ui.components.ColorsPanel
import com.bitgem.colorcam.ui.theme.ColorCamTheme
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the color panel and the permission gate.
 *
 * Expected strings are read from resources rather than hard-coded, so the tests pass on any
 * locale (the panel heading is Hebrew on an `iw` device, English otherwise).
 *
 * Note what is *not* needed here: no ViewModel, no Hilt, no camera. `CameraScreen` is a pure
 * function of `ColorAnalysisUiState`, so it can be rendered with hand-made state — the
 * practical payoff of the Route/Screen split.
 */
@RunWith(AndroidJUnit4::class)
class ColorBreakdownPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val colors = listOf(
        ColorResult(RgbColor(116, 114, 94), 38.24f),
        ColorResult(RgbColor(101, 99, 77), 24.11f),
        ColorResult(RgbColor(119, 120, 116), 18.03f),
        ColorResult(RgbColor(235, 236, 230), 12.29f),
        ColorResult(RgbColor(119, 120, 115), 7.33f),
    )

    @Test
    fun showsTheHeadingPercentagesAndRgbValues() {
        composeRule.setContent {
            ColorCamTheme {
                ColorsPanel(colorResults = colors, isWaitingForFrames = false)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.color_breakdown_title)).assertIsDisplayed()
        // Two decimals, as in the reference UI.
        composeRule.onNodeWithText(context.getString(R.string.color_card_percentage, 38.24f)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.color_card_percentage, 7.33f)).assertIsDisplayed()
        // RGB values sit below the swatch in the "R:116 G:114 B:94" format.
        composeRule.onNodeWithText(context.getString(R.string.color_card_rgb, 116, 114, 94)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.color_card_rgb, 235, 236, 230)).assertIsDisplayed()
    }

    @Test
    fun showsTheWaitingStateBeforeTheFirstFrame() {
        composeRule.setContent {
            ColorCamTheme {
                ColorsPanel(colorResults = emptyList(), isWaitingForFrames = true)
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.color_panel_waiting)).assertIsDisplayed()
    }

    @Test
    fun rendersFewerRowsWhenFewerColorsExist() {
        composeRule.setContent {
            ColorCamTheme {
                ColorsPanel(
                    colorResults = listOf(ColorResult(RgbColor(10, 10, 10), 100f)),
                    isWaitingForFrames = false,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.color_card_percentage, 100f)).assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class CameraScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val analyzer = ImageAnalysis.Analyzer { }
    private val executor = Executors.newSingleThreadExecutor()

    @Test
    fun asksForTheCameraPermissionWhenItIsMissing() {
        var requested = 0
        composeRule.setContent {
            ColorCamTheme {
                CameraScreen(
                    state = ColorAnalysisUiState(cameraPermission = CameraPermissionState.Requestable),
                    analyzer = analyzer,
                    analysisExecutor = executor,
                    onRequestPermission = { requested++ },
                    onOpenAppSettings = {},
                    onCameraError = {},
                    onDismissError = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.permission_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.permission_grant)).performClick()

        assertEquals(1, requested)
    }

    @Test
    fun sendsTheUserToSettingsWhenThePermissionIsBlocked() {
        var opened = 0
        composeRule.setContent {
            ColorCamTheme {
                CameraScreen(
                    state = ColorAnalysisUiState(cameraPermission = CameraPermissionState.Blocked),
                    analyzer = analyzer,
                    analysisExecutor = executor,
                    onRequestPermission = {},
                    onOpenAppSettings = { opened++ },
                    onCameraError = {},
                    onDismissError = {},
                )
            }
        }

        // The blocked variant must not offer to ask again — that request would resolve with no
        // dialog at all — it must offer Settings, which is the only place the permission can
        // be turned back on.
        composeRule.onNodeWithText(context.getString(R.string.permission_blocked_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.permission_open_settings)).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun keepsThePanelOnTheRightWhenTheLocaleIsRtl() {
        // Runs the screen in an RTL layout, as an `iw` device does. The panel must stay on the
        // physical right: the reference UI shows a Hebrew heading with the panel still on the
        // right, and an end-aligned panel alone mirrors to the left, which is what this test
        // caught on a real emulator.
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ColorCamTheme {
                    CameraScreen(
                        state = ColorAnalysisUiState(
                            cameraPermission = CameraPermissionState.Granted,
                            topColors = listOf(ColorResult(RgbColor(116, 114, 94), 100f)),
                        ),
                        analyzer = analyzer,
                        analysisExecutor = executor,
                        onRequestPermission = {},
                        onOpenAppSettings = {},
                        onCameraError = {},
                        onDismissError = {},
                    )
                }
            }
        }

        val screen = composeRule.onRoot().getBoundsInRoot()
        val panel = composeRule.onNodeWithTag(COLORS_PANEL_TEST_TAG).getBoundsInRoot()

        assertTrue(
            "the panel must stay on the physical right in RTL, but was at $panel in a $screen screen",
            panel.left.value > screen.right.value / 2f && panel.right.value <= screen.right.value,
        )
    }
}
