// Stub — owned by Stream 1 (core-networking). Provides a [Streamer] implementation
// over raw TCP using FCAF v2 framing. Filled in by Stream 1; this file only ensures
// the project compiles end-to-end during scaffold.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
}

dependencies {
    api(project(":app-shell:contracts"))
    implementation(libs.kotlinx.coroutines.android)
}
