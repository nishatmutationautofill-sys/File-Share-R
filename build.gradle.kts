plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rishat.fileshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rishat.fileshare"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.0"
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
    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
}

dependencies {
    // WebRTC সরানো হয়েছে: একই-Wi-Fi মোড এখন নিজস্ব দ্রুত LAN P2P ইঞ্জিন ব্যবহার করে (APK ~১৫MB ছোট)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
