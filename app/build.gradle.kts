plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// VERSION (MAJOR.MINOR.PATCH[-PRERELEASE]) is the single source of truth; the release workflow checks it against the tag.
val appVersion: String = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.substringBefore('-').split(".").map { it.toInt() }
require(versionParts.size == 3) { "VERSION must be MAJOR.MINOR.PATCH, got '$appVersion'" }

// The NDK both AGP (stripping) and cargo-ndk (building the Rust core) use.
val ndkVersionPinned = "28.2.13676358"

android {
    namespace = "app.nya.remote"
    compileSdk = 36
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "app.nya.remote"
        minSdk = 26
        targetSdk = 36
        versionName = appVersion
        versionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Release signing comes from the environment (the release workflow restores the keystore from a secret).
    // Without it `assembleRelease` still works and produces an unsigned APK, which must never be published.
    val keystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (!keystoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!keystoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(17)
}

// The Rust core (../Cargo.toml, needs ../common next to this repo) as
// libnya_android.so for each ABI, via cargo-ndk. Always built optimized:
// a debug build is far too slow for video. Cargo itself skips unchanged work.
val rustAbis = listOf("arm64-v8a", "x86_64")
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Build the Rust core for Android with cargo-ndk"
    val out = layout.buildDirectory.dir("rustJniLibs").get().asFile
    val ndkDir = androidComponents.sdkComponents.ndkDirectory
    workingDir = rootProject.projectDir
    doNotTrackState("cargo tracks its own inputs")
    doFirst {
        environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
    }
    commandLine(
        listOf(System.getenv("CARGO") ?: "cargo", "ndk") +
            rustAbis.flatMap { listOf("-t", it) } +
            listOf("--platform", "26", "-o", out.absolutePath, "build", "--release", "--lib"),
    )
}
tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
