plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.floatingkit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.floatingkit"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Backend cache URL (see backend/). Empty = direct Yahoo/Binance from phone.
        // Release: set to your Northflank URL, e.g. "https://xxx.code.run"
        buildConfigField("String", "SERVER_BASE", "\"\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
