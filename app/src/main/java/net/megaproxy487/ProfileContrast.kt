package net.megaproxy487

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Choose the stronger contrast against an opaque profile color. */
internal fun profileForeground(background: Color): Color {
    val luminance = background.luminance()
    val blackContrast = (luminance + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (luminance + 0.05f)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}
