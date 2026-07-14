import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional local NAS settings for the FN-02 SMB spike harness.
// File is gitignored — never commit real hosts or passwords.
val smbLocalProps = Properties().apply {
    val file = rootProject.file("smb.local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun smbProp(key: String, default: String = ""): String =
    smbLocalProps.getProperty(key, default)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.framenest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.framenest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-wave1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // libVLC multi-ABI inflates APK (~200MB all ABIs). Keep common device +
        // emulator ABIs for debug; widen in release packaging if needed.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Pre-fill SMB spike UI only; empty defaults keep CI / clean builds safe.
        buildConfigField("String", "SMB_HOST", "\"${smbProp("smb.host")}\"")
        buildConfigField("int", "SMB_PORT", smbProp("smb.port", "445").ifBlank { "445" })
        buildConfigField("String", "SMB_USERNAME", "\"${smbProp("smb.username")}\"")
        buildConfigField("String", "SMB_PASSWORD", "\"${smbProp("smb.password")}\"")
        buildConfigField("String", "SMB_DOMAIN", "\"${smbProp("smb.domain")}\"")
        buildConfigField("String", "SMB_SHARE", "\"${smbProp("smb.share")}\"")
        buildConfigField("String", "SMB_PATH", "\"${smbProp("smb.path", "/")}\"")
        buildConfigField("String", "SMB_TEST_FILE", "\"${smbProp("smb.testFile")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
        }
        // libVLC ships multi-ABI .so; keep default merge, avoid stripping debug symbols needed by some OEMs.
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.navigation.compose)

    // FN-01: libVLC playback kernel spike
    implementation(libs.libvlc.all)

    // FN-02: SMBJ client + coroutines for IO
    implementation(libs.smbj)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // FN-05: playback history (Room) + ViewModel for product player
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
