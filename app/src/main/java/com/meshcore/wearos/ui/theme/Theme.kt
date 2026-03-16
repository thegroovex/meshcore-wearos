package com.meshcore.wearos.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Colors

// -------------------------------------------------------------------------
// MeshCore Color Palette
// -------------------------------------------------------------------------

val MeshGreen = Color(0xFF4CAF50)
val MeshGreenLight = Color(0xFF81C784)
val MeshGreenDark = Color(0xFF388E3C)
val MeshBlue = Color(0xFF2196F3)
val MeshBlueLight = Color(0xFF64B5F6)
val MeshOrange = Color(0xFFFF9800)
val MeshRed = Color(0xFFF44336)
val MeshGray = Color(0xFF9E9E9E)
val MeshDarkBackground = Color(0xFF121212)
val MeshSurface = Color(0xFF1E1E1E)
val MeshOnSurface = Color(0xFFE0E0E0)

// -------------------------------------------------------------------------
// WearOS Material Theme
// -------------------------------------------------------------------------

private val MeshCoreColors = Colors(
    primary = MeshGreen,
    primaryVariant = MeshGreenDark,
    secondary = MeshBlue,
    secondaryVariant = MeshBlueLight,
    background = MeshDarkBackground,
    surface = MeshSurface,
    error = MeshRed,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = MeshOnSurface,
    onError = Color.White
)

@Composable
fun MeshCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MeshCoreColors,
        content = content
    )
}
