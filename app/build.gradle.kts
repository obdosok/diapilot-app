plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.diapilot"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.diapilot"
        minSdk = 26
        targetSdk = 36
        // Stage 7 maintenance/What-if hotfix. A higher code makes Android
        // reliably treat this APK as an update of the first experimental build.
        versionCode = 79
        versionName = "1.3.0-f05-dish-recognition"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // `/data/user/0/com.example.diapilot` — the SQLite history and
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
        // `InvalidFragmentVersionForActivityResult` fires because
        // `androidx.fragment:fragment:1.0.0` is on the classpath transitively.
        // Its premise is that a Fragment older than 1.3.0 is handling an
        // activity result — and this app has no Fragments at all: `MainActivity`
        // extends `ComponentActivity`, `registerForActivityResult` is called on
        // that, and `grep` over `app/src/main` finds no Fragment reference of
        // any kind. Verified before switching it off.
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
}

dependencies {
    // Ships `src/main/baseline-prof.txt` into the APK and asks ART to compile it
    // at install. Without this the profile file is inert — see its header for
    // the 18-26 s cold open it exists to remove.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
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
