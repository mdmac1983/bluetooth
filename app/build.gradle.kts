plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.orionmd.btvoicetype"
    compileSdk = 33

    defaultConfig {
        applicationId = "app.orionmd.btvoicetype"
        minSdk = 29
        targetSdk = 29
        versionCode = 4
        versionName = "1.3"
        ndk {
            // ALPS/MediaTek Android 10 Go Edition tablet is 32-bit ARM only
            abiFilters += listOf("armeabi-v7a")
        }
    }

    signingConfigs {
        // Fixed, checked-in debug key (app/keystore/debug.keystore) instead of the AGP-default
        // ~/.android/debug.keystore. That default is auto-generated with a fresh random key the
        // first time it's needed on any given machine — meaning every CI run on a clean GitHub
        // Actions runner got a *different* signing key, so each build was seen by Android as a
        // different app and could never be installed as an update over the previous one (silent
        // "App not installed" / signature-mismatch failure). Pinning one key here makes every
        // build, from CI or anywhere else, update in place.
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank())
                signingConfigs.getByName("debug") else signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }
    packaging {
        resources.excludes.add("META-INF/*")
    }

    lint {
        // targetSdk is intentionally pinned to 29 to match this app's target
        // hardware (an Android 10 Go Edition tablet) — don't fail CI over it.
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.7.2")

    // Fully on-device, offline speech recognition. This tablet has no Google app / no system
    // speech recognition service installed at all, so Android's built-in SpeechRecognizer API
    // has nothing to bind to — Vosk bundles its own engine + model directly into the APK instead,
    // needing nothing from the OS beyond microphone access.
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
