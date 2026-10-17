plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.zoomtranscriber.huawei"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.zoomtranscriber.huawei"
        minSdk = 26 // Kompatibel penuh dengan HarmonyOS 2/3/4 & EMUI di seluruh seri Huawei MatePad
        targetSdk = 34
        versionCode = 50
        versionName = "5.0.0-HuaweiTablet"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ditandatangani dengan debug signing agar langsung bisa diinstal di Huawei Tablet
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // On-Device Bundled Text Recognition (100% berjalan di dalam APK, TIDAK butuh Google Play Services / GMS,
    // sehingga berjalan mulus di semua Tablet Huawei MatePad HarmonyOS / EMUI!)
    implementation("com.google.mlkit:text-recognition:16.0.0")
}
