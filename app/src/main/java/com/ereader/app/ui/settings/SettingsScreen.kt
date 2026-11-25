package com.ereader.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.preferences.AppTheme
import com.ereader.app.data.preferences.UserPreferences
import com.ereader.app.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// ── Calibre connection test state ─────────────────────────────────────────────

/**
 * Tracks the result of tapping "Test Connection" in the Calibre settings card.
 *
 *  Idle    — no test running or result to show yet
 *  Testing — spinner shown while we ping the server
 *  Success — server responded and accepted the credentials (or no auth needed)
 *  Error   — something went wrong; [message] explains exactly what
 */
sealed class CalibreTestState {
    data object Idle    : CalibreTestState()
    data object Testing : CalibreTestState()
    data class  Success(val message: String) : CalibreTestState()
    data class  Error  (val message: String) : CalibreTestState()
}

// ── Theme colors (consistent with bookshelf) ──────────────────────────────────
private val SettingsBg       = Color(0xFF060D30)
private val SettingsCard     = Color(0xFF0D1848)
private val SettingsDivider  = Color(0xFF1A2560)
private val GoldAccent       = Color(0xFFDDBB3A)
private val TextPrimary      = Color.White
private val TextSecondary    = Color.White.copy(alpha = 0.6f)

// ── ViewModel ─────────────────────────────────────────────────────────────────

data class SettingsUiState(
    // Calibre
    val calibreHost: String        = "",
    val calibrePort: String        = "8080",
    val calibreConfigured: Boolean = false,
    val calibreUsername: String    = "",
    val calibrePassword: String    = "",
    // App
    val appTheme: AppTheme         = AppTheme.SYSTEM,
    // Reader
    val readerFontSize: Int        = 16,
    val readerFontFamily: String   = "SERIF",
    val readerTheme: String        = "DAY",
    // Storage
    val cacheSizeBytes: Long       = 0L,
    val localBookCount: Int        = 0
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val repository: BookRepository,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    // ── Connection test state (separate from main uiState for simplicity) ─────
    private val _testState = MutableStateFlow<CalibreTestState>(CalibreTestState.Idle)
    val testState: StateFlow<CalibreTestState> = _testState.asStateFlow()

    // Nested combines — Kotlin's combine() natively handles up to 5 flows.
    // We merge sub-groups first, then combine those into the final state.

    private val calibreStream = combine(
        prefs.calibreHost, prefs.calibrePort, prefs.calibreConfigured
    ) { host, port, configured -> Triple(host, port, configured) }

    private val calibreAuthStream = combine(
        prefs.calibreUsername, prefs.calibrePassword
    ) { username, password -> Pair(username, password) }

    private val readerStream = combine(
        prefs.readerFontSize, prefs.readerFontFamily, prefs.readerTheme
    ) { size, family, theme -> Triple(size, family, theme) }

    val uiState: StateFlow<SettingsUiState> = combine(
        calibreStream,
        calibreAuthStream,
        prefs.appTheme,
        readerStream,
        repository.observeLocalBooks()
    ) { calibre, auth, appTheme, reader, localBooks ->
        SettingsUiState(
            calibreHost       = calibre.first,
            calibrePort       = calibre.second,
            calibreConfigured = calibre.third,
            calibreUsername   = auth.first,
            calibrePassword   = auth.second,
            appTheme          = appTheme,
            readerFontSize    = reader.first,
            readerFontFamily  = reader.second,
            readerTheme       = reader.third,
            localBookCount    = localBooks.size
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun saveCalibre(host: String, port: String, username: String, password: String) {
        viewModelScope.launch { prefs.saveCalibreSettings(host, port, username, password) }
    }

    fun saveAppTheme(theme: AppTheme) {
        viewModelScope.launch { prefs.saveAppTheme(theme) }
    }

    fun saveReaderFontSize(sp: Int) {
        viewModelScope.launch { prefs.saveReaderFontSize(sp) }
    }

    /** Settings → Reader Defaults → "Turn pages with volume keys". */
    val volumeKeyPageTurns: StateFlow<Boolean> = prefs.volumeKeyPageTurns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun saveVolumeKeyPageTurns(on: Boolean) {
        viewModelScope.launch { prefs.saveVolumeKeyPageTurns(on) }
    }

    /** Settings → App Appearance → "Show cover badges". */
    val coverBadgesEnabled: StateFlow<Boolean> = prefs.coverBadgesEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun saveCoverBadgesEnabled(on: Boolean) {
        viewModelScope.launch { prefs.saveCoverBadgesEnabled(on) }
    }

    fun saveReaderFontFamily(family: String) {
        viewModelScope.launch { prefs.saveReaderFontFamily(family) }
    }

    fun saveReaderTheme(theme: String) {
        viewModelScope.launch { prefs.saveReaderTheme(theme) }
    }

    /**
     * Pings the Calibre server using the credentials currently TYPED in the
     * text fields (not what's saved in DataStore yet) and reports exactly what
     * went wrong.
     *
     * Steps:
     *  1. GET /opds with no auth → if 200, done (no password configured).
     *  2. If 401, build a one-off OkHttp client with an inline Digest/Basic
     *     authenticator using the provided credentials and retry.
     *  3. Report a specific, human-readable message for every failure case.
     */
    fun testCalibreConnection(host: String, port: String, username: String, password: String) {
        if (_testState.value == CalibreTestState.Testing) return
        viewModelScope.launch {
            _testState.value = CalibreTestState.Testing
            _testState.value = withContext(Dispatchers.IO) {
                val ip  = host.trim()
                val prt = port.trim().ifBlank { "8080" }
                if (ip.isBlank()) {
                    return@withContext CalibreTestState.Error("Enter an IP address first.")
                }
                val baseUrl = "http://$ip:$prt"

                try {
                    // Short timeout — if Calibre is on the LAN it replies in < 1 second
                    val shortClient = okHttpClient.newBuilder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()

                    // ── Pass 1: unauthenticated ────────────────────────────────
                    val r1 = shortClient.newCall(
                        Request.Builder().url("$baseUrl/opds").build()
                    ).execute()

                    when {
                        r1.isSuccessful ->
                            return@withContext CalibreTestState.Success(
                                "Connected to $baseUrl — no password required."
                            )

                        r1.code == 401 -> {
                            // ── Pass 2: authenticate ───────────────────────────
                            val wwwAuth = r1.header("WWW-Authenticate") ?: ""
                            if (username.isBlank()) {
                                return@withContext CalibreTestState.Error(
                                    "Server at $baseUrl requires a username and password.\n" +
                                    "Enter them above and tap Test again."
                                )
                            }

                            val authClient = shortClient.newBuilder()
                                .authenticator { _, response ->
                                    // Stop if we already tried — avoids infinite loops
                                    if (response.request.header("Authorization") != null) return@authenticator null
                                    val challenge = response.header("WWW-Authenticate") ?: return@authenticator null
                                    when {
                                        challenge.startsWith("Digest", ignoreCase = true) ->
                                            buildTestDigestRequest(response, challenge, username, password)
                                        challenge.startsWith("Basic", ignoreCase = true) ->
                                            response.request.newBuilder()
                                                .header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                                                .build()
                                        else -> null  // unsupported scheme
                                    }
                                }
                                .build()

                            val r2 = authClient.newCall(
                                Request.Builder().url("$baseUrl/opds").build()
                            ).execute()

                            when {
                                r2.isSuccessful ->
                                    CalibreTestState.Success(
                                        "Connected to $baseUrl as '$username' ✓"
                                    )
                                r2.code == 401 ->
                                    CalibreTestState.Error(
                                        "Wrong username or password — Calibre rejected the credentials.\n" +
                                        "Double-check them in Calibre → Preferences → Sharing → Content Server."
                                    )
                                else ->
                                    CalibreTestState.Error(
                                        "Unexpected response HTTP ${r2.code} after authentication."
                                    )
                            }
                        }

                        else ->
                            CalibreTestState.Error(
                                "Server replied with HTTP ${r1.code}. " +
                                "Is the Calibre content server running?"
                            )
                    }

                } catch (e: java.net.ConnectException) {
                    CalibreTestState.Error(
                        "Cannot reach $ip:$prt.\n" +
                        "• Check the IP address\n" +
                        "• Make sure Calibre's content server is started\n" +
                        "• Confirm your phone is on the same Wi-Fi as your Calibre PC"
                    )
                } catch (e: java.net.SocketTimeoutException) {
                    CalibreTestState.Error(
                        "Connection timed out — make sure your phone is on the same\n" +
                        "Wi-Fi network as your Calibre PC."
                    )
                } catch (e: java.net.UnknownHostException) {
                    CalibreTestState.Error(
                        "Unknown host '$ip' — enter a numeric IP like 192.168.1.100."
                    )
                } catch (e: Exception) {
                    CalibreTestState.Error("Connection error: ${e.localizedMessage}")
                }
            }
        }
    }

    fun resetTestState() { _testState.value = CalibreTestState.Idle }

    // ── Digest auth helpers (used only by the test — production uses CalibreDigestAuthenticator) ──

    private fun buildTestDigestRequest(
        response: okhttp3.Response,
        wwwAuth: String,
        username: String,
        password: String
    ): Request? {
        val realm  = testExtractParam(wwwAuth, "realm")  ?: return null
        val nonce  = testExtractParam(wwwAuth, "nonce")  ?: return null
        val qop    = testExtractParam(wwwAuth, "qop")
        val opaque = testExtractParam(wwwAuth, "opaque")
        val uri    = response.request.url.let { u ->
            val q = u.encodedQuery; if (q != null) "${u.encodedPath}?$q" else u.encodedPath
        }
        val method  = response.request.method
        val cnonce  = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val nc      = "00000001"
        val ha1     = testMd5("$username:$realm:$password")
        val ha2     = testMd5("$method:$uri")
        val resp    = if (qop != null) testMd5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
                      else testMd5("$ha1:$nonce:$ha2")
        val authVal = buildString {
            append("""Digest username="$username", realm="$realm", nonce="$nonce", uri="$uri", algorithm=MD5""")
            if (qop    != null) append(""", qop=$qop, nc=$nc, cnonce="$cnonce"""")
            if (opaque != null) append(""", opaque="$opaque"""")
            append(""", response="$resp"""")
        }
        return response.request.newBuilder().header("Authorization", authVal).build()
    }

    private fun testMd5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun testExtractParam(header: String, name: String): String? {
        Regex("""$name\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.let { return it }
        return Regex("""$name\s*=\s*([^,\s"]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state     by viewModel.uiState.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text       = "Settings",
                        fontWeight = FontWeight.Bold,
                        color      = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A1540)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(SettingsBg)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp)
        ) {
            // ── Section 1: Calibre Server ─────────────────────────────────
            SettingsSectionHeader(
                icon  = Icons.Default.Wifi,
                title = "Calibre Server"
            )
            CalibreSettingsCard(
                host      = state.calibreHost,
                port      = state.calibrePort,
                username  = state.calibreUsername,
                password  = state.calibrePassword,
                testState = testState,
                onSave    = { h, p, u, pw -> viewModel.saveCalibre(h, p, u, pw) },
                onTest    = { h, p, u, pw -> viewModel.testCalibreConnection(h, p, u, pw) },
                onTestDismiss = { viewModel.resetTestState() }
            )

            Spacer(Modifier.height(24.dp))

            // ── Section 1b: Read Tracking (which Calibre columns to use) ────
            SettingsSectionHeader(
                icon  = Icons.Default.TaskAlt,
                title = "Read Tracking"
            )
            ReadTrackingCard()

            Spacer(Modifier.height(24.dp))

            // ── Section 2: App Appearance ──────────────────────────────────
            SettingsSectionHeader(
                icon  = Icons.Default.Palette,
                title = "App Appearance"
            )
            AppThemeCard(
                current  = state.appTheme,
                onChange = viewModel::saveAppTheme
            )
            Spacer(Modifier.height(12.dp))
            val coverBadges by viewModel.coverBadgesEnabled.collectAsState()
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show cover badges", color = Color.White, fontSize = 14.sp)
                        Text("Checkmark = Read, NEW = added to Calibre in the last 14 days " +
                             "and not read yet, flame = rated 5 stars in Calibre.",
                            color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = coverBadges,
                        onCheckedChange = viewModel::saveCoverBadgesEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = GoldAccent)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Section 3: Reader Defaults ─────────────────────────────────
            SettingsSectionHeader(
                icon  = Icons.Default.MenuBook,
                title = "Reader Defaults"
            )
            ReaderDefaultsCard(
                fontSize     = state.readerFontSize,
                fontFamily   = state.readerFontFamily,
                readerTheme  = state.readerTheme,
                onFontSize   = viewModel::saveReaderFontSize,
                onFamily     = viewModel::saveReaderFontFamily,
                onTheme      = viewModel::saveReaderTheme
            )
            Spacer(Modifier.height(12.dp))
            val volumeKeys by viewModel.volumeKeyPageTurns.collectAsState()
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Turn pages with volume keys", color = Color.White, fontSize = 14.sp)
                        Text("Volume Down = next page, Volume Up = previous page. " +
                             "While reading aloud, the keys still change the volume.",
                            color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = volumeKeys,
                        onCheckedChange = viewModel::saveVolumeKeyPageTurns,
                        colors = SwitchDefaults.colors(checkedTrackColor = GoldAccent)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Section 4: Storage & Cache ─────────────────────────────────
            SettingsSectionHeader(
                icon  = Icons.Default.Storage,
                title = "Storage"
            )
            StorageCard(localBookCount = state.localBookCount)

            Spacer(Modifier.height(24.dp))

            // ── Section 5: About ───────────────────────────────────────────
            SettingsSectionHeader(
                icon  = Icons.Default.Info,
                title = "About"
            )
            AboutCard()

            Spacer(Modifier.height(24.dp))

            // ── Section 6: FAQ & Help ──────────────────────────────────────
            SettingsSectionHeader(
                icon  = Icons.Default.Help,
                title = "FAQ & Help"
            )
            // Replay the welcome tour (it only shows by itself on first launch).
            run {
                var showTour by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick  = { showTour = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDDBB3A))
                ) {
                    Icon(Icons.Default.Explore, contentDescription = null, tint = Color(0xFFDDBB3A))
                    Spacer(Modifier.width(8.dp))
                    Text("Take the Tour", color = Color(0xFFDDBB3A))
                }
                if (showTour) {
                    com.ereader.app.ui.welcome.TourDialog(
                        onFinish  = { showTour = false },
                        onDismiss = { showTour = false }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            FaqCard()

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Section header ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = GoldAccent,
            modifier           = Modifier.size(18.dp)
        )
        Text(
            text       = title,
            color      = GoldAccent,
            fontSize   = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

// ── Calibre settings card ──────────────────────────────────────────────────────

@Composable
private fun CalibreSettingsCard(
    host: String,
    port: String,
    username: String,
    password: String,
    testState: CalibreTestState,
    onSave: (String, String, String, String) -> Unit,
    onTest: (String, String, String, String) -> Unit,
    onTestDismiss: () -> Unit
) {
    var editHost     by remember(host)     { mutableStateOf(host) }
    var editPort     by remember(port)     { mutableStateOf(port) }
    var editUsername by remember(username) { mutableStateOf(username) }
    var editPassword by remember(password) { mutableStateOf(password) }
    var showPassword by remember { mutableStateOf(false) }
    var saved        by remember { mutableStateOf(false) }

    SettingsCard {
        Text("Server IP address", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value         = editHost,
            onValueChange = { editHost = it; saved = false },
            placeholder   = { Text("e.g. 192.168.1.100", color = TextSecondary) },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction    = ImeAction.Next
            ),
            colors   = calibreFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        Text("Port", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value         = editPort,
            onValueChange = { editPort = it; saved = false },
            placeholder   = { Text("8080", color = TextSecondary) },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction    = ImeAction.Next
            ),
            colors   = calibreFieldColors(),
            modifier = Modifier.fillMaxWidth(0.4f)
        )

        Spacer(Modifier.height(14.dp))

        // ── Optional credentials ─────────────────────────────────────────
        // Required when Calibre → Preferences → Sharing → Content server
        // has "Require username and password" enabled.
        Text("Username (optional)", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value         = editUsername,
            onValueChange = { editUsername = it; saved = false },
            placeholder   = { Text("Calibre username", color = TextSecondary) },
            singleLine    = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction    = ImeAction.Next
            ),
            colors   = calibreFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        Text("Password (optional)", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value         = editPassword,
            onValueChange = { editPassword = it; saved = false },
            placeholder   = { Text("Calibre password", color = TextSecondary) },
            singleLine    = true,
            visualTransformation = if (showPassword) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction    = ImeAction.Done
            ),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff
                                      else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = TextSecondary
                    )
                }
            },
            colors   = calibreFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))

        // ── Save + Test buttons ──────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    onSave(editHost, editPort, editUsername, editPassword)
                    saved = true
                },
                enabled = editHost.isNotBlank() && editPort.isNotBlank(),
                colors  = ButtonDefaults.buttonColors(
                    containerColor = GoldAccent,
                    contentColor   = Color(0xFF060D30)
                )
            ) {
                Text(if (saved) "Saved ✓" else "Save", fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = {
                    saved = false           // clear old "Saved ✓" if user is re-testing
                    onTest(editHost, editPort, editUsername, editPassword)
                },
                enabled = editHost.isNotBlank() && editPort.isNotBlank()
                        && testState != CalibreTestState.Testing,
                border = androidx.compose.foundation.BorderStroke(1.dp, GoldAccent)
            ) {
                if (testState == CalibreTestState.Testing) {
                    CircularProgressIndicator(
                        modifier  = Modifier.size(14.dp),
                        color     = GoldAccent,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Testing…", color = GoldAccent, fontWeight = FontWeight.Bold)
                } else {
                    Text("Test", color = GoldAccent, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Test result banner ───────────────────────────────────────────────
        when (val ts = testState) {
            is CalibreTestState.Success -> {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A3A1A))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable { onTestDismiss() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint     = Color(0xFF66BB6A),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(ts.message, color = Color(0xFF66BB6A), fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                }
            }
            is CalibreTestState.Error -> {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A1A1A))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clickable { onTestDismiss() },
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint     = Color(0xFFEF5350),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(ts.message, color = Color(0xFFEF5350), fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                }
            }
            else -> { /* Idle or Testing — nothing to show */ }
        }
    }
}

@Composable
private fun calibreFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor    = TextPrimary,
    unfocusedTextColor  = TextPrimary,
    focusedBorderColor  = GoldAccent,
    unfocusedBorderColor = SettingsDivider,
    cursorColor         = GoldAccent
)

// ── App theme card ─────────────────────────────────────────────────────────────

@Composable
private fun AppThemeCard(
    current: AppTheme,
    onChange: (AppTheme) -> Unit
) {
    SettingsCard {
        Text(
            text  = "Theme",
            color = TextSecondary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppTheme.entries.forEach { theme ->
                val label = when (theme) {
                    AppTheme.SYSTEM -> "System"
                    AppTheme.LIGHT  -> "Light"
                    AppTheme.DARK   -> "Dark"
                }
                FilterChip(
                    selected = current == theme,
                    onClick  = { onChange(theme) },
                    label    = { Text(label) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GoldAccent,
                        selectedLabelColor     = Color(0xFF060D30)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ── Reader defaults card ───────────────────────────────────────────────────────

@Composable
private fun ReaderDefaultsCard(
    fontSize: Int,
    fontFamily: String,
    readerTheme: String,
    onFontSize: (Int) -> Unit,
    onFamily: (String) -> Unit,
    onTheme: (String) -> Unit
) {
    SettingsCard {
        // Font size
        Text("Default font size", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.TextDecrease, contentDescription = null, tint = TextSecondary)
            Slider(
                value         = fontSize.toFloat(),
                onValueChange = { onFontSize(it.toInt()) },
                valueRange    = 12f..28f,
                steps         = 7,
                modifier      = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors        = SliderDefaults.colors(
                    thumbColor       = GoldAccent,
                    activeTrackColor = GoldAccent
                )
            )
            Icon(Icons.Default.TextIncrease, contentDescription = null, tint = TextSecondary)
            Spacer(Modifier.width(6.dp))
            Text("${fontSize}sp", color = GoldAccent, fontSize = 12.sp)
        }

        Spacer(Modifier.height(14.dp))

        // Font family
        Text("Default font", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("SERIF", "SANS", "MONO", "DYSLEXIC").forEach { family ->
                FilterChip(
                    selected = fontFamily == family,
                    onClick  = { onFamily(family) },
                    // DYSLEXIC = OpenDyslexic, a font designed to be easier to read with dyslexia
                    label    = { Text(family.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GoldAccent,
                        selectedLabelColor     = Color(0xFF060D30)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Reader theme
        Text("Default reader theme", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "DAY"   to Color(0xFFF5F0E8),
                "SEPIA" to Color(0xFFF1E7D0),
                "PAPER" to Color(0xFFF0EBE0),
                "NIGHT" to Color(0xFF1A1A1A)
            ).forEach { (key, bg) ->
                val fgColor = if (key == "NIGHT") Color.White else Color(0xFF1A1A1A)
                FilterChip(
                    selected = readerTheme == key,
                    onClick  = { onTheme(key) },
                    label    = { Text(key.lowercase().replaceFirstChar { it.uppercase() }, color = fgColor) },
                    colors   = FilterChipDefaults.filterChipColors(
                        containerColor         = bg,
                        selectedContainerColor = bg,
                        selectedLabelColor     = fgColor
                    ),
                    border   = FilterChipDefaults.filterChipBorder(
                        enabled             = true,
                        selected            = readerTheme == key,
                        selectedBorderColor = GoldAccent,
                        selectedBorderWidth = 2.dp,
                        borderColor         = SettingsDivider
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ── Storage card ───────────────────────────────────────────────────────────────

@Composable
private fun StorageCard(localBookCount: Int) {
    SettingsCard {
        SettingsRow(
            icon   = Icons.Default.FolderOpen,
            label  = "Imported local books",
            value  = "$localBookCount file${if (localBookCount != 1) "s" else ""}"
        )
        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(vertical = 10.dp))
        Text(
            text  = "Local books are stored in the app's private storage and are not deleted when you clear the cache.",
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}

// ── About card ─────────────────────────────────────────────────────────────────

@Composable
private fun AboutCard() {
    SettingsCard {
        SettingsRow(
            icon   = Icons.Default.AutoStories,
            label  = "App name",
            value  = "OmniTome"
        )
        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(vertical = 10.dp))
        SettingsRow(
            icon   = Icons.Default.Info,
            label  = "Version",
            // Read from versionName in app/build.gradle.kts, so it updates with every build.
            value  = com.ereader.app.BuildConfig.VERSION_NAME
        )
        HorizontalDivider(color = SettingsDivider, modifier = Modifier.padding(vertical = 10.dp))
        SettingsRow(
            icon   = Icons.Default.LibraryBooks,
            label  = "Reader engine",
            value  = "Readium 3.0"
        )
    }
}

// ── Shared composables ─────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SettingsCard)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        content = content
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(18.dp))
        Text(label, color = TextPrimary, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = TextSecondary, fontSize = 13.sp)
    }
}

// ── FAQ card ───────────────────────────────────────────────────────────────────

private data class FaqItem(val question: String, val answer: String)

private val faqItems = listOf(

    FaqItem(
        question = "How do star ratings and Times Read work?",
        answer   = "Star ratings: press and hold a book → Rate Book, or rate it when you reach the last page. " +
                   "Ratings are saved to Calibre's own Rating field (your Calibre user needs permission to make changes), " +
                   "and 5 stars shows the fire badge on the cover.\n\n" +
                   "Times Read: add an \"Integers\" column called Times Read in Calibre " +
                   "(Preferences → Add your own columns). Every time you finish a book in OmniTome, " +
                   "its count goes up by one. If your columns have other names, pick them in " +
                   "Settings → Read Tracking."
    ),

    FaqItem(
        question = "What are Smart Shelves?",
        answer   = "A Smart Shelf is a saved Calibre search that shows as a shelf on the Shelves tab, next to your own shelves. " +
                   "Tap \"Smart shelf\" and type a search the same way you would in Calibre's search bar, for example:\n" +
                   "• #read:false — books you haven't read\n" +
                   "• rating:>=4 — books rated 4 stars or more\n" +
                   "• date:>30daysago — added in the last 30 days\n" +
                   "• search:\"My saved search\" — one of your Calibre saved searches\n\n" +
                   "Smart Shelves fill themselves: they refresh every time the library syncs and still work offline. " +
                   "Press and hold one to edit or delete it."
    ),

    FaqItem(
        question = "Can I use OmniTome without Calibre?",
        answer   = "Yes. Tap the Local tab at the bottom to import EPUB or PDF files directly from your device and read them without any server. Calibre is only needed to sync your full library or push metadata changes back to your PC."
    ),

    FaqItem(
        question = "How do I set up the Calibre Content Server?",
        answer   = "On your PC:\n" +
                   "1. Open Calibre.\n" +
                   "2. Click the gear (Preferences) → Sharing → Sharing over the net.\n" +
                   "3. Click Start server (or check \"Run server automatically\").\n" +
                   "4. Note the IP address shown at the top (e.g. 192.168.1.100).\n" +
                   "5. In this app → Settings → Calibre Server, enter that IP and port 8080, then tap Save."
    ),

    FaqItem(
        question = "How do I add a username and password?",
        answer   = "In Calibre → Preferences → Sharing → Sharing over the net:\n" +
                   "1. Check \"Require username and password\".\n" +
                   "2. Click Add user, enter a username and password.\n" +
                   "3. Check \"Allow to make changes (grant write access)\".\n" +
                   "4. Click Apply / OK.\n\n" +
                   "Then in OmniTome → Settings → Calibre Server, enter the same username and password and tap Save."
    ),

    FaqItem(
        question = "How do I allow unauthenticated local connections?",
        answer   = "If you'd rather skip passwords on your home network:\n" +
                   "In Calibre → Preferences → Sharing → Sharing over the net → Advanced tab:\n" +
                   "Check \"Allow un-authenticated local connections to make changes\".\n\n" +
                   "This lets the app push metadata changes without a username or password, but only works while your phone is on the same local network as your PC."
    ),

    FaqItem(
        question = "How do I add a highlight?",
        answer   = "While reading:\n" +
                   "1. Long-press any word to start a text selection.\n" +
                   "2. Drag the handles to cover the passage you want.\n" +
                   "3. A toolbar appears — tap a color swatch to save the highlight."
    ),

    FaqItem(
        question = "How do I add a note?",
        answer   = "While reading:\n" +
                   "1. Long-press a word to start a selection, then drag to cover the relevant text.\n" +
                   "2. In the toolbar that appears, tap the pencil (Note) icon.\n" +
                   "3. Type your note and tap Save.\n\n" +
                   "Your note is stored alongside the highlighted passage and visible in the Notes view."
    ),

    FaqItem(
        question = "How do I use bookmarks?",
        answer   = "While reading, tap the bookmark ribbon icon in the top-right corner to save your current page. To view all bookmarks, open the panel from the top toolbar and tap the Bookmarks tab. Tap any entry to jump to that page."
    ),

    FaqItem(
        question = "How do I export my highlights and notes?",
        answer   = "1. In the Library, long-press a book cover and tap View Notes.\n" +
                   "2. On the Notes screen, tap the share icon (↑) in the top-right corner.\n" +
                   "3. A PDF of all your highlights and notes is generated automatically.\n" +
                   "4. The share sheet opens — save it to Files, email it, or share it however you like."
    )
)

@Composable
private fun FaqCard() {
    SettingsCard {
        faqItems.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    color    = SettingsDivider,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            FaqRow(item)
        }
    }
}

@Composable
private fun FaqRow(item: FaqItem) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 10.dp)
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text       = item.question,
                color      = TextPrimary,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier   = Modifier.weight(1f)
            )
            Icon(
                imageVector        = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint               = GoldAccent,
                modifier           = Modifier.size(18.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Text(
                text       = item.answer,
                color      = TextSecondary,
                fontSize   = 13.sp,
                lineHeight = 19.sp
            )
        }
    }
}
