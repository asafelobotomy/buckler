plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.solon.bucklerhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.solon.bucklerhome"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "0.5.0"
        // Public Twitch Client ID for IGDB device-code login (no secret in APK).
        // Register at https://dev.twitch.tv/console and enable Device Code Grant.
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"\"")
        buildConfigField(
            "String",
            "GITHUB_RELEASES_URL",
            "\"https://api.github.com/repos/asafelobotomy/buckler/releases/latest\"",
        )
        buildConfigField(
            "String",
            "CURATED_COVERS_URL",
            "\"https://raw.githubusercontent.com/asafelobotomy/buckler/main/covers/index.json\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0-alpha10")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
