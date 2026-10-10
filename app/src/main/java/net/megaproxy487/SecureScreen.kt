package net.megaproxy487

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

private val secureWindows = java.util.WeakHashMap<Window, Pair<Int, Boolean>>()

/** Navigation transitions can keep two credential screens composed at the same time. */
@Composable
internal fun SecureScreen(activity: Activity) {
    DisposableEffect(activity) {
        val window = activity.window
        val previous = secureWindows[window] ?: (0 to (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0))
        secureWindows[window] = previous.first + 1 to previous.second
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            val current = secureWindows.getValue(window)
            if (current.first > 1) secureWindows[window] = current.first - 1 to current.second
            else {
                secureWindows.remove(window)
                if (!current.second) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
