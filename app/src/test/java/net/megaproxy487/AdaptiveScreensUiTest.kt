package net.megaproxy487

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveScreensUiTest : MainUiTestBase() {
    private fun assertTextFits(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("No text layout for $label", layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("Clipped text height: $label", layout.didOverflowHeight)
            repeat(layout.lineCount) { line ->
                // Compare glyph width, allowing rounding to whole layout pixels.
                val width = layout.getLineRight(line) - layout.getLineLeft(line)
                assertTrue("Clipped text: $label ($width > ${layout.size.width})", width <= layout.size.width + 1f)
                assertFalse("Ellipsized text: $label", layout.isLineEllipsized(line))
            }
        }
    }

    @Test @Config(qualifiers = "ru-w320dp-h960dp")
    fun speedOptionsRemainReadableWithLargeFonts() {
        content {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                SpeedTestScreen(activity) {}
            }
        }
        SPEED_TEST_MEGABYTES.forEach { size -> assertTextFits(activity.uiText(R.string.cloudflare_speed_size, size)) }
        SPEED_TEST_PROTOCOLS.forEach(::assertTextFits)
        val first = compose.onNodeWithText(activity.uiText(R.string.cloudflare_speed_size, 10)).fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithText(activity.uiText(R.string.cloudflare_speed_size, 50)).fetchSemanticsNode().boundsInRoot
        assertTrue("Options must wrap on narrow windows", last.top > first.top)
    }

    @Test @Config(qualifiers = "ru-w320dp-h960dp")
    fun fieldLabelCanWrapWithLargeFonts() {
        val label = text(R.string.jump_private_key_optional)
        content {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                PasswordField("", {}, label, modifier = Modifier.fillMaxWidth())
            }
        }
        assertTextFits(label)
    }

    @Test @Config(qualifiers = "en-w1280dp-h900dp")
    fun profileListAndAddButtonShareCenteredWidth() {
        content { ProfilesScreen(activity, {}, {}) }
        val density = activity.resources.displayMetrics.density
        val list = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        val button = node(R.string.add_profile).getUnclippedBoundsInRoot()
        assertTrue("Profile list exceeds content limit", (list.right.value - list.left.value) <= 840f)
        assertTrue("Add button exceeds content limit", (button.right.value - button.left.value) <= 840f)
        assertEquals(640f, (list.left.value + list.right.value) / 2, 1f / density)
        assertEquals(640f, (button.left.value + button.right.value) / 2, 1f / density)
    }

    @Test @Config(qualifiers = "en-w1280dp-h900dp")
    fun editorFieldsShareCenteredWidth() {
        val id = store.activeProfileId()
        content { ProfileEditorScreen(activity, id, {}) }
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue(fields.isNotEmpty())
        val density = activity.resources.displayMetrics.density
        fields.forEach { field ->
            val bounds = field.boundsInRoot
            assertTrue("Editor field exceeds content limit", bounds.width / density <= 840f)
            assertEquals(640f, bounds.center.x / density, 1f)
        }
    }
}
