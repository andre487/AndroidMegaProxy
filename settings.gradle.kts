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
        // Maven Central rate-limits cold CI downloads of Robolectric's Android SDK jars.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeModule("org.robolectric", "android-all-instrumented") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MegaProxy"
include(":app", ":device-tests:headless")
