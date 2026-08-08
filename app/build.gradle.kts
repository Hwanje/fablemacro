plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI에서 -PappVersionCode / -PappVersionName 으로 덮어쓴다 (릴리스 워크플로 참고)
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"

android {
    namespace = "com.fablemacro.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fablemacro.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // 자동 업데이트를 위해 모든 빌드를 동일한 키로 서명한다.
    // 서명이 달라지면 기존 앱 위에 덮어쓰기 설치가 거부된다.
    signingConfigs {
        create("shared") {
            storeFile = file("signing/fablemacro.keystore")
            storePassword = "fablemacro"
            keyAlias = "fablemacro"
            keyPassword = "fablemacro"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // 한국어 포함 온디바이스 OCR (텍스트 검색 / OCR 복사)
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
}
