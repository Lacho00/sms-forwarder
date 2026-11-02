package dev.smsforwarder.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Per-app language without LocaleManager/AppCompatDelegate (see spec section 15).
 * The chosen tag is mirrored in SharedPreferences so attachBaseContext can read it without waiting for DataStore.
 */
object Localized {
    private const val PREFS = "ui_prefs"
    private const val KEY = "lang"

    /** Returns [base] when [tag] is null (follow the system); otherwise a context whose resources use [tag]. */
    fun wrap(base: Context, tag: String?): Context {
        if (tag == null) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }

    fun savedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun saveTag(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tag == null) remove(KEY) else putString(KEY, tag)
        }.apply()
    }
}
