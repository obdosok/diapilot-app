plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.obdosok.diapilot"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // The public build's own identity. It deliberately differs from any
        // private build of this source, so the two install side by side, each
        // with its own sandbox (database, photos, preferences), and neither can
        // update, overwrite or uninstall the other. Everything that embeds the
        // id — the FileProvider authority, broadcast actions, file names in
        // shared storage — derives from `${applicationId}` /
        // `BuildConfig.APPLICATION_ID` (see AppIdentity), never from a literal.
        // The Kotlin package and `namespace` above are a separate matter.
        applicationId = "io.github.obdosok.diapilot"
        minSdk = 26
        targetSdk = 36
        // Stage 7 maintenance/What-if hotfix. A higher code makes Android
        // reliably treat this APK as an update of the first experimental build.
        versionCode = 79
        versionName = "1.3.0-f05-dish-recognition"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // TWO EDITIONS OUT OF ONE TRUNK — see docs/editions.md.
    //
    // `oss` is the head: the research vehicle, everything on. `store` is the
    // same code with the prospective and the sensor-direct halves closed, so
    // what it collects and shows is the user's own data in the past tense. No
    // feature is deleted for `store` — it is simply not reachable there, and
    // the one place that decides is the `Edition` object, which reads the two
    // booleans declared below.
    //
    // A SUFFIX, not a second applicationId: a suffixed id keeps every derived
    // name (FileProvider authority, broadcast actions, backup file names — see
    // AppIdentity) distinct, so both editions install side by side, each with
    // its own database. Which edition eventually keeps the clean id is still
    // open — `roadmap.md` lists it as a "before O-B" checkpoint.
    flavorDimensions += "edition"
    productFlavors {
        create("oss") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "EDITION_PROSPECTIVE", "true")
            buildConfigField("boolean", "EDITION_SENSOR_DIRECT", "true")
        }
        create("store") {
            dimension = "edition"
            applicationIdSuffix = ".store"
            buildConfigField("boolean", "EDITION_PROSPECTIVE", "false")
            buildConfigField("boolean", "EDITION_SENSOR_DIRECT", "false")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // SIGNED WITH THE DEBUG KEY ON PURPOSE, and the purpose is measurable.
            //
            // ART refuses to compile a DEBUGGABLE package ahead of time: on this
            // phone `dumpsys package` reported `status=run-from-apk`, and forcing
            // `cmd package compile -m speed` left it at `verify`. So every cold
            // start was paying to JIT the whole forecast path — the first
            // `Forecaster.forecast` of a process cost ~3400 ms against ~200 for
            // every later one, with no single stage over 80 ms. A baseline
            // profile is the fix for that, and it can only take effect in a
            // non-debuggable build.
            //
            // The debug key rather than a new release keystore is what keeps the
            // user's data: same `applicationId`, same signature and the same
            // `versionCode` make this an update in place, so
            // `/data/user/0/<applicationId>` — the SQLite history and
            // `files/photos` — survives. A DIFFERENT key would make the install
            // fail and tempt someone into `uninstall`, which is exactly how six
            // weeks of glucose history would be lost.
            //
            // `optimization.enable = false` above is unchanged, so this build
            // runs the same code the debug one does: no shrinking, no
            // obfuscation, nothing that could move a forecast.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        // ONE CHECK, DISABLED WITH ITS PREMISE CHECKED RATHER THAN ASSUMED.
        //
        // `InvalidFragmentVersionForActivityResult` fires when an old
        // `androidx.fragment` is on the classpath transitively. Its premise is
        // that a Fragment older than 1.3.0 is handling an activity result — and
        // this app has no Fragments at all: `MainActivity` extends
        // `AppCompatActivity` (only for per-app language), `registerForActivityResult`
        // is called on that activity, and `grep` over `app/src/main` finds no
        // Fragment reference of any kind. Verified before switching it off.
        //
        // The alternative fixes are both worse: adding an unused
        // `androidx.fragment` dependency to satisfy a version check, or
        // `checkReleaseBuilds = false`, which would silence every future fatal
        // lint including real ones.
        disable += "InvalidFragmentVersionForActivityResult"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        // The History card cache is keyed by the BUILD as well as the model —
        // see MainState.projectionIdentity.
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    androidResources {
        // The app ships English (default) and Russian. Library strings in other
        // languages are dropped so a system dialog never shows a third language
        // next to the app's fallback English.
        localeFilters += listOf("en", "ru")
    }
    bundle {
        // The language is picked inside the app (Settings -> Language), so a
        // bundle must not split resources by the device language: a phone set
        // to English would otherwise never receive the Russian strings.
        language {
            enableSplit = false
        }
    }
}

dependencies {
    // Ships `src/main/baseline-prof.txt` into the APK and asks ART to compile it
    // at install. Without this the profile file is inert — see its header for
    // the 18-26 s cold open it exists to remove.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    // Per-app language (Settings -> Language) back to API 26:
    // AppCompatDelegate.setApplicationLocales + AppLocalesMetadataHolderService.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(project(":core"))
    // Barcode scanning for the label+weight evidence path: the Play Services
    // code scanner ships its own capture UI (no camera code, no new
    // permission) and unloads after use.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
