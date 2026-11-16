plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.heartlink.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Must match the phone app's applicationId so the two are treated as one app on Wear OS.
        applicationId = "com.heartlink.app"
        minSdk = libs.versions.watchMinSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so release builds install over debug ones, and the phone
            // and watch share a signature (needed for linking through the Data Layer). Use a real
            // keystore before publishing to the Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Libraries ship translations for ~80 languages; the app's own text is English only.
        localeFilters += "en"
    }
    packaging {
        // Compress the code in the APK (about 4.7 MB down to about 2 MB). Android 11 and later
        // store it uncompressed by default so it can run straight from the APK; compressed, it
        // is unpacked once when installed, which uses a little more storage on the watch.
        dex {
            useLegacyPackaging = true
        }
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Play Services pulls in an old Fragment that breaks ActivityResult permission requests.
    implementation(libs.androidx.fragment)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.input)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.health.services.client)
    implementation(libs.work.runtime)
    implementation(libs.guava)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}
