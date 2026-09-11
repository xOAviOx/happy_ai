import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets come from local.properties (gitignored) or the environment, never from
// source control. Absent values compile to empty strings; the code must degrade
// gracefully rather than crash. See section 12 of the build spec.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String = localProps.getProperty(name) ?: System.getenv(name) ?: ""

android {
    namespace = "com.happy.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.happy.assistant"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-phase0"

        buildConfigField("String", "GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${secret("SPOTIFY_CLIENT_ID")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Sideloaded personal build: keep it debuggable-ish and unobfuscated so
            // stack traces in LogActivity stay readable on the phone.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    androidResources {
        // The three openWakeWord models are memory-mapped straight out of assets.
        // Compressing them would force a copy to disk on every service start.
        noCompress += "tflite"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    // Present from Phase 0 so later phases add no new build plumbing.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Wake word inference. Interpreter.resizeInput is required: the mel model is
    // invoked with 1760 samples, not the 1280 it is initialised at.
    implementation(libs.tensorflow.lite)

    testImplementation(libs.junit)
}
