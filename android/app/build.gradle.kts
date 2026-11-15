import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// drone.properties (not committed): DJI_API_KEY, RELAY_URL, RELAY_TOKEN
val secrets = Properties().apply {
    val f = rootProject.file("drone.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "top.mambabot.drone"
    compileSdk = 35

    defaultConfig {
        applicationId = "top.mambabot.drone"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "0.1"
        multiDexEnabled = true
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        manifestPlaceholders["DJI_API_KEY"] = secrets.getProperty("DJI_API_KEY", "")
        buildConfigField("String", "RELAY_URL", "\"${secrets.getProperty("RELAY_URL", "")}\"")
        buildConfigField("String", "RELAY_TOKEN", "\"${secrets.getProperty("RELAY_TOKEN", "")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    useLibrary("org.apache.http.legacy")

    packaging {
        // DJI's secneo loader needs the .so files extracted on install
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "*/*/libdjivideo.so", "*/*/libSDKRelativeJNI.so", "*/*/libFlyForbid.so",
                "*/*/libduml_vision_bokeh.so", "*/*/libyuv2.so", "*/*/libGroudStation.so",
                "*/*/libFRCorkscrew.so", "*/*/libUpgradeVerify.so", "*/*/libFR.so",
                "*/*/libDJIFlySafeCore.so", "*/*/libdjifs_jni.so", "*/*/libsfjni.so",
                "*/*/libDJICommonJNI.so", "*/*/libDJICSDKCommon.so", "*/*/libDJIUpgradeCore.so",
                "*/*/libDJIUpgradeJNI.so", "*/*/libDJIWaypointV2Core.so", "*/*/libDJIMOP.so",
                "*/*/libDJISDKLOGJNI.so",
            )
        }
        resources {
            excludes += listOf("META-INF/rxjava.properties", "assets/location_map_gps_locked.png",
                "assets/location_map_gps_3d.png")
        }
    }
}

dependencies {
    implementation("com.dji:dji-sdk:4.18") {
        exclude(module = "library-anti-distortion")
        exclude(module = "fly-safe-database")
    }
    compileOnly("com.dji:dji-sdk-provided:4.18")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
