plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
}
android {
    namespace = "com.drivedex.prototype"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        applicationId = "com.drivedex.prototype"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-demo"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
