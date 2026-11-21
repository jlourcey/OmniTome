package com.ereader.app.ui.welcome

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ereader.app.R
import com.ereader.app.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Private colors ────────────────────────────────────────────────────────────
private val WelcomeBg   = Color(0xFF060D30)
private val WelcomeCard = Color(0xFF0D1848)
private val Gold        = Color(0xFFDDBB3A)
private val TextPrimary = Color.White
private val TextMuted   = Color.White.copy(alpha = 0.65f)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val prefs: UserPreferences
) : ViewModel() {
    fun markSeen() {
        viewModelScope.launch { prefs.markWelcomeSeen() }
    }
}

// ── Tour step model ───────────────────────────────────────────────────────────

private data class TourStep(
    val icon: ImageVector,
    val title: String,
    val body: String
)

private val tourSteps = listOf(
    TourStep(
        icon  = Icons.Default.LibraryBooks,
        title = "Your Library",
        body  = "OmniTome syncs your full Calibre library over Wi-Fi — covers, metadata, series, and all. Browse by Title, Author, or Series using the chips at the top of the Library tab."
    ),
    TourStep(
        icon  = Icons.Default.Wifi,
        title = "Connect to Calibre",
        body  = "On your PC: open Calibre → Preferences (gear icon) → Sharing → Sharing over the net → Start server. Note the IP address shown, then go to Settings → Calibre Server in this app and enter it."
    ),
    TourStep(
        icon  = Icons.Default.Lock,
        title = "Set a Username & Password",
        body  = "In Calibre → Preferences → Sharing → Sharing over the net: check 'Require username and password', add a user, and check 'Allow to make changes'. Enter the same credentials in Settings → Calibre Server."
    ),
    TourStep(
        icon  = Icons.Default.PhoneAndroid,
        title = "Read Without Calibre",
        body  = "No Calibre? No problem. Tap the Local tab at the bottom to import EPUB or PDF files directly from your device and start reading right away."
    ),
    TourStep(
        icon  = Icons.Default.Edit,
        title = "Highlights & Notes",
        body  = "While reading, long-press any word to start a text selection. Drag the handles to select a passage, then choose a highlight colour or tap the pencil icon to add a written note."
    ),
    TourStep(
        icon  = Icons.Default.Bookmark,
        title = "Bookmarks",
        body  = "Tap anywhere while reading to reveal the toolbar, then tap the bookmark ribbon icon in the top-right to save your place. Open the toolbar again → Bookmarks list to jump to any saved page."
    ),
    TourStep(
        icon  = Icons.Default.IosShare,
        title = "Export Highlights",
        body  = "Long-press any book cover on the Library screen and tap 'View Notes'. On the Notes screen, tap the share icon (↑) in the top-right corner to generate and share a PDF of all your highlights and notes."
    ),
    TourStep(
        icon  = Icons.Default.DriveFileRenameOutline,
        title = "Edit Book Info",
        body  = "Long-press a book cover and tap 'Edit Info' to change the title, author, series, tags, and description. Tap Save to update the details — if you're connected to Calibre, the changes are pushed back to your library."
    )
)

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * WelcomeScreen — shown once on first launch.
 *
 * Displays a branded welcome card centred on a dark navy background.
 * The user can skip directly to the app or start a guided 8-step tour
 * that walks through every feature. Either action marks the welcome as
 * seen so it never appears again.
 */
@Composable
fun WelcomeScreen(
    onComplete: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    var showTour by remember { mutableStateOf(false) }

    // Full-screen dark background — matches the app's bookshelf palette
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A1540),
                        WelcomeBg
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // ── Welcome card ──────────────────────────────────────────────────────
        Card(
            shape     = RoundedCornerShape(24.dp),
            colors    = CardDefaults.cardColors(containerColor = WelcomeCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        ) {
            Column(
                modifier            = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Logo
                Image(
                    painter            = painterResource(R.drawable.logo_omnitome),
                    contentDescription = "OmniTome",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier
                        .height(56.dp)
                        .wrapContentWidth()
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text       = "Welcome to OmniTome",
                    color      = TextPrimary,
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )

                Text(
                    text      = "Your personal e-book reader — synced with Calibre or standalone with local files.",
                    color     = TextMuted,
                    fontSize  = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(4.dp))

                // Primary action — tour
                Button(
                    onClick = { showTour = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape  = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gold,
                        contentColor   = Color(0xFF060D30)
                    )
                ) {
                    Icon(Icons.Default.Explore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Take the Tour", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                // Secondary action — skip
                TextButton(
                    onClick  = {
                        viewModel.markSeen()
                        onComplete()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text     = "Skip — I'll explore on my own",
                        color    = TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    // ── Guided tour dialog ────────────────────────────────────────────────────
    if (showTour) {
        TourDialog(
            onFinish = {
                showTour = false
                viewModel.markSeen()
                onComplete()
            },
            onDismiss = { showTour = false }
        )
    }
}

// ── Tour dialog ───────────────────────────────────────────────────────────────

@Composable
private fun TourDialog(
    onFinish: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val current = tourSteps[step]
    val isLast  = step == tourSteps.lastIndex

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape     = RoundedCornerShape(20.dp),
            colors    = CardDefaults.cardColors(containerColor = WelcomeCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier            = Modifier
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Step counter
                Text(
                    text     = "Step ${step + 1} of ${tourSteps.size}",
                    color    = Gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                // Feature icon in a gold circle
                Box(
                    modifier         = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Gold.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = current.icon,
                        contentDescription = null,
                        tint               = Gold,
                        modifier           = Modifier.size(36.dp)
                    )
                }

                // Title + description
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it } + fadeIn()).togetherWith(
                                slideOutHorizontally { -it } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally { -it } + fadeIn()).togetherWith(
                                slideOutHorizontally { it } + fadeOut()
                            )
                        }
                    },
                    label = "tourStep"
                ) { s ->
                    val shown = tourSteps[s]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text       = shown.title,
                            color      = TextPrimary,
                            fontSize   = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign  = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text       = shown.body,
                            color      = TextMuted,
                            fontSize   = 14.sp,
                            textAlign  = TextAlign.Center,
                            lineHeight = 21.sp
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Progress dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier              = Modifier.padding(vertical = 4.dp)
                ) {
                    tourSteps.indices.forEach { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == step) 10.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (i == step) Gold else Gold.copy(alpha = 0.3f))
                        )
                    }
                }

                // Navigation buttons
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Back / Skip
                    if (step > 0) {
                        OutlinedButton(
                            onClick  = { step-- },
                            modifier = Modifier.weight(1f),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                            border   = androidx.compose.foundation.BorderStroke(
                                1.dp, TextMuted.copy(alpha = 0.4f)
                            )
                        ) {
                            Text("Back")
                        }
                    } else {
                        TextButton(
                            onClick  = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Skip", color = TextMuted, fontSize = 13.sp)
                        }
                    }

                    // Next / Done
                    Button(
                        onClick  = { if (isLast) onFinish() else step++ },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = Gold,
                            contentColor   = Color(0xFF060D30)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (isLast) "Get Started" else "Next",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
