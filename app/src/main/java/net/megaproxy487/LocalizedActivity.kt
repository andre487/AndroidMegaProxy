package net.megaproxy487

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import java.util.Locale

enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    RUSSIAN("ru"),
}

object AppLanguageManager {
    private const val PREFERENCES = "app_language"
    private const val KEY_LANGUAGE = "language"

    fun current(context: Context): AppLanguage {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val stored = preferences.getString(KEY_LANGUAGE, null)
        if (stored != null) return AppLanguage.entries.firstOrNull { it.tag == stored } ?: AppLanguage.ENGLISH
        val initial = if (Locale.getDefault().language == AppLanguage.RUSSIAN.tag) {
            AppLanguage.RUSSIAN
        } else {
            AppLanguage.ENGLISH
        }
        preferences.edit().putString(KEY_LANGUAGE, initial.tag).apply()
        return initial
    }

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language.tag)
            .apply()
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(current(context).tag)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}

abstract class LocalizedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep every screen on the same backward-compatible edge-to-edge path.
        // Individual Compose roots remain responsible for consuming system and IME insets.
        enableEdgeToEdge()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }
}
