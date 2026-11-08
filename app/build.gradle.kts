plugins {
    id("com.android.application")
}

android {
    namespace = "com.e90os.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.e90os.launcher"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // FTDI / CH340 / PL2303 / CP210x USB serial drivers for K+DCAN and USB ELM327 adapters.
    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
}
