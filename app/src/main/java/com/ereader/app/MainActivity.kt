package com.ereader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ereader.app.data.preferences.AppTheme
import com.ereader.app.data.preferences.UserPreferences
import com.ereader.app.ui.navigation.AppNavigation
import com.ereader.app.ui.theme.EReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MainActivity — the single Compose Activity for the library shell.
 *
 * Responsibilities:
 *  1. Enable edge-to-edge rendering (content draws behind status and nav bars)
 *  2. Calculate [WindowSizeClass] — the adaptive breakpoint for phone vs tablet
 *  3. Hand both values to [AppNavigation], which builds the entire UI
 *
 * The ReaderActivity is a separate Activity because Readium's Fragment host
 * requires AppCompatActivity. See ReaderActivity.kt for details.
 *
 * @AndroidEntryPoint activates Hilt injection for this Activity and all
 * Composables reachable from it (via hiltViewModel()).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Hilt injects UserPreferences automatically — no manual setup needed
    @Inject lateinit var userPreferences: UserPreferences

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() MUST be called before super.onCreate() sets content.
        // It hands control of the system launch screen to the core-splashscreen
        // library and triggers the postSplashScreenTheme switch (→ Theme.EReader)
        // once the app's first Compose frame is ready to draw.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // Observe the saved app theme preference so Dark/Light/System toggle
            // takes effect immediately without restarting the app.
            val appTheme by userPreferences.appTheme
                .collectAsState(initial = AppTheme.SYSTEM)

            val darkTheme = when (appTheme) {
                AppTheme.DARK   -> true
                AppTheme.LIGHT  -> false
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }

            EReaderTheme(darkTheme = darkTheme) {
                val windowSizeClass = calculateWindowSizeClass(this)
                AppNavigation(
                    windowSizeClass  = windowSizeClass,
                    userPreferences  = userPreferences
                )
            }
        }
    }
}
