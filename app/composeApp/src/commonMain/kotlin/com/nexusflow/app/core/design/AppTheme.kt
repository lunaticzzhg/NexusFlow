package com.nexusflow.app.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val appLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF75C84A),
        onPrimary = Color(0xFF132B17),
        primaryContainer = Color(0xFFE5F6D9),
        onPrimaryContainer = Color(0xFF245C25),
        secondary = Color(0xFF007FC4),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCEFFF),
        onSecondaryContainer = Color(0xFF00466D),
        tertiary = Color(0xFF526E8D),
        background = Color(0xFFF5F9FC),
        surface = Color.White,
        surfaceVariant = Color(0xFFE1EAF3),
        onBackground = Color(0xFF0D2B45),
        onSurface = Color(0xFF0D2B45),
        onSurfaceVariant = Color(0xFF5D82A3),
        error = Color(0xFFBA1A1A),
    )

private val appDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFB9F56C),
        onPrimary = Color(0xFF132B17),
        primaryContainer = Color(0xFF263D1A),
        onPrimaryContainer = Color(0xFFC8F99D),
        secondary = Color(0xFF79CCFF),
        onSecondary = Color(0xFF00263F),
        secondaryContainer = Color(0xFF16374D),
        onSecondaryContainer = Color(0xFFC7EAFF),
        tertiary = Color(0xFFA8C8E8),
        background = Color(0xFF0B1020),
        surface = Color(0xFF151F36),
        surfaceVariant = Color(0xFF1D2A46),
        onBackground = Color(0xFFF5F7FC),
        onSurface = Color(0xFFF5F7FC),
        onSurfaceVariant = Color(0xFF9BA9C3),
        error = Color(0xFFFFB4AB),
    )

private val appTypography =
    Typography(
        displaySmall = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
    )

private val appShapes =
    Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    )

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) appDarkColorScheme else appLightColorScheme,
        typography = appTypography,
        shapes = appShapes,
        content = content,
    )
}
