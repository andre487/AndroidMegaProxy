package net.megaproxy487

import android.content.Context
import androidx.annotation.StringRes
import net.megaproxy487.model.ProxyProfile
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration

/** UI language selects words; the system locale selects number/date formatting. */
internal fun Context.uiText(@StringRes id: Int, vararg args: Any): String {
    val localized = if (resources.configuration.locales[0].language == AppLanguageManager.current(this).tag) {
        this
    } else AppLanguageManager.wrap(this)
    val template = localized.getString(id)
    return formatUiText(template, *args, locale = systemFormattingLocale())
}

internal fun ProxyProfile.localizedName(context: Context): String =
    name.trim().ifEmpty { config.host.trim().ifEmpty { context.uiText(R.string.profile_new) } }

internal fun ProxyProfile.localizedNameWithFlag(context: Context): String =
    listOf(flagEmoji, localizedName(context)).filter(String::isNotEmpty).joinToString(" ")

/** Expected input errors retain a stable resource ID and format arguments across layers. */
internal class UiException(@StringRes val textId: Int, vararg val arguments: Any) :
    IllegalArgumentException("UI error $textId")

internal fun Throwable.userMessage(context: Context, @StringRes fallback: Int): String =
    if (this is UiException) context.uiText(textId, *arguments) else context.uiText(fallback)

internal inline fun requireUi(condition: Boolean, error: () -> UiException = { UiException(R.string.error_invalid_input) }) {
    if (!condition) throw error()
}

@Composable
internal fun uiStringResource(@StringRes id: Int, vararg args: Any): String {
    // Read configuration so a language/configuration change invalidates Compose text.
    LocalConfiguration.current
    return LocalContext.current.uiText(id, *args)
}

internal fun Context.sshHopLabel(hop: String): String =
    uiText(if (hop == "jump") R.string.ssh_hop_jump else R.string.ssh_hop_destination)

/** Android may set the process default to the app language; use the device configuration. */
internal fun systemFormattingLocale(): java.util.Locale =
    android.content.res.Resources.getSystem().configuration.locales[0]
