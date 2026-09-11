plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(libs.junit)
}
