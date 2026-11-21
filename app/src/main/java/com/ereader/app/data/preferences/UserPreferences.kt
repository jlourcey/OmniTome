package com.ereader.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// Creates a single DataStore instance tied to the app's lifecycle
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "omnitome_prefs")

/** App-level theme: follows the system, or force dark/light. */
enum class AppTheme { SYSTEM, LIGHT, DARK }

/**
 * UserPreferences — persistent key-value storage for app settings.
 *
 * DataStore is the modern replacement for SharedPreferences. It stores data
 * asynchronously and safely using Kotlin coroutines and Flow.
 *
 * Stored values:
 *  - Calibre connection settings (host, port, configured flag)
 *  - App theme (SYSTEM / LIGHT / DARK)
 *  - Reader defaults (font size, font family, theme)
 */
@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // ── Calibre ──────────────────────────────────────────────────────────
        private val KEY_CALIBRE_HOST       = stringPreferencesKey("calibre_host")
        private val KEY_CALIBRE_PORT       = stringPreferencesKey("calibre_port")
        private val KEY_CALIBRE_CONFIGURED = booleanPreferencesKey("calibre_configured")
        private val KEY_CALIBRE_USERNAME   = stringPreferencesKey("calibre_username")
        private val KEY_CALIBRE_PASSWORD   = stringPreferencesKey("calibre_password")

        // ── Calibre libraries (one login can see several) ─────────────────────
        /** Library the user picked in the bookshelf switch (absent = server default). */
        private val KEY_ACTIVE_CALIBRE_LIB  = stringPreferencesKey("active_calibre_library")
        /** The login's default library, as last reported by Calibre. */
        private val KEY_DEFAULT_CALIBRE_LIB = stringPreferencesKey("default_calibre_library")
        /** JSON list of {"id","name"} the login can access, cached for offline use. */
        private val KEY_KNOWN_CALIBRE_LIBS  = stringPreferencesKey("known_calibre_libraries")

        // ── Onboarding ────────────────────────────────────────────────────────
        private val KEY_HAS_SEEN_WELCOME   = booleanPreferencesKey("has_seen_welcome")

        // ── App appearance ────────────────────────────────────────────────────
        private val KEY_APP_THEME          = stringPreferencesKey("app_theme")

        // ── Reader defaults ───────────────────────────────────────────────────
        private val KEY_READER_FONT_SIZE   = intPreferencesKey("reader_font_size")
        private val KEY_READER_FONT_FAMILY = stringPreferencesKey("reader_font_family")
        private val KEY_READER_THEME       = stringPreferencesKey("reader_theme")
    }

    // ── Calibre reads ─────────────────────────────────────────────────────────

    val calibreHost: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_CALIBRE_HOST] ?: "" }

    val calibrePort: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_CALIBRE_PORT] ?: "8080" }

    val calibreConfigured: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[KEY_CALIBRE_CONFIGURED] ?: false }

    /** Full base URL, e.g.  http://192.168.68.60:8080 */
    val calibreBaseUrl: Flow<String> = context.dataStore.data
        .map { prefs ->
            val host = prefs[KEY_CALIBRE_HOST] ?: ""
            val port = prefs[KEY_CALIBRE_PORT] ?: "8080"
            if (host.isBlank()) "" else "http://$host:$port"
        }

    /** Calibre Content Server username (empty = no auth configured). */
    val calibreUsername: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_CALIBRE_USERNAME] ?: "" }

    /** Calibre Content Server password. */
    val calibrePassword: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_CALIBRE_PASSWORD] ?: "" }

    // ── Calibre library reads ─────────────────────────────────────────────────

    /** Library picked in the bookshelf switch, or null for the login's default. */
    val activeCalibreLibraryId: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[KEY_ACTIVE_CALIBRE_LIB] }

    /** The login's default library id (null until the first successful sync). */
    val defaultCalibreLibraryId: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[KEY_DEFAULT_CALIBRE_LIB] }

    /** The library actually in use: the picked one, else the default. */
    val effectiveCalibreLibraryId: Flow<String?> = context.dataStore.data
        .map { prefs -> prefs[KEY_ACTIVE_CALIBRE_LIB] ?: prefs[KEY_DEFAULT_CALIBRE_LIB] }

    /** (id, display name) of every library this login can open, in Calibre's order. */
    val knownCalibreLibraries: Flow<List<Pair<String, String>>> = context.dataStore.data
        .map { prefs ->
            runCatching {
                val arr = JSONArray(prefs[KEY_KNOWN_CALIBRE_LIBS] ?: "[]")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    o.getString("id") to o.optString("name", o.getString("id"))
                }
            }.getOrDefault(emptyList())
        }

    /** True once the user has seen (and dismissed) the welcome / tour screen. */
    val hasSeenWelcome: Flow<Boolean> = context.dataStore.data
        .map { prefs -> prefs[KEY_HAS_SEEN_WELCOME] ?: false }

    // ── App theme reads ───────────────────────────────────────────────────────

    val appTheme: Flow<AppTheme> = context.dataStore.data
        .map { prefs ->
            when (prefs[KEY_APP_THEME]) {
                "LIGHT" -> AppTheme.LIGHT
                "DARK"  -> AppTheme.DARK
                else    -> AppTheme.SYSTEM
            }
        }

    // ── Reader defaults reads ─────────────────────────────────────────────────

    val readerFontSize: Flow<Int> = context.dataStore.data
        .map { prefs -> prefs[KEY_READER_FONT_SIZE] ?: 16 }

    val readerFontFamily: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_READER_FONT_FAMILY] ?: "SERIF" }

    val readerTheme: Flow<String> = context.dataStore.data
        .map { prefs -> prefs[KEY_READER_THEME] ?: "DAY" }

    // ── Calibre writes ────────────────────────────────────────────────────────

    suspend fun saveCalibreSettings(
        host: String,
        port: String = "8080",
        username: String = "",
        password: String = ""
    ) {
        context.dataStore.edit { prefs ->
            // A different server or login can see different libraries, so forget
            // the library choice; it is re-read from Calibre on the next sync.
            val serverChanged = prefs[KEY_CALIBRE_HOST]     != host.trim() ||
                                prefs[KEY_CALIBRE_PORT]     != port.trim() ||
                                prefs[KEY_CALIBRE_USERNAME] != username.trim()
            if (serverChanged) {
                prefs.remove(KEY_ACTIVE_CALIBRE_LIB)
                prefs.remove(KEY_DEFAULT_CALIBRE_LIB)
                prefs.remove(KEY_KNOWN_CALIBRE_LIBS)
            }
            prefs[KEY_CALIBRE_HOST]       = host.trim()
            prefs[KEY_CALIBRE_PORT]       = port.trim()
            prefs[KEY_CALIBRE_CONFIGURED] = true
            prefs[KEY_CALIBRE_USERNAME]   = username.trim()
            prefs[KEY_CALIBRE_PASSWORD]   = password          // don't trim passwords
        }
    }

    /** Called when the user taps "Skip" on the setup screen — marks Calibre as
     *  "configured" with an empty host so the app never redirects back to the setup
     *  screen, but leaves the base URL blank so Calibre-dependent features gracefully
     *  report that no server is connected. */
    suspend fun skipCalibreSetup() {
        context.dataStore.edit { prefs ->
            prefs[KEY_CALIBRE_CONFIGURED] = true
            // Leave host/port/credentials absent — standalone mode
        }
    }

    suspend fun clearCalibreSettings() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_CALIBRE_HOST)
            prefs.remove(KEY_CALIBRE_PORT)
            prefs.remove(KEY_CALIBRE_USERNAME)
            prefs.remove(KEY_CALIBRE_PASSWORD)
            prefs.remove(KEY_ACTIVE_CALIBRE_LIB)
            prefs.remove(KEY_DEFAULT_CALIBRE_LIB)
            prefs.remove(KEY_KNOWN_CALIBRE_LIBS)
            prefs[KEY_CALIBRE_CONFIGURED] = false
        }
    }

    // ── Calibre library writes ────────────────────────────────────────────────

    /** Bookshelf switch: pick a library (null = go back to the login's default). */
    suspend fun setActiveCalibreLibrary(libraryId: String?) {
        context.dataStore.edit { prefs ->
            if (libraryId == null) prefs.remove(KEY_ACTIVE_CALIBRE_LIB)
            else prefs[KEY_ACTIVE_CALIBRE_LIB] = libraryId
        }
    }

    /** Caches the library list Calibre reported for this login. */
    suspend fun saveCalibreLibraryInfo(libraries: List<Pair<String, String>>, defaultId: String) {
        val json = JSONArray().apply {
            libraries.forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) }
        }.toString()
        context.dataStore.edit { prefs ->
            prefs[KEY_KNOWN_CALIBRE_LIBS]  = json
            prefs[KEY_DEFAULT_CALIBRE_LIB] = defaultId
        }
    }

    // ── Onboarding writes ─────────────────────────────────────────────────────

    suspend fun markWelcomeSeen() {
        context.dataStore.edit { prefs -> prefs[KEY_HAS_SEEN_WELCOME] = true }
    }

    // ── App theme writes ──────────────────────────────────────────────────────

    suspend fun saveAppTheme(theme: AppTheme) {
        context.dataStore.edit { prefs -> prefs[KEY_APP_THEME] = theme.name }
    }

    // ── Reader defaults writes ────────────────────────────────────────────────

    suspend fun saveReaderFontSize(sp: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_READER_FONT_SIZE] = sp }
    }

    suspend fun saveReaderFontFamily(family: String) {
        context.dataStore.edit { prefs -> prefs[KEY_READER_FONT_FAMILY] = family }
    }

    suspend fun saveReaderTheme(theme: String) {
        context.dataStore.edit { prefs -> prefs[KEY_READER_THEME] = theme }
    }
}
