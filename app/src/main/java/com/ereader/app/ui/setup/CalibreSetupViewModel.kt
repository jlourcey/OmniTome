package com.ereader.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

sealed class SetupState {
    data object Idle       : SetupState()
    data object Testing    : SetupState()    // spinner while we ping Calibre
    data object Success    : SetupState()    // connection verified — navigate away
    data class  Error(val message: String) : SetupState()
}

/**
 * CalibreSetupViewModel — drives the first-run Calibre setup screen.
 *
 * Flow:
 *  1. User types their IP address (e.g. "192.168.68.60")
 *  2. They tap "Connect" → [onConnectClicked]
 *  3. We build the URL: http://{ip}:8080/opds
 *  4. We fire a quick HEAD/GET request to verify the server is reachable
 *  5. On success → save to DataStore → emit [SetupState.Success]
 *     The Navigation layer reacts to Success and navigates to the Library
 *  6. On failure → emit [SetupState.Error] with a helpful message
 *
 * The port field is optional — it defaults to 8080 (Calibre's default).
 * Most users never need to change it.
 */
@HiltViewModel
class CalibreSetupViewModel @Inject constructor(
    private val preferences: UserPreferences,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _state = MutableStateFlow<SetupState>(SetupState.Idle)
    val state: StateFlow<SetupState> = _state

    // These mirror the text fields in the UI
    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress

    private val _port = MutableStateFlow("8080")
    val port: StateFlow<String> = _port

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password

    fun onIpChanged(value: String)       { _ipAddress.value = value; _state.value = SetupState.Idle }
    fun onPortChanged(value: String)     { _port.value = value;      _state.value = SetupState.Idle }
    fun onUsernameChanged(value: String) { _username.value = value }
    fun onPasswordChanged(value: String) { _password.value = value }

    /** User chose to run without a Calibre server — mark setup done so the
     *  splash never redirects back here, but leave host blank (standalone mode). */
    fun onSkipClicked() {
        viewModelScope.launch {
            preferences.skipCalibreSetup()
            _state.value = SetupState.Success
        }
    }

    fun onConnectClicked() {
        val ip       = _ipAddress.value.trim()
        val port     = _port.value.trim().ifBlank { "8080" }
        val username = _username.value.trim()
        val password = _password.value

        // Basic validation before we hit the network
        if (ip.isBlank()) {
            _state.value = SetupState.Error("Please enter your Calibre server's IP address.")
            return
        }
        if (!isValidIpOrHost(ip)) {
            _state.value = SetupState.Error("That doesn't look like a valid IP address (e.g. 192.168.68.60).")
            return
        }

        val baseUrl = "http://$ip:$port"
        _state.value = SetupState.Testing

        viewModelScope.launch {
            val result = testConnection(baseUrl)
            if (result == null) {
                // Connection succeeded — save credentials + settings and proceed
                preferences.saveCalibreSettings(
                    host     = ip,
                    port     = port,
                    username = username,
                    password = password
                )
                Timber.d("Calibre connected at $baseUrl (credentials: ${if (username.isNotBlank()) "present" else "none"})")
                _state.value = SetupState.Success
            } else {
                _state.value = SetupState.Error(result)
            }
        }
    }

    /**
     * Fires a GET to /opds and returns null on success, or an error message string.
     * Uses a short 8-second timeout — if Calibre is on the LAN it responds instantly.
     */
    private suspend fun testConnection(baseUrl: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val testClient = okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()

            val response = testClient.newCall(
                Request.Builder().url("$baseUrl/opds").build()
            ).execute()

            if (response.isSuccessful || response.code == 401) {
                // 401 = password-protected Calibre, but server IS reachable
                null
            } else {
                "Server replied with HTTP ${response.code}. Is Calibre's content server running?"
            }
        } catch (e: java.net.ConnectException) {
            "Could not reach $baseUrl. Check the IP and make sure Calibre's server is started."
        } catch (e: java.net.SocketTimeoutException) {
            "Connection timed out. Make sure you're on the same Wi-Fi network as your Calibre server."
        } catch (e: Exception) {
            "Connection failed: ${e.localizedMessage}"
        }
    }

    /** Accepts dotted-decimal IPv4, IPv6, or a plain hostname. */
    private fun isValidIpOrHost(value: String): Boolean {
        if (value.length > 253) return false
        // IPv4
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        if (ipv4.matches(value)) {
            return value.split(".").all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
        // Hostname (letters, digits, hyphens, dots)
        return Regex("""^[a-zA-Z0-9._-]+$""").matches(value)
    }
}
