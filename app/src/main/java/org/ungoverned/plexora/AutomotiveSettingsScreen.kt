package org.ungoverned.plexora

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat

@Composable
fun AutomotiveSettingsScreen(
    viewModel: PlexoraViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionInfo = remember(context) { readVersionLabel(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = AutomotiveSettingsDimens.horizontalPadding,
                vertical = 20.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(AutomotiveSettingsDimens.sectionSpacing),
    ) {
        AutomotiveSettingsSection(title = "Plex account") {
            Text(
                "Linked to your Plex server. Resetting signs you out and clears local data.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.logout(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AutomotiveSettingsDimens.minButtonHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Unlink and reset", style = MaterialTheme.typography.titleMedium)
            }
        }

        AutomotiveSettingsSection(title = "Storage") {
            Text(
                "Clear cached library metadata and artwork if the vehicle library looks stale.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.clearAppCaches(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AutomotiveSettingsDimens.minButtonHeight),
            ) {
                Text("Clear caches", style = MaterialTheme.typography.titleMedium)
            }
        }

        AutomotiveSettingsSection(title = "About") {
            Text(
                "Version $versionInfo",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Browse and playback use your vehicle's media app. Change Plex account here only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutomotiveSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AutomotiveSettingsDimens.sectionCorner),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(AutomotiveSettingsDimens.sectionInnerPadding)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            )
            content()
        }
    }
}

private fun readVersionLabel(context: Context): String =
    runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        "${packageInfo.versionName ?: "Unknown"} " +
            "(${PackageInfoCompat.getLongVersionCode(packageInfo)})"
    }.getOrDefault("Unknown")
