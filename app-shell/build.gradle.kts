plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.watchapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchapp"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Optional pre-bake of the FCAF v2 server target. Override per-build
        // with `-PdefaultHost=...` / `-PdefaultPort=...` (see INTEGRATION_NOTES
        // §17). With no -P flags this produces an APK with the same defaults
        // as before BuildConfig was introduced (empty host, port 5088), so
        // the existing manual debug-screen flow still works.
        buildConfigField(
            "String", "DEFAULT_HOST",
            "\"${project.findProperty("defaultHost") ?: ""}\"",
        )
        buildConfigField(
            "int", "DEFAULT_PORT",
            "${project.findProperty("defaultPort") ?: 5088}",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Required on AGP 8+ to generate BuildConfig (opt-in).
        buildConfig = true
    }
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":core-networking"))
    implementation(project(":core-sensors"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.play.services.wearable)

    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
