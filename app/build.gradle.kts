plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bookscan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bookscan.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // -Pemu builds an x86_64 copy for the test emulator; the tablet build is arm64 only
        ndk { abiFilters += if (project.hasProperty("emu")) listOf("x86_64") else listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("bookscan.jks")
            storePassword = "bookscan"
            keyAlias = "bookscan"
            keyPassword = "bookscan"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }
}

dependencies {
    implementation("org.opencv:opencv:4.10.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
