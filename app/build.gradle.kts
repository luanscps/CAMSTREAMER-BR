plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23"
}

android {
    namespace = "com.camera2rtsp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.camera2rtsp"
        minSdk = 29
        targetSdk = 36
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // Ktor engine para Android (necessário pelo Supabase)
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

// kotlinx-serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

// Supabase Kotlin SDK
    implementation(platform("io.github.jan-tennermann:supabase-kt-bom:2.6.1"))
    implementation("io.github.jan-tennermann:postgrest-kt")
    implementation("io.github.jan-tennermann:gotrue-kt")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // NanoHTTPD
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RootEncoder 2.7.2 — resolvido via includeBuild em settings.gradle
    // O includeBuild substitui essa coordenada pelo modulo local ../RootEncoder
    implementation("com.github.pedroSG94.RootEncoder:library:2.7.2")

    // Armazenamento seguro (EncryptedSharedPreferences) para sub_license_key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // HTTP client para chamar o camui-panel (/api/activate, /api/license/validate)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}
