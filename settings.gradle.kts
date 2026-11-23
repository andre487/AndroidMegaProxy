pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Maven Central rate-limits cold CI downloads of Robolectric's large runtime jars.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content {
                includeModule("org.robolectric", "android-all-instrumented")
                includeModule("org.robolectric", "nativeruntime-dist-compat")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MegaProxy"
include(":app", ":device-tests:headless")
