plugins {
    alias(libs.plugins.android.test)
}

// Release smoke suite: drives the MINIFIED release build of :app from a
// separate, self-instrumenting APK — the macrobenchmark architecture. All
// other tests exercise debug artifacts, where R8 and the resource shrinker
// never run, so release-only breakage (stripped reflective constructors,
// shrunk resources) is invisible to them. Because this module never injects
// into the app process, the app's obfuscation is irrelevant here: tests talk
// to the app the way the OS does (widget host binding, launch intents).
//
// Run with scripts/release-smoke-test.sh, or:
//   ./gradlew :smoke:connectedCheck -PreleaseSmoke
android {
    namespace = "io.github.shhhapp.shhh.smoke"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    // Instrument this module's own process, not the app's: the app stays a
    // black box, exactly as shipped, driven from outside like a launcher
    // would. This also sidesteps every minified-app instrumentation problem
    // (the test APK never has to resolve classes R8 renamed or removed).
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Matches :app's release build type by name (the test plugin only
        // pre-creates debug); this module itself is never minified, and never
        // ships, so the debug key is fine.
        create("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// The whole point is the release build — a debug variant of this module would
// only re-test what app/src/androidTest already covers.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "release"
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
}
