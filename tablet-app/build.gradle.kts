plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.eyesforward.tablet"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.eyesforward.tablet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("demo") {
            storeFile = rootProject.file("keystore/eyesforward-demo.jks")
            storePassword = "eyesforward"
            keyAlias = "eyesforward"
            keyPassword = "eyesforward"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("demo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
