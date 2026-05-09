// :app-shell:contracts — pure Kotlin/JVM library that holds the cross-module
// FCAF v2 type contracts. Depended on by :app-shell, :core-networking,
// :core-sensors. Owned by Stream 0 (app-shell). See ../../CONTRACTS.md.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
