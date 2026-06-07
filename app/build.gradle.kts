plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // FIX: Wymagany plugin dla Kotlin 2.0+
    id("org.jetbrains.kotlin.plugin.compose") 
}

android {
    namespace = "com.example.bruceir" // Powrót do oryginału
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.bruceir" // Powrót do oryginału
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0_MTi"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    // FIX: Usunięto stare composeOptions, bo teraz plugin pilnuje wersji
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0-alpha01")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
}