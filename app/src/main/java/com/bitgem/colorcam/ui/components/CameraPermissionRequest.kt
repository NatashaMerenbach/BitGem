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
 * The rationale is spelled out (including the fact that frames never leave the device) rather
 * than firing the system dialog blindly — and the button re-triggers the request after a
 * denial, which the system dialog alone cannot do.
 */
@Composable
fun CameraPermissionRequest(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.permission_rationale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequestPermission,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(text = stringResource(R.string.permission_grant))
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun CameraPermissionRequestPreview() {
    ColorCamTheme {
        CameraPermissionRequest(onRequestPermission = {}, modifier = Modifier.padding(24.dp))
    }
}
