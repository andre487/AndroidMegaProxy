package net.megaproxy487

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal val LocalCompactKeyboard = compositionLocalOf { false }

/** Install once above navigation; outgoing screens must not remove the next screen's listener. */
@Composable
internal fun KeyboardAwareLayout(content: @Composable () -> Unit) {
    val root = LocalView.current.rootView
    val density = LocalDensity.current.density
    var compact by remember(root) { mutableStateOf(false) }
    DisposableEffect(root, density) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            compact = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0 &&
                root.height / density < 480
            insets
        }
        ViewCompat.requestApplyInsets(root)
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(root, null) }
    }
    CompositionLocalProvider(LocalCompactKeyboard provides compact, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditingTopAppBar(title: String, onBack: () -> Unit, compactKeyboard: Boolean = LocalCompactKeyboard.current) {
    if (!compactKeyboard) {
        TopAppBar(
            title = { ScreenTitle(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = uiStringResource(R.string.back),
                    )
                }
            },
        )
    }
}

/** Keep forms and result cards readable in large windows. */
@Composable
internal fun ScreenContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) { content() }
    }
}

/** Actions keep their natural touch targets and wrap instead of squeezing their neighbours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WrappingActions(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.End,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** Reserve enough text space at large font sizes before placing a trailing control inline. */
@Composable
internal fun AdaptiveLabelRow(
    modifier: Modifier = Modifier,
    minimumInlineWidth: Dp = 320.dp,
    label: @Composable (Modifier) -> Unit,
    trailing: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < minimumInlineWidth * LocalDensity.current.fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                label(Modifier.fillMaxWidth())
                trailing()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                label(Modifier.weight(1f))
                trailing()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveStatColumns(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / (132.dp * LocalDensity.current.fontScale)).toInt().coerceIn(1, 3)
        FlowRow(
            Modifier.fillMaxWidth(),
            maxItemsInEachRow = columns,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content(Modifier.weight(1f)) }
    }
}

/** App bars have a fixed height; the full title remains available to accessibility services. */
@Composable
internal fun ScreenTitle(title: String) {
    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text)
}

@Composable
internal fun ScrollableDialogText(text: String) {
    Text(text, modifier = Modifier.verticalScroll(rememberScrollState()))
}

@Composable
internal fun DialogTitle(title: String) {
    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
