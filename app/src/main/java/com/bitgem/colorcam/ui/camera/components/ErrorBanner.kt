package com.bitgem.colorcam.ui.camera.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitgem.colorcam.R

/**
 * Transient, dismissible message (camera could not be bound, analysis failed).
 *
 * Rendered on the primary container, per the project's convention for snackbar-style
 * surfaces.
 */
@Composable
fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.error_dismiss),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
