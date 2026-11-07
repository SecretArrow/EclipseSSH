plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here so com.android.library's version is known when :freerdp
    // requests it: the library and application plugins ship in the same
    // artifact, and a subproject request for an id whose implementation is
    // already on the classpath (via the application id) with an untracked
    // version is rejected with "already on the classpath with an unknown
    // version".
    alias(libs.plugins.android.library) apply false
    // No org.jetbrains.kotlin.android declaration: AGP 9 ships built-in Kotlin
    // support, and the KGP android plugin must no longer be applied (or declared
    // for the classpath) anywhere in this build.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // Spotless runs ktlint under the hood so one task (`spotlessApply`)
    // covers both style + format. The autofix workflow commits the result
    // back to the PR branch — see .github/workflows/autofix.yml.
    alias(libs.plugins.spotless) apply false
}

// Spotless is configured per-subproject so the ktlint target list can be
// scoped to the module's own sources (the :freerdp module is pure Java and
// has no Kotlin sources to format). The version catalog's `libs` extension is
// not visible inside `subprojects {}` at configuration time, so the plugin id
// and the ktlint version are spelled out as string literals here — they
// mirror the values in gradle/libs.versions.toml. If you bump one, bump the
// other.
subprojects {
    apply(plugin = "com.diffplug.spotless")
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        // ktlint_official 1.5.0 matches the Kotlin 2.4.20 dialect AGP 9.4.1
        // ships. The pin matches gradle/libs.versions.toml's `ktlint` value;
        // the autofix workflow reads the same pin.
        kotlin {
            ktlint("1.5.0")
            target("src/**/*.kt")
        }
        kotlinGradle {
            ktlint("1.5.0")
            target("*.gradle.kts", "gradle/*.gradle.kts")
        }
    }
}
