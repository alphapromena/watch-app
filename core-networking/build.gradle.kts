// :core-networking — TCP transport, FCAF v2 framing, reconnect/backoff,
// Room-backed offline buffer. Owned by Stream 1. Depends on
// :app-shell:contracts for SensorEvent / Streamer / DeviceConfig.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.watchapp.networking"
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

    testOptions {
        // Plain JVM unit tests (no Robolectric, no instrumentation per stream brief).
        // Returning default values keeps android.util.Log from blowing up under JUnit.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":app-shell:contracts"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
