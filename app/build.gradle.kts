import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Secrets come from local.properties or the environment (CI secrets), never from the repo.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { stream -> this.load(stream) }
}
fun secret(name: String) = (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim().replace("\"", "")
val traktId = secret("TRAKT_CLIENT_ID")
val traktSecret = secret("TRAKT_CLIENT_SECRET")
val tmdb = (localProps.getProperty("TMDB_ACCESS_TOKEN") ?: System.getenv("TMDB_ACCESS_TOKEN") ?: "").trim().replace("\"", "")

// docs/WHATS-NEW.md goes inside the app, for the "What's new" pop-up after an update, with its
// translations from docs/whats-new/<language>.md (as whats_new_<language>.md).
val whatsNewDir = layout.buildDirectory.dir("generated/whatsnew")
val copyWhatsNew by tasks.registering(Copy::class) {
    from(rootProject.file("docs/WHATS-NEW.md")) { rename { "whats_new.md" } }
    from(rootProject.file("docs/whats-new")) { include("*.md"); rename { "whats_new_$it" } }
    into(whatsNewDir)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(copyWhatsNew) }

// Smart Add › Choose an image: Tesseract's English text model (tessdata_fast, Apache-2.0), fetched when building.
val tessDir = layout.buildDirectory.dir("generated/tessdata")
val fetchTessData by tasks.registering {
    val out = tessDir.map { it.file("tessdata/eng.traineddata") }
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        if (f.exists() && f.length() > 1_000_000) return@doLast
        f.parentFile.mkdirs()
        java.net.URI("https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata").toURL().openStream().use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(fetchTessData) }

android {
    namespace = "com.vantaview.app"
    compileSdk = 35

    sourceSets["main"].assets.srcDir(whatsNewDir)
    sourceSets["main"].assets.srcDir(tessDir)

    defaultConfig {
        applicationId = "com.vantaview.app"
        // When this APK was built (for the update check when a release has no version.txt).
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        // TMDB read access token for Movies and TV Series tiles: from local.properties, or the
        // TMDB_ACCESS_TOKEN environment variable (a GitHub Actions secret on CI). Never in the repo.
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdb\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktId\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"$traktSecret\"")
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("VV_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("VV_VERSION") ?: ("0.1.0-test" + (System.getenv("GITHUB_RUN_NUMBER")?.let { ".$it" } ?: ""))
        // AI subtitles (whisper.cpp) for phones, tablets, TV boxes, emulators (Android TV images are x86).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static") } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Shared test key so each test build installs over the last one. Not for real releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        // Test builds and releases are both built as "release": not debuggable, so Android compiles the app
        // properly and it starts and runs much faster (a debug build took ~10 s to open on a Fire TV Stick).
        // Signed with the same shared key as before, so it installs over earlier builds.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // java.time (dates from add-ons, debrid and GitHub) needs this on Android 7 and 7.1 (Fire OS 6 and older boxes).
        isCoreLibraryDesugaringEnabled = true
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
    // Playing torrents without a debrid account (MIT licence; libtorrent itself is BSD).
    val libtorrent4j = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$libtorrent4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrent4j")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // Backups: QR codes for sending to a phone, and saving into a folder the user picks.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-android-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    // Reading playlist details from a picture (Smart Add), on the device. Apache-2.0.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
