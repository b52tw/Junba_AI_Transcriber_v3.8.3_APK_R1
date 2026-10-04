plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tw.junba.transcriber"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.junba.transcriber"
        minSdk = 26
        targetSdk = 35
        versionCode = 383
        versionName = "3.8.2-apk-r1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // whisper-android already decodes WAV/MP3/FLAC internally. Other common
    // Android formats are converted with MediaCodec in AudioPreprocessor.kt.
    // Do NOT add a separate FFmpegKit audio artifact here: it duplicates native runtimes and was
    // the source of the previous libc++_shared.so / FFmpegKitConfig failures.
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
