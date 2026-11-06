import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3.experimental) // Includes M3 Expressive (replaces compose.material3, 0.2.17)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // Auto-start (HKCU\…\Run). 0.2.29 (the known-folder API for "Downloads" was dropped in 0.2.31)
    implementation(libs.jna.platform)

    // QR scanning (webcam): grab frames with OpenCV (bundled natives), decode with ZXing
    implementation(libs.opencv)
    implementation(libs.zxing.core)
}

// Name and version come from gradle.properties (0.2.30)
val appName = providers.gradleProperty("peersync.appName").get()
val appVersion = providers.gradleProperty("peersync.version").get()

compose.desktop {
    application {
        mainClass = "com.example.peersync.desktop.MainKt"

        nativeDistributions {
            // Delivery format: app image with bundled JRE (a full folder).
            //   ./gradlew :desktopApp:createDistributable
            // → desktopApp/build/compose/binaries/main/app/PeerSync/
            targetFormats(TargetFormat.AppImage)
            packageName = appName
            packageVersion = appVersion
            description = "$appName - send files and text between devices with iroh"
            vendor = appName

            // Needed for native loading by JNA (iroh bindings) and OpenCV
            modules("jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/app.ico"))
            }
        }
    }
}
