package com.reabastr.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Always dark — the Reabastr brand is a dark-navy fintech aesthetic.
private val ColorScheme = darkColorScheme(
    primary             = Blue40,
    onPrimary           = Color.White,
    primaryContainer    = Navy40,
    onPrimaryContainer  = Blue70,

    secondary           = Slate60,
    onSecondary         = Color.White,
    secondaryContainer  = Slate20,
    onSecondaryContainer = Slate80,

    tertiary            = Blue70,
    onTertiary          = Navy10,
    tertiaryContainer   = Navy50,
    onTertiaryContainer = Blue80,

    error               = ErrorRed,
    onError             = OnErrorRed,
    errorContainer      = ErrorRedCt,
    onErrorContainer    = OnErrorRedCt,

    background          = Navy20,
    onBackground        = Color.White,

    surface             = Navy20,
    onSurface           = Color.White,

    // Cards and contained components sit one step above the background
    surfaceVariant      = Navy30,
    onSurfaceVariant    = Slate60,

    outline             = Navy60,
    outlineVariant      = Navy40,

    inverseSurface      = Slate80,
    inverseOnSurface    = Navy20,
    inversePrimary      = Blue50,
    surfaceTint         = Blue40,
)

// More generous rounding — matches the rounded-card language in the reference design.
private val ReabastrShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ReabastrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography  = ReabastrTypography,
        shapes      = ReabastrShapes,
        content     = content,
    )
}
