plugins {
    id("com.android.application")
}

android {
    namespace = "net.megaproxy487.test.headless"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 26
        targetSdk = 36
    }
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
    it.enable = false
}
