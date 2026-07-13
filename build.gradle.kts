plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9+ provides built-in Kotlin; only non-Android Kotlin plugins stay here.
    alias(libs.plugins.kotlin.compose) apply false
}
