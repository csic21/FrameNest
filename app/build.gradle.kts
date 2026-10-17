import java.security.MessageDigest
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Optional native experiment: ordinary builds retain the ML Kit-only artifact.
val hyMtNative = providers.gradleProperty("hyMtNative").map(String::toBooleanStrict).getOrElse(false)

// FN-77: no experiment source is compiled into app/default/release source sets.
val rollingCaptionDiagnosis = providers.gradleProperty("rollingCaptionDiagnosis").map(String::toBooleanStrict).getOrElse(false)
val rollingCaptionSources = if (rollingCaptionDiagnosis) tasks.register<Sync>("prepareRollingCaptionDiagnosisSources") {
    from(rootProject.file("scripts/experimental/local-agreement")) {
        include("RollingLocalAgreement.kt", "ReversibleAgreementKey.kt")
    }
    from(rootProject.file("scripts/experimental/baseline-pair-planner")) {
        include("BaselinePairPlanner.kt")
    }
    into(layout.buildDirectory.dir("generated/rollingCaptionDiagnosis/java"))
} else null

// FN-74: explicit experimental profile; ordinary builds retain the stock dependency.
// File dependencies bypass Gradle's Maven checksum metadata, so verify the exact
// reviewed AAR here, before it can become a compiler/packager input. No fallback.
val sherpaAsrSource = providers.gradleProperty("sherpaAsrSource").map(String::toBooleanStrict).getOrElse(false)
val sherpaSourceSha256 = "6a243d2da9a1f521a85ceeb30b51e00a660e2a1bfb05b79f6b591361af7c7503"
val sherpaSourceAar = layout.projectDirectory.file("libs/sherpa-onnx-1.13.8-asr-no-tts.aar").asFile
val sherpaRuntimeVersion = if (sherpaAsrSource) "1.13.8" else libs.versions.sherpaOnnx.get()
val sherpaRuntimeIdentity = if (sherpaAsrSource) {
    "1.13.8-asr-no-tts-sha256-$sherpaSourceSha256"
} else {
    sherpaRuntimeVersion
}
if (sherpaAsrSource) {
    check(sherpaSourceAar.isFile) {
        "Missing reviewed source AAR. See docs/experiments/FN-74-asr-source-app/README.md."
    }
    val actualSha256 = MessageDigest.getInstance("SHA-256").run {
        sherpaSourceAar.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                update(buffer, 0, count)
            }
        }
        digest().joinToString("") { "%02x".format(it) }
    }
    check(actualSha256 == sherpaSourceSha256) { "Source AAR SHA-256 mismatch; refusing build." }
}

// Recheck at execution as well: an existing/configuration-cached build must not
// package a file replaced after project configuration. This task has no outputs.
val verifySourceAsrAar = tasks.register("verifySourceAsrAar") {
    group = "verification"
    if (sherpaAsrSource) {
        inputs.file(sherpaSourceAar)
        inputs.property("expectedSha256", sherpaSourceSha256)
        doLast {
            val sha256 = MessageDigest.getInstance("SHA-256")
            inputs.files.singleFile.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha256.update(buffer, 0, count)
                }
            }
            check(sha256.digest().joinToString("") { "%02x".format(it) } == inputs.properties["expectedSha256"]) {
                "Source AAR changed; refusing build."
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifySourceAsrAar) }

android {
    namespace = "com.framenest"
    compileSdk = 36
    if (hyMtNative) {
        ndkVersion = "28.2.13676358"
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    defaultConfig {
        applicationId = "com.framenest"
        minSdk = 26
        targetSdk = 36
        versionCode = 22
        versionName = "0.6.11-internal"
        buildConfigField("boolean", "HY_MT_NATIVE", hyMtNative.toString())
        buildConfigField("boolean", "SHERPA_ASR_SOURCE", sherpaAsrSource.toString())
        // Upstream VersionInfo returns the plain version. Keep the artifact identity separate.
        buildConfigField("String", "SHERPA_RUNTIME_VERSION", "\"$sherpaRuntimeVersion\"")
        buildConfigField("String", "SHERPA_RUNTIME_IDENTITY", "\"$sherpaRuntimeIdentity\"")
        buildConfigField("String", "SHERPA_AAR_SHA256", "\"${if (sherpaAsrSource) sherpaSourceSha256 else ""}\"")
        if (hyMtNative) {
            externalNativeBuild {
                cmake {
                    arguments += "-DANDROID_STL=c++_static"
                    targets += "framenest_hymt"
                }
            }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Never compile local NAS details into a distributable APK. The debug
        // spike accepts runtime input; these constants only preserve safe defaults.
        buildConfigField("String", "SMB_HOST", "\"\"")
        buildConfigField("int", "SMB_PORT", "445")
        buildConfigField("String", "SMB_USERNAME", "\"\"")
        buildConfigField("String", "SMB_PASSWORD", "\"\"")
        buildConfigField("String", "SMB_DOMAIN", "\"\"")
        buildConfigField("String", "SMB_SHARE", "\"\"")
        buildConfigField("String", "SMB_PATH", "\"/\"")
        buildConfigField("String", "SMB_TEST_FILE", "\"\"")
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

    // Release keys are supplied by the maintainer/Actions, never generated by
    // the build. The publishing workflow also pins the owner-confirmed target certificate.
    signingConfigs {
        create("release") {
            providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull?.let {
                storeFile = file(it)
            }
            storePassword = providers.environmentVariable("ANDROID_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Keep the universal debug APK limited to the two supported ABIs.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Match the split outputs; Play/App Bundle delivery still installs one ABI.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
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
        if (sherpaAsrSource) {
            // JNI is already explicitly stripped with pinned NDK evidence; preserve
            // those reviewed bytes (and unchanged vendor ORT) into the actual APK.
            jniLibs.keepDebugSymbols += setOf("**/libsherpa-onnx-jni.so", "**/libonnxruntime.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
            // Kuromoji core/IPADIC carry identical upstream notices. Merge notices
            // instead of dropping legal attribution or applying a broad pickFirst.
            merges += setOf("META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md")
        }
    }

    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
    if (rollingCaptionDiagnosis) {
        // AGP built-in Kotlin does not compile .kt from additional java directories.
        sourceSets.getByName("androidTest").kotlin.directories.add("$projectDir/src/rollingCaptionDiagnostic/java")
        sourceSets.getByName("androidTest").kotlin.directories.add(
            layout.buildDirectory.dir("generated/rollingCaptionDiagnosis/java").get().asFile.absolutePath,
        )
    }
    if (sherpaAsrSource) {
        sourceSets.getByName("main").assets.directories.add("$projectDir/src/sourceAsr/assets")
    }
}

if (rollingCaptionSources != null) {
    tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach {
        dependsOn(rollingCaptionSources)
    }
}

// Fail closed for release packaging, while keeping ordinary debug CI usable
// without signing secrets. No unsigned release or fresh debug-key fallback.
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    group = "verification"
    description = "Require maintainer-supplied signing material before release builds."
    doLast {
        check(sherpaAsrSource) { "Release requires the exact source-ASR profile; legacy is debug-only." }
        check(!hyMtNative) { "Hy-MT native remains disabled for this release." }
        val acceptance = JsonSlurper().parse(rootProject.file(".github/source-asr-release-approval.json")) as Map<*, *>
        check(acceptance["approved"] == true && acceptance["aar_sha256"] == sherpaSourceSha256 &&
            acceptance["source_app_commit"] == "6216ecc3ad6cfe7a0cbb2100140ff4a7f9c788d0" &&
            acceptance["runtime_report_sha256"] is String &&
            Regex("[0-9a-f]{64}").matches(acceptance["runtime_report_sha256"] as String)) {
            "Source-ASR release awaits explicit runtime acceptance; refusing signing."
        }
        val required = listOf(
            "ANDROID_KEYSTORE_PATH", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD",
        )
        check(required.all { !providers.environmentVariable(it).orNull.isNullOrBlank() }) {
            "Release signing is not configured. See docs/RELEASING.md; refusing an unsigned release."
        }
        check(file(providers.environmentVariable("ANDROID_KEYSTORE_PATH").get()).isFile) {
            "The configured release keystore is missing."
        }
    }
}

tasks.matching {
    it.name in setOf(
        "preReleaseBuild", "validateSigningRelease", "packageRelease", "packageReleaseBundle", "signReleaseBundle",
    )
}.configureEach {
    dependsOn(requireReleaseSigning)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // FN-68: override vulnerable transitives from ML Kit and SMBJ only.
    constraints {
        implementation("com.squareup.okhttp3:okhttp:4.12.0") {
            because("Fix certificate validation advisories in ML Kit's OkHttp 3.0.0")
        }
        implementation("com.squareup.okio:okio:3.6.0") {
            because("Fix malformed gzip denial of service; compatible with OkHttp 4.12")
        }
        implementation("org.bouncycastle:bcprov-jdk18on:1.85") {
            because("Fix GOST, LDAP and ASN.1/name-constraint advisories in SMBJ's 1.79")
        }
    }
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
    // Cover frames call libvlc_video_set_callbacks. The AAR supplies the
    // Android JNI dispatcher; vosk/sherpa must not also package com.sun.jna.
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }

    implementation(libs.smbj)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)

    // FN-14: on-device listen-translate (Vosk ASR + ML Kit MT).
    implementation(libs.vosk.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    // FN-51: SenseVoice-Small int8 ASR via sherpa-onnx (default engine).
    // AAR bundles the JNI bridge + onnxruntime natives for all ABIs; the
    // arm64-v8a/x86_64 abiFilters above keep only the two shipped ABIs.
    if (sherpaAsrSource) {
        implementation(files(sherpaSourceAar))
    } else {
        implementation(libs.sherpa.onnx) {
            exclude(group = "net.java.dev.jna", module = "jna")
        }
    }
    implementation(libs.mlkit.translate)
    implementation(libs.kotlinx.coroutines.play.services)

    // Japanese caption units use the local dictionary only; no network service.
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
