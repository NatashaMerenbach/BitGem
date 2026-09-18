package com.bitgem.colorcam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bitgem.colorcam.R
import com.bitgem.colorcam.ui.theme.ColorCamTheme

/**
 * Shown instead of the camera preview while the runtime CAMERA permission is missing.
 *
 * Two variants, because the action that actually works differs:
 *  - [isBlocked] `false`: the system dialog is still available, so the rationale is spelled out
 *    (including the fact that frames never leave the device) and the button asks again.
 *  - [isBlocked] `true`: Android has stopped offering the dialog, so asking is a no-op — the
 *    only route left is the app's Settings page, and the copy says so instead of pretending.
 *    Without this branch the gate keeps showing a button that silently does nothing.
 */
@Composable
fun CameraPermissionRequest(
    isBlocked: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (isBlocked) R.string.permission_blocked_title else R.string.permission_title,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                if (isBlocked) R.string.permission_blocked_rationale else R.string.permission_rationale,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = if (isBlocked) onOpenAppSettings else onRequestPermission,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = stringResource(
                    if (isBlocked) R.string.permission_open_settings else R.string.permission_grant,
                ),
            )
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun CameraPermissionRequestPreview() {
    ColorCamTheme {
        CameraPermissionRequest(
            isBlocked = false,
            onRequestPermission = {},
            onOpenAppSettings = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}

/** The variant shown after Android has stopped offering the system dialog. */
@Preview(widthDp = 360)
@Composable
private fun CameraPermissionBlockedPreview() {
    ColorCamTheme {
        CameraPermissionRequest(
            isBlocked = true,
            onRequestPermission = {},
            onOpenAppSettings = {},
            modifier = Modifier.padding(24.dp),
        )
    }
}
