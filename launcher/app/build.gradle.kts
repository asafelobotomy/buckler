import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val githubRepo = "asafelobotomy/buckler"

// Release signing: put storeFile/storePassword/keyAlias/keyPassword in launcher/keystore.properties
// (git-ignored). Without it the release APK falls back to the machine-local debug key, and OTA
// updates only work when every build comes from that same machine.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it).isFile } == true

android {
    namespace = "dev.solon.bucklerhome"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.solon.bucklerhome"
        minSdk = 28
        targetSdk = 34
        versionCode = 13
        versionName = "0.13.0"
        // Public Twitch Client ID for IGDB device-code login (no secret in APK).
        // Register at https://dev.twitch.tv/console and enable Device Code Grant.
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        buildConfigField(
            "String",
            "GITHUB_RELEASES_URL",
            "\"https://api.github.com/repos/$githubRepo/releases/latest\"",
        )
        buildConfigField(
            "String",
            "CURATED_COVERS_URL",
            "\"https://raw.githubusercontent.com/$githubRepo/main/covers/index.json\"",
        )
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.core:core:1.19.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.zxing:core:3.5.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android.jar stubs org.json for unit tests; use the real implementation on the JVM.
    testImplementation("org.json:json:20260814")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
