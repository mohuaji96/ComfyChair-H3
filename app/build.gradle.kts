import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Build fails if either version field contains a 4.
 *
 * This is a standing preference of the person this build is for, and it has been broken twice by
 * hand. Checking it here turns a typo into a build error instead of a version that has to be
 * skipped afterwards.
 */
val appVersionCode = 376
val appVersionName = "0.9.8.8382.616-h3"
require(!appVersionCode.toString().contains('4')) {
    "versionCode $appVersionCode 含 4，请跳到下一个数字"
}
require(!appVersionName.contains('4')) {
    "versionName $appVersionName 含 4，请跳到下一个版本号"
}

android {
    namespace = "sh.hnet.comfychair"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.comfychair.h3"
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 德/西/法/波四个翻译包留在源码里当底稿，但不打进包：语言选择只提供中英。
    androidResources {
        localeFilters += listOf("en", "zh")
    }

    val releaseKey = Properties().apply {
        val candidates = listOfNotNull(
            System.getenv("COMFYCHAIR_KEYSTORE_PROPERTIES")?.let { File(it) },
            rootProject.projectDir.parentFile?.resolve("signing/keystore.properties"),
            rootProject.projectDir.resolve("signing/keystore.properties")
        )
        candidates.firstOrNull { it.exists() }?.reader(Charsets.UTF_8)?.use { load(it) }
    }
    val hasReleaseKey = releaseKey.getProperty("storeFile") != null

    signingConfigs {
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
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // AGP 9.5 prepares Compose previews for every variant, which needs the app's resources in unit tests
    testOptions {
        unitTests.isIncludeAndroidResources = true
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

val verifyAssets = tasks.register("verifyAssets") {
    val assetsDir = file("src/main/assets")
    val allowedFiles = setOf(
        "tag_catalog.json",
        "tag_catalog.previous.json",
        "story_catalog.json",
        "tag_catalog_sfw.json",
        "story_catalog_sfw.json",
        "prompt_expert.md",
        "prompt_expert_lite.md",
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
    implementation(libs.commonmark.ext.gfm.tables)

    // Security (encrypted SharedPreferences)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

/**
 * Copies this version's F-Droid changelogs (metadata/<locale>/changelogs/<versionCode>.txt, English and any
 * translations written for the release) into the app's assets as whats_new/<locale>.txt, for the What's new page.
 * Without an English one, the app shows no What's new.
 */
abstract class WhatsNewAssetTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelogs: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = File(outputDir.get().asFile, "whats_new")
        outputDir.get().asFile.deleteRecursively()
        dir.mkdirs()
        // metadata/<locale>/changelogs/<versionCode>.txt -> whats_new/<locale>.txt
        changelogs.files.filter { it.isFile }.forEach { it.copyTo(File(dir, "${it.parentFile.parentFile.name}.txt")) }
    }
}

val whatsNewAsset = tasks.register<WhatsNewAssetTask>("whatsNewAsset") {
    changelogs.from(
        rootProject.fileTree("metadata") { include("*/changelogs/${android.defaultConfig.versionCode}.txt") }
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(whatsNewAsset, WhatsNewAssetTask::outputDir)
    }
}
