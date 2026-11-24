plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber: String = System.getenv("BUILD_NUMBER") ?: "1"

android {
    namespace = "org.lior.gymbro"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.lior.gymbro"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber.toInt()
        versionName = "1.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // committed on purpose: every build signs with the same key, so each new APK installs over the last one
            // (an update that keeps the app's own copy of the data, too)
            storeFile = file("keystore/gymbro.p12")
            storePassword = "gymbro123"
            keyAlias = "gymbro"
            keyPassword = "gymbro123"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // the camera: the QR scanner's preview and analysis, and the station photos
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // the barcode reader, with its model inside the app (no Google Play download needed)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
