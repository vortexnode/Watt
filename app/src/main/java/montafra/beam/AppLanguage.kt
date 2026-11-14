package montafra.beam

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/** Locale override for Android versions without per-app language settings. */
fun appLanguageOverride(context: Context): Configuration? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
    val tag = context.getSharedPreferences(settingsName, Context.MODE_MULTI_PROCESS)
        .getString("appLanguage", null)
    if (tag.isNullOrEmpty()) return null
    return Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }
}

/** Migrate the old in-app language preference to Android's per-app language setting. */
fun migrateAppLanguage(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val prefs = context.getSharedPreferences(settingsName, Context.MODE_PRIVATE)
    val tag = prefs.getString("appLanguage", null)
    if (tag.isNullOrEmpty()) return
    val localeManager = context.getSystemService(LocaleManager::class.java)
    if (localeManager.applicationLocales.isEmpty) {
        localeManager.applicationLocales = LocaleList.forLanguageTags(tag)
    }
    prefs.edit().remove("appLanguage").apply()
}
