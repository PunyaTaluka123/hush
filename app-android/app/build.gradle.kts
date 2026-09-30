plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun prop(name: String) = "\"${providers.gradleProperty(name).get()}\""

android {
    namespace = "com.hush.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hush.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.0"
        buildConfigField("String", "FIREBASE_PROJECT_ID", prop("hush.firebase.projectId"))
        buildConfigField("String", "FIREBASE_APP_ID", prop("hush.firebase.appId"))
        buildConfigField("String", "FIREBASE_API_KEY", prop("hush.firebase.apiKey"))
        buildConfigField("String", "FIREBASE_SENDER_ID", prop("hush.firebase.senderId"))
        buildConfigField("String", "EMULATOR_HOST", prop("hush.emulatorHost"))
        manifestPlaceholders["webHost"] = providers.gradleProperty("hush.webHost").get()
        manifestPlaceholders["cleartext"] = "true"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            manifestPlaceholders["cleartext"] = "false"
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    val compose = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // No analytics SDKs (SPEC §13): auth, database, push only.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79") // argon2id, same parameters as the backend

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(compose)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
