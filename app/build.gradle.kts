import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// RELEASE SIGNING — three sources, tried in this order (audit S8, roadmap O-B).
//
// 1. Environment: DIAPILOT_KEYSTORE_FILE, DIAPILOT_KEYSTORE_PASSWORD,
//    DIAPILOT_KEY_ALIAS, DIAPILOT_KEY_PASSWORD — all four, or none. This is
//    what `.github/workflows/release.yml` sets from repository secrets.
// 2. `keystore.properties` at the repo root (gitignored; see
//    `keystore.properties.example`) with storeFile / storePassword / keyAlias /
//    keyPassword. A relative storeFile is resolved against the repo root.
// 3. Nothing — the release build type falls back to the debug key with a
//    warning (see the release block below for what that fallback costs).
//
// Resolved at configuration time and exposed as a nullable value so the
// `buildTypes` block can pick the signing config without re-reading anything.
data class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

val releaseSigning: ReleaseSigning? = run {
    val envNames = listOf(
        "DIAPILOT_KEYSTORE_FILE",
        "DIAPILOT_KEYSTORE_PASSWORD",
        "DIAPILOT_KEY_ALIAS",
        "DIAPILOT_KEY_PASSWORD",
    )
    val env = envNames.associateWith { System.getenv(it)?.takeIf { v -> v.isNotBlank() } }
    val present = env.values.count { it != null }
    if (present == envNames.size) {
        return@run ReleaseSigning(
            storeFile = rootDir.resolve(env.getValue("DIAPILOT_KEYSTORE_FILE")!!),
            storePassword = env.getValue("DIAPILOT_KEYSTORE_PASSWORD")!!,
            keyAlias = env.getValue("DIAPILOT_KEY_ALIAS")!!,
            keyPassword = env.getValue("DIAPILOT_KEY_PASSWORD")!!,
        )
    }
    if (present > 0) {
        // Half a configuration is a misconfiguration, not a fallback case:
        // stop here rather than quietly sign with the debug key.
        val missing = env.filterValues { it == null }.keys
        throw GradleException(
            "Release signing: $present of ${envNames.size} DIAPILOT_KEYSTORE_*/DIAPILOT_KEY_* " +
                "environment variables are set; missing ${missing.joinToString()}. " +
                "Set all four or none.",
        )
    }
    val propsFile = rootDir.resolve("keystore.properties")
    if (!propsFile.isFile) return@run null
    val props = Properties().also { p -> propsFile.inputStream().use { p.load(it) } }
    fun need(key: String): String = props.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Release signing: keystore.properties is missing `$key`.")
    ReleaseSigning(
        storeFile = rootDir.resolve(need("storeFile")),
        storePassword = need("storePassword"),
        keyAlias = need("keyAlias"),
        keyPassword = need("keyPassword"),
    )
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
        // `versionCode` is bumped as the release step, together with the tag
        // (`v*`, see .github/workflows/release.yml and README "Building"); a
        // higher code is what makes Android treat the APK as an update rather
        // than a downgrade. `-dev` on the name marks every build between two
        // tags; the tag's build drops it.
        versionCode = 79
        versionName = "1.4.0-dev"

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

    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = s.storeFile
                storePassword = s.storePassword
                keyAlias = s.keyAlias
                keyPassword = s.keyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // WHY A NON-DEBUGGABLE BUILD TYPE AT ALL, and why it runs the debug
            // code unchanged.
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
            // `optimization.enable = false` above is unchanged, so this build
            // runs the same code the debug one does: no shrinking, no
            // obfuscation, nothing that could move a forecast.
            //
            // SIGNING. A real release key (`releaseSigning` at the top of this
            // file: environment, then keystore.properties) is the choice; the
            // debug key is only the FALLBACK, kept so `assembleRelease` still
            // compiles on a machine or CI runner without the keystore. That
            // fallback was audit finding S8 — the debug keystore has a well-known
            // password and lives unprotected on every developer machine, so
            // anyone holding it can sign an "update" that installs over the
            // user's data — and it is why the warning below is loud: an APK
            // signed this way is not a release and must not be published.
            //
            // THE MIGRATION COST, stated plainly. Android ties an installed
            // package to its signing certificate. An install that was signed
            // with the debug key (every build before the release key existed)
            // CANNOT be updated by a release-key APK: the install fails with
            // INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the only way forward is to
            // uninstall, which deletes `/data/user/0/<applicationId>` — the
            // SQLite history and `files/photos`. The one-time path is therefore:
            // Settings → Data → export the database (or a daily backup already
            // in Downloads), uninstall, install the release-signed APK, then
            // Settings → Data → "Restore from a backup (.sqlite)". Preferences
            // (API key, thresholds, hand-entered ISF/ICR, switches) are NOT in
            // that file and have to be re-entered by hand — note them before
            // uninstalling. Same-key updates afterwards keep the data in place
            // as before.
            signingConfig = if (releaseSigning != null) {
                signingConfigs.getByName("release")
            } else {
                val message =
                    "WARNING [audit S8]: :app release build type is signed with the DEBUG key. " +
                        "No DIAPILOT_KEYSTORE_FILE/DIAPILOT_KEYSTORE_PASSWORD/DIAPILOT_KEY_ALIAS/" +
                        "DIAPILOT_KEY_PASSWORD environment and no keystore.properties at the repo root. " +
                        "The resulting APK is NOT a release and must not be published " +
                        "(see keystore.properties.example and README \"Building\")."
                // `--quiet` drops WARN-level output; a warning that vanishes
                // under `-q` is not loud, so it is repeated at QUIET level
                // only when WARN would not be shown — one line either way.
                if (logger.isWarnEnabled) logger.warn(message) else logger.quiet(message)
                signingConfigs.getByName("debug")
            }
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
        // A SECOND ONE, DISABLED BECAUSE THE DETECTOR CRASHES, NOT BECAUSE IT
        // FINDS ANYTHING.
        //
        // With AGP 9.2.1's lint, `lintReportOssDebug` dies in
        // `UnusedResourceDetector.checkPartialResults` →
        // `ResourceStore.deserialize` ("Array contains no element matching the
        // predicate") while merging the per-variant partial results — a bug in
        // the tool, as its own message says. `UnusedIds` shares the detector.
        // Nothing else keeps CI from running lint, so these two are off until
        // a lint release fixes the merge; re-enable and run `:app:lintOssDebug`
        // to check.
        disable += listOf("UnusedResources", "UnusedIds")
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
