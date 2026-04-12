plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.camera2rtsp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.camera2rtsp"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // NanoHTTPD - servidor HTTP leve para WebControlServer
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RootEncoder — referencia direta ao commit SHA da tag 2.7.2
    // Motivo: JitPack pode nao ter buildado a tag ainda; o hash sempre funciona.
    // setCustomOnCaptureCompletedCallback foi adicionado na 2.6.7 (SHA: 6d45da3c3c)
    // Hash da 2.7.2: 37c49033fba2e09d4ec441c2d1d6413d6aedc0a3 (primeiros 10 digitos usados)
    implementation("com.github.pedroSG94.RootEncoder:library:37c49033fb")
}
