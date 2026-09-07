package net.megaproxy487

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import net.megaproxy487.model.ProfileColors
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileContrastTest {
    @Test
    fun allProfileColorsMeetSmallTextContrast() {
        for (argb in ProfileColors.argb) {
            val background = Color(argb)
            val foreground = profileForeground(background)
            val light = maxOf(background.luminance(), foreground.luminance())
            val dark = minOf(background.luminance(), foreground.luminance())
            assertTrue("Profile color $argb must meet 4.5:1", (light + 0.05f) / (dark + 0.05f) >= 4.5f)
        }
    }
}
