// AGP 9 ships built-in Kotlin support: org.jetbrains.kotlin.android must NOT be applied.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
