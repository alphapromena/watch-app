// :core-sensors — Wear OS sensor acquisition (HR, GPS, SpO2, skin temperature).
// Owned by Stream 2. Emits SensorEvent via SensorCollector; no I/O, no networking.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.watchapp.sensors"
    compileSdk = 34

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Cross-module type contracts (SensorEvent / SensorCollector). Re-exported
    // so app-shell sees the same symbols when it consumes :core-sensors.
    api(project(":app-shell:contracts"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.health.services.client)

    // Required to await() the ListenableFutures returned by Health Services.
    // Pinned inline (not via libs.versions.toml) to keep this module's
    // dependency changes self-contained; promote to the catalog if other
    // modules end up needing it. See INTEGRATION_NOTES.md.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
}
