package org.ungoverned.plexora

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Muted palette closer to system settings than the mobile Plexora shell. */
fun automotiveSettingsColorScheme() = darkColorScheme(
    primary = Color(0xFFE5A93B),
    onPrimary = Color(0xFF1A1A1A),
    background = Color(0xFF1B1B1B),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF262626),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF333333),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF5A5A5A),
    error = Color(0xFFCF6679),
    onError = Color(0xFF1A1A1A),
)

object AutomotiveSettingsDimens {
    val horizontalPadding = 32.dp
    val sectionSpacing = 24.dp
    val sectionCorner = 12.dp
    val sectionInnerPadding = 24.dp
    val titleSp = 22.sp
    val bodySp = 18.sp
    val captionSp = 16.sp
    val minButtonHeight = 56.dp
    val sectionLabelSp = 14.sp
}
