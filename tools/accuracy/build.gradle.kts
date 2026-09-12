// A standalone JVM report tool — never on :app's or :core's classpath, and
// nothing in :app or :core depends on it. It reads a DiaPilot database
// offline (a copy pulled off a phone, or one produced by the app itself) and
// prints accuracy numbers for the forecast; see docs/accuracy.md.
//
// org.xerial:sqlite-jdbc is deliberately confined to this module: it is a
// pure reporting dependency (offline SQL access to a database file), not a
// production one, and keeping it out of :app avoids a second SQLite engine
// next to the platform's own.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(libs.junit)
}

application {
    mainClass.set("com.diapilot.tools.accuracy.MainKt")
}
