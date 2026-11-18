plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.devtool.usbmode"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devtool.usbmode"
        // Many head units report "Android 10" but actually run 8.1/9, so keep minSdk low.
        minSdk = 21
        targetSdk = 29       // match the head unit (Android 10)
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        getByName("debug") {
            // Some head-unit installers only accept v1 (JAR) signatures.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
}
