pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx Android AAR (JNI + onnxruntime natives) publishes via JitPack.
        // Content-filtered to its own group so no other artifact resolves from here.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.k2-fsa")
            }
        }
    }
}

rootProject.name = "FrameNest"
include(":app")
