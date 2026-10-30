package world.syncloom.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Indigo = Color(0xFF6A5CFF)
private val Teal = Color(0xFF12B5A6)
private val Light = lightColorScheme(primary = Indigo, secondary = Teal, tertiary = Teal)
private val Dark = darkColorScheme(primary = Color(0xFFB4ABFF), secondary = Color(0xFF58DDCE), tertiary = Teal)

/** Material You: follows the wallpaper colours on Android 12+, falls back to the Syncloom palette. */
@Composable
fun SyncloomTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
