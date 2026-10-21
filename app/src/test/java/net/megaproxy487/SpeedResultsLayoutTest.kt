package net.megaproxy487

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Test

class SpeedResultsLayoutTest : MainUiTestBase() {
    @Test fun narrowWidthAndLargeFontsSplitRoutesIntoSeparateBlocks() {
        val width = mutableStateOf(320)
        val fontScale = mutableStateOf(1f)
        content {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale.value)) {
                Box(Modifier.requiredWidth(width.value.dp)) { SpeedTestScreen(activity) {} }
            }
        }
        fun assertBlocks(count: Int) {
            compose.onAllNodesWithText(text(R.string.cloudflare_speed_download)).assertCountEquals(count)
            compose.onAllNodesWithText(text(R.string.external_underlying_network)).assertCountEquals(1)
            compose.onAllNodesWithText(text(R.string.external_tunnel_network)).assertCountEquals(1)
        }
        assertBlocks(2)
        compose.runOnIdle { width.value = 600 }
        assertBlocks(1)
        compose.runOnIdle { fontScale.value = 2f }
        assertBlocks(2)
    }
}
