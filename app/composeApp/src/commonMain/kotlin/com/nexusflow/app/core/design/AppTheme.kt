package com.nexusflow.app.core.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Calm dark tokens aligned with the approved Orbit prototype. */
object AppColors {
    val Navy = Color(0xFF08131F)
    val Surface = Color(0xFF121F2C)
    val SurfaceVariant = Color(0xFF1C2C3B)
    val Text = Color(0xFFF1F5F9)
    val MutedText = Color(0xFFA8B6C6)
    val Lime = Color(0xFFC6F36B)
    val Sky = Color(0xFF82C7FF)
    val Amber = Color(0xFFF0B36B)
}

object AppSpacing {
    val compact = 12.dp
    val page = 24.dp
}

private val appDarkColors =
    darkColorScheme(
        primary = AppColors.Lime,
        onPrimary = AppColors.Navy,
        secondary = AppColors.Sky,
        tertiary = AppColors.Amber,
        background = AppColors.Navy,
        surface = AppColors.Surface,
        surfaceVariant = AppColors.SurfaceVariant,
        onBackground = AppColors.Text,
        onSurface = AppColors.Text,
        onSurfaceVariant = AppColors.MutedText,
    )

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = appDarkColors, content = content)
}
