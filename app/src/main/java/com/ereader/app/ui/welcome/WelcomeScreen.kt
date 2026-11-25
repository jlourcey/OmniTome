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

/**
 * The tour's version. Raise this by one in an update with a lot of changes:
 * everyone who has only seen an older tour then gets a "What's new" prompt
 * after the splash screen, offering the tour (they can skip it).
 * Small updates leave it alone, so nobody is bothered for minor changes.
 *   1 = the original tour (before 2.0)
 *   2 = OmniTome 2.0
 */
const val TOUR_VERSION = 2

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val prefs: UserPreferences
) : ViewModel() {
    fun markSeen() {
        viewModelScope.launch { prefs.markWelcomeSeen(TOUR_VERSION) }
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
        body  = "OmniTome syncs your Calibre library — covers, series, tags and all. Sort by Title, Author, Series or Date with the chips at the top of the Library tab, and tap ↻ to sync. Badges on the covers show New books, Read books and 5-star favorites."
    ),
    TourStep(
        icon  = Icons.Default.Wifi,
        title = "Connect to Calibre",
        body  = "On your PC: open Calibre → Preferences → Sharing over the net → Start server. Note the address shown, then go to Settings → Calibre Server in this app and enter it. If you have several Calibre libraries, switch between them from the Library screen."
    ),
    TourStep(
        icon  = Icons.Default.Lock,
        title = "Set a Username & Password",
        body  = "In Calibre → Preferences → Sharing over the net: tick 'Require username and password', add a user, and tick 'Allow to make changes' so notes, Read status and ratings can sync back. Enter the same details in Settings → Calibre Server."
    ),
    TourStep(
        icon  = Icons.Default.PhoneAndroid,
        title = "Read Without Calibre",
        body  = "No Calibre? No problem. Tap the Local tab at the bottom to import EPUB or PDF files from your phone and start reading right away."
    ),
    TourStep(
        icon  = Icons.Default.DownloadForOffline,
        title = "Read Offline",
        body  = "Press and hold a book → Read Offline to keep it on your phone for when you're away from home. The book you're reading is saved automatically. Notes, your page, Read status and ratings sync to Calibre on their own once you're back."
    ),
    TourStep(
        icon  = Icons.Default.Tune,
        title = "While You Read",
        body  = "Tap the middle of the page to show the controls. Drag the slider to jump anywhere in the book, see Page X of Y and time left, change the text size and theme, open the contents, or tap the ribbon to bookmark the page. Tap ⋮ → Read aloud to have the book read to you."
    ),
    TourStep(
        icon  = Icons.Default.Edit,
        title = "Highlights, Notes & Dictionary",
        body  = "Press and hold a word, drag the handles to select a passage, then choose Define for an offline dictionary card, Highlight for a colour highlight, or Add Note to write a note."
    ),
    TourStep(
        icon  = Icons.Default.Star,
        title = "Read Status & Ratings",
        body  = "When you reach the end of a book it's marked Read and you're asked to rate it. You can also press and hold a book → Mark as Read or Rate Book. Ratings go to Calibre's own Rating. Add a \"Times Read\" column in Calibre to count re-reads — Settings → Read Tracking lets you pick your columns."
    ),
    TourStep(
        icon  = Icons.Default.AutoAwesome,
        title = "Shelves & Smart Shelves",
        body  = "Press and hold a book → Add to Shelf to make your own shelves, shown on the Shelves tab. Tap Smart shelf there to save a Calibre search, like rating:>=4 or #read:false, as a shelf that fills itself."
    ),
    TourStep(
        icon  = Icons.Default.ManageSearch,
        title = "Search Inside Books",
        body  = "On the Library screen, tap the search-inside icon at the top to find words across every book saved on your phone. Tap a result to open the book at that spot."
    ),
    TourStep(
        icon  = Icons.Default.IosShare,
        title = "Export Highlights",
        body  = "Press and hold a book cover and tap View Notes. Tap the share icon (↑) at the top to make a PDF of all your highlights and notes."
    ),
    TourStep(
        icon  = Icons.Default.DriveFileRenameOutline,
        title = "Edit Book Info",
        body  = "Press and hold a book cover and tap Edit Info to change the title, author, series, tags and description. If you're connected to Calibre, the changes go back to your library. You can replay this tour any time from Settings → FAQ & Help."
    )
)

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * WelcomeScreen — shown once on first launch.
 *
 * Displays a branded welcome card centred on a dark navy background.
 * The user can skip directly to the app or start a guided tour
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

// ── What's new (after a big update) ───────────────────────────────────────────

/** The highlights shown on the "What's new" prompt. Update these with TOUR_VERSION. */
private val whatsNew = listOf(
    Icons.Default.Star            to "Rate books and count re-reads, synced with Calibre",
    Icons.Default.AutoAwesome     to "Smart Shelves that fill themselves from a Calibre search",
    Icons.Default.Tune            to "Page slider, read aloud, and Page X of Y with time left",
    Icons.Default.MenuBook        to "Offline dictionary and search inside your books",
    Icons.Default.CollectionsBookmark to "Your own shelves, cover badges and a home-screen widget"
)

/**
 * Shown once after the splash screen when an update raises [TOUR_VERSION].
 * "Take the Tour" opens the tour; "Skip" goes straight to the app. Either way
 * it won't show again until the next big update.
 */
@Composable
fun WhatsNewScreen(
    onComplete: () -> Unit,
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    var showTour by remember { mutableStateOf(false) }
    val version = com.ereader.app.BuildConfig.VERSION_NAME.removeSuffix("-debug")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A1540), WelcomeBg))),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape     = RoundedCornerShape(24.dp),
            colors    = CardDefaults.cardColors(containerColor = WelcomeCard),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
        ) {
            Column(
                modifier            = Modifier
                    .padding(28.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    painter            = painterResource(R.drawable.logo_omnitome),
                    contentDescription = "OmniTome",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.height(56.dp).wrapContentWidth()
                )
                Text(
                    text       = "What's new in OmniTome $version",
                    color      = TextPrimary,
                    fontSize   = 21.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    whatsNew.forEach { (icon, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(text, color = TextMuted, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick  = { showTour = true },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF060D30))
                ) {
                    Icon(Icons.Default.Explore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Take the Tour", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                TextButton(
                    onClick  = { viewModel.markSeen(); onComplete() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip — you can take the tour later in Settings", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (showTour) {
        TourDialog(
            onFinish  = { showTour = false; viewModel.markSeen(); onComplete() },
            // Closing the tour early also counts as having been offered it.
            onDismiss = { showTour = false; viewModel.markSeen(); onComplete() }
        )
    }
}

// ── Tour dialog ───────────────────────────────────────────────────────────────

/** The guided tour. Also opened from Settings → FAQ & Help → Take the Tour. */
@Composable
fun TourDialog(
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
