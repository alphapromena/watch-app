// Stub — owned by Stream 2 (core-sensors). Provides a [SensorCollector] implementation
// over Wear OS Health Services + GPS. Filled in by Stream 2; this file only ensures
// the project compiles end-to-end during scaffold.
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
    api(project(":app-shell:contracts"))
    implementation(libs.kotlinx.coroutines.android)
}
