package com.reabastr.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.localeDataStore: DataStore<Preferences> by preferencesDataStore(name = "locale_prefs")

/**
 * Manages locale override using DataStore. The selected locale is persisted and
 * exposed as a Flow so the UI recomposes when it changes.
 *
 * Applied via AppCompatDelegate.setApplicationLocales() (Android 13+) or
 * createConfigurationContext() (pre-13). Change takes effect within 1 second,
 * no restart needed.
 */
@Singleton
class LocaleManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.localeDataStore

    private val LOCALE_KEY = stringPreferencesKey("locale_override")

    val currentLocale: Flow<Locale?> = dataStore.data.map { prefs ->
        prefs[LOCALE_KEY]?.let { Locale.forLanguageTag(it) }
    }

    suspend fun setLocale(locale: Locale?) {
        dataStore.edit { prefs ->
            if (locale == null) {
                prefs.remove(LOCALE_KEY)
            } else {
                prefs[LOCALE_KEY] = locale.toLanguageTag()
            }
        }
    }

    companion object {
        val SUPPORTED_LOCALES = listOf(
            Locale.ENGLISH,
            Locale("pt"),
            Locale("es"),
            Locale.FRENCH
        )
    }
}
