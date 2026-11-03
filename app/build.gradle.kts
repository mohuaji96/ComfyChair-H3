import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sh.hnet.comfychair"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.comfychair.h3"
        minSdk = 33
        targetSdk = 36
        versionCode = 77
        versionName = "0.9.8.668-h3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release keys live outside this repository and are never committed. Without them a
    // release build stays unsigned, which is the safe default for anyone who clones the source.
    // Looked up in order: an explicit env override, a signing/ folder beside this module, then
    // one beside the repository. None of these files are ever committed.
    val releaseKey = Properties().apply {
        val candidates = listOfNotNull(
            System.getenv("COMFYCHAIR_KEYSTORE_PROPERTIES")?.let { File(it) },
            rootProject.projectDir.parentFile?.resolve("signing/keystore.properties"),
            rootProject.projectDir.resolve("signing/keystore.properties")
        )
        // Read as UTF-8: the keystore path contains non-ASCII characters on this machine.
        candidates.firstOrNull { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
    }
    val hasReleaseKey = releaseKey.getProperty("storeFile") != null

    signingConfigs {
        // The published debug certificate keeps debug builds interchangeable between
        // machines. It is never used to sign anything we distribute.
        getByName("debug") {
            val bundled = file("../debug-signing/debug.keystore")
            if (bundled.exists()) {
                storeFile = bundled
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (hasReleaseKey) {
            create("releaseKey") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("releaseKey")
            } else {
                logger.lifecycle(
                    "No signing/keystore.properties next to the project: the release APK will be unsigned."
                )
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Disable baseline profile generation for F-Droid reproducible builds
// https://f-droid.org/docs/Reproducible_Builds/
tasks.whenTaskAdded {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

dependencies {
    testImplementation("org.json:json:20240303")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)

    // OkHttp for HTTP/HTTPS requests and WebSocket communication
    implementation(libs.okhttp)

    // Jetpack Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.text.google.fonts)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle and ViewModel for Compose
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation Compose
    implementation(libs.navigation.compose)

    // Media3 for video playback
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    // Markdown parsing
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    // Security (encrypted SharedPreferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}