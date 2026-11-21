// Top-level build file — configuration here applies to all sub-projects/modules.
// Individual module dependencies live in app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.kotlin.android)       apply false
    alias(libs.plugins.kotlin.kapt)          apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt)                 apply false
    alias(libs.plugins.compose.compiler)     apply false
}
