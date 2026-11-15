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
        versionCode = 102
        versionName = "1.2-iraqi-navigation-beta"
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
