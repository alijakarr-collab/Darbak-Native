plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "iq.darbak.nativegps"
    compileSdk = 35
    defaultConfig {
        applicationId = "iq.darbak.nativegps"
        minSdk = 26
        targetSdk = 34
        versionCode = 300
        versionName = "3.0-driving-themes-preview"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
}
