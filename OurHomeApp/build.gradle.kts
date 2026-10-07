// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there is no
// org.jetbrains.kotlin.android plugin here — only the compiler plugins.
import org.yaml.snakeyaml.Yaml

// SnakeYAML on the build classpath, to read settings.yml below.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath(libs.snakeyaml) }
}

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

// Private per-install settings (gitignored). See settings.example.yml.
val appSettings: Map<*, *> = rootProject.file("settings.yml").let { f ->
    if (!f.exists()) emptyMap<Any, Any>()
    else f.reader().use { Yaml().load<Any?>(it) } as? Map<*, *> ?: emptyMap<Any, Any>()
}

/** A value from settings.yml by key path, e.g. setting("server", "url"); "" if unset. */
fun setting(vararg path: String): String {
    var node: Any? = appSettings
    for (key in path) node = (node as? Map<*, *>)?.get(key)
    return node?.toString()?.trim().orEmpty()
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

        // Default server from settings.yml (server.url); blank means the
        // sign-in screen asks for it. Always editable there.
        val baseUrl = setting("server", "url")
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
