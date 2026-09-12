// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// This checkout is intentionally isolated: every project and build directory
// must resolve inside it, so a stale absolute path from wherever a checkout
// was copied from can never silently re-enter a task graph.
val checkoutRoot = layout.projectDirectory.asFile.canonicalFile

tasks.register("verifyProjectIsolation") {
    group = "verification"
    doLast {
        allprojects.forEach { p ->
            check(p.projectDir.canonicalFile.toPath().startsWith(checkoutRoot.toPath())) {
                "Project ${p.path} resolves outside the isolated checkout: ${p.projectDir}"
            }
            check(p.layout.buildDirectory.get().asFile.canonicalFile.toPath().startsWith(checkoutRoot.toPath())) {
                "Build output for ${p.path} resolves outside the isolated checkout: ${p.layout.buildDirectory.get().asFile}"
            }
        }
    }
}

allprojects {
    tasks.configureEach {
        // MATCHED BY SHAPE, NOT BY A FIXED LIST. `:app` is built in two
        // editions, so the variant task names carry the flavor —
        // `testOssDebugUnitTest`, `assembleStoreDebug`,
        // `compileOssDebugAndroidTestKotlin`. The literal set that used to be
        // here named only the flavorless `…Debug…` tasks, which after the
        // edition split exist no more: every build would have run with the
        // isolation check silently unwired.
        val checksIsolation = name == "test" ||
            (name.startsWith("test") && name.endsWith("UnitTest")) ||
            (name.startsWith("compile") && name.endsWith("AndroidTestKotlin")) ||
            name.startsWith("assemble")
        if (checksIsolation) {
            dependsOn(rootProject.tasks.named("verifyProjectIsolation"))
        }
    }
}
