import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.kover)
}

// Release signing is read from an untracked keystore.properties (see README).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// Set by scripts/release-smoke-test.sh: the :smoke module needs an installable
// release APK, so release signing may fall back to the debug key (see below).
val releaseSmokeRun = providers.gradleProperty("releaseSmoke").isPresent

android {
    namespace = "io.github.shhhapp.shhh"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.shhhapp.shhh"
        minSdk = 34
        // NOTE: do not bump targetSdk to 37 without a redesign — Android 17's
        // while-in-use requirement breaks the alarm → FGS → volume-change path
        // used for timed hush and quiet hours (see README architecture notes).
        targetSdk = 36
        versionCode = 8
        versionName = "1.6.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (keystorePropertiesFile.exists()) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real keystore when present; for release smoke-test runs on
            // machines/CI without one, fall back to the debug key so the
            // minified APK is installable. Published releases always come from
            // a machine with keystore.properties, so they are never debug-signed.
            signingConfig = signingConfigs.findByName("release")
                ?: if (releaseSmokeRun) signingConfigs.getByName("debug") else null
        }
    }

    // Google's dependency metadata is an encrypted blob only Play Console can
    // read. This app ships via GitHub Releases + the in-app updater, so the
    // blob would be dead weight in every APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        // Built-in Kotlin's jvmTarget defaults to targetCompatibility.
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // targetSdk is pinned to 36 on purpose (see the note in defaultConfig):
        // Android 17's while-in-use rule breaks the alarm -> FGS -> volume path.
        disable += "OldTargetApi"
    }
}

kover {
    reports {
        filters {
            excludes {
                // Generated code with no logic of ours.
                classes(
                    "*.BuildConfig",
                    "*.R", "*.R$*",
                    "*ComposableSingletons*"
                )
            }
        }

        verify {
            // Every reachable line is covered. The single permitted miss is the
            // `throw NoWhenBranchMatchedException()` Kotlin emits for the
            // exhaustive `when (current)` over the two-value Screen enum in
            // MainActivity.kt — unreachable without adding a third screen.
            rule("No uncovered lines except Kotlin's dead when-exhaustiveness throw") {
                bound {
                    maxValue = 1
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE
                    aggregationForGroup =
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.MISSED_COUNT
                }
            }
            // The remaining branch misses are Compose compiler scaffolding on
            // `@Composable fun` signature lines ($changed bitmasks, shouldExecute
            // skip paths, isTraceInProgress guards) that no test can steer. That
            // floor moves with the Compose compiler, so this bound sits a few
            // points below the current figure (~88%) rather than right on it —
            // the strict gate is the missed-line rule above. It still fails if a
            // meaningful block of conditional code arrives untested.
            rule("Branch coverage of hand-written control flow") {
                bound {
                    minValue = 85
                    coverageUnits = kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH
                }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    // Overrides the BOM: Material 3 Expressive (MaterialExpressiveTheme) is 1.5.0-alpha only.
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.glance.testing)
    testImplementation(libs.glance.appwidget.testing)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Compose ui-test pulls in an older Espresso whose reflection breaks on
    // Android 17 (InputManager.getInstance was removed) — force the current one.
    androidTestImplementation(libs.espresso.core)
}
