import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            // computer.iroh:iroh (JVM) requires JVM 21+, so desktop targets 21.
            // (Android is resolved by AGP, so 17 is fine there)
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // iOS targets real devices only (SideStore / LiveContainer).
    // Built on a GitHub Actions macOS runner, where Xcode (project.yml
    // preBuildScripts) runs linkReleaseFrameworkIosArm64.
    iosArm64 {
        compilations.getByName("main").cinterops.create("peersyncIroh") {
            defFile(project.file("src/nativeInterop/cinterop/peersync_iroh.def"))
            includeDirs(project.file("../native/peersync-iroh/include"))
        }
        binaries.framework {
            baseName = "PeerSyncShared"
            isStatic = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(libs.compose.material3.experimental) // includes M3 Expressive (replaces compose.material3, 0.2.17)
                implementation(compose.ui)
                implementation(libs.compose.icons.extended)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.qrose)
                implementation(libs.jetbrains.lifecycle.runtime.compose) // Device Hub online checks (0.2.36)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        // JVM-only code shared by Android and desktop (JVM).
        // Code using the official iroh Kotlin bindings (computer.iroh) and java.io lives here.
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // Exclude JNA: on Android, iroh-android brings the AAR JNA,
                // and a duplicate jar breaks the build. desktopMain adds the jar for desktop.
                implementation("computer.iroh:iroh:${libs.versions.iroh.get()}") {
                    exclude(group = "net.java.dev.jna", module = "jna")
                }
            }
        }

        val androidMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.activity.compose)
                // AAR with libiroh_ffi.so for each ABI and IrohAndroid (Context setup).
                // The classes themselves come from computer.iroh:iroh (jvmCommonMain).
                implementation(libs.iroh.android)
            }
        }

        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.jna) // needed for native calls of iroh (JVM)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val iosMain by getting
    }
}

android {
    namespace = "com.example.peersync.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 36
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                // Android doesn't need the desktop natives bundled in computer.iroh:iroh (JVM jar)
                "/win32-x86-64/**", "/darwin-aarch64/**", "/linux-x86-64/**", "/linux-aarch64/**",
            )
        }
    }
}
