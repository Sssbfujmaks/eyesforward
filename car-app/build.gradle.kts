plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.eyesforward.car"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.eyesforward.car"
        minSdk = 29
        targetSdk = 34
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
    useLibrary("android.car")
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
