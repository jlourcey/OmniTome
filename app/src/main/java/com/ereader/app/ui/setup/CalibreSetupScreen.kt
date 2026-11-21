package com.ereader.app.ui.setup

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ereader.app.R

/**
 * CalibreSetupScreen — shown once on first launch.
 *
 * The user types their Calibre server IP. The app auto-fills the port (8080)
 * and constructs the full URL. On "Connect", it pings /opds and either
 * saves the settings and navigates to the library, or shows a clear error.
 *
 * [onSetupComplete] is called when connection succeeds — AppNavigation uses
 * this to route to the Library screen.
 */
@Composable
fun CalibreSetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: CalibreSetupViewModel = hiltViewModel()
) {
    val state      by viewModel.state.collectAsStateWithLifecycle()
    val ipAddress  by viewModel.ipAddress.collectAsStateWithLifecycle()
    val port       by viewModel.port.collectAsStateWithLifecycle()
    val username   by viewModel.username.collectAsStateWithLifecycle()
    val password   by viewModel.password.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var showPassword by remember { mutableStateOf(false) }

    // Navigate to library as soon as connection is confirmed
    LaunchedEffect(state) {
        if (state is SetupState.Success) onSetupComplete()
    }

    // Full-screen gradient background matching the OmniTome color palette
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // ── Logo / title area ─────────────────────────────────────────
            Image(
                painter            = painterResource(R.drawable.logo_omnitome),
                contentDescription = "OmniTome",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .height(72.dp)
                    .wrapContentWidth()
            )
            Text(
                text      = "Connect your Calibre library",
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Setup card ────────────────────────────────────────────────
            Card(
                shape     = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier            = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    Text(
                        text       = "Calibre Server",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    // ── IP Address field ──────────────────────────────────
                    OutlinedTextField(
                        value         = ipAddress,
                        onValueChange = viewModel::onIpChanged,
                        label         = { Text("IP Address") },
                        placeholder   = { Text("e.g.  192.168.1.100", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                        leadingIcon   = { Icon(Icons.Default.Wifi, contentDescription = null) },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction    = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        isError   = state is SetupState.Error,
                        modifier  = Modifier.fillMaxWidth(),
                        supportingText = {
                            Text(
                                "Your Calibre computer's local network IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    // ── Port field (collapsed by default) ─────────────────
                    var showAdvanced by remember { mutableStateOf(false) }

                    TextButton(
                        onClick  = { showAdvanced = !showAdvanced },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (showAdvanced) "Hide advanced" else "Advanced (port)")
                    }

                    AnimatedVisibility(visible = showAdvanced) {
                        OutlinedTextField(
                            value         = port,
                            onValueChange = viewModel::onPortChanged,
                            label         = { Text("Port") },
                            placeholder   = { Text("8080") },
                            leadingIcon   = { Icon(Icons.Default.SettingsEthernet, contentDescription = null) },
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction    = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    viewModel.onConnectClicked()
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(
                                    "Calibre's default port is 8080. Only change this if you customised it.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }

                    // ── Credentials (optional) ────────────────────────────
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text  = "Credentials (optional)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                    OutlinedTextField(
                        value         = username,
                        onValueChange = viewModel::onUsernameChanged,
                        label         = { Text("Username") },
                        placeholder   = { Text("Optional", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                        leadingIcon   = { Icon(Icons.Default.Person, contentDescription = null) },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction    = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value         = password,
                        onValueChange = viewModel::onPasswordChanged,
                        label         = { Text("Password") },
                        placeholder   = { Text("Optional", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                        leadingIcon   = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon  = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password"
                                )
                            }
                        },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction    = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                viewModel.onConnectClicked()
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // ── Auto-preview of the constructed URL ───────────────
                    if (ipAddress.isNotBlank()) {
                        Surface(
                            shape  = RoundedCornerShape(8.dp),
                            color  = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier          = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Link,
                                    contentDescription = null,
                                    tint     = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text  = "http://${ipAddress.trim()}:${port.trim().ifBlank { "8080" }}/opds",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // ── Error message ─────────────────────────────────────
                    AnimatedVisibility(visible = state is SetupState.Error) {
                        val msg = (state as? SetupState.Error)?.message ?: ""
                        Row(
                            verticalAlignment    = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp).padding(top = 1.dp)
                            )
                            Text(
                                text  = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    // ── Connect button ────────────────────────────────────
                    Button(
                        onClick  = {
                            focusManager.clearFocus()
                            viewModel.onConnectClicked()
                        },
                        enabled  = state !is SetupState.Testing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        if (state is SetupState.Testing) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Connecting…")
                        } else {
                            Icon(Icons.Default.ConnectWithoutContact, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Connect to Calibre", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // ── Help text ─────────────────────────────────────────────────
            Text(
                text = "To find your IP: open Calibre on your computer → " +
                       "Preferences → Sharing over the net → note the IP shown, then tap Start server.",
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(horizontal = 8.dp)
            )

            // ── Skip for standalone / no-Calibre users ────────────────────
            TextButton(
                onClick  = { viewModel.onSkipClicked() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text  = "Skip — use the app without Calibre",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
