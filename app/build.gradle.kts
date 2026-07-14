import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional local NAS settings for the debug SMB spike harness.
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
        versionCode = 4
        versionName = "0.3.1-internal"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Pre-fill debug spike UI only; empty defaults keep CI / clean builds safe.
        buildConfigField("String", "SMB_HOST", "\"${smbProp("smb.host")}\"")
        buildConfigField("int", "SMB_PORT", smbProp("smb.port", "445").ifBlank { "445" })
        buildConfigField("String", "SMB_USERNAME", "\"${smbProp("smb.username")}\"")
        buildConfigField("String", "SMB_PASSWORD", "\"${smbProp("smb.password")}\"")
        buildConfigField("String", "SMB_DOMAIN", "\"${smbProp("smb.domain")}\"")
        buildConfigField("String", "SMB_SHARE", "\"${smbProp("smb.share")}\"")
        buildConfigField("String", "SMB_PATH", "\"${smbProp("smb.path", "/")}\"")
        buildConfigField("String", "SMB_TEST_FILE", "\"${smbProp("smb.testFile")}\"")
    }

    // Per-ABI APKs shrink install size (libVLC is the bulk).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Emulator + modern phones for local debug.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.libvlc.all)

    implementation(libs.smbj)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
