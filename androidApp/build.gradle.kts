plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Name and version come from gradle.properties (0.2.30)
val appName = providers.gradleProperty("peersync.appName").get()
val appVersion = providers.gradleProperty("peersync.version").get()
val appVersionCode = providers.gradleProperty("peersync.versionCode").get().toInt()

android {
    namespace = "com.example.peersync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.peersync"
        minSdk = 36
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        manifestPlaceholders["appName"] = appName
        ndk {
            // Only real devices (arm64) and emulators (x86_64), to keep the APK small
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                // Desktop natives bundled in computer.iroh:iroh (JVM jar) aren't needed
                "/win32-x86-64/**", "/darwin-aarch64/**", "/linux-x86-64/**", "/linux-aarch64/**",
            )
        }
        jniLibs {
            // Store libiroh_ffi.so uncompressed (no extraction, faster startup)
            useLegacyPackaging = false
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // PoC: release is signed with the debug key too (replace for distribution)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // False positive: iroh-ffi generated code calls java.lang.ref.Cleaner behind a guard
        disable += setOf("NewApi")
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    // Keep Compose itself on the same Compose Multiplatform version as shared
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(libs.compose.material3.experimental) // Includes M3 Expressive (replaces compose.material3, 0.2.17)
    implementation(compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx) // lifecycleScope (converting picker results)
    implementation(libs.kotlinx.coroutines.android)

    // For IrohAndroid.installAndroidContext (the AAR also comes in via shared)
    implementation(libs.iroh.android)

    // QR scanning: Google Code Scanner (0.2.38, replaces zxing-android-embedded). The camera screen is
    // Google Play services', so no CAMERA permission
    implementation(libs.play.services.code.scanner)
}
