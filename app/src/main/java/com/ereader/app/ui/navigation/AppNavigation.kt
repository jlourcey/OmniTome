package com.ereader.app.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.ereader.app.data.preferences.UserPreferences
import com.ereader.app.ui.bookshelf.BookshelfScreen
import com.ereader.app.ui.bookshelf.SeriesDetailScreen
import com.ereader.app.ui.edit.EditBookScreen
import com.ereader.app.ui.notes.NotesScreen
import com.ereader.app.ui.downloads.LocalImportScreen
import com.ereader.app.ui.favorites.FavoritesScreen
import com.ereader.app.ui.reader.ReaderActivity
import com.ereader.app.ui.settings.SettingsScreen
import com.ereader.app.ui.setup.CalibreSetupScreen
import com.ereader.app.ui.splash.SplashScreen
import com.ereader.app.ui.welcome.TOUR_VERSION
import com.ereader.app.ui.welcome.WelcomeScreen
import com.ereader.app.ui.welcome.WhatsNewScreen

// ── Route constants ───────────────────────────────────────────────────────────
private const val ROUTE_SETUP   = "setup"
private const val ROUTE_SPLASH  = "splash"
private const val ROUTE_WELCOME = "welcome"
private const val ROUTE_WHATS_NEW = "whats_new"

// ── Route definitions ─────────────────────────────────────────────────────────

/**
 * Each screen in the app is described by a sealed class.
 *
 * [route]  — a unique string identifier used by NavController to navigate.
 * [label]  — the text shown in the nav bar / rail.
 * [icon]   — the Material icon shown next to the label.
 */
sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Library   : Screen("library",   "Library",   Icons.Default.LibraryBooks)
    // Favorites + your own shelves (route name kept so existing links still work)
    data object Favorites : Screen("favorites", "Shelves",   Icons.Default.CollectionsBookmark)
    data object Downloads : Screen("downloads", "Local",     Icons.Default.PhoneAndroid)
    data object Settings  : Screen("settings",  "Settings",  Icons.Default.Settings)
}

private val topLevelScreens = listOf(
    Screen.Library,
    Screen.Favorites,
    Screen.Downloads,
    Screen.Settings
)

// ── Root navigation composable ────────────────────────────────────────────────

/**
 * AppNavigation — the root of the Compose UI tree.
 *
 * On first launch: shows [CalibreSetupScreen]. Once setup completes,
 * navigates to the main library shell and never shows setup again.
 *
 * Adaptive behavior after setup:
 *  - [WindowWidthSizeClass.Compact]  → phone portrait → NavigationBar at the bottom
 *  - [WindowWidthSizeClass.Medium]   → phone landscape / small tablet → NavigationRail on left
 *  - [WindowWidthSizeClass.Expanded] → large tablet / foldable → NavigationRail on left
 */
@Composable
fun AppNavigation(
    windowSizeClass: WindowSizeClass,
    userPreferences: UserPreferences
) {
    val navController = rememberNavController()
    val isCompact     = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    val calibreConfigured by userPreferences.calibreConfigured
        .collectAsStateWithLifecycle(initialValue = false)

    val hasSeenWelcome by userPreferences.hasSeenWelcome
        .collectAsStateWithLifecycle(initialValue = true) // default true avoids flash on re-launch

    // After a big update (TOUR_VERSION raised), offer the tour again once.
    val tourVersionSeen by userPreferences.tourVersionSeen
        .collectAsStateWithLifecycle(initialValue = TOUR_VERSION)

    // Always start at the splash screen; it decides where to go next.
    NavHost(navController = navController, startDestination = ROUTE_SPLASH) {

        // ── Splash ─────────────────────────────────────────────────────────
        composable(ROUTE_SPLASH) {
            SplashScreen(
                onSplashComplete = {
                    val next = when {
                        !hasSeenWelcome                 -> ROUTE_WELCOME
                        tourVersionSeen < TOUR_VERSION  -> ROUTE_WHATS_NEW
                        calibreConfigured               -> Screen.Library.route
                        else             -> ROUTE_SETUP
                    }
                    navController.navigate(next) {
                        popUpTo(ROUTE_SPLASH) { inclusive = true }
                    }
                }
            )
        }

        // ── Welcome / onboarding (first launch only) ────────────────────────
        composable(ROUTE_WELCOME) {
            WelcomeScreen(
                onComplete = {
                    // Welcome marks itself seen; go to setup or library as appropriate
                    val next = if (calibreConfigured) Screen.Library.route else ROUTE_SETUP
                    navController.navigate(next) {
                        popUpTo(ROUTE_WELCOME) { inclusive = true }
                    }
                }
            )
        }

        // ── What's new (once, after a big update) ───────────────────────────
        composable(ROUTE_WHATS_NEW) {
            WhatsNewScreen(
                onComplete = {
                    val next = if (calibreConfigured) Screen.Library.route else ROUTE_SETUP
                    navController.navigate(next) {
                        popUpTo(ROUTE_WHATS_NEW) { inclusive = true }
                    }
                }
            )
        }

        // ── Setup (first launch) ────────────────────────────────────────────
        composable(ROUTE_SETUP) {
            CalibreSetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Library.route) {
                        popUpTo(ROUTE_SETUP) { inclusive = true }
                    }
                }
            )
        }

        // ── Main library shell ──────────────────────────────────────────────
        composable(Screen.Library.route) {
            // Do NOT pass the outer navController here — MainShell creates its
            // own inner controller so each NavHost has exactly one owner.
            MainShell(windowSizeClass, isCompact)
        }
    }
}

@Composable
private fun MainShell(
    windowSizeClass: WindowSizeClass,
    isCompact: Boolean
) {
    // Fresh NavController dedicated to the library tab graph.
    // The outer NavController (splash/setup/library) must NOT be reused here —
    // each NavHost must own its own NavController exclusively.
    val innerNav = rememberNavController()

    if (isCompact) {
        Scaffold(
            bottomBar = { AppBottomBar(navController = innerNav) }
        ) { innerPadding ->
            AppNavHost(
                navController   = innerNav,
                windowSizeClass = windowSizeClass,
                modifier        = Modifier.padding(innerPadding)
            )
        }
    } else {
        Row(modifier = Modifier.fillMaxSize()) {
            AppNavigationRail(navController = innerNav)
            Divider(
                modifier = Modifier.fillMaxHeight().width(1.dp),
                color    = MaterialTheme.colorScheme.outlineVariant
            )
            AppNavHost(
                navController   = innerNav,
                windowSizeClass = windowSizeClass,
                modifier        = Modifier.weight(1f)
            )
        }
    }
}

// ── Bottom navigation bar (phones) ────────────────────────────────────────────

@Composable
private fun AppBottomBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationBar {
        topLevelScreens.forEach { screen ->
            val isSelected = currentDestination?.hierarchy
                ?.any { it.route == screen.route } == true

            NavigationBarItem(
                selected = isSelected,
                onClick  = { navController.navigateSingleTop(screen.route) },
                icon     = { Icon(screen.icon, contentDescription = screen.label) },
                label    = { Text(screen.label) }
            )
        }
    }
}

// ── Navigation rail (tablets) ─────────────────────────────────────────────────

@Composable
private fun AppNavigationRail(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        header   = {
            // App icon or logo could go here
            Spacer(Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = "E-Reader",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(32.dp)
                    .padding(bottom = 8.dp)
            )
        }
    ) {
        Spacer(Modifier.height(8.dp))
        topLevelScreens.forEach { screen ->
            val isSelected = currentDestination?.hierarchy
                ?.any { it.route == screen.route } == true

            NavigationRailItem(
                selected = isSelected,
                onClick  = { navController.navigateSingleTop(screen.route) },
                icon     = { Icon(screen.icon, contentDescription = screen.label) },
                label    = { Text(screen.label) }
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ── NavHost — wires routes to screens ────────────────────────────────────────

@Composable
private fun AppNavHost(
    navController: NavHostController,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    NavHost(
        navController    = navController,
        startDestination = Screen.Library.route,
        modifier         = modifier
    ) {
        composable(Screen.Library.route) {
            BookshelfScreen(
                windowWidthSizeClass = windowSizeClass.widthSizeClass,
                onBookClick = { book ->
                    val intent = Intent(context, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                        putExtra(ReaderActivity.EXTRA_BOOK_TITLE, book.title)
                    }
                    context.startActivity(intent)
                },
                onNotesClick = { book ->
                    navController.navigate("notes/${book.id}")
                },
                onSearchInsideBooks = { navController.navigate("search-text") },
                onEditClick = { book ->
                    navController.navigate("edit/${book.id}")
                },
                onSeriesClick = { series ->
                    navController.navigate("series/${series.encodeUrlSegment()}")
                }
            )
        }

        // ── Search inside books ─────────────────────────────────────────────
        composable("search-text") {
            com.ereader.app.ui.search.SearchInBooksScreen(
                onBack       = { navController.popBackStack() },
                onOpenResult = { book, href, progression, words ->
                    val intent = Intent(context, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                        putExtra(ReaderActivity.EXTRA_BOOK_TITLE, book.title)
                        putExtra(ReaderActivity.EXTRA_GOTO_HREF, href)
                        progression?.let { putExtra(ReaderActivity.EXTRA_GOTO_PROGRESSION, it) }
                        putStringArrayListExtra(ReaderActivity.EXTRA_HIGHLIGHT_WORDS, ArrayList(words))
                    }
                    context.startActivity(intent)
                }
            )
        }

        // ── Notes (consolidated annotations for one book) ───────────────────
        composable("notes/{bookId}") {
            NotesScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ── Edit book metadata ──────────────────────────────────────────────
        composable("edit/{bookId}") {
            EditBookScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // ── Series detail ───────────────────────────────────────────────────
        composable("series/{seriesName}") { backStackEntry ->
            val seriesName = backStackEntry.arguments
                ?.getString("seriesName")
                ?.decodeUrlSegment()
            if (seriesName != null) {
                SeriesDetailScreen(
                    seriesName   = seriesName,
                    onBookClick  = { book ->
                        val intent = Intent(context, ReaderActivity::class.java).apply {
                            putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                            putExtra(ReaderActivity.EXTRA_BOOK_TITLE, book.title)
                        }
                        context.startActivity(intent)
                    },
                    onNotesClick = { book -> navController.navigate("notes/${book.id}") },
                    onEditClick  = { book -> navController.navigate("edit/${book.id}") },
                    onBack       = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.Favorites.route) {
            FavoritesScreen(
                windowWidthSizeClass = windowSizeClass.widthSizeClass,
                onBookClick = { book ->
                    val intent = Intent(context, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                        putExtra(ReaderActivity.EXTRA_BOOK_TITLE, book.title)
                    }
                    context.startActivity(intent)
                }
            )
        }

        composable(Screen.Downloads.route) {
            LocalImportScreen(
                windowWidthSizeClass = windowSizeClass.widthSizeClass,
                onBookClick = { book ->
                    val intent = Intent(context, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
                        putExtra(ReaderActivity.EXTRA_BOOK_TITLE, book.title)
                    }
                    context.startActivity(intent)
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Navigate to a top-level screen without creating duplicate back-stack entries.
 * This matches standard Android navigation behavior (tapping a nav bar item
 * that's already selected doesn't push a new screen onto the stack).
 */
private fun NavHostController.navigateSingleTop(route: String) {
    navigate(route) {
        // Pop everything up to (but not including) the start destination
        // so back from any tab goes to the home screen, not a nested history
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState    = true
    }
}

// ── URL helpers for series name in route argument ─────────────────────────────

/** Encode a series name so it is safe to embed as a NavHost path segment. */
private fun String.encodeUrlSegment(): String =
    java.net.URLEncoder.encode(this, "UTF-8")

/** Decode a series name that was encoded by [encodeUrlSegment]. */
private fun String.decodeUrlSegment(): String =
    java.net.URLDecoder.decode(this, "UTF-8")

