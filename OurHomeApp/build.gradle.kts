// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no
// org.jetbrains.kotlin.android plugin here — only the compiler plugins.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Instant alerts (Firebase Cloud Messaging) are optional: the plugin is only
// applied when this install's google-services.json (gitignored) is present.
// Without it the app builds and runs normally on its hourly check alone.
// Setup: docs/push-notifications.md in the web repo.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// Private per-install settings (gitignored). See config.example.properties.
val appConfig = Properties().apply {
    val f = rootProject.file("config.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.eosoclub.ourhome"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.eosoclub.ourhome"
        // Android 12+: notification actions can require device unlock
        // (setAuthenticationRequired), which the lock-screen rules depend on.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // Default server from config.properties; blank means the sign-in
        // screen asks for it. Always editable there.
        val baseUrl = appConfig.getProperty("server.url", "").trim()
        buildConfigField("String", "DEFAULT_BASE_URL", "\"$baseUrl\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
}
