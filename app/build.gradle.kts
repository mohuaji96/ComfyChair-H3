import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Build fails if either version field contains a 4.
 *
 * This is a standing preference of the person this build is for, and it has been broken twice by
 * hand. Checking it here turns a typo into a build error instead of a version that has to be
 * skipped afterwards.
 */
// 184 carries a 4, so the code jumps to 185; the name follows the user's own numbering.
val appVersionCode = 321
val appVersionName = "0.9.8.8382.571-h3"
require(!appVersionCode.toString().contains('4')) {
    "versionCode $appVersionCode 含 4，请跳到下一个数字"
}
require(!appVersionName.contains('4')) {
    "versionName $appVersionName 含 4，请跳到下一个版本号"
}

android {
    namespace = "sh.hnet.comfychair"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.comfychair.h3"
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

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

// src/main/assets ships wholesale, so a stray backup or scratch file rides into the APK and
// nobody notices until someone unzips a release. Fail the build instead: a catalog copy
// (`tag_catalog.json.v24.bak`) already made it into 0.9.8.8298 this way.
val verifyAssets = tasks.register("verifyAssets") {
    val assetsDir = file("src/main/assets")
    // tag_catalog.previous.json is the snapshot the first automatic library upgrade diffs
    // against; without it a word the new file deleted looks the same as one the user typed.
    val allowedFiles = setOf(
        "tag_catalog.json",
        "tag_catalog.previous.json",
        "story_catalog.json",
        // SFW 两档各自一整套，文件边界就是审核边界：SFW 侧只认这两个文件。
        "tag_catalog_sfw.json",
        "story_catalog_sfw.json",
        "prompt_expert.md",
        "prompt_expert_lite.md",
        // 运行时断言用；与 tools 下同名文件是同一份内容，由 verifySfwGuards 比对。
        "forbidden_nsfw.txt",
        "allow_sfw.txt"
    )
    inputs.dir(assetsDir)
    doLast {
        val unexpected = assetsDir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in allowedFiles }
            .map { it.name }
        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "Unexpected files under src/main/assets: $unexpected. " +
                    "Move them out or add them to allowedFiles in build.gradle.kts."
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyAssets) }

// 禁词表只能有一份内容。tools 那份给 check_sfw.py，assets 那份给 App 运行时；
// 两份一旦漂移，就会出现「脚本说 0 错、App 还在抽」——那种错查起来最费时间。
val verifySfwGuards = tasks.register("verifySfwGuards") {
    val names = listOf("forbidden_nsfw.txt", "allow_sfw.txt")
    inputs.files(names.map { file("../tools/$it") } + names.map { file("src/main/assets/$it") })
    doLast {
        for (name in names) {
            val canonical = file("../tools/$name")
            val shipped = file("src/main/assets/$name")
            if (!canonical.exists() || !shipped.exists()) {
                throw GradleException(
                    "规则表缺失：tools/$name 与 app/src/main/assets/$name 必须同时存在"
                )
            }
            if (canonical.readText(Charsets.UTF_8) != shipped.readText(Charsets.UTF_8)) {
                throw GradleException(
                    "两份 $name 不一致：改完 tools/ 下那份要同步到 app/src/main/assets/"
                )
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifySfwGuards) }

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









