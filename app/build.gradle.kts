import java.io.File
import org.gradle.api.attributes.Attribute

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("MEGAPROXY_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("MEGAPROXY_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MEGAPROXY_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MEGAPROXY_KEY_PASSWORD").orNull
val versionCodeBase = 12
val versionVariant = providers.gradleProperty("megaproxyVersionVariant")
    .orElse("universal")
    .get()
val versionVariantCode = mapOf(
    "universal" to 0,
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)[versionVariant] ?: throw GradleException("Unsupported MegaProxy version variant: $versionVariant")
val gitCommitHash = providers.environmentVariable("GITHUB_SHA")
    .orElse(providers.environmentVariable("MEGAPROXY_GIT_COMMIT"))
    .orElse(providers.exec {
        commandLine("git", "rev-parse", "--short=8", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText)
    .map { value ->
        value.trim().take(8).takeIf { it.matches(Regex("[0-9a-fA-F]{7,8}")) } ?: "unknown"
    }
    .get()

android {
    namespace = "net.megaproxy487"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "net.megaproxy487"
        minSdk = 26
        targetSdk = 36
        // Each APK has a unique, monotonically ordered code. Keeping the
        // universal code below the ABI variants lets app stores prefer the
        // smaller compatible APK when both are available.
        versionCode = versionCodeBase * 1000 + versionVariantCode
        versionName = "0.0.12"
        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitCommitHash\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (versionVariant != "universal") {
            // Keep every ABI-specific APK genuinely single-ABI. Without this,
            // transitive native libraries are packaged for every architecture
            // even when the Go core itself was built for only one target.
            ndk {
                abiFilters += versionVariant
            }
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null && releaseKeystorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Both bundled locales must remain available to the in-app language selector.
    bundle { language { enableSplit = false } }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.01.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    runtimeOnly(files("libs/megaproxy.aar"))
}

// fwcd.kotlin does not understand Android Gradle Plugin variants reliably. Its language server
// invokes the repository's kls-classpath script, which uses this task to expose the same classpath
// as the debug Kotlin compiler, including Android's boot classes and generated R/BuildConfig code.
tasks.register("kotlinLanguageServerClasspath") {
    dependsOn("processDebugResources")
    doLast {
        val generatedEntries = listOf(
            layout.buildDirectory.file("intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar").get().asFile,
            layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes").get().asFile,
        )
        val compileJars = configurations.getByName("debugCompileClasspath").incoming.artifactView {
            attributes.attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar")
        }.files.files
        val entries = (
            compileJars +
                android.bootClasspath +
                generatedEntries.filter { it.exists() }
            ).map { it.absolutePath }.distinct()
        println(entries.joinToString(File.pathSeparator))
    }
}
