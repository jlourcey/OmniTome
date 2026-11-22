import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
}

// ── Release signing — credentials loaded from keystore.properties (not in git) ──
// If the file doesn't exist (e.g. CI, a new dev machine) signing is skipped and
// only debug builds work; release builds will fail at signing time with a clear error.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace   = "com.ereader.app"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.omnitome.app"
        minSdk          = 26          // Android 8.0 — covers ~98 % of active devices
        targetSdk       = 35
        versionCode     = 3
        versionName     = "1.20"

        // CALIBRE_BASE_URL is intentionally empty in defaultConfig.
        // Each user enters their own Calibre server address in Settings → Calibre Server.
        // The debug override below lets the developer skip that step during local testing.
        buildConfigField("String", "CALIBRE_BASE_URL", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            keyAlias      = keystoreProperties["keyAlias"]     as String? ?: ""
            keyPassword   = keystoreProperties["keyPassword"]  as String? ?: ""
            storeFile     = keystoreProperties["storeFile"]?.let { rootProject.file(it as String) }
            storePassword = keystoreProperties["storePassword"] as String? ?: ""
        }
    }

    buildTypes {
        debug {
            isDebuggable    = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            // Override so the developer's own Calibre IP is pre-filled during testing
            buildConfigField(
                "String",
                "CALIBRE_BASE_URL",
                "\"${project.findProperty("calibre.base.url") ?: ""}\""
            )
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // CALIBRE_BASE_URL stays "" (from defaultConfig) — users enter their own address
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // required by Readium SDK
    }

    buildFeatures {
        compose     = true
        buildConfig = true   // enables BuildConfig.CALIBRE_BASE_URL
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/DEPENDENCIES"   // duplicate from Google API client libs
        resources.excludes += "META-INF/INDEX.LIST"     // duplicate from Google API client libs
    }
}

dependencies {
    // ── Compose BOM — pins all Compose library versions in one place ──────────
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)     // only included in debug builds

    // ── Activity & Navigation ─────────────────────────────────────────────────
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.compose.window.size)
    implementation(libs.adaptive.navigation)

    // ── Lifecycle / ViewModel ─────────────────────────────────────────────────
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // ── Hilt (Dependency Injection) ───────────────────────────────────────────
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)                         // generates Hilt code at compile time
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)

    // ── Room (SQLite ORM) ─────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)                         // generates DAO implementations

    // ── Networking ────────────────────────────────────────────────────────────
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)              // logs HTTP calls in debug
    implementation(libs.kotlinx.serialization.json)

    // ── Image loading ─────────────────────────────────────────────────────────
    implementation(libs.coil.compose)

    // ── Readium 3 (EPUB engine) ───────────────────────────────────────────────
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.opds)

    // ── Google Drive ──────────────────────────────────────────────────────────
    implementation(libs.google.drive)
    implementation(libs.google.auth.client)
    implementation(libs.google.http.android)

    // ── DataStore (reading preferences) ──────────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── WorkManager (background sync) ─────────────────────────────────────────
    implementation(libs.work.runtime)

    // ── Core library desugaring (required by Readium SDK) ────────────────────
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ── AppCompat (required by ReaderActivity / Readium Fragment host) ───────
    implementation(libs.appcompat)

    // ── Splash screen (system launch screen: navy background + OmniTome logo) ─
    implementation(libs.core.splashscreen)

    // ── Utilities ─────────────────────────────────────────────────────────────
    implementation(libs.coroutines.android)
    implementation(libs.timber)
}

// Kotlin compiler options — replaces the deprecated kotlinOptions block inside android {}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Required so kapt can reference Room and Hilt generated code correctly
kapt {
    correctErrorTypes = true
}
